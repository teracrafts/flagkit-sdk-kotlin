package dev.flagkit

import kotlin.test.*

class EvaluationContextTest {
    @Test
    fun `test empty context`() {
        val context = EvaluationContext()
        assertNull(context.userId)
        assertTrue(context.attributes.isEmpty())
        assertTrue(context.isEmpty)
    }

    @Test
    fun `test context with userId`() {
        val context = EvaluationContext(userId = "user-123")
        assertEquals("user-123", context.userId)
        assertFalse(context.isEmpty)
    }

    @Test
    fun `test withUserId`() {
        val context = EvaluationContext(
            attributes = mapOf("email" to FlagValue.StringValue("test@example.com"))
        )
        val newContext = context.withUserId("user-456")

        assertEquals("user-456", newContext.userId)
        assertEquals("test@example.com", newContext["email"]?.stringValue)
        assertNull(context.userId) // Original unchanged
    }

    @Test
    fun `test withAttribute`() {
        val context = EvaluationContext(userId = "user-123")
        val newContext = context.withAttribute("plan", "enterprise")

        assertEquals("enterprise", newContext["plan"]?.stringValue)
        assertNull(context["plan"])
    }

    @Test
    fun `test merge`() {
        val context1 = EvaluationContext(
            userId = "user-1",
            attributes = mapOf("email" to FlagValue.StringValue("a@test.com"))
        )
        val context2 = EvaluationContext(
            userId = "user-2",
            attributes = mapOf("plan" to FlagValue.StringValue("pro"))
        )

        val merged = context1.merge(context2)

        assertEquals("user-2", merged.userId)
        assertEquals("a@test.com", merged["email"]?.stringValue)
        assertEquals("pro", merged["plan"]?.stringValue)
    }

    @Test
    fun `test merge with null`() {
        val context = EvaluationContext(userId = "user-1")
        val merged = context.merge(null)

        assertEquals(context, merged)
    }

    @Test
    fun `test stripPrivateAttributes`() {
        val context = EvaluationContext(
            userId = "user-123",
            attributes = mapOf(
                "email" to FlagValue.StringValue("test@example.com"),
                "_secret" to FlagValue.StringValue("hidden"),
                "_internal" to FlagValue.StringValue("value")
            )
        )

        val stripped = context.stripPrivateAttributes()

        assertEquals("user-123", stripped.userId)
        assertEquals("test@example.com", stripped["email"]?.stringValue)
        assertNull(stripped["_secret"])
        assertNull(stripped["_internal"])
    }

    @Test
    fun `test toMap`() {
        val context = EvaluationContext(
            userId = "user-123",
            attributes = mapOf("email" to FlagValue.StringValue("test@example.com"))
        )

        val map = context.toMap()

        assertEquals("user-123", map["userId"])
        assertNotNull(map["attributes"])
    }

    @Test
    fun `test builder`() {
        val context = EvaluationContext.builder()
            .userId("user-123")
            .attribute("enabled", true)
            .attribute("name", "Test")
            .attribute("count", 42)
            .build()

        assertEquals("user-123", context.userId)
        assertEquals(true, context["enabled"]?.boolValue)
        assertEquals("Test", context["name"]?.stringValue)
        assertEquals(42L, context["count"]?.intValue)
    }
}
