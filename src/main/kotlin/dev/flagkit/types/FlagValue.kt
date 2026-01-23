package dev.flagkit.types

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

/**
 * A type-safe wrapper for flag values.
 */
@Serializable(with = FlagValueSerializer::class)
sealed class FlagValue {
    data class BoolValue(val value: Boolean) : FlagValue()
    data class StringValue(val value: String) : FlagValue()
    data class IntValue(val value: Long) : FlagValue()
    data class DoubleValue(val value: Double) : FlagValue()
    data class JsonObjectValue(val value: Map<String, FlagValue>) : FlagValue()
    data class JsonArrayValue(val value: List<FlagValue>) : FlagValue()
    data object NullValue : FlagValue()

    val boolValue: Boolean?
        get() = (this as? BoolValue)?.value

    val stringValue: String?
        get() = when (this) {
            is StringValue -> value
            is BoolValue -> value.toString()
            is IntValue -> value.toString()
            is DoubleValue -> value.toString()
            else -> null
        }

    val numberValue: Double?
        get() = when (this) {
            is DoubleValue -> value
            is IntValue -> value.toDouble()
            else -> null
        }

    val intValue: Long?
        get() = when (this) {
            is IntValue -> value
            is DoubleValue -> value.toLong()
            else -> null
        }

    val jsonValue: Map<String, Any?>?
        get() = (this as? JsonObjectValue)?.value?.mapValues { it.value.toAny() }

    fun toAny(): Any? = when (this) {
        is BoolValue -> value
        is StringValue -> value
        is IntValue -> value
        is DoubleValue -> value
        is JsonObjectValue -> value.mapValues { it.value.toAny() }
        is JsonArrayValue -> value.map { it.toAny() }
        is NullValue -> null
    }

    val inferredType: FlagType
        get() = when (this) {
            is BoolValue -> FlagType.BOOLEAN
            is StringValue -> FlagType.STRING
            is IntValue, is DoubleValue -> FlagType.NUMBER
            else -> FlagType.JSON
        }

    companion object {
        fun from(value: Any?): FlagValue = when (value) {
            null -> NullValue
            is Boolean -> BoolValue(value)
            is String -> StringValue(value)
            is Int -> IntValue(value.toLong())
            is Long -> IntValue(value)
            is Float -> DoubleValue(value.toDouble())
            is Double -> DoubleValue(value)
            is Map<*, *> -> JsonObjectValue(value.entries.associate {
                it.key.toString() to from(it.value)
            })
            is List<*> -> JsonArrayValue(value.map { from(it) })
            else -> NullValue
        }

        fun fromJsonElement(element: JsonElement): FlagValue = when (element) {
            is JsonNull -> NullValue
            is JsonPrimitive -> when {
                element.isString -> StringValue(element.content)
                element.content == "true" || element.content == "false" -> BoolValue(element.boolean)
                element.content.contains('.') -> DoubleValue(element.double)
                else -> IntValue(element.long)
            }
            is JsonObject -> JsonObjectValue(element.mapValues { fromJsonElement(it.value) })
            is JsonArray -> JsonArrayValue(element.map { fromJsonElement(it) })
        }
    }
}

object FlagValueSerializer : KSerializer<FlagValue> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("FlagValue")

    override fun serialize(encoder: Encoder, value: FlagValue) {
        val jsonEncoder = encoder as? JsonEncoder ?: error("Can only serialize to JSON")
        jsonEncoder.encodeJsonElement(value.toJsonElement())
    }

    override fun deserialize(decoder: Decoder): FlagValue {
        val jsonDecoder = decoder as? JsonDecoder ?: error("Can only deserialize from JSON")
        return FlagValue.fromJsonElement(jsonDecoder.decodeJsonElement())
    }

    private fun FlagValue.toJsonElement(): JsonElement = when (this) {
        is FlagValue.BoolValue -> JsonPrimitive(value)
        is FlagValue.StringValue -> JsonPrimitive(value)
        is FlagValue.IntValue -> JsonPrimitive(value)
        is FlagValue.DoubleValue -> JsonPrimitive(value)
        is FlagValue.JsonObjectValue -> JsonObject(value.mapValues { it.value.toJsonElement() })
        is FlagValue.JsonArrayValue -> JsonArray(value.map { it.toJsonElement() })
        is FlagValue.NullValue -> JsonNull
    }
}
