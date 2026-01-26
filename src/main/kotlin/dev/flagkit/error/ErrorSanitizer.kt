package dev.flagkit.error

/**
 * Configuration for error message sanitization.
 *
 * @param enabled Whether sanitization is enabled. Defaults to true.
 * @param preserveOriginal Whether to preserve the original unsanitized message
 *                         in the exception (accessible via originalMessage property).
 *                         Defaults to false for maximum security.
 */
data class ErrorSanitizationConfig(
    val enabled: Boolean = true,
    val preserveOriginal: Boolean = false
)

/**
 * Sanitizes error messages to remove sensitive information and prevent information leakage.
 *
 * This utility removes patterns that could expose:
 * - File system paths (Unix and Windows)
 * - IP addresses
 * - API keys (sdk_, srv_, cli_ prefixed)
 * - Email addresses
 * - Database connection strings
 */
object ErrorSanitizer {

    /**
     * Patterns to match and their replacements for sanitization.
     * Order matters - more specific patterns should come before general ones.
     */
    private val PATTERNS = listOf(
        // Database connection strings (must come before path patterns)
        Regex("""(?i)(?:postgres|mysql|mongodb|redis)://[^\s]+""") to "[CONNECTION_STRING]",
        // Unix-style paths (at least 2 directory levels)
        Regex("""/(?:[\w.-]+/)+[\w.-]+""") to "[PATH]",
        // Windows-style paths
        Regex("""[A-Za-z]:\\(?:[\w.-]+\\)+[\w.-]*""") to "[PATH]",
        // IP addresses (IPv4)
        Regex("""\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b""") to "[IP]",
        // SDK API keys
        Regex("""sdk_[a-zA-Z0-9_-]{8,}""") to "sdk_[REDACTED]",
        // Server API keys
        Regex("""srv_[a-zA-Z0-9_-]{8,}""") to "srv_[REDACTED]",
        // CLI API keys
        Regex("""cli_[a-zA-Z0-9_-]{8,}""") to "cli_[REDACTED]",
        // Email addresses
        Regex("""[\w.-]+@[\w.-]+\.\w+""") to "[EMAIL]"
    )

    /**
     * Default configuration for error sanitization.
     */
    val DEFAULT_CONFIG = ErrorSanitizationConfig()

    /**
     * Sanitizes an error message by replacing sensitive patterns with placeholders.
     *
     * @param message The message to sanitize.
     * @param config Configuration for sanitization behavior.
     * @return The sanitized message, or the original if sanitization is disabled.
     */
    fun sanitize(message: String, config: ErrorSanitizationConfig = DEFAULT_CONFIG): String {
        if (!config.enabled) {
            return message
        }

        var sanitized = message
        for ((pattern, replacement) in PATTERNS) {
            sanitized = pattern.replace(sanitized, replacement)
        }
        return sanitized
    }

    /**
     * Sanitizes an error message using only the enabled flag.
     *
     * @param message The message to sanitize.
     * @param enabled Whether sanitization is enabled.
     * @return The sanitized message, or the original if sanitization is disabled.
     */
    fun sanitize(message: String, enabled: Boolean): String {
        return sanitize(message, ErrorSanitizationConfig(enabled = enabled))
    }

    /**
     * Checks if a message contains any sensitive patterns.
     *
     * @param message The message to check.
     * @return True if the message contains sensitive information.
     */
    fun containsSensitiveInfo(message: String): Boolean {
        return PATTERNS.any { (pattern, _) -> pattern.containsMatchIn(message) }
    }
}
