package dev.flagkit.http

import dev.flagkit.error.ErrorCode
import dev.flagkit.error.FlagKitException
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
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * HTTP client with retry logic and circuit breaker integration.
 */
class HttpClient(
    private val apiKey: String,
    private val timeout: Duration,
    private val retryAttempts: Int,
    private val circuitBreaker: CircuitBreaker,
    private val isLocal: Boolean = false
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = timeout.inWholeMilliseconds
            connectTimeoutMillis = timeout.inWholeMilliseconds
        }
        defaultRequest {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header("Authorization", "Bearer $apiKey")
            header("User-Agent", "FlagKit-Kotlin/1.0.0")
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
        val baseUrl = if (isLocal) LOCAL_BASE_URL else DEFAULT_BASE_URL
        val response = client.request("${baseUrl}$path") {
            this.method = method
            params?.forEach { (key, value) -> parameter(key, value) }
            body?.let { setBody(it) }
        }

        return parseResponse(response)
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun parseResponse(response: HttpResponse): Map<String, Any?> {
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
        internal const val DEFAULT_BASE_URL = "https://api.flagkit.dev/api/v1"
        private const val LOCAL_BASE_URL = "http://localhost:8200/api/v1"
        private val BASE_RETRY_DELAY = 1.seconds
        private val MAX_RETRY_DELAY = 30.seconds
        private const val JITTER_FACTOR = 0.1
    }
}
