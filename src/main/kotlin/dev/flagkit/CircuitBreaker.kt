package dev.flagkit

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import kotlin.time.Duration

/**
 * Circuit breaker pattern implementation for resilient HTTP calls.
 */
class CircuitBreaker(
    private val failureThreshold: Int = 5,
    private val resetTimeout: Duration = kotlin.time.Duration.Companion.seconds(30)
) {
    enum class State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private val mutex = Mutex()
    private var state = State.CLOSED
    private var failureCount = 0
    private var lastFailureTime: Instant? = null

    suspend fun allowRequest(): Boolean = mutex.withLock {
        when (state) {
            State.CLOSED -> true
            State.OPEN -> {
                checkResetTimeout()
                state != State.OPEN
            }
            State.HALF_OPEN -> true
        }
    }

    suspend fun recordSuccess() = mutex.withLock {
        failureCount = 0
        state = State.CLOSED
    }

    suspend fun recordFailure() = mutex.withLock {
        failureCount++
        lastFailureTime = Instant.now()

        if (failureCount >= failureThreshold) {
            state = State.OPEN
        }
    }

    suspend fun isOpen(): Boolean = mutex.withLock {
        checkResetTimeout()
        state == State.OPEN
    }

    suspend fun isClosed(): Boolean = mutex.withLock {
        state == State.CLOSED
    }

    suspend fun isHalfOpen(): Boolean = mutex.withLock {
        checkResetTimeout()
        state == State.HALF_OPEN
    }

    suspend fun reset() = mutex.withLock {
        state = State.CLOSED
        failureCount = 0
        lastFailureTime = null
    }

    suspend fun getFailureCount(): Int = mutex.withLock { failureCount }

    suspend fun getState(): State = mutex.withLock {
        checkResetTimeout()
        state
    }

    suspend fun <T> execute(operation: suspend () -> T): T {
        if (!allowRequest()) {
            throw FlagKitException(ErrorCode.CIRCUIT_OPEN, "Circuit breaker is open")
        }

        return try {
            val result = operation()
            recordSuccess()
            result
        } catch (e: Exception) {
            recordFailure()
            throw e
        }
    }

    private fun checkResetTimeout() {
        if (state != State.OPEN) return
        val lastFailure = lastFailureTime ?: return

        val elapsed = java.time.Duration.between(lastFailure, Instant.now())
        if (elapsed.toMillis() >= resetTimeout.inWholeMilliseconds) {
            state = State.HALF_OPEN
        }
    }
}
