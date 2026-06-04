# Исправление сброса голоса (Анна) на мужской при проверке Android TTS

Проблема заключается в том, что при каждом вызове `speak` или `synthesizePartToWav` происходит автоматическое определение языка и вызов `tts?.setLanguage(...)`. В Android TTS вызов `setLanguage` сбрасывает текущий выбранный голос (`Voice`) на системный голос по умолчанию для данного языка. Если выбрана "Анна", но системным голосом по умолчанию является "Максим", то после `setLanguage` будет звучать Максим.

## Предложенные изменения

### [TTSManager](file:///C:/Telegram_cloude/TelegramNewsReader/app/src/main/java/com/p2petrovich/telegramnewsreader/tts/TTSManager.kt)

- Добавить метод `applyLanguageByText(text: String)`, который будет менять язык только если он действительно отличается от языка текущего голоса.
- Улучшить `detectLocale`, чтобы он игнорировал тексты без букв (цифры, знаки).
- Заменить прямые вызовы `setLanguage(detectLocale(text))` на вызов нового метода `applyLanguageByText(text)`.

```kotlin
    private fun applyLanguageByText(text: String) {
        val cyrillicCount = text.count { it in '\u0400'..'\u04FF' }
        val latinCount = text.count { it.isLetter() && it !in '\u0400'..'\u04FF' }

        // Если букв нет (только цифры/знаки), не меняем язык
        if (cyrillicCount == 0 && latinCount == 0) return

        val detectedLocale = if (cyrillicCount > latinCount) Locale("ru") else Locale.ENGLISH
        val currentVoice = tts?.voice

        // Если текущий голос уже имеет нужный язык — ничего не делаем,
        // чтобы не сбросить конкретный голос (например, Анна) на системный дефолт.
        if (currentVoice != null && currentVoice.locale.language == detectedLocale.language) {
            return
        }

        tts?.language = detectedLocale
    }
```

## План верификации

### Автоматические тесты
- Так как это связано с системным TTS, автоматизированное тестирование затруднено без эмулятора с настроенным TTS.

### Ручная верификация
1. Открыть настройки голоса.
2. Выбрать "Android TTS".
3. Выбрать голос "Анна" (или любой другой женский голос, если доступен).
4. Нажать кнопку прослушивания (Play) рядом с именем голоса.
5. Убедиться, что звучит именно выбранный голос, а не системный мужской голос по умолчанию.
6. Проверить на текстах с цифрами (например, "123"), что голос не сбрасывается.
7. Проверить переключение на английский, если текст содержит преимущественно латиницу.
