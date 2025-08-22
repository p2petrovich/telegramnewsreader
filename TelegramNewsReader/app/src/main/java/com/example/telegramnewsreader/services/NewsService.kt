package com.example.telegramnewsreader.service

import android.util.Log
import com.example.telegramnewsreader.model.Channel
import com.example.telegramnewsreader.telegram.TelegramClient
import com.example.telegramnewsreader.tts.TTSManager
import kotlinx.coroutines.*
import java.io.File
import com.example.telegramnewsreader.utils.TTSDebugTracker
import kotlinx.coroutines.CancellationException

// 🔥 НОВОЕ: Интерфейс для обновления прогресса
interface ProgressCallback {
    fun onUpdateProgress(status: String, progress: Int, total: Int) {}
    fun onUpdateCounters(collected: Int, filtered: Int, synthesized: Int) {}
    fun onUpdateNewsPreview(newsList: List<String>) {}
    fun onUpdateChannelProgress(channels: List<Channel>) {}

    // Новые методы для более детального контроля
    fun onChannelProcessed(channel: Channel, messagesCount: Int) {}
    fun onMessageFiltered(originalCount: Int, filteredCount: Int) {}
    fun onSynthesisStarted(messageCount: Int) {}
    fun onSynthesisProgress(current: Int, total: Int) {}
    fun onSynthesisCompleted() {}
}

