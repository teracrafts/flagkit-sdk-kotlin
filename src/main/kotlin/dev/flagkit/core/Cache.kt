package dev.flagkit.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import kotlin.time.Duration

/**
 * Cache entry with metadata for TTL and LRU tracking.
 *
 * @param V The type of value stored in the cache entry.
 * @property value The cached value.
 * @property fetchedAt When the entry was fetched/stored.
 * @property expiresAt When the entry expires.
 * @property lastAccessedAt When the entry was last accessed (for LRU eviction).
 */
data class CacheEntry<V>(
    val value: V,
    val fetchedAt: Instant,
    val expiresAt: Instant,
    var lastAccessedAt: Instant = Instant.now()
) {
    /**
     * Check if the entry has expired.
     */
    val isExpired: Boolean
        get() = Instant.now().isAfter(expiresAt)

    /**
     * Check if the entry is stale (expired but still available for fallback).
     */
    val isStale: Boolean
        get() = isExpired
}

/**
 * Cache statistics for monitoring and debugging.
 *
 * @property size Current number of entries in the cache.
 * @property validCount Number of non-expired entries.
 * @property staleCount Number of expired but still stored entries.
 * @property maxSize Maximum allowed cache size.
 * @property hitCount Number of cache hits.
 * @property missCount Number of cache misses.
 */
data class CacheStats(
    val size: Int,
    val validCount: Int,
    val staleCount: Int,
    val maxSize: Int,
    val hitCount: Long,
    val missCount: Long
) {
    /**
     * Cache hit rate as a percentage.
     */
    val hitRate: Double
        get() = if (hitCount + missCount > 0) {
            hitCount.toDouble() / (hitCount + missCount) * 100.0
        } else 0.0
}

/**
 * Thread-safe in-memory cache with TTL and LRU eviction.
 *
 * Features:
 * - Configurable TTL (default 5 minutes)
 * - Maximum size limit (default 1000 entries)
 * - LRU eviction when max size is reached
 * - Stale value fallback for resilience
 * - Thread-safe operations using Mutex
 *
 * @param V The type of values stored in the cache.
 * @param ttl Time-to-live for cache entries.
 * @param maxSize Maximum number of entries allowed.
 */
