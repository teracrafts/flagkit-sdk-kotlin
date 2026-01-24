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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * State of the polling manager.
 */
sealed class PollingState {
    /**
     * Polling is stopped and not running.
     */
    data object Stopped : PollingState()

    /**
     * Polling is actively running.
     *
     * @property startedAt When polling started.
     * @property lastPollAt When the last poll occurred.
     * @property consecutiveErrors Number of consecutive poll failures.
     */
    data class Running(
        val startedAt: Instant = Instant.now(),
        val lastPollAt: Instant? = null,
        val consecutiveErrors: Int = 0
    ) : PollingState()

    /**
     * Polling is paused (e.g., due to network unavailability).
     *
     * @property pausedAt When polling was paused.
     * @property reason Reason for pausing.
     */
    data class Paused(
        val pausedAt: Instant = Instant.now(),
        val reason: String? = null
    ) : PollingState()
}

/**
 * Configuration for polling behavior.
 *
 * @property interval Base polling interval (default: 30 seconds).
 * @property jitter Maximum jitter to add to interval (default: 1 second).
 * @property backoffMultiplier Multiplier for exponential backoff on errors (default: 2.0).
 * @property maxInterval Maximum interval after backoff (default: 5 minutes).
 * @property maxConsecutiveErrors Maximum consecutive errors before pausing (default: 10).
 */
data class PollingConfig(
    val interval: Duration = DEFAULT_INTERVAL,
    val jitter: Duration = DEFAULT_JITTER,
    val backoffMultiplier: Double = DEFAULT_BACKOFF_MULTIPLIER,
    val maxInterval: Duration = DEFAULT_MAX_INTERVAL,
    val maxConsecutiveErrors: Int = DEFAULT_MAX_CONSECUTIVE_ERRORS
) {
    init {
        require(interval.isPositive()) { "interval must be positive" }
        require(!jitter.isNegative()) { "jitter must not be negative" }
        require(backoffMultiplier >= 1.0) { "backoffMultiplier must be at least 1.0" }
        require(maxInterval >= interval) { "maxInterval must be at least interval" }
        require(maxConsecutiveErrors > 0) { "maxConsecutiveErrors must be positive" }
    }

    companion object {
        val DEFAULT_INTERVAL = 30.seconds
        val DEFAULT_JITTER = 1.seconds
        const val DEFAULT_BACKOFF_MULTIPLIER = 2.0
        val DEFAULT_MAX_INTERVAL = 5.minutes
        const val DEFAULT_MAX_CONSECUTIVE_ERRORS = 10
    }
}

/**
 * Result of a poll operation.
 *
 * @property success Whether the poll succeeded.
 * @property timestamp When the poll occurred.
 * @property error Error message if poll failed.
 * @property updatesFound Whether any updates were found.
 */
data class PollResult(
    val success: Boolean,
    val timestamp: Instant = Instant.now(),
    val error: String? = null,
    val updatesFound: Boolean = false
)

/**
 * Manages background polling for flag updates.
 *
 * Features:
 * - Configurable polling interval (default: 30 seconds)
 * - Jitter to prevent thundering herd
 * - Exponential backoff on errors
 * - State management using sealed class (Running, Stopped, Paused)
 * - Graceful pause/resume support
 * - Thread-safe operations using Mutex
 *
 * @param interval Base polling interval.
 * @param onUpdate Callback invoked on each poll with the last update time.
 * @param scope Coroutine scope for background operations.
 * @param config Full polling configuration (optional).
 */
