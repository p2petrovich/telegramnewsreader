# Fix Moderate and Stylistic Issues

This plan addresses a collection of secondary issues that impact content quality, player reliability, and overall code robustness.

## Proposed Changes

### Content Quality (Deduplication)

#### [Deduplicator.kt](file:///C:/Telegram_cloude/TelegramNewsReader/app/src/main/java/com/p2petrovich/telegramnewsreader/utils/Deduplicator.kt)

- Lower default `matchThreshold` to `0.7f` for better consistency with across-channel dedup.
- Update `normalize`:
    - Reduce minimum word length for fingerprinting from 4 to 2 (to catch short Russian words).
    - Ensure it doesn't drop too much content from "lightning" (short) news.

#### [TextProcessor.kt](file:///C:/Telegram_cloude/TelegramNewsReader/app/src/main/java/com/p2petrovich/telegramnewsreader/utils/TextProcessor.kt)

- Update `deduplicateAcrossChannels` to use `0.7` threshold (consistent with `Deduplicator`).
- Refine `TTS_AD_PATTERN` and `TTS_PROMO_PATTERN`: Use more specific regex to avoid cutting news about "акции протеста" (protest actions) or "скидка ставки" (rate cut).

### Cache Reliability

#### [NewsCache.kt](file:///C:/Telegram_cloude/TelegramNewsReader/app/src/main/java/com/p2petrovich/telegramnewsreader/utils/NewsCache.kt) (Implicit)

- Update `messageHash` to use MD5 or SHA-1 for a more robust 128/160-bit key.
- Explicitly prepend the engine name (e.g., `edge:`, `android:`) to the hash input.

### Player Reliability

#### [AudioPlayerService.kt](file:///C:/Telegram_cloude/TelegramNewsReader/app/src/main/java/com/p2petrovich/telegramnewsreader/services/AudioPlayerService.kt)

- Change return value of `onStartCommand` to `START_STICKY` to help the system restart the player if it's killed under memory pressure.
- Integrate with `PreferenceManager`:
    - On `ACTION_SET_PLAYLIST`, save paths and index to `SharedPreferences`.
    - In `onCreate`, check if there's a saved state to restore (allowing playback to resume after service restart).

## Verification Plan

### Automated Tests
- `gradle_build` to verify compilation.

### Manual Verification
- Code review to ensure:
    - Thresholds are identical across both deduplication implementations.
    - `messageHash` changes don't cause collisions or invalid cache lookups.
    - `AudioPlayerService` correctly handles state restoration logic.
    - Regex changes for ads/promo are safe and don't introduce new false positives.
