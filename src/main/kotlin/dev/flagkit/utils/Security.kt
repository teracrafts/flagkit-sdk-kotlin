package dev.flagkit.utils

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Security utilities for FlagKit SDK
 */

/**
 * Security exception thrown when security violations are detected.
 */
class SecurityException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Logger interface for security warnings.
 * Users can implement this interface to integrate with their own logging system.
 */
interface Logger {
    fun debug(message: String, data: Map<String, Any?>? = null)
    fun info(message: String, data: Map<String, Any?>? = null)
    fun warn(message: String, data: Map<String, Any?>? = null)
    fun error(message: String, data: Map<String, Any?>? = null)
}

/**
 * Security configuration options
 */
data class SecurityConfig(
    /** Warn about potential PII in context/events. Default: true in development */
    val warnOnPotentialPII: Boolean = !isProduction(),
    /** Warn when server keys are used in browser-like environments. Default: true */
    val warnOnServerKeyInBrowser: Boolean = true,
    /** Custom PII patterns to detect (in addition to built-in patterns) */
    val additionalPIIPatterns: List<String> = emptyList(),
    /** Strict PII mode throws exception instead of warning. Default: false */
    val strictPiiMode: Boolean = false,
    /** Secondary API key for automatic failover on 401 errors */
    val secondaryApiKey: String? = null,
    /** Enable request signing with HMAC-SHA256. Default: true */
    val enableRequestSigning: Boolean = true,
    /** Enable cache encryption with AES-256-GCM. Default: false */
    val enableCacheEncryption: Boolean = false
) {
    companion object {
        /**
         * Default security configuration
         */
        val DEFAULT = SecurityConfig()
    }
}

/**
 * Common PII field patterns (case-insensitive)
 */
private val PII_PATTERNS = listOf(
    "email",
    "phone",
    "telephone",
    "mobile",
    "ssn",
    "social_security",
    "socialSecurity",
    "credit_card",
    "creditCard",
    "card_number",
    "cardNumber",
    "cvv",
    "password",
    "passwd",
    "secret",
    "token",
    "api_key",
    "apiKey",
    "private_key",
    "privateKey",
    "access_token",
    "accessToken",
    "refresh_token",
    "refreshToken",
    "auth_token",
    "authToken",
    "address",
    "street",
    "zip_code",
    "zipCode",
    "postal_code",
    "postalCode",
    "date_of_birth",
    "dateOfBirth",
    "dob",
    "birth_date",
    "birthDate",
    "passport",
    "driver_license",
    "driverLicense",
    "national_id",
    "nationalId",
    "bank_account",
    "bankAccount",
    "routing_number",
    "routingNumber",
    "iban",
    "swift"
)

/**
 * Check if we're running in a production environment.
 */
private fun isProduction(): Boolean {
    return System.getenv("ENVIRONMENT")?.lowercase() == "production" ||
           System.getenv("NODE_ENV")?.lowercase() == "production" ||
           System.getenv("ENV")?.lowercase() == "production" ||
           System.getProperty("environment")?.lowercase() == "production"
}

/**
 * Check if we're running in a browser-like environment (e.g., Android WebView, embedded browser).
 * This is a heuristic check - in pure JVM/Android server environments, this returns false.
 */
internal fun isBrowserLikeEnvironment(): Boolean {
    // Check for Android WebView or browser-like environments
    val userAgent = System.getProperty("http.agent")?.lowercase() ?: ""
    val isWebView = userAgent.contains("webview") ||
                    userAgent.contains("browser") ||
                    System.getProperty("webview.enabled")?.toBoolean() == true

    // Check if explicitly marked as browser/client environment
    val envType = System.getenv("FLAGKIT_ENVIRONMENT_TYPE")?.lowercase()
    val isBrowserEnv = envType == "browser" || envType == "client" || envType == "webview"

    return isWebView || isBrowserEnv
}

/**
 * Check if a field name potentially contains PII.
 *
 * @param fieldName The field name to check
 * @param additionalPatterns Optional additional patterns to check against
 * @return true if the field name matches any PII pattern
 */
