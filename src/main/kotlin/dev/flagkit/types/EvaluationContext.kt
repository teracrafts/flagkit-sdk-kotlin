package dev.flagkit.types

/**
 * Context for flag evaluation containing user and custom attributes.
 */
data class EvaluationContext(
    val userId: String? = null,
    val attributes: Map<String, FlagValue> = emptyMap()
) {
    fun withUserId(userId: String) = copy(userId = userId)

    fun withAttribute(key: String, value: Any?) = copy(
        attributes = attributes + (key to FlagValue.from(value))
    )

    fun withAttributes(attrs: Map<String, Any?>) = copy(
        attributes = attributes + attrs.mapValues { FlagValue.from(it.value) }
    )

    fun merge(other: EvaluationContext?): EvaluationContext {
        if (other == null) return this
        return EvaluationContext(
            userId = other.userId ?: userId,
            attributes = attributes + other.attributes
        )
    }

    fun stripPrivateAttributes(): EvaluationContext = copy(
        attributes = attributes.filterKeys { !it.startsWith(PRIVATE_ATTRIBUTE_PREFIX) }
    )

    val isEmpty: Boolean
        get() = userId == null && attributes.isEmpty()

    operator fun get(key: String): FlagValue? = attributes[key]

    fun toMap(): Map<String, Any?> = buildMap {
        userId?.let { put("userId", it) }
        if (attributes.isNotEmpty()) {
            put("attributes", attributes.mapValues { it.value.toAny() })
        }
    }

    class Builder {
        private var userId: String? = null
        private val attributes = mutableMapOf<String, FlagValue>()

        fun userId(userId: String) = apply { this.userId = userId }

        fun attribute(key: String, value: Boolean) = apply {
            attributes[key] = FlagValue.BoolValue(value)
        }

        fun attribute(key: String, value: String) = apply {
            attributes[key] = FlagValue.StringValue(value)
        }

        fun attribute(key: String, value: Number) = apply {
            attributes[key] = when (value) {
                is Double, is Float -> FlagValue.DoubleValue(value.toDouble())
                else -> FlagValue.IntValue(value.toLong())
            }
        }

        fun build() = EvaluationContext(userId, attributes.toMap())
    }

    companion object {
        private const val PRIVATE_ATTRIBUTE_PREFIX = "_"

        fun builder() = Builder()
    }
}
