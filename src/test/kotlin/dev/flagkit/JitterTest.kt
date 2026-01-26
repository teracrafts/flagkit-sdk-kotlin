package dev.flagkit

import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

class JitterTest {

    @Test
    fun `test jitter is NOT applied when disabled (default)`() = runTest {
        val options = FlagKitOptions.builder("sdk_test_key")
            .cacheEnabled(false)
            .eventsEnabled(false)
            .build()

        // Verify jitter is disabled by default
        assertFalse(options.evaluationJitter.enabled)
        assertEquals(5L, options.evaluationJitter.minMs)
        assertEquals(15L, options.evaluationJitter.maxMs)

        val client = FlagKitClient(options)

        // Measure evaluation time without jitter - should be fast
        val duration = measureTime {
            repeat(5) {
                client.getBoolValue("test-flag", false)
            }
        }

        // Without jitter, 5 evaluations should take less than 50ms total
        // (allowing for some overhead but way less than 5 * 5ms = 25ms minimum if jitter were applied)
        assertTrue(
            duration.inWholeMilliseconds < 50,
            "Evaluations took ${duration.inWholeMilliseconds}ms, expected < 50ms without jitter"
        )

        client.close()
    }

    @Test
    fun `test jitter IS applied when enabled`() = runTest {
        val jitterConfig = EvaluationJitterConfig(
            enabled = true,
            minMs = 10,
            maxMs = 20
        )

        val options = FlagKitOptions.builder("sdk_test_key")
            .evaluationJitter(jitterConfig)
            .cacheEnabled(false)
            .eventsEnabled(false)
            .build()

        assertTrue(options.evaluationJitter.enabled)

        val client = FlagKitClient(options)

        // Measure evaluation time with jitter
        val duration = measureTime {
            repeat(3) {
                client.getBoolValue("test-flag", false)
            }
        }

        // With jitter enabled (10-20ms), 3 evaluations should take at least 30ms
        assertTrue(
            duration.inWholeMilliseconds >= 30,
            "Evaluations took ${duration.inWholeMilliseconds}ms, expected >= 30ms with jitter (3 * 10ms min)"
        )

        client.close()
    }

    @Test
    fun `test timing falls within min and max range`() = runTest {
        val minMs = 15L
        val maxMs = 25L

        val jitterConfig = EvaluationJitterConfig(
            enabled = true,
            minMs = minMs,
            maxMs = maxMs
        )

        val options = FlagKitOptions.builder("sdk_test_key")
            .evaluationJitter(jitterConfig)
            .cacheEnabled(false)
            .eventsEnabled(false)
            .build()

        val client = FlagKitClient(options)

        // Collect individual evaluation times
        val evaluationTimes = mutableListOf<Long>()

        repeat(10) {
            val duration = measureTime {
                client.getBoolValue("test-flag-$it", false)
            }
            evaluationTimes.add(duration.inWholeMilliseconds)
        }

        // Each evaluation should take at least minMs (minus small overhead tolerance)
        evaluationTimes.forEach { time ->
            assertTrue(
                time >= minMs - 2,
                "Evaluation time $time ms was less than minimum jitter $minMs ms (with 2ms tolerance)"
            )
        }

        // The average should be reasonably close to the expected range midpoint
        // Expected average jitter: (15 + 25) / 2 = 20ms
        val averageTime = evaluationTimes.average()
        assertTrue(
            averageTime >= minMs - 2 && averageTime <= maxMs + 50, // allow for some overhead
            "Average evaluation time $averageTime ms was outside expected range"
        )

        client.close()
    }

    @Test
    fun `test EvaluationJitterConfig defaults`() {
        val config = EvaluationJitterConfig()

        assertFalse(config.enabled)
        assertEquals(5L, config.minMs)
        assertEquals(15L, config.maxMs)
    }

    @Test
    fun `test EvaluationJitterConfig custom values`() {
        val config = EvaluationJitterConfig(
            enabled = true,
            minMs = 10,
            maxMs = 50
        )

        assertTrue(config.enabled)
        assertEquals(10L, config.minMs)
        assertEquals(50L, config.maxMs)
    }

    @Test
    fun `test FlagKitOptions builder with jitter config`() {
        val jitterConfig = EvaluationJitterConfig(
            enabled = true,
            minMs = 8,
            maxMs = 12
        )

        val options = FlagKitOptions.builder("sdk_test_key")
            .evaluationJitter(jitterConfig)
            .build()

        assertTrue(options.evaluationJitter.enabled)
        assertEquals(8L, options.evaluationJitter.minMs)
        assertEquals(12L, options.evaluationJitter.maxMs)
    }

    @Test
    fun `test FlagKitOptions constructor with jitter config`() {
        val jitterConfig = EvaluationJitterConfig(
            enabled = true,
            minMs = 20,
            maxMs = 30
        )

        val options = FlagKitOptions(
            apiKey = "sdk_test_key",
            evaluationJitter = jitterConfig
        )

        assertTrue(options.evaluationJitter.enabled)
        assertEquals(20L, options.evaluationJitter.minMs)
        assertEquals(30L, options.evaluationJitter.maxMs)
    }
}
