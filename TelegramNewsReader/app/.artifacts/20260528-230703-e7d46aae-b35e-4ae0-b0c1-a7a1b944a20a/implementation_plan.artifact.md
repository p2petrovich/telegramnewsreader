# Улучшение стабильности и конфигурации Edge TTS

Задача направлена на повышение отказоустойчивости Edge TTS через прозрачный fallback на системный Android TTS и вынос хрупких настроек (токены, версии) в централизованный конфиг.

## Proposed Changes

### [Configuration]

#### [ApiConfig.kt](file:///C:/Telegram_cloude/TelegramNewsReader/app/src/main/java/com/p2petrovich/telegramnewsreader/ApiConfig.kt)

- Добавление констант для Edge TTS: `EDGE_TOKEN`, `EDGE_WS_BASE`, `EDGE_CHROMIUM_VERSION`.
- Добавление комментариев о необходимости мониторинга этих значений.

---

### [TTS Provider]

#### [EdgeTtsProvider.kt](file:///C:/Telegram_cloude/TelegramNewsReader/app/src/main/java/com/p2petrovich/telegramnewsreader/tts/EdgeTtsProvider.kt)

- Использование констант из `ApiConfig`.
- Удаление захардкоженных значений.

---

### [TTS Management]

#### [TTSManager.kt](file:///C:/Telegram_cloude/TelegramNewsReader/app/src/main/java/com/p2petrovich/telegramnewsreader/tts/TTSManager.kt)

- Добавление `ACTION_TTS_ERROR` и `EXTRA_ERROR_MESSAGE` для уведомления UI о сбоях Edge TTS.
- При сбое `trySynthesizeEdge` отправлять Broadcast-сообщение.
- Уточнение логики Fallback: если Edge не сработал, системный TTS вызывается с явным логированием и уведомлением.

---

### [User Interface]

#### [MainActivity.kt](file:///C:/Telegram_cloude/TelegramNewsReader/app/src/main/java/com/p2petrovich/telegramnewsreader/activities/MainActivity.kt)

- Добавление `ttsErrorReceiver` для прослушивания сообщений о сбоях Edge TTS.
- Отображение `Toast` или обновление `tvStatus` при получении ошибки Edge, чтобы пользователь понимал, почему изменился голос.

## Verification Plan

### Automated Tests
- Сборка проекта:
  `./gradlew :app:assembleDebug`

### Manual Verification
1. **Симуляция сбоя Edge**: Временно изменить `EDGE_WS_BASE` на неверный URL.
2. **Проверка уведомления**: Запустить сбор новостей с выбранным Edge TTS.
3. **Ожидаемый результат**:
   - Приложение должно выдать уведомление (Toast) "Edge TTS временно недоступен, используется системный голос".
   - Сбор новостей должен завершиться успешно (используя системный TTS).
   - В логах должно быть четко видно сообщение о fallback.