fun isPotentialPIIField(
    fieldName: String,
    additionalPatterns: List<String> = emptyList()
): Boolean {
    val lowerName = fieldName.lowercase()
    val allPatterns = PII_PATTERNS + additionalPatterns
    return allPatterns.any { pattern -> lowerName.contains(pattern.lowercase()) }
}

/**
 * Detect potential PII in a data map and return the field paths.
 *
 * @param data The data to check for PII fields
 * @param prefix Optional prefix for nested field paths
 * @param additionalPatterns Optional additional patterns to check against
 * @return List of field paths that potentially contain PII
 */
fun detectPotentialPII(
    data: Map<String, Any?>,
    prefix: String = "",
    additionalPatterns: List<String> = emptyList()
): List<String> {
    val piiFields = mutableListOf<String>()

    for ((key, value) in data) {
        val fullPath = if (prefix.isEmpty()) key else "$prefix.$key"

        if (isPotentialPIIField(key, additionalPatterns)) {
            piiFields.add(fullPath)
        }

        // Recursively check nested maps
        @Suppress("UNCHECKED_CAST")
        if (value is Map<*, *>) {
            val nestedMap = value as? Map<String, Any?> ?: continue
            val nestedPII = detectPotentialPII(nestedMap, fullPath, additionalPatterns)
            piiFields.addAll(nestedPII)
        }
    }

    return piiFields
}

/**
 * Data type for PII warnings
 */
enum class DataType(val displayName: String) {
    CONTEXT("context"),
    EVENT("event")
}

/**
 * Warn about potential PII in data.
 *
 * @param data The data to check for PII
 * @param dataType The type of data being checked (context or event)
 * @param logger Optional logger for outputting warnings
 * @param additionalPatterns Optional additional patterns to check against
 */
fun warnIfPotentialPII(
    data: Map<String, Any?>?,
    dataType: DataType,
    logger: Logger?,
    additionalPatterns: List<String> = emptyList()
) {
    if (data == null || logger == null) {
        return
    }

    val piiFields = detectPotentialPII(data, additionalPatterns = additionalPatterns)

    if (piiFields.isNotEmpty()) {
        val fieldsList = piiFields.joinToString(", ")
        val advice = when (dataType) {
            DataType.CONTEXT -> "Consider adding these to privateAttributes."
            DataType.EVENT -> "Consider removing sensitive data from events."
        }
        logger.warn(
            "[FlagKit Security] Potential PII detected in ${dataType.displayName} data: $fieldsList. $advice"
        )
    }
}

/**
 * Convenience overload that accepts a string for dataType.
 */
fun warnIfPotentialPII(
    data: Map<String, Any?>?,
    dataType: String,
    logger: Logger?,
    additionalPatterns: List<String> = emptyList()
) {
    val type = when (dataType.lowercase()) {
        "context" -> DataType.CONTEXT
        "event" -> DataType.EVENT
        else -> DataType.CONTEXT
    }
    warnIfPotentialPII(data, type, logger, additionalPatterns)
}

/**
 * Check if an API key is a server key.
 *
 * @param apiKey The API key to check
 * @return true if the key starts with "srv_"
 */
fun isServerKey(apiKey: String): Boolean {
    return apiKey.startsWith("srv_")
}

/**
 * Check if an API key is a client/SDK key.
 *
 * @param apiKey The API key to check
 * @return true if the key starts with "sdk_" or "cli_"
 */
fun isClientKey(apiKey: String): Boolean {
    return apiKey.startsWith("sdk_") || apiKey.startsWith("cli_")
}

/**
 * Warn if a server key is used in a browser-like environment.
 *
 * @param apiKey The API key to check
 * @param logger Optional logger for outputting warnings
 */
