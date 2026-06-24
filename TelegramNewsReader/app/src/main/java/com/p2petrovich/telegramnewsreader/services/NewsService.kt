package com.p2petrovich.telegramnewsreader.services

import android.util.Log
import com.p2petrovich.telegramnewsreader.models.Channel
import com.p2petrovich.telegramnewsreader.telegram.TelegramClient
import com.p2petrovich.telegramnewsreader.tts.TTSManager
import com.p2petrovich.telegramnewsreader.utils.Deduplicator
import com.p2petrovich.telegramnewsreader.utils.TextProcessor
import com.p2petrovich.telegramnewsreader.utils.AiProcessor
import com.p2petrovich.telegramnewsreader.utils.PreferenceManager
import com.p2petrovich.telegramnewsreader.utils.EdgeConfig
import com.p2petrovich.telegramnewsreader.utils.DebugConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

interface ProgressCallback {
    fun onUpdateProgress(status: String, progress: Int, total: Int) {}
    fun onUpdateCounters(collected: Int, filtered: Int, synthesized: Int) {}
    fun onUpdateNewsPreview(newsList: List<String>) {}
    fun onUpdateChannelProgress(channels: List<Channel>) {}
    fun onChannelProcessed(channel: Channel, messagesCount: Int) {}
    fun onDeduplicationComplete(beforeCount: Int, afterCount: Int) {}
    fun onMessageFiltered(originalCount: Int, filteredCount: Int) {}
    fun onNewsTruncated(kept: Int, dropped: Int) {}
    fun onAiProcessingComplete(beforeCount: Int, afterCount: Int) {}
    fun onSynthesisStarted(messageCount: Int) {}
    fun onSynthesisProgress(current: Int, total: Int) {}
    fun onSynthesisCompleted() {}
    fun onOverallProgress(status: String, percentage: Int) {}
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

        // Захватывает время вместе с возможным тире/дефисом и пробелами.
        private val TIME_PREFIX_WITH_DASH = Regex("^\\d{2}:\\d{2}\\s*—?\\s*")
        private val TIME_ONLY = Regex("^\\d{2}:\\d{2}")

