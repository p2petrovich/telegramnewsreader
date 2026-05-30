# Fix Fragile WAV Parsing and Concatenation

This plan addresses reliability issues when reading WAV file metadata and concatenating multiple audio segments.

## Proposed Changes

### TTS Component

#### [TTSManager.kt](file:///C:/Telegram_cloude/TelegramNewsReader/app/src/main/java/com/p2petrovich/telegramnewsreader/tts/TTSManager.kt)

- Refactor `readWavMeta()`:
    - Replace fixed-offset parsing with a chunk-based iterator.
    - Correctly find `fmt ` and `data` chunks, regardless of their position or the presence of other metadata chunks (LIST, INFO, etc.).
    - Account for 2-byte chunk alignment.

### Audio Utilities Component

#### [AudioUtils.kt](file:///C:/Telegram_cloude/TelegramNewsReader/app/src/main/java/com/p2petrovich/telegramnewsreader/utils/AudioUtils.kt)

- Refactor `concatWavFiles()`:
    - Replace `-c copy` with explicit re-encoding to `pcm_s16le`.
    - Set consistent sample rate (`-ar 24000`) and channels (`-ac 1`).
    - This ensures that output files are valid even if input segments have slightly different header structures or metadata.

## Verification Plan

### Automated Tests
- `gradle_build` to verify compilation.

### Manual Verification
- Code review to ensure:
    - Chunk iterator correctly handles large `sz` values and alignment.
    - FFmpeg command arguments are correct for re-encoding.
    - Resource closing (RandomAccessFile) is preserved.
