package dev.flagkit

import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class FlagKitOptionsTest {
    @Test
    fun `test isLocal defaults to false`() {
        val options = FlagKitOptions(apiKey = "sdk_test_key")
        assertFalse(options.isLocal)
    }

    @Test
    fun `test isLocal can be set to true`() {
        val options = FlagKitOptions(apiKey = "sdk_test_key", isLocal = true)
        assertTrue(options.isLocal)
    }

    @Test
    fun `test builder isLocal with boolean parameter`() {
        val options = FlagKitOptions.builder("sdk_test_key")
            .isLocal(true)
            .build()
        assertTrue(options.isLocal)
    }

    @Test
    fun `test builder isLocal no-arg sets true`() {
        val options = FlagKitOptions.builder("sdk_test_key")
            .isLocal()
            .build()
        assertTrue(options.isLocal)
    }

    @Test
    fun `test builder isLocal defaults to false`() {
        val options = FlagKitOptions.builder("sdk_test_key")
            .build()
        assertFalse(options.isLocal)
    }

    @Test
    fun `test builder isLocal can be set to false`() {
        val options = FlagKitOptions.builder("sdk_test_key")
            .isLocal(false)
            .build()
        assertFalse(options.isLocal)
    }

    @Test
    fun `test builder with all options including isLocal`() {
        val options = FlagKitOptions.builder("sdk_test_key")
            .pollingInterval(60.seconds)
            .cacheTtl(600.seconds)
            .cacheEnabled(true)
            .eventsEnabled(false)
            .isLocal(true)
            .build()

        assertEquals("sdk_test_key", options.apiKey)
        assertEquals(60.seconds, options.pollingInterval)
        assertEquals(600.seconds, options.cacheTtl)
        assertTrue(options.cacheEnabled)
        assertFalse(options.eventsEnabled)
        assertTrue(options.isLocal)
    }
}
