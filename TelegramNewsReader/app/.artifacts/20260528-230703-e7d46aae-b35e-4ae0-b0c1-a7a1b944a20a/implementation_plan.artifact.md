# Исключение зачитывания AI-ошибок вслух

Задача направлена на улучшение пользовательского опыта (UX) путём удаления технических меток ошибок ИИ (например, `[AI Error 429]`) из текста перед его отправкой в движок синтеза речи (TTS).

## Proposed Changes

### [Utilities]

#### [AiProcessor.kt](file:///C:/Telegram_cloude/TelegramNewsReader/app/src/main/java/com/p2petrovich/telegramnewsreader/utils/AiProcessor.kt)

- Добавление константы `ERROR_PREFIX = "[AI "` для унификации поиска ошибок.
- Реализация вспомогательного метода `stripErrorPrefix(text: String): String`, который удаляет технические пометки вида `[AI Error ...]` или `[AI Empty Response]`.

```kotlin
fun stripErrorPrefix(text: String): String {
    if (text.startsWith("[AI ")) {
        // Находим закрывающую скобку и возвращаем текст после неё
        val closingBracketIndex = text.indexOf(']')
        if (closingBracketIndex != -1) {
            return text.substring(closingBracketIndex + 1).trim()
        }
    }
    return text
}
```

---

### [Services]

#### [NewsService.kt](file:///C:/Telegram_cloude/TelegramNewsReader/app/src/main/java/com/p2petrovich/telegramnewsreader/services/NewsService.kt)

- Обновление логики обработки сообщений в `collectAndPrepareMessages`: вызов `AiProcessor.stripErrorPrefix(summarized)` сразу после получения ответа от ИИ.
- Это гарантирует, что даже если ИИ вернул ошибку, пользователь услышит оригинальный текст новости (который приложен после метки), но без технических подробностей.

```diff
- val summarized = AiProcessor.summarizeNews(msg, context)
+ val rawResult = AiProcessor.summarizeNews(msg, context)
+ val summarized = AiProcessor.stripErrorPrefix(rawResult)
```

## Verification Plan

### Automated Tests
- Сборка проекта:
  `./gradlew :app:assembleDebug`

### Manual Verification
1. **Симуляция ошибки ИИ**:
   - Временно изменить `AiProcessor.kt`, чтобы он всегда возвращал `"[AI Error 429] Тестовая новость"`.
2. **Проверка TTS**:
   - Запустить сбор новостей с включенным ИИ.
   - Убедиться, что в логах `NewsService` или через TTS слышно только "Тестовая новость", а техническая приставка отброшена.
3. **Проверка превью**:
   - Убедиться, что в главном окне приложения в превью новостей также отсутствует техническая метка (так как `NewsService` обновляет превью из `finalMessages`).
