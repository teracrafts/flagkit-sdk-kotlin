package dev.flagkit.core

import dev.flagkit.error.ErrorCode
import dev.flagkit.error.FlagKitException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Event types supported by the SDK.
 */
enum class EventType(val value: String) {
    EVALUATION("evaluation"),
    IDENTIFY("identify"),
    TRACK("track"),
    PAGE_VIEW("page_view"),
    SDK_INITIALIZED("sdk_initialized"),
    FLAG_EVALUATED("flag_evaluated"),
    CONTEXT_CHANGED("context_changed"),
    CUSTOM("custom");

    companion object {
        fun fromString(value: String): EventType {
            return entries.find { it.value == value } ?: CUSTOM
        }
    }
}

/**
 * Represents an analytics event.
 *
 * @property eventType The type of event.
 * @property timestamp When the event occurred.
 * @property userId The user ID associated with the event.
 * @property data Additional event data.
 * @property sessionId The session ID.
 * @property environmentId The environment ID.
 * @property sdkVersion The SDK version.
 */
data class Event(
    val eventType: String,
    val timestamp: Instant = Instant.now(),
    val userId: String? = null,
    val data: Map<String, Any?>? = null,
    val sessionId: String? = null,
    val environmentId: String? = null,
    val sdkVersion: String? = null
) {
    /**
     * Convert to a map for serialization.
     */
    fun toMap(): Map<String, Any?> = buildMap {
        put("eventType", eventType)
        put("timestamp", timestamp.toString())
        userId?.let { put("userId", it) }
        data?.let { put("eventData", it) }
        sessionId?.let { put("sessionId", it) }
        environmentId?.let { put("environmentId", it) }
        sdkVersion?.let { put("sdkVersion", it) }
    }
}

/**
 * Configuration for the event queue.
 *
 * @property batchSize Maximum events before auto-flush (default: 10).
 * @property flushInterval Time between automatic flushes (default: 30 seconds).
 * @property maxQueueSize Maximum queue size (default: 1000).
 * @property maxRetries Maximum retry attempts for failed flushes (default: 3).
 * @property enabledEventTypes Event types to track (default: all).
 * @property disabledEventTypes Event types to ignore (default: none).
 * @property sampleRate Sampling rate for events (0.0-1.0, default: 1.0).
 */
data class EventQueueConfig(
    val batchSize: Int = DEFAULT_BATCH_SIZE,
    val flushInterval: Duration = DEFAULT_FLUSH_INTERVAL,
    val maxQueueSize: Int = DEFAULT_MAX_QUEUE_SIZE,
    val maxRetries: Int = DEFAULT_MAX_RETRIES,
    val enabledEventTypes: Set<String> = setOf("*"),
    val disabledEventTypes: Set<String> = emptySet(),
    val sampleRate: Double = 1.0
) {
    init {
        require(batchSize > 0) { "batchSize must be positive" }
        require(flushInterval.isPositive()) { "flushInterval must be positive" }
        require(maxQueueSize > 0) { "maxQueueSize must be positive" }
        require(sampleRate in 0.0..1.0) { "sampleRate must be between 0.0 and 1.0" }
    }

    companion object {
        const val DEFAULT_BATCH_SIZE = 10
        val DEFAULT_FLUSH_INTERVAL = 30.seconds
        const val DEFAULT_MAX_QUEUE_SIZE = 1000
        const val DEFAULT_MAX_RETRIES = 3
    }
}

/**
 * Batches and sends analytics events.
 *
 * Features:
 * - Configurable batch size (default: 10 events)
 * - Automatic flush interval (default: 30 seconds)
 * - Retry on failure with re-queuing
 * - Event type filtering and sampling
 * - Graceful shutdown with final flush
 * - Thread-safe operations using Mutex
 *
 * @param batchSize Maximum events before auto-flush.
 * @param flushInterval Time between automatic flushes.
 * @param onFlush Callback to send events to the server.
 * @param scope Coroutine scope for background operations.
 */
