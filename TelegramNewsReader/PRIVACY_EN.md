🇬🇧 English | [🇷🇺 Русский](PRIVACY.md)

# Privacy Policy

**Telegram News Reader** (the "App") is designed for personal use and prioritizes the security and privacy of your data.

## 1. Data Collection and Transmission

The App operates primarily locally but interacts with external services to provide its core functionality:

- **Telegram Data**: The App connects directly to Telegram servers using the official **TDLib** library. Your credentials (phone number, code) are transmitted only to Telegram servers. News content is downloaded directly into an encrypted local database on your device.

- **AI Summarization**: When AI features are enabled, the news text (without author or channel metadata) is sent to your chosen provider (**OpenRouter** or **Groq**).
  - Data sent: Article text only (limited to 8000 characters), selected model, and style.
  - **Security**: Your API keys are stored in the secure Android Keystore.

- **Speech Synthesis (TTS)**:
    - *System Android TTS*: Processing happens entirely on the device.
    - *Edge TTS*: Text is sent to **Microsoft** servers via WebSocket to generate high-quality audio.

- **Proxy**: If using an MTProto proxy, traffic data passes through the server of your choice.

## 2. Local Data Processing and Storage

- **Encryption**: The TDLib database is encrypted with a 256-bit key. Access to encryption and AI API keys is protected by the Android system Keystore, preventing theft even with root access.
- **Local Algorithms**: Deduplication, ad filtering, and text cleaning logic run **entirely locally** on your device.
- **Backups**: Backup data is saved as a JSON file in your `Downloads` folder.
    - **SECURITY**: Sensitive data (phone number and AI API keys) is encrypted using **Android Keystore**. This ensures the file can be safely stored in public folders.
    - **LIMITATION**: Encrypted data can only be restored on the **same device**. When transferring a backup to a different phone, non-sensitive settings (channels, presets) will be restored, but API keys must be re-entered manually.

## 3. Analytics and Advertising

- The App contains **no** advertising modules, third-party trackers (Google Analytics, Firebase, etc.), or automated crash-reporting systems.
- We do not collect information about your news preferences, channels, or listening history.

## 4. Permissions

- `INTERNET`: For fetching news, AI processing, and Edge TTS.
- `FOREGROUND_SERVICE`: To ensure the player and aggregator continue working in the background.
- `POST_NOTIFICATIONS`: For player controls in the notification area (Android 13+).

## 5. Contacts

This policy is valid for version 3.0 and above. For privacy concerns, you may contact the developer through the project's GitHub repository.
