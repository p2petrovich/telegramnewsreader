# Исправление проблемы с отображением дубликатов при отключенной фильтрации

Проблема заключается в том, что "кросс-канальная дедупликация" (удаление одинаковых новостей из разных каналов в текущей подборке) выполняется всегда, независимо от настроек пользователя. Кроме того, статистика в UI некорректно объединяет фильтрацию спама и дедупликацию, помечая всё как "дубли".

## Предложенные изменения

### [NewsService](file:///C:/Telegram_cloude/TelegramNewsReader/app/src/main/java/com/p2petrovich/telegramnewsreader/services/NewsService.kt)

- Добавить проверку флага `PreferenceManager.isDedupEnabled(context)` перед выполнением кросс-канальной дедупликации.
- Исправить вызовы колбэков прогресса, чтобы они передавали корректные промежуточные значения счетчиков для разделения статистики на "спам" и "дубли".

```kotlin
// Было:
progressCallback.onDeduplicationComplete(totalCollected, dedupNewsCount)
progressCallback.onMessageFiltered(filteredNewsCount, dedupNewsCount)

// Станет:
progressCallback.onMessageFiltered(totalCollected, filteredNewsCount)
progressCallback.onDeduplicationComplete(filteredNewsCount, dedupNewsCount)
```

### [MainActivity](file:///C:/Telegram_cloude/TelegramNewsReader/app/src/main/java/com/p2petrovich/telegramnewsreader/activities/MainActivity.kt)

- Обновить метод `updatePipelineStatus()`, чтобы статистика отображалась в логическом порядке: сначала потери от фильтрации (спам/короткие), затем потери от дедупликации.
- Исправить расчет `baseForTrash`, чтобы он корректно выбирал базовое число новостей перед этапом очистки от "мусора".

---

## План верификации

### Ручная верификация
1. Отключить "Фильтрацию дублей" в настройках приложения.
2. Собрать новости из нескольких каналов, где могут быть похожие сообщения (например, новостные агрегаторы).
3. Убедиться, что в строке статуса НЕ появляется надпись "дубли: -X" (если они были удалены кросс-канально).
4. Проверить, что при включенной фильтрации статистика "спам" и "дубли" отображается раздельно и корректно.
5. Проверить логи (Stage 2 и Stage 3), чтобы подтвердить, что этап дедупликации пропускается при отключении настройки.
