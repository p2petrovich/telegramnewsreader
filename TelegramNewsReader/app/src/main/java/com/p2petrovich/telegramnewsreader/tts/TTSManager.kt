package com.p2petrovich.telegramnewsreader.tts

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.p2petrovich.telegramnewsreader.models.VoiceEntry
import com.p2petrovich.telegramnewsreader.models.VoiceMappings
import com.p2petrovich.telegramnewsreader.services.NewsService
import com.p2petrovich.telegramnewsreader.utils.AudioUtils
import com.p2petrovich.telegramnewsreader.utils.EdgeConfig // [FIX] для предварительного refresh версии Chromium
import com.p2petrovich.telegramnewsreader.utils.NewsCache
import com.p2petrovich.telegramnewsreader.utils.PcmResampler // [FFmpeg removed] нативная нормализация выхода Android TTS
import com.p2petrovich.telegramnewsreader.utils.PreferenceManager
import com.p2petrovich.telegramnewsreader.utils.TextProcessor
import com.p2petrovich.telegramnewsreader.utils.HttpClients
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.withTimeoutOrNull

object TTSManagerSingleton {
    @Volatile
    private var INSTANCE: TTSManager? = null

    fun getInstance(context: Context): TTSManager {
        return INSTANCE ?: synchronized(this) {
            INSTANCE ?: TTSManager(context.applicationContext).also { INSTANCE = it }
        }
    }

    fun clearInstance() {
        synchronized(this) {
            INSTANCE?.shutdown()
            INSTANCE = null
            HttpClients.shutdown()
        }
    }
}

