# Fix Privacy Leaks in Logs

This plan addresses the issue where sensitive news content is leaked into system logs (logcat) in production builds, which violates user privacy and can be accessed by other apps or through bug reports.

## Proposed Changes

### Logging Utility

#### [NEW] [Logx.kt](file:///C:/Telegram_cloude/TelegramNewsReader/app/src/main/java/com/p2petrovich/telegramnewsreader/utils/Logx.kt)

- Implements a gated logger that only executes debug and verbose log string construction if `BuildConfig.DEBUG` is true.
- Provides standard `w` and `e` methods for non-sensitive warnings and errors.

### Security and Obfuscation

#### [proguard-rules.pro](file:///C:/Telegram_cloude/TelegramNewsReader/app/proguard-rules.pro)

- Add stricter rules to remove `android.util.Log` calls (`d`, `v`, `i`) in release builds.
- Ensure `isLoggable` is also handled.

### Content Privacy Cleanup

#### [TextProcessor.kt](file:///C:/Telegram_cloude/TelegramNewsReader/app/src/main/java/com/p2petrovich/telegramnewsreader/utils/TextProcessor.kt)

- Replace all `Log.d` calls that include message previews (e.g., `SPAM [too_short]: $preview`) with metrics-only logs (e.g., `drop[too_short] len=${trimmed.length}`).
- Use `Logx.d` for these calls to ensure they are stripped from release.

#### [Deduplicator.kt](file:///C:/Telegram_cloude/TelegramNewsReader/app/src/main/java/com/p2petrovich/telegramnewsreader/utils/Deduplicator.kt)

- Replace `android.util.Log.d` calls containing message text with metrics-only logs.
- Use `Logx.d` for gated logging.

## Verification Plan

### Automated Tests
- `gradle_build` to verify compilation.

### Manual Verification
- Code review to ensure:
    - No news content strings are passed to any logging methods.
    - `Logx.d` is used for all debug-level logs.
    - The ProGuard rules match the recommendations to strip `Log.i` as well, as it can also leak data.
