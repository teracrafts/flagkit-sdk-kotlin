package dev.flagkit

import dev.flagkit.core.Cache
import dev.flagkit.core.CacheStats
import dev.flagkit.core.ContextManager
import dev.flagkit.core.EventQueue
import dev.flagkit.core.PollingManager
import dev.flagkit.http.CircuitBreaker
import dev.flagkit.http.HttpClient
import dev.flagkit.types.EvaluationContext
import dev.flagkit.types.EvaluationReason
import dev.flagkit.types.EvaluationResult
import dev.flagkit.types.FlagState
import dev.flagkit.types.FlagValue
import dev.flagkit.utils.DataType
import dev.flagkit.utils.EncryptedStorage
import dev.flagkit.utils.Logger
import dev.flagkit.utils.SecurityException
import dev.flagkit.utils.enforceNoPii
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.time.Instant
import kotlin.random.Random
import kotlin.time.Duration

/**
 * Internal logger adapter for the FlagKit client.
 */
private class ClientLogger : Logger {
    override fun debug(message: String, data: Map<String, Any?>?) {
        // Could be enhanced to use a proper logging framework
    }

    override fun info(message: String, data: Map<String, Any?>?) {
        // Could be enhanced to use a proper logging framework
    }

    override fun warn(message: String, data: Map<String, Any?>?) {
        System.err.println("[FlagKit WARN] $message")
    }

    override fun error(message: String, data: Map<String, Any?>?) {
        System.err.println("[FlagKit ERROR] $message")
    }
}

/**
 * Cache wrapper that provides optional AES-256-GCM encryption for flag state values.
 *
 * When encryption is enabled, all flag state values are encrypted before storage
 * and decrypted on retrieval using the API key for key derivation.
 */
private class EncryptingCache(
    private val ttl: Duration,
    private val maxSize: Int,
    private val encryptionEnabled: Boolean,
    apiKey: String
) {
    private val json = Json { ignoreUnknownKeys = true }

    // Encrypted storage for key derivation (only if encryption is enabled)
    private val encryptedStorage: EncryptedStorage? = if (encryptionEnabled) {
        EncryptedStorage(apiKey)
    } else null

    // Internal storage: key -> (encrypted or plain JSON string, metadata)
    private val entries = mutableMapOf<String, EncryptedCacheEntry>()
    private val mutex = Mutex()
    private var hitCount: Long = 0
    private var missCount: Long = 0

    private data class EncryptedCacheEntry(
        val data: String, // encrypted or plain JSON depending on encryptionEnabled
        val fetchedAt: java.time.Instant,
        val expiresAt: java.time.Instant,
        var lastAccessedAt: java.time.Instant = java.time.Instant.now()
    ) {
        val isExpired: Boolean
            get() = java.time.Instant.now().isAfter(expiresAt)
    }

    suspend fun get(key: String): FlagState? = mutex.withLock {
        val entry = entries[key]
        if (entry == null) {
            missCount++
            return@withLock null
        }
        if (entry.isExpired) {
            missCount++
            return@withLock null
        }
        entry.lastAccessedAt = java.time.Instant.now()
        hitCount++
        deserialize(entry.data)
    }

    suspend fun getStale(key: String): FlagState? = mutex.withLock {
        val entry = entries[key] ?: return@withLock null
        deserialize(entry.data)
    }

    suspend fun set(key: String, value: FlagState, customTtl: Duration? = null) = mutex.withLock {
        evictIfNeeded()
        val now = java.time.Instant.now()
        val effectiveTtl = customTtl ?: ttl
        val serialized = serialize(value)
        entries[key] = EncryptedCacheEntry(
            data = serialized,
            fetchedAt = now,
            expiresAt = now.plusMillis(effectiveTtl.inWholeMilliseconds)
        )
    }

    suspend fun has(key: String): Boolean = mutex.withLock {
        val entry = entries[key] ?: return@withLock false
        !entry.isExpired
    }

    suspend fun hasAny(key: String): Boolean = mutex.withLock {
        entries.containsKey(key)
    }

    suspend fun keys(): Set<String> = mutex.withLock {
        entries.keys.toSet()
    }

    suspend fun toMap(): Map<String, FlagState> = mutex.withLock {
        entries.filter { !it.value.isExpired }
            .mapValues { deserialize(it.value.data) }
            .filterValues { it != null }
            .mapValues { it.value!! }
    }

    suspend fun clear() = mutex.withLock {
        entries.clear()
        hitCount = 0
        missCount = 0
    }

    suspend fun getStats(): CacheStats = mutex.withLock {
        val now = java.time.Instant.now()
        var validCount = 0
        var staleCount = 0

        entries.values.forEach { entry ->
            if (now.isBefore(entry.expiresAt) || now == entry.expiresAt) {
                validCount++
            } else {
                staleCount++
            }
        }

        CacheStats(
            size = entries.size,
            validCount = validCount,
            staleCount = staleCount,
            maxSize = maxSize,
            hitCount = hitCount,
            missCount = missCount
        )
    }

    private fun serialize(flagState: FlagState): String {
        val jsonString = json.encodeToString(FlagState.serializer(), flagState)
        return if (encryptionEnabled && encryptedStorage != null) {
            encryptedStorage.encrypt(jsonString)
        } else {
            jsonString
        }
    }

    private fun deserialize(data: String): FlagState? {
        return try {
            val jsonString = if (encryptionEnabled && encryptedStorage != null) {
                encryptedStorage.decrypt(data)
            } else {
                data
            }
            json.decodeFromString(FlagState.serializer(), jsonString)
        } catch (e: Exception) {
            null
        }
    }

    private fun evictIfNeeded() {
        if (entries.size < maxSize) return

        // First try to remove expired entries
        entries.entries.removeIf { it.value.isExpired }
        if (entries.size < maxSize) return

        // LRU eviction - remove least recently accessed
        val lruKey = entries.minByOrNull { it.value.lastAccessedAt }?.key
        lruKey?.let { entries.remove(it) }
    }
}

