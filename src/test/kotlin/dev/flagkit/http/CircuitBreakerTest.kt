package dev.flagkit.http

import dev.flagkit.error.ErrorCode
import dev.flagkit.error.FlagKitException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

class CircuitBreakerTest {
    @Test
    fun `test initial state is closed`() = runTest {
        val breaker = CircuitBreaker(failureThreshold = 3, resetTimeout = 200.milliseconds)
        assertTrue(breaker.isClosed())
    }

    @Test
    fun `test allows request when closed`() = runTest {
        val breaker = CircuitBreaker(failureThreshold = 3, resetTimeout = 200.milliseconds)
        assertTrue(breaker.allowRequest())
    }

    @Test
    fun `test opens after threshold`() = runTest {
        val breaker = CircuitBreaker(failureThreshold = 3, resetTimeout = 200.milliseconds)

        breaker.recordFailure()
        breaker.recordFailure()
        breaker.recordFailure()

        assertTrue(breaker.isOpen())
    }

    @Test
    fun `test does not open before threshold`() = runTest {
        val breaker = CircuitBreaker(failureThreshold = 3, resetTimeout = 200.milliseconds)

        breaker.recordFailure()
        breaker.recordFailure()

        assertFalse(breaker.isOpen())
        assertTrue(breaker.isClosed())
    }

    @Test
    fun `test record success resets failure count`() = runTest {
        val breaker = CircuitBreaker(failureThreshold = 3, resetTimeout = 200.milliseconds)

        breaker.recordFailure()
        breaker.recordFailure()
        breaker.recordSuccess()

        assertEquals(0, breaker.getFailureCount())
    }

    @Test
    fun `test reset`() = runTest {
        val breaker = CircuitBreaker(failureThreshold = 3, resetTimeout = 200.milliseconds)

        breaker.recordFailure()
        breaker.recordFailure()
        breaker.recordFailure()
        breaker.reset()

        assertTrue(breaker.isClosed())
        assertEquals(0, breaker.getFailureCount())
    }

    @Test
    fun `test half open after timeout`() = runTest {
        val breaker = CircuitBreaker(failureThreshold = 3, resetTimeout = 50.milliseconds)

        breaker.recordFailure()
        breaker.recordFailure()
        breaker.recordFailure()

        // Use Thread.sleep for real time delay
        @Suppress("BlockingMethodInNonBlockingContext")
        Thread.sleep(100)

        assertTrue(breaker.isHalfOpen())
    }

    @Test
    fun `test closes from half open on success`() = runTest {
        val breaker = CircuitBreaker(failureThreshold = 3, resetTimeout = 50.milliseconds)

        breaker.recordFailure()
        breaker.recordFailure()
        breaker.recordFailure()

        // Use Thread.sleep for real time delay
        @Suppress("BlockingMethodInNonBlockingContext")
        Thread.sleep(100)

        breaker.isHalfOpen()
        breaker.recordSuccess()

        assertTrue(breaker.isClosed())
    }

    @Test
    fun `test execute success`() = runTest {
        val breaker = CircuitBreaker(failureThreshold = 3, resetTimeout = 200.milliseconds)

        val result = breaker.execute { "success" }

        assertEquals("success", result)
    }

    @Test
    fun `test execute failure`() = runTest {
        val breaker = CircuitBreaker(failureThreshold = 3, resetTimeout = 200.milliseconds)

        assertFailsWith<RuntimeException> {
            breaker.execute { throw RuntimeException("error") }
        }

        assertEquals(1, breaker.getFailureCount())
    }

    @Test
    fun `test execute when open`() = runTest {
        val breaker = CircuitBreaker(failureThreshold = 3, resetTimeout = 30000.milliseconds)

        breaker.recordFailure()
        breaker.recordFailure()
        breaker.recordFailure()

        val exception = assertFailsWith<FlagKitException> {
            breaker.execute { "success" }
        }

        assertEquals(ErrorCode.CIRCUIT_OPEN, exception.errorCode)
    }
}
