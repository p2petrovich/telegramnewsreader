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
        private const val CHANNEL_TIMEOUT_MS = 15000L
        private const val TOTAL_TIMEOUT_MS = 120000L
    }


    data class Prepared(
        val preparedMessages: List<String>,
        val totalMessages: Int
    )

    data class AudioWithChapters(
        val file: File,
        val chaptersMs: List<Long>
    )

    suspend fun collectAndProcessNews(
        channels: List<Channel>,
        timeHours: Double
    ): File? = withContext(Dispatchers.IO) {
        val list = collectAndPrepareMessages(channels, timeHours) ?: return@withContext null
        if (list.preparedMessages.isEmpty()) return@withContext null
        ttsManager.convertToAudio(list.preparedMessages, pauseMs = 1200)
    }

    suspend fun collectAndSynthesizeNewsList(
        channels: List<Channel>,
        timeHours: Double
    ): List<File> = withContext(Dispatchers.IO) {
        val res = collectAndSynthesizeWithChapters(channels, timeHours)
        if (res != null) listOf(res.file) else emptyList()
    }

    suspend fun collectAndSynthesizeWithChapters(
        channels: List<Channel>,
        timeHours: Double
    ): AudioWithChapters? = withContext(Dispatchers.IO) {
        val list = collectAndPrepareMessages(channels, timeHours) ?: return@withContext null
        if (list.preparedMessages.isEmpty()) return@withContext null
        val audio = ttsManager.convertToAudioWithChapters(list.preparedMessages, pauseMs = 1200)
            ?: return@withContext null
        AudioWithChapters(audio.file, audio.chaptersMs)
    }

    private suspend fun collectAndPrepareMessages(
        channels: List<Channel>,
        timeHours: Double
    ): Prepared? = withContext(Dispatchers.IO) {
        if (channels.isEmpty() || timeHours <= 0) return@withContext null

        try {
            withTimeout(TOTAL_TIMEOUT_MS) {
                val allMessages = mutableListOf<String>()
                val currentTimeSeconds = System.currentTimeMillis() / 1000
                val fromDate = currentTimeSeconds - (timeHours * 3600).toLong()

                val channelResults = channels.map { channel ->
                    async { processChannelWithTimeout(channel, fromDate) }
                }.awaitAll()

                var totalMessages = 0
                channelResults.forEach { (channel, messages) ->
                    if (messages.isNotEmpty()) {
                        allMessages.add("Новости из канала ${channel.title}:")
                        allMessages.addAll(messages)
                        totalMessages += messages.size
                    }
                }

                if (allMessages.isEmpty()) return@withTimeout Prepared(emptyList(), 0)

                val preparedMessages = prepareMessages(allMessages)
                Prepared(preparedMessages, totalMessages)
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Timeout", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error", e)
            null
        }
    }

    private suspend fun processChannelWithTimeout(
        channel: Channel,
        fromDate: Long
    ): Pair<Channel, List<String>> {
        return try {
            withTimeout(CHANNEL_TIMEOUT_MS) {
                val messages = telegramClient.getChannelMessagesPaginated(channel.id, fromDate)
                channel.newMessagesCount = messages.size
                Pair(channel, messages)
            }
        } catch (_: Exception) {
            channel.newMessagesCount = 0
            Pair(channel, emptyList())
        }
    }

