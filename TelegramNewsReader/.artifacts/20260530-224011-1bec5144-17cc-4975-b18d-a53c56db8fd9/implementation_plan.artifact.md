# Fix Unclosed OkHttpClient Singletons

This plan addresses a resource leak issue where `OkHttpClient` instances in `EdgeTtsProvider` and `AiProcessor` are never shut down, potentially leaking file descriptors and threads.

## Proposed Changes

### Centralized HTTP Management

#### [NEW] [HttpClients.kt](file:///C:/Telegram_cloude/TelegramNewsReader/app/src/main/java/com/p2petrovich/telegramnewsreader/utils/HttpClients.kt)

- Implements an `object HttpClients` with a shared `OkHttpClient` instance.
- Default timeouts set to 30 seconds (connect, read, write) to accommodate AI and TTS needs.
- Includes a `shutdown()` method to close the executor service, connection pool, and cache.

### TTS and AI Components

#### [EdgeTtsProvider.kt](file:///C:/Telegram_cloude/TelegramNewsReader/app/src/main/java/com/p2petrovich/telegramnewsreader/tts/EdgeTtsProvider.kt)

- Replace local `sharedClient` with `HttpClients.shared`.

#### [AiProcessor.kt](file:///C:/Telegram_cloude/TelegramNewsReader/app/src/main/java/com/p2petrovich/telegramnewsreader/utils/AiProcessor.kt)

- Replace local `client` with `HttpClients.shared`.

#### [EdgeConfig.kt](file:///C:/Telegram_cloude/TelegramNewsReader/app/src/main/java/com/p2petrovich/telegramnewsreader/utils/EdgeConfig.kt)

- Replace local `http` with `HttpClients.shared`.

### Lifecycle Management

#### [TTSManager.kt](file:///C:/Telegram_cloude/TelegramNewsReader/app/src/main/java/com/p2petrovich/telegramnewsreader/tts/TTSManager.kt)

- Update `TTSManagerSingleton.clearInstance()` to call `HttpClients.shutdown()`.

## Verification Plan

### Automated Tests
- `gradle_build` to verify compilation.

### Manual Verification
- Code review to ensure:
    - `HttpClients.shared` is used in all identified places.
    - `HttpClients.shutdown()` is called exactly once when the TTS system is shut down.
    - `newBuilder()` is used if any component specifically needs different timeouts while still sharing the pool.