/**
 * The main FlagKit client for evaluating feature flags.
 *
 * This client provides:
 * - Flag evaluation with type-safe methods
 * - Context management for user targeting
 * - In-memory caching with TTL support (optionally encrypted with AES-256-GCM)
 * - Background polling for flag updates
 * - Event tracking for analytics
 * - Circuit breaker for resilience
 * - PII detection and enforcement
 *
 * @param options Configuration options for the client.
 * @param scope Coroutine scope for background operations.
 */
class FlagKitClient(
    private val options: FlagKitOptions,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    private val mutex = Mutex()
    private val contextManager = ContextManager()
    private var isReady = false
    private val readyDeferred = CompletableDeferred<Unit>()

    // Internal logger for security warnings
    private val logger: Logger = ClientLogger()

    private val circuitBreaker = CircuitBreaker(
        failureThreshold = options.circuitBreakerThreshold,
        resetTimeout = options.circuitBreakerResetTimeout
    )

    private val httpClient = HttpClient(
        apiKey = options.apiKey,
        timeout = options.timeout,
        retryAttempts = options.retryAttempts,
        circuitBreaker = circuitBreaker,
        localPort = options.localPort,
        secondaryApiKey = options.secondaryApiKey,
        enableRequestSigning = options.enableRequestSigning
    )

    // Cache with optional encryption support
    private val cache = EncryptingCache(
        ttl = options.cacheTtl,
        maxSize = options.maxCacheSize,
        encryptionEnabled = options.enableCacheEncryption,
        apiKey = options.apiKey
    )

    private val pollingManager = PollingManager(
        interval = options.pollingInterval,
        onUpdate = { lastUpdate -> pollForUpdates(lastUpdate) },
        scope = scope
    )

    private val eventQueue: EventQueue? = if (options.eventsEnabled) {
        EventQueue(
            batchSize = options.eventBatchSize,
            flushInterval = options.eventFlushInterval,
            onFlush = { events -> sendEvents(events) },
            scope = scope
        )
    } else null

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Initialize the client.
     *
     * This method:
     * 1. Loads bootstrap data if provided
     * 2. Fetches initial flags from the server
     * 3. Starts background polling and event queue
     * 4. Marks the client as ready
     */
    suspend fun initialize() {
        options.bootstrap?.let { loadBootstrap(it) }

        try {
            fetchInitialFlags()
        } catch (e: Exception) {
            // Continue with bootstrap/cache data if available
        }

        startBackgroundTasks()
        markReady()
    }

    /**
     * Wait for the client to be ready.
     *
     * This suspends until initialization is complete.
     */
    suspend fun waitForReady() {
        readyDeferred.await()
    }

    /**
     * Check if the client is ready.
     *
     * @return True if initialization is complete.
     */
    fun isReady(): Boolean = isReady

    /**
     * Identify a user for targeting.
     *
     * @param userId The unique user identifier.
     * @param attributes Optional additional attributes.
     * @throws SecurityException if strictPiiMode is enabled and PII is detected in attributes.
     */
    suspend fun identify(userId: String, attributes: Map<String, Any?> = emptyMap()) {
        // Enforce PII check on user attributes
        if (attributes.isNotEmpty()) {
            enforceNoPii(
                data = attributes,
                dataType = DataType.CONTEXT,
                strictMode = options.strictPiiMode,
                logger = if (!options.strictPiiMode) logger else null
            )
        }
        contextManager.identify(userId, attributes)
    }

    /**
     * Reset the user context to anonymous.
     */
    suspend fun resetContext() {
        contextManager.reset()
    }

    /**
     * Clear the user context entirely.
     */
    suspend fun clearContext() {
        contextManager.clearContext()
    }

    /**
     * Get the current evaluation context.
     *
     * @return The current context, or null if not set.
     */
    suspend fun getContext(): EvaluationContext? {
        return contextManager.getContext()
    }

    /**
     * Set the global evaluation context.
     *
     * @param context The context to set.
     * @throws SecurityException if strictPiiMode is enabled and PII is detected in context attributes.
     */
    suspend fun setContext(context: EvaluationContext) {
        // Enforce PII check on context attributes
        val attributesMap = context.attributes.mapValues { it.value.toAny() }
        if (attributesMap.isNotEmpty()) {
            enforceNoPii(
                data = attributesMap,
                dataType = DataType.CONTEXT,
                strictMode = options.strictPiiMode,
                logger = if (!options.strictPiiMode) logger else null
            )
        }
        contextManager.setContext(context)
    }

    /**
     * Applies evaluation jitter to protect against cache timing attacks.
     *
     * When enabled, this adds a random delay at the start of flag evaluation
     * to make timing-based attacks more difficult.
     */
    private fun applyEvaluationJitter() {
        if (options.evaluationJitter.enabled) {
            val jitterMs = Random.nextLong(
                options.evaluationJitter.minMs,
                options.evaluationJitter.maxMs + 1
            )
            Thread.sleep(jitterMs)
        }
    }

    /**
     * Evaluate a flag and get full result details.
     *
     * @param key The flag key.
     * @param defaultValue The default value if evaluation fails.
     * @param contextOverride Optional context override for this evaluation.
     * @return The evaluation result with value and metadata.
     */
    suspend fun evaluate(
        key: String,
        defaultValue: FlagValue,
        contextOverride: EvaluationContext? = null
    ): EvaluationResult {
        // Apply jitter at the start of evaluation for timing attack protection
        applyEvaluationJitter()

        val effectiveContext = contextManager.mergeContext(contextOverride)

        // Check cache first
        if (options.cacheEnabled) {
            val cached = cache.get(key)
            if (cached != null) {
                return EvaluationResult(
                    flagKey = key,
                    value = cached.value,
                    enabled = cached.enabled,
                    reason = EvaluationReason.CACHED,
                    version = cached.version
                )
            }

            // Try stale cache as fallback
            if (circuitBreaker.isOpen()) {
                val stale = cache.getStale(key)
                if (stale != null) {
                    return EvaluationResult(
                        flagKey = key,
                        value = stale.value,
                        enabled = stale.enabled,
                        reason = EvaluationReason.STALE_CACHE,
                        version = stale.version
                    )
                }
            }
        }

        // Fetch from server
        return try {
            val body = buildMap<String, Any?> {
                put("flagKey", key)
                effectiveContext?.stripPrivateAttributes()?.toMap()?.let { put("context", it) }
            }

            val response = httpClient.post("/sdk/evaluate", body)
            val flagState = decodeFlagState(response)

            if (options.cacheEnabled) {
                cache.set(key, flagState)
            }

            EvaluationResult(
                flagKey = key,
                value = flagState.value,
                enabled = flagState.enabled,
                reason = EvaluationReason.SERVER,
                version = flagState.version
            )
        } catch (e: Exception) {
            // Try stale cache before returning default
            if (options.cacheEnabled) {
                val stale = cache.getStale(key)
                if (stale != null) {
                    return EvaluationResult(
                        flagKey = key,
                        value = stale.value,
                        enabled = stale.enabled,
                        reason = EvaluationReason.STALE_CACHE,
                        version = stale.version
                    )
                }
            }

            EvaluationResult.defaultResult(key, defaultValue, EvaluationReason.ERROR)
        }
    }

    /**
     * Evaluate all flags with the current context.
     *
     * @param contextOverride Optional context override.
     * @return Map of flag keys to evaluation results.
     */
    suspend fun evaluateAll(contextOverride: EvaluationContext? = null): Map<String, EvaluationResult> {
        val effectiveContext = contextManager.mergeContext(contextOverride)

        return try {
            val body = buildMap<String, Any?> {
                effectiveContext?.stripPrivateAttributes()?.toMap()?.let { put("context", it) }
            }

            val response = httpClient.post("/sdk/evaluate/all", body)

            @Suppress("UNCHECKED_CAST")
            val flags = response["flags"] as? Map<String, Map<String, Any?>> ?: emptyMap()

            flags.mapValues { (key, data) ->
                try {
                    val flagState = decodeFlagState(data)
                    if (options.cacheEnabled) {
                        cache.set(key, flagState)
                    }
                    EvaluationResult(
                        flagKey = key,
                        value = flagState.value,
                        enabled = flagState.enabled,
                        reason = EvaluationReason.SERVER,
                        version = flagState.version
                    )
                } catch (e: Exception) {
                    EvaluationResult.defaultResult(key, FlagValue.NullValue, EvaluationReason.ERROR)
                }
            }
        } catch (e: Exception) {
            // Return cached values if server fails
            val cachedFlags = cache.toMap()
            cachedFlags.mapValues { (key, flagState) ->
                EvaluationResult(
                    flagKey = key,
                    value = flagState.value,
                    enabled = flagState.enabled,
                    reason = EvaluationReason.CACHED,
                    version = flagState.version
                )
            }
        }
    }

    /**
     * Evaluate a boolean flag.
     *
     * @param key The flag key.
     * @param defaultValue The default value.
     * @param context Optional context override.
     * @return The boolean value.
     */
    suspend fun getBoolValue(
        key: String,
        defaultValue: Boolean,
        context: EvaluationContext? = null
    ): Boolean {
        return evaluate(key, FlagValue.BoolValue(defaultValue), context).boolValue
    }

    /**
     * Evaluate a string flag.
     *
     * @param key The flag key.
     * @param defaultValue The default value.
     * @param context Optional context override.
     * @return The string value.
     */
    suspend fun getStringValue(
        key: String,
        defaultValue: String,
        context: EvaluationContext? = null
    ): String {
        return evaluate(key, FlagValue.StringValue(defaultValue), context).stringValue ?: defaultValue
    }

    /**
     * Evaluate a number flag (double).
     *
     * @param key The flag key.
     * @param defaultValue The default value.
     * @param context Optional context override.
     * @return The double value.
     */
    suspend fun getNumberValue(
        key: String,
        defaultValue: Double,
        context: EvaluationContext? = null
    ): Double {
        return evaluate(key, FlagValue.DoubleValue(defaultValue), context).numberValue
    }

    /**
     * Evaluate an integer flag.
     *
     * @param key The flag key.
     * @param defaultValue The default value.
     * @param context Optional context override.
     * @return The long value.
     */
    suspend fun getIntValue(
        key: String,
        defaultValue: Long,
        context: EvaluationContext? = null
    ): Long {
        return evaluate(key, FlagValue.IntValue(defaultValue), context).intValue
    }

    /**
     * Evaluate a JSON flag.
     *
     * @param key The flag key.
     * @param defaultValue The default value.
     * @param context Optional context override.
     * @return The JSON value as a Map.
     */
    suspend fun getJsonValue(
        key: String,
        defaultValue: Map<String, Any?>,
        context: EvaluationContext? = null
    ): Map<String, Any?> {
        val flagValue = FlagValue.JsonObjectValue(defaultValue.mapValues { FlagValue.from(it.value) })
        return evaluate(key, flagValue, context).jsonValue ?: defaultValue
    }

    /**
     * Check if a flag exists in the cache.
     *
     * @param key The flag key.
     * @return True if the flag is cached.
     */
    suspend fun hasFlag(key: String): Boolean {
        return cache.hasAny(key)
    }

    /**
     * Get all cached flag keys.
     *
     * @return Set of flag keys.
     */
    suspend fun getAllFlagKeys(): Set<String> {
        return cache.keys()
    }

    /**
     * Force refresh flags from the server.
     *
     * This bypasses the polling interval and immediately fetches updates.
     */
    suspend fun refresh() {
        try {
            fetchInitialFlags()
        } catch (e: Exception) {
            // Ignore errors, keep existing cache
        }
    }

    /**
     * Flush pending events immediately.
     */
    suspend fun flush() {
        eventQueue?.flush()
    }

    /**
     * Track a custom event.
     *
     * @param eventType The type of event.
     * @param data Optional event data.
     * @throws SecurityException if strictPiiMode is enabled and PII is detected in event data.
     */
    suspend fun track(eventType: String, data: Map<String, Any?>? = null) {
        if (!options.eventsEnabled || eventQueue == null) return

        // Enforce PII check on event data
        if (data != null) {
            enforceNoPii(
                data = data,
                dataType = DataType.EVENT,
                strictMode = options.strictPiiMode,
                logger = if (!options.strictPiiMode) logger else null
            )
        }

        val currentContext = getContext()
        val event = buildMap<String, Any?> {
            put("type", eventType)
            put("timestamp", Instant.now().toString())
            currentContext?.userId?.let { put("userId", it) }
            data?.let { put("data", it) }
        }

        eventQueue.enqueue(event)
    }

    /**
     * Get cache statistics.
     *
     * @return Cache statistics.
     */
    suspend fun getCacheStats() = cache.getStats()

    /**
     * Get polling statistics.
     *
     * @return Polling statistics.
     */
    suspend fun getPollingStats() = pollingManager.getStats()

    /**
     * Get event queue statistics.
     *
     * @return Event queue statistics, or null if events are disabled.
     */
    suspend fun getEventStats() = eventQueue?.getStats()

    /**
     * Check if the circuit breaker is open.
     *
     * @return True if the circuit is open (failing).
     */
    suspend fun isCircuitOpen() = circuitBreaker.isOpen()

    /**
     * Check if cache encryption is enabled.
     *
     * @return True if cache encryption is enabled.
     */
    fun isCacheEncryptionEnabled(): Boolean = options.enableCacheEncryption

    /**
     * Close the client and release resources.
     *
     * This method:
     * 1. Stops the polling manager
     * 2. Flushes and stops the event queue
     * 3. Clears the cache
     * 4. Closes the HTTP client
     */
    suspend fun close() {
        pollingManager.stop()
        eventQueue?.stop()
        cache.clear()
        httpClient.close()
    }

    private fun markReady() {
        isReady = true
        readyDeferred.complete(Unit)
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun loadBootstrap(bootstrap: Map<String, Any>) {
        val flags = bootstrap["flags"] as? List<Map<String, Any?>> ?: return

        flags.forEach { flagData ->
            try {
                val flagState = decodeFlagState(flagData)
                cache.set(flagState.key, flagState)
            } catch (e: Exception) {
                // Skip invalid flags
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun fetchInitialFlags() {
        val response = httpClient.get("/sdk/init")
        val flags = response["flags"] as? List<Map<String, Any?>> ?: return

        flags.forEach { flagData ->
            try {
                val flagState = decodeFlagState(flagData)
                cache.set(flagState.key, flagState)
            } catch (e: Exception) {
                // Skip invalid flags
            }
        }
    }

    private suspend fun startBackgroundTasks() {
        pollingManager.start()
        eventQueue?.start()
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun pollForUpdates(lastUpdate: Instant?) {
        val params = buildMap {
            lastUpdate?.let { put("since", it.toString()) }
        }

        val response = httpClient.get("/sdk/updates", params)
        val flags = response["flags"] as? List<Map<String, Any?>> ?: return

        flags.forEach { flagData ->
            try {
                val flagState = decodeFlagState(flagData)
                cache.set(flagState.key, flagState)
            } catch (e: Exception) {
                // Skip invalid flags
            }
        }
    }

    private suspend fun sendEvents(events: List<Map<String, Any?>>) {
        httpClient.post("/sdk/events/batch", mapOf("events" to events))
    }

    private fun decodeFlagState(data: Map<String, Any?>): FlagState {
        val jsonString = Json.encodeToString(
            JsonObject.serializer(),
            JsonObject(data.mapValues { convertToJsonElement(it.value) })
        )
        return json.decodeFromString<FlagState>(jsonString)
    }

    private fun convertToJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associate {
            it.key.toString() to convertToJsonElement(it.value)
        })
        is List<*> -> JsonArray(value.map { convertToJsonElement(it) })
        else -> JsonNull
    }
}
