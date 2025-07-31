package com.example.telegramnewsreader.service

import android.util.Log
import com.example.telegramnewsreader.model.Channel
import com.example.telegramnewsreader.telegram.TelegramClient
import com.example.telegramnewsreader.tts.TTSManager
import kotlinx.coroutines.*
import java.io.File

class NewsService(
    private val telegramClient: TelegramClient,
    private val ttsManager: TTSManager
) {
    companion object {
        private const val TAG = "NewsService"
        private const val CHANNEL_TIMEOUT_MS = 15000L // 15 секунд на канал
        private const val TOTAL_TIMEOUT_MS = 120000L   // 2 минута общий таймаут
    }

    suspend fun collectAndProcessNews(
        channels: List<Channel>,
        timeHours: Double
    ): File? = withContext(Dispatchers.IO) {

        // ✅ Валидация входных данных
        if (channels.isEmpty()) {
            Log.w(TAG, "❌ Пустой список каналов")
            return@withContext null
        }

        if (timeHours <= 0) {
            Log.w(TAG, "❌ Некорректный период времени: $timeHours")
            return@withContext null
        }

        try {
            // ✅ Общий таймаут для всей операции
            withTimeout(TOTAL_TIMEOUT_MS) {
                val allMessages = mutableListOf<String>()
                val currentTimeSeconds = System.currentTimeMillis() / 1000
                val fromDate = currentTimeSeconds - (timeHours * 3600).toLong()

                Log.d(TAG, "🔍 Начинаем сбор новостей:")
                Log.d(TAG, "   📅 Период: $timeHours часов назад")
                Log.d(TAG, "   📺 Каналов: ${channels.size}")
                Log.d(TAG, "   ⏰ fromDate: $fromDate")

                // ✅ ИСПРАВЛЕНИЕ: Параллельная обработка каналов с индивидуальными таймаутами
                val channelResults = channels.map { channel ->
                    async {
                        processChannelWithTimeout(channel, fromDate)
                    }
                }.awaitAll()

                // ✅ Собираем результаты
                var totalMessages = 0
                channelResults.forEach { (channel, messages) ->
                    if (messages.isNotEmpty()) {
                        allMessages.add("Новости из канала ${channel.title}:")
                        allMessages.addAll(messages)
                        totalMessages += messages.size
                        Log.d(TAG, "✅ Канал '${channel.title}': ${messages.size} сообщений")
                    } else {
                        Log.d(TAG, "⚠️ Канал '${channel.title}': сообщений не найдено")
                    }
                }

                Log.d(TAG, "📊 Итого собрано сообщений: $totalMessages из ${channels.size} каналов")

                if (allMessages.isNotEmpty()) {
                    Log.d(TAG, "🔄 Фильтруем сообщения...")
                    val preparedMessages = prepareMessages(allMessages)
                    Log.d(TAG, "✅ После фильтрации: ${preparedMessages.size} сообщений")

                    if (preparedMessages.isNotEmpty()) {
                        Log.d(TAG, "🎵 Создаем аудиофайл...")
                        return@withTimeout ttsManager.convertToAudio(preparedMessages)
                    } else {
                        Log.w(TAG, "⚠️ После фильтрации не осталось сообщений")
                        return@withTimeout null
                    }
                } else {
                    Log.w(TAG, "⚠️ Нет сообщений для обработки")
                    return@withTimeout null
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "⏰ Превышен общий таймаут операции ($TOTAL_TIMEOUT_MS мс)")
            null
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка в collectAndProcessNews", e)
            null
        }
    }

    // ✅ НОВЫЙ МЕТОД: Обработка одного канала с таймаутом
    private suspend fun processChannelWithTimeout(
        channel: Channel,
        fromDate: Long
    ): Pair<Channel, List<String>> {
        return try {
            withTimeout(CHANNEL_TIMEOUT_MS) {
                Log.d(TAG, "📡 Загружаем сообщения из '${channel.title}' (ID: ${channel.id})")

                val messages = telegramClient.getChannelMessagesPaginated(channel.id, fromDate)


                // ✅ ИСПРАВЛЕНИЕ: Однократное присвоение newMessagesCount
                channel.newMessagesCount = messages.size

                Log.d(TAG, "📨 Канал '${channel.title}': получено ${messages.size} сообщений")

                if (messages.isNotEmpty()) {
                    Log.v(TAG, "   🔍 Примеры: ${messages.take(2)}")
                }

                Pair(channel, messages)
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "⏰ Таймаут для канала '${channel.title}' ($CHANNEL_TIMEOUT_MS мс)")
            channel.newMessagesCount = 0
            Pair(channel, emptyList())
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка обработки канала '${channel.title}'", e)
            channel.newMessagesCount = 0
            Pair(channel, emptyList())
        }
    }

    private fun prepareMessages(messages: List<String>): List<String> {
        Log.d(TAG, "🧪 prepareMessages: обрабатываем ${messages.size} сообщений")

        // ✅ Улучшенные паттерны фильтрации
        val promoPatterns = listOf(
            "^🔹.*",
            "^🔸.*",
            "^🔴.*",
            "^⚡.*",
            "подписаться на.*",
            "подписывайся(те)? на.*",
            "все наши каналы.*",
            "читать(ь)? больше.*",
            "t\\.me/\\S+",
            "перейти в канал.*",
            "наш tg.*",
            "^🐚.*",
            "^Фото:.*",
            "^Фото.*",
            "^Видео.*",
            "^\\[.*\\]$",  // [Фото], [Видео] и т.д.
            "^\\d{2}:\\d{2}\\s*—\\s*\\[.*\\]$"  // "12:34 — [Стикер]"
        )

        val filtered = messages.mapNotNull { original ->
            val trimmed = original.trim()

            // ✅ Базовые фильтры
            when {
                trimmed.length <= 3 -> {
                    Log.v(TAG, "⛔ Слишком короткое: \"$trimmed\"")
                    return@mapNotNull null
                }

                trimmed.matches(Regex("^https?://.*$")) -> {
                    Log.v(TAG, "⛔ Только ссылка: \"$trimmed\"")
                    return@mapNotNull null
                }

                trimmed.matches(Regex("^[\\p{So}\\p{Sk}\\s]+$")) -> {
                    Log.v(TAG, "⛔ Только эмодзи/символы: \"$trimmed\"")
                    return@mapNotNull null
                }

                trimmed.matches(Regex("^\\d{2}:\\d{2}\\s*—\\s*\\[.*\\]$")) -> {
                    Log.v(TAG, "⛔ Медиа-заглушка: \"$trimmed\"")
                    return@mapNotNull null
                }
            }

            // ✅ Проверка на промо-паттерны
            val hasPromoPattern = promoPatterns.any { pattern ->
                trimmed.matches(Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)))
            }

            if (hasPromoPattern) {
                Log.v(TAG, "⛔ Промо-контент: \"$trimmed\"")
                return@mapNotNull null
            }

            // ✅ Очистка текста
            var cleaned = trimmed
                // Удаляем медиа-префиксы
                .replace(Regex("^\\d{2}:\\d{2}\\s*—\\s*(фото|видео|аудио|документ|gif|голосовое сообщение)[\\p{P}\\s]*", RegexOption.IGNORE_CASE), "")
                // Нормализуем переносы строк
                .replace(Regex("\\n{3,}"), "\n\n")
                // Удаляем ссылки
                .replace(Regex("https?://\\S+"), "")
                // Удаляем хештеги и упоминания
                .replace(Regex("#\\S+"), "")
                .replace(Regex("@\\S+"), "")
                // Удаляем избыточные эмодзи (но оставляем основные)
                .replace(Regex("[🔸🔹🔴⚡🐚]"), "")
                .trim()

            // ✅ Финальная проверка
            if (cleaned.isBlank() || cleaned.length <= 5) {
                Log.v(TAG, "⛔ Пустое после очистки: \"$original\" -> \"$cleaned\"")
                return@mapNotNull null
            }

            // ✅ Ограничиваем длину сообщения
            val finalMessage = if (cleaned.length > 5000) {
                cleaned.take(4970) + "..."
            } else {
                cleaned
            }

            Log.v(TAG, "✅ Сообщение принято: \"${finalMessage.take(50)}...\"")
            finalMessage
        }
            .distinct() // Убираем дубликаты
            .take(100) // Ограничиваем количество

        Log.d(TAG, "🎯 prepareMessages: итого ${filtered.size} сообщений после фильтрации")

        // ✅ Логируем статистику фильтрации
        val originalCount = messages.size
        val filteredCount = filtered.size
        val filterRate = if (originalCount > 0) ((originalCount - filteredCount) * 100 / originalCount) else 0
        Log.d(TAG, "📊 Статистика фильтрации: $originalCount -> $filteredCount (отфильтровано $filterRate%)")

        return filtered
    }
}