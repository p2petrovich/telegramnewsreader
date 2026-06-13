# Privacy Policy

**Telegram News Reader** (the "App") is built for personal use and treats your data security as a top priority.

## 1. Data Collection & Transmission

The App operates primarily on-device but connects to external services to function:

- **Telegram Data** — The App connects directly to Telegram's servers via the official **TDLib** library. Your credentials (phone number, login code) are sent only to Telegram. News content is downloaded directly into an encrypted local database. The App never has access to your 2FA password — that is handled entirely by TDLib.

- **AI Summarization** — If AI is enabled, the article text (without channel metadata or author info) is sent to your chosen provider (**OpenRouter** or **Groq**). The App does not store this data on external servers, but it is subject to the privacy policy of the selected AI provider.
  - **OpenRouter**: Requests go to `https://openrouter.ai/api/v1/chat/completions` with `HTTP-Referer` and `X-Title` headers for app identification.
  - **Groq**: Requests go to `https://api.groq.com/openai/v1/chat/completions`.
  - What is sent: only the article text (capped at 8,000 characters), the selected model, and the processing style. Channel metadata, author info, and other identifiers are **never transmitted**.

- **Text-to-Speech (TTS)**
  - *Android System TTS*: Processing happens entirely on-device (or per your Google account settings).
  - *Edge TTS*: Text is sent to **Microsoft** (Azure) servers via WebSocket for audio generation in SSML format.

- **Proxy** — If you use an MTProto proxy, your traffic passes through the proxy server you configure. The App supports custom proxy lists.

## 2. Local Storage & Processing

- **Local Database** — Channel lists, metadata, and news cache are stored in Room DB and TDLib. The TDLib database is encrypted with a 256-bit key.
- **Security Keys** — Database encryption keys and AI provider API keys are stored in **Android Keystore** via `EncryptedSharedPreferences`, preventing access even with root privileges.
- **Deduplication & Filtering** — All text-cleaning, ad-removal, and deduplication algorithms (Jaccard similarity, anchor fingerprints) run **entirely on-device**.
- **Audio Cache** — Generated audio fragments are stored in the app's internal cache and automatically cleared when you clear the cache or reinstall the app.
- **Backups** — Backups are saved as `telegram_news_backup_*.json` to your Downloads folder.
  - ⚠️ **Warning**: Backup files contain settings in plain JSON, including your phone number and proxy secret (if configured). Do not share backup files with others.

## 3. Analytics & Advertising

- The App contains **no** advertising SDKs, trackers (Google Analytics, Firebase Analytics, etc.), or automated crash-reporting systems.
- We do not collect information about which channels you listen to or which AI models you use.

## 4. Permissions

| Permission | Purpose |
|---|---|
| `INTERNET` | Loading news from Telegram, AI processing, Edge TTS |
| `FOREGROUND_SERVICE` | Keeping the player and news collector running in the background |
| `READ/WRITE_EXTERNAL_STORAGE` | Backup file access (Android 9 and below) |
| `POST_NOTIFICATIONS` | Player control notification (Android 13+) |

## 5. Updates

The App checks for new versions via GitHub Releases. Only a request for the latest release info is sent — no personal data is included.

## 6. Contact

This policy applies to version 3.0 and above. For privacy-related questions, please reach out via the project's GitHub repository.
