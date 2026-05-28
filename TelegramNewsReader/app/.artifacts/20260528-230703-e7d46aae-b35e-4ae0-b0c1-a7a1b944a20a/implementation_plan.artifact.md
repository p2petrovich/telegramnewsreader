# Очистка логирования и защита чувствительных данных

Задача направлена на удаление конфиденциальной информации из логов и приведение уровней логирования в соответствие с общепринятыми практиками (отделение служебных сообщений от ошибок, удаление отладочных логов в release-сборке).

## Proposed Changes

### [Network & TTS]

#### [EdgeTtsProvider.kt](file:///C:/Telegram_cloude/TelegramNewsReader/app/src/main/java/com/p2petrovich/telegramnewsreader/tts/EdgeTtsProvider.kt)

- Удаление логирования `wsUrl` (содержит токен).
- Удаление логирования `ssml` и `cfg` (содержат текст новостей и служебные данные).
- Удаление текста новостей из логов `synthesizePart` и `synthesizeToWav` (timeout).
- Удаление вывода содержимого текстовых фреймов WebSocket.

```diff
- Log.d(TAG, "synthesizePart start, text='${text.take(40)}', voice=$voice")
- Log.d(TAG, "WS URL: $wsUrl")
+ Log.d(TAG, "synthesizePart start, voice=$voice, length=${text.length}")

- Log.d(TAG, "→ config:\n$cfg")
- Log.d(TAG, "→ ssml:\n$ssml")

- Log.d(TAG, "← text frame:\n${text.take(400)}")
+ Log.d(TAG, "← text frame (path: ${extractPath(text)})")

- Log.e(TAG, "synthesizeToWav overall timeout for: ${text.take(60)}")
+ Log.e(TAG, "synthesizeToWav overall timeout (length: ${text.length})")
```

---

### [Utilities]

#### [TextProcessor.kt](file:///C:/Telegram_cloude/TelegramNewsReader/app/src/main/java/com/p2petrovich/telegramnewsreader/utils/TextProcessor.kt)

- Исправление уровней логирования: служебные сообщения (старт/результат фильтрации) переведены с `Log.e` на `Log.i`.
- Перевод логов с контентом (SPAM, DEDUP, OK) с `Log.w` на `Log.d`, чтобы они автоматически удалялись Proguard-ом в release-сборке.

```diff
- Log.e(TAG, "====== FILTER START: ${messages.size} messages ======")
+ Log.i(TAG, "====== FILTER START: ${messages.size} messages ======")

- Log.w(TAG, "SPAM [too_short]: $preview")
+ Log.d(TAG, "SPAM [too_short]: $preview")

- Log.e(TAG, "====== FILTER RESULT: ${messages.size} -> ${filtered.size} ======")
+ Log.i(TAG, "====== FILTER RESULT: ${messages.size} -> ${filtered.size} ======")
```

---

### [Build & Obfuscation]

#### [proguard-rules.pro](file:///C:/Telegram_cloude/TelegramNewsReader/app/proguard-rules.pro)

- Проверка и подтверждение правила для удаления `Log.d` и `Log.v`.
- Добавление `if (BuildConfig.DEBUG)` не требуется, так как `assumenosideeffects` эффективно удаляет вызовы `Log.d`.

## Verification Plan

### Automated Tests
- Запуск сборки проекта для проверки отсутствия синтаксических ошибок:
  `./gradlew :app:assembleDebug`
- Запуск lint для проверки правил логирования (если есть специфичные правила):
  `./gradlew :app:lintDebug`

### Manual Verification
- Проверка логов в Logcat при работе приложения:
  1. В Debug-сборке логи `TextProcessor` (SPAM, OK) и `EdgeTtsProvider` должны быть видны, но без токенов/полного текста SSML.
  2. (Теоретически) В Release-сборке логи `Log.d` должны отсутствовать.
