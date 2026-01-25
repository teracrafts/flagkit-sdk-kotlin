package dev.flagkit.core

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.time.Instant
import java.util.UUID
import java.util.logging.Logger

/**
 * Status of a persisted event.
 */
enum class PersistedEventStatus(val value: String) {
    PENDING("pending"),
    SENDING("sending"),
    SENT("sent"),
    FAILED("failed");

    companion object {
        fun fromString(value: String): PersistedEventStatus {
            return entries.find { it.value == value } ?: PENDING
        }
    }
}

/**
 * A persisted event record in JSON Lines format.
 */
@Serializable
data class PersistedEvent(
    val id: String,
    val type: String? = null,
    val data: Map<String, String>? = null,
    val timestamp: Long = 0,
    val status: String,
    val userId: String? = null,
    val sessionId: String? = null,
    val environmentId: String? = null,
    val sdkVersion: String? = null,
    val sentAt: Long? = null
) {
    /**
     * Check if this is a full event (not just a status update).
     */
    fun isFullEvent(): Boolean = type != null && type.isNotEmpty()

    fun toEvent(): Event {
        @Suppress("UNCHECKED_CAST")
        return Event(
            eventType = type ?: "",
            timestamp = Instant.ofEpochMilli(timestamp),
            userId = userId,
            data = data as? Map<String, Any?>,
            sessionId = sessionId,
            environmentId = environmentId,
            sdkVersion = sdkVersion
        )
    }

    companion object {
        fun fromEvent(event: Event, id: String = UUID.randomUUID().toString()): PersistedEvent {
            val dataMap: Map<String, String>? = event.data?.mapValues { (_, value) ->
                value?.toString() ?: ""
            }
            return PersistedEvent(
                id = id,
                type = event.eventType,
                data = dataMap,
                timestamp = event.timestamp.toEpochMilli(),
                status = PersistedEventStatus.PENDING.value,
                userId = event.userId,
                sessionId = event.sessionId,
                environmentId = event.environmentId,
                sdkVersion = event.sdkVersion
            )
        }
    }
}

/**
 * Crash-resilient event persistence using Write-Ahead Logging (WAL).
 *
 * Events are persisted to disk before being queued for sending, ensuring
 * that analytics data is not lost during unexpected process termination.
 *
 * Features:
 * - JSON Lines format for append-only logging
 * - File locking for multi-process safety
 * - Buffered writes for performance
 * - Event recovery on initialization
 * - Automatic cleanup of old sent events
 *
 * @param storagePath Directory path for event storage files.
 * @param maxEvents Maximum number of events to persist.
 * @param flushIntervalMs Milliseconds between disk writes.
 * @param logger Logger for diagnostic messages.
 * @param scope Coroutine scope for background operations.
 */
