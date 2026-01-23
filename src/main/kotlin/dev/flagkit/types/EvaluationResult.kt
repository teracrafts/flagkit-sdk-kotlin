package dev.flagkit.types

import java.time.Instant

/**
 * Result of evaluating a feature flag.
 */
data class EvaluationResult(
    val flagKey: String,
    val value: FlagValue,
    val enabled: Boolean = false,
    val reason: EvaluationReason = EvaluationReason.DEFAULT,
    val version: Int = 0,
    val timestamp: Instant = Instant.now()
) {
    val boolValue: Boolean
        get() = value.boolValue ?: false

    val stringValue: String?
        get() = value.stringValue

    val numberValue: Double
        get() = value.numberValue ?: 0.0

    val intValue: Long
        get() = value.intValue ?: 0L

    val jsonValue: Map<String, Any?>?
        get() = value.jsonValue

    companion object {
        fun defaultResult(key: String, defaultValue: FlagValue, reason: EvaluationReason) =
            EvaluationResult(
                flagKey = key,
                value = defaultValue,
                enabled = false,
                reason = reason
            )
    }
}
