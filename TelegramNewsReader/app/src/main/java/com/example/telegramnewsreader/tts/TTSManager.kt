package com.example.telegramnewsreader.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import com.example.telegramnewsreader.utils.PreferenceManager
import android.speech.tts.Voice

class TTSManager(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isMale = true
    private var ttsInitialized = AtomicBoolean(false)
    private var initializationContinuation: CancellableContinuation<Boolean>? = null

    init {
        tts = TextToSpeech(context, this, findBestEngine())
    }

    private fun findBestEngine(): String? {
        tts?.engines?.let { engines ->
            val preferredEngines = listOf(
                "com.google.android.tts",
                "com.samsung.SMT"
            )
            for (preferredEngine in preferredEngines) {
                if (engines.any { it.name == preferredEngine }) {
                    Log.d("TTSManager", "Using preferred TTS engine: $preferredEngine")
                    return preferredEngine
                }
            }
            Log.d("TTSManager", "Preferred TTS engine not found, using system default.")
        }
        return null
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ttsInitialized.set(true)
            Log.d("TTSManager", "TTS Initialized successfully.")
            var result = tts?.setLanguage(Locale("ru"))

            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w("TTSManager", "Russian language not supported, falling back to US English.")
                tts?.setLanguage(Locale.US)
            }

            // Применяем сохраненный голос сразу после инициализации
            applySavedVoice()

            initializationContinuation?.resume(true)
        } else {
            ttsInitialized.set(false)
            Log.e("TTSManager", "TTS Initialization Failed! Status: $status")
            initializationContinuation?.resume(false)
        }
        initializationContinuation = null
    }

    // 🔥 Новый метод для применения сохраненного голоса
    private fun applySavedVoice() {
        val availableVoices = tts?.voices
        val russianVoices = availableVoices?.filter { it.locale.language == "ru" }
        val savedVoiceName = PreferenceManager.getTtsVoiceName(context)
        val matchedVoice = availableVoices?.find { it.name == savedVoiceName }

        russianVoices?.forEach { voice ->
            Log.d(
                "TTSManager",
                "🔍 Voice found: name=${voice.name}, locale=${voice.locale}, quality=${voice.quality}"
            )
        }

        if (matchedVoice != null) {
            Log.d("TTSManager", "🔊 Применяем сохранённый голос: ${matchedVoice.name}")
            tts?.language = matchedVoice.locale
            tts?.voice = matchedVoice
        } else if (!russianVoices.isNullOrEmpty()) {
            Log.d("TTSManager", "🟡 Голос не найден, выбираем первый доступный: ${russianVoices.first().name}")
            tts?.language = russianVoices.first().locale
            tts?.voice = russianVoices.first()
        } else {
            Log.w("TTSManager", "⚠️ Нет русских голосов, используется голос по умолчанию.")
        }

        // Настройки тембра и скорости
        tts?.setPitch(if (isMale) 0.8f else 1.2f)
        tts?.setSpeechRate(1.0f)
    }

    private suspend fun ensureTtsInitialized(): Boolean {
        if (ttsInitialized.get()) {
            return true
        }
        if (tts != null && initializationContinuation == null) {
            return suspendCancellableCoroutine { continuation ->
                initializationContinuation = continuation
                continuation.invokeOnCancellation {
                    initializationContinuation = null
                }
            }
        }
        return ttsInitialized.get()
    }

    fun setVoiceGender(isMale: Boolean) {
        this.isMale = isMale
        if (ttsInitialized.get()) {
            tts?.setPitch(if (isMale) 0.8f else 1.2f)
        }
    }

    fun getAvailableVoices(): List<Voice> {
        return tts?.voices?.filter {
            (it.locale.language == "ru" || it.locale.language == "en") && !it.name.contains("network")
        }?.toList() ?: emptyList()
    }

    // 🔥 Обновленный метод - применяет голос перед синтезом
    suspend fun convertToAudio(texts: List<String>): File? {
        if (!ensureTtsInitialized() || tts == null) {
            Log.e("TTSManager", "TTS not initialized. Cannot convert to audio.")
            return null
        }

        // 🔥 ВАЖНО: Применяем сохраненный голос перед синтезом
        val savedVoiceName = PreferenceManager.getTtsVoiceName(context)
        if (savedVoiceName != null) {
            val savedVoice = tts?.voices?.find { it.name == savedVoiceName }
            if (savedVoice != null) {
                Log.d("TTSManager", "🔊 convertToAudio(): применяем сохранённый голос ${savedVoice.name}")
                tts?.language = savedVoice.locale
                tts?.voice = savedVoice
            } else {
                Log.w("TTSManager", "❗ convertToAudio(): сохранённый голос $savedVoiceName не найден")
            }
        }

        return suspendCancellableCoroutine { continuation ->
            val combinedText = texts.joinToString(" ")
            val words = combinedText.split(Regex("\\s+"))
            val limitedText = if (words.size > 4500) {
                Log.w("TTSManager", "Text too long (${words.size} words), truncating to 4500 words.")
                words.take(4500).joinToString(" ")
            } else {
                combinedText
            }

            if (limitedText.isBlank()) {
                Log.w("TTSManager", "Cannot synthesize empty text.")
                if (continuation.isActive) continuation.resume(null)
                return@suspendCancellableCoroutine
            }

            val utteranceId = "ttsAudioConversion_${System.currentTimeMillis()}"
            val tempWavFile = File(context.cacheDir, "${utteranceId}.wav")
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            }

            val listener = object : UtteranceProgressListener() {
                override fun onStart(id: String?) {
                    if (id == utteranceId) {
                        Log.d("TTSManager", "TTS synthesis started for $utteranceId")
                    }
                }

                override fun onDone(id: String?) {
                    if (id == utteranceId) {
                        Log.d("TTSManager", "TTS synthesis done for $utteranceId. Converting to MP3.")
                        val mp3File = convertToMp3(tempWavFile)
                        if (continuation.isActive) {
                            continuation.resume(mp3File)
                        }
                        tempWavFile.delete()
                    }
                }

                @Deprecated("deprecated in API level 21")
                override fun onError(id: String?) {
                    if (id == utteranceId) {
                        Log.e("TTSManager", "TTS synthesis error (legacy) for $utteranceId")
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                        tempWavFile.delete()
                    }
                }

                override fun onError(id: String?, errorCode: Int) {
                    if (id == utteranceId) {
                        Log.e("TTSManager", "TTS synthesis error for $utteranceId. Error code: $errorCode")
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                        tempWavFile.delete()
                    }
                }

                override fun onStop(id: String?, interrupted: Boolean) {
                    if (id == utteranceId && interrupted) {
                        Log.w("TTSManager", "TTS synthesis stopped (interrupted) for $utteranceId")
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                        tempWavFile.delete()
                    }
                }
            }
            tts?.setOnUtteranceProgressListener(listener)

            Log.d("TTSManager", "Starting TTS synthesis to file: ${tempWavFile.absolutePath}")
            val result = tts?.synthesizeToFile(limitedText, params, tempWavFile, utteranceId)

            if (result == TextToSpeech.ERROR) {
                Log.e("TTSManager", "tts.synthesizeToFile immediately returned ERROR for $utteranceId.")
                tts?.setOnUtteranceProgressListener(null)
                if (continuation.isActive) {
                    continuation.resume(null)
                }
                tempWavFile.delete()
            } else if (result == TextToSpeech.SUCCESS) {
                Log.d("TTSManager", "tts.synthesizeToFile call successful for $utteranceId")
            }

            continuation.invokeOnCancellation {
                Log.d("TTSManager", "TTS Coroutine cancelled for $utteranceId")
                tts?.stop()
                tts?.setOnUtteranceProgressListener(null)
                tempWavFile.delete()
            }
        }
    }

    private fun convertToMp3(wavFile: File): File? {
        if (!wavFile.exists() || wavFile.length() == 0L) {
            Log.e("TTSManager", "WAV file is missing or empty: ${wavFile.absolutePath}")
            return null
        }
        val mp3FileName = wavFile.nameWithoutExtension + ".mp3"
        val mp3File = File(wavFile.parentFile, mp3FileName)

        Log.d("TTSManager", "Converting ${wavFile.name} to ${mp3File.name}")
        try {
            val cmd = arrayOf("-y", "-i", wavFile.absolutePath, "-acodec", "libmp3lame", "-b:a", "64k", "-vn", mp3File.absolutePath)
            val session = FFmpegKit.executeWithArguments(cmd)

            if (ReturnCode.isSuccess(session.returnCode)) {
                if (mp3File.exists() && mp3File.length() > 0) {
                    Log.d("TTSManager", "MP3 conversion successful: ${mp3File.absolutePath}")
                    return mp3File
                } else {
                    Log.e("TTSManager", "MP3 conversion reported success, but file is missing or empty")
                    return null
                }
            } else {
                Log.e("TTSManager", "MP3 conversion failed. Return code: ${session.returnCode}")
                if (mp3File.exists()) mp3File.delete()
                return null
            }
        } catch (e: Exception) {
            Log.e("TTSManager", "Exception during MP3 conversion", e)
            return null
        } finally {
            if (wavFile.exists()) {
                wavFile.delete()
                Log.d("TTSManager", "Deleted temporary WAV file: ${wavFile.name}")
            }
        }
    }

    fun shutdown() {
        Log.d("TTSManager", "Shutting down TTS engine.")
        ttsInitialized.set(false)
        initializationContinuation?.cancel()
        initializationContinuation = null
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    // 🔥 Обновленный метод - сохраняет настройки и обновляет текущий голос
    fun setVoiceByName(voiceName: String) {
        val voice = tts?.voices?.firstOrNull { it.name == voiceName }
        voice?.let {
            Log.d("TTSManager", "🔄 Применяем выбранный голос: ${voice.name}")
            tts?.language = voice.locale
            tts?.voice = voice

            // Сохраняем в настройки
            PreferenceManager.saveTtsVoiceName(context, voiceName)
            Log.d("TTSManager", "💾 Голос сохранен в настройки: $voiceName")
        } ?: run {
            Log.w("TTSManager", "❗ Голос '$voiceName' не найден среди доступных")
        }
    }

    fun speak(text: String) {
        if (ttsInitialized.get()) {
            // 💡 Устанавливаем голос каждый раз при воспроизведении
            val savedVoiceName = PreferenceManager.getTtsVoiceName(context)
            if (savedVoiceName != null) {
                val selectedVoice = tts?.voices?.find { it.name == savedVoiceName }
                if (selectedVoice != null) {
                    Log.d("TTSManager", "🔁 speak(): применяем голос ${selectedVoice.name}")
                    tts?.language = selectedVoice.locale
                    tts?.voice = selectedVoice
                } else {
                    Log.w("TTSManager", "❗ speak(): голос $savedVoiceName не найден среди доступных")
                }
            }

            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "TTS_SAMPLE")
        } else {
            Log.w("TTSManager", "⚠️ TTS не инициализирован, speak пропущен.")
        }
    }

    // 🔥 Новый метод для принудительного обновления голоса
    fun refreshVoice() {
        if (ttsInitialized.get()) {
            applySavedVoice()
            Log.d("TTSManager", "🔄 Голос принудительно обновлен")
        }
    }
}