        fun isChannelHeader(text: String): Boolean = text.contains(HEADER_MARKER)
        fun makeChannelHeader(title: String, context: android.content.Context): String =
            "${HEADER_MARKER}${context.getString(com.p2petrovich.telegramnewsreader.R.string.channel_header_format, title)}"
    }

    /**
     * Логирует "снимок" всех сообщений на конкретном этапе обработки.
     * Logcat режет строки ~4000 байт, поэтому раньше ограничивали 300 символами.
     * Сейчас делегирует в logStageFull — печатает полный текст кусками по 800.
     */
    private fun logStage(stage: String, messages: List<String>) {
        if (DebugConfig.LOG_PIPELINE_STAGES) {
            logStageFull(stage, messages)
        }
    }

    /**
     * Краткая версия: печатает каждое сообщение, обрезая до 300 символов.
     * Оставлена на случай, когда нужен компактный лог без длинных хвостов.
     */
    private fun logStageShort(stage: String, messages: List<String>) {
        val news = messages.count { !isChannelHeader(it) }
        Log.d(TAG, "═══════ STAGE: $stage (всего=${messages.size}, новостей=$news) ═══════")
        messages.forEachIndexed { i, msg ->
            val tag = if (isChannelHeader(msg)) "[HEADER]" else "[NEWS]"
            Log.d(TAG, "$stage[$i] $tag: ${msg.replace("\n", "\\n").take(300)}")
        }
    }

    /**
     * Полная версия: печатает текст без обрезки, разбивая на куски по 800 символов.
     * Часть .0 — начало сообщения, .1, .2 — продолжение. Позволяет увидеть хвост
     * сообщения (подписки, ссылки, мусор), который не виден при обрезке до 300.
     */
    private fun logStageFull(stage: String, messages: List<String>) {
        val news = messages.count { !isChannelHeader(it) }
        Log.d(TAG, "═══════ STAGE: $stage (всего=${messages.size}, новостей=$news) ═══════")
        messages.forEachIndexed { i, msg ->
            val tag = if (isChannelHeader(msg)) "[HEADER]" else "[NEWS]"
            msg.chunked(800).forEachIndexed { part, chunk ->
                Log.d(TAG, "$stage[$i].$part $tag: ${chunk.replace("\n", "\\n")}")
            }
        }
    }

    data class Prepared(
        val preparedMessages: List<String>,
        val totalCollected: Int,
        val totalToSynthesize: Int,
        val realNewsCount: Int = 0,
        val wasAiEnabled: Boolean = false
    )

    data class AudioPlaylist(
        val files: List<java.io.File>,
        val realNewsCount: Int = 0,
        val newsFileIndices: Set<Int> = emptySet(),
        val fileToMsgIndex: IntArray = intArrayOf(),
        val originalMessages: List<String> = emptyList()
    )

    suspend fun getAllChannelsNewsCount(
        channels: List<Channel>,
        timeHours: Double
    ): Map<Long, Int> = withContext(Dispatchers.IO) {
        val currentTimeSeconds = System.currentTimeMillis() / 1000
        val fromDate = currentTimeSeconds - (timeHours * 3600).toLong()

        coroutineScope {
            channels.map { channel ->
                async {
                    channel.id to try {
                        withTimeout(CHANNEL_TIMEOUT_MS) {
                            telegramClient.getChannelMessagesPaginated(channel.id, fromDate).size
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "count failed for channel ${channel.id}: ${e.message}")
                        0
                    }
                }
            }.awaitAll().toMap()
        }
    }

    suspend fun collectAndSynthesizePlaylist(
        channels: List<Channel>,
        timeHours: Double,
        progressCallback: ProgressCallback = object : ProgressCallback {},
        deduplicator: Deduplicator? = null
    ): AudioPlaylist? = withContext(Dispatchers.IO) {
        val context = ttsManager.getContext()
        if (PreferenceManager.getTtsEngine(context) == "edge") {
            EdgeConfig.refreshIfNeeded(context)
        }

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

                    val overallPercentage = if (list.wasAiEnabled) {
                        50 + if (total > 0) (current * 50 / total).coerceIn(0, 50) else 0
                    } else {
                        if (total > 0) (current * 100 / total).coerceIn(0, 100) else 0
                    }
                    progressCallback.onOverallProgress(context.getString(com.p2petrovich.telegramnewsreader.R.string.speech_synthesis_status), overallPercentage)

                    val synthesizedNews = if (total > 0) {
                        (current.toLong() * actualTtsNewsCount / total).toInt().coerceIn(0, actualTtsNewsCount)
                    } else 0
                    progressCallback.onUpdateCounters(list.totalCollected, actualTtsNewsCount, synthesizedNews)
                }
                override fun onStarted(messageCount: Int) { progressCallback.onSynthesisStarted(messageCount) }
                override fun onCompleted() {
                    progressCallback.onUpdateCounters(list.totalCollected, actualTtsNewsCount, actualTtsNewsCount)
                    progressCallback.onOverallProgress(context.getString(com.p2petrovich.telegramnewsreader.R.string.synthesis_completed_status), 100)
                    progressCallback.onSynthesisCompleted()
                }
            }
        ) ?: return@withContext null

        AudioPlaylist(playlist.files, actualTtsNewsCount, playlist.newsFileIndices, playlist.fileToMsgIndex, list.preparedMessages)
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

                progressCallback.onUpdateProgress(context.getString(com.p2petrovich.telegramnewsreader.R.string.collecting_from_n_channels, channels.size), 0, channels.size)
                progressCallback.onUpdateChannelProgress(channels)

                val channelResults = channels.mapIndexed { index, channel ->
                    async {
                        ensureActive()
                        val result = processChannelWithTimeout(channel, fromDate)
                        progressCallback.onChannelProcessed(result.first, result.second.size)
                        progressCallback.onUpdateProgress(context.getString(com.p2petrovich.telegramnewsreader.R.string.channel_n_of_m, index + 1, channels.size), index + 1, channels.size)
                        result
                    }
                }.awaitAll()

                ensureActive()

                var realNewsCount = 0
                val newsPreview = mutableListOf<String>()
                val order = PreferenceManager.getNewsOrder(context)

                when (order) {
                    0 -> {
                        channelResults.forEach { (channel, messages) ->
                            if (messages.isNotEmpty()) {
                                allMessages.add(makeChannelHeader(channel.title, context))
                                allMessages.addAll(messages)
                                realNewsCount += messages.size
                                newsPreview.addAll(messages.take(5))
                            }
                        }
                    }
                    1 -> {
                        channelResults.forEach { (channel, messages) ->
                            if (messages.isNotEmpty()) {
                                allMessages.add(makeChannelHeader(channel.title, context))
                                allMessages.addAll(messages.reversed())
                                realNewsCount += messages.size
                                newsPreview.addAll(messages.reversed().take(5))
                            }
                        }
                    }
                    2 -> {
                        val mixedMessages = channelResults.flatMap { it.second }
                        val sorted = mixedMessages.sortedByDescending { it.take(5) }
                        allMessages.addAll(sorted)
                        realNewsCount = sorted.size
                        newsPreview.addAll(sorted.take(5))
                    }
                    3 -> {
                        val mixedMessages = channelResults.flatMap { it.second }
                        val sorted = mixedMessages.sortedBy { it.take(5) }
                        allMessages.addAll(sorted)
                        realNewsCount = sorted.size
                        newsPreview.addAll(sorted.take(5))
                    }
                }

                // ───────── ЭТАП 1: СЫРЫЕ НОВОСТИ ─────────
                logStage("1_RAW", allMessages)

                val totalCollected = realNewsCount
                progressCallback.onUpdateNewsPreview(newsPreview)

                if (allMessages.isEmpty()) {
                    Log.w(TAG, "Total messages from all channels is ZERO")
                    progressCallback.onUpdateProgress(context.getString(com.p2petrovich.telegramnewsreader.R.string.no_news_status), 100, 100)
                    return@withTimeout Prepared(emptyList(), 0, 0, 0)
                }

                ensureActive()

                progressCallback.onUpdateProgress(context.getString(com.p2petrovich.telegramnewsreader.R.string.filtering_status), 0, 100)
                val filteredMessages = TextProcessor.filterMessages(
                    allMessages,
                    maxNews = TextProcessor.MAX_NEWS_DEFAULT,
                    onFilterProgress = { _, _ -> },
                    onTruncated = { kept, dropped ->
                        progressCallback.onNewsTruncated(kept, dropped)
                        Log.w(TAG, "truncated $dropped news (kept $kept)")
                    }
                )
                val filteredNewsCount = filteredMessages.count { !isChannelHeader(it) }

                // ───────── ЭТАП 2: ПОСЛЕ ФИЛЬТРАЦИИ ─────────
                logStage("2_FILTER", filteredMessages)

                ensureActive()

                progressCallback.onUpdateProgress(context.getString(com.p2petrovich.telegramnewsreader.R.string.deduplication_status), 0, 100)
                // ПОРОГ совпадения берётся из ползунка настроек — управляет якорной дедупликацией
                val crossThreshold = PreferenceManager.getDedupThreshold(context).toDouble()
                val deduplicated = TextProcessor.deduplicateAcrossChannels(filteredMessages, crossThreshold)
                val dedupNewsCount = deduplicated.count { !isChannelHeader(it) }
                Log.d(TAG, "After across-channel dedup: ${deduplicated.size} (news: $dedupNewsCount, threshold=$crossThreshold)")

                // ───────── ЭТАП 3: ПОСЛЕ ДЕДУПЛИКАЦИИ МЕЖДУ КАНАЛАМИ ─────────
                logStage("3_DEDUP", deduplicated)

                progressCallback.onDeduplicationComplete(totalCollected, dedupNewsCount)

                val preparedMessages = deduplicated
                progressCallback.onMessageFiltered(filteredNewsCount, dedupNewsCount)

                ensureActive()

                val afterDedup = if (deduplicator != null && deduplicator.isEnabled) {
                    progressCallback.onUpdateProgress(context.getString(com.p2petrovich.telegramnewsreader.R.string.checking_duplicates_status), 0, 100)
                    val filtered = mutableListOf<String>()
                    for (msg in preparedMessages) {
                        if (isChannelHeader(msg)) {
                            filtered.add(msg)
                        } else if (!deduplicator.isDuplicate(msg)) {
                            filtered.add(msg)
                        }
                    }
                    Log.d(TAG, "After Deduplicator: ${filtered.size}")
                    filtered
                } else {
                    preparedMessages
                }

                // ───────── ЭТАП 4: ПОСЛЕ Deduplicator ─────────
                logStage("4_AFTER_DEDUPLICATOR", afterDedup)

                val afterDropTrivial = TextProcessor.dropTrivial(afterDedup)
                val totalToSynthesizeBeforeAi = afterDropTrivial.count { !isChannelHeader(it) }

                // ───────── ЭТАП 5: ПОСЛЕ dropTrivial ─────────
                logStage("5_DROP_TRIVIAL", afterDropTrivial)

                ensureActive()

                val finalMessages = if (isAiEnabled) {
                    progressCallback.onUpdateProgress(context.getString(com.p2petrovich.telegramnewsreader.R.string.ai_summarization_status), 0, totalToSynthesizeBeforeAi)

                    Log.d(TAG, "═══════ AI ОБРАБОТКА ВКЛЮЧЕНА (на вход: $totalToSynthesizeBeforeAi новостей) ═══════")

                    val semaphore = Semaphore(3)
                    var processedCount = 0

                    val results = afterDropTrivial.map { msg ->
                        async {
                            if (isChannelHeader(msg)) {
                                msg
                            } else {
                                semaphore.withPermit {
                                    // Отделяем время вместе с возможным тире, переклеиваем чистое время
                                    // обратно к результату — чтобы тире не уходило ни в AI, ни в финал.
                                    val matched = TIME_PREFIX_WITH_DASH.find(msg)?.value ?: ""
                                    val msgWithoutPrefix = if (matched.isNotEmpty()) msg.removePrefix(matched) else msg
                                    val cleanTimePrefix = TIME_ONLY.find(matched)?.value?.let { "$it " } ?: ""

                                    val rawResult = AiProcessor.summarizeNews(msgWithoutPrefix, context)
                                    val summarized = cleanTimePrefix + AiProcessor.stripErrorPrefix(rawResult)

                                    if (DebugConfig.LOG_PIPELINE_STAGES) {
                                        Log.d(TAG, "AI_IN : ${msgWithoutPrefix.replace("\n", "\\n").take(300)}")
                                        Log.d(TAG, "AI_OUT: ${summarized.replace("\n", "\\n").take(300)}")
                                    }

                                    synchronized(this@NewsService) {
                                        processedCount++
                                        progressCallback.onUpdateProgress(context.getString(com.p2petrovich.telegramnewsreader.R.string.ai_summarization_status), processedCount, totalToSynthesizeBeforeAi)

                                        val overallPercentage = if (totalToSynthesizeBeforeAi > 0) {
                                            (processedCount * 50 / totalToSynthesizeBeforeAi).coerceIn(0, 50)
                                        } else 0
                                        progressCallback.onOverallProgress(context.getString(com.p2petrovich.telegramnewsreader.R.string.ai_summarization_status), overallPercentage)
                                    }
                                    summarized
                                }
                            }
                        }
                    }.awaitAll()

                    val filteredResults = results.filter { it.isNotBlank() }

                    val summarizedPreview = filteredResults.take(5)
                    progressCallback.onUpdateNewsPreview(summarizedPreview)

                    val totalToSynthesizeAfterAi = filteredResults.count { !isChannelHeader(it) }
                    progressCallback.onAiProcessingComplete(totalToSynthesizeBeforeAi, totalToSynthesizeAfterAi)
                    progressCallback.onUpdateProgress(context.getString(com.p2petrovich.telegramnewsreader.R.string.ai_processing_completed_status), 100, 100)
                    filteredResults
                } else {
                    // DEBUG_LOGS: временно закомментировано — можно включить для отладки AI pipeline.
                    // Log.d(TAG, "═══════ AI ОБРАБОТКА ОТКЛЮЧЕНА ═══════")
                    afterDropTrivial
                }

                val finalMessagesWithHeaders = if (finalMessages.isNotEmpty()) {
                    TextProcessor.dropEmptyHeaders(finalMessages)
                } else {
                    emptyList()
                }

                val finalToSynthesize = finalMessagesWithHeaders.count { !isChannelHeader(it) }

                // ───────── ЭТАП 6: ФИНАЛ ДЛЯ TTS ─────────
                logStage("6_FINAL_FOR_TTS", finalMessagesWithHeaders)

                progressCallback.onUpdateCounters(totalCollected, finalToSynthesize, 0)
                progressCallback.onUpdateProgress(context.getString(com.p2petrovich.telegramnewsreader.R.string.prepared_for_synthesis_status), 100, 100)

                Prepared(finalMessagesWithHeaders, totalCollected, finalToSynthesize, realNewsCount, isAiEnabled)
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "collectAndPrepareMessages: TOTAL TIMEOUT", e)
            val context = ttsManager.getContext()
            progressCallback.onUpdateProgress(context.getString(com.p2petrovich.telegramnewsreader.R.string.timeout_exceeded_status), 0, 100)
            null
        } catch (e: CancellationException) {
            Log.d(TAG, "collectAndPrepareMessages: Cancelled")
            throw e
        }
        catch (e: Exception) {
            Log.e(TAG, "collectAndPrepareMessages: ERROR", e)
            val context = ttsManager.getContext()
            progressCallback.onUpdateProgress(context.getString(com.p2petrovich.telegramnewsreader.R.string.error_prefix, e.message), 0, 100)
            null
        }
    }

    private suspend fun processChannelWithTimeout(channel: Channel, fromDate: Long): Pair<Channel, List<String>> {
        return try {
            withTimeout(CHANNEL_TIMEOUT_MS) {
                val messages = telegramClient.getChannelMessagesPaginated(channel.id, fromDate)
                Log.d(TAG, "Channel ${channel.title} (${channel.id}): returned ${messages.size} messages")
                channel.newMessagesCount = messages.size
                Pair(channel, messages)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing channel ${channel.title} (${channel.id}): ${e.message}")
            channel.newMessagesCount = 0
            Pair(channel, emptyList())
        }
    }
}
