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
    }

    suspend fun collectAndProcessNews(
        channels: List<Channel>,
        timeHours: Double
    ): File? = withContext(Dispatchers.IO) {
        try {
            val allMessages = mutableListOf<String>()

            val currentTimeSeconds = System.currentTimeMillis() / 1000
            val fromDate = currentTimeSeconds - (timeHours * 3600).toLong()
            Log.d(TAG, "collectAndProcessNews: fromDate = $fromDate ($timeHours ч назад)")

            for (channel in channels) {
                try {
                    Log.d(TAG, "▶ Обрабатываем канал: ${channel.title} (ID: ${channel.id})")

                    val messages = telegramClient.getChannelMessagesSuspend(channel.id, fromDate)
                    Log.d(TAG, "▶ Из канала ${channel.title} получено сообщений: ${messages.size}")
                    channel.newMessagesCount = messages.size
                    Log.d(TAG, "▶ Примеры: ${messages.take(3)}")

                    // ✅ сохраняем количество сообщений в модель канала
                    channel.newMessagesCount = messages.size

                    if (messages.isNotEmpty()) {
                        allMessages.add("Новости из канала ${channel.title}:")
                        allMessages.addAll(messages)
                    } else {
                        Log.d(TAG, "⚠ Канал ${channel.title} не содержит сообщений за период.")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Ошибка при обработке канала ${channel.title}", e)
                }
            }

            if (allMessages.isNotEmpty()) {
                Log.d(TAG, "💬 Всего сообщений до фильтрации: ${allMessages.size}")
                val preparedMessages = prepareMessages(allMessages)
                Log.d(TAG, "✅ После prepareMessages: ${preparedMessages.size} сообщений")
                return@withContext ttsManager.convertToAudio(preparedMessages)
            } else {
                Log.w(TAG, "⚠ Нет сообщений для обработки. Возвращаем null.")
                return@withContext null
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка в collectAndProcessNews", e)
            null
        }
    }

    private fun prepareMessages(messages: List<String>): List<String> {
        Log.d(TAG, "🧪 prepareMessages: на входе ${messages.size} сообщений")

        val promoPatterns = listOf(
            "^🔹.*",
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
            "^Видео.*"
        )

        val filtered = messages.mapNotNull { original ->
            Log.v(TAG, "📩 Сообщение до фильтрации: \"$original\"")

            if (original.length <= 3) {
                Log.v(TAG, "⛔ Отфильтровано (короткое): \"$original\"")
                return@mapNotNull null
            }

            if (original.matches(Regex("^https?://.*$"))) {
                Log.v(TAG, "⛔ Отфильтровано (ссылка целиком): \"$original\"")
                return@mapNotNull null
            }

            if (original.matches(Regex("^[\\p{So}\\s]+$"))) {
                Log.v(TAG, "⛔ Отфильтровано (только эмодзи или пробелы): \"$original\"")
                return@mapNotNull null
            }

            if (original.trim().matches(
                    Regex("^(фото|видео|аудио|документ|gif|голосовое сообщение)[\\p{P}\\s]*$",
                        RegexOption.IGNORE_CASE)
                )) {
                Log.v(TAG, "⛔ Отфильтровано (медиа-заглушка): \"$original\"")
                return@mapNotNull null
            }

            // 🧹 Удаление медиа-префиксов в начале строки (например: "Фото, Видео, ..." → "...")
            val mediaPrefixPattern = Regex("^(фото|видео|аудио|документ|gif|голосовое сообщение)[\\p{P}\\s]+", RegexOption.IGNORE_CASE)
            val withoutMediaPrefix = original.trim().replace(mediaPrefixPattern, "").trim()

            // 🔧 Очистка текста
            var cleaned = withoutMediaPrefix
                .replace(Regex("\\n{3,}"), "\n\n")
                .replace(Regex("https?://\\S+"), "")
                .replace(Regex("#\\S+"), "")
                .replace(Regex("@\\S+"), "")
                .replace(Regex("[\\p{So}&&[^\\p{L}\\p{N}]]"), "") // Удаляет большинство эмодзи
                .trim()

            promoPatterns.forEach { pattern ->
                cleaned = cleaned.replace(Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)), "")
            }

            cleaned = cleaned.trim()

            if (cleaned.isBlank()) {
                Log.v(TAG, "⛔ Отфильтровано (пусто после очистки): \"$original\"")
                return@mapNotNull null
            }

            return@mapNotNull cleaned
        }
            .distinct()
            .take(50)

        Log.d(TAG, "🧪 prepareMessages: после фильтрации ${filtered.size} сообщений")
        return filtered
    }



}
