package dev.flagkit.types

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Reasons for flag evaluation results.
 */
@Serializable
enum class EvaluationReason {
    @SerialName("CACHED") CACHED,
    @SerialName("DEFAULT") DEFAULT,
    @SerialName("FLAG_NOT_FOUND") FLAG_NOT_FOUND,
    @SerialName("BOOTSTRAP") BOOTSTRAP,
    @SerialName("SERVER") SERVER,
    @SerialName("STALE_CACHE") STALE_CACHE,
    @SerialName("ERROR") ERROR,
    @SerialName("DISABLED") DISABLED,
    @SerialName("TYPE_MISMATCH") TYPE_MISMATCH,
    @SerialName("OFFLINE") OFFLINE
}
