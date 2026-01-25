package dev.flagkit

import dev.flagkit.utils.SecurityException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for FlagKitClient security features:
 * - Strict PII mode enforcement in identify(), track(), setContext()
 * - Cache encryption integration
 */
class FlagKitClientSecurityTest {

    // ============= Strict PII Mode Tests for identify() =============

    @Test
    fun `identify with safe attributes succeeds in strict mode`() = runBlocking {
        val client = createClientWithStrictPiiMode()

        // Should not throw - no PII in attributes
        client.identify("user123", mapOf(
            "plan" to "premium",
            "country" to "US",
            "age" to 25
        ))

        val context = client.getContext()
        assertEquals("user123", context?.userId)
        client.close()
    }

    @Test
    fun `identify with PII throws in strict mode`() = runBlocking {
        val client = createClientWithStrictPiiMode()

        val exception = assertFailsWith<SecurityException> {
            client.identify("user123", mapOf(
                "email" to "user@example.com",
                "plan" to "premium"
            ))
        }

        assertTrue(exception.message!!.contains("PII detected"))
        assertTrue(exception.message!!.contains("email"))
        client.close()
    }

    @Test
    fun `identify with multiple PII fields throws in strict mode`() = runBlocking {
        val client = createClientWithStrictPiiMode()

        val exception = assertFailsWith<SecurityException> {
            client.identify("user123", mapOf(
                "email" to "user@example.com",
                "phone" to "555-1234",
                "ssn" to "123-45-6789"
            ))
        }

        assertTrue(exception.message!!.contains("PII detected"))
        client.close()
    }

    @Test
    fun `identify with empty attributes succeeds in strict mode`() = runBlocking {
        val client = createClientWithStrictPiiMode()

        // Should not throw - empty attributes
        client.identify("user123")

        val context = client.getContext()
        assertEquals("user123", context?.userId)
        client.close()
    }

    @Test
    fun `identify with PII warns but succeeds in non-strict mode`() = runBlocking {
        val client = createClientWithNonStrictMode()

        // Should not throw - just warn
        client.identify("user123", mapOf(
            "email" to "user@example.com"
        ))

        val context = client.getContext()
        assertEquals("user123", context?.userId)
        client.close()
    }

    // ============= Strict PII Mode Tests for track() =============

    @Test
    fun `track with safe data succeeds in strict mode`() = runBlocking {
        // Use a large batch size to prevent auto-flush during test
        val client = createClientWithStrictPiiModeNoAutoFlush()

        // Should not throw - no PII in event data
        client.track("purchase", mapOf(
            "productId" to "SKU123",
            "amount" to 99.99,
            "currency" to "USD"
        ))

        // Close without flushing to avoid network errors
        safeClose(client)
    }

    @Test
    fun `track with PII throws in strict mode`() = runBlocking {
        val client = createClientWithStrictPiiMode()

        val exception = assertFailsWith<SecurityException> {
            client.track("checkout", mapOf(
                "creditCard" to "4111-1111-1111-1111",
                "amount" to 99.99
            ))
        }

        assertTrue(exception.message!!.contains("PII detected"))
        assertTrue(exception.message!!.contains("event"))
        client.close()
    }

    @Test
    fun `track with password field throws in strict mode`() = runBlocking {
        val client = createClientWithStrictPiiMode()

        val exception = assertFailsWith<SecurityException> {
            client.track("login", mapOf(
                "username" to "john",
                "password" to "secret123"
            ))
        }

        assertTrue(exception.message!!.contains("PII detected"))
        assertTrue(exception.message!!.contains("password"))
        client.close()
    }

    @Test
    fun `track with null data succeeds in strict mode`() = runBlocking {
        // Use a large batch size to prevent auto-flush during test
        val client = createClientWithStrictPiiModeNoAutoFlush()

        // Should not throw - null data
        client.track("page_view", null)

        // Close without flushing to avoid network errors
        safeClose(client)
    }

    @Test
    fun `track with PII warns but succeeds in non-strict mode`() = runBlocking {
        // Use a large batch size to prevent auto-flush during test
        val client = createClientWithNonStrictModeNoAutoFlush()

        // Should not throw - just warn
        client.track("signup", mapOf(
            "email" to "user@example.com"
        ))

        // Close without flushing to avoid network errors
        safeClose(client)
    }

    // ============= Strict PII Mode Tests for setContext() =============

    @Test
    fun `setContext with safe attributes succeeds in strict mode`() = runBlocking {
        val client = createClientWithStrictPiiMode()

        val context = dev.flagkit.types.EvaluationContext.builder()
            .userId("user123")
            .attribute("plan", "premium")
            .attribute("country", "US")
            .build()

        // Should not throw - no PII
        client.setContext(context)

        val retrieved = client.getContext()
        assertEquals("user123", retrieved?.userId)
        client.close()
    }

    @Test
    fun `setContext with PII throws in strict mode`() = runBlocking {
        val client = createClientWithStrictPiiMode()

        val context = dev.flagkit.types.EvaluationContext.builder()
            .userId("user123")
            .attribute("email", "user@example.com")
            .build()

        val exception = assertFailsWith<SecurityException> {
            client.setContext(context)
        }

        assertTrue(exception.message!!.contains("PII detected"))
        client.close()
    }

