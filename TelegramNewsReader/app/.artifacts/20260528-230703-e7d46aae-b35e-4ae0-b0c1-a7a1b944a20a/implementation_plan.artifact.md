# Оптимизация разрешений хранилища

Задача направлена на удаление избыточных и опасных разрешений хранилища, которые не требуются для текущей функциональности приложения. Приложение использует `MediaStore` для работы с папкой Downloads на Android 10+ и внутреннее хранилище (`cacheDir`, `filesDir`) для временных файлов, что позволяет отказаться от «всемогущего» разрешения `MANAGE_EXTERNAL_STORAGE`.

## Proposed Changes

### [Manifest]

#### [AndroidManifest.xml](file:///C:/Telegram_cloude/TelegramNewsReader/app/src/main/AndroidManifest.xml)

- Удаление `android.permission.MANAGE_EXTERNAL_STORAGE`.
- Удаление `android:requestLegacyExternalStorage="true"` (приложение уже корректно работает через `MediaStore`).
- Опционально: Оставление `READ_EXTERNAL_STORAGE` и `WRITE_EXTERNAL_STORAGE` с `maxSdkVersion="32"` для поддержки импорта/экспорта бэкапов на старых устройствах (API < 33).

```diff
- <uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />

  <application
      ...
-     android:requestLegacyExternalStorage="true"
      ...
  >
```

---

### [Logic Check]

#### [SettingsBackup.kt](file:///C:/Telegram_cloude/TelegramNewsReader/app/src/main/java/com/p2petrovich/telegramnewsreader/utils/SettingsBackup.kt)

- Код в `SettingsBackup` уже разделен на ветки `Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q` (использование `MediaStore`) и `legacy` (прямой доступ к файлам).
- На Android 10 (Q) и выше для записи в `Downloads` через `MediaStore` разрешения не требуются.
- На Android 13+ (API 33) разрешения `READ/WRITE_EXTERNAL_STORAGE` игнорируются системой для файлов общего назначения, но в манифесте они ограничены `maxSdkVersion="32"`, что корректно.

## Verification Plan

### Automated Tests
- Сборка проекта:
  `./gradlew :app:assembleDebug`

### Manual Verification
1. **Проверка TTS**: Запустить прослушивание новостей (убедиться, что временные WAV файлы создаются и читаются без ошибок доступа, так как они в `cacheDir`).
2. **Проверка Бэкапа (Export)**: Создать бэкап настроек. Проверить, что файл появляется в папке `Downloads` (на Android 10+ это должно работать без `MANAGE_EXTERNAL_STORAGE`).
3. **Проверка Бэкапа (Import)**: Попробовать импортировать бэкап из списка доступных.
