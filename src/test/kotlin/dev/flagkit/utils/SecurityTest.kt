package dev.flagkit.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class SecurityTest {

    // Test Logger implementation for capturing warnings
    private class TestLogger : Logger {
        val warnings = mutableListOf<String>()
        val debugMessages = mutableListOf<String>()
        val infoMessages = mutableListOf<String>()
        val errorMessages = mutableListOf<String>()

        override fun debug(message: String, data: Map<String, Any?>?) {
            debugMessages.add(message)
        }

        override fun info(message: String, data: Map<String, Any?>?) {
            infoMessages.add(message)
        }

        override fun warn(message: String, data: Map<String, Any?>?) {
            warnings.add(message)
        }

        override fun error(message: String, data: Map<String, Any?>?) {
            errorMessages.add(message)
        }

        fun clear() {
            warnings.clear()
            debugMessages.clear()
            infoMessages.clear()
            errorMessages.clear()
        }
    }

    // ============= isPotentialPIIField tests =============

    @Test
    fun `isPotentialPIIField detects email field`() {
        assertTrue(isPotentialPIIField("email"))
        assertTrue(isPotentialPIIField("userEmail"))
        assertTrue(isPotentialPIIField("email_address"))
        assertTrue(isPotentialPIIField("EMAIL"))
    }

    @Test
    fun `isPotentialPIIField detects phone field`() {
        assertTrue(isPotentialPIIField("phone"))
        assertTrue(isPotentialPIIField("phoneNumber"))
        assertTrue(isPotentialPIIField("phone_number"))
        assertTrue(isPotentialPIIField("telephone"))
        assertTrue(isPotentialPIIField("mobile"))
    }

    @Test
    fun `isPotentialPIIField detects SSN field`() {
        assertTrue(isPotentialPIIField("ssn"))
        assertTrue(isPotentialPIIField("SSN"))
        assertTrue(isPotentialPIIField("social_security"))
        assertTrue(isPotentialPIIField("socialSecurity"))
    }

    @Test
    fun `isPotentialPIIField detects credit card fields`() {
        assertTrue(isPotentialPIIField("credit_card"))
        assertTrue(isPotentialPIIField("creditCard"))
        assertTrue(isPotentialPIIField("card_number"))
        assertTrue(isPotentialPIIField("cardNumber"))
        assertTrue(isPotentialPIIField("cvv"))
    }

    @Test
    fun `isPotentialPIIField detects password and secret fields`() {
        assertTrue(isPotentialPIIField("password"))
        assertTrue(isPotentialPIIField("passwd"))
        assertTrue(isPotentialPIIField("secret"))
        assertTrue(isPotentialPIIField("userPassword"))
    }

    @Test
    fun `isPotentialPIIField detects token and API key fields`() {
        assertTrue(isPotentialPIIField("token"))
        assertTrue(isPotentialPIIField("api_key"))
        assertTrue(isPotentialPIIField("apiKey"))
        assertTrue(isPotentialPIIField("private_key"))
        assertTrue(isPotentialPIIField("privateKey"))
        assertTrue(isPotentialPIIField("access_token"))
        assertTrue(isPotentialPIIField("accessToken"))
        assertTrue(isPotentialPIIField("refresh_token"))
        assertTrue(isPotentialPIIField("refreshToken"))
        assertTrue(isPotentialPIIField("auth_token"))
        assertTrue(isPotentialPIIField("authToken"))
    }

    @Test
    fun `isPotentialPIIField detects address fields`() {
        assertTrue(isPotentialPIIField("address"))
        assertTrue(isPotentialPIIField("street"))
        assertTrue(isPotentialPIIField("zip_code"))
        assertTrue(isPotentialPIIField("zipCode"))
        assertTrue(isPotentialPIIField("postal_code"))
        assertTrue(isPotentialPIIField("postalCode"))
    }

    @Test
    fun `isPotentialPIIField detects birth date fields`() {
        assertTrue(isPotentialPIIField("date_of_birth"))
        assertTrue(isPotentialPIIField("dateOfBirth"))
        assertTrue(isPotentialPIIField("dob"))
        assertTrue(isPotentialPIIField("birth_date"))
        assertTrue(isPotentialPIIField("birthDate"))
    }

    @Test
    fun `isPotentialPIIField detects ID fields`() {
        assertTrue(isPotentialPIIField("passport"))
        assertTrue(isPotentialPIIField("driver_license"))
        assertTrue(isPotentialPIIField("driverLicense"))
        assertTrue(isPotentialPIIField("national_id"))
        assertTrue(isPotentialPIIField("nationalId"))
    }

    @Test
    fun `isPotentialPIIField detects banking fields`() {
        assertTrue(isPotentialPIIField("bank_account"))
        assertTrue(isPotentialPIIField("bankAccount"))
        assertTrue(isPotentialPIIField("routing_number"))
        assertTrue(isPotentialPIIField("routingNumber"))
        assertTrue(isPotentialPIIField("iban"))
        assertTrue(isPotentialPIIField("swift"))
    }

    @Test
    fun `isPotentialPIIField returns false for non-PII fields`() {
        assertFalse(isPotentialPIIField("name"))
        assertFalse(isPotentialPIIField("userId"))
        assertFalse(isPotentialPIIField("country"))
        assertFalse(isPotentialPIIField("locale"))
        assertFalse(isPotentialPIIField("plan"))
        assertFalse(isPotentialPIIField("featureEnabled"))
    }

    @Test
    fun `isPotentialPIIField with additional patterns`() {
        assertFalse(isPotentialPIIField("customSensitive"))
        assertTrue(isPotentialPIIField("customSensitive", listOf("sensitive")))
        assertTrue(isPotentialPIIField("myCustomField", listOf("custom")))
    }

    // ============= detectPotentialPII tests =============

    @Test
    fun `detectPotentialPII returns empty list for empty map`() {
        val result = detectPotentialPII(emptyMap())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `detectPotentialPII returns empty list for safe data`() {
        val data = mapOf(
            "userId" to "user123",
            "country" to "US",
            "locale" to "en"
        )
        val result = detectPotentialPII(data)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `detectPotentialPII detects single PII field`() {
        val data = mapOf(
            "userId" to "user123",
            "email" to "test@example.com"
        )
        val result = detectPotentialPII(data)
        assertEquals(listOf("email"), result)
    }

    @Test
    fun `detectPotentialPII detects multiple PII fields`() {
        val data = mapOf(
            "email" to "test@example.com",
            "phone" to "555-1234",
            "userId" to "user123"
        )
        val result = detectPotentialPII(data)
        assertEquals(2, result.size)
        assertTrue(result.contains("email"))
        assertTrue(result.contains("phone"))
    }

    @Test
    fun `detectPotentialPII detects nested PII fields`() {
        val data = mapOf(
            "user" to mapOf(
                "email" to "test@example.com",
                "profile" to mapOf(
                    "ssn" to "123-45-6789"
                )
            )
        )
        val result = detectPotentialPII(data)
        assertEquals(2, result.size)
        assertTrue(result.contains("user.email"))
        assertTrue(result.contains("user.profile.ssn"))
    }

    @Test
    fun `detectPotentialPII uses prefix correctly`() {
        val data = mapOf("email" to "test@example.com")
        val result = detectPotentialPII(data, prefix = "context")
        assertEquals(listOf("context.email"), result)
    }

    @Test
    fun `detectPotentialPII with additional patterns`() {
        val data = mapOf(
            "customField" to "value",
            "normalField" to "value"
        )
        val result = detectPotentialPII(data, additionalPatterns = listOf("custom"))
        assertEquals(listOf("customField"), result)
    }

    // ============= warnIfPotentialPII tests =============

    @Test
    fun `warnIfPotentialPII does nothing for null data`() {
        val logger = TestLogger()
        warnIfPotentialPII(null, DataType.CONTEXT, logger)
        assertTrue(logger.warnings.isEmpty())
    }

    @Test
    fun `warnIfPotentialPII does nothing for null logger`() {
        val data = mapOf("email" to "test@example.com")
        // Should not throw
        warnIfPotentialPII(data, DataType.CONTEXT, null)
    }

    @Test
    fun `warnIfPotentialPII does nothing for safe data`() {
        val logger = TestLogger()
        val data = mapOf(
            "userId" to "user123",
            "country" to "US"
        )
        warnIfPotentialPII(data, DataType.CONTEXT, logger)
        assertTrue(logger.warnings.isEmpty())
    }

    @Test
    fun `warnIfPotentialPII logs warning for context data`() {
        val logger = TestLogger()
        val data = mapOf(
            "email" to "test@example.com",
            "phone" to "555-1234"
        )
        warnIfPotentialPII(data, DataType.CONTEXT, logger)

        assertEquals(1, logger.warnings.size)
        val warning = logger.warnings[0]
        assertTrue(warning.contains("[FlagKit Security]"))
        assertTrue(warning.contains("context"))
        assertTrue(warning.contains("email"))
        assertTrue(warning.contains("phone"))
        assertTrue(warning.contains("privateAttributes"))
    }

    @Test
    fun `warnIfPotentialPII logs warning for event data`() {
        val logger = TestLogger()
        val data = mapOf("password" to "secret123")
        warnIfPotentialPII(data, DataType.EVENT, logger)

        assertEquals(1, logger.warnings.size)
        val warning = logger.warnings[0]
        assertTrue(warning.contains("event"))
        assertTrue(warning.contains("password"))
        assertTrue(warning.contains("removing sensitive data"))
    }

    @Test
    fun `warnIfPotentialPII with string dataType`() {
        val logger = TestLogger()
        val data = mapOf("email" to "test@example.com")
        warnIfPotentialPII(data, "context", logger)

        assertEquals(1, logger.warnings.size)
        assertTrue(logger.warnings[0].contains("context"))
    }

    @Test
    fun `warnIfPotentialPII with additional patterns`() {
        val logger = TestLogger()
        val data = mapOf("customSensitive" to "value")
        warnIfPotentialPII(data, DataType.CONTEXT, logger, listOf("sensitive"))

        assertEquals(1, logger.warnings.size)
        assertTrue(logger.warnings[0].contains("customSensitive"))
    }

    // ============= isServerKey tests =============

    @Test
    fun `isServerKey returns true for srv_ prefix`() {
        assertTrue(isServerKey("srv_abc123"))
        assertTrue(isServerKey("srv_"))
        assertTrue(isServerKey("srv_test_key_12345"))
    }

    @Test
    fun `isServerKey returns false for non-server keys`() {
        assertFalse(isServerKey("sdk_abc123"))
        assertFalse(isServerKey("cli_abc123"))
        assertFalse(isServerKey("abc_srv_123"))
        assertFalse(isServerKey("SRV_abc123")) // case sensitive
        assertFalse(isServerKey(""))
    }

    // ============= isClientKey tests =============

    @Test
    fun `isClientKey returns true for sdk_ prefix`() {
        assertTrue(isClientKey("sdk_abc123"))
        assertTrue(isClientKey("sdk_"))
        assertTrue(isClientKey("sdk_test_key_12345"))
    }

    @Test
    fun `isClientKey returns true for cli_ prefix`() {
        assertTrue(isClientKey("cli_abc123"))
        assertTrue(isClientKey("cli_"))
        assertTrue(isClientKey("cli_test_key_12345"))
    }

    @Test
    fun `isClientKey returns false for non-client keys`() {
        assertFalse(isClientKey("srv_abc123"))
        assertFalse(isClientKey("abc_sdk_123"))
        assertFalse(isClientKey("SDK_abc123")) // case sensitive
        assertFalse(isClientKey("CLI_abc123")) // case sensitive
        assertFalse(isClientKey(""))
    }

    // ============= warnIfServerKeyInBrowser tests =============

    @Test
    fun `warnIfServerKeyInBrowser logs nothing for client key`() {
        val logger = TestLogger()
        // This should not log since sdk_ is a client key
        warnIfServerKeyInBrowser("sdk_abc123", logger)
        assertTrue(logger.warnings.isEmpty())
    }

    @Test
    fun `warnIfServerKeyInBrowser logs nothing when null logger`() {
        // Should not throw
        warnIfServerKeyInBrowser("srv_abc123", null)
    }

    // Note: Testing browser-like environment detection requires setting system properties
    // which may not be reliable in all test environments. The isBrowserLikeEnvironment()
    // function checks for specific environment variables and system properties.

    // ============= Extension function tests =============

    @Test
    fun `String isPotentialPII extension works`() {
        assertTrue("email".isPotentialPII())
        assertFalse("userId".isPotentialPII())
        assertTrue("customField".isPotentialPII(listOf("custom")))
    }

    @Test
    fun `Map detectPII extension works`() {
        val data = mapOf(
            "email" to "test@example.com",
            "userId" to "123"
        )
        val result = data.detectPII()
        assertEquals(listOf("email"), result)
    }

    @Test
    fun `Map detectPII extension with prefix works`() {
        val data = mapOf("email" to "test@example.com")
        val result = data.detectPII(prefix = "user")
        assertEquals(listOf("user.email"), result)
    }

    @Test
    fun `String isServerApiKey extension works`() {
        assertTrue("srv_abc123".isServerApiKey())
        assertFalse("sdk_abc123".isServerApiKey())
    }

    @Test
    fun `String isClientApiKey extension works`() {
        assertTrue("sdk_abc123".isClientApiKey())
        assertTrue("cli_abc123".isClientApiKey())
        assertFalse("srv_abc123".isClientApiKey())
    }

    // ============= SecurityConfig tests =============

    @Test
    fun `SecurityConfig default values`() {
        val config = SecurityConfig.DEFAULT
        assertTrue(config.warnOnServerKeyInBrowser)
        assertTrue(config.additionalPIIPatterns.isEmpty())
        assertFalse(config.strictPiiMode)
        assertNull(config.secondaryApiKey)
        assertTrue(config.enableRequestSigning)
        assertFalse(config.enableCacheEncryption)
    }

    @Test
    fun `SecurityConfig custom values`() {
        val config = SecurityConfig(
            warnOnPotentialPII = false,
            warnOnServerKeyInBrowser = false,
            additionalPIIPatterns = listOf("custom1", "custom2"),
            strictPiiMode = true,
            secondaryApiKey = "sdk_secondary_key",
            enableRequestSigning = false,
            enableCacheEncryption = true
        )
        assertFalse(config.warnOnPotentialPII)
        assertFalse(config.warnOnServerKeyInBrowser)
        assertEquals(listOf("custom1", "custom2"), config.additionalPIIPatterns)
        assertTrue(config.strictPiiMode)
        assertEquals("sdk_secondary_key", config.secondaryApiKey)
        assertFalse(config.enableRequestSigning)
        assertTrue(config.enableCacheEncryption)
    }

    // ============= Edge cases =============

    @Test
    fun `detectPotentialPII handles null values in map`() {
        val data = mapOf<String, Any?>(
            "email" to null,
            "userId" to "123"
        )
        val result = detectPotentialPII(data)
        assertEquals(listOf("email"), result)
    }

    @Test
    fun `detectPotentialPII handles deeply nested structures`() {
        val data = mapOf(
            "level1" to mapOf(
                "level2" to mapOf(
                    "level3" to mapOf(
                        "email" to "test@example.com"
                    )
                )
            )
        )
        val result = detectPotentialPII(data)
        assertEquals(listOf("level1.level2.level3.email"), result)
    }

    @Test
    fun `detectPotentialPII handles mixed value types`() {
        val data = mapOf<String, Any?>(
            "email" to "test@example.com",
            "count" to 42,
            "active" to true,
            "tags" to listOf("a", "b"),
            "nested" to mapOf("phone" to "555-1234")
        )
        val result = detectPotentialPII(data)
        assertEquals(2, result.size)
        assertTrue(result.contains("email"))
        assertTrue(result.contains("nested.phone"))
    }

    @Test
    fun `isPotentialPIIField is case insensitive`() {
        assertTrue(isPotentialPIIField("EMAIL"))
        assertTrue(isPotentialPIIField("Email"))
        assertTrue(isPotentialPIIField("eMaIl"))
        assertTrue(isPotentialPIIField("SOCIAL_SECURITY"))
        assertTrue(isPotentialPIIField("CreditCard"))
    }

    // ============= Request Signing (HMAC-SHA256) tests =============

    @Test
    fun `getKeyId returns first 8 characters`() {
        assertEquals("sdk_abc1", getKeyId("sdk_abc123def456"))
        assertEquals("srv_xyz7", getKeyId("srv_xyz789"))
    }

    @Test
    fun `getKeyId handles short keys`() {
        assertEquals("sdk_", getKeyId("sdk_"))
        assertEquals("ab", getKeyId("ab"))
    }

    @Test
    fun `generateHmacSha256 generates consistent signatures`() {
        val message = "test message"
        val key = "secret-key"

        val sig1 = generateHmacSha256(message, key)
        val sig2 = generateHmacSha256(message, key)

        assertEquals(sig1, sig2)
        assertTrue(sig1.matches(Regex("^[a-f0-9]{64}$"))) // SHA256 = 64 hex chars
    }

    @Test
    fun `generateHmacSha256 generates different signatures for different messages`() {
        val key = "secret-key"

        val sig1 = generateHmacSha256("message1", key)
        val sig2 = generateHmacSha256("message2", key)

        assertNotEquals(sig1, sig2)
    }

    @Test
    fun `generateHmacSha256 generates different signatures for different keys`() {
        val message = "test message"

        val sig1 = generateHmacSha256(message, "key1")
        val sig2 = generateHmacSha256(message, "key2")

        assertNotEquals(sig1, sig2)
    }

    @Test
    fun `createRequestSignature creates signature with all fields`() {
        val body = """{"event":"test","value":123}"""
        val apiKey = "sdk_abc123def456"

        val signature = createRequestSignature(body, apiKey)

        assertTrue(signature.signature.matches(Regex("^[a-f0-9]{64}$")))
        assertTrue(signature.timestamp > 0)
        assertEquals("sdk_abc1", signature.keyId)
    }

    @Test
    fun `createRequestSignature uses provided timestamp`() {
        val body = """{"test":true}"""
        val apiKey = "sdk_test"
        val timestamp = 1700000000000L

        val signature = createRequestSignature(body, apiKey, timestamp)

        assertEquals(timestamp, signature.timestamp)
    }

    @Test
    fun `verifyRequestSignature verifies valid signature`() {
        val body = """{"event":"test","value":123}"""
        val apiKey = "sdk_abc123def456"

        val signature = createRequestSignature(body, apiKey)
        val isValid = verifyRequestSignature(
            body,
            signature.signature,
            signature.timestamp,
            signature.keyId,
            apiKey
        )

        assertTrue(isValid)
    }

    @Test
    fun `verifyRequestSignature rejects wrong key`() {
        val body = """{"event":"test"}"""
        val apiKey = "sdk_abc123def456"

        val signature = createRequestSignature(body, apiKey)
        val isValid = verifyRequestSignature(
            body,
            signature.signature,
            signature.timestamp,
            signature.keyId,
            "sdk_different_key"
        )

        assertFalse(isValid)
    }

    @Test
    fun `verifyRequestSignature rejects expired signature`() {
        val body = """{"event":"test"}"""
        val apiKey = "sdk_abc123def456"
        val oldTimestamp = System.currentTimeMillis() - 600000 // 10 minutes ago

        val signature = createRequestSignature(body, apiKey, oldTimestamp)
        val isValid = verifyRequestSignature(
            body,
            signature.signature,
            signature.timestamp,
            signature.keyId,
            apiKey,
            maxAgeMs = 300000 // 5 min max age
        )

        assertFalse(isValid)
    }

    @Test
    fun `verifyRequestSignature rejects modified body`() {
        val body = """{"event":"test","value":123}"""
        val apiKey = "sdk_abc123def456"

        val signature = createRequestSignature(body, apiKey)
        val modifiedBody = """{"event":"modified","value":999}"""
        val isValid = verifyRequestSignature(
            modifiedBody,
            signature.signature,
            signature.timestamp,
            signature.keyId,
            apiKey
        )

        assertFalse(isValid)
    }

    @Test
    fun `verifyRequestSignature rejects mismatched keyId`() {
        val body = """{"event":"test"}"""
        val apiKey = "sdk_abc123def456"

        val signature = createRequestSignature(body, apiKey)
        val isValid = verifyRequestSignature(
            body,
            signature.signature,
            signature.timestamp,
            "sdk_diff", // Different key ID
            apiKey
        )

        assertFalse(isValid)
    }

    // ============= Key Rotation tests =============

    @Test
    fun `KeyRotationManager returns primary key by default`() {
        val manager = KeyRotationManager("sdk_primary", "sdk_secondary")

        assertEquals("sdk_primary", manager.getCurrentKey())
        assertFalse(manager.isUsingSecondary())
    }

    @Test
    fun `KeyRotationManager fails over to secondary on auth error`() {
        val manager = KeyRotationManager("sdk_primary", "sdk_secondary")

        assertTrue(manager.handleAuthError())
        assertEquals("sdk_secondary", manager.getCurrentKey())
        assertTrue(manager.isUsingSecondary())
    }

    @Test
    fun `KeyRotationManager returns false when no secondary key`() {
        val manager = KeyRotationManager("sdk_primary", null)

        assertFalse(manager.handleAuthError())
        assertEquals("sdk_primary", manager.getCurrentKey())
        assertFalse(manager.hasSecondaryKey())
    }

    @Test
    fun `KeyRotationManager returns false when already using secondary`() {
        val manager = KeyRotationManager("sdk_primary", "sdk_secondary")

        assertTrue(manager.handleAuthError()) // First failover
        assertFalse(manager.handleAuthError()) // Already on secondary
        assertEquals("sdk_secondary", manager.getCurrentKey())
    }

    @Test
    fun `KeyRotationManager reset returns to primary`() {
        val manager = KeyRotationManager("sdk_primary", "sdk_secondary")

        manager.handleAuthError()
        assertTrue(manager.isUsingSecondary())

        manager.reset()
        assertFalse(manager.isUsingSecondary())
        assertEquals("sdk_primary", manager.getCurrentKey())
    }

    @Test
    fun `KeyRotationManager updatePrimaryKey updates and resets`() {
        val manager = KeyRotationManager("sdk_primary", "sdk_secondary")

        manager.handleAuthError()
        manager.updatePrimaryKey("sdk_new_primary")

        assertFalse(manager.isUsingSecondary())
        assertEquals("sdk_new_primary", manager.getCurrentKey())
    }

    // ============= Strict PII Mode tests =============

    @Test
    fun `checkForPotentialPii returns hasPii false for no PII`() {
        val data = mapOf("userId" to "123", "plan" to "premium")
        val result = checkForPotentialPii(data, DataType.CONTEXT)

        assertFalse(result.hasPii)
        assertTrue(result.fields.isEmpty())
        assertEquals("", result.message)
    }

    @Test
    fun `checkForPotentialPii returns hasPii true for PII`() {
        val data = mapOf("email" to "test@example.com", "phone" to "555-1234")
        val result = checkForPotentialPii(data, DataType.CONTEXT)

        assertTrue(result.hasPii)
        assertEquals(2, result.fields.size)
        assertTrue(result.fields.contains("email"))
        assertTrue(result.fields.contains("phone"))
        assertTrue(result.message.contains("Potential PII detected"))
    }

    @Test
    fun `checkForPotentialPii respects privateAttributes`() {
        val data = mapOf("email" to "test@example.com", "phone" to "555-1234")
        val result = checkForPotentialPii(
            data,
            DataType.CONTEXT,
            privateAttributes = setOf("email")
        )

        assertTrue(result.hasPii)
        assertEquals(1, result.fields.size)
        assertTrue(result.fields.contains("phone"))
        assertFalse(result.fields.contains("email"))
    }

    @Test
    fun `checkForPotentialPii returns false when all PII in privateAttributes`() {
        val data = mapOf("email" to "test@example.com")
        val result = checkForPotentialPii(
            data,
            DataType.CONTEXT,
            privateAttributes = setOf("email")
        )

        assertFalse(result.hasPii)
        assertTrue(result.fields.isEmpty())
    }

    @Test
    fun `enforceNoPii warns in non-strict mode`() {
        val logger = TestLogger()
        val data = mapOf("email" to "test@example.com")

        enforceNoPii(data, DataType.CONTEXT, strictMode = false, logger = logger)

        assertEquals(1, logger.warnings.size)
        assertTrue(logger.warnings[0].contains("Potential PII"))
    }

    @Test
    fun `enforceNoPii throws in strict mode`() {
        val data = mapOf("email" to "test@example.com")

        val exception = assertFailsWith<SecurityException> {
            enforceNoPii(data, DataType.CONTEXT, strictMode = true)
        }

        assertTrue(exception.message!!.contains("PII detected"))
        assertTrue(exception.message!!.contains("strict PII mode"))
    }

    @Test
    fun `enforceNoPii does not throw for safe data in strict mode`() {
        val data = mapOf("userId" to "123")

        // Should not throw
        enforceNoPii(data, DataType.CONTEXT, strictMode = true)
    }

    @Test
    fun `enforceNoPii respects privateAttributes in strict mode`() {
        val data = mapOf("email" to "test@example.com")

        // Should not throw when email is in privateAttributes
        enforceNoPii(
            data,
            DataType.CONTEXT,
            strictMode = true,
            privateAttributes = setOf("email")
        )
    }

    // ============= Cache Encryption tests =============

    @Test
    fun `EncryptedStorage encrypts and decrypts correctly`() {
        val storage = EncryptedStorage("sdk_test_api_key")
        val plaintext = """{"flagKey":"test","value":true}"""

        val encrypted = storage.encrypt(plaintext)
        val decrypted = storage.decrypt(encrypted)

        assertEquals(plaintext, decrypted)
        assertNotEquals(plaintext, encrypted)
    }

    @Test
    fun `EncryptedStorage generates different ciphertext for same plaintext`() {
        val storage = EncryptedStorage("sdk_test_api_key")
        val plaintext = "test data"

        val encrypted1 = storage.encrypt(plaintext)
        val encrypted2 = storage.encrypt(plaintext)

        // Different IVs should produce different ciphertext
        assertNotEquals(encrypted1, encrypted2)

        // But both should decrypt to the same value
        assertEquals(plaintext, storage.decrypt(encrypted1))
        assertEquals(plaintext, storage.decrypt(encrypted2))
    }

    @Test
    fun `EncryptedStorage with same salt produces same key`() {
        val apiKey = "sdk_test_api_key"
        val salt = EncryptedStorage.generateSalt()

        val storage1 = EncryptedStorage(apiKey, salt)
        val storage2 = EncryptedStorage(apiKey, salt)

        val plaintext = "test data"
        val encrypted = storage1.encrypt(plaintext)
        val decrypted = storage2.decrypt(encrypted)

        assertEquals(plaintext, decrypted)
    }

    @Test
    fun `EncryptedStorage withSalt factory works correctly`() {
        val apiKey = "sdk_test_api_key"
        val storage1 = EncryptedStorage(apiKey)
        val saltBase64 = storage1.getSaltBase64()

        val plaintext = "test data"
        val encrypted = storage1.encrypt(plaintext)

        // Recreate storage with saved salt
        val storage2 = EncryptedStorage.withSalt(apiKey, saltBase64)
        val decrypted = storage2.decrypt(encrypted)

        assertEquals(plaintext, decrypted)
    }

    @Test
    fun `EncryptedStorage throws on invalid data`() {
        val storage = EncryptedStorage("sdk_test_api_key")

        assertFailsWith<SecurityException> {
            storage.decrypt("invalid_base64_data!!!")
        }
    }

    @Test
    fun `EncryptedStorage throws on tampered data`() {
        val storage = EncryptedStorage("sdk_test_api_key")
        val plaintext = "test data"
        val encrypted = storage.encrypt(plaintext)

        // Tamper with the encrypted data
        val tamperedBytes = java.util.Base64.getDecoder().decode(encrypted)
        tamperedBytes[tamperedBytes.size - 1] = (tamperedBytes[tamperedBytes.size - 1].toInt() xor 0xFF).toByte()
        val tampered = java.util.Base64.getEncoder().encodeToString(tamperedBytes)

        assertFailsWith<SecurityException> {
            storage.decrypt(tampered)
        }
    }

    @Test
    fun `EncryptedCache set and get work correctly`() {
        val storage = EncryptedStorage("sdk_test_api_key")
        val cache = EncryptedCache<String>(
            storage = storage,
            serializer = { it },
            deserializer = { it }
        )

        cache.set("key1", "value1")
        cache.set("key2", "value2")

        assertEquals("value1", cache.get("key1"))
        assertEquals("value2", cache.get("key2"))
        assertNull(cache.get("nonexistent"))
    }

    @Test
    fun `EncryptedCache has and remove work correctly`() {
        val storage = EncryptedStorage("sdk_test_api_key")
        val cache = EncryptedCache<String>(
            storage = storage,
            serializer = { it },
            deserializer = { it }
        )

        cache.set("key1", "value1")

        assertTrue(cache.has("key1"))
        assertFalse(cache.has("key2"))

        assertTrue(cache.remove("key1"))
        assertFalse(cache.has("key1"))
        assertFalse(cache.remove("key1"))
    }

    @Test
    fun `EncryptedCache clear and keys work correctly`() {
        val storage = EncryptedStorage("sdk_test_api_key")
        val cache = EncryptedCache<String>(
            storage = storage,
            serializer = { it },
            deserializer = { it }
        )

        cache.set("key1", "value1")
        cache.set("key2", "value2")

        assertEquals(setOf("key1", "key2"), cache.keys())
        assertEquals(2, cache.size())

        cache.clear()

        assertEquals(emptySet<String>(), cache.keys())
        assertEquals(0, cache.size())
    }

    @Test
    fun `EncryptedCache export and import work correctly`() {
        val storage = EncryptedStorage("sdk_test_api_key")
        val cache1 = EncryptedCache<String>(
            storage = storage,
            serializer = { it },
            deserializer = { it }
        )

        cache1.set("key1", "value1")
        cache1.set("key2", "value2")

        val exported = cache1.exportEncrypted()

        val cache2 = EncryptedCache<String>(
            storage = storage,
            serializer = { it },
            deserializer = { it }
        )
        cache2.importEncrypted(exported)

        assertEquals("value1", cache2.get("key1"))
        assertEquals("value2", cache2.get("key2"))
    }

}
