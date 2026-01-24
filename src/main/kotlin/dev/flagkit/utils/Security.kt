package dev.flagkit.utils

/**
 * Security utilities for FlagKit SDK
 */

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
    val additionalPIIPatterns: List<String> = emptyList()
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
