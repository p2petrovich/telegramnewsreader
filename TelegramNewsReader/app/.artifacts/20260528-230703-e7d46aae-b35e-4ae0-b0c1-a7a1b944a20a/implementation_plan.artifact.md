# Оптимизация задержек AI-запросов

Задача направлена на ускорение процесса саммаризации путём удаления безусловных задержек (`delay(200)`) из `NewsService` и переноса логики ожидания исключительно в механизм ретраев `AiProcessor` (только при возникновении ошибки 429).

## Proposed Changes

### [Services]

#### [NewsService.kt](file:///C:/Telegram_cloude/TelegramNewsReader/app/src/main/java/com/p2petrovich/telegramnewsreader/services/NewsService.kt)

- Удаление безусловного `delay(200)` внутри блока `semaphore.withPermit`. Это позволит выполнять запросы максимально быстро, если API не ограничивает скорость.

```diff
 semaphore.withPermit {
     val rawResult = AiProcessor.summarizeNews(msg, context)
     val summarized = AiProcessor.stripErrorPrefix(rawResult)
     synchronized(this@NewsService) {
         processedCount++
         progressCallback.onUpdateProgress("Сжатие через ИИ...", processedCount, totalToSynthesizeBeforeAi)
     }
-    // Небольшая задержка между запросами для free-моделей
-    delay(200)
     summarized
 }
```

---

### [Utilities]

#### [AiProcessor.kt](file:///C:/Telegram_cloude/TelegramNewsReader/app/src/main/java/com/p2petrovich/telegramnewsreader/utils/AiProcessor.kt)

- Логика ожидания уже присутствует в цикле ретраев для ошибки 429: `delay(2000L * attempt)`.
- Дополнительных изменений в `AiProcessor` не требуется, так как задержка теперь будет возникать только тогда, когда это действительно необходимо (при получении HTTP 429).

## Verification Plan

### Automated Tests
- Сборка проекта:
  `./gradlew :app:assembleDebug`

### Manual Verification
1. **Скорость работы**: Запустить саммаризацию большого количества новостей. Убедиться, что процесс идет быстрее, так как нет принудительной паузы в 200мс после каждого сообщения.
2. **Обработка Rate Limit**: Если модель бесплатная и лимиты достигнуты, убедиться в логах, что `AiProcessor` по-прежнему корректно делает паузу (2с, 4с) после получения ошибки 429.