class TTSManager(private val context: Context) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "TTSManager"

        const val ACTION_TTS_ERROR = "com.p2petrovich.telegramnewsreader.TTS_ERROR"
        const val EXTRA_ERROR_MESSAGE = "extra_error_message"

        // Количество попыток синтеза через Edge перед fallback на Android TTS.
        // Сетевые сбои WebSocket — норма, обычно вторая попытка проходит.
        // [FIX] Поднято с 2 до 3: в логах падения частые, третья попытка
        // заметно снижает уход в Android-fallback.
        private const val EDGE_RETRY_ATTEMPTS = 3
        private const val EDGE_RETRY_DELAY_MS = 500L

        // DEBUG_LOGS: временно закомментировано — можно включить для отладки синтеза.
        // Лог финального текста перед синтезом (после prepareForSpeech).
        // true — печатает то, что реально идёт в TTS, кусками по 800 символов.
        // Удобно для проверки чистки. Выключи в релизе.
        // private const val LOG_SYNTH_INPUT = true
        private const val LOG_SYNTH_INPUT = true
    }

    private var tts: TextToSpeech? = null
    private var ttsInitialized = AtomicBoolean(false)

    // Список ожидающих инициализации корутин — чтобы несколько вызовов waitInit()
    // не перетирали друг друга.
    private val initWaiters = mutableListOf<CancellableContinuation<Boolean>>()
    private val initLock = Any()

    private var voiceParametersApplied = false
    private var currentAppliedPitch: Float? = null
    private var currentAppliedRate: Float? = null

    private val androidTtsMutex = Mutex()
    private val progressLock = Any()

    // Edge TTS провайдер (null = используем Android TTS)
    @Volatile private var edgeProvider: EdgeTtsProvider? = null

    init {
        tts = TextToSpeech(context, this)
        refreshEdgeProvider()
    }

    override fun onInit(status: Int) {
        val success = status == TextToSpeech.SUCCESS
        if (success) {
            ttsInitialized.set(true)
            applySavedVoice()
        } else {
            ttsInitialized.set(false)
            Log.e(TAG, "TTS init failed, status=$status")
        }

        // Разбудить всех ожидающих
        val waiters = synchronized(initLock) {
            val copy = initWaiters.toList()
            initWaiters.clear()
            copy
        }
        waiters.forEach { cont ->
            if (cont.isActive) cont.resume(success)
        }
    }

    private fun applySavedVoice() {
        val availableVoices = tts?.voices
        val savedVoiceName = PreferenceManager.getTtsVoiceName(context)
        val matchedVoice = availableVoices?.find { it.name == savedVoiceName }

        if (matchedVoice != null) {
            tts?.language = matchedVoice.locale
            tts?.voice = matchedVoice
        }
        // Если сохранённого голоса нет — оставляем системный дефолт (язык телефона)

        applyVoiceParametersOnce()
    }

    private fun applyVoiceParametersOnce() {
        if (voiceParametersApplied) return
        val savedVoiceName = PreferenceManager.getTtsVoiceName(context)
        if (savedVoiceName != null) {
            applyVoiceSettings(savedVoiceName)
        } else {
            val pitch = PreferenceManager.getTtsPitch(context)
            val rate = PreferenceManager.getTtsRate(context)
            tts?.setPitch(pitch)
            tts?.setSpeechRate(rate)
            currentAppliedPitch = pitch
            currentAppliedRate = rate
        }
        voiceParametersApplied = true
    }

    /**
     * Ждёт инициализации Android TTS. Безопасно вызывать из нескольких корутин одновременно —
     * каждая получит результат, никто не потеряется.
     */
    suspend fun waitInit(): Boolean {
        if (ttsInitialized.get()) return true
        if (tts == null) return false

        return withTimeoutOrNull(5000L) {
            suspendCancellableCoroutine<Boolean> { continuation ->
                // Если успели проинициализироваться, пока заходили — сразу резюмим
                if (ttsInitialized.get()) {
                    continuation.resume(true)
                    return@suspendCancellableCoroutine
                }

                synchronized(initLock) {
                    initWaiters.add(continuation)
                }

                continuation.invokeOnCancellation {
                    synchronized(initLock) {
                        initWaiters.remove(continuation)
                    }
                }
            }
        } ?: ttsInitialized.get()
    }

    fun getAvailableVoiceEntries(): List<VoiceEntry> {
        val systemVoices = tts?.voices?.toList() ?: emptyList()
        return VoiceMappings.mapVoices(context, systemVoices)
    }

    fun setVoiceByEntry(voiceEntry: VoiceEntry) { setVoiceByName(voiceEntry.systemName) }

    fun setVoiceByName(voiceName: String) {
        val voice = tts?.voices?.firstOrNull { it.name == voiceName }
        voice?.let {
            tts?.language = it.locale
            tts?.voice = it
            PreferenceManager.saveTtsVoiceName(context, voiceName)
            voiceParametersApplied = false
            applyVoiceSettings(voiceName)
        }
    }

    fun applyVoiceSettings(voiceName: String) {
        val pitch = PreferenceManager.getTtsPitchForVoice(context, voiceName)
        val rate = PreferenceManager.getTtsRateForVoice(context, voiceName)
        tts?.setPitch(pitch)
        tts?.setSpeechRate(rate)
        currentAppliedPitch = pitch
        currentAppliedRate = rate
    }

    /** Пересоздаёт Edge TTS провайдер по текущим настройкам. Вызывать при смене движка/голоса. */
    fun refreshEdgeProvider() {
        edgeProvider = if (PreferenceManager.getTtsEngine(context) == "edge") {
            EdgeTtsProvider(
                context = context,
                voice   = PreferenceManager.getEdgeVoice(context),
                ratePct = PreferenceManager.getEdgeRate(context),
                pitchHz = PreferenceManager.getEdgePitch(context)
            )
        } else {
            null
        }
    }

    /** Возвращает true если сейчас выбран Edge TTS движок. */
    fun isEdgeEngineActive(): Boolean = edgeProvider != null

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

    fun speak(text: String) {
        if (ttsInitialized.get()) {
            applyLanguageByText(text)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "TTS_SAMPLE")
        }
    }

    fun refreshVoice() {
        if (!ttsInitialized.get()) return
        val savedVoiceName = PreferenceManager.getTtsVoiceName(context)
        val selectedVoice = tts?.voices?.find { it.name == savedVoiceName }
        selectedVoice?.let {
            tts?.language = it.locale
            tts?.voice = it
            if (savedVoiceName != null) applyVoiceSettings(savedVoiceName)
        }
    }

    fun getContext(): Context = context

    fun updatePitchForVoice(voiceName: String, pitch: Float) { PreferenceManager.saveTtsPitchForVoice(context, voiceName, pitch) }
    fun updateRateForVoice(voiceName: String, rate: Float) { PreferenceManager.saveTtsRateForVoice(context, voiceName, rate) }
    fun getPitchForVoice(voiceName: String): Float = PreferenceManager.getTtsPitchForVoice(context, voiceName)
    fun getRateForVoice(voiceName: String): Float = PreferenceManager.getTtsRateForVoice(context, voiceName)

    // ============ Audio synthesis ============

    interface SynthesisProgressCallback {
        fun onProgress(current: Int, total: Int)
        fun onStarted(messageCount: Int)
        fun onCompleted()
        fun onActualCounts(newsCount: Int, partsCount: Int) {}
    }

    data class AudioPlaylist(
        val files: List<File>,
        val actualNewsCount: Int = 0,
        val newsFileIndices: Set<Int> = emptySet(),
        val fileToMsgIndex: IntArray = intArrayOf()
    )

    private data class PartJob(
        val chapterIdx: Int,
        val partInChapter: Int,
        val partText: String,
        val partIndex: Int,
        val hash: String
    )

    private data class PartResult(
        val job: PartJob,
        val wav: File,
        val meta: WavMeta,
        val actuallyUsedEdge: Boolean,
        val isFromCache: Boolean
    )

    private fun stripHeaderMarker(text: String): String {
        return text.replace("\u200B", "").replace("\u200C", "").trim()
    }

    /**
     * [FIX] Единая точка расчёта ключа кэша.
     *
     * Раньше ключ считался в двух местах (synthesizePlaylist и synthesizeOnePart)
     * с риском разойтись по символам — тогда запись и чтение кэша не совпадали бы.
     * Теперь обе точки используют этот helper.
     *
     * Ключ привязан к ФАКТИЧЕСКОМУ движку: Edge-аудио → "edge:<voice>",
     * Android-fallback → системный voiceName. Это не даёт fallback-аудио
     * лечь под Edge-ключ и подменить голос при следующем запуске.
     */
    private fun cacheHashFor(
        text: String,
        useEdge: Boolean,
        voiceName: String,
        cachePitch: Float,
        cacheRate: Float
    ): String {
        return if (useEdge) {
            val ev = PreferenceManager.getEdgeVoice(context)
            val er = PreferenceManager.getEdgeRate(context)
            val ep = PreferenceManager.getEdgePitch(context)
            NewsCache.messageHash(text, "edge:$ev", ep.toFloat(), er.toFloat())
        } else {
            NewsCache.messageHash(text, voiceName, cachePitch, cacheRate)
        }
    }

    /**
     * Логирует финальный текст части перед синтезом — то, что реально пойдёт в TTS
     * после prepareForSpeech и splitByParagraphs. Разбивает на куски по 800 символов,
     * чтобы Logcat не обрезал длинные сообщения.
     */
    private fun logSynthInput(job: PartJob) {
        if (!LOG_SYNTH_INPUT) return
        val isHeader = NewsService.isChannelHeader(job.partText)
        job.partText.chunked(800).forEachIndexed { p, chunk ->
            Log.d(TAG, "SYNTH_IN[part=${job.partIndex}].$p header=$isHeader: ${chunk.replace("\n", "\\n")}")
        }
    }

    /**
     * Синтезирует одну часть через Edge TTS с повторными попытками.
     * Возвращает true если получилось, false если все попытки провалились.
     */
    private suspend fun trySynthesizeEdge(
        edge: EdgeTtsProvider,
        text: String,
        outFile: File,
        partIndex: Int
    ): Boolean {
        for (attempt in 1..EDGE_RETRY_ATTEMPTS) {
            val ok = try {
                edge.synthesizeToWav(text, outFile)
            } catch (e: Exception) {
                Log.w(TAG, "Edge attempt $attempt error for part $partIndex: ${e.message}")
                false
            }
            if (ok && outFile.exists() && outFile.length() > 0) {
                if (attempt > 1) {
                    Log.d(TAG, "Edge succeeded on attempt $attempt for part $partIndex")
                }
                return true
            }
            Log.w(TAG, "Edge attempt $attempt failed for part $partIndex")
            if (outFile.exists()) outFile.delete()
            if (attempt < EDGE_RETRY_ATTEMPTS) {
                // [FIX] Экспоненциальная задержка вместо фиксированной: 500мс, 1000мс, 2000мс...
                // Снижает шанс попасть под rate-limit Microsoft при серии сбоев подряд.
                delay(EDGE_RETRY_DELAY_MS * (1L shl (attempt - 1)))
            }
        }

        // Если все попытки провалены — уведомляем UI о необходимости fallback
        sendTtsError(context.getString(com.p2petrovich.telegramnewsreader.R.string.edge_tts_unavailable_fallback))
        return false
    }

    private fun sendTtsError(message: String) {
        try {
            val intent = Intent(ACTION_TTS_ERROR).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_ERROR_MESSAGE, message)
            }
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send TTS error broadcast", e)
        }
    }

    private suspend fun synthesizeOnePart(
        job: PartJob,
        baseUtteranceId: String,
        voiceName: String,
        cachePitch: Float,
        cacheRate: Float,
        isEdgeActive: Boolean,
        cachedWavPaths: MutableSet<String>
    ): PartResult? {
        // Лог реального входа в синтез (после всей чистки)
        logSynthInput(job)

        val cachedFile = NewsCache.findCachedWav(context, job.hash)
        if (cachedFile != null) {
            val meta = readWavMeta(cachedFile) ?: return null
            synchronized(cachedWavPaths) { cachedWavPaths.add(cachedFile.absolutePath) }
            return PartResult(job, cachedFile, meta, actuallyUsedEdge = isEdgeActive, isFromCache = true)
        }

        var wav: File? = null
        var actuallyUsedEdge = false

        val edge = edgeProvider
        if (edge != null) {
            val tmp = File(context.cacheDir, "${baseUtteranceId}_part_${job.partIndex}.wav")
            if (trySynthesizeEdge(edge, job.partText, tmp, job.partIndex)) {
                wav = tmp
                actuallyUsedEdge = true
            }
        }

        if (wav == null) {
            // Android Fallback - MUST BE SERIALIZED
            wav = androidTtsMutex.withLock {
                if (voiceName != "default") {
                    setVoiceByName(voiceName)
                }
                synthesizePartToWav(job.partText, job.partIndex, baseUtteranceId)
            }
        }

        val finalWav = wav ?: return null
        if (!finalWav.exists() || finalWav.length() == 0L) return null

        val meta = readWavMeta(finalWav) ?: return null

        // [FIX] Кэшируем ВСЕГДА (а не только при совпадении ожидаемого и фактического
        // движка, как было раньше), но под ключом ФАКТИЧЕСКОГО движка.
        //
        // Раньше тут стоял "Skip caching ... expectedEdge!=actualEdge", из-за чего
        // fallback-куски не кэшировались вовсе и при каждом запуске синтезировались
        // заново. Теперь fallback-аудио ложится под Android-ключ: при повторном
        // падении Edge оно возьмётся из кэша, а не пойдёт на третий синтез.
        // Привязка ключа к реальному движку исключает подмену голоса:
        // в Edge-режиме поиск идёт по Edge-ключу, и Android-кэш туда не попадёт.
        val cacheHash = cacheHashFor(job.partText, actuallyUsedEdge, voiceName, cachePitch, cacheRate)
        NewsCache.saveWavToCache(context, cacheHash, finalWav)

        return PartResult(job, finalWav, meta, actuallyUsedEdge, isFromCache = false)
    }

    suspend fun synthesizePlaylist(
        texts: List<String>,
        pauseMs: Int = 1000,
        progressCallback: SynthesisProgressCallback?
    ): AudioPlaylist? {
        if (!waitInit() || tts == null) return null

        progressCallback?.onStarted(texts.size)

        val filteredNews = TextProcessor.dropTrivial(texts)
        if (filteredNews.isEmpty()) {
            progressCallback?.onCompleted()
            return null
        }

        val voiceName = PreferenceManager.getTtsVoiceName(context) ?: "default"
        val cachePitch = currentAppliedPitch ?: PreferenceManager.getTtsPitch(context)
        val cacheRate = currentAppliedRate ?: PreferenceManager.getTtsRate(context)
        val isEdgeActive = edgeProvider != null

        // [FIX] Освежаем версию Chromium ДО старта синтеза, а не только реактивно
        // после первого 403 в onFailure. Это снижает частоту самого первого
        // "Edge attempt 1 failed" из-за устаревшего Sec-MS-GEC-Version.
        // refreshIfNeeded сам решает, нужно ли обновление (по таймауту 24ч),
        // так что лишних сетевых запросов не будет.
        if (isEdgeActive) {
            try { EdgeConfig.refreshIfNeeded(context) } catch (_: Exception) {}
        }

        data class PreparedNews(
            val originalIndex: Int,
            val textForSplitting: String,
            val isHeader: Boolean
        )

        val prepared = mutableListOf<PreparedNews>()

        filteredNews.forEachIndexed { newsIndex, raw ->
            val isHeader = NewsService.isChannelHeader(raw)
            if (isHeader) {
                val cleanTitle = stripHeaderMarker(raw)
                if (cleanTitle.isNotBlank()) {
                    prepared += PreparedNews(newsIndex, "$cleanTitle...", true)
                }
            } else {
                // Единый конвейер чистки — одинаковый результат с AI и без AI.
                var finalText = TextProcessor.prepareForSpeech(raw)

                // Дополнительные паузы между предложениями нужны только Android TTS:
                // Edge Neural на "..." реагирует ускорением темпа.
                if (!isEdgeActive) {
                    finalText = finalText.replace(Regex("(?<=[.!?…])\\s+"), "... ")
                }

                if (finalText.isNotBlank()) {
                    prepared += PreparedNews(newsIndex, finalText, false)
                }
            }
        }

        if (prepared.isEmpty()) {
            progressCallback?.onCompleted()
            return null
        }

        val actualNewsCount = prepared.count { !it.isHeader }

        // ФАЗА 1: Подготовка списка задач
        val jobs = mutableListOf<PartJob>()
        prepared.forEachIndexed { chIdx, item ->
            val parts = TextProcessor.splitByParagraphs(item.textForSplitting, 2800)
            parts.forEachIndexed { pIdx, text ->
                val partIndex = ((item.originalIndex + 1) * 1000 + (pIdx + 1))
                // [FIX] Расчёт ключа вынесен в общий helper cacheHashFor, чтобы
                // запись (synthesizeOnePart) и чтение использовали ИДЕНТИЧНУЮ логику.
                // Поведение для Edge-режима не изменилось: ключ всё так же
                // "edge:<voice>|pitch|rate|text".
                val hash = cacheHashFor(text, isEdgeActive, voiceName, cachePitch, cacheRate)
                jobs.add(PartJob(chIdx, pIdx, text, partIndex, hash))
            }
        }

        val totalParts = jobs.size
        progressCallback?.onActualCounts(actualNewsCount, totalParts)
        Log.d(TAG, "Playlist: news=$actualNewsCount, parts=$totalParts, chapters=${prepared.size}")

        val baseUtteranceId = "tts_${System.currentTimeMillis()}"
        val cachedWavPaths = mutableSetOf<String>()

        // ФАЗА 2: Синтез частей (Параллельно для Edge)
        val partResults: List<PartResult?> = coroutineScope {
            if (isEdgeActive) {
                val sem = Semaphore(4)
                val doneCount = AtomicInteger(0)
                jobs.map { job ->
                    async(Dispatchers.IO) {
                        sem.withPermit {
                            val res = synthesizeOnePart(job, baseUtteranceId, voiceName, cachePitch, cacheRate, true, cachedWavPaths)
                            val n = doneCount.incrementAndGet()
                            synchronized(progressLock) {
                                progressCallback?.onProgress(n, totalParts)
                            }
                            res
                        }
                    }
                }.awaitAll()
            } else {
                jobs.mapIndexed { n, job ->
                    val res = synthesizeOnePart(job, baseUtteranceId, voiceName, cachePitch, cacheRate, false, cachedWavPaths)
                    progressCallback?.onProgress(n + 1, totalParts)
                    res
                }
            }
        }

        if (partResults.any { it == null }) {
            Log.e(TAG, "Some parts failed to synthesize")
            cleanupChapterFiles(emptyList(), cachedWavPaths)
            progressCallback?.onCompleted()
            return null
        }

        // ФАЗА 3: Сборка глав (Последовательно)
        //
        // [FFmpeg removed] Раньше здесь через ensureMatchingFormat части
        // приводились к общему формату (FFmpeg-ресемплинг), потому что Android
        // TTS и Edge могли давать разный sample rate. Теперь весь пайплайн
        // одноформатный: Edge запрашивается как raw-24khz-16bit-mono-pcm, а
        // выход Android TTS нормализуется к тому же формату через PcmResampler
        // прямо в synthesizePartToWav. Поэтому склейка идёт без перекодирования.
        var silenceFile: File? = null
        var baselineFormat: WavMeta? = null
        val chapterFiles = mutableListOf<File>()
        val newsFileIndices = mutableSetOf<Int>()

        val resultsByChapter = partResults.filterNotNull().groupBy { it.job.chapterIdx }

        prepared.forEachIndexed { idx, item ->
            val chapterIndex = chapterFiles.size
            if (!item.isHeader) {
                newsFileIndices.add(chapterIndex)
            }

            val chapterPartResults = resultsByChapter[idx]?.sortedBy { it.job.partInChapter } ?: run {
                cleanupChapterFiles(chapterFiles, cachedWavPaths)
                return null
            }

            val partWavs = mutableListOf<File>()

            for (res in chapterPartResults) {
                val wav = res.wav
                val meta = res.meta

                if (baselineFormat == null) {
                    baselineFormat = meta
                    if (pauseMs > 0) {
                        silenceFile = createSilenceWav(pauseMs, meta.sampleRate, meta.channels, meta.bitsPerSample)
                    }
                }

                // [FFmpeg removed] Формат уже унифицирован на этапе синтеза
                // (Edge raw-PCM и нормализованный Android TTS → 24kHz/mono/16bit),
                // поэтому ensureMatchingFormat больше не нужен — добавляем как есть.
                partWavs.add(wav)
            }

            if (idx != prepared.lastIndex && pauseMs > 0) {
                silenceFile?.let { partWavs.add(it) }
            }

            val chapterWav = File(context.cacheDir, "${baseUtteranceId}_ch${chapterIndex}.wav")
            val ok = if (partWavs.size == 1) {
                partWavs.first().copyTo(chapterWav, overwrite = true)
                true
            } else {
                AudioUtils.concatWavFiles(partWavs, chapterWav)
            }

            val silPath = silenceFile?.absolutePath
            partWavs.forEach { f ->
                if (f.exists() && f != chapterWav && f.absolutePath != silPath
                    && !cachedWavPaths.contains(f.absolutePath)
                ) {
                    try { f.delete() } catch (_: Exception) {}
                }
            }

            if (!ok || !chapterWav.exists() || chapterWav.length() == 0L) {
                Log.e(TAG, "Failed to concat chapter $chapterIndex")
                try { chapterWav.delete() } catch (_: Exception) {}
                cleanupChapterFiles(chapterFiles, cachedWavPaths)
                progressCallback?.onCompleted()
                return null
            }

            chapterFiles.add(chapterWav)
        }

        silenceFile?.delete()

        Log.d(TAG, "Playlist ready: ${chapterFiles.size} files, news=$actualNewsCount")

        val finalFileToMsgIndex = IntArray(chapterFiles.size)
        // Мы знаем, что chapterFiles строились в том же порядке, что и prepared, 
        // но на случай пропусков (хотя их быть не должно при успехе), сопоставляем заново.
        // На самом деле, в текущем коде chapterFiles.size == prepared.size если всё успешно.
        prepared.forEachIndexed { idx, item ->
            if (idx < chapterFiles.size) {
                finalFileToMsgIndex[idx] = item.originalIndex
            }
        }

        // Применяем лимиты кэша после завершения синтеза
        NewsCache.cleanup(context)

        return AudioPlaylist(chapterFiles, actualNewsCount, newsFileIndices, finalFileToMsgIndex)
    }

    private fun cleanupChapterFiles(files: List<File>, cachedPaths: Set<String>) {
        files.forEach {
            if (it.exists() && !cachedPaths.contains(it.absolutePath)) {
                try { it.delete() } catch (_: Exception) {}
            }
        }
    }

    private suspend fun synthesizePartToWav(text: String, partIndex: Int, baseUtteranceId: String): File? {
        return withTimeoutOrNull(15_000L) {
            suspendCancellableCoroutine { continuation ->
                val utteranceId = "${baseUtteranceId}_part_${partIndex}"
                val tempWavFile = File(context.cacheDir, "${utteranceId}.wav")
                val params = Bundle().apply {
                    putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                }
                tts?.setOnUtteranceProgressListener(null)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) {
                        if (id == utteranceId) {
                            tts?.setOnUtteranceProgressListener(null)
                            // [FFmpeg removed] Нормализуем выход системного TTS к
                            // 24kHz/mono/16bit. Формат synthesizeToFile зависит от
                            // движка устройства (22050/24000/48000, mono/stereo),
                            // а без единого формата склейка глав с Edge-кусками
                            // (или с тишиной) рассыпается. Раньше это делал
                            // FFmpeg-ресемплинг в ensureMatchingFormat.
                            val normalized = PcmResampler.normalizeToTarget(tempWavFile)
                            if (normalized == null) {
                                if (continuation.isActive) continuation.resume(null)
                                tempWavFile.delete()
                                return
                            }
                            // Если ресемплер создал новый файл — подменяем им
                            // исходный, чтобы дальше по пайплайну шёл один путь
                            // (и кэш/очистка работали по ожидаемому имени).
                            val finalFile = if (normalized.absolutePath != tempWavFile.absolutePath) {
                                try {
                                    tempWavFile.delete()
                                    normalized.copyTo(tempWavFile, overwrite = true)
                                    normalized.delete()
                                    tempWavFile
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to swap normalized file: ${e.message}")
                                    normalized
                                }
                            } else tempWavFile
                            if (continuation.isActive) continuation.resume(finalFile)
                        }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) {
                        if (id == utteranceId) {
                            tts?.setOnUtteranceProgressListener(null)
                            if (continuation.isActive) continuation.resume(null)
                            tempWavFile.delete()
                        }
                    }
                    override fun onError(id: String?, errorCode: Int) {
                        if (id == utteranceId) {
                            tts?.setOnUtteranceProgressListener(null)
                            if (continuation.isActive) continuation.resume(null)
                            tempWavFile.delete()
                        }
                    }
                })
                applyLanguageByText(text)
                val result = tts?.synthesizeToFile(text, params, tempWavFile, utteranceId)
                if (result == TextToSpeech.ERROR) {
                    tts?.setOnUtteranceProgressListener(null)
                    if (continuation.isActive) continuation.resume(null)
                    tempWavFile.delete()
                }
                continuation.invokeOnCancellation {
                    tts?.stop()
                    tts?.setOnUtteranceProgressListener(null)
                    tempWavFile.delete()
                }
            }
        } ?: run {
            Log.e(TAG, "synthesizePartToWav timeout for part $partIndex")
            null
        }
    }

    // ============ WAV utilities ============

    private data class WavMeta(val sampleRate: Int, val channels: Int, val bitsPerSample: Int, val durationMs: Long?)

    private fun readWavMeta(file: File): WavMeta? {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val head = ByteArray(12)
                raf.readFully(head)
                if (String(head, 0, 4) != "RIFF" || String(head, 8, 4) != "WAVE") return null

                var sampleRate = 0
                var channels = 0
                var bits = 0
                var dataSize = 0L
                var pos = 12L
                val len = raf.length()
                val chunkHeader = ByteArray(8)
                val buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)

                while (pos + 8 <= len) {
                    raf.seek(pos)
                    raf.readFully(chunkHeader)
                    val id = String(chunkHeader, 0, 4, Charsets.US_ASCII)

                    buffer.clear()
                    buffer.put(chunkHeader, 4, 4)
                    buffer.flip()
                    val sz = buffer.int.toLong() and 0xFFFFFFFFL

                    when (id) {
                        "fmt " -> {
                            val fmt = ByteArray(16)
                            raf.seek(pos + 8)
                            raf.readFully(fmt)
                            val b = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                            channels = b.getShort(2).toInt()
                            sampleRate = b.getInt(4)
                            bits = b.getShort(14).toInt()
                        }
                        "data" -> {
                            dataSize = sz
                        }
                    }
                    if (id == "data") break
                    pos += 8 + sz + (sz and 1L) // Чанки выровнены по 2 байта
                }

                if (sampleRate == 0 || channels == 0 || bits == 0) return null
                val byteRate = sampleRate * channels * bits / 8
                val durMs = if (byteRate > 0) (dataSize * 1000 / byteRate) else 0L
                WavMeta(sampleRate, channels, bits, durMs)
            }
        } catch (e: Exception) {
            Log.e(TAG, "readWavMeta failed for ${file.name}: ${e.message}")
            null
        }
    }

    private fun createSilenceWav(durationMs: Int, sampleRate: Int = 22050, channels: Int = 1, bitsPerSample: Int = 16): File {
        val numSamples = (durationMs.toLong() * sampleRate / 1000L).toInt()
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = numSamples * blockAlign
        val totalSize = 36 + dataSize
        val file = File(context.cacheDir, "silence_${durationMs}ms.wav")
        file.outputStream().use { os ->
            fun writeLE(value: Int, bytes: Int) { repeat(bytes) { i -> os.write((value shr (8 * i)) and 0xFF) } }
            os.write("RIFF".toByteArray()); writeLE(totalSize, 4)
            os.write("WAVE".toByteArray())
            os.write("fmt ".toByteArray()); writeLE(16, 4)
            writeLE(1, 2)
            writeLE(channels, 2); writeLE(sampleRate, 4)
            writeLE(sampleRate * channels * bitsPerSample / 8, 4)
            writeLE(blockAlign, 2); writeLE(bitsPerSample, 2)
            os.write("data".toByteArray()); writeLE(dataSize, 4)
            val buf = ByteArray(4096)
            var remaining = dataSize
            while (remaining > 0) { val w = minOf(remaining, buf.size); os.write(buf, 0, w); remaining -= w }
        }
        return file
    }

    fun shutdown() {
        ttsInitialized.set(false)

        // Разбудить ожидающих со статусом false
        val waiters = synchronized(initLock) {
            val copy = initWaiters.toList()
            initWaiters.clear()
            copy
        }
        waiters.forEach { cont ->
            if (cont.isActive) cont.resume(false)
        }

        tts?.stop()
        tts?.shutdown()
        tts = null
        voiceParametersApplied = false
        currentAppliedPitch = null
        currentAppliedRate = null
    }
}
