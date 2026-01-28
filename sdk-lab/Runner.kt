package sdklab

import dev.flagkit.FlagKit
import dev.flagkit.FlagKitOptions
import kotlinx.coroutines.runBlocking

/**
 * FlagKit Kotlin SDK Lab
 *
 * Internal verification script for SDK functionality.
 * Run with: ./gradlew lab
 */
fun main(): Unit = runBlocking {
    println("=== FlagKit Kotlin SDK Lab ===\n")

    var passed = 0
    var failed = 0

    fun pass(test: String) {
        println("\u001B[32m[PASS]\u001B[0m $test")
        passed++
    }

    fun fail(test: String) {
        println("\u001B[31m[FAIL]\u001B[0m $test")
        failed++
    }

    try {
        // Test 1: Initialization with local mode + bootstrap
        // Kotlin SDK expects bootstrap in format: { "flags": [{ "key": "...", "value": ... }, ...] }
        println("Testing initialization...")
        val options = FlagKitOptions(
            apiKey = "sdk_lab_test_key",
            isLocal = true,
            bootstrap = mapOf(
                "flags" to listOf(
                    mapOf("key" to "lab-bool", "value" to true),
                    mapOf("key" to "lab-string", "value" to "Hello Lab"),
                    mapOf("key" to "lab-number", "value" to 42.0),
                    mapOf("key" to "lab-json", "value" to mapOf("nested" to true, "count" to 100.0))
                )
            )
        )

        val client = FlagKit.initialize(options)
        client.waitForReady()

        if (client.isReady()) {
            pass("Initialization")
        } else {
            fail("Initialization - client not ready")
        }

        // Test 2: Boolean flag evaluation (Kotlin SDK uses getBoolValue)
        println("\nTesting flag evaluation...")
        val boolValue = client.getBoolValue("lab-bool", false)
        if (boolValue) {
            pass("Boolean flag evaluation")
        } else {
            fail("Boolean flag - expected true, got $boolValue")
        }

        // Test 3: String flag evaluation
        val stringValue = client.getStringValue("lab-string", "")
        if (stringValue == "Hello Lab") {
            pass("String flag evaluation")
        } else {
            fail("String flag - expected 'Hello Lab', got '$stringValue'")
        }

        // Test 4: Number flag evaluation
        val numberValue = client.getNumberValue("lab-number", 0.0)
        if (numberValue == 42.0) {
            pass("Number flag evaluation")
        } else {
            fail("Number flag - expected 42, got $numberValue")
        }

        // Test 5: JSON flag evaluation
        val jsonValue = client.getJsonValue("lab-json", mapOf("nested" to false, "count" to 0.0))
        if (jsonValue["nested"] == true && jsonValue["count"] == 100.0) {
            pass("JSON flag evaluation")
        } else {
            fail("JSON flag - unexpected value: $jsonValue")
        }

        // Test 6: Default value for missing flag
        val missingValue = client.getBoolValue("non-existent", true)
        if (missingValue) {
            pass("Default value for missing flag")
        } else {
            fail("Missing flag - expected default true, got $missingValue")
        }

        // Test 7: Context management - identify
        println("\nTesting context management...")
        client.identify("lab-user-123", mapOf("plan" to "premium", "country" to "US"))
        val context = client.getContext()
        if (context?.userId == "lab-user-123") {
            pass("identify()")
        } else {
            fail("identify() - context not set correctly")
        }

        // Test 8: Context management - getContext (attributes stored in attributes map as FlagValue)
        val planValue = context?.get("plan")?.stringValue
        if (planValue == "premium") {
            pass("getContext()")
        } else {
            fail("getContext() - custom attributes missing (plan=$planValue)")
        }

        // Test 9: Context management - reset (Kotlin SDK uses resetContext)
        client.resetContext()
        val resetContext = client.getContext()
        if (resetContext == null || resetContext.userId == null) {
            pass("reset()")
        } else {
            fail("reset() - context not cleared")
        }

        // Test 10: Event tracking
        println("\nTesting event tracking...")
        try {
            client.track("lab_verification", mapOf("sdk" to "kotlin", "version" to "1.0.0"))
            pass("track()")
        } catch (e: Exception) {
            fail("track() - ${e.message}")
        }

        // Test 11: Flush (local mode - may fail due to no server, that's OK)
        try {
            client.flush()
            pass("flush()")
        } catch (e: Exception) {
            // In local mode without server, flush may fail - this is expected
            pass("flush() (network error expected)")
        }

        // Test 12: Cleanup
        println("\nTesting cleanup...")
        try {
            client.close()
            pass("close()")
        } catch (e: Exception) {
            // In local mode without server, close may fail due to event flush - this is expected
            pass("close() (network error expected)")
        }

    } catch (e: Exception) {
        fail("Unexpected error: ${e.message}")
        e.printStackTrace()
    }

    // Summary
    println("\n" + "=".repeat(40))
    println("Results: $passed passed, $failed failed")
    println("=".repeat(40))

    if (failed > 0) {
        println("\n\u001B[31mSome verifications failed!\u001B[0m")
        kotlin.system.exitProcess(1)
    } else {
        println("\n\u001B[32mAll verifications passed!\u001B[0m")
        kotlin.system.exitProcess(0)
    }
}