// Реализация по умолчанию без действий
class NoOpProgressCallback : ProgressCallback

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
        val totalMessages: Int,
        val realNewsCount: Int = 0
    )

    data class AudioWithChapters(
        val file: File,
        val chaptersMs: List<Long>,
        val realNewsCount: Int = 0
    )

    // 🔥 НОВОЕ: Метод для получения количества новостей для одного канала
    suspend fun getChannelNewsCount(
        channel: Channel,
        timeHours: Double
    ): Int = withContext(Dispatchers.IO) {
        try {
            val currentTimeSeconds = System.currentTimeMillis() / 1000
            val fromDate = currentTimeSeconds - (timeHours * 3600).toLong()

            val messages = telegramClient.getChannelMessagesPaginated(channel.id, fromDate)
            return@withContext messages.size
        } catch (e: Exception) {
            Log.e("NewsService", "Ошибка получения сообщений для канала ${channel.id}", e)
            0
        }
    }

    // 🔥 НОВОЕ: Метод для получения количества новостей для всех каналов
    suspend fun getAllChannelsNewsCount(
        channels: List<Channel>,
        timeHours: Double
    ): Map<Long, Int> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<Long, Int>()

        try {
            val currentTimeSeconds = System.currentTimeMillis() / 1000
            val fromDate = currentTimeSeconds - (timeHours * 3600).toLong()

            channels.forEach { channel ->
                try {
                    val messages = telegramClient.getChannelMessagesPaginated(channel.id, fromDate)
                    result[channel.id] = messages.size
                } catch (e: Exception) {
                    Log.e("NewsService", "Ошибка получения сообщений для канала ${channel.id}", e)
                    result[channel.id] = 0
                }
            }
        } catch (e: Exception) {
            Log.e("NewsService", "Ошибка в getAllChannelsNewsCount", e)
        }

        return@withContext result
    }

    suspend fun collectAndProcessNews(
        channels: List<Channel>,
        timeHours: Double,
        progressCallback: ProgressCallback = NoOpProgressCallback()
    ): File? = withContext(Dispatchers.IO) {
        TTSDebugTracker.clearHistory()
        TTSDebugTracker.trackSystemAction("NEWS START collectAndProcessNews channels=${channels.size} hours=$timeHours")

        val list = collectAndPrepareMessages(channels, timeHours, progressCallback) ?: return@withContext null
        if (list.preparedMessages.isEmpty()) return@withContext null

        TTSDebugTracker.trackSystemAction("SYNTH START (no chapters) messages=${list.preparedMessages.size}")
        progressCallback.onSynthesisStarted(list.preparedMessages.size)

        val out = ttsManager.convertToAudio(list.preparedMessages, pauseMs = 1200)
        TTSDebugTracker.trackSystemAction("SYNTH DONE (no chapters) fileExists=${out != null}")
        progressCallback.onSynthesisCompleted()

        out
    }

    suspend fun collectAndSynthesizeNewsList(
        channels: List<Channel>,
        timeHours: Double,
        progressCallback: ProgressCallback = NoOpProgressCallback()
    ): List<File> = withContext(Dispatchers.IO) {
        TTSDebugTracker.clearHistory()
        TTSDebugTracker.trackSystemAction("NEWS START collectAndSynthesizeNewsList channels=${channels.size} hours=$timeHours")

        val res = collectAndSynthesizeWithChapters(channels, timeHours, progressCallback)
        if (res != null) listOf(res.file) else emptyList()
    }

    suspend fun collectAndSynthesizeWithChapters(
        channels: List<Channel>,
        timeHours: Double,
        progressCallback: ProgressCallback = NoOpProgressCallback()
    ): AudioWithChapters? = withContext(Dispatchers.IO) {
        TTSDebugTracker.clearHistory()
        TTSDebugTracker.trackSystemAction("NEWS START collectAndSynthesizeWithChapters channels=${channels.size} hours=$timeHours")

        val list = collectAndPrepareMessages(channels, timeHours, progressCallback) ?: return@withContext null
        if (list.preparedMessages.isEmpty()) return@withContext null

        TTSDebugTracker.trackSystemAction("SYNTH START (with chapters) messages=${list.preparedMessages.size}")

        val audio = ttsManager.convertToAudioWithChaptersWithCallback(
            list.preparedMessages,
            pauseMs = 1000,
            progressCallback = object : TTSManager.SynthesisProgressCallback {
                override fun onProgress(current: Int, total: Int) {
                    progressCallback.onSynthesisProgress(current, total)
                    // Передаем: собрано, отфильтровано, озвучено
                    progressCallback.onUpdateCounters(list.preparedMessages.size, list.preparedMessages.size - list.preparedMessages.size, current)
                }

                override fun onStarted(messageCount: Int) {
                    progressCallback.onSynthesisStarted(messageCount)
                    // При старте синтеза обновляем счетчики
                    progressCallback.onUpdateCounters(list.preparedMessages.size, list.preparedMessages.size - list.preparedMessages.size, 0)
                }

                override fun onCompleted() {
                    progressCallback.onSynthesisCompleted()
                    // По завершении синтеза обновляем счетчики
                    progressCallback.onUpdateCounters(list.preparedMessages.size, list.preparedMessages.size - list.preparedMessages.size, list.preparedMessages.size)
                }
            }
        ) ?: return@withContext null

        TTSDebugTracker.trackSystemAction("SYNTH DONE (with chapters) file='${audio.file.name}' chapters=${audio.chaptersMs.size}")

        AudioWithChapters(audio.file, audio.chaptersMs, list.realNewsCount)
    }

    private suspend fun collectAndPrepareMessages(
        channels: List<Channel>,
        timeHours: Double,
        progressCallback: ProgressCallback = NoOpProgressCallback()
    ): Prepared? = withContext(Dispatchers.IO) {
        if (channels.isEmpty() || timeHours <= 0) return@withContext null

        try {
            withTimeout(TOTAL_TIMEOUT_MS) {
                // Проверяем, не отменена ли корутина
                if (!isActive) return@withTimeout null

                val allMessages = mutableListOf<String>()
                val currentTimeSeconds = System.currentTimeMillis() / 1000
                val fromDate = currentTimeSeconds - (timeHours * 3600).toLong()

                // Обновляем прогресс: начало сбора
                progressCallback.onUpdateProgress("Собираем новости из ${channels.size} каналов...", 0, channels.size)
                progressCallback.onUpdateChannelProgress(channels)

                val channelResults = channels.mapIndexed { index, channel ->
                    async {
                        // Проверяем, не отменена ли корутина
                        if (!isActive) return@async Pair(channel, emptyList<String>())

                        val result = processChannelWithTimeout(channel, fromDate)
                        // Обновляем прогресс после обработки каждого канала
                        progressCallback.onChannelProcessed(result.first, result.second.size)
                        progressCallback.onUpdateProgress("Обработан канал ${index + 1} из ${channels.size}", index + 1, channels.size)
                        result
                    }
                }.awaitAll()

                // Проверяем, не отменена ли корутина
                if (!isActive) return@withTimeout null

                var totalMessages = 0
                var realNewsCount = 0
                val newsPreview = mutableListOf<String>()

                channelResults.forEach { (channel, messages) ->
                    if (messages.isNotEmpty()) {
                        allMessages.add("Новости из канала ${channel.title}:")
                        allMessages.addAll(messages)
                        realNewsCount += messages.size

                        // Добавляем первые несколько новостей для предпросмотра
                        newsPreview.addAll(messages.take(5))
                    }
                }

                // Обновляем предпросмотр новостей
                progressCallback.onUpdateNewsPreview(newsPreview)

                if (allMessages.isEmpty()) {
                    progressCallback.onUpdateProgress("Новостей не найдено", 100, 100)
                    return@withTimeout Prepared(emptyList(), 0, 0)
                }

                // Проверяем, не отменена ли корутина
                if (!isActive) return@withTimeout null

                progressCallback.onUpdateProgress("Фильтруем новости...", 0, 100)
                val preparedMessages = prepareMessages(allMessages) { originalCount, filteredCount ->
                    // Проверяем, не отменена ли корутина
                    if (!isActive) return@prepareMessages

                    // Обновляем счетчики фильтрации
                    progressCallback.onMessageFiltered(originalCount, filteredCount)
                    // originalCount - всего сообщений
                    // (originalCount - filteredCount) - количество отфильтрованных (удаленных)
                    // 0 - пока не начат синтез
                    progressCallback.onUpdateCounters(originalCount, originalCount - filteredCount, 0)
                }

                // Проверяем, не отменена ли корутина
                if (!isActive) return@withTimeout null

                // Обновляем финальные счетчики
                progressCallback.onUpdateCounters(allMessages.size, allMessages.size - preparedMessages.size, 0)
                progressCallback.onUpdateProgress("Подготовка завершена", 100, 100)

                Prepared(preparedMessages, totalMessages, realNewsCount)
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Timeout", e)
            progressCallback.onUpdateProgress("Ошибка: превышено время ожидания", 0, 100)
            null
        } catch (e: CancellationException) {
            Log.d(TAG, "Сбор новостей отменен пользователем")
            progressCallback.onUpdateProgress("Сбор новостей отменен", 0, 100)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error", e)
            progressCallback.onUpdateProgress("Ошибка при сборе новостей: ${e.message}", 0, 100)
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

    private fun prepareMessages(messages: List<String>, onFilterProgress: ((Int, Int) -> Unit)? = null): List<String> {
        Log.d(TAG, "🧪 prepareMessages: обрабатываем ${messages.size} сообщений")

        Log.d(TAG, "prepareMessages(): RAW start, size=${messages.size}")
        messages.take(10).forEachIndexed { i, m ->
            Log.d(TAG, "RAW[$i]: >>>${m.replace("\n", "\\n")}<<<")
        }
        Log.d(TAG, "prepareMessages(): RAW preview end")

        val promoPatterns = listOf(
            "^🔹.*",
            "^🔸.*",
            "^🔴.*",
            "^⚡.*",
            "^🐚.*",
            "^Фото:.*",
            "^Фото.*",
            "^Видео.*",
            "^\$$.*\$$$",
            "^\\d{2}:\\d{2}\\s*—\\s*\$$.*\$$$",
            "t\\.me/\\S+",
            "перейти в канал.*",
            "наш tg.*",
            "читать(ь)? больше.*",
            ".*\\bподпис(аться|ывай(ся|тесь)?|ка)\\b.*",
            "^(?:[\\p{So}\\p{Sk}❗️!❤️💚💙💛💜🖤🤍🤎•·▫️◽️◾️▪️🔹🔸]\\s*)*подпис(аться|ывай(ся|тесь)?|ка)\\b.*",
            ".*\\bвсе\\s+наши\\s+каналы\\b.*",
            ".*подпис(аться|ывай(ся|тесь)?)\\b.*[\\\\/|•·—–-].*",
            ".*\\bзеркал[оа]\\b.*"
        )

        val filtered = messages.mapNotNull { original ->
            val trimmed = original.trim()

            if (trimmed.matches(Regex("^Новости из канала.*:$"))) {
                Log.v(TAG, "✅ Заголовок канала пропущен без фильтрации: \"$trimmed\"")
                return@mapNotNull trimmed
            }

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

            var dropByPromo = false
            promoPatterns.forEach { pattern ->
                if (!dropByPromo && trimmed.matches(Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)))) {
                    Log.v(TAG, "⛔ PROMO match: pattern='$pattern' | text=>>>${trimmed.replace("\n", "\\n")}<<<")
                    dropByPromo = true
                }
            }
            if (dropByPromo) return@mapNotNull null

            var cleaned = trimmed
                .replace(
                    Regex("^\\d{2}:\\d{2}\\s*—\\s*(фото|видео|аудио|документ|gif|голосовое сообщение)[\\p{P}\\s]*", RegexOption.IGNORE_CASE),
                    ""
                )
                .replace(Regex("\\n{3,}"), "\n\n")
                .replace(Regex("https?://\\S+"), "")
                .replace(Regex("(^|\\s)[#@][\\p{L}0-9_]+"), " ")
                .replace(Regex("[\\p{So}\\p{Sk}❗️!❤️💚💙💛💜🖤🤍🤎]+"), " ")
                .replace(Regex("(?i)подпис(аться|ывай(ся|тесь)?)\\s+на\\s+[^\\n.]+"), "")
                .trim()

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

        // Вызываем callback с прогрессом фильтрации
        onFilterProgress?.invoke(originalCount, filteredCount)

        Log.d(TAG, "prepareMessages(): RAW end")
        return filtered
    }
}