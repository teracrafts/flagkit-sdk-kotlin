package dev.flagkit.core

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Manages background polling for flag updates.
 */
class PollingManager(
    private val interval: Duration,
    private val onUpdate: suspend (Instant?) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    private val mutex = Mutex()
    private var job: Job? = null
    private var isRunning = false
    private var lastUpdateTime: Instant? = null
    private var consecutiveErrors = 0

    suspend fun start() = mutex.withLock {
        if (isRunning) return@withLock

        isRunning = true
        job = scope.launch {
            pollingLoop()
        }
    }

    suspend fun stop() {
        mutex.withLock {
            isRunning = false
        }
        job?.cancelAndJoin()
        job = null
    }

    suspend fun isRunning(): Boolean = mutex.withLock { isRunning }

    suspend fun getLastUpdateTime(): Instant? = mutex.withLock { lastUpdateTime }

    suspend fun pollNow(): Boolean = performPoll()

    private suspend fun pollingLoop() {
        while (mutex.withLock { isRunning }) {
            delay(currentIntervalWithJitter().inWholeMilliseconds)
            if (!mutex.withLock { isRunning }) break
            performPoll()
        }
    }

    private suspend fun performPoll(): Boolean {
        return try {
            onUpdate(lastUpdateTime)
            mutex.withLock {
                lastUpdateTime = Instant.now()
                consecutiveErrors = 0
            }
            true
        } catch (e: Exception) {
            mutex.withLock {
                consecutiveErrors++
            }
            false
        }
    }

    private suspend fun currentIntervalWithJitter(): Duration {
        val multiplier = mutex.withLock {
            min(2.0.pow(consecutiveErrors), MAX_BACKOFF_MULTIPLIER)
        }
        val base = interval.inWholeMilliseconds * multiplier
        val jitter = base * 0.1 * Random.nextDouble()
        return (base + jitter).toLong().milliseconds
    }

    companion object {
        private const val MAX_BACKOFF_MULTIPLIER = 4.0
    }
}
