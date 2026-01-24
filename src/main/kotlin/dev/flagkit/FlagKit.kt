package dev.flagkit

import dev.flagkit.error.ErrorCode
import dev.flagkit.error.FlagKitException
import dev.flagkit.types.EvaluationContext
import dev.flagkit.types.EvaluationReason
import dev.flagkit.types.EvaluationResult
import dev.flagkit.types.FlagValue
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Static factory for FlagKit SDK with singleton pattern.
 *
 * This object provides:
 * - Singleton client initialization
 * - Convenience methods for flag evaluation
 * - Thread-safe initialization and shutdown
 *
 * Example usage:
 * ```kotlin
 * // Initialize
 * FlagKit.initialize("sdk_your_api_key")
 *
 * // Wait for ready
 * FlagKit.waitForReady()
 *
 * // Identify user
 * FlagKit.identify("user-123", mapOf("plan" to "premium"))
 *
 * // Evaluate flags
 * val darkMode = FlagKit.getBoolValue("dark-mode", false)
 *
 * // Track events
 * FlagKit.track("button_clicked", mapOf("button" to "signup"))
 *
 * // Cleanup
 * FlagKit.shutdown()
 * ```
 */
object FlagKit {
    private val mutex = Mutex()
    private var _instance: FlagKitClient? = null

    /**
     * Get the current client instance.
     *
     * @return The client instance, or null if not initialized.
     */
    val instance: FlagKitClient?
        get() = _instance

    /**
     * Check if the SDK is initialized.
     *
     * @return True if initialized.
     */
    val isInitialized: Boolean
        get() = _instance != null

    /**
     * Initialize the SDK with options.
     *
     * @param options Configuration options.
     * @return The initialized client.
     * @throws FlagKitException if already initialized.
     */
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

    /**
     * Initialize the SDK with just an API key.
     *
     * @param apiKey The API key.
     * @return The initialized client.
     */
    suspend fun initialize(apiKey: String): FlagKitClient {
        return initialize(FlagKitOptions(apiKey))
    }

    /**
     * Initialize the SDK with a builder.
     *
     * @param apiKey The API key.
     * @param configure Configuration lambda.
     * @return The initialized client.
     */
    suspend fun initialize(
        apiKey: String,
        configure: FlagKitOptions.Builder.() -> Unit
    ): FlagKitClient {
        val builder = FlagKitOptions.Builder(apiKey)
        builder.configure()
        return initialize(builder.build())
    }

    /**
     * Shutdown the SDK and release resources.
     */
    suspend fun shutdown() {
        val client = mutex.withLock {
            val c = _instance
            _instance = null
            c
        }
        client?.close()
    }

    /**
     * Wait for the SDK to be ready.
     */
    suspend fun waitForReady() {
        instance?.waitForReady()
    }

    /**
     * Check if the SDK is ready.
     *
     * @return True if ready.
     */
    fun isReady(): Boolean {
        return instance?.isReady() ?: false
    }

    // Flag Evaluation Methods

    /**
     * Evaluate a boolean flag.
     *
     * @param key The flag key.
     * @param defaultValue The default value.
     * @return The flag value.
     */
    suspend fun getBoolValue(key: String, defaultValue: Boolean): Boolean {
        return instance?.getBoolValue(key, defaultValue) ?: defaultValue
    }

    /**
     * Evaluate a string flag.
     *
     * @param key The flag key.
     * @param defaultValue The default value.
     * @return The flag value.
     */
    suspend fun getStringValue(key: String, defaultValue: String): String {
        return instance?.getStringValue(key, defaultValue) ?: defaultValue
    }

    /**
     * Evaluate a number flag.
     *
     * @param key The flag key.
     * @param defaultValue The default value.
     * @return The flag value.
     */
    suspend fun getNumberValue(key: String, defaultValue: Double): Double {
        return instance?.getNumberValue(key, defaultValue) ?: defaultValue
    }

    /**
     * Evaluate an integer flag.
     *
     * @param key The flag key.
     * @param defaultValue The default value.
     * @return The flag value.
     */
    suspend fun getIntValue(key: String, defaultValue: Long): Long {
        return instance?.getIntValue(key, defaultValue) ?: defaultValue
    }

    /**
     * Evaluate a JSON flag.
     *
     * @param key The flag key.
     * @param defaultValue The default value.
     * @return The flag value.
     */
    suspend fun getJsonValue(key: String, defaultValue: Map<String, Any?>): Map<String, Any?> {
        return instance?.getJsonValue(key, defaultValue) ?: defaultValue
    }

    /**
     * Evaluate a flag with full result details.
     *
     * @param key The flag key.
     * @param defaultValue The default value.
     * @return The evaluation result.
     */
    suspend fun evaluate(key: String, defaultValue: FlagValue): EvaluationResult {
        return instance?.evaluate(key, defaultValue)
            ?: EvaluationResult.defaultResult(key, defaultValue, EvaluationReason.ERROR)
    }

    /**
     * Evaluate all flags.
     *
     * @return Map of flag keys to evaluation results.
     */
    suspend fun evaluateAll(): Map<String, EvaluationResult> {
        return instance?.evaluateAll() ?: emptyMap()
    }

    /**
     * Check if a flag exists.
     *
     * @param key The flag key.
     * @return True if the flag exists.
     */
    suspend fun hasFlag(key: String): Boolean {
        return instance?.hasFlag(key) ?: false
    }

    /**
     * Get all flag keys.
     *
     * @return Set of flag keys.
     */
    suspend fun getAllFlagKeys(): Set<String> {
        return instance?.getAllFlagKeys() ?: emptySet()
    }

    // Context Management

    /**
     * Identify a user.
     *
     * @param userId The user ID.
     * @param attributes Optional attributes.
     */
    suspend fun identify(userId: String, attributes: Map<String, Any?> = emptyMap()) {
        instance?.identify(userId, attributes)
    }

    /**
     * Reset the user context to anonymous.
     */
    suspend fun resetContext() {
        instance?.resetContext()
    }

    /**
     * Clear the user context.
     */
    suspend fun clearContext() {
        instance?.clearContext()
    }

    /**
     * Get the current context.
     *
     * @return The current context, or null.
     */
    suspend fun getContext(): EvaluationContext? {
        return instance?.getContext()
    }

    /**
     * Set the global context.
     *
     * @param context The context to set.
     */
    suspend fun setContext(context: EvaluationContext) {
        instance?.setContext(context)
    }

    // Event Tracking

    /**
     * Track a custom event.
     *
     * @param eventType The event type.
     * @param data Optional event data.
     */
    suspend fun track(eventType: String, data: Map<String, Any?>? = null) {
        instance?.track(eventType, data)
    }

    /**
     * Flush pending events.
     */
    suspend fun flush() {
        instance?.flush()
    }

    // Lifecycle

    /**
     * Force refresh flags from server.
     */
    suspend fun refresh() {
        instance?.refresh()
    }

    /**
     * Close and shutdown the SDK.
     */
    suspend fun close() {
        shutdown()
    }
}
