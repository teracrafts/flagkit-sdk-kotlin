package dev.flagkit

import dev.flagkit.error.ErrorCode
import dev.flagkit.error.FlagKitException
import dev.flagkit.utils.SecurityConfig
import dev.flagkit.utils.validateLocalPortRestriction
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration for evaluation jitter to protect against cache timing attacks.
 *
 * When enabled, adds a random delay at the start of flag evaluation to make
 * timing-based attacks more difficult.
 *
 * @param enabled Whether jitter is enabled. Defaults to false.
 * @param minMs Minimum jitter delay in milliseconds. Defaults to 5.
 * @param maxMs Maximum jitter delay in milliseconds. Defaults to 15.
 */
data class EvaluationJitterConfig(
    val enabled: Boolean = false,
    val minMs: Long = 5,
    val maxMs: Long = 15
)

/**
 * Configuration for bootstrap flag values with optional HMAC-SHA256 signature verification.
 *
 * This allows pre-loading flag values at initialization time with cryptographic verification
 * to ensure the bootstrap data has not been tampered with.
 *
 * @param flags Map of flag configurations to bootstrap.
 * @param signature Optional HMAC-SHA256 signature of the canonicalized flags data.
 * @param timestamp Optional timestamp (in milliseconds) when the bootstrap data was generated.
 */
data class BootstrapConfig(
    val flags: Map<String, Any?>,
    val signature: String? = null,
    val timestamp: Long? = null
)

/**
 * Configuration for bootstrap signature verification behavior.
 *
 * @param enabled Whether signature verification is enabled. Defaults to true.
 * @param maxAge Maximum age of bootstrap data in milliseconds. Defaults to 24 hours.
 * @param onFailure Behavior when verification fails: "warn" (log warning and continue),
 *                  "error" (throw exception), or "ignore" (skip bootstrap silently).
 */
data class BootstrapVerificationConfig(
    val enabled: Boolean = true,
    val maxAge: Long = 86400000L, // 24 hours in milliseconds
    val onFailure: String = "warn" // "warn", "error", "ignore"
) {
    init {
        require(onFailure in listOf("warn", "error", "ignore")) {
            "onFailure must be one of: warn, error, ignore"
        }
    }
}

/**
 * Configuration options for the FlagKit SDK.
 */