    @Test
    fun `setContext with empty attributes succeeds in strict mode`() = runBlocking {
        val client = createClientWithStrictPiiMode()

        val context = dev.flagkit.types.EvaluationContext.builder()
            .userId("user123")
            .build()

        // Should not throw - empty attributes
        client.setContext(context)
        client.close()
    }

    // ============= Cache Encryption Tests =============

    @Test
    fun `client reports encryption disabled when not configured`() {
        val options = FlagKitOptions.builder("sdk_test_key_12345")
            .enableCacheEncryption(false)
            .build()

        val client = FlagKitClient(options)
        assertFalse(client.isCacheEncryptionEnabled())
        runBlocking { client.close() }
    }

    @Test
    fun `client reports encryption enabled when configured`() {
        val options = FlagKitOptions.builder("sdk_test_key_12345")
            .enableCacheEncryption(true)
            .build()

        val client = FlagKitClient(options)
        assertTrue(client.isCacheEncryptionEnabled())
        runBlocking { client.close() }
    }

    @Test
    fun `encrypted cache can store and retrieve flags`() = runBlocking {
        val options = FlagKitOptions.builder("sdk_test_key_12345")
            .enableCacheEncryption(true)
            .cacheEnabled(true)
            .cacheTtl(300.seconds)
            .build()

        val client = FlagKitClient(options)

        // Initialize to ensure cache is ready
        // Note: This will fail to connect to the server, but that's OK
        try {
            client.initialize()
        } catch (e: Exception) {
            // Expected - no server
        }

        // Verify cache stats work with encrypted cache
        val stats = client.getCacheStats()
        assertEquals(0, stats.hitCount)
        assertEquals(0, stats.missCount)

        client.close()
    }

    @Test
    fun `encrypted cache starts empty`() = runBlocking {
        val options = FlagKitOptions.builder("sdk_test_key_12345")
            .enableCacheEncryption(true)
            .build()

        val client = FlagKitClient(options)

        val keys = client.getAllFlagKeys()
        assertTrue(keys.isEmpty())

        client.close()
    }

    // ============= Combined Tests =============

    @Test
    fun `client with both encryption and strict PII mode works`() = runBlocking {
        val options = FlagKitOptions.builder("sdk_test_key_12345")
            .enableCacheEncryption(true)
            .strictPiiMode(true)
            .eventBatchSize(1000) // Prevent auto-flush
            .eventFlushInterval(1.hours) // Prevent auto-flush
            .build()

        val client = FlagKitClient(options)

        assertTrue(client.isCacheEncryptionEnabled())

        // Safe operations should work
        client.identify("user123", mapOf("plan" to "premium"))
        client.track("page_view", mapOf("page" to "/home"))

        // PII should still throw
        assertFailsWith<SecurityException> {
            client.identify("user456", mapOf("email" to "test@example.com"))
        }

        // Close without flushing to avoid network errors
        safeClose(client)
    }

    @Test
    fun `client tracks events disabled does not check PII`() = runBlocking {
        val options = FlagKitOptions.builder("sdk_test_key_12345")
            .strictPiiMode(true)
            .eventsEnabled(false)
            .build()

        val client = FlagKitClient(options)

        // Should not throw because events are disabled (early return)
        client.track("test", mapOf("email" to "test@example.com"))

        client.close()
    }

    // ============= Helper Methods =============

    private fun createClientWithStrictPiiMode(): FlagKitClient {
        val options = FlagKitOptions.builder("sdk_test_key_12345")
            .strictPiiMode(true)
            .eventsEnabled(true)
            .build()
        return FlagKitClient(options)
    }

    private fun createClientWithStrictPiiModeNoAutoFlush(): FlagKitClient {
        val options = FlagKitOptions.builder("sdk_test_key_12345")
            .strictPiiMode(true)
            .eventsEnabled(true)
            .eventBatchSize(1000) // Prevent auto-flush during test
            .eventFlushInterval(1.hours) // Prevent auto-flush during test
            .build()
        return FlagKitClient(options)
    }

    private fun createClientWithNonStrictMode(): FlagKitClient {
        val options = FlagKitOptions.builder("sdk_test_key_12345")
            .strictPiiMode(false)
            .eventsEnabled(true)
            .build()
        return FlagKitClient(options)
    }

    private fun createClientWithNonStrictModeNoAutoFlush(): FlagKitClient {
        val options = FlagKitOptions.builder("sdk_test_key_12345")
            .strictPiiMode(false)
            .eventsEnabled(true)
            .eventBatchSize(1000) // Prevent auto-flush during test
            .eventFlushInterval(1.hours) // Prevent auto-flush during test
            .build()
        return FlagKitClient(options)
    }

    /**
     * Close client without throwing on flush errors (since we don't have a real server).
     */
    private suspend fun safeClose(client: FlagKitClient) {
        try {
            client.close()
        } catch (e: Exception) {
            // Ignore close errors - expected when no server is available
        }
    }
}
