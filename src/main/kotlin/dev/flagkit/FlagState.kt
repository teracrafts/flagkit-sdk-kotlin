package dev.flagkit

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * Represents the state of a feature flag.
 */
@Serializable
data class FlagState(
    val key: String,
    val value: FlagValue,
    val enabled: Boolean = true,
    val version: Int = 0,
    @SerialName("flagType") val flagType: FlagType? = null,
    @SerialName("lastModified") val lastModified: String? = null,
    val metadata: Map<String, String>? = null
) {
    val effectiveFlagType: FlagType
        get() = flagType ?: value.inferredType

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
}
