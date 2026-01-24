package dev.flagkit.core

import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class PollingManagerTest {
    @Test
    fun `test initial state is stopped`() = runTest {
        val manager = PollingManager(
            interval = 30.seconds,
            onUpdate = { }
        )

        assertTrue(manager.isStopped())
        assertFalse(manager.isRunning())
    }

    @Test
    fun `test start`() = runTest {
        val manager = PollingManager(
            interval = 30.seconds,
            onUpdate = { }
        )

        manager.start()

        assertTrue(manager.isRunning())
        assertFalse(manager.isStopped())

        manager.stop()
    }

    @Test
    fun `test stop`() = runTest {
        val manager = PollingManager(
            interval = 30.seconds,
            onUpdate = { }
        )

        manager.start()
        manager.stop()

        assertTrue(manager.isStopped())
        assertFalse(manager.isRunning())
    }

    @Test
    fun `test pause`() = runTest {
        val manager = PollingManager(
            interval = 30.seconds,
            onUpdate = { }
        )

        manager.start()
        manager.pause("Network unavailable")

        assertTrue(manager.isPaused())
        assertFalse(manager.isRunning())

        val state = manager.getState()
        assertTrue(state is PollingState.Paused)
        assertEquals("Network unavailable", (state as PollingState.Paused).reason)
    }

    @Test
    fun `test resume`() = runTest {
        val manager = PollingManager(
            interval = 30.seconds,
            onUpdate = { }
        )

        manager.start()
        manager.pause()
        manager.resume()

        assertTrue(manager.isRunning())
        assertFalse(manager.isPaused())

        manager.stop()
    }

    @Test
    fun `test pollNow`() = runTest {
        var pollCount = 0
        val manager = PollingManager(
            interval = 30.seconds,
            onUpdate = { pollCount++ }
        )

        val result = manager.pollNow()

        assertTrue(result.success)
        assertEquals(1, pollCount)
    }

    @Test
    fun `test pollNow failure`() = runTest {
        val manager = PollingManager(
            interval = 30.seconds,
            onUpdate = { throw RuntimeException("Network error") }
        )

        val result = manager.pollNow()

        assertFalse(result.success)
        assertNotNull(result.error)
    }

    @Test
    fun `test getStats`() = runTest {
        val manager = PollingManager(
            interval = 30.seconds,
            onUpdate = { }
        )

        manager.pollNow()
        val stats = manager.getStats()

        assertEquals("Stopped", stats["state"])
        assertEquals(1L, stats["totalPolls"])
        assertEquals(1L, stats["successfulPolls"])
        assertEquals(0L, stats["failedPolls"])
    }

    @Test
    fun `test reset`() = runTest {
        val manager = PollingManager(
            interval = 30.seconds,
            onUpdate = { throw RuntimeException("Error") }
        )

        // Cause some failures
        manager.pollNow()
        manager.pollNow()

        var stats = manager.getStats()
        assertEquals(2L, stats["failedPolls"])
        assertEquals(2, stats["consecutiveErrors"])

        manager.reset()

        stats = manager.getStats()
        assertEquals(0, stats["consecutiveErrors"])
    }

    @Test
    fun `test getLastUpdateTime`() = runTest {
        val manager = PollingManager(
            interval = 30.seconds,
            onUpdate = { }
        )

        assertNull(manager.getLastUpdateTime())

        manager.pollNow()

        assertNotNull(manager.getLastUpdateTime())
    }

    @Test
    fun `test create with config`() = runTest {
        val config = PollingConfig(
            interval = 60.seconds,
            jitter = 2.seconds,
            maxConsecutiveErrors = 5
        )

        val manager = PollingManager.create(config, onUpdate = { })

        assertNotNull(manager)
        assertTrue(manager.isStopped())
    }
}
