# Fix Hardcoded Edge TTS Version

This plan addresses the issue where a hardcoded Chromium version in Edge TTS requests causes 403 Forbidden errors when Microsoft raises the minimum version requirement.

## Proposed Changes

### Configuration Component

#### [NEW] [EdgeConfig.kt](file:///C:/Telegram_cloude/TelegramNewsReader/app/src/main/java/com/p2petrovich/telegramnewsreader/utils/EdgeConfig.kt)

- Implements dynamic Chromium version fetching from Google's version history API.
- Caches the version in `SharedPreferences` for 24 hours.
- Provides `fullVersion(context)` and `majorVersion(context)` for TTS providers.
- Includes `invalidate(context)` to force a refresh on 403 errors.

### TTS Component

#### [EdgeTtsProvider.kt](file:///C:/Telegram_cloude/TelegramNewsReader/app/src/main/java/com/p2petrovich/telegramnewsreader/tts/EdgeTtsProvider.kt)

- Accept `Context` in constructor to access `EdgeConfig`.
- Use `EdgeConfig` to populate `Sec-MS-GEC-Version` and `User-Agent` headers.
- Update `onFailure` to detect 403 errors and trigger `EdgeConfig.invalidate()`.

#### [TTSManager.kt](file:///C:/Telegram_cloude/TelegramNewsReader/app/src/main/java/com/p2petrovich/telegramnewsreader/tts/TTSManager.kt)

- Update `refreshEdgeProvider()` to pass `context` to the `EdgeTtsProvider` constructor.

### Service Component

#### [NewsService.kt](file:///C:/Telegram_cloude/TelegramNewsReader/app/src/main/java/com/p2petrovich/telegramnewsreader/services/NewsService.kt)

- In `collectAndPrepareMessages`, call `EdgeConfig.refreshIfNeeded(context)` before starting synthesis if Edge TTS is enabled.

## Verification Plan

### Automated Tests
- `gradle_build` to verify compilation.

### Manual Verification
- Code review to ensure:
    - Version refresh logic doesn't block the main thread (uses `Dispatchers.IO`).
    - 403 error handling correctly triggers an invalidation for the next run.
    - Default values from `ApiConfig` are used as fallback if API fetch fails.
