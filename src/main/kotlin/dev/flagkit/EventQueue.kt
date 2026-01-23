package dev.flagkit

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration

/**
 * Batches and sends analytics events.
 */
class EventQueue(
    private val batchSize: Int,
    private val flushInterval: Duration,
    private val onFlush: suspend (List<Map<String, Any?>>) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    private val mutex = Mutex()
    private val queue = mutableListOf<Map<String, Any?>>()
    private var job: Job? = null
    private var isRunning = false

    suspend fun start() = mutex.withLock {
        if (isRunning) return@withLock

        isRunning = true
        job = scope.launch {
            flushLoop()
        }
    }

    suspend fun stop() {
        mutex.withLock {
            isRunning = false
        }
        job?.cancelAndJoin()
        job = null
        flush()
    }

    suspend fun enqueue(event: Map<String, Any?>) {
        val shouldFlush = mutex.withLock {
            queue.add(event)
            queue.size >= batchSize
        }

        if (shouldFlush) {
            flush()
        }
    }

    suspend fun flush() {
        val events = mutex.withLock {
            if (queue.isEmpty()) return@flush
            val copy = queue.toList()
            queue.clear()
            copy
        }

        try {
            onFlush(events)
        } catch (e: Exception) {
            // Re-queue events on failure
            mutex.withLock {
                queue.addAll(0, events)
            }
        }
    }

    suspend fun size(): Int = mutex.withLock { queue.size }

    suspend fun isRunning(): Boolean = mutex.withLock { isRunning }

    private suspend fun flushLoop() {
        while (mutex.withLock { isRunning }) {
            delay(flushInterval.inWholeMilliseconds)
            if (!mutex.withLock { isRunning }) break
            flush()
        }
    }
}
