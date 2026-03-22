package com.p2petrovich.telegramnewsreader.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.p2petrovich.telegramnewsreader.models.VoiceEntry
import com.p2petrovich.telegramnewsreader.models.VoiceMappings
import com.p2petrovich.telegramnewsreader.service.NewsService
import com.p2petrovich.telegramnewsreader.utils.AudioUtils
import com.p2petrovich.telegramnewsreader.utils.NewsCache
import com.p2petrovich.telegramnewsreader.utils.PreferenceManager
import com.p2petrovich.telegramnewsreader.utils.TextProcessor
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation

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
    }

    private var tts: TextToSpeech? = null
    private var ttsInitialized = AtomicBoolean(false)
    private var initializationContinuation: CancellableContinuation<Boolean>? = null

    private var voiceParametersApplied = false
    private var currentAppliedPitch: Float? = null
    private var currentAppliedRate: Float? = null

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ttsInitialized.set(true)
            val result = tts?.setLanguage(Locale("ru"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.US)
            }
            applySavedVoice()
            initializationContinuation?.resume(true)
        } else {
            ttsInitialized.set(false)
            Log.e(TAG, "TTS init failed, status=$status")
            initializationContinuation?.resume(false)
        }
        initializationContinuation = null
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

    private suspend fun ensureTtsInitialized(): Boolean {
        if (ttsInitialized.get()) return true
        if (tts != null && initializationContinuation == null) {
            return suspendCancellableCoroutine { continuation ->
                initializationContinuation = continuation
                continuation.invokeOnCancellation { initializationContinuation = null }
            }
        }
        return ttsInitialized.get()
    }

    fun getAvailableVoiceEntries(): List<VoiceEntry> {
        val systemVoices = tts?.voices?.filter {
            it.locale.language == "ru" || it.locale.language == "en"
        }?.toList() ?: emptyList()
        return VoiceMappings.mapVoices(systemVoices)
    }

    fun setVoiceByEntry(voiceEntry: VoiceEntry) {
        setVoiceByName(voiceEntry.systemName)
    }

    fun setVoiceByName(voiceName: String) {
        val voice = tts?.voices?.firstOrNull { it.name == voiceName }
        voice?.let {
            tts?.language = it.locale
            tts?.voice = it
            PreferenceManager.saveTtsVoiceName(context, voiceName)
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

    fun updatePitchForVoice(voiceName: String, pitch: Float) {
        PreferenceManager.saveTtsPitchForVoice(context, voiceName, pitch)
    }

    fun updateRateForVoice(voiceName: String, rate: Float) {
        PreferenceManager.saveTtsRateForVoice(context, voiceName, rate)
    }

    fun getPitchForVoice(voiceName: String): Float =
        PreferenceManager.getTtsPitchForVoice(context, voiceName)

    fun getRateForVoice(voiceName: String): Float =
        PreferenceManager.getTtsRateForVoice(context, voiceName)

    // ============ Audio synthesis ============

    interface SynthesisProgressCallback {
        fun onProgress(current: Int, total: Int)
        fun onStarted(messageCount: Int)
        fun onCompleted()
        fun onActualCounts(newsCount: Int, partsCount: Int) {}
    }

    data class AudioWithChapters(
        val file: File,
        val chaptersMs: List<Long>,
        val actualNewsCount: Int = 0
    )

    /**
     * Убирает маркер заголовка канала и возвращает чистое название для озвучки.
     * Например: "\u200B\u200C\u200BНовости:" -> "Новости"
     */
    private fun stripHeaderMarker(text: String): String {
        return text
            .replace("\u200B", "")
            .replace("\u200C", "")
            .trim()
    }


    suspend fun convertToAudioWithChaptersWithCallback(
        texts: List<String>,
        pauseMs: Int = 1000,
        progressCallback: SynthesisProgressCallback?
    ): AudioWithChapters? {
        if (!ensureTtsInitialized() || tts == null) return null

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
                // Для заголовка: убираем маркер и готовим чистый текст для озвучки
                val cleanTitle = stripHeaderMarker(raw)
                if (cleanTitle.isNotBlank()) {
                    prepared += PreparedNews(newsIndex, cleanTitle, true)
                }
            } else {
                val cleaned = TextProcessor.cleanForTts(raw)
                val deduped = TextProcessor.deduplicateLines(cleaned)
                val normalized = TextProcessor.normalizeNumbers(deduped)
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
        Log.d(TAG, "TTS actual: news=$actualNewsCount, parts=$totalParts, prepared=${prepared.size}, headers=${prepared.count { it.isHeader }}")

        var processedParts = 0

        val baseUtteranceId = "tts_${System.currentTimeMillis()}"
        val wavFiles = mutableListOf<File>()
        val cachedWavPaths = mutableSetOf<String>()
        var silenceFile: File? = null
        var baselineFormat: WavMeta? = null

        val newsOnlyChaptersMs = mutableListOf<Long>()
        var offsetMs = 0L

        var cachedPartsCount = 0
        var synthesizedPartsCount = 0

        prepared.forEachIndexed { idx, item ->
            if (!item.isHeader) {
                newsOnlyChaptersMs.add(offsetMs)
            }

            val parts = TextProcessor.splitByParagraphs(item.textForSplitting, 2800)

            for (i in parts.indices) {
                val partText = parts[i]
                val partIndex = ((item.originalIndex + 1) * 1000 + (i + 1))

                val hash = NewsCache.messageHash(partText, voiceName, cachePitch, cacheRate)
                val cachedFile = NewsCache.findCachedWav(context, hash)

                val wav: File?

                if (cachedFile != null) {
                    wav = cachedFile
                    cachedWavPaths.add(cachedFile.absolutePath)
                    cachedPartsCount++
                    Log.d(TAG, "Cache hit for part $partIndex (hash=$hash)")
                } else {
                    wav = synthesizePartToWav(partText, partIndex, baseUtteranceId)

                    if (wav == null || !wav.exists() || wav.length() == 0L) {
                        Log.e(TAG, "Failed to synthesize part $i of news ${idx + 1}")
                        cleanupFiles(wavFiles, silenceFile, cachedWavPaths)
                        progressCallback?.onCompleted()
                        return null
                    }

                    NewsCache.saveWavToCache(context, hash, wav)
                    synthesizedPartsCount++
                    Log.d(TAG, "Synthesized and cached part $partIndex (hash=$hash)")
                }

                val meta = readWavMeta(wav)
                if (meta == null) {
                    cleanupFiles(wavFiles, silenceFile, cachedWavPaths)
                    progressCallback?.onCompleted()
                    return null
                }

                if (baselineFormat == null) {
                    baselineFormat = meta
                    if (pauseMs > 0) {
                        silenceFile = createSilenceWav(pauseMs, meta.sampleRate, meta.channels, meta.bitsPerSample)
                    }
                }

                val usedWav = ensureMatchingFormat(wav, meta, baselineFormat!!)
                if (usedWav == null) {
                    cleanupFiles(wavFiles, silenceFile, cachedWavPaths)
                    progressCallback?.onCompleted()
                    return null
                }

                if (usedWav.absolutePath != wav.absolutePath && cachedWavPaths.contains(wav.absolutePath)) {
                    cachedWavPaths.add(usedWav.absolutePath)
                }

                wavFiles.add(usedWav)
                offsetMs += readWavDurationMs(usedWav) ?: meta.durationMs ?: 0L

                processedParts++
                if (totalParts > 0) {
                    progressCallback?.onProgress(processedParts, totalParts)
                }
            }

            if (idx != prepared.lastIndex && pauseMs > 0 && silenceFile != null) {
                wavFiles.add(silenceFile!!)
                offsetMs += pauseMs
            }
        }

        Log.d(TAG, "Synthesis complete: $cachedPartsCount from cache, $synthesizedPartsCount synthesized, $totalParts total parts")
        Log.d(TAG, "NewsOnly chapters: ${newsOnlyChaptersMs.size}, actualNewsCount=$actualNewsCount")

        progressCallback?.onProgress(processedParts, totalParts)

        if (wavFiles.isEmpty()) {
            progressCallback?.onCompleted()
            return null
        }

        val combinedWav = File(context.cacheDir, "${baseUtteranceId}_combined.wav")
        val concatOk = if (wavFiles.size == 1) {
            wavFiles.first().copyTo(combinedWav, overwrite = true)
            true
        } else {
            AudioUtils.concatWavFiles(wavFiles, combinedWav)
        }

        val silencePath = silenceFile?.absolutePath
        wavFiles.forEach { f ->
            if (f.exists()
                && f != combinedWav
                && f.absolutePath != silencePath
                && !cachedWavPaths.contains(f.absolutePath)
            ) {
                try { f.delete() } catch (_: Exception) {}
            }
        }
        silenceFile?.delete()

        if (!concatOk || !combinedWav.exists() || combinedWav.length() == 0L) {
            try { combinedWav.delete() } catch (_: Exception) {}
            progressCallback?.onCompleted()
            return null
        }

        val mp3File = convertToMp3(combinedWav)
        try { combinedWav.delete() } catch (_: Exception) {}

        NewsCache.cleanup(context)

        progressCallback?.onCompleted()

        return if (mp3File != null) AudioWithChapters(mp3File, newsOnlyChaptersMs, actualNewsCount) else null
    }

    private fun cleanupFiles(wavFiles: List<File>, silenceFile: File?, cachedPaths: Set<String> = emptySet()) {
        wavFiles.forEach {
            if (it.exists() && !cachedPaths.contains(it.absolutePath)) {
                try { it.delete() } catch (_: Exception) {}
            }
        }
        silenceFile?.delete()
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
        return suspendCancellableCoroutine { continuation ->
            val utteranceId = "${baseUtteranceId}_part_${partIndex}"
            val tempWavFile = File(context.cacheDir, "${utteranceId}.wav")
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            }

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

    private fun readWavDurationMs(file: File): Long? = readWavMeta(file)?.durationMs

    private fun createSilenceWav(durationMs: Int, sampleRate: Int = 22050, channels: Int = 1, bitsPerSample: Int = 16): File {
        val numSamples = (durationMs.toLong() * sampleRate / 1000L).toInt()
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = numSamples * blockAlign
        val totalSize = 36 + dataSize

        val file = File(context.cacheDir, "silence_${durationMs}ms.wav")
        file.outputStream().use { os ->
            fun writeLE(value: Int, bytes: Int) {
                repeat(bytes) { i -> os.write((value shr (8 * i)) and 0xFF) }
            }
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
            while (remaining > 0) {
                val writeNow = minOf(remaining, buf.size)
                os.write(buf, 0, writeNow)
                remaining -= writeNow
            }
        }
        return file
    }

    private fun convertToMp3(wavFile: File): File? {
        if (!wavFile.exists() || wavFile.length() == 0L) return null

        val mp3File = File(wavFile.parentFile, wavFile.nameWithoutExtension + ".mp3")
        val cmd = arrayOf("-y", "-i", wavFile.absolutePath, "-acodec", "libmp3lame", "-b:a", "64k", "-vn", mp3File.absolutePath)
        val session = FFmpegKit.executeWithArguments(cmd)

        return if (ReturnCode.isSuccess(session.returnCode) && mp3File.exists() && mp3File.length() > 0) {
            mp3File
        } else {
            if (mp3File.exists()) mp3File.delete()
            null
        }
    }

    fun shutdown() {
        ttsInitialized.set(false)
        initializationContinuation?.cancel()
        initializationContinuation = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        voiceParametersApplied = false
        currentAppliedPitch = null
        currentAppliedRate = null
    }
}
