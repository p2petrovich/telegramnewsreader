package com.p2petrovich.telegramnewsreader.services

import android.util.Log
import com.p2petrovich.telegramnewsreader.models.Channel
import com.p2petrovich.telegramnewsreader.telegram.TelegramClient
import com.p2petrovich.telegramnewsreader.tts.TTSManager
import com.p2petrovich.telegramnewsreader.utils.Deduplicator
import com.p2petrovich.telegramnewsreader.utils.TextProcessor
import com.p2petrovich.telegramnewsreader.utils.AiProcessor
import com.p2petrovich.telegramnewsreader.utils.PreferenceManager
import kotlinx.coroutines.*

interface ProgressCallback {
    fun onUpdateProgress(status: String, progress: Int, total: Int) {}
    fun onUpdateCounters(collected: Int, filtered: Int, synthesized: Int) {}
    fun onUpdateNewsPreview(newsList: List<String>) {}
    fun onUpdateChannelProgress(channels: List<Channel>) {}
    fun onChannelProcessed(channel: Channel, messagesCount: Int) {}
    fun onDeduplicationComplete(beforeCount: Int, afterCount: Int) {}
    fun onMessageFiltered(originalCount: Int, filteredCount: Int) {}
    fun onAiProcessingComplete(beforeCount: Int, afterCount: Int) {}
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

        private const val HEADER_MARKER = "\u200B\u200C\u200B"

