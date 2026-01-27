package dev.flagkit.core

import dev.flagkit.types.FlagState
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min
import kotlin.math.pow

/**
 * Connection states for streaming.
 */
enum class StreamingState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    FAILED
}

/**
 * Streaming configuration.
 */
data class StreamingConfig(
    val enabled: Boolean = true,
    val reconnectIntervalMs: Long = 3000,
    val maxReconnectAttempts: Int = 3,
    val heartbeatIntervalMs: Long = 30000
)

@Serializable
private data class StreamTokenResponse(
    val token: String,
    val expiresIn: Int
)

@Serializable
private data class DeleteData(
    val key: String
)

/**
 * Manages Server-Sent Events (SSE) connection for real-time flag updates.
 *
 * Security: Uses token exchange pattern to avoid exposing API keys in URLs.
 * 1. Fetches short-lived token via POST with API key in header
 * 2. Connects to SSE endpoint with disposable token in URL
 *
 * Features:
 * - Secure token-based authentication
 * - Automatic token refresh before expiry
 * - Automatic reconnection with exponential backoff
 * - Graceful degradation to polling after max failures
 * - Heartbeat monitoring for connection health
 */
class StreamingManager(
    private val baseUrl: String,
    private val getApiKey: () -> String,
    private val config: StreamingConfig = StreamingConfig(),
    private val onFlagUpdate: (FlagState) -> Unit,
    private val onFlagDelete: (String) -> Unit,
    private val onFlagsReset: (List<FlagState>) -> Unit,
    private val onFallbackToPolling: () -> Unit
) {
    private val logger = LoggerFactory.getLogger(StreamingManager::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
            socketTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val state = AtomicReference(StreamingState.DISCONNECTED)
    private val consecutiveFailures = AtomicInteger(0)
    private val lastHeartbeat = AtomicLong(System.currentTimeMillis())

    private var connectionJob: Job? = null
    private var tokenRefreshJob: Job? = null
    private var heartbeatJob: Job? = null
    private var retryJob: Job? = null

    /**
     * Gets the current connection state.
     */
    fun getState(): StreamingState = state.get()

    /**
     * Checks if streaming is connected.
     */
    fun isConnected(): Boolean = state.get() == StreamingState.CONNECTED

    /**
     * Starts the streaming connection.
     */
    fun connect() {
        val current = state.get()
        if (current == StreamingState.CONNECTED || current == StreamingState.CONNECTING) {
            return
        }

        state.set(StreamingState.CONNECTING)
        connectionJob = scope.launch { initiateConnection() }
    }

    /**
     * Stops the streaming connection.
     */
    fun disconnect() {
        cleanup()
        state.set(StreamingState.DISCONNECTED)
        consecutiveFailures.set(0)
        logger.debug("Streaming disconnected")
    }

    /**
     * Retries the streaming connection.
     */
    fun retryConnection() {
        val current = state.get()
        if (current == StreamingState.CONNECTED || current == StreamingState.CONNECTING) {
            return
        }
        consecutiveFailures.set(0)
        connect()
    }

    private suspend fun initiateConnection() {
        try {
            // Step 1: Fetch short-lived stream token
            val tokenResponse = fetchStreamToken()

            // Step 2: Schedule token refresh at 80% of TTL
            scheduleTokenRefresh((tokenResponse.expiresIn * 0.8 * 1000).toLong())

            // Step 3: Create SSE connection with token
            createConnection(tokenResponse.token)
        } catch (e: Exception) {
            logger.error("Failed to fetch stream token", e)
            handleConnectionFailure()
        }
    }

    private suspend fun fetchStreamToken(): StreamTokenResponse {
        val tokenUrl = "$baseUrl/sdk/stream/token"

        val response = httpClient.post(tokenUrl) {
            contentType(ContentType.Application.Json)
            header("X-API-Key", getApiKey())
            setBody("{}")
        }

        if (!response.status.isSuccess()) {
            throw Exception("Failed to fetch stream token: ${response.status.value}")
        }

        val body = response.bodyAsText()
        return json.decodeFromString<StreamTokenResponse>(body)
    }

    private fun scheduleTokenRefresh(delayMs: Long) {
        tokenRefreshJob?.cancel()

        tokenRefreshJob = scope.launch {
            delay(delayMs)
            try {
                val tokenResponse = fetchStreamToken()
                scheduleTokenRefresh((tokenResponse.expiresIn * 0.8 * 1000).toLong())
            } catch (e: Exception) {
                logger.warn("Failed to refresh stream token, reconnecting", e)
                disconnect()
                connect()
            }
        }
    }

    private suspend fun createConnection(token: String) {
        val streamUrl = "$baseUrl/sdk/stream?token=$token"

        try {
            httpClient.prepareGet(streamUrl) {
                header(HttpHeaders.Accept, "text/event-stream")
                header(HttpHeaders.CacheControl, "no-cache")
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    logger.error("SSE connection failed: {}", response.status.value)
                    handleConnectionFailure()
                    return@execute
                }

                handleOpen()
                readEvents(response)
            }
        } catch (e: CancellationException) {
            // Normal cancellation, ignore
        } catch (e: Exception) {
            logger.error("SSE connection error", e)
            handleConnectionFailure()
        }
    }

    private fun handleOpen() {
        state.set(StreamingState.CONNECTED)
        consecutiveFailures.set(0)
        lastHeartbeat.set(System.currentTimeMillis())
        startHeartbeatMonitor()
        logger.info("Streaming connected")
    }

    private suspend fun readEvents(response: HttpResponse) {
        val channel: ByteReadChannel = response.bodyAsChannel()
        var eventType: String? = null
        val dataBuilder = StringBuilder()

        while (!channel.isClosedForRead) {
            val line = channel.readUTF8Line() ?: break
            val trimmedLine = line.trim()

            // Empty line = end of event
            if (trimmedLine.isEmpty()) {
                if (eventType != null && dataBuilder.isNotEmpty()) {
                    processEvent(eventType, dataBuilder.toString())
                    eventType = null
                    dataBuilder.clear()
                }
                continue
            }

            // Parse SSE format
            when {
                trimmedLine.startsWith("event:") -> {
                    eventType = trimmedLine.substring(6).trim()
                }
                trimmedLine.startsWith("data:") -> {
                    dataBuilder.append(trimmedLine.substring(5).trim())
                }
            }
        }

        // Connection closed
        if (state.get() == StreamingState.CONNECTED) {
            handleConnectionFailure()
        }
    }

    private fun processEvent(eventType: String, data: String) {
        try {
            when (eventType) {
                "flag_updated" -> {
                    val flag = json.decodeFromString<FlagState>(data)
                    onFlagUpdate(flag)
                }
                "flag_deleted" -> {
                    val deleteData = json.decodeFromString<DeleteData>(data)
                    onFlagDelete(deleteData.key)
                }
                "flags_reset" -> {
                    val flags = json.decodeFromString<List<FlagState>>(data)
                    onFlagsReset(flags)
                }
                "heartbeat" -> {
                    lastHeartbeat.set(System.currentTimeMillis())
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to process event: {}", eventType, e)
        }
    }

    private fun handleConnectionFailure() {
        cleanup()
        val failures = consecutiveFailures.incrementAndGet()

        if (failures >= config.maxReconnectAttempts) {
            state.set(StreamingState.FAILED)
            logger.warn("Streaming failed, falling back to polling. Failures: {}", failures)
            onFallbackToPolling()
            scheduleStreamingRetry()
        } else {
            state.set(StreamingState.RECONNECTING)
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        val delayMs = getReconnectDelay()
        logger.debug("Scheduling reconnect in {}ms, attempt {}", delayMs, consecutiveFailures.get())

        scope.launch {
            delay(delayMs)
            connect()
        }
    }

    private fun getReconnectDelay(): Long {
        val baseDelay = config.reconnectIntervalMs
        val backoff = 2.0.pow((consecutiveFailures.get() - 1).toDouble())
        val delayMs = (baseDelay * backoff).toLong()
        // Cap at 30 seconds
        return min(delayMs, 30000)
    }

    private fun scheduleStreamingRetry() {
        retryJob?.cancel()

        retryJob = scope.launch {
            delay(5 * 60 * 1000) // 5 minutes
            logger.info("Retrying streaming connection")
            retryConnection()
        }
    }

    private fun startHeartbeatMonitor() {
        stopHeartbeatMonitor()

        val checkInterval = (config.heartbeatIntervalMs * 1.5).toLong()

        heartbeatJob = scope.launch {
            delay(checkInterval)
            val timeSince = System.currentTimeMillis() - lastHeartbeat.get()
            val threshold = config.heartbeatIntervalMs * 2

            if (timeSince > threshold) {
                logger.warn("Heartbeat timeout, reconnecting. Time since: {}ms", timeSince)
                handleConnectionFailure()
            } else {
                startHeartbeatMonitor()
            }
        }
    }

    private fun stopHeartbeatMonitor() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun cleanup() {
        connectionJob?.cancel()
        connectionJob = null
        tokenRefreshJob?.cancel()
        tokenRefreshJob = null
        stopHeartbeatMonitor()
        retryJob?.cancel()
        retryJob = null
    }

    /**
     * Shuts down the streaming manager.
     */
    fun shutdown() {
        disconnect()
        httpClient.close()
        scope.cancel()
    }
}
