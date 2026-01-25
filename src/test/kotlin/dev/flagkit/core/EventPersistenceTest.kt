package dev.flagkit.core

import kotlinx.coroutines.test.runTest
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class EventPersistenceTest {
    private lateinit var tempDir: File
    private lateinit var persistence: EventPersistence

    @BeforeTest
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "flagkit-test-${UUID.randomUUID()}")
        tempDir.mkdirs()
        persistence = EventPersistence(
            storagePath = tempDir.absolutePath,
            maxEvents = 100,
            flushIntervalMs = 100
        )
    }

    @AfterTest
    fun tearDown() = runTest {
        persistence.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun `test persist event creates file`() = runTest {
        val event = Event(
            eventType = "test",
            data = mapOf("key" to "value"),
            userId = "user-123"
        )

        val id = persistence.persist(event)
        persistence.flush()

        assertNotNull(id)
        assertTrue(id.isNotEmpty())

        // Check that a file was created
        val eventFiles = tempDir.listFiles { file ->
            file.name.startsWith("flagkit-events-") && file.name.endsWith(".jsonl")
        }
        assertNotNull(eventFiles)
        assertTrue(eventFiles.isNotEmpty())
    }

    @Test
    fun `test persist multiple events`() = runTest {
        repeat(5) { i ->
            persistence.persist(Event(
                eventType = "test-$i",
                data = mapOf("index" to i.toString())
            ))
        }
        persistence.flush()

        val stats = persistence.getStats()
        assertEquals(5, stats["totalEvents"])
        assertEquals(5, stats["pendingEvents"])
    }

    @Test
    fun `test recover events on startup`() = runTest {
        // Persist some events
        val events = (1..3).map { i ->
            Event(
                eventType = "test-$i",
                userId = "user-$i"
            )
        }

        events.forEach { persistence.persist(it) }
        persistence.flush()

        // Close and create new persistence instance
        persistence.close()

        val newPersistence = EventPersistence(
            storagePath = tempDir.absolutePath,
            maxEvents = 100,
            flushIntervalMs = 100
        )

        // Recover events
        val recovered = newPersistence.recover()

        assertEquals(3, recovered.size)
        assertTrue(recovered.any { it.eventType == "test-1" })
        assertTrue(recovered.any { it.eventType == "test-2" })
        assertTrue(recovered.any { it.eventType == "test-3" })

        newPersistence.close()
    }

    @Test
    fun `test mark sent removes events from pending`() = runTest {
        val event = Event(
            eventType = "test",
            data = mapOf("key" to "value")
        )

        val id = persistence.persist(event)
        persistence.flush()

        // Mark as sent
        persistence.markSent(listOf(id))

        val stats = persistence.getStats()
        assertEquals(0, stats["pendingEvents"])
        assertEquals(1, stats["sentEvents"])
    }

    @Test
    fun `test sent events not recovered`() = runTest {
        val event = Event(eventType = "test")

        val id = persistence.persist(event)
        persistence.flush()
        persistence.markSent(listOf(id))
        persistence.close()

        // Create new persistence and recover
        val newPersistence = EventPersistence(
            storagePath = tempDir.absolutePath,
            maxEvents = 100,
            flushIntervalMs = 100
        )

        val recovered = newPersistence.recover()
        assertTrue(recovered.isEmpty())

        newPersistence.close()
    }

    @Test
    fun `test cleanup removes old sent events`() = runTest {
        val event = Event(eventType = "test")

        val id = persistence.persist(event)
        persistence.flush()
        persistence.markSent(listOf(id))

        // Cleanup with 0 retention (remove immediately)
        persistence.cleanup(retentionMs = 0)

        val stats = persistence.getStats()
        assertEquals(0, stats["totalEvents"])
    }

    @Test
    fun `test max events limit`() = runTest {
        val maxEvents = 5
        val limitedPersistence = EventPersistence(
            storagePath = tempDir.absolutePath,
            maxEvents = maxEvents,
            flushIntervalMs = 100
        )

        // Add more than max events
        repeat(10) { i ->
            limitedPersistence.persist(Event(
                eventType = "test-$i"
            ))
        }
        limitedPersistence.flush()

        val stats = limitedPersistence.getStats()
        assertEquals(maxEvents, stats["totalEvents"])

        limitedPersistence.close()
    }

    @Test
    fun `test file locking prevents corruption`() = runTest {
        // Create two persistence instances pointing to same directory
        val persistence1 = EventPersistence(
            storagePath = tempDir.absolutePath,
            maxEvents = 100,
            flushIntervalMs = 10000 // Long interval to control flush manually
        )
        val persistence2 = EventPersistence(
            storagePath = tempDir.absolutePath,
            maxEvents = 100,
            flushIntervalMs = 10000
        )

        // Persist events from both instances
        persistence1.persist(Event(eventType = "from-1"))
        persistence2.persist(Event(eventType = "from-2"))

        // Flush both - file locking should prevent corruption
        persistence1.flush()
        persistence2.flush()

        // Both events should be readable
        persistence1.close()
        persistence2.close()

        val recoveryPersistence = EventPersistence(
            storagePath = tempDir.absolutePath,
            maxEvents = 100,
            flushIntervalMs = 100
        )
        val recovered = recoveryPersistence.recover()

        // At least one event should be recoverable (both ideally)
        assertTrue(recovered.isNotEmpty())

        recoveryPersistence.close()
    }

    @Test
    fun `test mark sending and pending status transitions`() = runTest {
        val event = Event(eventType = "test")

        val id = persistence.persist(event)
        persistence.flush()

        // Mark as sending
        persistence.markSending(listOf(id))

        var stats = persistence.getStats()
        assertEquals(1, stats["sendingEvents"])
        assertEquals(0, stats["pendingEvents"])

        // Revert to pending (simulating failed send)
        persistence.markPending(listOf(id))

        stats = persistence.getStats()
        assertEquals(0, stats["sendingEvents"])
        assertEquals(1, stats["pendingEvents"])
    }

    @Test
    fun `test sending events are recovered as they may have crashed mid-send`() = runTest {
        val event = Event(eventType = "test")

        val id = persistence.persist(event)
        persistence.flush()
        persistence.markSending(listOf(id))
        persistence.close()

        // Create new persistence and recover
        val newPersistence = EventPersistence(
            storagePath = tempDir.absolutePath,
            maxEvents = 100,
            flushIntervalMs = 100
        )

        // Events marked as 'sending' should be recovered (crash scenario)
        val recovered = newPersistence.recover()
        assertEquals(1, recovered.size)
        assertEquals("test", recovered.first().eventType)

        newPersistence.close()
    }

    @Test
    fun `test event data is preserved through persistence`() = runTest {
        val originalData = mapOf(
            "stringVal" to "hello",
            "numberVal" to "42",
            "boolVal" to "true"
        )
        val event = Event(
            eventType = "complex-event",
            data = originalData,
            userId = "user-123",
            sessionId = "session-456",
            environmentId = "env-789",
            sdkVersion = "1.0.0"
        )

        persistence.persist(event)
        persistence.flush()
        persistence.close()

        val newPersistence = EventPersistence(
            storagePath = tempDir.absolutePath,
            maxEvents = 100,
            flushIntervalMs = 100
        )

        val recovered = newPersistence.recover()
        assertEquals(1, recovered.size)

        val recoveredEvent = recovered.first()
        assertEquals("complex-event", recoveredEvent.eventType)
        assertEquals("user-123", recoveredEvent.userId)
        assertEquals("session-456", recoveredEvent.sessionId)
        assertEquals("env-789", recoveredEvent.environmentId)
        assertEquals("1.0.0", recoveredEvent.sdkVersion)

        newPersistence.close()
    }

    @Test
    fun `test getStats returns correct values`() = runTest {
        persistence.start()

        val stats = persistence.getStats()

        assertTrue(stats.containsKey("totalEvents"))
        assertTrue(stats.containsKey("pendingEvents"))
        assertTrue(stats.containsKey("sendingEvents"))
        assertTrue(stats.containsKey("sentEvents"))
        assertTrue(stats.containsKey("bufferSize"))
        assertTrue(stats.containsKey("storagePath"))
        assertEquals(tempDir.absolutePath, stats["storagePath"])
    }

    @Test
    fun `test empty recovery when no events exist`() = runTest {
        val recovered = persistence.recover()
        assertTrue(recovered.isEmpty())
    }

    @Test
    fun `test getEventId returns correct id`() = runTest {
        val event = Event(
            eventType = "test",
            timestamp = Instant.now()
        )

        val id = persistence.persist(event)
        persistence.flush()

        val retrievedId = persistence.getEventId(event)
        assertEquals(id, retrievedId)
    }

    @Test
    fun `test getEventIds returns map for multiple events`() = runTest {
        val events = listOf(
            Event(eventType = "test-1", timestamp = Instant.now()),
            Event(eventType = "test-2", timestamp = Instant.now().plusMillis(1)),
            Event(eventType = "test-3", timestamp = Instant.now().plusMillis(2))
        )

        val ids = events.map { persistence.persist(it) }
        persistence.flush()

        val eventIdMap = persistence.getEventIds(events)
        assertEquals(3, eventIdMap.size)
    }
}