class Cache<V>(
    private val ttl: Duration,
    private val maxSize: Int = DEFAULT_MAX_SIZE
) {
    private val entries = mutableMapOf<String, CacheEntry<V>>()
    private val mutex = Mutex()
    private var hitCount: Long = 0
    private var missCount: Long = 0

    /**
     * Get a value from cache if it exists and is not expired.
     *
     * @param key The cache key.
     * @return The cached value, or null if not found or expired.
     */
    suspend fun get(key: String): V? = mutex.withLock {
        val entry = entries[key]

        if (entry == null) {
            missCount++
            return@withLock null
        }

        if (entry.isExpired) {
            // Don't remove - keep for stale fallback
            missCount++
            return@withLock null
        }

        entry.lastAccessedAt = Instant.now()
        hitCount++
        entry.value
    }

    /**
     * Get the full cache entry with metadata.
     *
     * @param key The cache key.
     * @return The cache entry, or null if not found.
     */
    suspend fun getEntry(key: String): CacheEntry<V>? = mutex.withLock {
        entries[key]
    }

    /**
     * Get a stale (expired) value for fallback when network is unavailable.
     * This returns the value even if it has expired.
     *
     * @param key The cache key.
     * @return The cached value (even if expired), or null if not found.
     */
    suspend fun getStale(key: String): V? = mutex.withLock {
        entries[key]?.value
    }

    /**
     * Check if a cached entry is stale (expired but still available).
     *
     * @param key The cache key.
     * @return True if the entry exists and is stale.
     */
    suspend fun isStale(key: String): Boolean = mutex.withLock {
        entries[key]?.isStale ?: false
    }

    /**
     * Set a value in the cache.
     *
     * @param key The cache key.
     * @param value The value to cache.
     * @param customTtl Optional custom TTL for this entry.
     */
    suspend fun set(key: String, value: V, customTtl: Duration? = null) = mutex.withLock {
        evictIfNeededInternal()
        val now = Instant.now()
        val effectiveTtl = customTtl ?: ttl
        entries[key] = CacheEntry(
            value = value,
            fetchedAt = now,
            expiresAt = now.plusMillis(effectiveTtl.inWholeMilliseconds)
        )
    }

    /**
     * Set multiple values in the cache.
     *
     * @param map Map of keys to values.
     * @param customTtl Optional custom TTL for these entries.
     */
    suspend fun setAll(map: Map<String, V>, customTtl: Duration? = null) = mutex.withLock {
        val now = Instant.now()
        val effectiveTtl = customTtl ?: ttl
        map.forEach { (key, value) ->
            evictIfNeededInternal()
            entries[key] = CacheEntry(
                value = value,
                fetchedAt = now,
                expiresAt = now.plusMillis(effectiveTtl.inWholeMilliseconds)
            )
        }
    }

    /**
     * Check if a key exists in the cache (valid, non-expired).
     *
     * @param key The cache key.
     * @return True if the key exists and is not expired.
     */
    suspend fun has(key: String): Boolean = mutex.withLock {
        val entry = entries[key] ?: return@withLock false
        !entry.isExpired
    }

    /**
     * Check if a key exists in the cache (including stale entries).
     *
     * @param key The cache key.
     * @return True if the key exists (even if expired).
     */
    suspend fun hasAny(key: String): Boolean = mutex.withLock {
        entries.containsKey(key)
    }

    /**
     * Delete a key from the cache.
     *
     * @param key The cache key.
     * @return True if the key was deleted.
     */
    suspend fun delete(key: String): Boolean = mutex.withLock {
        entries.remove(key) != null
    }

    /**
     * Clear all entries from the cache.
     */
    suspend fun clear() = mutex.withLock {
        entries.clear()
        hitCount = 0
        missCount = 0
    }

    /**
     * Get the current size of the cache.
     *
     * @return The number of entries in the cache.
     */
    suspend fun size(): Int = mutex.withLock {
        entries.size
    }

    /**
     * Get all keys in the cache (including stale).
     *
     * @return Set of all cache keys.
     */
    suspend fun keys(): Set<String> = mutex.withLock {
        entries.keys.toSet()
    }

    /**
     * Get all valid (non-expired) keys.
     *
     * @return Set of valid cache keys.
     */
    suspend fun validKeys(): Set<String> = mutex.withLock {
        entries.filter { !it.value.isExpired }.keys.toSet()
    }

    /**
     * Get all values as a map (only valid entries).
     *
     * @return Map of keys to values for non-expired entries.
     */
    suspend fun toMap(): Map<String, V> = mutex.withLock {
        cleanupExpired()
        entries.mapValues { it.value.value }
    }

    /**
     * Get all values including stale ones.
     *
     * @return Map of all keys to values.
     */
    suspend fun toMapIncludingStale(): Map<String, V> = mutex.withLock {
        entries.mapValues { it.value.value }
    }

    /**
     * Get all valid (non-expired) values.
     *
     * @return List of non-expired values.
     */
    suspend fun getAllValid(): List<V> = mutex.withLock {
        val now = Instant.now()
        entries.values
            .filter { now.isBefore(it.expiresAt) || now == it.expiresAt }
            .map { it.value }
    }

    /**
     * Get all values including stale ones.
     *
     * @return List of all values.
     */
    suspend fun getAll(): List<V> = mutex.withLock {
        entries.values.map { it.value }
    }

    /**
     * Get cache statistics.
     *
     * @return Cache statistics including size, hit/miss counts.
     */
    suspend fun getStats(): CacheStats = mutex.withLock {
        val now = Instant.now()
        var validCount = 0
        var staleCount = 0

        entries.values.forEach { entry ->
            if (now.isBefore(entry.expiresAt) || now == entry.expiresAt) {
                validCount++
            } else {
                staleCount++
            }
        }

        CacheStats(
            size = entries.size,
            validCount = validCount,
            staleCount = staleCount,
            maxSize = maxSize,
            hitCount = hitCount,
            missCount = missCount
        )
    }

    /**
     * Export cache data for persistence.
     *
     * @return Map of keys to cache entries.
     */
    suspend fun export(): Map<String, CacheEntry<V>> = mutex.withLock {
        entries.toMap()
    }

    /**
     * Import cache data from persistence.
     * Only imports non-expired entries.
     *
     * @param data Map of keys to cache entries.
     */
    suspend fun import(data: Map<String, CacheEntry<V>>) = mutex.withLock {
        val now = Instant.now()
        data.forEach { (key, entry) ->
            if (now.isBefore(entry.expiresAt) || now == entry.expiresAt) {
                entries[key] = entry
            }
        }
    }

    /**
     * Remove all expired entries from the cache.
     */
    suspend fun cleanup() = mutex.withLock {
        cleanupExpired()
    }

    /**
     * Internal cleanup of expired entries (called within mutex).
     */
    private fun cleanupExpired() {
        entries.entries.removeIf { it.value.isExpired }
    }

    /**
     * Internal eviction logic (called within mutex).
     */
    private fun evictIfNeededInternal() {
        if (entries.size < maxSize) return

        // First try to remove expired entries
        cleanupExpired()
        if (entries.size < maxSize) return

        // LRU eviction - remove least recently accessed
        val lruKey = entries.minByOrNull { it.value.lastAccessedAt }?.key
        lruKey?.let { entries.remove(it) }
    }

    companion object {
        /**
         * Default maximum cache size.
         */
        const val DEFAULT_MAX_SIZE = 1000

        /**
         * Default TTL in milliseconds (5 minutes).
         */
        const val DEFAULT_TTL_MS = 300000L
    }
}
