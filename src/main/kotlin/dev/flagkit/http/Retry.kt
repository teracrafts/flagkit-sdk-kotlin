package dev.flagkit.http

import dev.flagkit.error.ErrorCode
import dev.flagkit.error.FlagKitException
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration for retry behavior.
 *
 * @property maxAttempts Maximum number of retry attempts (default: 3).
 * @property baseDelay Base delay between retries (default: 1 second).
 * @property maxDelay Maximum delay between retries (default: 30 seconds).
 * @property backoffMultiplier Multiplier for exponential backoff (default: 2.0).
 * @property jitterFactor Factor for jitter randomization (default: 0.1 = 10%).
 */
data class RetryConfig(
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    val baseDelay: Duration = DEFAULT_BASE_DELAY,
    val maxDelay: Duration = DEFAULT_MAX_DELAY,
    val backoffMultiplier: Double = DEFAULT_BACKOFF_MULTIPLIER,
    val jitterFactor: Double = DEFAULT_JITTER_FACTOR
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
        require(baseDelay.isPositive()) { "baseDelay must be positive" }
        require(maxDelay.isPositive()) { "maxDelay must be positive" }
        require(backoffMultiplier >= 1.0) { "backoffMultiplier must be at least 1.0" }
        require(jitterFactor >= 0.0 && jitterFactor <= 1.0) { "jitterFactor must be between 0.0 and 1.0" }
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 3
        val DEFAULT_BASE_DELAY = 1.seconds
        val DEFAULT_MAX_DELAY = 30.seconds
        const val DEFAULT_BACKOFF_MULTIPLIER = 2.0
        const val DEFAULT_JITTER_FACTOR = 0.1
    }
}

/**
 * Result of a retry operation.
 *
 * @param T The type of the successful result value.
 * @property success Whether the operation succeeded.
 * @property value The result value if successful.
 * @property error The error if unsuccessful.
 * @property attempts Number of attempts made.
 */
data class RetryResult<T>(
    val success: Boolean,
    val value: T? = null,
    val error: Throwable? = null,
    val attempts: Int
)

/**
 * Calculate the backoff delay for a given attempt.
 *
 * Uses exponential backoff with optional jitter:
 * delay = min(baseDelay * (multiplier ^ (attempt - 1)), maxDelay) + jitter
 *
 * @param attempt The current attempt number (1-based).
 * @param config The retry configuration.
 * @return The calculated delay duration.
 */
fun calculateBackoff(attempt: Int, config: RetryConfig): Duration {
    // Exponential backoff: baseDelay * (multiplier ^ (attempt - 1))
    val exponentialDelay = config.baseDelay.inWholeMilliseconds *
            config.backoffMultiplier.pow(attempt - 1)

    // Cap at maxDelay
    val cappedDelay = min(exponentialDelay, config.maxDelay.inWholeMilliseconds.toDouble())

    // Add jitter to prevent thundering herd
    val jitter = cappedDelay * config.jitterFactor * Random.nextDouble()

    return (cappedDelay + jitter).toLong().milliseconds
}

/**
 * Check if an error is retryable.
 *
 * @param error The error to check.
 * @return True if the error is retryable.
 */
fun isRetryableError(error: Throwable): Boolean {
    return when (error) {
        is FlagKitException -> error.isRecoverable
        is java.net.SocketTimeoutException -> true
        is java.net.ConnectException -> true
        is java.net.UnknownHostException -> true
        is java.io.IOException -> true
        else -> false
    }
}

/**
 * Check if a specific error code is retryable.
 *
 * @param code The error code to check.
 * @return True if the error code is retryable.
 */
fun isRetryableErrorCode(code: ErrorCode): Boolean {
    return code.isRecoverable
}

/**
 * Execute an operation with retry logic.
 *
 * Features:
 * - Exponential backoff with jitter
 * - Configurable retry attempts
 * - Custom retry predicate
 * - Callback on each retry
 *
 * @param T The type of the operation result.
 * @param config The retry configuration.
 * @param shouldRetry Custom predicate to determine if an error is retryable.
 * @param onRetry Callback invoked on each retry attempt.
 * @param operation The operation to execute.
 * @return The result of the operation.
 * @throws Throwable If all retry attempts fail.
 */
