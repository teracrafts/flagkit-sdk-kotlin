package dev.flagkit.types

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Reasons for flag evaluation results.
 *
 * These reasons indicate why a particular value was returned during flag evaluation.
 */
@Serializable
enum class EvaluationReason {
    /** Value retrieved from cache (valid, non-expired). */
    @SerialName("CACHED") CACHED,

    /** Default value was returned. */
    @SerialName("DEFAULT") DEFAULT,

    /** Flag was not found. */
    @SerialName("FLAG_NOT_FOUND") FLAG_NOT_FOUND,

    /** Value came from bootstrap data. */
    @SerialName("BOOTSTRAP") BOOTSTRAP,

    /** Value was fetched from the server. */
    @SerialName("SERVER") SERVER,

    /** Value from stale (expired) cache used as fallback. */
    @SerialName("STALE_CACHE") STALE_CACHE,

    /** An error occurred during evaluation. */
    @SerialName("ERROR") ERROR,

    /** Flag is disabled in this environment. */
    @SerialName("DISABLED") DISABLED,

    /** Type mismatch between expected and actual value. */
    @SerialName("TYPE_MISMATCH") TYPE_MISMATCH,

    /** SDK is in offline mode. */
    @SerialName("OFFLINE") OFFLINE,

    /** Environment is not configured for this flag. */
    @SerialName("ENVIRONMENT_NOT_CONFIGURED") ENVIRONMENT_NOT_CONFIGURED,

    /** Default targeting rule matched (fallthrough). */
    @SerialName("FALLTHROUGH") FALLTHROUGH,

    /** A targeting rule matched. */
    @SerialName("RULE_MATCH") RULE_MATCH,

    /** User is in a matched segment. */
    @SerialName("SEGMENT_MATCH") SEGMENT_MATCH,

    /** Evaluation resulted in an error. */
    @SerialName("EVALUATION_ERROR") EVALUATION_ERROR
}
