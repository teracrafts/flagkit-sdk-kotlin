package dev.flagkit.types

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Feature flags indicating server capabilities.
 */
@Serializable
data class ServerFeatures(
    val streaming: Boolean = false,
    val localEval: Boolean = false,
    val experiments: Boolean = false,
    val segments: Boolean = false
)

/**
 * Metadata included in the SDK init response.
 *
 * Contains SDK version requirements and feature flags from the server.
 */
@Serializable
data class InitResponseMetadata(
    /**
     * Minimum SDK version required.
     * SDKs below this version may not work correctly with the API.
     */
    @SerialName("sdkVersionMin")
    val sdkVersionMin: String? = null,

    /**
     * Recommended SDK version for optimal experience.
     * SDKs should encourage users to upgrade if below this version.
     */
    @SerialName("sdkVersionRecommended")
    val sdkVersionRecommended: String? = null,

    /**
     * Latest available SDK version.
     * Informational only - a newer version is available.
     */
    @SerialName("sdkVersionLatest")
    val sdkVersionLatest: String? = null,

    /**
     * Server-provided deprecation warning message.
     * Should be displayed to users if present.
     */
    @SerialName("deprecationWarning")
    val deprecationWarning: String? = null,

    /**
     * Server feature capabilities.
     */
    val features: ServerFeatures = ServerFeatures()
)

/**
 * Full init response from the SDK initialization endpoint.
 */
@Serializable
data class InitResponse(
    /** List of flag states */
    val flags: List<FlagState> = emptyList(),

    /** Environment name */
    val environment: String? = null,

    /** Environment ID */
    @SerialName("environmentId")
    val environmentId: String? = null,

    /** Project ID */
    @SerialName("projectId")
    val projectId: String? = null,

    /** Organization ID */
    @SerialName("organizationId")
    val organizationId: String? = null,

    /** Server time at response */
    @SerialName("serverTime")
    val serverTime: String? = null,

    /** Recommended polling interval in seconds */
    @SerialName("pollingIntervalSeconds")
    val pollingIntervalSeconds: Int = 30,

    /** Streaming URL if streaming is available */
    @SerialName("streamingUrl")
    val streamingUrl: String? = null,

    /** Response metadata including version requirements */
    val metadata: InitResponseMetadata? = null
)
