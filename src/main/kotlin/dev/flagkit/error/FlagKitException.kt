package dev.flagkit.error

/**
 * Exception for FlagKit SDK errors.
 *
 * Error messages are automatically sanitized to remove sensitive information
 * such as file paths, IP addresses, API keys, and email addresses when created
 * through the companion object factory methods.
 *
 * @param errorCode The error code categorizing this error.
 * @param message The error message (will be sanitized if using factory methods with sanitization enabled).
 * @param cause The underlying cause of the exception.
 */
class FlagKitException(
    val errorCode: ErrorCode,
    override val message: String,
    override val cause: Throwable? = null
) : Exception("[${errorCode.code}] $message", cause) {

    /**
     * The original unsanitized message (only set if preserveOriginal is true in config).
     */
    var originalMessage: String? = null
        internal set

    val isRecoverable: Boolean
        get() = errorCode.isRecoverable

    /**
     * Returns true if the message was sanitized (i.e., it differs from the original).
     */
    val wasSanitized: Boolean
        get() = originalMessage != null && originalMessage != message

    companion object {
        /**
         * Global sanitization configuration. Can be set to customize sanitization behavior
         * for all exceptions created through factory methods.
         */
        var globalConfig: ErrorSanitizationConfig = ErrorSanitizer.DEFAULT_CONFIG

        /**
         * Creates a FlagKitException with message sanitization applied.
         */
        private fun create(
            errorCode: ErrorCode,
            message: String,
            cause: Throwable? = null,
            config: ErrorSanitizationConfig = globalConfig
        ): FlagKitException {
            val sanitizedMessage = ErrorSanitizer.sanitize(message, config)
            return FlagKitException(
                errorCode = errorCode,
                message = sanitizedMessage,
                cause = cause
            ).apply {
                if (config.preserveOriginal && config.enabled) {
                    originalMessage = message
                }
            }
        }

        fun initError(message: String, config: ErrorSanitizationConfig = globalConfig) =
            create(ErrorCode.INIT_FAILED, message, config = config)

        fun authError(code: ErrorCode, message: String, config: ErrorSanitizationConfig = globalConfig) =
            create(code, message, config = config)

        fun networkError(message: String, cause: Throwable? = null, config: ErrorSanitizationConfig = globalConfig) =
            create(ErrorCode.NETWORK_ERROR, message, cause, config)

        fun evalError(code: ErrorCode, message: String, config: ErrorSanitizationConfig = globalConfig) =
            create(code, message, config = config)

        fun configError(code: ErrorCode, message: String, config: ErrorSanitizationConfig = globalConfig) =
            create(code, message, config = config)

        /**
         * Creates an exception with sanitization explicitly disabled.
         * Use with caution - only for internal logging or debugging purposes.
         */
        fun unsanitized(errorCode: ErrorCode, message: String, cause: Throwable? = null) =
            create(errorCode, message, cause, ErrorSanitizationConfig(enabled = false))

        /**
         * Creates a sanitized exception from any error code.
         * This is the recommended way to create exceptions when not using the specialized factory methods.
         */
        fun sanitized(errorCode: ErrorCode, message: String, cause: Throwable? = null, config: ErrorSanitizationConfig = globalConfig) =
            create(errorCode, message, cause, config)
    }
}