private fun prepareMessages(messages: List<String>): List<String> {
        Log.d(TAG, "🧪 prepareMessages: обрабатываем ${messages.size} сообщений")

        // RAW лог первых 10 элементов (переносы → \n)
        Log.d(TAG, "prepareMessages(): RAW start, size=${messages.size}")
        messages.take(10).forEachIndexed { i, m ->
            Log.d(TAG, "RAW[$i]: >>>${m.replace("\n", "\\n")}<<<")
        }
        Log.d(TAG, "prepareMessages(): RAW preview end")

        val promoPatterns = listOf(
            // Служебные/медиа‑маркеры
            "^🔹.*",
            "^🔸.*",
            "^🔴.*",
            "^⚡.*",
            "^🐚.*",
            "^Фото:.*",
            "^Фото.*",
            "^Видео.*",
            "^\$$.*\$$$",                 // [Фото], [Видео] и т.д.
            "^\\d{2}:\\d{2}\\s*—\\s*\$$.*\$$$", // "12:34 — [Стикер]"

            // Явные ссылки/приглашения
            "t\\.me/\\S+",
            "перейти в канал.*",
            "наш tg.*",
            "читать(ь)? больше.*",

            // Усиленные подписочные паттерны:
            // 1) Любая строка, где встречается подписочная форма
            ".*\\bподпис(аться|ывай(ся|тесь)?|ка)\\b.*",

            // 2) С эмодзи/символами в начале
            "^(?:[\\p{So}\\p{Sk}❗️!❤️💚💙💛💜🖤🤍🤎•·▫️◽️◾️▪️🔹🔸]\\s*)*подпис(аться|ывай(ся|тесь)?|ка)\\b.*",

            // 3) “все наши каналы”
            ".*\\bвсе\\s+наши\\s+каналы\\b.*",

            // 4) Комбинированные варианты через слэш/вертикальную черту/точку/тире
            ".*подпис(аться|ывай(ся|тесь)?)\\b.*[\\\\/|•·—–-].*",
            ".*\\bзеркал[оа]\\b.*" // “Зеркало/Зеркала”
        )

        val filtered = messages.mapNotNull { original ->
            val trimmed = original.trim()

            // Базовые фильтры
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
                trimmed.matches(Regex("^\\d{2}:\\d{2}\\s*—\\s*\$$.*\$$$")) -> {
                    Log.v(TAG, "⛔ Медиа-заглушка: \"$trimmed\"")
                    return@mapNotNull null
                }
            }

            // Проверка на промо-паттерны с логом совпавшего паттерна
            var dropByPromo = false
            promoPatterns.forEach { pattern ->
                if (!dropByPromo && trimmed.matches(Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)))) {
                    Log.v(TAG, "⛔ PROMO match: pattern='$pattern' | text=>>>${trimmed.replace("\n", "\\n")}<<<")
                    dropByPromo = true
                }
            }
            if (dropByPromo) return@mapNotNull null

            // Очистка
            var cleaned = trimmed
                // Удаляем медиа-префиксы типа "12:34 — фото/видео …"
                .replace(
                    Regex("^\\d{2}:\\d{2}\\s*—\\s*(фото|видео|аудио|документ|gif|голосовое сообщение)[\\p{P}\\s]*", RegexOption.IGNORE_CASE),
                    ""
                )
                // Нормализуем переносы
                .replace(Regex("\\n{3,}"), "\n\n")
                // Удаляем ссылки
                .replace(Regex("https?://\\S+"), "")
                // Удаляем хэштеги и упоминания
                .replace(Regex("(^|\\s)[#@][\\p{L}0-9_]+"), " ")
                // Удаляем одиночные пачки эмодзи/восклицаний
                .replace(Regex("[\\p{So}\\p{Sk}❗️!❤️💚💙💛💜🖤🤍🤎]+"), " ")
                // Внутренние подписочные фразы
                .replace(Regex("(?i)подпис(аться|ывай(ся|тесь)?)\\s+на\\s+[^\\n.]+"), "")
                .trim()

            // Срез промо‑хвостов после основного текста (на случай, если matches не сработал)
            cleaned = cleaned
                .replace(Regex("(?i)[\\s\\p{So}\\p{Sk}]*[\\\\/|•·—–-]\\s*подпис(аться|ывай(ся|тесь)?|ка)\\b.*$"), "")
                .replace(Regex("(?i)[\\s\\p{So}\\p{Sk}]*[\\\\/|•·—–-]\\s*все\\s+наши\\s+каналы\\b.*$"), "")
                .replace(Regex("(?i)[\\s\\p{So}\\p{Sk}]*[\\\\/|•·—–-]\\s*зеркал[оа]\\b.*$"), "")
                .replace(Regex("(?im)^.*\\bподпис(аться|ывай(ся|тесь)?|ка)\\b.*(\\||/|•|—|–).*$"), "")

            if (cleaned.isBlank() || cleaned.length <= 5) {
                Log.v(TAG, "⛔ Пустое после очистки: \"$original\" -> \"$cleaned\"")
                return@mapNotNull null
            }

            val finalMessage = if (cleaned.length > 5000) cleaned.take(4970) + "..." else cleaned
            Log.v(TAG, "✅ Сообщение принято: \"${finalMessage.take(50)}...\"")
            finalMessage
        }
            .distinct()
            .take(100)

        Log.d(TAG, "🎯 prepareMessages: итого ${filtered.size} сообщений после фильтрации")

        val originalCount = messages.size
        val filteredCount = filtered.size
        val filterRate = if (originalCount > 0) ((originalCount - filteredCount) * 100 / originalCount) else 0
        Log.d(TAG, "📊 Статистика фильтрации: $originalCount -> $filteredCount (отфильтровано $filterRate%)")

        Log.d(TAG, "prepareMessages(): RAW end")
        return filtered
    }
}