fun warnIfServerKeyInBrowser(apiKey: String, logger: Logger?) {
    if (isBrowserLikeEnvironment() && isServerKey(apiKey)) {
        val message = "[FlagKit Security] WARNING: Server keys (srv_) should not be used in browser environments. " +
            "This exposes your server key in client-side code, which is a security risk. " +
            "Use SDK keys (sdk_) for client-side applications instead. " +
            "See: https://docs.flagkit.dev/sdk/security#api-keys"

        // Always print to stderr for visibility
        System.err.println(message)

        // Also log through the SDK logger if available
        logger?.warn(message)
    }
}

// Extension functions for more idiomatic Kotlin usage

/**
 * Extension function to check if a string field name potentially contains PII.
 */
fun String.isPotentialPII(additionalPatterns: List<String> = emptyList()): Boolean {
    return isPotentialPIIField(this, additionalPatterns)
}

/**
 * Extension function to detect potential PII in a map.
 */
fun Map<String, Any?>.detectPII(
    prefix: String = "",
    additionalPatterns: List<String> = emptyList()
): List<String> {
    return detectPotentialPII(this, prefix, additionalPatterns)
}

/**
 * Extension function to check if an API key is a server key.
 */
fun String.isServerApiKey(): Boolean = isServerKey(this)

/**
 * Extension function to check if an API key is a client key.
 */
fun String.isClientApiKey(): Boolean = isClientKey(this)

// ============= Request Signing (HMAC-SHA256) =============

/**
 * Request signature result containing signature and metadata
 */
data class RequestSignature(
    val signature: String,
    val timestamp: Long,
    val keyId: String
)

/**
 * Get the first 8 characters of an API key for identification.
 * This is safe to expose as it doesn't reveal the full key.
 *
 * @param apiKey The API key to get the identifier from
 * @return First 8 characters of the API key
 */
fun getKeyId(apiKey: String): String {
    return if (apiKey.length >= 8) apiKey.substring(0, 8) else apiKey
}

/**
 * Generate HMAC-SHA256 signature for a message.
 *
 * @param message The message to sign
 * @param key The secret key for signing
 * @return Hex-encoded signature string
 */
fun generateHmacSha256(message: String, key: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    val secretKey = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256")
    mac.init(secretKey)
    val hmacBytes = mac.doFinal(message.toByteArray(Charsets.UTF_8))
    return hmacBytes.joinToString("") { "%02x".format(it) }
}

/**
 * Create a request signature for POST request bodies.
 *
 * The signature is computed as HMAC-SHA256(timestamp.body, apiKey).
 * This ensures request integrity and prevents replay attacks.
 *
 * @param body The request body to sign
 * @param apiKey The API key to use for signing
 * @param timestamp Optional timestamp (defaults to current time)
 * @return RequestSignature containing signature, timestamp, and key ID
 */
fun createRequestSignature(
    body: String,
    apiKey: String,
    timestamp: Long = System.currentTimeMillis()
): RequestSignature {
    val message = "$timestamp.$body"
    val signature = generateHmacSha256(message, apiKey)
    return RequestSignature(
        signature = signature,
        timestamp = timestamp,
        keyId = getKeyId(apiKey)
    )
}

/**
 * Verify a request signature.
 *
 * @param body The request body that was signed
 * @param signature The signature to verify
 * @param timestamp The timestamp from the signature
 * @param keyId The key ID from the signature
 * @param apiKey The API key to verify against
 * @param maxAgeMs Maximum age of the signature in milliseconds (default 5 minutes)
 * @return True if the signature is valid and not expired
 */
fun verifyRequestSignature(
    body: String,
    signature: String,
    timestamp: Long,
    keyId: String,
    apiKey: String,
    maxAgeMs: Long = 300000
): Boolean {
    // Check timestamp age
    val age = System.currentTimeMillis() - timestamp
    if (age > maxAgeMs || age < 0) {
        return false
    }

    // Verify key ID matches
    if (keyId != getKeyId(apiKey)) {
        return false
    }

    // Verify signature
    val message = "$timestamp.$body"
    val expectedSignature = generateHmacSha256(message, apiKey)
    return signature == expectedSignature
}

