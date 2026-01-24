package dev.flagkit.core

import dev.flagkit.types.EvaluationContext
import dev.flagkit.types.FlagValue
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ContextManagerTest {
    @Test
    fun `test setContext and getContext`() = runTest {
        val manager = ContextManager()
        val context = EvaluationContext(userId = "user-123")

        manager.setContext(context)
        val result = manager.getContext()

        assertEquals("user-123", result?.userId)
    }

    @Test
    fun `test getContext returns null initially`() = runTest {
        val manager = ContextManager()
        val result = manager.getContext()

        assertNull(result)
    }

    @Test
    fun `test clearContext`() = runTest {
        val manager = ContextManager()
        val context = EvaluationContext(userId = "user-123")

        manager.setContext(context)
        manager.clearContext()
        val result = manager.getContext()

        assertNull(result)
    }

    @Test
    fun `test identify`() = runTest {
        val manager = ContextManager()

        manager.identify("user-456", mapOf("plan" to "premium"))
        val result = manager.getContext()

        assertEquals("user-456", result?.userId)
        assertTrue(manager.isIdentified())
        assertFalse(manager.isAnonymous())
    }

    @Test
    fun `test reset to anonymous`() = runTest {
        val manager = ContextManager()

        manager.identify("user-123")
        manager.reset()

        assertTrue(manager.isAnonymous())
        assertFalse(manager.isIdentified())
    }

    @Test
    fun `test mergeContext with null evaluation context`() = runTest {
        val manager = ContextManager()
        val globalContext = EvaluationContext(userId = "global-user")

        manager.setContext(globalContext)
        val result = manager.mergeContext(null)

        assertEquals("global-user", result?.userId)
    }

    @Test
    fun `test mergeContext with evaluation context override`() = runTest {
        val manager = ContextManager()
        val globalContext = EvaluationContext(userId = "global-user")
        val evalContext = EvaluationContext(userId = "eval-user")

        manager.setContext(globalContext)
        val result = manager.mergeContext(evalContext)

        assertEquals("eval-user", result?.userId)
    }

    @Test
    fun `test mergeContext when no global context`() = runTest {
        val manager = ContextManager()
        val evalContext = EvaluationContext(userId = "eval-user")

        val result = manager.mergeContext(evalContext)

        assertEquals("eval-user", result?.userId)
    }

    @Test
    fun `test mergeContext returns null when both null`() = runTest {
        val manager = ContextManager()

        val result = manager.mergeContext(null)

        assertNull(result)
    }

    @Test
    fun `test getUserId`() = runTest {
        val manager = ContextManager()

        assertNull(manager.getUserId())

        manager.identify("user-123")

        assertEquals("user-123", manager.getUserId())
    }

    @Test
    fun `test setAttribute`() = runTest {
        val manager = ContextManager()

        manager.setAttribute("plan", "premium")
        val result = manager.getAttribute("plan")

        assertEquals(FlagValue.StringValue("premium"), result)
    }

    @Test
    fun `test setAttributes`() = runTest {
        val manager = ContextManager()

        manager.setAttributes(mapOf(
            "plan" to "premium",
            "country" to "US"
        ))

        assertEquals(FlagValue.StringValue("premium"), manager.getAttribute("plan"))
        assertEquals(FlagValue.StringValue("US"), manager.getAttribute("country"))
    }

    @Test
    fun `test removeAttribute`() = runTest {
        val manager = ContextManager()

        manager.setAttribute("plan", "premium")
        manager.removeAttribute("plan")

        assertNull(manager.getAttribute("plan"))
    }

    @Test
    fun `test hasAttribute`() = runTest {
        val manager = ContextManager()

        assertFalse(manager.hasAttribute("plan"))

        manager.setAttribute("plan", "premium")

        assertTrue(manager.hasAttribute("plan"))
    }

    @Test
    fun `test getAttributeKeys`() = runTest {
        val manager = ContextManager()

        assertEquals(emptySet(), manager.getAttributeKeys())

        manager.setAttribute("plan", "premium")
        manager.setAttribute("country", "US")

        assertEquals(setOf("plan", "country"), manager.getAttributeKeys())
    }
}
