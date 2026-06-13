# Telegram News Reader 🎧📱

[🇷🇺 Русская версия](README.md) | 🇬🇧 English

**Telegram News Reader** is an Android app that turns your Telegram news feed into a personal audio digest. Using AI, it filters out noise, summarizes articles, and reads them aloud — so you can stay informed without stopping what you're doing.

> 🔗 [Boosty (Support the developer)](https://boosty.to/telegramnewsreader)

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
- **Intelligent Filtering** — Automatically removes ads, links, promo blocks, and filler phrases (including channel-specific filters for RBC, Baza, etc.).
- **AI Editor** — Three processing modes: from simple cleanup to ultra-short "flash" summaries. Supports **Groq** and **OpenRouter** models.
- **Advanced Deduplication** — Uses anchor fingerprints (numbers, proper nouns, abbreviations) to eliminate duplicate stories across multiple channels.
- **Audio Playlist** — Background player with notification controls, lock screen support, and Bluetooth media buttons.
- **Flexible Playback Order** — 4 modes: by channel or chronologically, newest or oldest first.
- **Edge TTS** — Microsoft Azure neural voices for natural-sounding speech.
- **Presets** — Create themed channel groups for quick one-tap launch.
- **MTProto Proxy** — Built-in client with proxy list, auto-switching, and ping monitoring.
- **Themes** — Classic purple, modern teal, and light themes.
- **Backup System** — Export all settings, presets, and channel lists to JSON with easy date-based restore.

---

## ⚙️ Technologies & Algorithms

### 1. Deduplication (`TextProcessor.kt`, `Deduplicator.kt`)
A hybrid algorithm for detecting duplicate news:
- **Anchor Fingerprints** — Extracts unique entities: numbers (amounts, dates, percentages), abbreviations, and proper nouns. If two news items share ≥2 strong anchors, they are considered duplicates.
- **Jaccard Similarity Index** — Fallback comparison using sets of significant words.
- **History Buffer** — Stores fingerprints of the last 500 articles to filter repeats within a configurable window (default: 60 min).

### 2. Speech Preparation (`TextProcessor.kt`)
Deep text transformation before TTS synthesis:
- **Number Normalization** — "10 bln RUB" → "ten billion rubles", "$50" → "fifty dollars".
- **Dates & Times** — Converts dates to genitive case for natural speech; handles ranges ("27–29" → "from 27 to 29").
- **Abbreviations** — Expands units of measurement and common news acronyms.
- **Intonation Markup** — Replaces special characters (‼‼‼ → "Breaking...") and emphasizes key words ("Urgent", "Flash") for better delivery.
- **Noise Removal** — Strips URLs, hashtags, mentions, editorial notes, and channel-specific footers.

### 3. AI Processing (`AiProcessor.kt`)
- **Parallelism** — Processes up to 3 articles simultaneously using a semaphore system.
- **Adaptive Prompts** — Auto-detects article language (RU/EN) and selects the matching prompt.
- **Reliability** — Retry logic for rate limit errors (HTTP 429) with exponential backoff.
- **Quality Control** — Removes AI error prefixes before passing text to TTS.

---

## 🏗️ System Architecture

- **Core** — Built on **TDLib** (Telegram Database Library) for fast, stable access to the Telegram API.
- **Storage**
  - **Room DB** — Local storage for channels and metadata.
  - **EncryptedSharedPreferences** — Secure storage for API keys and database encryption keys.
- **Services**
  - `NewsService` — Orchestrates the processing pipeline: fetch → filter → deduplicate → AI → TTS.
  - `AudioPlayerService` — Foreground service for continuous playback and media session management.
- **Update System** — `UpdateChecker` monitors versions via GitHub Releases and supports a kill-switch for forced updates.

---

## 🤖 AI Processing

| Mode | Description | Temperature |
|------|-------------|-------------|
| **Minimal** | Removes noise (links, tags, ads) while preserving all facts verbatim. | 0.1 |
| **Balanced** | Classic summarization. Compresses text ~2×, keeping the key points. | 0.1 |
| **Extreme** | "Flash" mode. Keeps only the single most important sentence. | 0.15 |

> **Security**: Groq and OpenRouter API keys are stored encrypted via Android Keystore. Only the article text is sent — no user metadata.

---

## 🗣️ Text-to-Speech (TTS)

### Engines
1. **Android System TTS** — Works with any installed voice (Google, Yandex, etc.). Fully offline.
2. **Edge TTS** — High-quality Microsoft neural voices with configurable speed and pitch.

### Optimizations
- WAV fragment pre-generation for seamless transitions between articles.
- Audio caching to reduce data usage and enable instant replay.

---

## 🛡️ Privacy & Security

- **Database Encryption** — The TDLib key is generated on first launch and stored in **Android Keystore**. If the Keystore is compromised, the database is automatically wiped.
- **No Tracking** — Zero third-party analytics, trackers, or crash-reporting SDKs.
- **Secure Backups** — Backups are saved as JSON. Sensitive values (keys) should be stored separately.

See the full [Privacy Policy](PRIVACY.md).

---

## 🛠️ Build & Development

Create a `local.properties` file and add your keys:

```properties
telegram.api.id=YOUR_ID
telegram.api.hash=YOUR_HASH
openrouter.api.key=sk-or-v1-...
groq.api.key=gsk_...
```

**Key modules:**
- `utils/TextProcessor.kt` — Core text cleaning and transformation logic.
- `utils/SecurityManager.kt` — Encryption key management and Keystore integration.
- `telegram/TelegramClient.kt` — TDLib wrapper and proxy support.
- `utils/SettingsBackup.kt` — Backup and restore logic.

---

Built with ❤️ for people who value their time.  
**Support the project:** [Boosty](https://boosty.to/telegramnewsreader)
