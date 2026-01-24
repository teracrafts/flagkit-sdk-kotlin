package dev.flagkit.http

import dev.flagkit.error.ErrorCode
import dev.flagkit.error.FlagKitException
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RetryTest {
    @Test
    fun `test calculateBackoff first attempt`() {
        val config = RetryConfig(
            maxAttempts = 3,
            baseDelay = 1.seconds,
            maxDelay = 30.seconds,
            backoffMultiplier = 2.0,
            jitterFactor = 0.0 // No jitter for deterministic test
        )

        val delay = calculateBackoff(1, config)

        assertEquals(1.seconds, delay)
    }

    @Test
    fun `test calculateBackoff exponential increase`() {
        val config = RetryConfig(
            maxAttempts = 5,
            baseDelay = 1.seconds,
            maxDelay = 30.seconds,
            backoffMultiplier = 2.0,
            jitterFactor = 0.0
        )

        val delay1 = calculateBackoff(1, config)
        val delay2 = calculateBackoff(2, config)
        val delay3 = calculateBackoff(3, config)

        assertEquals(1.seconds, delay1)
        assertEquals(2.seconds, delay2)
        assertEquals(4.seconds, delay3)
    }

    @Test
    fun `test calculateBackoff respects maxDelay`() {
        val config = RetryConfig(
            maxAttempts = 10,
            baseDelay = 1.seconds,
            maxDelay = 5.seconds,
            backoffMultiplier = 2.0,
            jitterFactor = 0.0
        )

        val delay = calculateBackoff(10, config)

        assertEquals(5.seconds, delay)
    }

    @Test
    fun `test calculateBackoff with jitter`() {
        val config = RetryConfig(
            maxAttempts = 3,
            baseDelay = 1.seconds,
            maxDelay = 30.seconds,
            backoffMultiplier = 2.0,
            jitterFactor = 0.1
        )

        val delay = calculateBackoff(1, config)

        // Should be between 1s and 1.1s
        assertTrue(delay.inWholeMilliseconds >= 1000)
        assertTrue(delay.inWholeMilliseconds <= 1100)
    }

    @Test
    fun `test isRetryableError with FlagKitException`() {
        val retryable = FlagKitException(ErrorCode.NETWORK_ERROR, "Network error")
        val notRetryable = FlagKitException(ErrorCode.AUTH_INVALID_KEY, "Invalid key")

        assertTrue(isRetryableError(retryable))
        assertFalse(isRetryableError(notRetryable))
    }

    @Test
    fun `test isRetryableError with network exceptions`() {
        assertTrue(isRetryableError(java.net.SocketTimeoutException()))
        assertTrue(isRetryableError(java.net.ConnectException()))
        assertTrue(isRetryableError(java.net.UnknownHostException()))
        assertTrue(isRetryableError(java.io.IOException()))
    }

    @Test
    fun `test isRetryableError with non-retryable exception`() {
        assertFalse(isRetryableError(IllegalArgumentException()))
        assertFalse(isRetryableError(NullPointerException()))
    }

    @Test
    fun `test withRetry success on first attempt`() = runTest {
        var attempts = 0
        val result = withRetry(
            config = RetryConfig(maxAttempts = 3, baseDelay = 10.milliseconds)
        ) {
            attempts++
            "success"
        }

        assertEquals("success", result)
        assertEquals(1, attempts)
    }

    @Test
    fun `test withRetry success after retry`() = runTest {
        var attempts = 0
        val result = withRetry(
            config = RetryConfig(maxAttempts = 3, baseDelay = 10.milliseconds)
        ) {
            attempts++
            if (attempts < 2) {
                throw java.io.IOException("Temporary error")
            }
            "success"
        }

        assertEquals("success", result)
        assertEquals(2, attempts)
    }

    @Test
    fun `test withRetry exhausts retries`() = runTest {
        var attempts = 0
        val exception = assertFailsWith<java.io.IOException> {
            withRetry(
                config = RetryConfig(maxAttempts = 3, baseDelay = 10.milliseconds)
            ) {
                attempts++
                throw java.io.IOException("Persistent error")
            }
        }

        assertEquals("Persistent error", exception.message)
        assertEquals(3, attempts)
    }

    @Test
    fun `test withRetry non-retryable error`() = runTest {
        var attempts = 0
        val exception = assertFailsWith<IllegalArgumentException> {
            withRetry(
                config = RetryConfig(maxAttempts = 3, baseDelay = 10.milliseconds)
            ) {
                attempts++
                throw IllegalArgumentException("Invalid input")
            }
        }

        assertEquals("Invalid input", exception.message)
        assertEquals(1, attempts)
    }

    @Test
    fun `test withRetry custom shouldRetry`() = runTest {
        var attempts = 0
        val exception = assertFailsWith<RuntimeException> {
            withRetry(
                config = RetryConfig(maxAttempts = 3, baseDelay = 10.milliseconds),
                shouldRetry = { it is RuntimeException && it.message == "retry me" }
            ) {
                attempts++
                if (attempts < 2) {
                    throw RuntimeException("retry me")
                }
                throw RuntimeException("don't retry")
            }
        }

        assertEquals("don't retry", exception.message)
        assertEquals(2, attempts)
    }

    @Test
    fun `test withRetry onRetry callback`() = runTest {
        val retryAttempts = mutableListOf<Int>()
        val result = withRetry(
            config = RetryConfig(maxAttempts = 3, baseDelay = 10.milliseconds),
            onRetry = { attempt, _, _ -> retryAttempts.add(attempt) }
        ) {
            if (retryAttempts.size < 2) {
                throw java.io.IOException("Error")
            }
            "success"
        }

        assertEquals("success", result)
        assertEquals(listOf(1, 2), retryAttempts)
    }

    @Test
    fun `test withRetryResult success`() = runTest {
        val result = withRetryResult(
            config = RetryConfig(maxAttempts = 3, baseDelay = 10.milliseconds)
        ) {
            "success"
        }

        assertTrue(result.success)
        assertEquals("success", result.value)
        assertEquals(1, result.attempts)
        assertNull(result.error)
    }

    @Test
    fun `test withRetryResult failure`() = runTest {
        val result = withRetryResult(
            config = RetryConfig(maxAttempts = 3, baseDelay = 10.milliseconds)
        ) {
            throw java.io.IOException("Error")
        }

        assertFalse(result.success)
        assertNull(result.value)
        assertEquals(3, result.attempts)
        assertNotNull(result.error)
    }

    @Test
    fun `test parseRetryAfter seconds`() {
        assertEquals(120L, parseRetryAfter("120"))
        assertEquals(60L, parseRetryAfter("60"))
        assertNull(parseRetryAfter("0"))
        assertNull(parseRetryAfter("-1"))
    }

    @Test
    fun `test parseRetryAfter null and empty`() {
        assertNull(parseRetryAfter(null))
        assertNull(parseRetryAfter(""))
        assertNull(parseRetryAfter("  "))
    }

    @Test
    fun `test parseRetryAfter invalid`() {
        assertNull(parseRetryAfter("invalid"))
        assertNull(parseRetryAfter("abc"))
    }

    @Test
    fun `test RetryConfig validation`() {
        assertFailsWith<IllegalArgumentException> {
            RetryConfig(maxAttempts = 0)
        }

        assertFailsWith<IllegalArgumentException> {
            RetryConfig(baseDelay = 0.milliseconds)
        }

        assertFailsWith<IllegalArgumentException> {
            RetryConfig(backoffMultiplier = 0.5)
        }

        assertFailsWith<IllegalArgumentException> {
            RetryConfig(jitterFactor = 1.5)
        }
    }
}