class EventQueue(
    private val batchSize: Int = EventQueueConfig.DEFAULT_BATCH_SIZE,
    private val flushInterval: Duration = EventQueueConfig.DEFAULT_FLUSH_INTERVAL,
    private val onFlush: suspend (List<Map<String, Any?>>) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    private val config: EventQueueConfig = EventQueueConfig(batchSize = batchSize, flushInterval = flushInterval),
    private val eventPersistence: EventPersistence? = null
) {
    private val mutex = Mutex()
    private val queue = mutableListOf<Event>()
    private var job: Job? = null
    private var isRunning = false
    private var flushCount: Long = 0
    private var eventCount: Long = 0
    private var errorCount: Long = 0

    /**
     * Start the event queue background processing.
     */
    suspend fun start() {
        // Start persistence layer if enabled
        eventPersistence?.start()

        // Recover any pending events from previous runs
        eventPersistence?.let { persistence ->
            val recoveredEvents = persistence.recover()
            if (recoveredEvents.isNotEmpty()) {
                mutex.withLock {
                    // Add recovered events at the beginning of the queue (priority)
                    queue.addAll(0, recoveredEvents)
                }
            }
        }

        mutex.withLock {
            if (isRunning) return@withLock

            isRunning = true
            job = scope.launch {
                flushLoop()
            }
        }
    }

    /**
     * Stop the event queue and flush remaining events.
     */
    suspend fun stop() {
        mutex.withLock {
            isRunning = false
        }
        job?.cancelAndJoin()
        job = null
        flush()

        // Close persistence layer
        eventPersistence?.close()
    }

    /**
     * Add a raw event map to the queue.
     *
     * @param event The event data as a map.
     */
    suspend fun enqueue(event: Map<String, Any?>) {
        val eventType = event["type"] as? String ?: event["eventType"] as? String ?: "custom"

        // Check if event type is enabled
        if (!isEventTypeEnabled(eventType)) return

        // Apply sampling
        if (!shouldSample()) return

        @Suppress("UNCHECKED_CAST")
        val eventData = event["data"] as? Map<String, Any?> ?: event["eventData"] as? Map<String, Any?>

        val wrappedEvent = Event(
            eventType = eventType,
            timestamp = Instant.now(),
            userId = event["userId"] as? String,
            data = eventData,
            sessionId = event["sessionId"] as? String,
            environmentId = event["environmentId"] as? String,
            sdkVersion = event["sdkVersion"] as? String
        )

        addToQueue(wrappedEvent)
    }

    /**
     * Add a typed event to the queue.
     *
     * @param event The event to add.
     */
    suspend fun enqueue(event: Event) {
        // Check if event type is enabled
        if (!isEventTypeEnabled(event.eventType)) return

        // Apply sampling
        if (!shouldSample()) return

        addToQueue(event)
    }

    /**
     * Track a custom event.
     *
     * @param eventType The type of event.
     * @param data Optional event data.
     * @param userId Optional user ID.
     */
    suspend fun track(
        eventType: String,
        data: Map<String, Any?>? = null,
        userId: String? = null
    ) {
        val event = Event(
            eventType = eventType,
            data = data,
            userId = userId
        )
        enqueue(event)
    }

    /**
     * Flush all pending events immediately.
     */
    suspend fun flush() {
        val events = mutex.withLock {
            if (queue.isEmpty()) return@flush
            val copy = queue.toList()
            queue.clear()
            copy
        }

        // Get event IDs for persistence tracking
        val eventIds = eventPersistence?.getEventIds(events) ?: emptyMap()

        // Mark events as sending in persistence layer
        if (eventIds.isNotEmpty()) {
            eventPersistence?.markSending(eventIds.values.toList())
        }

        try {
            val eventMaps = events.map { it.toMap() }
            onFlush(eventMaps)

            // Mark events as sent in persistence layer
            if (eventIds.isNotEmpty()) {
                eventPersistence?.markSent(eventIds.values.toList())
            }

            mutex.withLock {
                flushCount++
            }
        } catch (e: Exception) {
            mutex.withLock {
                errorCount++
            }

            // Revert to pending in persistence layer
            if (eventIds.isNotEmpty()) {
                eventPersistence?.markPending(eventIds.values.toList())
            }

            // Re-queue events on failure (up to max size)
            requeue(events)
            throw FlagKitException(ErrorCode.EVENT_FLUSH_FAILED, "Failed to flush events: ${e.message}", e)
        }
    }

    /**
     * Force flush without throwing exceptions.
     *
     * @return True if flush succeeded, false otherwise.
     */
    suspend fun tryFlush(): Boolean {
        return try {
            flush()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get the current queue size.
     *
     * @return Number of events in the queue.
     */
    suspend fun size(): Int = mutex.withLock { queue.size }

    /**
     * Check if the queue is running.
     *
     * @return True if the background processor is running.
     */
    suspend fun isRunning(): Boolean = mutex.withLock { isRunning }

    /**
     * Get queue statistics.
     *
     * @return Map of statistics.
     */
    suspend fun getStats(): Map<String, Any> = mutex.withLock {
        mapOf(
            "queueSize" to queue.size,
            "eventCount" to eventCount,
            "flushCount" to flushCount,
            "errorCount" to errorCount,
            "isRunning" to isRunning
        )
    }

    /**
     * Get queued events (for debugging).
     *
     * @return List of events currently in the queue.
     */
    suspend fun getQueuedEvents(): List<Event> = mutex.withLock {
        queue.toList()
    }

    /**
     * Clear the event queue without flushing.
     */
    suspend fun clear() = mutex.withLock {
        queue.clear()
    }

    /**
     * Add an event to the queue.
     */
    private suspend fun addToQueue(event: Event) {
        // Persist event BEFORE adding to queue (crash-safe)
        eventPersistence?.persist(event)

        val shouldFlush = mutex.withLock {
            eventCount++

            // Enforce max queue size
            if (queue.size >= config.maxQueueSize) {
                // Drop oldest event
                queue.removeAt(0)
            }

            queue.add(event)
            queue.size >= batchSize
        }

        if (shouldFlush) {
            try {
                flush()
            } catch (e: Exception) {
                // Ignore flush errors in add - they're handled in flush()
            }
        }
    }

    /**
     * Re-queue events that failed to send.
     */
    private suspend fun requeue(events: List<Event>) = mutex.withLock {
        val availableSpace = config.maxQueueSize - queue.size
        val eventsToRequeue = events.take(availableSpace)
        queue.addAll(0, eventsToRequeue)
    }

    /**
     * Check if an event type is enabled.
     */
    private fun isEventTypeEnabled(eventType: String): Boolean {
        // Check disabled list first
        if (config.disabledEventTypes.contains(eventType)) {
            return false
        }

        // Check enabled list
        return config.enabledEventTypes.contains("*") ||
                config.enabledEventTypes.contains(eventType)
    }

    /**
     * Apply sampling to determine if event should be recorded.
     */
    private fun shouldSample(): Boolean {
        if (config.sampleRate >= 1.0) return true
        if (config.sampleRate <= 0.0) return false
        return Math.random() < config.sampleRate
    }

    /**
     * Background flush loop.
     */
    private suspend fun flushLoop() {
        while (mutex.withLock { isRunning }) {
            delay(flushInterval.inWholeMilliseconds)
            if (!mutex.withLock { isRunning }) break
            try {
                flush()
            } catch (e: Exception) {
                // Continue despite flush errors
            }
        }
    }

    companion object {
        /**
         * Create an EventQueue with a configuration object.
         */
        fun create(
            config: EventQueueConfig,
            onFlush: suspend (List<Map<String, Any?>>) -> Unit,
            scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
            eventPersistence: EventPersistence? = null
        ): EventQueue {
            return EventQueue(
                batchSize = config.batchSize,
                flushInterval = config.flushInterval,
                onFlush = onFlush,
                scope = scope,
                config = config,
                eventPersistence = eventPersistence
            )
        }
    }
}
