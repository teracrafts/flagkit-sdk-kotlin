# FlagKit Kotlin SDK

Official Kotlin SDK for [FlagKit](https://flagkit.dev) feature flag management.

## Requirements

- Kotlin 1.9+
- JDK 17+

## Installation

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("com.teracrafts:flagkit-kotlin:1.0.0")
}
```

### Gradle (Groovy)

```groovy
dependencies {
    implementation 'com.teracrafts:flagkit-kotlin:1.0.0'
}
```

## Quick Start

```kotlin
import dev.flagkit.*
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    // Initialize the SDK
    val client = FlagKit.initialize("sdk_your_api_key")

    // Identify the current user
    FlagKit.identify("user-123", mapOf("plan" to "pro"))

    // Evaluate feature flags
    val darkMode = FlagKit.getBoolValue("dark-mode", false)
    val theme = FlagKit.getStringValue("theme", "light")
    val maxItems = FlagKit.getIntValue("max-items", 10)
    val config = FlagKit.getJsonValue("feature-config", emptyMap())

    // Track events
    FlagKit.track("button_clicked", mapOf("button" to "signup"))

    // Shutdown when done
    FlagKit.shutdown()
}
```

## Features

- **Type-safe evaluation** - Boolean, string, number, and JSON flag types
- **Local caching** - Fast evaluations with configurable TTL and optional encryption
- **Background polling** - Automatic flag updates with coroutines
- **Event tracking** - Analytics with batching and crash-resilient persistence
- **Resilient** - Circuit breaker, retry with exponential backoff, offline support
- **Thread-safe** - Coroutine-safe with proper synchronization
- **Security** - PII detection, request signing, bootstrap verification, timing attack protection

## Configuration Options

```kotlin
val options = FlagKitOptions.builder("sdk_your_api_key")
    .pollingInterval(30.seconds)
    .cacheTtl(300.seconds)
    .cacheEnabled(true)
    .eventsEnabled(true)
    .eventBatchSize(10)
    .eventFlushInterval(30.seconds)
    .timeout(10.seconds)
    .retryAttempts(3)
    .build()

val client = FlagKit.initialize(options)
```

## Local Development


```kotlin
val options = FlagKitOptions.builder("sdk_your_api_key")
    .build()

val client = FlagKit.initialize(options)
```

## Using the Client Directly

```kotlin
val options = FlagKitOptions(apiKey = "sdk_your_api_key")
val client = FlagKitClient(options)

client.initialize()
client.waitForReady()

// Evaluate flags
val result = client.evaluate("my-feature", FlagValue.BoolValue(false))
println(result.value)
println(result.reason)
println(result.version)

// Clean up
client.close()
```

## Evaluation Context

```kotlin
// Build a context
val context = EvaluationContext.builder()
    .userId("user-123")
    .attribute("email", "user@example.com")
    .attribute("plan", "enterprise")
    .attribute("beta", true)
    .build()

// Use with evaluation
val value = client.getBoolValue("premium-feature", false, context)

// Private attributes (stripped before sending to server)
val contextWithPrivate = EvaluationContext(
    userId = "user-123",
    attributes = mapOf(
        "email" to FlagValue.StringValue("user@example.com"),
        "_internal_id" to FlagValue.StringValue("hidden")  // Underscore prefix = private
    )
)
```

## Error Handling

```kotlin
try {
    FlagKit.initialize("invalid_key")
} catch (e: FlagKitException) {
    println("Error code: ${e.errorCode.code}")
    println("Message: ${e.message}")
    println("Recoverable: ${e.isRecoverable}")
}
```

## Coroutines Support

The Kotlin SDK is built with coroutines and provides suspend functions:

```kotlin
// In a coroutine scope
launch {
    val value = FlagKit.getBoolValue("feature", false)
}

// Using runBlocking for testing/scripts
runBlocking {
    val value = FlagKit.getBoolValue("feature", false)
}
```

## Security

The SDK includes built-in security features that can be enabled through configuration options, including request signing, bootstrap verification, cache encryption, evaluation jitter for timing attack protection, and error message sanitization. These are configurable via `FlagKitOptions.builder()`.

## API Reference

### Static Methods (FlagKit object)

| Method | Description |
|--------|-------------|
| `FlagKit.initialize(options)` | Initialize the SDK |
| `FlagKit.initialize(apiKey)` | Initialize with API key |
| `FlagKit.shutdown()` | Shutdown and release resources |
| `FlagKit.isInitialized` | Check if SDK is initialized |
| `FlagKit.identify(userId, attributes)` | Set user context |
| `FlagKit.resetContext()` | Clear user context |
| `FlagKit.getBoolValue(key, default)` | Get boolean flag |
| `FlagKit.getStringValue(key, default)` | Get string flag |
| `FlagKit.getNumberValue(key, default)` | Get number flag |
| `FlagKit.getIntValue(key, default)` | Get integer flag |
| `FlagKit.getJsonValue(key, default)` | Get JSON flag |
| `FlagKit.evaluate(key, defaultValue)` | Get full evaluation result |
| `FlagKit.track(eventType, data)` | Track analytics event |

### Client Methods (FlagKitClient)

| Method | Description |
|--------|-------------|
| `client.initialize()` | Initialize and fetch flags |
| `client.waitForReady()` | Wait for initialization |
| `client.isReady()` | Check if ready |
| `client.identify(userId, attributes)` | Set user context |
| `client.resetContext()` | Clear user context |
| `client.getContext()` | Get current context |
| `client.evaluate(key, defaultValue, context)` | Evaluate a flag |
| `client.getBoolValue(key, default, context)` | Get boolean value |
| `client.getStringValue(key, default, context)` | Get string value |
| `client.getNumberValue(key, default, context)` | Get number value |
| `client.getIntValue(key, default, context)` | Get integer value |
| `client.getJsonValue(key, default, context)` | Get JSON value |
| `client.track(eventType, data)` | Track an event |
| `client.close()` | Close and release resources |

## Thread Safety

The SDK is designed for safe concurrent use with Kotlin coroutines. All operations are coroutine-safe with proper synchronization for:

- Flag cache access
- Event queue operations
- Context management
- Polling state

## License

MIT License - see [LICENSE](LICENSE) for details.
