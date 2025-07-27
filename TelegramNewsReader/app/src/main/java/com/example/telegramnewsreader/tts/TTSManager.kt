package com.example.telegramnewsreader.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log // Рекомендуется для логирования
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
    private var ttsInitialized = AtomicBoolean(false) // Для отслеживания статуса инициализации
    private var initializationContinuation: CancellableContinuation<Boolean>? = null // Для ожидания инициализации

    init {
        // Запускаем инициализацию TTS.
        // Если TTS инициализируется асинхронно и может быть не готов сразу.
        tts = TextToSpeech(context, this, findBestEngine())
    }

    // Попытка найти более качественный движок, если стандартный не очень
    private fun findBestEngine(): String? {
        tts?.engines?.let { engines ->
            // Приоритеты движков (можно настроить)
            val preferredEngines = listOf(
                "com.google.android.tts", // Google TTS
                "com.samsung.SMT"         // Samsung TTS (если есть)
                // Добавьте другие, если знаете их package name
            )
            for (preferredEngine in preferredEngines) {
                if (engines.any { it.name == preferredEngine }) {
                    Log.d("TTSManager", "Using preferred TTS engine: $preferredEngine")
                    return preferredEngine
                }
            }
            // Если предпочтительные не найдены, вернется null, и система выберет движок по умолчанию
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
                Log.w("TTSManager", "Russian language not supported or missing data, falling back to US English.")
                tts?.setLanguage(Locale.US)
            }

            val availableVoices = tts?.voices
            val russianVoices = availableVoices?.filter { it.locale.language == "ru" }
            val savedVoiceName = PreferenceManager.getTtsVoiceName(context)
            val matchedVoice = availableVoices?.find { it.name == savedVoiceName }



            // 🔍 Покажем все голоса в лог
            russianVoices?.forEach { voice ->
                Log.d(
                    "TTSManager",
                    "🔍 Voice found: name=${voice.name}, locale=${voice.locale}, quality=${voice.quality}, latency=${voice.latency}, isNetwork=${voice.isNetworkConnectionRequired}, genderHint=${if ("fem" in voice.name.lowercase()) "Женский" else "Мужской/неопределён"}"
                )
            }

            if (matchedVoice != null) {
                Log.d("TTSManager", "🔊 Применяем сохранённый голос: ${matchedVoice.name}")
                tts?.voice = matchedVoice
            } else if (!russianVoices.isNullOrEmpty()) {
                Log.d("TTSManager", "🟡 Голос не найден, выбираем первый доступный: ${russianVoices.first().name}")
                tts?.voice = russianVoices.first()
            } else {
                Log.w("TTSManager", "⚠️ Нет русских голосов, используется голос по умолчанию.")
            }


            // Настройки тембра и скорости
            tts?.setPitch(if (isMale) 0.8f else 1.2f)
            tts?.setSpeechRate(1.0f)

            initializationContinuation?.resume(true)
        } else {
            ttsInitialized.set(false)
            Log.e("TTSManager", "TTS Initialization Failed! Status: $status")
            initializationContinuation?.resume(false)
        }
        initializationContinuation = null
    }



    private suspend fun ensureTtsInitialized(): Boolean {
        if (ttsInitialized.get()) {
            return true
        }
        // Если TTS еще не инициализирован, но процесс инициализации уже запущен (tts != null)
        // и мы еще не пытались ждать (initializationContinuation == null)
        if (tts != null && initializationContinuation == null) {
            return suspendCancellableCoroutine { continuation ->
                initializationContinuation = continuation
                // Если onInit уже был вызван (с ошибкой или успехом) до этого момента,
                // то resume произойдет сразу из onInit.
                // Если TTS движок не смог запуститься (tts остался null), эта ветка не выполнится.
                continuation.invokeOnCancellation {
                    initializationContinuation = null // Очистка при отмене
                }
            }
        }
        return ttsInitialized.get() // Возвращаем текущий статус
    }


    fun setVoiceGender(isMale: Boolean) {
        this.isMale = isMale
        if (ttsInitialized.get()) { // Применяем только если TTS инициализирован
            tts?.setPitch(if (isMale) 0.8f else 1.2f)
        }
    }
    fun getAvailableVoices(): List<Voice> {
        return tts?.voices?.filter {
            it.locale.language == "ru" || it.locale.language == "en"
        }?.toList() ?: emptyList()
    }

    suspend fun convertToAudio(texts: List<String>): File? {
        if (!ensureTtsInitialized() || tts == null) {
            Log.e("TTSManager", "TTS not initialized or failed to initialize. Cannot convert to audio.")
            return null
        }

        return suspendCancellableCoroutine { continuation ->
            val combinedText = texts.joinToString(" ")
            // Ограничение по количеству слов (4000-5000 слов ~ лимит для synthesizeToFile)
            val words = combinedText.split(Regex("\\s+"))
            val limitedText = if (words.size > 4500) {
                Log.w("TTSManager", "Text too long (${words.size} words), truncating to 4500 words.")
                words.take(4500).joinToString(" ")
            } else {
                combinedText
            }

            if (limitedText.isBlank()) {
                Log.w("TTSManager", "Cannot synthesize empty or blank text.")
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
                        tempWavFile.delete() // Удаляем временный WAV файл
                    }
                }

                @Deprecated("deprecated in API level 21")
                override fun onError(id: String?) { // Для API < 21
                    if (id == utteranceId) {
                        Log.e("TTSManager", "TTS synthesis error (legacy) for $utteranceId")
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                        tempWavFile.delete()
                    }
                }

                override fun onError(id: String?, errorCode: Int) { // Для API >= 21
                    if (id == utteranceId) {
                        Log.e("TTSManager", "TTS synthesis error for $utteranceId. Error code: $errorCode")
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                        tempWavFile.delete()
                    }
                }

                override fun onStop(id: String?, interrupted: Boolean) { // Обработка остановки
                    if (id == utteranceId && interrupted) {
                        Log.w("TTSManager", "TTS synthesis stopped (interrupted) for $utteranceId")
                        if (continuation.isActive) {
                            continuation.resume(null) // Если прервано, считаем неудачей
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
                tts?.setOnUtteranceProgressListener(null) // Сброс listener
                if (continuation.isActive) {
                    continuation.resume(null)
                }
                tempWavFile.delete()
            } else if (result == TextToSpeech.SUCCESS) {
                Log.d("TTSManager", "tts.synthesizeToFile call successful for $utteranceId, waiting for onDone/onError.")
            }


            continuation.invokeOnCancellation {
                Log.d("TTSManager", "TTS Coroutine cancelled for $utteranceId. Stopping TTS and deleting temp file.")
                tts?.stop()
                tts?.setOnUtteranceProgressListener(null) // Важно сбросить listener, чтобы избежать утечек или вызовов для старых continuation
                tempWavFile.delete()
            }
        }
    }

    private fun convertToMp3(wavFile: File): File? {
        if (!wavFile.exists() || wavFile.length() == 0L) {
            Log.e("TTSManager", "WAV file is missing or empty, cannot convert to MP3: ${wavFile.absolutePath}")
            return null
        }
        val mp3FileName = wavFile.nameWithoutExtension + ".mp3"
        val mp3File = File(wavFile.parentFile, mp3FileName)

        Log.d("TTSManager", "Converting ${wavFile.name} to ${mp3File.name}")
        try {
            // -y: перезаписывать выходной файл без запроса
            // -i: входной файл
            // -b:a: битрейт аудио (64k - разумное значение для голоса)
            // -vn: не включать видео (на всякий случай, если во входном файле что-то есть)
            // -acodec libmp3lame: использование кодека LAME для MP3 (обычно включен в полные сборки FFmpegKit)
            val cmd = arrayOf("-y", "-i", wavFile.absolutePath, "-acodec", "libmp3lame", "-b:a", "64k", "-vn", mp3File.absolutePath)
            val session = FFmpegKit.executeWithArguments(cmd)

            if (ReturnCode.isSuccess(session.returnCode)) {
                if (mp3File.exists() && mp3File.length() > 0) {
                    Log.d("TTSManager", "MP3 conversion successful: ${mp3File.absolutePath}")
                    return mp3File
                } else {
                    Log.e("TTSManager", "MP3 conversion reported success, but file is missing or empty: ${mp3File.absolutePath}. FFmpeg output: ${session.output}")
                    return null
                }
            } else {
                Log.e("TTSManager", "MP3 conversion failed. Return code: ${session.returnCode}. FFmpeg output: ${session.output}")
                // Попытка удалить пустой или некорректный mp3 файл, если он был создан
                if (mp3File.exists()) mp3File.delete()
                return null
            }
        } catch (e: Exception) {
            Log.e("TTSManager", "Exception during MP3 conversion", e)
            return null
        } finally {
            // Удаляем исходный WAV файл после попытки конвертации, независимо от успеха
            // Если вы хотите его сохранить для отладки, закомментируйте эту строку
            if (wavFile.exists()) {
                wavFile.delete()
                Log.d("TTSManager", "Deleted temporary WAV file: ${wavFile.name}")
            }
        }
    }

    fun shutdown() {
        Log.d("TTSManager", "Shutting down TTS engine.")
        ttsInitialized.set(false)
        initializationContinuation?.cancel() // Отменяем ожидание инициализации, если оно есть
        initializationContinuation = null
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
    fun setVoiceByName(voiceName: String) {
        val voice = tts?.voices?.firstOrNull { it.name == voiceName }
        voice?.let {
            Log.d("TTSManager", "🔄 Применяем выбранный голос: ${voice.name}")
            tts?.voice = voice
        }
    }

    fun speak(text: String) {
        if (ttsInitialized.get()) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "TTS_SAMPLE")
        } else {
            Log.w("TTSManager", "⚠️ TTS не инициализирован, speak пропущен.")
        }
    }

}
