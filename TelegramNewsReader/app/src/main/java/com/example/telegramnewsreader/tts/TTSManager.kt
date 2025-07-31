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
import com.example.telegramnewsreader.utils.AudioUtils
import android.speech.tts.Voice
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

// 🔥 Singleton Manager для TTSManager
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
        val pitch = PreferenceManager.getTtsPitch(context)
        val rate = PreferenceManager.getTtsRate(context)
        tts?.setPitch(pitch)
        tts?.setSpeechRate(rate)
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

    fun updatePitch(pitch: Float) {
        PreferenceManager.saveTtsPitch(context, pitch)
        tts?.setPitch(pitch)
        Log.d("TTSManager", "🎚 Тембр речи обновлён: $pitch")
    }

    fun updateRate(rate: Float) {
        PreferenceManager.saveTtsRate(context, rate)
        tts?.setSpeechRate(rate)
        Log.d("TTSManager", "⏩ Скорость речи обновлена: $rate")
    }

    private fun numberToOrdinalRu(number: Int): String {
        return when (number) {
            1 -> "первого"
            2 -> "второго"
            3 -> "третьего"
            4 -> "четвёртого"
            5 -> "пятого"
            6 -> "шестого"
            7 -> "седьмого"
            8 -> "восьмого"
            9 -> "девятого"
            10 -> "десятого"
            11 -> "одиннадцатого"
            12 -> "двенадцатого"
            13 -> "тринадцатого"
            14 -> "четырнадцатого"
            15 -> "пятнадцатого"
            16 -> "шестнадцатого"
            17 -> "семнадцатого"
            18 -> "восемнадцатого"
            19 -> "девятнадцатого"
            20 -> "двадцатого"
            21 -> "двадцать первого"
            22 -> "двадцать второго"
            23 -> "двадцать третьего"
            24 -> "двадцать четвёртого"
            25 -> "двадцать пятого"
            26 -> "двадцать шестого"
            27 -> "двадцать седьмого"
            28 -> "двадцать восьмого"
            29 -> "двадцать девятого"
            30 -> "тридцатого"
            31 -> "тридцать первого"
            else -> number.toString()
        }
    }

    private fun formatForIntonation(text: String): String {
        val dateRegex = Regex("\\b(\\d{1,2})\\s+(января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря)\\b")
        val withDatesConverted = dateRegex.replace(text) { match ->
            val day = match.groupValues[1].toIntOrNull() ?: return@replace match.value
            val month = match.groupValues[2]
            "${numberToOrdinalRu(day)} $month"
        }

        return withDatesConverted
            .replace("([а-яА-Я]{2,})([.!?])\\s+".toRegex(), "$1$2\n\n") // разбивка на абзацы по точке/воскл/вопросу
            .replace("([а-яА-Я]{2,}):".toRegex(), "Цитата:") // Путин: → Цитата:
            .replace(Regex(" - "), " — ") // тире с паузой
            .replace(Regex("\\.\\.\\."), "…") // нормализуем ...
            .replace(Regex(", "), ", ") // нормализуем запятые
            .trim()
    }

    // 🔥 Новый метод для разбивки текста на части по 2800 символов
    private fun splitTextSafely(text: String, maxChars: Int = 2800): List<String> {
        if (text.length <= maxChars) {
            return listOf(text)
        }

        val parts = mutableListOf<String>()
        var currentPart = ""
        val sentences = text.split(Regex("(?<=[.!?])\\s+"))

        for (sentence in sentences) {
            if ((currentPart + sentence).length <= maxChars) {
                currentPart += if (currentPart.isEmpty()) sentence else " $sentence"
            } else {
                if (currentPart.isNotEmpty()) {
                    parts.add(currentPart.trim())
                    currentPart = sentence
                } else {
                    // Если одно предложение слишком длинное, режем по словам
                    val words = sentence.split(" ")
                    var wordPart = ""
                    for (word in words) {
                        if ((wordPart + word).length <= maxChars) {
                            wordPart += if (wordPart.isEmpty()) word else " $word"
                        } else {
                            if (wordPart.isNotEmpty()) {
                                parts.add(wordPart.trim())
                                wordPart = word
                            } else {
                                // Если слово слишком длинное, режем жестко
                                parts.add(word.take(maxChars))
                                wordPart = ""
                            }
                        }
                    }
                    if (wordPart.isNotEmpty()) {
                        currentPart = wordPart
                    }
                }
            }
        }

        if (currentPart.isNotEmpty()) {
            parts.add(currentPart.trim())
        }

        return parts.filter { it.isNotBlank() }
    }

    // 🔥 Новый метод для генерации одной части в WAV
    private suspend fun synthesizePartToWav(text: String, partIndex: Int, baseUtteranceId: String): File? {
        return suspendCancellableCoroutine { continuation ->
            val utteranceId = "${baseUtteranceId}_part_${partIndex}"
            val tempWavFile = File(context.cacheDir, "${utteranceId}.wav")
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            }

            // 💾 Сохраняем текст части в .txt файл
            val textFile = File(context.cacheDir, "${utteranceId}.txt")
            try {
                textFile.writeText(text)
                Log.d("TTSManager", "💾 Сохранили текст части $partIndex в ${textFile.absolutePath}")
            } catch (e: Exception) {
                Log.e("TTSManager", "❌ Ошибка при сохранении текста части $partIndex", e)
            }

            val listener = object : UtteranceProgressListener() {
                override fun onStart(id: String?) {
                    if (id == utteranceId) {
                        Log.d("TTSManager", "🎤 TTS синтез начат для части $partIndex ($utteranceId)")
                    }
                }

                override fun onDone(id: String?) {
                    if (id == utteranceId) {
                        Log.d("TTSManager", "✅ TTS синтез завершен для части $partIndex ($utteranceId)")
                        if (continuation.isActive) {
                            continuation.resume(tempWavFile)
                        }
                    }
                }

                @Deprecated("deprecated in API level 21")
                override fun onError(id: String?) {
                    if (id == utteranceId) {
                        Log.e("TTSManager", "❌ TTS ошибка (legacy) для части $partIndex ($utteranceId)")
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                        tempWavFile.delete()
                    }
                }

                override fun onError(id: String?, errorCode: Int) {
                    if (id == utteranceId) {
                        Log.e("TTSManager", "❌ TTS ошибка для части $partIndex ($utteranceId). Код: $errorCode")
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                        tempWavFile.delete()
                    }
                }

                override fun onStop(id: String?, interrupted: Boolean) {
                    if (id == utteranceId && interrupted) {
                        Log.w("TTSManager", "⏹️ TTS остановлен (прерван) для части $partIndex ($utteranceId)")
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                        tempWavFile.delete()
                    }
                }
            }

            tts?.setOnUtteranceProgressListener(listener)

            Log.d("TTSManager", "🎬 Начинаем TTS синтез части $partIndex: ${tempWavFile.absolutePath}")
            val result = tts?.synthesizeToFile(text, params, tempWavFile, utteranceId)

            if (result == TextToSpeech.ERROR) {
                Log.e("TTSManager", "❌ tts.synthesizeToFile немедленно вернул ERROR для части $partIndex ($utteranceId)")
                tts?.setOnUtteranceProgressListener(null)
                if (continuation.isActive) {
                    continuation.resume(null)
                }
                tempWavFile.delete()
            } else if (result == TextToSpeech.SUCCESS) {
                Log.d("TTSManager", "✅ tts.synthesizeToFile успешно вызван для части $partIndex ($utteranceId)")
            }

            continuation.invokeOnCancellation {
                Log.d("TTSManager", "🚫 TTS Coroutine отменена для части $partIndex ($utteranceId)")
                tts?.stop()
                tts?.setOnUtteranceProgressListener(null)
                tempWavFile.delete()
            }
        }
    }

    // 🔥 Обновленный основной метод convertToAudio с разбивкой на части
    suspend fun convertToAudio(texts: List<String>): File? {
        if (!ensureTtsInitialized() || tts == null) {
            Log.e("TTSManager", "TTS не инициализирован. Невозможно конвертировать в аудио.")
            return null
        }

        // 🔥 ВАЖНО: Всегда применяем актуальный сохраненный голос перед синтезом
        refreshVoice()

        val combinedText = texts.joinToString(" ")
        val formattedText = formatForIntonation(combinedText)
        val baseUtteranceId = "ttsAudioConversion_${System.currentTimeMillis()}"

        // 💾 Сохраняем полный (необрезанный) текст для анализа
        val rawTextFile = File(context.cacheDir, "${baseUtteranceId}_full.txt")
        try {
            rawTextFile.writeText(formattedText)
            Log.d("TTSManager", "📄 Сохранили полный исходный текст в ${rawTextFile.absolutePath}")
        } catch (e: Exception) {
            Log.e("TTSManager", "❌ Ошибка при сохранении полного текста", e)
        }

        if (formattedText.isBlank()) {
            Log.w("TTSManager", "⚠️ Невозможно синтезировать пустой текст.")
            return null
        }

        // 🔪 Разбиваем текст на части по 2800 символов
        val textParts = splitTextSafely(formattedText, 3500)
        Log.d("TTSManager", "📝 Разбили текст на ${textParts.size} частей (исходный размер: ${formattedText.length} символов)")

        // 🎤 Генерируем WAV файлы для каждой части последовательно
        val wavFiles = mutableListOf<File>()
        for (i in textParts.indices) {
            Log.d("TTSManager", "🎬 Синтезируем часть ${i + 1}/${textParts.size} (${textParts[i].length} символов)")
            val wavFile = synthesizePartToWav(textParts[i], i + 1, baseUtteranceId)
            if (wavFile != null && wavFile.exists() && wavFile.length() > 0) {
                wavFiles.add(wavFile)
                Log.d("TTSManager", "✅ Часть ${i + 1} синтезирована: ${wavFile.name} (${wavFile.length()} байт)")
            } else {
                Log.e("TTSManager", "❌ Не удалось синтезировать часть ${i + 1}")
                // Очищаем созданные файлы при ошибке
                wavFiles.forEach { it.delete() }
                return null
            }
        }

        if (wavFiles.isEmpty()) {
            Log.e("TTSManager", "❌ Не удалось создать ни одного WAV файла")
            return null
        }

        // 🔗 Объединяем WAV файлы в один
        val combinedWavFile = File(context.cacheDir, "${baseUtteranceId}_combined.wav")
        val concatSuccess = if (wavFiles.size == 1) {
            // Если только одна часть, просто переименовываем
            wavFiles.first().renameTo(combinedWavFile)
        } else {
            Log.d("TTSManager", "🔗 Объединяем ${wavFiles.size} WAV файлов в один")
            AudioUtils.concatWavFiles(wavFiles, combinedWavFile)
        }

        // Удаляем временные WAV файлы частей
        wavFiles.forEach { file ->
            if (file.exists() && file != combinedWavFile) {
                file.delete()
                Log.d("TTSManager", "🗑️ Удалили временный WAV: ${file.name}")
            }
        }

        if (!concatSuccess || !combinedWavFile.exists() || combinedWavFile.length() == 0L) {
            Log.e("TTSManager", "❌ Не удалось объединить WAV файлы")
            combinedWavFile.delete()
            return null
        }

        Log.d("TTSManager", "✅ Объединенный WAV создан: ${combinedWavFile.name} (${combinedWavFile.length()} байт)")

        // 🎵 Конвертируем в MP3
        val mp3File = convertToMp3(combinedWavFile)
        combinedWavFile.delete()

        if (mp3File != null) {
            Log.d("TTSManager", "🎉 Синтез завершен успешно: ${mp3File.name} (${mp3File.length()} байт)")
            Log.d("TTSManager", "📊 Статистика: ${textParts.size} частей, ${formattedText.length} символов → ${mp3File.length()} байт MP3")
        } else {
            Log.e("TTSManager", "❌ Не удалось конвертировать в MP3")
        }

        return mp3File
    }

    private fun convertToMp3(wavFile: File): File? {
        if (!wavFile.exists() || wavFile.length() == 0L) {
            Log.e("TTSManager", "WAV файл отсутствует или пуст: ${wavFile.absolutePath}")
            return null
        }
        val mp3FileName = wavFile.nameWithoutExtension + ".mp3"
        val mp3File = File(wavFile.parentFile, mp3FileName)

        Log.d("TTSManager", "🎵 Конвертируем ${wavFile.name} в ${mp3File.name}")
        try {
            val cmd = arrayOf("-y", "-i", wavFile.absolutePath, "-acodec", "libmp3lame", "-b:a", "64k", "-vn", mp3File.absolutePath)
            val session = FFmpegKit.executeWithArguments(cmd)

            if (ReturnCode.isSuccess(session.returnCode)) {
                if (mp3File.exists() && mp3File.length() > 0) {
                    Log.d("TTSManager", "✅ Конвертация в MP3 успешна: ${mp3File.absolutePath}")
                    return mp3File
                } else {
                    Log.e("TTSManager", "❌ Конвертация в MP3 сообщила об успехе, но файл отсутствует или пуст")
                    return null
                }
            } else {
                Log.e("TTSManager", "❌ Конвертация в MP3 неуспешна. Код возврата: ${session.returnCode}")
                if (mp3File.exists()) mp3File.delete()
                return null
            }
        } catch (e: Exception) {
            Log.e("TTSManager", "❌ Исключение при конвертации в MP3", e)
            return null
        } finally {
            if (wavFile.exists()) {
                wavFile.delete()
                Log.d("TTSManager", "🗑️ Удалили временный WAV файл: ${wavFile.name}")
            }
        }
    }

    fun shutdown() {
        Log.d("TTSManager", "🔌 Выключаем TTS движок.")
        ttsInitialized.set(false)
        initializationContinuation?.cancel()
        initializationContinuation = null
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    // 🔥 Улучшенный метод - применяет выбранный голос немедленно
    fun setVoiceByName(voiceName: String) {
        val voice = tts?.voices?.firstOrNull { it.name == voiceName }
        voice?.let {
            Log.d("TTSManager", "🔄 Применяем выбранный голос: ${voice.name}")
            tts?.language = voice.locale
            tts?.voice = voice

            // Сохраняем в настройки
            PreferenceManager.saveTtsVoiceName(context, voiceName)
            Log.d("TTSManager", "💾 Голос сохранен и применен: $voiceName")
        } ?: run {
            Log.w("TTSManager", "❗ Голос '$voiceName' не найден среди доступных")
        }
    }

    fun speak(text: String) {
        if (ttsInitialized.get()) {
            // 💡 Применяем актуальный голос перед воспроизведением
            refreshVoice()
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "TTS_SAMPLE")
        } else {
            Log.w("TTSManager", "⚠️ TTS не инициализирован, speak пропущен.")
        }
    }

    // 🔥 Метод для принудительного обновления голоса (применяет актуальные настройки)
    fun refreshVoice() {
        if (ttsInitialized.get()) {
            val savedVoiceName = PreferenceManager.getTtsVoiceName(context)
            val selectedVoice = tts?.voices?.find { it.name == savedVoiceName }
            selectedVoice?.let {
                Log.d("TTSManager", "🔁 refreshVoice(): применяем голос ${it.name}")
                tts?.language = it.locale
                tts?.voice = it
            } ?: run {
                Log.w("TTSManager", "❗ refreshVoice(): голос $savedVoiceName не найден среди доступных")
            }

            // ✅ Применяем сохранённые настройки
            val pitch = PreferenceManager.getTtsPitch(context)
            val rate = PreferenceManager.getTtsRate(context)
            tts?.setPitch(pitch)
            tts?.setSpeechRate(rate)

            Log.d("TTSManager", "🔄 Голос и параметры обновлены: pitch=$pitch, rate=$rate")
        }
    }
}