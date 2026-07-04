# Telegram News Reader 🎧📱

🇬🇧 English | [🇷🇺 Русский](README.md)

**Telegram News Reader** is an innovative Android app that turns your Telegram news feed into a personal audio digest. Using AI, it filters out information noise, summarizes news, and reads them aloud — so you can stay informed without stopping what you're doing.

---

## 📑 Table of Contents
1. [🌟 Key Features](#-key-features)
2. [⚙️ Technologies & Algorithms](#%EF%B8%8F-technologies--algorithms)
3. [🏗️ System Architecture](#%EF%B8%8F-system-architecture)
4. [🤖 AI Processing](#-ai-processing)
5. [🗣️ Text-to-Speech (TTS)](#%EF%B8%8F-text-to-speech-tts)
6. [🛡️ Privacy & Security](#%EF%B8%8F-privacy--security)
7. [🛠️ Build & Development](#%EF%B8%8F-build--development)

---

## 🌟 Key Features

- **Smart Aggregation** — Choose channels and a collection window (from 10 minutes to 24 hours).
- **Intelligent Filtering** — Automatically removes ads, links, promo blocks, and filler phrases (including channel-specific filters).
- **AI Editor** — Three processing modes: from simple cleanup to ultra-short "flash" summaries. Supports **Google Gemini**, **Groq**, and **OpenRouter** models.
- **Advanced Deduplication** — Uses anchor fingerprints (numbers, proper nouns, abbreviations) to eliminate duplicate stories across multiple channels.
- **Audio Playlist** — Background player with notification controls, lock screen support, and Bluetooth media buttons.
- **Flexible Playback Order** — 4 modes: by channel or chronologically, newest or oldest first.
- **Edge TTS** — High-quality Microsoft Azure neural voices for natural-sounding speech.
- **Presets** — Create themed channel groups for quick one-tap launch.
- **MTProto Proxy** — Built-in client with proxy list, auto-switching, and ping monitoring.
- **Themes** — Classic purple, modern teal, and light themes.
- **Full Backup System** — Export settings, presets, channel list, and AI keys to JSON. Sensitive data (keys, phone number) is encrypted with a device-specific key for security in public storage.

---

## ⚙️ Technologies & Algorithms

### 1. Deduplication (`TextProcessor.kt`, `Deduplicator.kt`)
A hybrid algorithm for detecting duplicate news:
- **Anchor Fingerprints** — Extracts unique entities: numbers (amounts, dates, percentages), abbreviations, and proper nouns.
- **Jaccard Similarity Index** — Fallback comparison using sets of significant words.
- **Persistent History** — Stores fingerprints of the last 500 articles.
- **Smart Mark-as-Read** — News are marked as "read" only when playback actually starts, ensuring that unplayed news remain available in future collections.

### 2. Collection Stability & Telegram
- **Forced Synchronization** — Uses `OpenChat` and controlled sync delays to ensure the most up-to-date data is fetched from Telegram servers.
- **Reliable Pagination** — A recursive history loading algorithm prevents message gaps, even when the API returns incomplete data batches.
- **Clean Playlists** — Automatically removes channel headers for channels that contain zero news after filtering and deduplication.

### 3. Speech & Audio Processing
- **Deep Text Transformation** — Number normalization, abbreviation expansion, and Genitive case date conversion for natural speech.
- **Native WAV Processing** — FFmpeg removed in favor of pure Kotlin and MediaCodec, reducing APK size by 73 MB and speeding up audio operations.
- **PcmResampler** — Custom audio normalization (24kHz / 16-bit / Mono) for seamless stitching of mixed TTS sources.

### 3. AI Processing (`AiProcessor.kt`)
- **Parallelism** — Processes up to 10 articles simultaneously (for Groq) using a semaphore system, significantly speeding up the aggregation process.
- **Adaptive Prompts** — Auto-detects language (RU/EN) and selects the matching prompt.
- **Reliability** — Retry logic for rate limit errors (HTTP 429) with exponential backoff.

---

## 🏗️ System Architecture

- **Pattern** — Modern **MVVM** (Model-View-ViewModel) architecture ensures the news collection process survives screen rotations and configuration changes.
- **Core** — Built on **TDLib** (Telegram Database Library) for direct, stable access to the Telegram API.
- **Storage**:
    - **Room DB** — Local storage for channels and metadata.
    - **EncryptedSharedPreferences** — Secure storage for API keys via Android Keystore.
- **UI Controllers** — Dedicated managers for presets and deduplication (`PresetController`, `DeduplicationController`) to simplify code maintenance.

---

## 🤖 AI Processing

| Mode | Description | Temperature |
|------|-------------|-------------|
| **Minimal** | Removes noise (links, tags, ads) while preserving all facts verbatim. | 0.1 |
| **Balanced** | Classic summarization. Compresses text ~2×, keeping the key points. | 0.1 |
| **Extreme** | "Flash" mode. Keeps only the single most important sentence. | 0.15 |

---

## 🗣️ Text-to-Speech (TTS)

### Engines
1. **Android System TTS** — Works with any installed voice. Offline support.
2. **Edge TTS** — Microsoft neural voices with configurable speed and pitch.

### Optimizations
- Audio fragment caching to reduce data usage and enable instant replay.
- Real-time synthesis progress feedback in the UI.

---

## 🛡️ Privacy & Security

- **Encryption** — TDLib database is encrypted with a 256-bit key stored in the system Keystore.
- **Privacy First** — No analytics, no trackers, no third-party logging systems.
- **Secure Backups** — Secrets in the JSON file are encrypted via Android Keystore. This allows safe storage of backups in the Downloads folder.

---

## 🛠️ Build & Development

1. Create a `local.properties` file:
```properties
telegram.api.id=YOUR_ID
telegram.api.hash=YOUR_HASH
```
2. **Logging**: Use the centralized system in `DebugConfig.kt` for debugging. Details in [LOGGING_GUIDE.md](LOGGING_GUIDE.md).

---

Built with ❤️ for people who value their time.  
**Support the project:** [Boosty](https://boosty.to/telegramnewsreader)
