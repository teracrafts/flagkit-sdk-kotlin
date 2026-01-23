package dev.flagkit

import dev.flagkit.error.ErrorCode
import dev.flagkit.error.FlagKitException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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
    val bootstrap: Map<String, Any>? = null
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
            bootstrap = bootstrap
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

        fun builder(apiKey: String) = Builder(apiKey)
    }
}
