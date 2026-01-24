package dev.flagkit

import dev.flagkit.core.Cache
import dev.flagkit.core.EventQueue
import dev.flagkit.core.PollingManager
import dev.flagkit.http.CircuitBreaker
import dev.flagkit.http.HttpClient
import dev.flagkit.types.EvaluationContext
import dev.flagkit.types.EvaluationReason
import dev.flagkit.types.EvaluationResult
import dev.flagkit.types.FlagState
import dev.flagkit.types.FlagValue
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.time.Instant

/**
 * The main FlagKit client for evaluating feature flags.
 */
class FlagKitClient(
    private val options: FlagKitOptions,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    private val mutex = Mutex()
    private var context = EvaluationContext()
    private var isReady = false
    private val readyDeferred = CompletableDeferred<Unit>()

    private val circuitBreaker = CircuitBreaker(
        failureThreshold = options.circuitBreakerThreshold,
        resetTimeout = options.circuitBreakerResetTimeout
    )

    private val httpClient = HttpClient(
        apiKey = options.apiKey,
        timeout = options.timeout,
        retryAttempts = options.retryAttempts,
        circuitBreaker = circuitBreaker,
        localPort = options.localPort
    )

    private val cache = Cache<FlagState>(
        ttl = options.cacheTtl,
        maxSize = options.maxCacheSize
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

    suspend fun initialize() {
        options.bootstrap?.let { loadBootstrap(it) }

        try {
            fetchInitialFlags()
        } catch (e: Exception) {
            // Continue without initial flags
        }

        startBackgroundTasks()
        markReady()
    }

    suspend fun waitForReady() {
        readyDeferred.await()
    }

    fun isReady(): Boolean = isReady

    suspend fun identify(userId: String, attributes: Map<String, Any?> = emptyMap()) {
        mutex.withLock {
            context = EvaluationContext(
                userId = userId,
                attributes = attributes.mapValues { FlagValue.from(it.value) }
            )
        }
    }

    suspend fun resetContext() {
        mutex.withLock {
            context = EvaluationContext()
        }
    }

    suspend fun getContext(): EvaluationContext = mutex.withLock { context }

    suspend fun evaluate(
        key: String,
        defaultValue: FlagValue,
        contextOverride: EvaluationContext? = null
    ): EvaluationResult {
        val effectiveContext = mutex.withLock { context.merge(contextOverride) }

        // Check cache first
        if (options.cacheEnabled) {
            cache.get(key)?.let { cached ->
                return EvaluationResult(
                    flagKey = key,
                    value = cached.value,
                    enabled = cached.enabled,
                    reason = EvaluationReason.CACHED,
                    version = cached.version
                )
            }
        }

        // Fetch from server
        return try {
            val body = mapOf(
                "key" to key,
                "context" to effectiveContext.stripPrivateAttributes().toMap()
            )

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
            EvaluationResult.defaultResult(key, defaultValue, EvaluationReason.ERROR)
        }
    }

    suspend fun getBoolValue(
        key: String,
        defaultValue: Boolean,
        context: EvaluationContext? = null
    ): Boolean {
        return evaluate(key, FlagValue.BoolValue(defaultValue), context).boolValue
    }

    suspend fun getStringValue(
        key: String,
        defaultValue: String,
        context: EvaluationContext? = null
    ): String {
        return evaluate(key, FlagValue.StringValue(defaultValue), context).stringValue ?: defaultValue
    }

    suspend fun getNumberValue(
        key: String,
        defaultValue: Double,
        context: EvaluationContext? = null
    ): Double {
        return evaluate(key, FlagValue.DoubleValue(defaultValue), context).numberValue
    }

    suspend fun getIntValue(
        key: String,
        defaultValue: Long,
        context: EvaluationContext? = null
    ): Long {
        return evaluate(key, FlagValue.IntValue(defaultValue), context).intValue
    }

    suspend fun getJsonValue(
        key: String,
        defaultValue: Map<String, Any?>,
        context: EvaluationContext? = null
    ): Map<String, Any?> {
        val flagValue = FlagValue.JsonObjectValue(defaultValue.mapValues { FlagValue.from(it.value) })
        return evaluate(key, flagValue, context).jsonValue ?: defaultValue
    }

    suspend fun track(eventType: String, data: Map<String, Any?>? = null) {
        if (!options.eventsEnabled || eventQueue == null) return

        val currentContext = getContext()
        val event = buildMap<String, Any?> {
            put("type", eventType)
            put("timestamp", Instant.now().toString())
            currentContext.userId?.let { put("userId", it) }
            data?.let { put("data", it) }
        }

        eventQueue.enqueue(event)
    }

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
