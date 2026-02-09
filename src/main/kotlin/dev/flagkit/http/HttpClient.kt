package dev.flagkit.http

import dev.flagkit.error.ErrorCode
import dev.flagkit.error.FlagKitException
import dev.flagkit.utils.KeyRotationManager
import dev.flagkit.utils.createRequestSignature
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Usage metrics extracted from response headers.
 *
 * Provides visibility into API usage, rate limits, and subscription status.
 */
data class UsageMetrics(
    /** Percentage of API call limit used this period (0-150+) */
    val apiUsagePercent: Double? = null,
    /** Percentage of evaluation limit used (0-150+) */
    val evaluationUsagePercent: Double? = null,
    /** Whether approaching rate limit threshold */
    val rateLimitWarning: Boolean = false,
    /** Current subscription status */
    val subscriptionStatus: String? = null
) {
    companion object {
        /** Valid subscription status values */
        val VALID_STATUSES = setOf("active", "trial", "past_due", "suspended", "cancelled")
    }
}

/**
 * Callback type for usage metrics updates.
 */
typealias UsageUpdateCallback = (UsageMetrics) -> Unit

/**
 * HTTP client with retry logic, circuit breaker integration, request signing, and key rotation.
 */
class HttpClient(
    private val apiKey: String,
    private val timeout: Duration,
    private val retryAttempts: Int,
    private val circuitBreaker: CircuitBreaker,
    private val localPort: Int? = null,
    private val secondaryApiKey: String? = null,
    private val enableRequestSigning: Boolean = true,
    private val onUsageUpdate: UsageUpdateCallback? = null
) {
    private val logger = LoggerFactory.getLogger(HttpClient::class.java)
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val keyRotationManager = KeyRotationManager(apiKey, secondaryApiKey)

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = timeout.inWholeMilliseconds
            connectTimeoutMillis = timeout.inWholeMilliseconds
        }
    }

    suspend fun get(path: String, params: Map<String, String> = emptyMap()): Map<String, Any?> {
        return request(HttpMethod.Get, path, params = params)
    }

    suspend fun post(path: String, body: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        return request(HttpMethod.Post, path, body = body)
    }

    private suspend fun request(
        method: HttpMethod,
        path: String,
        params: Map<String, String>? = null,
        body: Map<String, Any?>? = null
    ): Map<String, Any?> {
        if (!circuitBreaker.allowRequest()) {
            throw FlagKitException(ErrorCode.CIRCUIT_OPEN, "Circuit breaker is open")
        }

        var attempts = 0
        var lastError: Exception? = null

        while (attempts < retryAttempts) {
            attempts++
            try {
                val response = executeRequest(method, path, params, body)
                circuitBreaker.recordSuccess()
                return response
            } catch (e: FlagKitException) {
                // Handle 401 with key rotation
                if (e.errorCode == ErrorCode.AUTH_INVALID_KEY && keyRotationManager.handleAuthError()) {
                    // Retry immediately with the secondary key
                    continue
                }
                if (!e.isRecoverable) throw e
                lastError = e
            } catch (e: Exception) {
                lastError = FlagKitException.networkError("Request failed: ${e.message}", e)
            }

            if (attempts < retryAttempts) {
                val backoff = calculateBackoff(attempts)
                delay(backoff.inWholeMilliseconds)
            }
        }

        circuitBreaker.recordFailure()
        throw lastError ?: FlagKitException.networkError("Request failed after $retryAttempts attempts")
    }

    private suspend fun executeRequest(
        method: HttpMethod,
        path: String,
        params: Map<String, String>?,
        body: Map<String, Any?>?
    ): Map<String, Any?> {
        val baseUrl = getBaseUrl(localPort)
        val currentApiKey = keyRotationManager.getCurrentKey()

        val response = client.request("${baseUrl}$path") {
            this.method = method
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header("X-API-Key", currentApiKey)
            header("User-Agent", "FlagKit-Kotlin/$SDK_VERSION")
            header("X-FlagKit-SDK-Version", SDK_VERSION)
            header("X-FlagKit-SDK-Language", "kotlin")
            params?.forEach { (key, value) -> parameter(key, value) }

            // Add request signing for POST requests
            if (method == HttpMethod.Post && body != null && enableRequestSigning) {
                val bodyJson = json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), convertToJsonElement(body))
                val signature = createRequestSignature(bodyJson, currentApiKey)
                header("X-Signature", signature.signature)
                header("X-Timestamp", signature.timestamp.toString())
                header("X-Key-Id", signature.keyId)
                setBody(body)
            } else {
                body?.let { setBody(it) }
            }
        }

        return parseResponse(response)
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun parseResponse(response: HttpResponse): Map<String, Any?> {
        // Extract usage metrics from headers
        val usageMetrics = extractUsageMetrics(response)
        if (usageMetrics != null) {
            onUsageUpdate?.invoke(usageMetrics)
        }

        return when (response.status.value) {
            in 200..299 -> {
                val text = response.bodyAsText()
                if (text.isBlank()) emptyMap()
                else json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(text)
                    .mapValues { convertJsonElement(it.value) }
            }
            401 -> throw FlagKitException.authError(ErrorCode.AUTH_INVALID_KEY, "Invalid API key")
            403 -> throw FlagKitException.authError(ErrorCode.AUTH_PERMISSION_DENIED, "Permission denied")
            404 -> throw FlagKitException(ErrorCode.EVAL_FLAG_NOT_FOUND, "Resource not found")
            429 -> throw FlagKitException(ErrorCode.NETWORK_RETRY_LIMIT, "Rate limit exceeded")
            in 500..599 -> throw FlagKitException.networkError("Server error: ${response.status.value}")
            else -> throw FlagKitException.networkError("Unexpected response status: ${response.status.value}")
        }
    }

    /**
     * Extracts usage metrics from response headers.
     *
     * Headers parsed:
     * - X-API-Usage-Percent: Percentage of API call limit used
     * - X-Evaluation-Usage-Percent: Percentage of evaluation limit used
     * - X-Rate-Limit-Warning: Boolean indicating approaching rate limit
     * - X-Subscription-Status: Current subscription status (active, trial, past_due, suspended, cancelled)
     *
     * @return UsageMetrics if any usage headers present, null otherwise
     */
    private fun extractUsageMetrics(response: HttpResponse): UsageMetrics? {
        val headers = response.headers

        val apiUsageHeader = headers["X-API-Usage-Percent"]
        val evalUsageHeader = headers["X-Evaluation-Usage-Percent"]
        val rateLimitWarningHeader = headers["X-Rate-Limit-Warning"]
        val subscriptionStatusHeader = headers["X-Subscription-Status"]

        // Return null if no usage headers present
        if (apiUsageHeader == null && evalUsageHeader == null &&
            rateLimitWarningHeader == null && subscriptionStatusHeader == null) {
            return null
        }

        val apiUsagePercent = apiUsageHeader?.toDoubleOrNull()
        val evaluationUsagePercent = evalUsageHeader?.toDoubleOrNull()
        val rateLimitWarning = rateLimitWarningHeader == "true"
        val subscriptionStatus = if (subscriptionStatusHeader in UsageMetrics.VALID_STATUSES) {
            subscriptionStatusHeader
        } else {
            null
        }

        // Log warnings for high usage (>= 80%)
        if (apiUsagePercent != null && apiUsagePercent >= 80) {
            logger.warn("[FlagKit] API usage at {}%", apiUsagePercent)
        }
        if (evaluationUsagePercent != null && evaluationUsagePercent >= 80) {
            logger.warn("[FlagKit] Evaluation usage at {}%", evaluationUsagePercent)
        }
        if (subscriptionStatus == "suspended") {
            logger.error("[FlagKit] Subscription suspended - service degraded")
        }

        return UsageMetrics(
            apiUsagePercent = apiUsagePercent,
            evaluationUsagePercent = evaluationUsagePercent,
            rateLimitWarning = rateLimitWarning,
            subscriptionStatus = subscriptionStatus
        )
    }

    private fun convertToJsonElement(value: Any?): kotlinx.serialization.json.JsonElement = when (value) {
        null -> kotlinx.serialization.json.JsonNull
        is Boolean -> kotlinx.serialization.json.JsonPrimitive(value)
        is Number -> kotlinx.serialization.json.JsonPrimitive(value)
        is String -> kotlinx.serialization.json.JsonPrimitive(value)
        is Map<*, *> -> kotlinx.serialization.json.JsonObject(value.entries.associate {
            it.key.toString() to convertToJsonElement(it.value)
        })
        is List<*> -> kotlinx.serialization.json.JsonArray(value.map { convertToJsonElement(it) })
        else -> kotlinx.serialization.json.JsonNull
    }

    private fun convertJsonElement(element: kotlinx.serialization.json.JsonElement): Any? {
        return when (element) {
            is kotlinx.serialization.json.JsonNull -> null
            is kotlinx.serialization.json.JsonPrimitive -> {
                when {
                    element.isString -> element.content
                    element.content == "true" -> true
                    element.content == "false" -> false
                    element.content.contains('.') -> element.content.toDoubleOrNull()
                    else -> element.content.toLongOrNull()
                }
            }
            is kotlinx.serialization.json.JsonObject -> element.mapValues { convertJsonElement(it.value) }
            is kotlinx.serialization.json.JsonArray -> element.map { convertJsonElement(it) }
        }
    }

    private fun calculateBackoff(attempt: Int): Duration {
        val delay = BASE_RETRY_DELAY.inWholeMilliseconds * 2.0.pow(attempt - 1)
        val cappedDelay = min(delay, MAX_RETRY_DELAY.inWholeMilliseconds.toDouble())
        val jitter = cappedDelay * JITTER_FACTOR * Random.nextDouble()
        return (cappedDelay + jitter).toLong().milliseconds
    }

    fun close() {
        client.close()
    }

    companion object {
        /**
         * SDK version - should be updated with releases.
         */
        const val SDK_VERSION = "1.0.5"

        internal const val DEFAULT_BASE_URL = "https://api.flagkit.dev/api/v1"
        private val BASE_RETRY_DELAY = 1.seconds
        private val MAX_RETRY_DELAY = 30.seconds
        private const val JITTER_FACTOR = 0.1

        fun getBaseUrl(localPort: Int?): String =
            if (localPort != null) "http://localhost:$localPort/api/v1" else DEFAULT_BASE_URL
    }
}
