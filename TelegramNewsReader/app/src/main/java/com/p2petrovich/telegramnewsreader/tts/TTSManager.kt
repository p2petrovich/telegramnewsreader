package com.p2petrovich.telegramnewsreader.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.p2petrovich.telegramnewsreader.models.VoiceEntry
import com.p2petrovich.telegramnewsreader.models.VoiceMappings
import com.p2petrovich.telegramnewsreader.services.NewsService
import com.p2petrovich.telegramnewsreader.utils.AudioUtils
import com.p2petrovich.telegramnewsreader.utils.NewsCache
import com.p2petrovich.telegramnewsreader.utils.PreferenceManager
import com.p2petrovich.telegramnewsreader.utils.TextProcessor
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
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
        }
    }
}

class TTSManager(private val context: Context) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "TTSManager"

        // Количество попыток синтеза через Edge перед fallback на Android TTS.
        // Сетевые сбои WebSocket — норма, обычно вторая попытка проходит.
        private const val EDGE_RETRY_ATTEMPTS = 2
        private const val EDGE_RETRY_DELAY_MS = 500L
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
            tts?.setLanguage(Locale("ru"))
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
        val russianVoices = availableVoices?.filter { it.locale.language == "ru" }
        val savedVoiceName = PreferenceManager.getTtsVoiceName(context)
        val matchedVoice = availableVoices?.find { it.name == savedVoiceName }

        if (matchedVoice != null) {
            tts?.language = matchedVoice.locale
            tts?.voice = matchedVoice
        } else if (!russianVoices.isNullOrEmpty()) {
            tts?.language = russianVoices.first().locale
            tts?.voice = russianVoices.first()
        }

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
        return VoiceMappings.mapVoices(systemVoices)
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

    fun speak(text: String) {
        if (ttsInitialized.get()) {
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
        val newsFileIndices: Set<Int> = emptySet()
    )

    private fun stripHeaderMarker(text: String): String {
        return text.replace("\u200B", "").replace("\u200C", "").trim()
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
                delay(EDGE_RETRY_DELAY_MS)
            }
        }
        return false
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
                val cleaned = TextProcessor.cleanForTts(raw)
                val deduped = TextProcessor.deduplicateLines(cleaned)
                val expanded = TextProcessor.expandAbbreviations(deduped)
                val normalized = TextProcessor.normalizeNumbers(expanded)
                val formatted = TextProcessor.formatForIntonation(normalized)
                val finalText = TextProcessor.formatForSpeech(formatted)
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
        val totalParts = prepared.sumOf { TextProcessor.splitByParagraphs(it.textForSplitting, 2800).size }

        progressCallback?.onActualCounts(actualNewsCount, totalParts)
        Log.d(TAG, "Playlist: news=$actualNewsCount, parts=$totalParts, chapters=${prepared.size}")

        var processedParts = 0
        val baseUtteranceId = "tts_${System.currentTimeMillis()}"
        val cachedWavPaths = mutableSetOf<String>()
        var silenceFile: File? = null
        var baselineFormat: WavMeta? = null

        val chapterFiles = mutableListOf<File>()
        val newsFileIndices = mutableSetOf<Int>()

        prepared.forEachIndexed { idx, item ->
            val chapterIndex = chapterFiles.size

            if (!item.isHeader) {
                newsFileIndices.add(chapterIndex)
            }

            val parts = TextProcessor.splitByParagraphs(item.textForSplitting, 2800)
            val partWavs = mutableListOf<File>()

            for (i in parts.indices) {
                val partText = parts[i]
                val partIndex = ((item.originalIndex + 1) * 1000 + (i + 1))

                val isEdgeActive = edgeProvider != null
                val hash = if (isEdgeActive) {
                    val ev = PreferenceManager.getEdgeVoice(context)
                    val er = PreferenceManager.getEdgeRate(context)
                    val ep = PreferenceManager.getEdgePitch(context)
                    NewsCache.messageHash(partText, "edge:$ev", ep.toFloat(), er.toFloat())
                } else {
                    NewsCache.messageHash(partText, voiceName, cachePitch, cacheRate)
                }

                val cachedFile = NewsCache.findCachedWav(context, hash)

                var wav: File? = null
                // Флаг для отслеживания, каким движком фактически синтезирована часть.
                // Если ожидали Edge, но упали на Android — в кэш не пишем,
                // иначе при следующем запуске эта фраза подтянется чужим голосом.
                var actuallyUsedEdge = false

                if (cachedFile != null) {
                    wav = cachedFile
                    cachedWavPaths.add(cachedFile.absolutePath)
                } else {
                    val edge = edgeProvider
                    if (edge != null) {
                        val tmp = File(context.cacheDir, "${baseUtteranceId}_part_${partIndex}.wav")
                        // Внешнего withTimeoutOrNull больше нет — EdgeTtsProvider.synthesizeToWav
                        // уже имеет внутренний таймаут 60с. Двойной таймаут резал длинные
                        // фразы на полпути и отправлял их в Android-fallback (== другой голос).
                        val ok = trySynthesizeEdge(edge, partText, tmp, partIndex)
                        if (ok) {
                            wav = tmp
                            actuallyUsedEdge = true
                        } else {
                            tmp.delete()
                            Log.w(TAG, "Edge TTS failed for part $partIndex after $EDGE_RETRY_ATTEMPTS attempts, falling back to Android TTS")
                        }
                    }

                    if (wav == null) {
                        // Гарантируем, что для Android TTS установлен правильный голос перед синтезом части
                        if (voiceName != "default") {
                            setVoiceByName(voiceName)
                        }
                        wav = synthesizePartToWav(partText, partIndex, baseUtteranceId)
                    }

                    if (wav == null || !wav.exists() || wav.length() == 0L) {
                        Log.e(TAG, "Failed to synthesize part $i of chapter ${idx + 1}")
                        cleanupChapterFiles(chapterFiles, cachedWavPaths)
                        progressCallback?.onCompleted()
                        return null
                    }

                    // Кэшируем только если фактический движок совпал с ожидаемым.
                    // Если ожидался Edge, но в итоге сработал Android-fallback — не сохраняем,
                    // чтобы хэш "edge:..." не указывал на Android-озвучку.
                    val expectedEdge = isEdgeActive
                    if (expectedEdge == actuallyUsedEdge) {
                        NewsCache.saveWavToCache(context, hash, wav)
                    } else {
                        Log.w(TAG, "Skip caching part $partIndex: expectedEdge=$expectedEdge, actualEdge=$actuallyUsedEdge")
                    }
                }

                val meta = readWavMeta(wav)
                if (meta == null) {
                    cleanupChapterFiles(chapterFiles, cachedWavPaths)
                    progressCallback?.onCompleted()
                    return null
                }

                if (baselineFormat == null) {
                    baselineFormat = meta
                    if (pauseMs > 0) {
                        silenceFile = createSilenceWav(pauseMs, meta.sampleRate, meta.channels, meta.bitsPerSample)
                    }
                }

                val currentBaseline = baselineFormat
                val usedWav = if (currentBaseline != null) {
                    ensureMatchingFormat(wav, meta, currentBaseline)
                } else null

                if (usedWav == null) {
                    cleanupChapterFiles(chapterFiles, cachedWavPaths)
                    progressCallback?.onCompleted()
                    return null
                }

                if (usedWav.absolutePath != wav.absolutePath && cachedWavPaths.contains(wav.absolutePath)) {
                    cachedWavPaths.add(usedWav.absolutePath)
                }

                partWavs.add(usedWav)

                processedParts++
                if (totalParts > 0) {
                    progressCallback?.onProgress(processedParts, totalParts)
                }
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

        NewsCache.cleanup(context)
        progressCallback?.onCompleted()

        return AudioPlaylist(chapterFiles, actualNewsCount, newsFileIndices)
    }

    private fun cleanupChapterFiles(files: List<File>, cachedPaths: Set<String>) {
        files.forEach {
            if (it.exists() && !cachedPaths.contains(it.absolutePath)) {
                try { it.delete() } catch (_: Exception) {}
            }
        }
    }

    private fun ensureMatchingFormat(wav: File, meta: WavMeta, baseline: WavMeta): File? {
        val matches = meta.sampleRate == baseline.sampleRate &&
                meta.channels == baseline.channels &&
                meta.bitsPerSample == baseline.bitsPerSample
        if (matches) return wav

        val fmt = when (baseline.bitsPerSample) {
            8 -> "u8"; 16 -> "s16"; 24 -> "s32"; 32 -> "s32"; else -> "s16"
        }
        val out = File(wav.parentFile, wav.nameWithoutExtension + "_resampled.wav")
        val cmd = arrayOf(
            "-y", "-i", wav.absolutePath,
            "-ar", baseline.sampleRate.toString(),
            "-ac", baseline.channels.toString(),
            "-sample_fmt", fmt,
            out.absolutePath
        )
        val session = FFmpegKit.executeWithArguments(cmd)
        return if (ReturnCode.isSuccess(session.returnCode) && out.exists() && out.length() > 0) out
        else { if (out.exists()) out.delete(); null }
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
                            if (continuation.isActive) continuation.resume(tempWavFile)
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
                val header = ByteArray(44)
                raf.readFully(header)
                val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                if (String(header.copyOfRange(0, 4)) != "RIFF" || String(header.copyOfRange(8, 12)) != "WAVE") return null
                val channels = buf.getShort(22).toInt()
                val sampleRate = buf.getInt(24)
                val bitsPerSample = buf.getShort(34).toInt()
                val byteRate = sampleRate * channels * bitsPerSample / 8
                val dataSize = buf.getInt(40)
                val durMs = if (byteRate > 0) ((dataSize.toDouble() / byteRate) * 1000).toLong() else 0L
                WavMeta(sampleRate, channels, bitsPerSample, durMs)
            }
        } catch (e: Exception) { null }
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