// ============= Bootstrap Signature Verification =============

/**
 * Result of bootstrap signature verification.
 *
 * @param valid Whether the signature verification passed.
 * @param errorMessage Error message if verification failed, null otherwise.
 */
data class BootstrapVerificationResult(
    val valid: Boolean,
    val errorMessage: String?
)

/**
 * Canonicalize an object to a deterministic JSON string for signature verification.
 *
 * This function ensures consistent ordering of keys and formatting to produce
 * the same output regardless of the original key ordering in the input map.
 *
 * @param obj The object to canonicalize.
 * @return A deterministic JSON string representation.
 */
fun canonicalizeObject(obj: Map<String, Any?>): String {
    return buildCanonicalString(obj)
}

/**
 * Internal function to recursively build canonical JSON string.
 */
private fun buildCanonicalString(value: Any?): String {
    return when (value) {
        null -> "null"
        is Boolean -> value.toString()
        is Number -> {
            // Handle floating point numbers - remove trailing zeros
            when {
                value is Double || value is Float -> {
                    val d = value.toDouble()
                    if (d == d.toLong().toDouble()) {
                        d.toLong().toString()
                    } else {
                        d.toString()
                    }
                }
                else -> value.toString()
            }
        }
        is String -> "\"${escapeJsonString(value)}\""
        is Map<*, *> -> {
            @Suppress("UNCHECKED_CAST")
            val map = value as Map<String, Any?>
            val sortedEntries = map.entries.sortedBy { it.key }
            val pairs = sortedEntries.map { (k, v) ->
                "\"${escapeJsonString(k)}\":${buildCanonicalString(v)}"
            }
            "{${pairs.joinToString(",")}}"
        }
        is List<*> -> {
            val items = value.map { buildCanonicalString(it) }
            "[${items.joinToString(",")}]"
        }
        is Array<*> -> {
            val items = value.map { buildCanonicalString(it) }
            "[${items.joinToString(",")}]"
        }
        else -> "\"${escapeJsonString(value.toString())}\""
    }
}

/**
 * Escape special characters in a JSON string.
 */
private fun escapeJsonString(str: String): String {
    val sb = StringBuilder()
    for (c in str) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> {
                if (c.code < 0x20) {
                    sb.append("\\u${c.code.toString(16).padStart(4, '0')}")
                } else {
                    sb.append(c)
                }
            }
        }
    }
    return sb.toString()
}

/**
 * Verify a bootstrap signature using HMAC-SHA256.
 *
 * This function verifies that:
 * 1. The signature matches the HMAC-SHA256 of the canonicalized flags data
 * 2. The timestamp (if provided) is not older than the configured maxAge
 *
 * Uses constant-time comparison to prevent timing attacks.
 *
 * @param flags The bootstrap flags data.
 * @param signature The HMAC-SHA256 signature to verify.
 * @param timestamp Optional timestamp when the bootstrap was generated.
 * @param apiKey The API key to use for HMAC computation.
 * @param maxAge Maximum age of the bootstrap data in milliseconds.
 * @param verificationEnabled Whether verification is enabled.
 * @return BootstrapVerificationResult indicating success or failure with message.
 */