class EventPersistence(
    private val storagePath: String,
    private val maxEvents: Int = DEFAULT_MAX_EVENTS,
    private val flushIntervalMs: Long = DEFAULT_FLUSH_INTERVAL_MS,
    private val logger: Logger = Logger.getLogger(EventPersistence::class.java.name),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private val mutex = Mutex()
    private val buffer = mutableListOf<PersistedEvent>()
    private val eventIds = mutableMapOf<String, PersistedEvent>()
    private var flushJob: Job? = null
    private var isRunning = false

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val storageDir: File by lazy {
        File(storagePath).also { dir ->
            if (!dir.exists()) {
                dir.mkdirs()
            }
        }
    }

    private val lockFile: File by lazy {
        File(storageDir, LOCK_FILE_NAME)
    }

    private val currentLogFile: File by lazy {
        val timestamp = System.currentTimeMillis()
        val random = UUID.randomUUID().toString().take(8)
        File(storageDir, "flagkit-events-$timestamp-$random.jsonl")
    }

    /**
     * Start the background flush timer.
     */
    suspend fun start() = mutex.withLock {
        if (isRunning) return@withLock

        isRunning = true
        flushJob = scope.launch {
            while (isActive && isRunning) {
                delay(flushIntervalMs)
                try {
                    flush()
                } catch (e: Exception) {
                    logger.warning("Failed to flush events to disk: ${e.message}")
                }
            }
        }
    }

    /**
     * Persist an event to the buffer.
     *
     * Events are written to disk when the buffer is full or on the next flush interval.
     *
     * @param event The event to persist.
     * @return The ID of the persisted event.
     */
    suspend fun persist(event: Event): String {
        val id = UUID.randomUUID().toString()
        val persistedEvent = PersistedEvent.fromEvent(event, id)

        val shouldFlush = mutex.withLock {
            // Check if we've reached max events
            if (eventIds.size >= maxEvents) {
                logger.warning("Max persisted events reached ($maxEvents), dropping oldest event")
                // Remove oldest pending event
                val oldestPending = eventIds.values
                    .filter { it.status == PersistedEventStatus.PENDING.value }
                    .minByOrNull { it.timestamp }

                oldestPending?.let {
                    eventIds.remove(it.id)
                    buffer.removeAll { b -> b.id == it.id }
                }
            }

            buffer.add(persistedEvent)
            eventIds[id] = persistedEvent

            buffer.size >= BUFFER_SIZE
        }

        if (shouldFlush) {
            try {
                flush()
            } catch (e: Exception) {
                logger.warning("Failed to flush events: ${e.message}")
            }
        }

        return id
    }

    /**
     * Flush buffered events to disk with file locking.
     */
    suspend fun flush() {
        val eventsToWrite = mutex.withLock {
            if (buffer.isEmpty()) return@flush
            buffer.toList().also { buffer.clear() }
        }

        withFileLock {
            currentLogFile.appendText(
                eventsToWrite.joinToString("\n") { json.encodeToString(it) } + "\n"
            )
        }
    }

    /**
     * Mark events as sent after successful transmission.
     *
     * @param eventIds List of event IDs that were successfully sent.
     */
    suspend fun markSent(eventIds: List<String>) {
        val sentAt = System.currentTimeMillis()

        mutex.withLock {
            eventIds.forEach { id ->
                this.eventIds[id]?.let { event ->
                    this.eventIds[id] = event.copy(
                        status = PersistedEventStatus.SENT.value,
                        sentAt = sentAt
                    )
                }
            }
        }

        // Write status updates to file
        withFileLock {
            val updates = eventIds.map { id ->
                """{"id":"$id","status":"sent","sentAt":$sentAt}"""
            }
            currentLogFile.appendText(updates.joinToString("\n") + "\n")
        }
    }

    /**
     * Mark events as sending (in progress).
     *
     * @param eventIds List of event IDs being sent.
     */
    suspend fun markSending(eventIds: List<String>) {
        mutex.withLock {
            eventIds.forEach { id ->
                this.eventIds[id]?.let { event ->
                    this.eventIds[id] = event.copy(
                        status = PersistedEventStatus.SENDING.value
                    )
                }
            }
        }
    }

    /**
     * Revert events to pending status after failed transmission.
     *
     * @param eventIds List of event IDs to revert.
     */
    suspend fun markPending(eventIds: List<String>) {
        mutex.withLock {
            eventIds.forEach { id ->
                this.eventIds[id]?.let { event ->
                    this.eventIds[id] = event.copy(
                        status = PersistedEventStatus.PENDING.value
                    )
                }
            }
        }
    }

    /**
     * Recover pending events from disk on startup.
     *
     * This reads all event files and returns events that are still pending
     * or were being sent when the process crashed.
     *
     * @return List of events to be re-queued.
     */
    suspend fun recover(): List<Event> {
        val recoveredEvents = mutableListOf<Event>()
        val eventStates = mutableMapOf<String, PersistedEvent>()

        withFileLock {
            // Read all event files
            val eventFiles = storageDir.listFiles { file ->
                file.name.startsWith("flagkit-events-") && file.name.endsWith(".jsonl")
            } ?: emptyArray()

            for (file in eventFiles.sortedBy { it.name }) {
                file.forEachLine { line ->
                    if (line.isBlank()) return@forEachLine

                    try {
                        val event = json.decodeFromString<PersistedEvent>(line)

                        // Check if this is a status update or full event
                        if (event.isFullEvent()) {
                            // Full event
                            eventStates[event.id] = event
                        } else {
                            // Status update - merge with existing
                            eventStates[event.id]?.let { existing ->
                                eventStates[event.id] = existing.copy(
                                    status = event.status,
                                    sentAt = event.sentAt
                                )
                            }
                        }
                    } catch (e: Exception) {
                        logger.warning("Failed to parse event line: $line - ${e.message}")
                    }
                }
            }
        }

        // Filter for pending/sending events (sending means crashed mid-send)
        mutex.withLock {
            eventStates.values
                .filter {
                    it.status == PersistedEventStatus.PENDING.value ||
                    it.status == PersistedEventStatus.SENDING.value
                }
                .forEach { event ->
                    eventIds[event.id] = event
                    recoveredEvents.add(event.toEvent())
                }
        }

        logger.info("Recovered ${recoveredEvents.size} pending events")
        return recoveredEvents
    }

    /**
     * Get the ID for an event (for tracking after recovery).
     *
     * @param event The event to look up.
     * @return The event ID if found, null otherwise.
     */
    suspend fun getEventId(event: Event): String? = mutex.withLock {
        eventIds.entries.find { (_, persistedEvent) ->
            persistedEvent.type == event.eventType &&
            persistedEvent.timestamp == event.timestamp.toEpochMilli()
        }?.key
    }

    /**
     * Get all event IDs for a list of events.
     *
     * @param events The events to look up.
     * @return Map of events to their IDs.
     */
    suspend fun getEventIds(events: List<Event>): Map<Event, String> {
        val result = mutableMapOf<Event, String>()

        mutex.withLock {
            events.forEach { event ->
                eventIds.entries.find { (_, persistedEvent) ->
                    persistedEvent.type == event.eventType &&
                    persistedEvent.timestamp == event.timestamp.toEpochMilli()
                }?.let { (id, _) ->
                    result[event] = id
                }
            }
        }

        return result
    }

    /**
     * Clean up old sent events from storage.
     *
     * This compacts event files by removing sent entries older than the retention period.
     *
     * @param retentionMs Retention period in milliseconds (default: 24 hours).
     */
    suspend fun cleanup(retentionMs: Long = DEFAULT_RETENTION_MS) {
        val cutoffTime = System.currentTimeMillis() - retentionMs

        withFileLock {
            val eventFiles = storageDir.listFiles { file ->
                file.name.startsWith("flagkit-events-") && file.name.endsWith(".jsonl")
            } ?: return@withFileLock

            // First pass: build merged state of all events across all files
            val eventStates = mutableMapOf<String, PersistedEvent>()
            val eventFileMap = mutableMapOf<String, MutableList<Pair<File, String>>>() // eventId -> list of (file, line)

            for (file in eventFiles.sortedBy { it.name }) {
                file.forEachLine { line ->
                    if (line.isBlank()) return@forEachLine

                    try {
                        val event = json.decodeFromString<PersistedEvent>(line)

                        // Track which files contain this event
                        eventFileMap.getOrPut(event.id) { mutableListOf() }.add(file to line)

                        if (event.isFullEvent()) {
                            eventStates[event.id] = event
                        } else {
                            // Status update - merge with existing
                            eventStates[event.id]?.let { existing ->
                                eventStates[event.id] = existing.copy(
                                    status = event.status,
                                    sentAt = event.sentAt
                                )
                            }
                        }
                    } catch (e: Exception) {
                        // Skip unparseable lines
                    }
                }
            }

            // Determine which event IDs should be removed
            val idsToRemove = eventStates.entries
                .filter { (_, event) ->
                    event.status == PersistedEventStatus.SENT.value &&
                    (event.sentAt ?: 0) <= cutoffTime
                }
                .map { it.key }
                .toSet()

            // Second pass: rewrite files without removed events
            for (file in eventFiles) {
                val linesToKeep = mutableListOf<String>()
                var hasChanges = false

                file.forEachLine { line ->
                    if (line.isBlank()) return@forEachLine

                    try {
                        val event = json.decodeFromString<PersistedEvent>(line)

                        if (event.id in idsToRemove) {
                            hasChanges = true
                        } else {
                            linesToKeep.add(line)
                        }
                    } catch (e: Exception) {
                        // Keep unparseable lines
                        linesToKeep.add(line)
                    }
                }

                if (hasChanges) {
                    if (linesToKeep.isEmpty()) {
                        file.delete()
                    } else {
                        file.writeText(linesToKeep.joinToString("\n") + "\n")
                    }
                }
            }
        }

        // Also clean up in-memory state
        mutex.withLock {
            val idsToRemove = eventIds.entries
                .filter { (_, event) ->
                    event.status == PersistedEventStatus.SENT.value &&
                    (event.sentAt ?: 0) <= cutoffTime
                }
                .map { it.key }

            idsToRemove.forEach { eventIds.remove(it) }
        }
    }

    /**
     * Close the persistence layer and flush remaining events.
     */
    suspend fun close() {
        mutex.withLock {
            isRunning = false
        }

        flushJob?.cancelAndJoin()
        flushJob = null

        // Final flush
        try {
            flush()
        } catch (e: Exception) {
            logger.warning("Failed to flush events on close: ${e.message}")
        }

        // Cleanup old sent events
        try {
            cleanup()
        } catch (e: Exception) {
            logger.warning("Failed to cleanup on close: ${e.message}")
        }
    }

    /**
     * Get statistics about the persistence layer.
     */
    suspend fun getStats(): Map<String, Any> = mutex.withLock {
        val pending = eventIds.values.count { it.status == PersistedEventStatus.PENDING.value }
        val sending = eventIds.values.count { it.status == PersistedEventStatus.SENDING.value }
        val sent = eventIds.values.count { it.status == PersistedEventStatus.SENT.value }

        mapOf(
            "totalEvents" to eventIds.size,
            "pendingEvents" to pending,
            "sendingEvents" to sending,
            "sentEvents" to sent,
            "bufferSize" to buffer.size,
            "storagePath" to storagePath
        )
    }

    /**
     * Execute a block with file locking.
     */
    private suspend fun <T> withFileLock(block: () -> T): T {
        return withContext(Dispatchers.IO) {
            var lock: FileLock? = null
            var channel: FileChannel? = null

            try {
                // Create lock file if it doesn't exist
                if (!lockFile.exists()) {
                    lockFile.createNewFile()
                }

                channel = RandomAccessFile(lockFile, "rw").channel
                lock = channel.lock()

                block()
            } finally {
                try {
                    lock?.release()
                } catch (e: Exception) {
                    logger.warning("Failed to release file lock: ${e.message}")
                }
                try {
                    channel?.close()
                } catch (e: Exception) {
                    logger.warning("Failed to close lock channel: ${e.message}")
                }
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_EVENTS = 10000
        const val DEFAULT_FLUSH_INTERVAL_MS = 1000L
        const val DEFAULT_RETENTION_MS = 24 * 60 * 60 * 1000L // 24 hours
        const val BUFFER_SIZE = 100
        const val LOCK_FILE_NAME = "flagkit-events.lock"
    }
}
