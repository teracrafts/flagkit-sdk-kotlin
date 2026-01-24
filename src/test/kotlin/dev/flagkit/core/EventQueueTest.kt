package dev.flagkit.core

import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class EventQueueTest {
    @Test
    fun `test enqueue and size`() = runTest {
        var flushedEvents: List<Map<String, Any?>>? = null
        val queue = EventQueue(
            batchSize = 10,
            flushInterval = 60.seconds,
            onFlush = { events -> flushedEvents = events }
        )

        queue.enqueue(mapOf("type" to "test", "data" to mapOf("key" to "value")))

        assertEquals(1, queue.size())
    }

    @Test
    fun `test flush`() = runTest {
        var flushedEvents: List<Map<String, Any?>>? = null
        val queue = EventQueue(
            batchSize = 10,
            flushInterval = 60.seconds,
            onFlush = { events -> flushedEvents = events }
        )

        queue.enqueue(mapOf("type" to "test", "data" to mapOf("key" to "value")))
        queue.flush()

        assertNotNull(flushedEvents)
        assertEquals(1, flushedEvents?.size)
        assertEquals(0, queue.size())
    }

    @Test
    fun `test auto flush on batch size`() = runTest {
        var flushedEvents: List<Map<String, Any?>>? = null
        val queue = EventQueue(
            batchSize = 2,
            flushInterval = 60.seconds,
            onFlush = { events -> flushedEvents = events }
        )

        queue.enqueue(mapOf("type" to "test1"))
        assertNull(flushedEvents)

        queue.enqueue(mapOf("type" to "test2"))
        assertNotNull(flushedEvents)
        assertEquals(2, flushedEvents?.size)
    }

    @Test
    fun `test track method`() = runTest {
        var flushedEvents: List<Map<String, Any?>>? = null
        val queue = EventQueue(
            batchSize = 10,
            flushInterval = 60.seconds,
            onFlush = { events -> flushedEvents = events }
        )

        queue.track("purchase", mapOf("amount" to 99.99), "user-123")
        queue.flush()

        assertNotNull(flushedEvents)
        assertEquals(1, flushedEvents?.size)
        assertEquals("purchase", flushedEvents?.first()?.get("eventType"))
    }

    @Test
    fun `test clear`() = runTest {
        val queue = EventQueue(
            batchSize = 10,
            flushInterval = 60.seconds,
            onFlush = { }
        )

        queue.enqueue(mapOf("type" to "test"))
        assertEquals(1, queue.size())

        queue.clear()
        assertEquals(0, queue.size())
    }

    @Test
    fun `test getQueuedEvents`() = runTest {
        val queue = EventQueue(
            batchSize = 10,
            flushInterval = 60.seconds,
            onFlush = { }
        )

        queue.enqueue(mapOf("type" to "test"))
        val events = queue.getQueuedEvents()

        assertEquals(1, events.size)
        assertEquals("test", events.first().eventType)
    }

    @Test
    fun `test getStats`() = runTest {
        val queue = EventQueue(
            batchSize = 10,
            flushInterval = 60.seconds,
            onFlush = { }
        )

        queue.enqueue(mapOf("type" to "test"))
        val stats = queue.getStats()

        assertEquals(1, stats["queueSize"])
        assertEquals(1L, stats["eventCount"])
    }

    @Test
    fun `test disabled event types are not queued`() = runTest {
        val config = EventQueueConfig(
            batchSize = 10,
            flushInterval = 60.seconds,
            disabledEventTypes = setOf("disabled_type")
        )
        val queue = EventQueue.create(config, onFlush = { })

        queue.enqueue(mapOf("type" to "disabled_type"))

        assertEquals(0, queue.size())
    }

    @Test
    fun `test sampling at zero rate`() = runTest {
        val config = EventQueueConfig(
            batchSize = 10,
            flushInterval = 60.seconds,
            sampleRate = 0.0
        )
        val queue = EventQueue.create(config, onFlush = { })

        repeat(10) {
            queue.enqueue(mapOf("type" to "test"))
        }

        assertEquals(0, queue.size())
    }

    @Test
    fun `test requeue on failure`() = runTest {
        var failFirst = true
        val queue = EventQueue(
            batchSize = 10,
            flushInterval = 60.seconds,
            onFlush = { events ->
                if (failFirst) {
                    failFirst = false
                    throw RuntimeException("Failed to send")
                }
            }
        )

        queue.enqueue(mapOf("type" to "test"))

        val exception = assertFailsWith<Exception> {
            queue.flush()
        }

        // Event should be re-queued
        assertEquals(1, queue.size())
    }
}