fun verifyBootstrapSignature(
    flags: Map<String, Any?>,
    signature: String?,
    timestamp: Long?,
    apiKey: String,
    maxAge: Long,
    verificationEnabled: Boolean = true
): BootstrapVerificationResult {
    // If verification is disabled, always pass
    if (!verificationEnabled) {
        return BootstrapVerificationResult(valid = true, errorMessage = null)
    }

    // If no signature provided, verification cannot proceed
    if (signature == null) {
        return BootstrapVerificationResult(
            valid = false,
            errorMessage = "Bootstrap signature is missing"
        )
    }

    // Check timestamp expiration if provided
    if (timestamp != null) {
        val now = System.currentTimeMillis()
        val age = now - timestamp

        if (age > maxAge) {
            return BootstrapVerificationResult(
                valid = false,
                errorMessage = "Bootstrap data has expired (age: ${age}ms, maxAge: ${maxAge}ms)"
            )
        }

        if (age < 0) {
            return BootstrapVerificationResult(
                valid = false,
                errorMessage = "Bootstrap timestamp is in the future"
            )
        }
    }

    // Canonicalize the flags data
    val canonicalData = canonicalizeObject(flags)

    // Generate expected signature
    val expectedSignature = generateHmacSha256(canonicalData, apiKey)

    // Constant-time comparison to prevent timing attacks
    val signatureBytes = signature.toByteArray(Charsets.UTF_8)
    val expectedBytes = expectedSignature.toByteArray(Charsets.UTF_8)

    val isValid = java.security.MessageDigest.isEqual(signatureBytes, expectedBytes)

    return if (isValid) {
        BootstrapVerificationResult(valid = true, errorMessage = null)
    } else {
        BootstrapVerificationResult(
            valid = false,
            errorMessage = "Bootstrap signature verification failed"
        )
    }
}

/**
 * Generate a signature for bootstrap data.
 *
 * This is a utility function for creating signed bootstrap configurations.
 *
 * @param flags The flags data to sign.
 * @param apiKey The API key to use for signing.
 * @return The HMAC-SHA256 signature as a hex string.
 */
fun generateBootstrapSignature(flags: Map<String, Any?>, apiKey: String): String {
    val canonicalData = canonicalizeObject(flags)
    return generateHmacSha256(canonicalData, apiKey)
}

// ============= LocalPort Restriction =============

/**
 * Validate that localPort is not used in production environment.
 *
 * @param localPort The local port configuration
 * @throws SecurityException if localPort is used in production
 */
fun validateLocalPortRestriction(localPort: Int?) {
    if (localPort != null && isProductionAppEnv()) {
        throw SecurityException(
            "LocalPort cannot be used in production environment. " +
            "APP_ENV is set to 'production' but localPort is configured to $localPort. " +
            "Remove localPort configuration or use a non-production environment."
        )
    }
}

/**
 * Check if APP_ENV environment variable is set to "production".
 */
internal fun isProductionAppEnv(): Boolean {
    return System.getenv("APP_ENV")?.lowercase() == "production"
}

// ============= Key Rotation =============

/**
 * Key rotation manager for automatic failover on 401 errors.
 */
class KeyRotationManager(
    private var primaryApiKey: String,
    private val secondaryApiKey: String?
) {
    @Volatile
    private var usingSecondary: Boolean = false

    /**
     * Get the current active API key.
     *
     * @return The current API key (primary or secondary)
     */
    fun getCurrentKey(): String {
        return if (usingSecondary && secondaryApiKey != null) {
            secondaryApiKey
        } else {
            primaryApiKey
        }
    }

    /**
     * Check if a secondary key is configured.
     *
     * @return True if secondary key is available
     */
    fun hasSecondaryKey(): Boolean = secondaryApiKey != null

    /**
     * Check if currently using the secondary key.
     *
     * @return True if secondary key is active
     */
    fun isUsingSecondary(): Boolean = usingSecondary

    /**
     * Handle a 401 authentication error by failing over to secondary key.
     *
     * @return True if failover was successful (secondary key available and not already used)
     */
    fun handleAuthError(): Boolean {
        if (!usingSecondary && secondaryApiKey != null) {
            usingSecondary = true
            return true
        }
        return false
    }

    /**
     * Reset to primary key.
     */
    fun reset() {
        usingSecondary = false
    }

    /**
     * Update the primary key (e.g., after successful key rotation).
     *
     * @param newPrimaryKey The new primary API key
     */
    fun updatePrimaryKey(newPrimaryKey: String) {
        primaryApiKey = newPrimaryKey
        usingSecondary = false
    }
}

// ============= Strict PII Mode =============

/**
 * PII detection result containing detection status and field information.
 */
data class PiiDetectionResult(
    val hasPii: Boolean,
    val fields: List<String>,
    val message: String
)