suspend fun <T> withRetry(
    config: RetryConfig = RetryConfig(),
    shouldRetry: ((Throwable) -> Boolean)? = null,
    onRetry: (suspend (attempt: Int, error: Throwable, delay: Duration) -> Unit)? = null,
    operation: suspend () -> T
): T {
    var lastError: Throwable? = null

    for (attempt in 1..config.maxAttempts) {
        try {
            return operation()
        } catch (e: Throwable) {
            lastError = e

            // Check if we should retry
            val canRetry = shouldRetry?.invoke(e) ?: isRetryableError(e)

            if (!canRetry) {
                throw e
            }

            // Check if we've exhausted retries
            if (attempt >= config.maxAttempts) {
                throw e
            }

            // Calculate and apply backoff
            val delay = calculateBackoff(attempt, config)

            // Invoke retry callback
            onRetry?.invoke(attempt, e, delay)

            // Wait before retrying
            delay(delay.inWholeMilliseconds)
        }
    }

    // This should never be reached, but Kotlin requires it
    throw lastError ?: FlagKitException(
        ErrorCode.NETWORK_RETRY_LIMIT,
        "Retry failed after ${config.maxAttempts} attempts"
    )
}

/**
 * Execute an operation with retry logic, returning a result wrapper.
 *
 * Unlike [withRetry], this function catches all exceptions and returns
 * a [RetryResult] indicating success or failure.
 *
 * @param T The type of the operation result.
 * @param config The retry configuration.
 * @param shouldRetry Custom predicate to determine if an error is retryable.
 * @param onRetry Callback invoked on each retry attempt.
 * @param operation The operation to execute.
 * @return A [RetryResult] containing the result or error.
 */
suspend fun <T> withRetryResult(
    config: RetryConfig = RetryConfig(),
    shouldRetry: ((Throwable) -> Boolean)? = null,
    onRetry: (suspend (attempt: Int, error: Throwable, delay: Duration) -> Unit)? = null,
    operation: suspend () -> T
): RetryResult<T> {
    var lastError: Throwable? = null
    var attempts = 0

    for (attempt in 1..config.maxAttempts) {
        attempts = attempt
        try {
            val result = operation()
            return RetryResult(
                success = true,
                value = result,
                attempts = attempts
            )
        } catch (e: Throwable) {
            lastError = e

            // Check if we should retry
            val canRetry = shouldRetry?.invoke(e) ?: isRetryableError(e)

            if (!canRetry || attempt >= config.maxAttempts) {
                return RetryResult(
                    success = false,
                    error = e,
                    attempts = attempts
                )
            }

            // Calculate and apply backoff
            val delay = calculateBackoff(attempt, config)

            // Invoke retry callback
            onRetry?.invoke(attempt, e, delay)

            // Wait before retrying
            delay(delay.inWholeMilliseconds)
        }
    }

    return RetryResult(
        success = false,
        error = lastError,
        attempts = attempts
    )
}

/**
 * Parse a Retry-After header value.
 *
 * The value can be either:
 * - A number of seconds (e.g., "120")
 * - An HTTP date (e.g., "Wed, 21 Oct 2015 07:28:00 GMT")
 *
 * @param value The Retry-After header value.
 * @return The number of seconds to wait, or null if parsing fails.
 */
fun parseRetryAfter(value: String?): Long? {
    if (value.isNullOrBlank()) return null

    // Try parsing as number of seconds
    value.toLongOrNull()?.let { seconds ->
        if (seconds > 0) return seconds
    }

    // Try parsing as HTTP date
    try {
        val format = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
        val date = format.parse(value)
        if (date != null) {
            val now = System.currentTimeMillis()
            val retryAt = date.time
            if (retryAt > now) {
                return (retryAt - now) / 1000
            }
        }
    } catch (_: Exception) {
        // Ignore parsing errors
    }

    return null
}

/**
 * Delay with exponential backoff.
 *
 * A convenience function for simple backoff delays without full retry logic.
 *
 * @param attempt The current attempt number (1-based).
 * @param config The retry configuration.
 */
suspend fun backoffDelay(attempt: Int, config: RetryConfig = RetryConfig()) {
    val delay = calculateBackoff(attempt, config)
    delay(delay.inWholeMilliseconds)
}
