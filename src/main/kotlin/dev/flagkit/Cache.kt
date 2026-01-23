package dev.flagkit

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import kotlin.time.Duration

/**
 * Thread-safe in-memory cache with TTL and LRU eviction.
 */
class Cache<V>(
    private val ttl: Duration,
    private val maxSize: Int = 1000
) {
    private data class Entry<V>(
        val value: V,
        val expiresAt: Instant,
        var lastAccessedAt: Instant = Instant.now()
    ) {
        val isExpired: Boolean
            get() = Instant.now().isAfter(expiresAt)
    }

    private val entries = mutableMapOf<String, Entry<V>>()
    private val mutex = Mutex()

    suspend fun get(key: String): V? = mutex.withLock {
        val entry = entries[key] ?: return@withLock null

        if (entry.isExpired) {
            entries.remove(key)
            return@withLock null
        }

        entry.lastAccessedAt = Instant.now()
        entry.value
    }

    suspend fun set(key: String, value: V) = mutex.withLock {
        evictIfNeeded()
        entries[key] = Entry(
            value = value,
            expiresAt = Instant.now().plusMillis(ttl.inWholeMilliseconds)
        )
    }

    suspend fun has(key: String): Boolean = mutex.withLock {
        val entry = entries[key] ?: return@withLock false

        if (entry.isExpired) {
            entries.remove(key)
            return@withLock false
        }

        true
    }

    suspend fun delete(key: String): Boolean = mutex.withLock {
        entries.remove(key) != null
    }

    suspend fun clear() = mutex.withLock {
        entries.clear()
    }

    suspend fun size(): Int = mutex.withLock {
        cleanupExpired()
        entries.size
    }

    suspend fun keys(): Set<String> = mutex.withLock {
        cleanupExpired()
        entries.keys.toSet()
    }

    suspend fun toMap(): Map<String, V> = mutex.withLock {
        cleanupExpired()
        entries.mapValues { it.value.value }
    }

    suspend fun setAll(map: Map<String, V>) = mutex.withLock {
        map.forEach { (key, value) ->
            evictIfNeeded()
            entries[key] = Entry(
                value = value,
                expiresAt = Instant.now().plusMillis(ttl.inWholeMilliseconds)
            )
        }
    }

    private fun cleanupExpired() {
        entries.entries.removeIf { it.value.isExpired }
    }

    private fun evictIfNeeded() {
        if (entries.size < maxSize) return

        cleanupExpired()
        if (entries.size < maxSize) return

        // LRU eviction
        val lruKey = entries.minByOrNull { it.value.lastAccessedAt }?.key
        lruKey?.let { entries.remove(it) }
    }
}
