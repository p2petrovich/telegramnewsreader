package com.example.telegramnewsreader.service

import com.example.telegramnewsreader.model.Channel
import com.example.telegramnewsreader.telegram.TelegramClient
import com.example.telegramnewsreader.tts.TTSManager
import kotlinx.coroutines.*
import java.io.File

class NewsService(
    private val telegramClient: TelegramClient,
    private val ttsManager: TTSManager
) {

    suspend fun collectAndProcessNews(
        channels: List<Channel>,
        timeHours: Int
    ): File? = withContext(Dispatchers.IO) {
        try {
            val allMessages = mutableListOf<String>()

            val currentTimeSeconds = System.currentTimeMillis() / 1000
            val fromDate = currentTimeSeconds - timeHours * 3600

            for (channel in channels) {
                try {
                    val messages = telegramClient.getChannelMessagesSuspend(channel.id, fromDate)
                    if (messages.isNotEmpty()) {
                        allMessages.add("Новости из канала ${channel.title}:")
                        allMessages.addAll(messages)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            if (allMessages.isNotEmpty()) {
                val preparedMessages = prepareMessages(allMessages)
                return@withContext ttsManager.convertToAudio(preparedMessages)  // убедитесь, что convertToAudio возвращает File?
            } else {
                return@withContext null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    
    private fun prepareMessages(messages: List<String>): List<String> {
        return messages
            .filter { message ->
                // Фильтруем слишком короткие сообщения
                message.length > 10 &&
                // Фильтруем сообщения только из ссылок
                !message.matches(Regex("^https?://.*$")) &&
                // Фильтруем сообщения только из эмодзи
                !message.matches(Regex("^[\\p{So}\\s]+$"))
            }
            .map { message ->
                // Удаляем множественные переносы строк
                message.replace(Regex("\\n{3,}"), "\n\n")
                    // Удаляем ссылки
                    .replace(Regex("https?://\\S+"), "")
                    // Удаляем хештеги
                    .replace(Regex("#\\S+"), "")
                    // Удаляем упоминания
                    .replace(Regex("@\\S+"), "")
                    .trim()
            }
            .filter { it.isNotBlank() }
            .distinct() // Удаляем дубликаты
            .take(50) // Ограничиваем количество сообщений
    }
}
