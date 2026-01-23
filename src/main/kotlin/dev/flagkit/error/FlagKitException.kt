package dev.flagkit.error

/**
 * Exception for FlagKit SDK errors.
 */
class FlagKitException(
    val errorCode: ErrorCode,
    override val message: String,
    override val cause: Throwable? = null
) : Exception("[${errorCode.code}] $message", cause) {

    val isRecoverable: Boolean
        get() = errorCode.isRecoverable

    companion object {
        fun initError(message: String) = FlagKitException(ErrorCode.INIT_FAILED, message)

        fun authError(code: ErrorCode, message: String) = FlagKitException(code, message)

        fun networkError(message: String, cause: Throwable? = null) =
            FlagKitException(ErrorCode.NETWORK_ERROR, message, cause)

        fun evalError(code: ErrorCode, message: String) = FlagKitException(code, message)

        fun configError(code: ErrorCode, message: String) = FlagKitException(code, message)
    }
}
