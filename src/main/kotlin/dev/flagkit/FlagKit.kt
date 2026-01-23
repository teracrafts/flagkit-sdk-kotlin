package dev.flagkit

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Static factory for FlagKit SDK with singleton pattern.
 */
object FlagKit {
    private val mutex = Mutex()
    private var _instance: FlagKitClient? = null

    val instance: FlagKitClient?
        get() = _instance

    val isInitialized: Boolean
        get() = _instance != null

    suspend fun initialize(options: FlagKitOptions): FlagKitClient = mutex.withLock {
        _instance?.let {
            throw FlagKitException(ErrorCode.INIT_ALREADY_INITIALIZED, "FlagKit is already initialized")
        }

        options.validate()

        val client = FlagKitClient(options)
        client.initialize()
        _instance = client
        client
    }

    suspend fun initialize(apiKey: String): FlagKitClient {
        return initialize(FlagKitOptions(apiKey))
    }

    suspend fun shutdown() {
        val client = mutex.withLock {
            val c = _instance
            _instance = null
            c
        }
        client?.close()
    }

    // Convenience methods
    suspend fun getBoolValue(key: String, defaultValue: Boolean): Boolean {
        return instance?.getBoolValue(key, defaultValue) ?: defaultValue
    }

    suspend fun getStringValue(key: String, defaultValue: String): String {
        return instance?.getStringValue(key, defaultValue) ?: defaultValue
    }

    suspend fun getNumberValue(key: String, defaultValue: Double): Double {
        return instance?.getNumberValue(key, defaultValue) ?: defaultValue
    }

    suspend fun getIntValue(key: String, defaultValue: Long): Long {
        return instance?.getIntValue(key, defaultValue) ?: defaultValue
    }

    suspend fun getJsonValue(key: String, defaultValue: Map<String, Any?>): Map<String, Any?> {
        return instance?.getJsonValue(key, defaultValue) ?: defaultValue
    }

    suspend fun evaluate(key: String, defaultValue: FlagValue): EvaluationResult {
        return instance?.evaluate(key, defaultValue)
            ?: EvaluationResult.defaultResult(key, defaultValue, EvaluationReason.ERROR)
    }

    suspend fun identify(userId: String, attributes: Map<String, Any?> = emptyMap()) {
        instance?.identify(userId, attributes)
    }

    suspend fun resetContext() {
        instance?.resetContext()
    }

    suspend fun track(eventType: String, data: Map<String, Any?>? = null) {
        instance?.track(eventType, data)
    }
}