class PollingManager(
    private val interval: Duration = PollingConfig.DEFAULT_INTERVAL,
    private val onUpdate: suspend (Instant?) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    private val config: PollingConfig = PollingConfig(interval = interval)
) {
    private val mutex = Mutex()
    private var job: Job? = null
    private var state: PollingState = PollingState.Stopped
    private var lastUpdateTime: Instant? = null
    private var consecutiveErrors = 0
    private var currentInterval: Duration = config.interval
    private var totalPolls: Long = 0
    private var successfulPolls: Long = 0
    private var failedPolls: Long = 0

    /**
     * Start polling.
     */
    suspend fun start() = mutex.withLock {
        if (state is PollingState.Running) return@withLock

        state = PollingState.Running()
        consecutiveErrors = 0
        currentInterval = config.interval
        job = scope.launch {
            pollingLoop()
        }
    }

    /**
     * Stop polling.
     */
    suspend fun stop() {
        mutex.withLock {
            state = PollingState.Stopped
        }
        job?.cancelAndJoin()
        job = null
    }

    /**
     * Pause polling with an optional reason.
     *
     * @param reason Reason for pausing (for debugging).
     */
    suspend fun pause(reason: String? = null) {
        mutex.withLock {
            if (state is PollingState.Running) {
                state = PollingState.Paused(reason = reason)
            }
        }
        job?.cancelAndJoin()
        job = null
    }

    /**
     * Resume polling after pause.
     */
    suspend fun resume() = mutex.withLock {
        if (state is PollingState.Paused) {
            state = PollingState.Running()
            job = scope.launch {
                pollingLoop()
            }
        }
    }

    /**
     * Check if polling is running.
     *
     * @return True if actively polling.
     */
    suspend fun isRunning(): Boolean = mutex.withLock {
        state is PollingState.Running
    }

    /**
     * Check if polling is paused.
     *
     * @return True if paused.
     */
    suspend fun isPaused(): Boolean = mutex.withLock {
        state is PollingState.Paused
    }

    /**
     * Check if polling is stopped.
     *
     * @return True if stopped.
     */
    suspend fun isStopped(): Boolean = mutex.withLock {
        state is PollingState.Stopped
    }

    /**
     * Get the current polling state.
     *
     * @return Current polling state.
     */
    suspend fun getState(): PollingState = mutex.withLock { state }

    /**
     * Get the last update time.
     *
     * @return Time of last successful poll, or null if none.
     */
    suspend fun getLastUpdateTime(): Instant? = mutex.withLock { lastUpdateTime }

    /**
     * Get the current polling interval (including backoff).
     *
     * @return Current interval duration.
     */
    suspend fun getCurrentInterval(): Duration = mutex.withLock { currentInterval }

    /**
     * Get polling statistics.
     *
     * @return Map of statistics.
     */
    suspend fun getStats(): Map<String, Any?> = mutex.withLock {
        mapOf(
            "state" to state::class.simpleName,
            "totalPolls" to totalPolls,
            "successfulPolls" to successfulPolls,
            "failedPolls" to failedPolls,
            "consecutiveErrors" to consecutiveErrors,
            "currentInterval" to currentInterval.inWholeMilliseconds,
            "lastUpdateTime" to lastUpdateTime?.toString()
        )
    }

    /**
     * Force an immediate poll.
     *
     * @return Result of the poll operation.
     */
    suspend fun pollNow(): PollResult {
        return performPoll()
    }

    /**
     * Reset the polling manager state.
     * Resets consecutive errors and interval without stopping.
     */
    suspend fun reset() = mutex.withLock {
        consecutiveErrors = 0
        currentInterval = config.interval
        if (state is PollingState.Running) {
            state = PollingState.Running()
        }
    }

    /**
     * Main polling loop.
     */
    private suspend fun pollingLoop() {
        while (mutex.withLock { state is PollingState.Running }) {
            val delayMs = currentIntervalWithJitter().inWholeMilliseconds
            delay(delayMs)

            if (mutex.withLock { state !is PollingState.Running }) break

            performPoll()
        }
    }

    /**
     * Perform a single poll.
     *
     * @return Result of the poll operation.
     */
    private suspend fun performPoll(): PollResult {
        mutex.withLock {
            totalPolls++
        }

        return try {
            onUpdate(lastUpdateTime)
            mutex.withLock {
                lastUpdateTime = Instant.now()
                consecutiveErrors = 0
                currentInterval = config.interval
                successfulPolls++
                if (state is PollingState.Running) {
                    state = PollingState.Running(
                        lastPollAt = lastUpdateTime,
                        consecutiveErrors = 0
                    )
                }
            }
            PollResult(success = true, updatesFound = true)
        } catch (e: Exception) {
            mutex.withLock {
                consecutiveErrors++
                failedPolls++
                applyBackoff()

                if (state is PollingState.Running) {
                    state = PollingState.Running(
                        lastPollAt = Instant.now(),
                        consecutiveErrors = consecutiveErrors
                    )
                }

                // Check if we should auto-pause
                if (consecutiveErrors >= config.maxConsecutiveErrors) {
                    state = PollingState.Paused(
                        reason = "Too many consecutive errors ($consecutiveErrors)"
                    )
                }
            }

            PollResult(
                success = false,
                error = e.message ?: "Unknown error",
                updatesFound = false
            )
        }
    }

    /**
     * Apply exponential backoff to the current interval.
     */
    private fun applyBackoff() {
        val multiplier = min(
            config.backoffMultiplier.pow(consecutiveErrors.toDouble()),
            MAX_BACKOFF_MULTIPLIER
        )
        val newInterval = (config.interval.inWholeMilliseconds * multiplier).toLong().milliseconds
        currentInterval = minOf(newInterval, config.maxInterval)
    }

    /**
     * Calculate the next delay with jitter.
     *
     * @return Interval with random jitter added.
     */
    private suspend fun currentIntervalWithJitter(): Duration {
        val base = mutex.withLock { currentInterval.inWholeMilliseconds }
        val jitter = (config.jitter.inWholeMilliseconds * Random.nextDouble()).toLong()
        return (base + jitter).milliseconds
    }

    companion object {
        private const val MAX_BACKOFF_MULTIPLIER = 16.0

        /**
         * Create a PollingManager with a configuration object.
         */
        fun create(
            config: PollingConfig,
            onUpdate: suspend (Instant?) -> Unit,
            scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        ): PollingManager {
            return PollingManager(
                interval = config.interval,
                onUpdate = onUpdate,
                scope = scope,
                config = config
            )
        }
    }
}