/**
 * Check for potential PII in data and return detailed result.
 *
 * @param data The data to check for PII
 * @param dataType The type of data being checked (context or event)
 * @param privateAttributes List of fields that are explicitly marked as private
 * @param additionalPatterns Optional additional patterns to check
 * @return PiiDetectionResult with detection details
 */
fun checkForPotentialPii(
    data: Map<String, Any?>?,
    dataType: DataType,
    privateAttributes: Set<String> = emptySet(),
    additionalPatterns: List<String> = emptyList()
): PiiDetectionResult {
    if (data == null) {
        return PiiDetectionResult(hasPii = false, fields = emptyList(), message = "")
    }

    val piiFields = detectPotentialPII(data, additionalPatterns = additionalPatterns)

    // Filter out fields that are in privateAttributes
    val unprotectedPiiFields = piiFields.filter { field ->
        val fieldParts = field.split(".")
        !fieldParts.any { part -> privateAttributes.contains(part) }
    }

    if (unprotectedPiiFields.isEmpty()) {
        return PiiDetectionResult(hasPii = false, fields = emptyList(), message = "")
    }

    val advice = when (dataType) {
        DataType.CONTEXT -> "Consider adding these to privateAttributes."
        DataType.EVENT -> "Consider removing sensitive data from events."
    }

    val message = "[FlagKit Security] Potential PII detected in ${dataType.displayName} data: " +
                  "${unprotectedPiiFields.joinToString(", ")}. $advice"

    return PiiDetectionResult(
        hasPii = true,
        fields = unprotectedPiiFields,
        message = message
    )
}

/**
 * Check for PII and throw exception in strict mode.
 *
 * @param data The data to check for PII
 * @param dataType The type of data being checked
 * @param strictMode If true, throws SecurityException when PII is detected
 * @param logger Optional logger for warnings
 * @param privateAttributes Fields marked as private
 * @param additionalPatterns Optional additional patterns to check
 * @throws SecurityException if strictMode is true and PII is detected
 */
fun enforceNoPii(
    data: Map<String, Any?>?,
    dataType: DataType,
    strictMode: Boolean,
    logger: Logger? = null,
    privateAttributes: Set<String> = emptySet(),
    additionalPatterns: List<String> = emptyList()
) {
    val result = checkForPotentialPii(data, dataType, privateAttributes, additionalPatterns)

    if (result.hasPii) {
        if (strictMode) {
            throw SecurityException(
                "[FlagKit Security] PII detected in ${dataType.displayName} data without privateAttributes: " +
                "${result.fields.joinToString(", ")}. In strict PII mode, all PII must be marked in privateAttributes."
            )
        } else {
            logger?.warn(result.message)
        }
    }
}

// ============= Cache Encryption (AES-256-GCM) =============

/**
 * Encrypted cache storage using AES-256-GCM.
 *
 * Key derivation uses PBKDF2 with HMAC-SHA256.
 * Each encryption uses a unique random IV/nonce.
 */
