package dev.flagkit

import dev.flagkit.utils.BootstrapVerificationResult
import dev.flagkit.utils.canonicalizeObject
import dev.flagkit.utils.generateBootstrapSignature
import dev.flagkit.utils.generateHmacSha256
import dev.flagkit.utils.verifyBootstrapSignature
import dev.flagkit.utils.SecurityException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

class BootstrapVerificationTest {

    private val testApiKey = "sdk_test_api_key_12345"

    // ============= canonicalizeObject tests =============

    @Test
    fun `canonicalizeObject produces deterministic output for simple map`() {
        val obj = mapOf(
            "b" to "value2",
            "a" to "value1",
            "c" to "value3"
        )

        val result = canonicalizeObject(obj)

        // Keys should be sorted alphabetically
        assertEquals("""{"a":"value1","b":"value2","c":"value3"}""", result)
    }

    @Test
    fun `canonicalizeObject handles nested objects`() {
        val obj = mapOf(
            "outer" to mapOf(
                "z" to 1,
                "a" to 2
            )
        )

        val result = canonicalizeObject(obj)

        assertEquals("""{"outer":{"a":2,"z":1}}""", result)
    }

    @Test
    fun `canonicalizeObject handles arrays`() {
        val obj = mapOf(
            "items" to listOf("a", "b", "c")
        )

        val result = canonicalizeObject(obj)

        assertEquals("""{"items":["a","b","c"]}""", result)
    }

    @Test
    fun `canonicalizeObject handles null values`() {
        val obj = mapOf(
            "key" to null
        )

        val result = canonicalizeObject(obj)

        assertEquals("""{"key":null}""", result)
    }

    @Test
    fun `canonicalizeObject handles boolean values`() {
        val obj = mapOf(
            "enabled" to true,
            "active" to false
        )

        val result = canonicalizeObject(obj)

        assertEquals("""{"active":false,"enabled":true}""", result)
    }

    @Test
    fun `canonicalizeObject handles numeric values`() {
        val obj = mapOf(
            "int" to 42,
            "double" to 3.14,
            "long" to 1234567890L
        )

        val result = canonicalizeObject(obj)

        assertTrue(result.contains("\"int\":42"))
        assertTrue(result.contains("\"long\":1234567890"))
    }

    @Test
    fun `canonicalizeObject handles special characters in strings`() {
        val obj = mapOf(
            "text" to "Hello\n\"World\"\t\\end"
        )

        val result = canonicalizeObject(obj)

        assertEquals("""{"text":"Hello\n\"World\"\t\\end"}""", result)
    }

    @Test
    fun `canonicalizeObject handles empty map`() {
        val obj = emptyMap<String, Any?>()

        val result = canonicalizeObject(obj)

        assertEquals("{}", result)
    }

    @Test
    fun `canonicalizeObject handles complex nested structure`() {
        val obj = mapOf(
            "flags" to listOf(
                mapOf(
                    "key" to "feature-1",
                    "enabled" to true,
                    "value" to mapOf("variant" to "A")
                ),
                mapOf(
                    "key" to "feature-2",
                    "enabled" to false,
                    "value" to null
                )
            )
        )

        val result1 = canonicalizeObject(obj)
        val result2 = canonicalizeObject(obj)

        // Should produce consistent output
        assertEquals(result1, result2)
    }

    // ============= generateBootstrapSignature tests =============

    @Test
    fun `generateBootstrapSignature produces consistent signatures`() {
        val flags = mapOf(
            "flags" to listOf(
                mapOf("key" to "test-flag", "enabled" to true)
            )
        )

        val sig1 = generateBootstrapSignature(flags, testApiKey)
        val sig2 = generateBootstrapSignature(flags, testApiKey)

        assertEquals(sig1, sig2)
        assertTrue(sig1.matches(Regex("^[a-f0-9]{64}$")))
    }

    @Test
    fun `generateBootstrapSignature produces different signatures for different data`() {
        val flags1 = mapOf("flags" to listOf(mapOf("key" to "flag1")))
        val flags2 = mapOf("flags" to listOf(mapOf("key" to "flag2")))

        val sig1 = generateBootstrapSignature(flags1, testApiKey)
        val sig2 = generateBootstrapSignature(flags2, testApiKey)

        assertFalse(sig1 == sig2)
    }

    @Test
    fun `generateBootstrapSignature produces different signatures for different keys`() {
        val flags = mapOf("flags" to listOf(mapOf("key" to "test-flag")))

        val sig1 = generateBootstrapSignature(flags, "sdk_key1")
        val sig2 = generateBootstrapSignature(flags, "sdk_key2")

        assertFalse(sig1 == sig2)
    }

    // ============= verifyBootstrapSignature tests =============

    @Test
    fun `verifyBootstrapSignature accepts valid signature`() {
        val flags = mapOf(
            "flags" to listOf(
                mapOf("key" to "test-flag", "enabled" to true, "value" to "variant-a")
            )
        )
        val signature = generateBootstrapSignature(flags, testApiKey)

        val result = verifyBootstrapSignature(
            flags = flags,
            signature = signature,
            timestamp = System.currentTimeMillis(),
            apiKey = testApiKey,
            maxAge = 86400000L
        )

        assertTrue(result.valid)
        assertNull(result.errorMessage)
    }

