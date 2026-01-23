package dev.flagkit

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Types of feature flags.
 */
@Serializable
enum class FlagType {
    @SerialName("boolean") BOOLEAN,
    @SerialName("string") STRING,
    @SerialName("number") NUMBER,
    @SerialName("json") JSON;

    companion object {
        fun infer(value: Any?): FlagType = when (value) {
            is Boolean -> BOOLEAN
            is String -> STRING
            is Number -> NUMBER
            else -> JSON
        }
    }
}
