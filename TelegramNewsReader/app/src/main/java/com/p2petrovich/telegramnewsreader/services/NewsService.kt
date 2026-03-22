package com.p2petrovich.telegramnewsreader.service

import android.util.Log
import com.p2petrovich.telegramnewsreader.model.Channel
import com.p2petrovich.telegramnewsreader.telegram.TelegramClient
import com.p2petrovich.telegramnewsreader.tts.TTSManager
import com.p2petrovich.telegramnewsreader.utils.TextProcessor
import kotlinx.coroutines.*

interface ProgressCallback {
    fun onUpdateProgress(status: String, progress: Int, total: Int) {}
    fun onUpdateCounters(collected: Int, filtered: Int, synthesized: Int) {}
    fun onUpdateNewsPreview(newsList: List<String>) {}
    fun onUpdateChannelProgress(channels: List<Channel>) {}
    fun onChannelProcessed(channel: Channel, messagesCount: Int) {}
    fun onMessageFiltered(originalCount: Int, filteredCount: Int) {}
    fun onSynthesisStarted(messageCount: Int) {}
    fun onSynthesisProgress(current: Int, total: Int) {}
    fun onSynthesisCompleted() {}
}

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
        val totalCollected: Int,
        val totalToSynthesize: Int,
        val realNewsCount: Int = 0
    )

    data class AudioWithChapters(
        val file: java.io.File,
        val chaptersMs: List<Long>,
        val realNewsCount: Int = 0
    )

    suspend fun getAllChannelsNewsCount(
        channels: List<Channel>,
        timeHours: Double
    ): Map<Long, Int> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<Long, Int>()
        val currentTimeSeconds = System.currentTimeMillis() / 1000
        val fromDate = currentTimeSeconds - (timeHours * 3600).toLong()

        channels.forEach { channel ->
            try {
                val messages = telegramClient.getChannelMessagesPaginated(channel.id, fromDate)
                result[channel.id] = messages.size
            } catch (e: Exception) {
                Log.e(TAG, "Error getting news count for channel ${channel.id}", e)
                result[channel.id] = 0
            }
        }
        result
    }

    suspend fun collectAndSynthesizeWithChapters(
        channels: List<Channel>,
        timeHours: Double,
        progressCallback: ProgressCallback = object : ProgressCallback {}
    ): AudioWithChapters? = withContext(Dispatchers.IO) {

        val list = collectAndPrepareMessages(channels, timeHours, progressCallback)
            ?: return@withContext null
        if (list.preparedMessages.isEmpty()) return@withContext null

        val audio = ttsManager.convertToAudioWithChaptersWithCallback(
            list.preparedMessages,
            pauseMs = 1000,
            progressCallback = object : TTSManager.SynthesisProgressCallback {
                override fun onProgress(current: Int, total: Int) {
                    progressCallback.onSynthesisProgress(current, total)
                    // Пересчитываем: сколько новостей озвучено пропорционально частям
                    val synthesizedNews = if (total > 0) {
                        (current.toLong() * list.totalToSynthesize / total).toInt()
                            .coerceIn(0, list.totalToSynthesize)
                    } else 0
                    progressCallback.onUpdateCounters(list.totalCollected, list.totalToSynthesize, synthesizedNews)
                }
                override fun onStarted(messageCount: Int) {
                    progressCallback.onSynthesisStarted(messageCount)
                }
                override fun onCompleted() {
                    // Финальное обновление: всё озвучено
                    progressCallback.onUpdateCounters(list.totalCollected, list.totalToSynthesize, list.totalToSynthesize)
                    progressCallback.onSynthesisCompleted()
                }
            }
        ) ?: return@withContext null


        AudioWithChapters(audio.file, audio.chaptersMs, list.realNewsCount)
    }

    private suspend fun collectAndPrepareMessages(
        channels: List<Channel>,
        timeHours: Double,
        progressCallback: ProgressCallback
    ): Prepared? = withContext(Dispatchers.IO) {
        if (channels.isEmpty() || timeHours <= 0) return@withContext null

        try {
            withTimeout(TOTAL_TIMEOUT_MS) {
                val allMessages = mutableListOf<String>()
                val currentTimeSeconds = System.currentTimeMillis() / 1000
                val fromDate = currentTimeSeconds - (timeHours * 3600).toLong()

                progressCallback.onUpdateProgress("Сбор из ${channels.size} каналов...", 0, channels.size)
                progressCallback.onUpdateChannelProgress(channels)

                val channelResults = channels.mapIndexed { index, channel ->
                    async {
                        ensureActive()
                        val result = processChannelWithTimeout(channel, fromDate)
                        progressCallback.onChannelProcessed(result.first, result.second.size)
                        progressCallback.onUpdateProgress("Канал ${index + 1} из ${channels.size}", index + 1, channels.size)
                        result
                    }
                }.awaitAll()

                ensureActive()

                var realNewsCount = 0
                val newsPreview = mutableListOf<String>()

                channelResults.forEach { (channel, messages) ->
                    if (messages.isNotEmpty()) {
                        allMessages.add("   ${channel.title}:")
                        allMessages.addAll(messages)
                        realNewsCount += messages.size
                        newsPreview.addAll(messages.take(5))
                    }
                }

                // Реальное число собранных сообщений из Telegram (без заголовков)
                val totalCollected = allMessages.count { msg ->
                    val trimmed = msg.trimEnd()
                    !(trimmed.endsWith(":") && !trimmed.contains("\n") && trimmed.length < 80)
                }

                progressCallback.onUpdateNewsPreview(newsPreview)

                if (allMessages.isEmpty()) {
                    progressCallback.onUpdateProgress("Нет новостей", 100, 100)
                    return@withTimeout Prepared(emptyList(), 0, 0, 0)
                }

                ensureActive()

                // Дедупликация между каналами
                progressCallback.onUpdateProgress("Дедупликация...", 0, 100)
                val deduplicated = TextProcessor.deduplicateAcrossChannels(allMessages)
                val removedByDedup = allMessages.size - deduplicated.size
                Log.d(TAG, "Dedup: ${allMessages.size} -> ${deduplicated.size} (removed $removedByDedup)")

                ensureActive()

                // Фильтрация спама/рекламы
                progressCallback.onUpdateProgress("Фильтрация...", 0, 100)

                val preparedMessages = TextProcessor.filterMessages(deduplicated) { originalCount, filteredCount ->
                    progressCallback.onMessageFiltered(originalCount, filteredCount)
                }

                val headerPattern = Regex("^\\s{2,}.{1,60}:\\s*$")
                val totalToSynthesize = preparedMessages.count { !it.matches(headerPattern) }.coerceAtMost(totalCollected)
                val removedByFilter = deduplicated.size - totalToSynthesize
                Log.d(TAG, "Filter: ${deduplicated.size} -> $totalToSynthesize (removed $removedByFilter)")

                ensureActive()

                // Собрано = из Telegram, К озвучке = после фильтров, Синтезировано = 0 пока
                progressCallback.onUpdateCounters(totalCollected, totalToSynthesize, 0)
                progressCallback.onUpdateProgress("Подготовлено к озвучке", 100, 100)

                Prepared(preparedMessages, totalCollected, totalToSynthesize, realNewsCount)
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Timeout", e)
            progressCallback.onUpdateProgress("Превышено время ожидания", 0, 100)
            null
        } catch (e: CancellationException) {
            Log.d(TAG, "Collection cancelled by user")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error", e)
            progressCallback.onUpdateProgress("Ошибка: ${e.message}", 0, 100)
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
}
