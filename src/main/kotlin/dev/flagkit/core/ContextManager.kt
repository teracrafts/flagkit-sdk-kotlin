package dev.flagkit.core

import dev.flagkit.types.EvaluationContext
import dev.flagkit.types.FlagValue
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Manages global and per-evaluation context for flag evaluations.
 *
 * Features:
 * - Global context that applies to all evaluations
 * - Context merging with per-evaluation overrides
 * - Private attribute stripping for API requests
 * - User identification and anonymous state management
 *
 * Thread-safe implementation using coroutine Mutex.
 */
class ContextManager {
    private val mutex = Mutex()
    private var globalContext: EvaluationContext? = null

    /**
     * Set the global context that applies to all evaluations.
     *
     * @param context The context to set as global.
     */
    suspend fun setContext(context: EvaluationContext) = mutex.withLock {
        globalContext = context
    }

    /**
     * Get the current global context.
     *
     * @return The current global context, or null if not set.
     */
    suspend fun getContext(): EvaluationContext? = mutex.withLock {
        globalContext
    }

    /**
     * Clear the global context.
     */
    suspend fun clearContext() = mutex.withLock {
        globalContext = null
    }

    /**
     * Identify a user by setting userId and optional attributes.
     * This sets the user as non-anonymous.
     *
     * @param userId The unique user identifier.
     * @param attributes Optional additional attributes to set.
     */
    suspend fun identify(userId: String, attributes: Map<String, Any?> = emptyMap()) = mutex.withLock {
        val existingAttributes = globalContext?.attributes ?: emptyMap()
        val newAttributes = existingAttributes + attributes.mapValues { FlagValue.from(it.value) }

        globalContext = EvaluationContext(
            userId = userId,
            attributes = newAttributes + mapOf("anonymous" to FlagValue.BoolValue(false))
        )
    }

    /**
     * Reset to anonymous state.
     * Clears the userId and sets anonymous to true.
     */
    suspend fun reset() = mutex.withLock {
        globalContext = EvaluationContext(
            userId = null,
            attributes = mapOf("anonymous" to FlagValue.BoolValue(true))
        )
    }

    /**
     * Merge global context with per-evaluation context.
     * Per-evaluation context takes precedence over global context.
     *
     * @param evaluationContext Optional per-evaluation context to merge.
     * @return The merged context, or null if both are null.
     */
    suspend fun mergeContext(evaluationContext: EvaluationContext?): EvaluationContext? = mutex.withLock {
        when {
            globalContext == null && evaluationContext == null -> null
            globalContext == null -> evaluationContext
            evaluationContext == null -> globalContext
            else -> globalContext!!.merge(evaluationContext)
        }
    }

    /**
     * Resolve context by merging and stripping private attributes.
     * Use this when preparing context for API requests.
     *
     * @param evaluationContext Optional per-evaluation context to merge.
     * @return The resolved context with private attributes stripped.
     */
    suspend fun resolveContext(evaluationContext: EvaluationContext?): EvaluationContext? {
        val merged = mergeContext(evaluationContext)
        return merged?.stripPrivateAttributes()
    }

    /**
     * Get the raw merged context without stripping private attributes.
     *
     * @param evaluationContext Optional per-evaluation context to merge.
     * @return The merged context with private attributes intact.
     */
    suspend fun getMergedContext(evaluationContext: EvaluationContext?): EvaluationContext? {
        return mergeContext(evaluationContext)
    }

    /**
     * Check if a user is currently identified.
     *
     * @return True if a user is identified (has userId and is not anonymous).
     */
    suspend fun isIdentified(): Boolean = mutex.withLock {
        val context = globalContext ?: return@withLock false
        val anonymous = context.attributes["anonymous"]?.boolValue ?: true
        context.userId != null && !anonymous
    }

    /**
     * Check if the current state is anonymous.
     *
     * @return True if no user is identified or anonymous is true.
     */
    suspend fun isAnonymous(): Boolean = mutex.withLock {
        val context = globalContext ?: return@withLock true
        context.attributes["anonymous"]?.boolValue ?: (context.userId == null)
    }

    /**
     * Get the current user ID if identified.
     *
     * @return The current user ID, or null if not identified.
     */
    suspend fun getUserId(): String? = mutex.withLock {
        globalContext?.userId
    }

    /**
     * Add or update a single attribute in the global context.
     *
     * @param key The attribute key.
     * @param value The attribute value.
     */
    suspend fun setAttribute(key: String, value: Any?) = mutex.withLock {
        val current = globalContext ?: EvaluationContext()
        globalContext = current.withAttribute(key, value)
    }

    /**
     * Add or update multiple attributes in the global context.
     *
     * @param attributes Map of attributes to add/update.
     */
    suspend fun setAttributes(attributes: Map<String, Any?>) = mutex.withLock {
        val current = globalContext ?: EvaluationContext()
        globalContext = current.withAttributes(attributes)
    }

    /**
     * Remove an attribute from the global context.
     *
     * @param key The attribute key to remove.
     */
    suspend fun removeAttribute(key: String) = mutex.withLock {
        val current = globalContext ?: return@withLock
        globalContext = current.copy(
            attributes = current.attributes.filterKeys { it != key }
        )
    }

    /**
     * Get a specific attribute value from the global context.
     *
     * @param key The attribute key.
     * @return The attribute value, or null if not found.
     */
    suspend fun getAttribute(key: String): FlagValue? = mutex.withLock {
        globalContext?.attributes?.get(key)
    }

    /**
     * Check if the global context has a specific attribute.
     *
     * @param key The attribute key to check.
     * @return True if the attribute exists.
     */
    suspend fun hasAttribute(key: String): Boolean = mutex.withLock {
        globalContext?.attributes?.containsKey(key) ?: false
    }

    /**
     * Get all attribute keys from the global context.
     *
     * @return Set of attribute keys, or empty set if no context.
     */
    suspend fun getAttributeKeys(): Set<String> = mutex.withLock {
        globalContext?.attributes?.keys ?: emptySet()
    }
}
