# Fix TDLib Encryption Key Loss

This plan addresses the risk of "bricking" the app if the Android Keystore master key is invalidated (e.g., due to screen lock change or device migration).

## Proposed Changes

### Security Component

#### [SecurityManager.kt](file:///C:/Telegram_cloude/TelegramNewsReader/app/src/main/java/com/p2petrovich/telegramnewsreader/utils/SecurityManager.kt)

- Introduce `KeyResult` sealed class to distinguish between successful key retrieval, unrecoverable key loss (requiring wipe), and general unavailability.
- Use a plain `SharedPreferences` marker (`db_key_was_created`) to detect if a key existed before the Keystore failure.
- Implement `getDatabaseEncryptionKeyChecked` with robust error handling for `EncryptedSharedPreferences`.
- Use `commit()` instead of `apply()` for critical security state persistence.

### Telegram Component

#### [TelegramClient.kt](file:///C:/Telegram_cloude/TelegramNewsReader/app/src/main/java/com/p2petrovich/telegramnewsreader/telegram/TelegramClient.kt)

- Update `setTdlibParameters` to handle the new `KeyResult`.
- Implement a "wipe and recovery" logic: if the key is lost, delete TDLib database and files directories, notify the user, and regenerate a new key for a fresh start.

```kotlin
    private fun setTdlibParameters() {
        val keyResult = SecurityManager.getDatabaseEncryptionKeyChecked(context)

        val encryptionKey: ByteArray = when (keyResult) {
            is SecurityManager.KeyResult.Ok -> keyResult.key
            is SecurityManager.KeyResult.LostNeedsWipe -> {
                Log.w(TAG, "DB key lost — wiping TDLib dirs and re-initializing")
                ApiConfig.tdlibDatabaseDir(context).deleteRecursively()
                ApiConfig.tdlibFilesDir(context).deleteRecursively()
                onFatalError?.invoke(
                    "Ключ шифрования базы был сброшен системой. Данные очищены — потребуется повторный вход в Telegram."
                )
                when (val retry = SecurityManager.getDatabaseEncryptionKeyChecked(context)) {
                    is SecurityManager.KeyResult.Ok -> retry.key
                    else -> { onFatalError?.invoke("Ошибка безопасности: Android Keystore недоступен."); return }
                }
            }
            is SecurityManager.KeyResult.Unavailable -> {
                onFatalError?.invoke("Ошибка безопасности: Android Keystore недоступен. Запуск невозможен.")
                return
            }
        }
        // ... send SetTdlibParameters ...
    }
```

## Verification Plan

### Automated Tests
- `gradle_build` to verify compilation.

### Manual Verification
- Code review of `SecurityManager.kt` logic to ensure all catch blocks correctly handle the "key was created before" flag.
- Verify `commit()` usage to prevent data loss during crashes.
- Verify `deleteRecursively()` usage on correct TDLib directories.