data class FlagKitOptions(
    val apiKey: String,
    val pollingInterval: Duration = DEFAULT_POLLING_INTERVAL,
    val cacheTtl: Duration = DEFAULT_CACHE_TTL,
    val maxCacheSize: Int = DEFAULT_MAX_CACHE_SIZE,
    val cacheEnabled: Boolean = true,
    val eventBatchSize: Int = DEFAULT_EVENT_BATCH_SIZE,
    val eventFlushInterval: Duration = DEFAULT_EVENT_FLUSH_INTERVAL,
    val eventsEnabled: Boolean = true,
    val timeout: Duration = DEFAULT_TIMEOUT,
    val retryAttempts: Int = DEFAULT_RETRY_ATTEMPTS,
    val circuitBreakerThreshold: Int = DEFAULT_CIRCUIT_BREAKER_THRESHOLD,
    val circuitBreakerResetTimeout: Duration = DEFAULT_CIRCUIT_BREAKER_RESET_TIMEOUT,
    val bootstrap: Map<String, Any>? = null,
    /** Bootstrap configuration with signature verification support */
    val bootstrapConfig: BootstrapConfig? = null,
    /** Bootstrap verification settings */
    val bootstrapVerification: BootstrapVerificationConfig = BootstrapVerificationConfig(),
    val localPort: Int? = null,
    val isLocal: Boolean = false,
    /** Secondary API key for automatic failover on 401 errors */
    val secondaryApiKey: String? = null,
    /** Enable strict PII mode - throws exception instead of warning */
    val strictPiiMode: Boolean = false,
    /** Enable request signing with HMAC-SHA256 for POST requests */
    val enableRequestSigning: Boolean = true,
    /** Enable cache encryption with AES-256-GCM */
    val enableCacheEncryption: Boolean = false,
    /** Security configuration options */
    val securityConfig: SecurityConfig = SecurityConfig.DEFAULT,
    /** Enable crash-resilient event persistence to disk */
    val persistEvents: Boolean = false,
    /** Directory path for event storage files (defaults to system temp dir) */
    val eventStoragePath: String? = null,
    /** Maximum number of events to persist */
    val maxPersistedEvents: Int = DEFAULT_MAX_PERSISTED_EVENTS,
    /** Milliseconds between persistence flush operations */
    val persistenceFlushIntervalMs: Long = DEFAULT_PERSISTENCE_FLUSH_INTERVAL_MS,
    /** Evaluation jitter configuration for timing attack protection */
    val evaluationJitter: EvaluationJitterConfig = EvaluationJitterConfig()
) {
    fun validate() {
        require(apiKey.isNotBlank()) {
            throw FlagKitException.configError(ErrorCode.CONFIG_INVALID_API_KEY, "API key is required")
        }

        val validPrefixes = listOf("sdk_", "srv_", "cli_")
        require(validPrefixes.any { apiKey.startsWith(it) }) {
            throw FlagKitException.configError(ErrorCode.CONFIG_INVALID_API_KEY, "Invalid API key format")
        }

        require(pollingInterval.isPositive()) {
            throw FlagKitException.configError(
                ErrorCode.CONFIG_INVALID_POLLING_INTERVAL,
                "Polling interval must be positive"
            )
        }

        require(cacheTtl.isPositive()) {
            throw FlagKitException.configError(ErrorCode.CONFIG_INVALID_CACHE_TTL, "Cache TTL must be positive")
        }

        // Validate localPort restriction in production
        validateLocalPortRestriction(localPort)

        // Validate secondary API key format if provided
        if (secondaryApiKey != null) {
            require(validPrefixes.any { secondaryApiKey.startsWith(it) }) {
                throw FlagKitException.configError(
                    ErrorCode.CONFIG_INVALID_API_KEY,
                    "Invalid secondary API key format"
                )
            }
        }
    }

    class Builder(private val apiKey: String) {
        private var pollingInterval = DEFAULT_POLLING_INTERVAL
        private var cacheTtl = DEFAULT_CACHE_TTL
        private var maxCacheSize = DEFAULT_MAX_CACHE_SIZE
        private var cacheEnabled = true
        private var eventBatchSize = DEFAULT_EVENT_BATCH_SIZE
        private var eventFlushInterval = DEFAULT_EVENT_FLUSH_INTERVAL
        private var eventsEnabled = true
        private var timeout = DEFAULT_TIMEOUT
        private var retryAttempts = DEFAULT_RETRY_ATTEMPTS
        private var circuitBreakerThreshold = DEFAULT_CIRCUIT_BREAKER_THRESHOLD
        private var circuitBreakerResetTimeout = DEFAULT_CIRCUIT_BREAKER_RESET_TIMEOUT
        private var bootstrap: Map<String, Any>? = null
        private var bootstrapConfig: BootstrapConfig? = null
        private var bootstrapVerification = BootstrapVerificationConfig()
        private var localPort: Int? = null
        private var isLocal = false
        private var secondaryApiKey: String? = null
        private var strictPiiMode = false
        private var enableRequestSigning = true
        private var enableCacheEncryption = false
        private var securityConfig = SecurityConfig.DEFAULT
        private var persistEvents = false
        private var eventStoragePath: String? = null
        private var maxPersistedEvents = DEFAULT_MAX_PERSISTED_EVENTS
        private var persistenceFlushIntervalMs = DEFAULT_PERSISTENCE_FLUSH_INTERVAL_MS
        private var evaluationJitter = EvaluationJitterConfig()

        fun pollingInterval(interval: Duration) = apply { this.pollingInterval = interval }
        fun cacheTtl(ttl: Duration) = apply { this.cacheTtl = ttl }
        fun maxCacheSize(size: Int) = apply { this.maxCacheSize = size }
        fun cacheEnabled(enabled: Boolean) = apply { this.cacheEnabled = enabled }
        fun eventBatchSize(size: Int) = apply { this.eventBatchSize = size }
        fun eventFlushInterval(interval: Duration) = apply { this.eventFlushInterval = interval }
        fun eventsEnabled(enabled: Boolean) = apply { this.eventsEnabled = enabled }
        fun timeout(timeout: Duration) = apply { this.timeout = timeout }
        fun retryAttempts(attempts: Int) = apply { this.retryAttempts = attempts }
        fun bootstrap(data: Map<String, Any>) = apply { this.bootstrap = data }
        fun bootstrapConfig(config: BootstrapConfig) = apply { this.bootstrapConfig = config }
        fun bootstrapVerification(config: BootstrapVerificationConfig) = apply { this.bootstrapVerification = config }
        fun localPort(port: Int) = apply { this.localPort = port }
        fun isLocal(local: Boolean = true) = apply { this.isLocal = local }
        fun secondaryApiKey(key: String) = apply { this.secondaryApiKey = key }
        fun strictPiiMode(enabled: Boolean = true) = apply { this.strictPiiMode = enabled }
        fun enableRequestSigning(enabled: Boolean = true) = apply { this.enableRequestSigning = enabled }
        fun enableCacheEncryption(enabled: Boolean = true) = apply { this.enableCacheEncryption = enabled }
        fun securityConfig(config: SecurityConfig) = apply { this.securityConfig = config }
        fun persistEvents(enabled: Boolean = true) = apply { this.persistEvents = enabled }
        fun eventStoragePath(path: String) = apply { this.eventStoragePath = path }
        fun maxPersistedEvents(max: Int) = apply { this.maxPersistedEvents = max }
        fun persistenceFlushIntervalMs(intervalMs: Long) = apply { this.persistenceFlushIntervalMs = intervalMs }
        fun evaluationJitter(config: EvaluationJitterConfig) = apply { this.evaluationJitter = config }

        fun build() = FlagKitOptions(
            apiKey = apiKey,
            pollingInterval = pollingInterval,
            cacheTtl = cacheTtl,
            maxCacheSize = maxCacheSize,
            cacheEnabled = cacheEnabled,
            eventBatchSize = eventBatchSize,
            eventFlushInterval = eventFlushInterval,
            eventsEnabled = eventsEnabled,
            timeout = timeout,
            retryAttempts = retryAttempts,
            circuitBreakerThreshold = circuitBreakerThreshold,
            circuitBreakerResetTimeout = circuitBreakerResetTimeout,
            bootstrap = bootstrap,
            bootstrapConfig = bootstrapConfig,
            bootstrapVerification = bootstrapVerification,
            localPort = localPort,
            isLocal = isLocal,
            secondaryApiKey = secondaryApiKey,
            strictPiiMode = strictPiiMode,
            enableRequestSigning = enableRequestSigning,
            enableCacheEncryption = enableCacheEncryption,
            securityConfig = securityConfig,
            persistEvents = persistEvents,
            eventStoragePath = eventStoragePath,
            maxPersistedEvents = maxPersistedEvents,
            persistenceFlushIntervalMs = persistenceFlushIntervalMs,
            evaluationJitter = evaluationJitter
        )
    }

    companion object {
        val DEFAULT_POLLING_INTERVAL = 30.seconds
        val DEFAULT_CACHE_TTL = 300.seconds
        const val DEFAULT_MAX_CACHE_SIZE = 1000
        const val DEFAULT_EVENT_BATCH_SIZE = 10
        val DEFAULT_EVENT_FLUSH_INTERVAL = 30.seconds
        val DEFAULT_TIMEOUT = 10.seconds
        const val DEFAULT_RETRY_ATTEMPTS = 3
        const val DEFAULT_CIRCUIT_BREAKER_THRESHOLD = 5
        val DEFAULT_CIRCUIT_BREAKER_RESET_TIMEOUT = 30.seconds
        const val DEFAULT_MAX_PERSISTED_EVENTS = 10000
        const val DEFAULT_PERSISTENCE_FLUSH_INTERVAL_MS = 1000L

        fun builder(apiKey: String) = Builder(apiKey)
    }
}