class EncryptedStorage(
    apiKey: String,
    private val salt: ByteArray = generateSalt()
) {
    private val secretKey: SecretKeySpec

    companion object {
        private const val ALGORITHM = "AES/GCM/NoPadding"
        private const val KEY_SIZE = 256
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
        private const val PBKDF2_ITERATIONS = 100000
        private const val SALT_LENGTH = 16

        /**
         * Generate a cryptographically secure random salt.
         */
        fun generateSalt(): ByteArray {
            val salt = ByteArray(SALT_LENGTH)
            SecureRandom().nextBytes(salt)
            return salt
        }

        /**
         * Create an EncryptedStorage with a previously stored salt.
         *
         * @param apiKey The API key to derive encryption key from
         * @param saltBase64 Base64-encoded salt from previous encryption
         * @return EncryptedStorage instance
         */
        fun withSalt(apiKey: String, saltBase64: String): EncryptedStorage {
            val salt = Base64.getDecoder().decode(saltBase64)
            return EncryptedStorage(apiKey, salt)
        }
    }

    init {
        secretKey = deriveKey(apiKey, salt)
    }

    /**
     * Derive an AES key from the API key using PBKDF2.
     */
    private fun deriveKey(apiKey: String, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(apiKey.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_SIZE)
        val secretKey = factory.generateSecret(spec)
        return SecretKeySpec(secretKey.encoded, "AES")
    }

    /**
     * Encrypt data using AES-256-GCM.
     *
     * @param plaintext The data to encrypt
     * @return Base64-encoded encrypted data (IV prepended)
     */
    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(ALGORITHM)
        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)

        val parameterSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec)

        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        // Prepend IV to ciphertext
        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)

        return Base64.getEncoder().encodeToString(combined)
    }

    /**
     * Decrypt data using AES-256-GCM.
     *
     * @param encryptedData Base64-encoded encrypted data (IV prepended)
     * @return Decrypted plaintext
     * @throws SecurityException if decryption fails
     */
    fun decrypt(encryptedData: String): String {
        try {
            val combined = Base64.getDecoder().decode(encryptedData)

            if (combined.size < GCM_IV_LENGTH) {
                throw SecurityException("Invalid encrypted data: too short")
            }

            val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
            val ciphertext = combined.copyOfRange(GCM_IV_LENGTH, combined.size)

            val cipher = Cipher.getInstance(ALGORITHM)
            val parameterSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec)

            val plaintext = cipher.doFinal(ciphertext)
            return String(plaintext, Charsets.UTF_8)
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            throw SecurityException("Decryption failed: ${e.message}", e)
        }
    }

    /**
     * Get the salt used for key derivation.
     * This should be stored alongside encrypted data for later decryption.
     *
     * @return Base64-encoded salt
     */
    fun getSaltBase64(): String {
        return Base64.getEncoder().encodeToString(salt)
    }
}

/**
 * Encrypted cache wrapper that encrypts values before storage.
 *
 * @param T The type of values to store (must be serializable to JSON)
 */
class EncryptedCache<T>(
    private val storage: EncryptedStorage,
    private val serializer: (T) -> String,
    private val deserializer: (String) -> T
) {
    private val cache = mutableMapOf<String, String>()

    /**
     * Store an encrypted value.
     *
     * @param key The cache key
     * @param value The value to encrypt and store
     */
    fun set(key: String, value: T) {
        val plaintext = serializer(value)
        val encrypted = storage.encrypt(plaintext)
        cache[key] = encrypted
    }

    /**
     * Retrieve and decrypt a value.
     *
     * @param key The cache key
     * @return The decrypted value, or null if not found
     */
    fun get(key: String): T? {
        val encrypted = cache[key] ?: return null
        val plaintext = storage.decrypt(encrypted)
        return deserializer(plaintext)
    }

    /**
     * Check if a key exists.
     *
     * @param key The cache key
     * @return True if the key exists
     */
    fun has(key: String): Boolean = cache.containsKey(key)

    /**
     * Remove a key from the cache.
     *
     * @param key The cache key
     * @return True if the key was removed
     */
    fun remove(key: String): Boolean = cache.remove(key) != null

    /**
     * Clear all cached values.
     */
    fun clear() = cache.clear()

    /**
     * Get all keys.
     *
     * @return Set of cache keys
     */
    fun keys(): Set<String> = cache.keys.toSet()

    /**
     * Get the number of cached items.
     *
     * @return Cache size
     */
    fun size(): Int = cache.size

    /**
     * Export encrypted data for persistence.
     * Does NOT decrypt the values - exports raw encrypted data.
     *
     * @return Map of keys to encrypted values
     */
    fun exportEncrypted(): Map<String, String> = cache.toMap()

    /**
     * Import encrypted data from persistence.
     * Assumes data was exported with exportEncrypted().
     *
     * @param data Map of keys to encrypted values
     */
    fun importEncrypted(data: Map<String, String>) {
        cache.putAll(data)
    }
}
