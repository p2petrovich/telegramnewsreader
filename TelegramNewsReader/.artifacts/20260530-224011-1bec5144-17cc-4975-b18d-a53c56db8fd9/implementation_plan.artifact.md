# Optimize getAllChannelsNewsCount Performance

This plan addresses the performance issue where message counting across multiple channels is done sequentially and without proper timeouts, leading to long wait times and excessive resource usage.

## Proposed Changes

### Service Component

#### [NewsService.kt](file:///C:/Telegram_cloude/TelegramNewsReader/app/src/main/java/com/p2petrovich/telegramnewsreader/services/NewsService.kt)

- Refactor `getAllChannelsNewsCount` to use `coroutineScope` and `async/awaitAll` for parallel processing of channels.
- Implement per-channel timeout using `withTimeout(CHANNEL_TIMEOUT_MS)`.
- Ensure proper error handling per channel to prevent one failed request from failing the entire counting process.

```kotlin
    suspend fun getAllChannelsNewsCount(
        channels: List<Channel>,
        timeHours: Double
    ): Map<Long, Int> = withContext(Dispatchers.IO) {
        val currentTimeSeconds = System.currentTimeMillis() / 1000
        val fromDate = currentTimeSeconds - (timeHours * 3600).toLong()

        coroutineScope {
            channels.map { channel ->
                async {
                    channel.id to try {
                        withTimeout(CHANNEL_TIMEOUT_MS) {
                            telegramClient.getChannelMessagesPaginated(channel.id, fromDate).size
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "count failed for channel ${channel.id}: ${e.message}")
                        0
                    }
                }
            }.awaitAll().toMap()
        }
    }
```

## Verification Plan

### Automated Tests
- `gradle_build` to verify compilation.

### Manual Verification
- Code review to ensure:
    - Channels are processed in parallel using `async`.
    - `CHANNEL_TIMEOUT_MS` (15s) is applied to each channel request.
    - Exceptions are caught and logged, returning 0 as a safe fallback.
    - `withContext(Dispatchers.IO)` and `coroutineScope` are correctly nested.
