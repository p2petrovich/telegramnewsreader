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
        timeHours: Int
    ): File? = withContext(Dispatchers.IO) {
        try {
            val allMessages = mutableListOf<String>()

            val currentTimeSeconds = System.currentTimeMillis() / 1000
            val fromDate = currentTimeSeconds - timeHours * 3600
            Log.d(TAG, "collectAndProcessNews: fromDate = $fromDate ($timeHours ч назад)")

            for (channel in channels) {
                try {
                    Log.d(TAG, "▶ Обрабатываем канал: ${channel.title} (ID: ${channel.id})")

                    val messages = telegramClient.getChannelMessagesSuspend(channel.id, fromDate)
                    Log.d(TAG, "▶ Из канала ${channel.title} получено сообщений: ${messages.size}")
                    Log.d(TAG, "▶ Примеры: ${messages.take(3)}")

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

        val filtered = messages
            .filter { message ->
                message.length > 10 &&
                        !message.matches(Regex("^https?://.*$")) &&
                        !message.matches(Regex("^[\\p{So}\\s]+$"))
            }
            .map { message ->
                message.replace(Regex("\\n{3,}"), "\n\n")
                    .replace(Regex("https?://\\S+"), "")
                    .replace(Regex("#\\S+"), "")
                    .replace(Regex("@\\S+"), "")
                    .trim()
            }
            .filter { it.isNotBlank() }
            .distinct()
            .take(50)

        Log.d(TAG, "🧪 prepareMessages: после фильтрации ${filtered.size} сообщений")
        return filtered
    }
}
