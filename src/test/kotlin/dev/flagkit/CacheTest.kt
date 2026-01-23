package dev.flagkit

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class CacheTest {
    @Test
    fun `test set and get`() = runTest {
        val cache = Cache<String>(ttl = 60.seconds, maxSize = 10)

        cache.set("key1", "value1")
        val result = cache.get("key1")

        assertEquals("value1", result)
    }

    @Test
    fun `test get missing key`() = runTest {
        val cache = Cache<String>(ttl = 60.seconds, maxSize = 10)
        val result = cache.get("nonexistent")

        assertNull(result)
    }

    @Test
    fun `test has`() = runTest {
        val cache = Cache<String>(ttl = 60.seconds, maxSize = 10)

        cache.set("key1", "value1")

        assertTrue(cache.has("key1"))
        assertFalse(cache.has("nonexistent"))
    }

    @Test
    fun `test delete`() = runTest {
        val cache = Cache<String>(ttl = 60.seconds, maxSize = 10)

        cache.set("key1", "value1")
        val deleted = cache.delete("key1")
        val result = cache.get("key1")

        assertTrue(deleted)
        assertNull(result)
    }

    @Test
    fun `test delete missing key`() = runTest {
        val cache = Cache<String>(ttl = 60.seconds, maxSize = 10)
        val deleted = cache.delete("nonexistent")

        assertFalse(deleted)
    }

    @Test
    fun `test clear`() = runTest {
        val cache = Cache<String>(ttl = 60.seconds, maxSize = 10)

        cache.set("key1", "value1")
        cache.set("key2", "value2")
        cache.clear()

        assertEquals(0, cache.size())
    }

    @Test
    fun `test size`() = runTest {
        val cache = Cache<String>(ttl = 60.seconds, maxSize = 10)

        assertEquals(0, cache.size())
        cache.set("key1", "value1")
        assertEquals(1, cache.size())
        cache.set("key2", "value2")
        assertEquals(2, cache.size())
    }

    @Test
    fun `test keys`() = runTest {
        val cache = Cache<String>(ttl = 60.seconds, maxSize = 10)

        cache.set("key1", "value1")
        cache.set("key2", "value2")

        assertEquals(setOf("key1", "key2"), cache.keys())
    }

    @Test
    fun `test toMap`() = runTest {
        val cache = Cache<String>(ttl = 60.seconds, maxSize = 10)

        cache.set("key1", "value1")
        cache.set("key2", "value2")

        assertEquals(mapOf("key1" to "value1", "key2" to "value2"), cache.toMap())
    }

    @Test
    fun `test setAll`() = runTest {
        val cache = Cache<String>(ttl = 60.seconds, maxSize = 10)

        cache.setAll(mapOf("key1" to "value1", "key2" to "value2"))

        assertEquals("value1", cache.get("key1"))
        assertEquals("value2", cache.get("key2"))
    }

    @Test
    fun `test TTL expiration`() = runTest {
        val cache = Cache<String>(ttl = 100.milliseconds, maxSize = 10)

        cache.set("key1", "value1")
        assertEquals("value1", cache.get("key1"))

        delay(150)

        assertNull(cache.get("key1"))
    }

    @Test
    fun `test LRU eviction`() = runTest {
        val cache = Cache<String>(ttl = 60.seconds, maxSize = 3)

        cache.set("key1", "value1")
        cache.set("key2", "value2")
        cache.set("key3", "value3")
        cache.set("key4", "value4")

        // One entry should be evicted
        val values = listOfNotNull(
            cache.get("key1"),
            cache.get("key2"),
            cache.get("key3"),
            cache.get("key4")
        )

        assertEquals(3, values.size)
        assertEquals("value4", cache.get("key4"))
    }
}