        fun isChannelHeader(text: String): Boolean = text.contains(HEADER_MARKER)
        fun makeChannelHeader(title: String): String = "${HEADER_MARKER}Новости из канала ${title}:"
    }

    data class Prepared(
        val preparedMessages: List<String>,
        val totalCollected: Int,
        val totalToSynthesize: Int,
        val realNewsCount: Int = 0
    )

    data class AudioPlaylist(
        val files: List<java.io.File>,
        val realNewsCount: Int = 0,
        val newsFileIndices: Set<Int> = emptySet()
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

    suspend fun collectAndSynthesizePlaylist(
        channels: List<Channel>,
        timeHours: Double,
        progressCallback: ProgressCallback = object : ProgressCallback {},
        deduplicator: Deduplicator? = null
    ): AudioPlaylist? = withContext(Dispatchers.IO) {

        val list = collectAndPrepareMessages(channels, timeHours, progressCallback, deduplicator)
            ?: return@withContext null
        if (list.preparedMessages.isEmpty()) return@withContext null

        var actualTtsNewsCount = list.totalToSynthesize

        val playlist = ttsManager.synthesizePlaylist(
            list.preparedMessages,
            pauseMs = 1000,
            progressCallback = object : TTSManager.SynthesisProgressCallback {
                override fun onActualCounts(newsCount: Int, partsCount: Int) {
                    actualTtsNewsCount = newsCount
                    progressCallback.onUpdateCounters(list.totalCollected, newsCount, 0)
                }
                override fun onProgress(current: Int, total: Int) {
                    progressCallback.onSynthesisProgress(current, total)
                    val synthesizedNews = if (total > 0) {
                        (current.toLong() * actualTtsNewsCount / total).toInt().coerceIn(0, actualTtsNewsCount)
                    } else 0
                    progressCallback.onUpdateCounters(list.totalCollected, actualTtsNewsCount, synthesizedNews)
                }
                override fun onStarted(messageCount: Int) { progressCallback.onSynthesisStarted(messageCount) }
                override fun onCompleted() {
                    progressCallback.onUpdateCounters(list.totalCollected, actualTtsNewsCount, actualTtsNewsCount)
                    progressCallback.onSynthesisCompleted()
                }
            }
        ) ?: return@withContext null

        AudioPlaylist(playlist.files, actualTtsNewsCount, playlist.newsFileIndices)
    }

    private suspend fun collectAndPrepareMessages(
        channels: List<Channel>,
        timeHours: Double,
        progressCallback: ProgressCallback,
        deduplicator: Deduplicator?
    ): Prepared? = withContext(Dispatchers.IO) {
        if (channels.isEmpty() || timeHours <= 0) return@withContext null

        try {
            withTimeout(TOTAL_TIMEOUT_MS) {
                val context = ttsManager.getContext()
                val isAiEnabled = PreferenceManager.isAiSummaryEnabled(context)
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
                        allMessages.add(makeChannelHeader(channel.title))
                        allMessages.addAll(messages)
                        realNewsCount += messages.size
                        newsPreview.addAll(messages.take(5))
                    }
                }

                val totalCollected = realNewsCount
                progressCallback.onUpdateNewsPreview(newsPreview)

                if (allMessages.isEmpty()) {
                    progressCallback.onUpdateProgress("Нет новостей", 100, 100)
                    return@withTimeout Prepared(emptyList(), 0, 0, 0)
                }

                ensureActive()

                progressCallback.onUpdateProgress("Дедупликация...", 0, 100)
                val deduplicated = TextProcessor.deduplicateAcrossChannels(allMessages)
                val dedupNewsCount = deduplicated.count { !isChannelHeader(it) }
                progressCallback.onDeduplicationComplete(totalCollected, dedupNewsCount)

                ensureActive()

                progressCallback.onUpdateProgress("Фильтрация...", 0, 100)
                val preparedMessages = TextProcessor.filterMessages(deduplicated) { _, _ -> }
                val filteredNewsCount = preparedMessages.count { !isChannelHeader(it) }
                progressCallback.onMessageFiltered(dedupNewsCount, filteredNewsCount)

                ensureActive()

                // Дедупликация через Deduplicator (если включена)
                val afterDedup = if (deduplicator != null && deduplicator.isEnabled) {
                    progressCallback.onUpdateProgress("Проверка на дубли...", 0, 100)
                    val filtered = mutableListOf<String>()
                    for (msg in preparedMessages) {
                        if (isChannelHeader(msg)) {
                            filtered.add(msg)
                        } else if (!deduplicator.isDuplicate(msg)) {
                            filtered.add(msg)
                        }
                    }
                    filtered
                } else {
                    preparedMessages
                }

                val afterDropTrivial = TextProcessor.dropTrivial(afterDedup)
                val totalToSynthesizeBeforeAi = afterDropTrivial.count { !isChannelHeader(it) }

                ensureActive()
                
                // 🔥 УСКОРЕНО: Параллельная обработка через ИИ
                val finalMessages = if (isAiEnabled) {
                    progressCallback.onUpdateProgress("Сжатие через ИИ...", 0, totalToSynthesizeBeforeAi)
                    
                    val deferredResults = afterDropTrivial.map { msg ->
                        async {
                            if (isChannelHeader(msg)) msg
                            else {
                                val summarized = AiProcessor.summarizeNews(msg, context)
                                summarized
                            }
                        }
                    }
                    
                    val results = deferredResults.awaitAll()
                    val filteredResults = results.filter { it.isNotBlank() }
                    
                    // Обновляем превью после ИИ-обработки
                    val summarizedPreview = filteredResults.take(5)
                    progressCallback.onUpdateNewsPreview(summarizedPreview)

                    val totalToSynthesizeAfterAi = filteredResults.count { !isChannelHeader(it) }
                    progressCallback.onAiProcessingComplete(totalToSynthesizeBeforeAi, totalToSynthesizeAfterAi)
                    progressCallback.onUpdateProgress("ИИ обработка завершена", 100, 100)
                    filteredResults
                } else {
                    afterDropTrivial
                }
                
                val finalToSynthesize = finalMessages.count { !isChannelHeader(it) }

                progressCallback.onUpdateCounters(totalCollected, finalToSynthesize, 0)
                progressCallback.onUpdateProgress("Подготовлено к озвучке", 100, 100)

                Prepared(finalMessages, totalCollected, finalToSynthesize, realNewsCount)
            }
        } catch (e: TimeoutCancellationException) {
            progressCallback.onUpdateProgress("Превышено время ожидания", 0, 100)
            null
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            progressCallback.onUpdateProgress("Ошибка: ${e.message}", 0, 100)
            null
        }
    }

    private suspend fun processChannelWithTimeout(channel: Channel, fromDate: Long): Pair<Channel, List<String>> {
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