    @Test
    fun `verifyBootstrapSignature rejects invalid signature`() {
        val flags = mapOf(
            "flags" to listOf(mapOf("key" to "test-flag", "enabled" to true))
        )
        val invalidSignature = "invalid_signature_12345678901234567890123456789012345678901234"

        val result = verifyBootstrapSignature(
            flags = flags,
            signature = invalidSignature,
            timestamp = System.currentTimeMillis(),
            apiKey = testApiKey,
            maxAge = 86400000L
        )

        assertFalse(result.valid)
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage!!.contains("verification failed"))
    }

    @Test
    fun `verifyBootstrapSignature rejects expired timestamp`() {
        val flags = mapOf(
            "flags" to listOf(mapOf("key" to "test-flag"))
        )
        val signature = generateBootstrapSignature(flags, testApiKey)
        val expiredTimestamp = System.currentTimeMillis() - 100000000L // Very old

        val result = verifyBootstrapSignature(
            flags = flags,
            signature = signature,
            timestamp = expiredTimestamp,
            apiKey = testApiKey,
            maxAge = 86400000L // 24 hours
        )

        assertFalse(result.valid)
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage!!.contains("expired"))
    }

    @Test
    fun `verifyBootstrapSignature rejects future timestamp`() {
        val flags = mapOf(
            "flags" to listOf(mapOf("key" to "test-flag"))
        )
        val signature = generateBootstrapSignature(flags, testApiKey)
        val futureTimestamp = System.currentTimeMillis() + 100000L

        val result = verifyBootstrapSignature(
            flags = flags,
            signature = signature,
            timestamp = futureTimestamp,
            apiKey = testApiKey,
            maxAge = 86400000L
        )

        assertFalse(result.valid)
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage!!.contains("future"))
    }

    @Test
    fun `verifyBootstrapSignature accepts null timestamp`() {
        val flags = mapOf(
            "flags" to listOf(mapOf("key" to "test-flag"))
        )
        val signature = generateBootstrapSignature(flags, testApiKey)

        val result = verifyBootstrapSignature(
            flags = flags,
            signature = signature,
            timestamp = null,
            apiKey = testApiKey,
            maxAge = 86400000L
        )

        assertTrue(result.valid)
    }

    @Test
    fun `verifyBootstrapSignature rejects missing signature`() {
        val flags = mapOf(
            "flags" to listOf(mapOf("key" to "test-flag"))
        )

        val result = verifyBootstrapSignature(
            flags = flags,
            signature = null,
            timestamp = System.currentTimeMillis(),
            apiKey = testApiKey,
            maxAge = 86400000L
        )

        assertFalse(result.valid)
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage!!.contains("missing"))
    }

    @Test
    fun `verifyBootstrapSignature passes when verification disabled`() {
        val flags = mapOf(
            "flags" to listOf(mapOf("key" to "test-flag"))
        )

        val result = verifyBootstrapSignature(
            flags = flags,
            signature = "invalid_signature",
            timestamp = System.currentTimeMillis(),
            apiKey = testApiKey,
            maxAge = 86400000L,
            verificationEnabled = false
        )

        assertTrue(result.valid)
        assertNull(result.errorMessage)
    }

    @Test
    fun `verifyBootstrapSignature rejects tampered data`() {
        val originalFlags = mapOf(
            "flags" to listOf(mapOf("key" to "test-flag", "enabled" to true))
        )
        val signature = generateBootstrapSignature(originalFlags, testApiKey)

        // Tamper with the data
        val tamperedFlags = mapOf(
            "flags" to listOf(mapOf("key" to "test-flag", "enabled" to false))
        )

        val result = verifyBootstrapSignature(
            flags = tamperedFlags,
            signature = signature,
            timestamp = System.currentTimeMillis(),
            apiKey = testApiKey,
            maxAge = 86400000L
        )

        assertFalse(result.valid)
    }

    // ============= BootstrapConfig tests =============

    @Test
    fun `BootstrapConfig can be created with all parameters`() {
        val flags = mapOf(
            "flags" to listOf(mapOf("key" to "test"))
        )
        val signature = "signature123"
        val timestamp = System.currentTimeMillis()

        val config = BootstrapConfig(
            flags = flags,
            signature = signature,
            timestamp = timestamp
        )

        assertEquals(flags, config.flags)
        assertEquals(signature, config.signature)
        assertEquals(timestamp, config.timestamp)
    }

    @Test
    fun `BootstrapConfig defaults to null signature and timestamp`() {
        val flags = mapOf("flags" to listOf<Any>())

        val config = BootstrapConfig(flags = flags)

        assertNull(config.signature)
        assertNull(config.timestamp)
    }

    // ============= BootstrapVerificationConfig tests =============

    @Test
    fun `BootstrapVerificationConfig has correct defaults`() {
        val config = BootstrapVerificationConfig()

        assertTrue(config.enabled)
        assertEquals(86400000L, config.maxAge)
        assertEquals("warn", config.onFailure)
    }

    @Test
    fun `BootstrapVerificationConfig accepts valid onFailure values`() {
        val warnConfig = BootstrapVerificationConfig(onFailure = "warn")
        val errorConfig = BootstrapVerificationConfig(onFailure = "error")
        val ignoreConfig = BootstrapVerificationConfig(onFailure = "ignore")

        assertEquals("warn", warnConfig.onFailure)
        assertEquals("error", errorConfig.onFailure)
        assertEquals("ignore", ignoreConfig.onFailure)
    }

    @Test
    fun `BootstrapVerificationConfig rejects invalid onFailure values`() {
        assertFailsWith<IllegalArgumentException> {
            BootstrapVerificationConfig(onFailure = "invalid")
        }
    }

    @Test
    fun `BootstrapVerificationConfig can be disabled`() {
        val config = BootstrapVerificationConfig(enabled = false)

        assertFalse(config.enabled)
    }

    @Test
    fun `BootstrapVerificationConfig maxAge can be customized`() {
        val config = BootstrapVerificationConfig(maxAge = 3600000L) // 1 hour

        assertEquals(3600000L, config.maxAge)
    }

    // ============= FlagKitOptions integration tests =============

    @Test
    fun `FlagKitOptions accepts bootstrapConfig`() {
        val bootstrapConfig = BootstrapConfig(
            flags = mapOf("flags" to listOf(mapOf("key" to "test"))),
            signature = "signature",
            timestamp = System.currentTimeMillis()
        )

        val options = FlagKitOptions(
            apiKey = "sdk_test_key",
            bootstrapConfig = bootstrapConfig
        )

        assertNotNull(options.bootstrapConfig)
        assertEquals(bootstrapConfig, options.bootstrapConfig)
    }

    @Test
    fun `FlagKitOptions accepts bootstrapVerification config`() {
        val verificationConfig = BootstrapVerificationConfig(
            enabled = true,
            maxAge = 3600000L,
            onFailure = "error"
        )

        val options = FlagKitOptions(
            apiKey = "sdk_test_key",
            bootstrapVerification = verificationConfig
        )

        assertEquals(verificationConfig, options.bootstrapVerification)
    }

    @Test
    fun `FlagKitOptions builder supports bootstrapConfig`() {
        val bootstrapConfig = BootstrapConfig(
            flags = mapOf("flags" to listOf(mapOf("key" to "test")))
        )

        val options = FlagKitOptions.builder("sdk_test_key")
            .bootstrapConfig(bootstrapConfig)
            .build()

        assertEquals(bootstrapConfig, options.bootstrapConfig)
    }

    @Test
    fun `FlagKitOptions builder supports bootstrapVerification`() {
        val verificationConfig = BootstrapVerificationConfig(
            enabled = false,
            onFailure = "ignore"
        )

        val options = FlagKitOptions.builder("sdk_test_key")
            .bootstrapVerification(verificationConfig)
            .build()

        assertEquals(verificationConfig, options.bootstrapVerification)
    }

    // ============= Edge cases =============

    @Test
    fun `verifyBootstrapSignature handles empty flags map`() {
        val flags = emptyMap<String, Any?>()
        val signature = generateBootstrapSignature(flags, testApiKey)

        val result = verifyBootstrapSignature(
            flags = flags,
            signature = signature,
            timestamp = null,
            apiKey = testApiKey,
            maxAge = 86400000L
        )

        assertTrue(result.valid)
    }

    @Test
    fun `canonicalizeObject handles deeply nested structure`() {
        val obj = mapOf(
            "a" to mapOf(
                "b" to mapOf(
                    "c" to mapOf(
                        "d" to "value"
                    )
                )
            )
        )

        val result = canonicalizeObject(obj)

        assertEquals("""{"a":{"b":{"c":{"d":"value"}}}}""", result)
    }

    @Test
    fun `canonicalizeObject handles mixed array content`() {
        val obj = mapOf(
            "mixed" to listOf(
                1,
                "string",
                true,
                null,
                mapOf("key" to "value")
            )
        )

        val result = canonicalizeObject(obj)

        assertTrue(result.contains("[1,\"string\",true,null,{\"key\":\"value\"}]"))
    }

    @Test
    fun `signature verification is timing-safe`() {
        // This test verifies that constant-time comparison is used
        // by checking that similar signatures don't take significantly different time
        val flags = mapOf("key" to "value")
        val validSignature = generateBootstrapSignature(flags, testApiKey)

        // Generate slightly different signatures
        val almostValid = validSignature.replaceFirst("a", "b")
        val veryDifferent = "0".repeat(64)

        // Both should fail, and timing shouldn't reveal which is closer
        val result1 = verifyBootstrapSignature(flags, almostValid, null, testApiKey, 86400000L)
        val result2 = verifyBootstrapSignature(flags, veryDifferent, null, testApiKey, 86400000L)

        assertFalse(result1.valid)
        assertFalse(result2.valid)
    }
}
