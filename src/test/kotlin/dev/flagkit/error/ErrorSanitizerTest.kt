package dev.flagkit.error

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class ErrorSanitizerTest {

    // ================== Unix Path Sanitization Tests ==================

    @Test
    fun `sanitizes Unix-style file paths`() {
        val message = "Failed to read file /home/user/config/secrets.json"
        val sanitized = ErrorSanitizer.sanitize(message)
        assertEquals("Failed to read file [PATH]", sanitized)
    }

    @Test
    fun `sanitizes deeply nested Unix paths`() {
        val message = "Error at /var/log/flagkit/events/2024/01/data.log"
        val sanitized = ErrorSanitizer.sanitize(message)
        assertEquals("Error at [PATH]", sanitized)
    }

    @Test
    fun `sanitizes multiple Unix paths in one message`() {
        val message = "Copied /home/user/source.txt to /home/user/dest.txt"
        val sanitized = ErrorSanitizer.sanitize(message)
        assertEquals("Copied [PATH] to [PATH]", sanitized)
    }

    // ================== Windows Path Sanitization Tests ==================

    @Test
    fun `sanitizes Windows-style file paths`() {
        val message = "Cannot access C:\\Users\\Admin\\Documents\\config.ini"
        val sanitized = ErrorSanitizer.sanitize(message)
        assertEquals("Cannot access [PATH]", sanitized)
    }

    @Test
    fun `sanitizes Windows paths with lowercase drive letter`() {
        val message = "File not found: d:\\projects\\flagkit\\data.json"
        val sanitized = ErrorSanitizer.sanitize(message)
        assertEquals("File not found: [PATH]", sanitized)
    }

    // ================== IP Address Sanitization Tests ==================

    @Test
    fun `sanitizes IPv4 addresses`() {
        val message = "Connection refused to 192.168.1.100"
        val sanitized = ErrorSanitizer.sanitize(message)
        assertEquals("Connection refused to [IP]", sanitized)
    }

    @Test
    fun `sanitizes localhost IP address`() {
        val message = "Server running on 127.0.0.1:8080"
        val sanitized = ErrorSanitizer.sanitize(message)
        assertEquals("Server running on [IP]:8080", sanitized)
    }

    @Test
    fun `sanitizes multiple IP addresses`() {
        val message = "Failed to connect from 10.0.0.1 to 10.0.0.2"
        val sanitized = ErrorSanitizer.sanitize(message)
        assertEquals("Failed to connect from [IP] to [IP]", sanitized)
    }

    // ================== API Key Sanitization Tests ==================

    @Test
    fun `sanitizes SDK API keys`() {
        val message = "Invalid API key: sdk_abc12345xyz"
        val sanitized = ErrorSanitizer.sanitize(message)
        assertEquals("Invalid API key: sdk_[REDACTED]", sanitized)
    }

    @Test
    fun `sanitizes server API keys`() {
        val message = "Authentication failed for srv_secretkey123456"
        val sanitized = ErrorSanitizer.sanitize(message)
        assertEquals("Authentication failed for srv_[REDACTED]", sanitized)
    }

    @Test
    fun `sanitizes CLI API keys`() {
        val message = "Token expired: cli_mytoken-with-dashes_and_underscores"
        val sanitized = ErrorSanitizer.sanitize(message)
        assertEquals("Token expired: cli_[REDACTED]", sanitized)
    }

    @Test
    fun `does not sanitize short API key prefixes`() {
        // Keys shorter than 8 chars after prefix should not be sanitized
        val message = "Key: sdk_short"
        val sanitized = ErrorSanitizer.sanitize(message)
        assertEquals("Key: sdk_short", sanitized)
    }

    @Test
    fun `sanitizes API keys with exactly 8 characters`() {
        val message = "Key: sdk_12345678"
        val sanitized = ErrorSanitizer.sanitize(message)
        assertEquals("Key: sdk_[REDACTED]", sanitized)
    }

    // ================== Email Sanitization Tests ==================

    @Test
    fun `sanitizes email addresses`() {
        val message = "User not found: admin@example.com"
        val sanitized = ErrorSanitizer.sanitize(message)
        assertEquals("User not found: [EMAIL]", sanitized)
    }

    @Test
    fun `sanitizes email addresses with subdomains`() {
        val message = "Contact: support@mail.flagkit.dev"
        val sanitized = ErrorSanitizer.sanitize(message)
        assertEquals("Contact: [EMAIL]", sanitized)
    }

    @Test
    fun `sanitizes email addresses with dots and hyphens`() {
        val message = "Invalid user: john.doe-test@company-name.co.uk"
        val sanitized = ErrorSanitizer.sanitize(message)
        assertEquals("Invalid user: [EMAIL]", sanitized)
    }

    // ================== Connection String Sanitization Tests ==================

    @Test
    fun `sanitizes PostgreSQL connection strings`() {
        val message = "Failed to connect: postgres://user:password@localhost:5432/dbname"
        val sanitized = ErrorSanitizer.sanitize(message)
        assertEquals("Failed to connect: [CONNECTION_STRING]", sanitized)
    }

    @Test
    fun `sanitizes MySQL connection strings`() {
        val message = "Error: mysql://root:secret@192.168.1.1:3306/mydb"
        val sanitized = ErrorSanitizer.sanitize(message)
        assertEquals("Error: [CONNECTION_STRING]", sanitized)
    }

    @Test
    fun `sanitizes MongoDB connection strings`() {
        val message = "Cannot connect to mongodb://admin:pass@cluster.mongodb.net/app"
        val sanitized = ErrorSanitizer.sanitize(message)
        assertEquals("Cannot connect to [CONNECTION_STRING]", sanitized)
    }

    @Test
    fun `sanitizes Redis connection strings`() {
        val message = "Cache error: redis://default:auth@redis.example.com:6379"
        val sanitized = ErrorSanitizer.sanitize(message)
        assertEquals("Cache error: [CONNECTION_STRING]", sanitized)
    }

    @Test
    fun `sanitizes connection strings case-insensitively`() {
        val message = "Connection: POSTGRES://user:pass@host/db"
        val sanitized = ErrorSanitizer.sanitize(message)
        assertEquals("Connection: [CONNECTION_STRING]", sanitized)
    }

    // ================== Configuration Tests ==================

    @Test
    fun `respects enabled false configuration`() {
        val config = ErrorSanitizationConfig(enabled = false)
        val message = "Error at /home/user/secret.txt with key sdk_verysecretkey123"
        val sanitized = ErrorSanitizer.sanitize(message, config)
        assertEquals(message, sanitized)
    }

    @Test
    fun `default config enables sanitization`() {
        val config = ErrorSanitizer.DEFAULT_CONFIG
        assertTrue(config.enabled)
        assertFalse(config.preserveOriginal)
    }

    @Test
    fun `sanitize with boolean enabled parameter`() {
        val message = "Path: /home/user/config.json"
        assertEquals("[PATH]", ErrorSanitizer.sanitize("Path: /home/user/config.json", true).substringAfter("Path: "))
        assertEquals(message, ErrorSanitizer.sanitize(message, false))
    }

    // ================== Combined Pattern Tests ==================

    @Test
    fun `sanitizes multiple sensitive patterns in one message`() {
        val message = "User admin@company.com from 192.168.1.1 accessed /var/log/app.log with key sdk_secretapikey123"
        val sanitized = ErrorSanitizer.sanitize(message)
        assertEquals("User [EMAIL] from [IP] accessed [PATH] with key sdk_[REDACTED]", sanitized)
    }

    @Test
    fun `handles messages with no sensitive data`() {
        val message = "Flag evaluation completed successfully"
        val sanitized = ErrorSanitizer.sanitize(message)
        assertEquals(message, sanitized)
    }

    @Test
    fun `handles empty string`() {
        val sanitized = ErrorSanitizer.sanitize("")
        assertEquals("", sanitized)
    }

    // ================== containsSensitiveInfo Tests ==================

    @Test
    fun `containsSensitiveInfo detects paths`() {
        assertTrue(ErrorSanitizer.containsSensitiveInfo("File at /home/user/data.txt"))
        assertTrue(ErrorSanitizer.containsSensitiveInfo("File at C:\\Users\\Admin\\data.txt"))
    }

    @Test
    fun `containsSensitiveInfo detects IPs`() {
        assertTrue(ErrorSanitizer.containsSensitiveInfo("Server 192.168.1.1"))
    }

    @Test
    fun `containsSensitiveInfo detects API keys`() {
        assertTrue(ErrorSanitizer.containsSensitiveInfo("Key: sdk_verylongapikey123"))
        assertTrue(ErrorSanitizer.containsSensitiveInfo("Key: srv_verylongapikey123"))
        assertTrue(ErrorSanitizer.containsSensitiveInfo("Key: cli_verylongapikey123"))
    }

    @Test
    fun `containsSensitiveInfo detects emails`() {
        assertTrue(ErrorSanitizer.containsSensitiveInfo("Email: test@example.com"))
    }

    @Test
    fun `containsSensitiveInfo detects connection strings`() {
        assertTrue(ErrorSanitizer.containsSensitiveInfo("DB: postgres://user:pass@host/db"))
    }

    @Test
    fun `containsSensitiveInfo returns false for clean messages`() {
        assertFalse(ErrorSanitizer.containsSensitiveInfo("Simple error message"))
        assertFalse(ErrorSanitizer.containsSensitiveInfo("Flag 'my-feature' not found"))
    }

    // ================== FlagKitException Integration Tests ==================

    @Test
    fun `FlagKitException sanitizes messages by default`() {
        val exception = FlagKitException.initError("Failed to read /etc/flagkit/config.json")
        assertEquals("Failed to read [PATH]", exception.message)
    }

    @Test
    fun `FlagKitException preserves original when configured`() {
        val originalConfig = FlagKitException.globalConfig
        try {
            FlagKitException.globalConfig = ErrorSanitizationConfig(enabled = true, preserveOriginal = true)
            val message = "Error at /home/user/data.txt"
            val exception = FlagKitException.initError(message)
            assertEquals("Error at [PATH]", exception.message)
            assertEquals(message, exception.originalMessage)
            assertTrue(exception.wasSanitized)
        } finally {
            FlagKitException.globalConfig = originalConfig
        }
    }

    @Test
    fun `FlagKitException wasSanitized is false when no changes made`() {
        val originalConfig = FlagKitException.globalConfig
        try {
            FlagKitException.globalConfig = ErrorSanitizationConfig(enabled = true, preserveOriginal = true)
            val exception = FlagKitException.initError("Simple error message")
            assertFalse(exception.wasSanitized)
        } finally {
            FlagKitException.globalConfig = originalConfig
        }
    }

    @Test
    fun `FlagKitException unsanitized factory method bypasses sanitization`() {
        val message = "Error at /home/user/secret.txt with sdk_verysecretapikey123"
        val exception = FlagKitException.unsanitized(ErrorCode.INIT_FAILED, message)
        assertEquals(message, exception.message)
    }

    @Test
    fun `FlagKitException networkError sanitizes with cause`() {
        val cause = RuntimeException("Connection failed")
        val exception = FlagKitException.networkError(
            "Failed to connect to 192.168.1.100:8080",
            cause
        )
        assertEquals("Failed to connect to [IP]:8080", exception.message)
        assertEquals(cause, exception.cause)
    }

    @Test
    fun `FlagKitException authError sanitizes API keys`() {
        val exception = FlagKitException.authError(
            ErrorCode.AUTH_INVALID_KEY,
            "Invalid key: sdk_supersecretapikey123456"
        )
        assertEquals("Invalid key: sdk_[REDACTED]", exception.message)
    }

    @Test
    fun `FlagKitException evalError sanitizes messages`() {
        val exception = FlagKitException.evalError(
            ErrorCode.EVAL_FLAG_NOT_FOUND,
            "Flag data at /var/cache/flags/feature.json not found"
        )
        assertEquals("Flag data at [PATH] not found", exception.message)
    }

    @Test
    fun `FlagKitException configError sanitizes messages`() {
        val exception = FlagKitException.configError(
            ErrorCode.CONFIG_INVALID_API_KEY,
            "Config error for user admin@internal.company.com"
        )
        assertEquals("Config error for user [EMAIL]", exception.message)
    }

    @Test
    fun `FlagKitException respects custom config passed to factory method`() {
        val customConfig = ErrorSanitizationConfig(enabled = false)
        val message = "Error at /home/user/data.txt"
        val exception = FlagKitException.initError(message, customConfig)
        assertEquals(message, exception.message)
    }

    @Test
    fun `FlagKitException originalMessage is null when preserveOriginal is false`() {
        val originalConfig = FlagKitException.globalConfig
        try {
            FlagKitException.globalConfig = ErrorSanitizationConfig(enabled = true, preserveOriginal = false)
            val exception = FlagKitException.initError("Error at /home/user/data.txt")
            assertNull(exception.originalMessage)
        } finally {
            FlagKitException.globalConfig = originalConfig
        }
    }
}
