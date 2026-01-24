package dev.flagkit.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
    }

    @Test
    fun `SecurityConfig custom values`() {
        val config = SecurityConfig(
            warnOnPotentialPII = false,
            warnOnServerKeyInBrowser = false,
            additionalPIIPatterns = listOf("custom1", "custom2")
        )
        assertFalse(config.warnOnPotentialPII)
        assertFalse(config.warnOnServerKeyInBrowser)
        assertEquals(listOf("custom1", "custom2"), config.additionalPIIPatterns)
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
}
