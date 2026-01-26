# FlagKit Kotlin SDK

Official Kotlin SDK for [FlagKit](https://flagkit.dev) feature flag management.

## Requirements

- Kotlin 1.9+
- JDK 17+

## Installation

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("dev.flagkit:flagkit-kotlin:1.0.0")
}
```

### Gradle (Groovy)

```groovy
dependencies {
    implementation 'dev.flagkit:flagkit-kotlin:1.0.0'
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

## Architecture

The SDK is organized into clean, modular packages:

```
dev.flagkit/
├── FlagKit.kt              # Static methods and singleton access
├── FlagKitClient.kt        # Main client implementation
├── FlagKitOptions.kt       # Configuration options
├── core/                   # Core components
│   ├── FlagCache.kt        # In-memory cache with TTL
│   ├── ContextManager.kt
│   ├── PollingManager.kt
│   ├── EventQueue.kt       # Event batching
│   └── EventPersistence.kt # Crash-resilient persistence
├── http/                   # HTTP client, circuit breaker, retry
│   ├── HttpClient.kt
│   └── CircuitBreaker.kt
├── error/                  # Error types and codes
│   ├── FlagKitException.kt
│   ├── ErrorCode.kt
│   └── ErrorSanitizer.kt
├── types/                  # Type definitions
│   ├── EvaluationContext.kt
│   ├── EvaluationResult.kt
│   └── FlagState.kt
└── utils/                  # Utilities
    └── Security.kt         # PII detection, HMAC signing
```

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

For local development, use the `localPort` option to connect to a local FlagKit server:

```kotlin
val options = FlagKitOptions.builder("sdk_your_api_key")
    .localPort(8200)  // Uses http://localhost:8200/api/v1
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

## Security Features

### PII Detection

The SDK can detect and warn about potential PII (Personally Identifiable Information) in contexts and events:

```kotlin
// Enable strict PII mode - throws exceptions instead of warnings
val options = FlagKitOptions.builder("sdk_...")
    .strictPiiMode(true)
    .build()

// Attributes containing PII will throw FlagKitException
try {
    client.identify("user-123", mapOf(
        "email" to "user@example.com"  // PII detected!
    ))
} catch (e: FlagKitException) {
    println("PII error: ${e.message}")
}

// Use private attributes to mark fields as intentionally containing PII
val context = EvaluationContext(
    userId = "user-123",
    attributes = mapOf(
        "email" to FlagValue.StringValue("user@example.com"),
        "_email" to FlagValue.BoolValue(true)  // Underscore prefix marks as private
    )
)
```

### Request Signing

POST requests to the FlagKit API are signed with HMAC-SHA256 for integrity:

```kotlin
// Enabled by default, can be disabled if needed
val options = FlagKitOptions.builder("sdk_...")
    .enableRequestSigning(false)  // Disable signing
    .build()
```

### Bootstrap Signature Verification

Verify bootstrap data integrity using HMAC signatures:

```kotlin
// Create signed bootstrap data
val bootstrap = Security.createBootstrapSignature(
    flags = mapOf("feature-a" to true, "feature-b" to "value"),
    apiKey = "sdk_your_api_key"
)

// Use signed bootstrap with verification
val options = FlagKitOptions.builder("sdk_...")
    .bootstrapConfig(bootstrap)
    .bootstrapVerification(BootstrapVerificationConfig(
        enabled = true,
        maxAge = 86_400_000L,  // 24 hours in milliseconds
        onFailure = "error"   // "warn" (default), "error", or "ignore"
    ))
    .build()
```

### Cache Encryption

Enable AES-256-GCM encryption for cached flag data:

```kotlin
val options = FlagKitOptions.builder("sdk_...")
    .enableCacheEncryption(true)
    .build()
```

### Evaluation Jitter (Timing Attack Protection)

Add random delays to flag evaluations to prevent cache timing attacks:

```kotlin
val options = FlagKitOptions.builder("sdk_...")
    .evaluationJitter(EvaluationJitterConfig(
        enabled = true,
        minMs = 5,
        maxMs = 15
    ))
    .build()
```

### Error Sanitization

Automatically redact sensitive information from error messages:

```kotlin
val options = FlagKitOptions.builder("sdk_...")
    .errorSanitization(ErrorSanitizationConfig(
        enabled = true,
        preserveOriginal = false  // Set true for debugging
    ))
    .build()
// Errors will have paths, IPs, API keys, and emails redacted
```

## Event Persistence

Enable crash-resilient event persistence to prevent data loss:

```kotlin
val options = FlagKitOptions.builder("sdk_...")
    .persistEvents(true)
    .eventStoragePath("/path/to/storage")  // Optional, defaults to temp dir
    .maxPersistedEvents(10000)             // Optional, default 10000
    .persistenceFlushIntervalMs(1000)      // Optional, default 1000ms
    .build()
```

Events are written to disk before being sent, and automatically recovered on restart.

## Key Rotation

Support seamless API key rotation:

```kotlin
val options = FlagKitOptions.builder("sdk_primary_key")
    .secondaryApiKey("sdk_secondary_key")
    .build()
// SDK will automatically failover to secondary key on 401 errors
```

## All Configuration Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `apiKey` | String | Required | API key for authentication |
| `secondaryApiKey` | String? | null | Secondary key for rotation |
| `pollingInterval` | Duration | 30s | Polling interval |
| `cacheTtl` | Duration | 300s | Cache TTL |
| `maxCacheSize` | Int | 1000 | Maximum cache entries |
| `cacheEnabled` | Boolean | true | Enable local caching |
| `enableCacheEncryption` | Boolean | false | Enable AES-256-GCM encryption |
| `eventsEnabled` | Boolean | true | Enable event tracking |
| `eventBatchSize` | Int | 10 | Events per batch |
| `eventFlushInterval` | Duration | 30s | Interval between flushes |
| `timeout` | Duration | 10s | Request timeout |
| `retryAttempts` | Int | 3 | Number of retry attempts |
| `circuitBreakerThreshold` | Int | 5 | Failures before circuit opens |
| `circuitBreakerResetTimeout` | Duration | 30s | Time before half-open |
| `bootstrap` | Map? | null | Initial flag values |
| `bootstrapConfig` | BootstrapConfig? | null | Signed bootstrap data |
| `bootstrapVerification` | Config | enabled | Bootstrap verification settings |
| `localPort` | Int? | null | Local development port |
| `strictPiiMode` | Boolean | false | Error on PII detection |
| `enableRequestSigning` | Boolean | true | Enable request signing |
| `persistEvents` | Boolean | false | Enable event persistence |
| `eventStoragePath` | String? | temp dir | Event storage directory |
| `maxPersistedEvents` | Int | 10000 | Max persisted events |
| `persistenceFlushIntervalMs` | Long | 1000 | Persistence flush interval (ms) |
| `evaluationJitter` | Config | disabled | Timing attack protection |
| `errorSanitization` | Config | enabled | Sanitize error messages |

## Thread Safety

The SDK is designed for safe concurrent use with Kotlin coroutines. All operations are coroutine-safe with proper synchronization for:

- Flag cache access
- Event queue operations
- Context management
- Polling state

## License

MIT License - see [LICENSE](LICENSE) for details.
