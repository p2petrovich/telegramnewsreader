# Fix Silent News Truncation

This plan addresses the issue where news items are silently truncated at a hardcoded limit of 200, potentially losing important content without notifying the user.

## Proposed Changes

### Core Logic Component

#### [TextProcessor.kt](file:///C:/Telegram_cloude/TelegramNewsReader/app/src/main/java/com/p2petrovich/telegramnewsreader/utils/TextProcessor.kt)

- Introduce `MAX_NEWS_DEFAULT = 500`.
- Update `filterMessages` signature to include `maxNews` and `onTruncated` callback.
- Modify truncation logic:
    - Only count actual news items towards the limit (exclude channel headers).
    - Use a `droppedNews` counter and trigger `onTruncated` if any items are removed.
    - Remove the hardcoded `.take(200)`.

#### [NewsService.kt](file:///C:/Telegram_cloude/TelegramNewsReader/app/src/main/java/com/p2petrovich/telegramnewsreader/services/NewsService.kt)

- Add `onNewsTruncated(kept: Int, dropped: Int)` to the `ProgressCallback` interface.
- In `collectAndPrepareMessages`, pass the `onTruncated` signal from `TextProcessor` to the `progressCallback`.

### UI Component

#### [MainActivity.kt](file:///C:/Telegram_cloude/TelegramNewsReader/app/src/main/java/com/p2petrovich/telegramnewsreader/activities/MainActivity.kt)

- Implement `onNewsTruncated` in the `ProgressCallback` listener.
- Show a non-intrusive notification (e.g., a Toast or a UI status update) when news is truncated, suggesting the user reduce the time period or number of channels.

## Verification Plan

### Automated Tests
- `gradle_build` to verify compilation.

### Manual Verification
- Code review to ensure:
    - Channel headers are correctly identified and excluded from the count.
    - The `onTruncated` signal is correctly propagated from `TextProcessor` to `MainActivity`.
    - `MAX_NEWS_DEFAULT` is used as the default value but can be overridden.
