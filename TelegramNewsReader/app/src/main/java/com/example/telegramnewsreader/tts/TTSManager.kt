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
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.example.telegramnewsreader.models.VoiceMappings
import com.example.telegramnewsreader.models.VoiceEntry
import com.example.telegramnewsreader.utils.TTSDebugTracker

object TTSManagerSingleton {
    @Volatile
    private var INSTANCE: TTSManager? = null


    fun getInstance(context: Context): TTSManager {
        return INSTANCE ?: synchronized(this) {
            INSTANCE ?: TTSManager(context.applicationContext).also {
                INSTANCE = it
                Log.d("TTSManagerSingleton", "🏗️ Создан новый экземпляр TTSManager")
            }
        }
    }

    fun clearInstance() {
        synchronized(this) {
            Log.d("TTSManagerSingleton", "🗑️ Очистка экземпляра TTSManager")
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

    // Флаги/параметры
    private var voiceParametersApplied = false
    private var currentAppliedPitch: Float? = null
    private var currentAppliedRate: Float? = null

    // Счетчики
    private var pitchChangeCount = 0
    private var rateChangeCount = 0
    private var voiceChangeCount = 0

    init {
        val stackTrace = Thread.currentThread().stackTrace
        Log.d("TTSManager", "🚀 ИНИЦИАЛИЗАЦИЯ TTSManager")
        Log.d("TTSManager", "📍 Стек вызовов TTSManager init:")
        stackTrace.take(10).forEach { element ->
            Log.d("TTSManager", "   ${element.className}.${element.methodName}(${element.fileName}:${element.lineNumber})")
        }
        tts = TextToSpeech(context, this, findBestEngine())
    }

    private fun findBestEngine(): String? {
        // В этом месте tts ещё null; оставляем как есть, чтобы не ломать логику.
        // Детальное логирование текущего движка добавлено в onInit().
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
        TTSDebugTracker.trackTTSInit("status: $status")

        Log.d("TTSManager", "🎬 onInit() вызван со статусом: $status")
        val stackTrace = Thread.currentThread().stackTrace
        Log.d("TTSManager", "📍 Стек вызовов onInit:")
        stackTrace.take(8).forEach { element ->
            Log.d("TTSManager", "   ${element.className}.${element.methodName}(${element.fileName}:${element.lineNumber})")
        }

        if (status == TextToSpeech.SUCCESS) {
            ttsInitialized.set(true)
            Log.d("TTSManager", "✅ TTS Initialized successfully.")

            // Логируем выбранный движок и голос
            try {
                val engine = tts?.defaultEngine
                Log.d("TTSManager", "🛠️ TTS defaultEngine: $engine")
                val voices = tts?.voices?.size ?: -1
                Log.d("TTSManager", "🛠️ Доступных голосов: $voices")
            } catch (e: Exception) {
                Log.w("TTSManager", "Не удалось получить информацию о движке/голосах", e)
            }

            val result = tts?.setLanguage(Locale("ru"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w("TTSManager", "Russian language not supported, falling back to US English.")
                tts?.setLanguage(Locale.US)
            }

            Log.d("TTSManager", "🔄 Вызываем applySavedVoice() из onInit")
            applySavedVoice()
            initializationContinuation?.resume(true)
        } else {
            ttsInitialized.set(false)
            Log.e("TTSManager", "TTS Initialization Failed! Status: $status")
            initializationContinuation?.resume(false)
        }
        initializationContinuation = null
    }

    private fun applySavedVoice() {
        Log.d("TTSManager", "🎯 === applySavedVoice() НАЧАЛО ===")
        val stackTrace = Thread.currentThread().stackTrace
        Log.d("TTSManager", "📍 Стек вызовов applySavedVoice:")
        stackTrace.take(8).forEach { element ->
            Log.d("TTSManager", "   ${element.className}.${element.methodName}(${element.fileName}:${element.lineNumber})")
        }

        val availableVoices = tts?.voices
        val russianVoices = availableVoices?.filter { it.locale.language == "ru" }
        val savedVoiceName = PreferenceManager.getTtsVoiceName(context)
        val matchedVoice = availableVoices?.find { it.name == savedVoiceName }

        Log.d("TTSManager", "💾 Сохраненное имя голоса: $savedVoiceName")
        Log.d("TTSManager", "🔍 Найдено русских голосов: ${russianVoices?.size}")

        russianVoices?.forEach { voice ->
            val voiceEntry = VoiceMappings.mapVoice(voice)
            Log.d("TTSManager", "🔍 Voice found: name=${voice.name} -> ${voiceEntry.displayName} (${voiceEntry.getGenderDescription()})")
        }

        if (matchedVoice != null) {
            val voiceEntry = VoiceMappings.mapVoice(matchedVoice)
            Log.d("TTSManager", "🔊 Применяем сохранённый голос: ${matchedVoice.name} -> ${voiceEntry.displayName}")
            tts?.language = matchedVoice.locale
            tts?.voice = matchedVoice
            voiceChangeCount++
            TTSDebugTracker.trackVoiceChange(matchedVoice.name, "applySavedVoice - matched voice")
        } else if (!russianVoices.isNullOrEmpty()) {
            val firstVoice = russianVoices.first()
            val voiceEntry = VoiceMappings.mapVoice(firstVoice)
            Log.d("TTSManager", "🟡 Голос не найден, выбираем первый доступный: ${firstVoice.name} -> ${voiceEntry.displayName}")
            tts?.language = firstVoice.locale
            tts?.voice = firstVoice
            voiceChangeCount++
            TTSDebugTracker.trackVoiceChange(firstVoice.name, "applySavedVoice - first available")
        } else {
            Log.w("TTSManager", "⚠️ Нет русских голосов, используется голос по умолчанию.")
        }

        Log.d("TTSManager", "🎚️ Вызываем applyVoiceParametersOnce() из applySavedVoice")
        applyVoiceParametersOnce()
        Log.d("TTSManager", "🎯 === applySavedVoice() КОНЕЦ ===")
    }

    private fun applyVoiceParametersOnce() {
        Log.d("TTSManager", "🎯 === applyVoiceParametersOnce() НАЧАЛО ===")
        val stackTrace = Thread.currentThread().stackTrace
        Log.d("TTSManager", "📍 Стек вызовов applyVoiceParametersOnce:")
        stackTrace.take(8).forEach { element ->
            Log.d("TTSManager", "   ${element.className}.${element.methodName}(${element.fileName}:${element.lineNumber})")
        }

        Log.d("TTSManager", "🔍 Текущий статус: voiceParametersApplied=$voiceParametersApplied")
        Log.d("TTSManager", "🔍 Текущие параметры: currentAppliedPitch=$currentAppliedPitch, currentAppliedRate=$currentAppliedRate")

        if (!voiceParametersApplied) {
            val pitch = PreferenceManager.getTtsPitch(context)
            val rate = PreferenceManager.getTtsRate(context)

            Log.d("TTSManager", "📖 Считанные настройки: pitch=$pitch, rate=$rate")

            val pitchResult = tts?.setPitch(pitch)
            val rateResult = tts?.setSpeechRate(rate)
            pitchChangeCount++
            rateChangeCount++

            TTSDebugTracker.trackPitchChange(pitch, "applyVoiceParametersOnce - system init")
            TTSDebugTracker.trackRateChange(rate, "applyVoiceParametersOnce - system init")

            Log.d("TTSManager", "🎚️ Результат setPitch($pitch): $pitchResult")
            Log.d("TTSManager", "⏩ Результат setSpeechRate($rate): $rateResult")

            currentAppliedPitch = pitch
            currentAppliedRate = rate
            voiceParametersApplied = true

            Log.d("TTSManager", "🎯 Параметры голоса применены ЕДИНОКРАТНО: pitch=$pitch, rate=$rate")
            Log.d("TTSManager", "📊 Счетчики изменений: pitch=$pitchChangeCount, rate=$rateChangeCount, voice=$voiceChangeCount")
        } else {
            Log.d("TTSManager", "✅ Параметры голоса уже применены, пропускаем повторное применение")
            Log.d("TTSManager", "📊 Текущие счетчики: pitch=$pitchChangeCount, rate=$rateChangeCount, voice=$voiceChangeCount")
        }
        Log.d("TTSManager", "🎯 === applyVoiceParametersOnce() КОНЕЦ ===")
    }

    private suspend fun ensureTtsInitialized(): Boolean {
        Log.d("TTSManager", "🔄 ensureTtsInitialized() вызван. Текущий статус: ${ttsInitialized.get()}")
        if (ttsInitialized.get()) return true
        if (tts != null && initializationContinuation == null) {
            return suspendCancellableCoroutine { continuation ->
                initializationContinuation = continuation
                continuation.invokeOnCancellation { initializationContinuation = null }
            }
        }
        return ttsInitialized.get()
    }

    fun setVoiceGender(isMale: Boolean) {
        Log.d("TTSManager", "🚻 setVoiceGender($isMale) вызван")
        val stackTrace = Thread.currentThread().stackTrace
        Log.d("TTSManager", "📍 Стек вызовов setVoiceGender:")
        stackTrace.take(6).forEach { element ->
            Log.d("TTSManager", "   ${element.className}.${element.methodName}(${element.fileName}:${element.lineNumber})")
        }

        this.isMale = isMale
        if (ttsInitialized.get()) {
            val newPitch = if (isMale) 0.8f else 1.2f
            Log.d("TTSManager", "🎚️ ВНИМАНИЕ! setVoiceGender устанавливает pitch=$newPitch (было ${currentAppliedPitch})")

            val result = tts?.setPitch(newPitch)
            pitchChangeCount++
            currentAppliedPitch = newPitch

            TTSDebugTracker.trackPitchChange(newPitch, "setVoiceGender - automatic gender adjustment")

            Log.d("TTSManager", "⚠️ ПОТЕНЦИАЛЬНАЯ ПРОБЛЕМА! setVoiceGender изменил pitch на $newPitch")
            Log.d("TTSManager", "📊 Счетчик изменений pitch: $pitchChangeCount")
        }
    }

    fun getAvailableVoices(): List<Voice> {
        return tts?.voices?.filter {
            (it.locale.language == "ru" || it.locale.language == "en") && !it.name.contains("network")
        }?.toList() ?: emptyList()
    }

    fun getAvailableVoiceEntries(): List<VoiceEntry> {
        Log.d("TTSManager", "📋 getAvailableVoiceEntries() вызван")
        val systemVoices = tts?.voices?.filter {
            it.locale.language == "ru" || it.locale.language == "en"
        }?.toList() ?: emptyList()

        val voiceEntries = VoiceMappings.mapVoices(systemVoices)

        Log.d("TTSManager", "📋 Найдено голосов: ${systemVoices.size} системных -> ${voiceEntries.size} с понятными названиями")
        voiceEntries.forEach { entry ->
            Log.d("TTSManager", "  ${entry.getGenderIcon()} ${entry.displayName} (${entry.systemName})")
        }

        return voiceEntries
    }

    fun setVoiceByEntry(voiceEntry: VoiceEntry) {
        Log.d("TTSManager", "🎤 setVoiceByEntry(${voiceEntry.displayName}) вызван")
        setVoiceByName(voiceEntry.systemName)
    }

    fun updatePitch(pitch: Float) {
        TTSDebugTracker.trackPitchChange(pitch, "updatePitch method - user change")

        Log.d("TTSManager", "🎚️ === updatePitch($pitch) НАЧАЛО ===")
        val stackTrace = Thread.currentThread().stackTrace
        Log.d("TTSManager", "📍 Стек вызовов updatePitch:")
        stackTrace.take(8).forEach { element ->
            Log.d("TTSManager", "   ${element.className}.${element.methodName}(${element.fileName}:${element.lineNumber})")
        }

        Log.d("TTSManager", "🔍 Было: currentAppliedPitch=$currentAppliedPitch")
        Log.d("TTSManager", "🔍 Устанавливаем: pitch=$pitch")

        val result = tts?.setPitch(pitch)
        pitchChangeCount++
        currentAppliedPitch = pitch

        Log.d("TTSManager", "🎚️ Результат setPitch: $result")
        Log.d("TTSManager", "📊 Счетчик изменений pitch: $pitchChangeCount")
        Log.d("TTSManager", "🎚️ Тембр речи обновлён ПОЛЬЗОВАТЕЛЕМ: $pitch")
        Log.d("TTSManager", "🎚️ === updatePitch($pitch) КОНЕЦ ===")
    }

    fun updateRate(rate: Float) {
        TTSDebugTracker.trackRateChange(rate, "updateRate method - user change")

        Log.d("TTSManager", "⏩ === updateRate($rate) НАЧАЛО ===")
        val stackTrace = Thread.currentThread().stackTrace
        Log.d("TTSManager", "📍 Стек вызовов updateRate:")
        stackTrace.take(8).forEach { element ->
            Log.d("TTSManager", "   ${element.className}.${element.methodName}(${element.fileName}:${element.lineNumber})")
        }

        Log.d("TTSManager", "🔍 Было: currentAppliedRate=$currentAppliedRate")
        Log.d("TTSManager", "🔍 Устанавливаем: rate=$rate")

        val result = tts?.setSpeechRate(rate)
        rateChangeCount++
        currentAppliedRate = rate

        Log.d("TTSManager", "⏩ Результат setSpeechRate: $result")
        Log.d("TTSManager", "📊 Счетчик изменений rate: $rateChangeCount")
        Log.d("TTSManager", "⏩ Скорость речи обновлена ПОЛЬЗОВАТЕЛЕМ: $rate")
        Log.d("TTSManager", "⏩ === updateRate($rate) КОНЕЦ ===")
    }

    private fun numberToOrdinalRu(number: Int): String {
        return when (number) {
            1 -> "первого"; 2 -> "второго"; 3 -> "третьего"; 4 -> "четвёртого"; 5 -> "пятого"
            6 -> "шестого"; 7 -> "седьмого"; 8 -> "восьмого"; 9 -> "девятого"; 10 -> "десятого"
            11 -> "одиннадцатого"; 12 -> "двенадцатого"; 13 -> "тринадцатого"; 14 -> "четырнадцатого"
            15 -> "пятнадцатого"; 16 -> "шестнадцатого"; 17 -> "семнадцатого"; 18 -> "восемнадцатого"
            19 -> "девятнадцатого"; 20 -> "двадцатого"; 21 -> "двадцать первого"; 22 -> "двадцать второго"
            23 -> "двадцать третьего"; 24 -> "двадцать четвёртого"; 25 -> "двадцать пятого"
            26 -> "двадцать шестого"; 27 -> "двадцать седьмого"; 28 -> "двадцать восьмого"
            29 -> "двадцать девятого"; 30 -> "тридцатого"; 31 -> "тридцать первого"
            else -> number.toString()
        }
    }

    private val NEWS_SEPARATOR = "\n\n— — —\n\n"

    private fun cleanTextForTts(text: String): String {
        var t = text
        // Удаление URL
        t = t.replace(Regex("(https?://|www\\.)\\S+"), " ")
        // Удаление хештегов и упоминаний
        t = t.replace(Regex("(^|\\s)[#@][\\p{L}0-9_]+"), " ")
        // Удаление служебной информации
        t = t.replace(Regex("(?im)^переслано из:?\\s.*$"), "")
        t = t.replace(Regex("(?im)^ред\\.?\\s*:?\\s*\\d{1,2}:\\d{2}.*$"), "")

        // УЛУЧШЕННАЯ фильтрация подписок и рекламы
        t = t.replace(Regex("(?im)^\\s*(?:[\\p{So}\\p{Sk}❗️!❤️💚💙💛💜🖤🤍🤎]\\s*)*подписывай(ся|тесь)?\\b.*$", RegexOption.MULTILINE), "")
        t = t.replace(Regex("(?im)^\\s*подписка\\b.*$", RegexOption.MULTILINE), "")
        t = t.replace(Regex("(?im)^.*\\b(реклама|промокод|скидк[аи])\\b.*$", RegexOption.MULTILINE), "")
        t = t.replace(Regex("(?im)^.*\\b(акци[яи]|распродажа|купи)\\b.*$", RegexOption.MULTILINE), "")

        // Удаление контактов (телефоны)
        t = t.replace(Regex("\\+?\\d{1,3}[\\s-]?\\(?\\d{1,4}\\)?[\\s-]?\\d{1,4}[\\s-]?\\d{1,4}[\\s-]?\\d{1,4}"), "")

        // Удаление всех цветных квадратов
        t = t.replace(Regex("[🟩🟨🟥🟦🟪🟫⬛⬜]"), "")
        // Удаление эмодзи
        t = t.replace(Regex("[\\p{So}\\p{Sk}]"), " ")
        // Удаление markdown разметки
        t = t.replace(Regex("[*_`]+"), "")
        // Замена кавычек
        t = t.replace(Regex("[«»]"), "\"")
        // Удаление повторной рекламы подписки
        t = t.replace(Regex("(?i)подписывай(ся|тесь)?\\s+на\\s+[^\\n.]+"), "")
        // Нормализация многоточий
        t = t.replace(Regex("\\.\\.\\."), "…")
        // Очистка пробелов
        t = t.replace(Regex("[ \\t]{2,}"), " ")
        t = t.replace(Regex("\\n{3,}"), "\n\n")
        return t.trim()
    }

    private fun deduplicateLines(text: String): String {
        val seen = HashSet<String>()
        val sb = StringBuilder()
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            val key = line.lowercase()
            if (seen.add(key)) {
                if (sb.isNotEmpty()) sb.append("\n")
                sb.append(line)
            }
        }
        return sb.toString()
    }

    private fun normalizeNumbers(text: String): String {
        var t = text
        t = t.replace(Regex("\\b№\\s*(\\d+)"), "номер $1")
        t = t.replace(Regex("\\b(\\d+[\\d\\s]*)(?:₽|руб\\.?|р\\.)\\b", RegexOption.IGNORE_CASE), "$1 рублей")
        t = t.replace(Regex("\\b(\\d+[\\d\\s]*)\\s?%\\b"), "$1 процентов")
        return t
    }

    private fun formatForIntonation(text: String): String {
        var t = text

        // Даты
        val dateRegex = Regex("\\b(\\d{1,2})\\s+(января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря)\\b")
        t = dateRegex.replace(t) { match ->
            val day = match.groupValues[1].toIntOrNull() ?: return@replace match.value
            val month = match.groupValues[2]
            "${numberToOrdinalRu(day)} $month"
        }

        // Единицы измерения
        t = t.replace(Regex("\\bкм/ч\\b", RegexOption.IGNORE_CASE), "километров в час")
        t = t.replace(Regex("\\bкм\\b", RegexOption.IGNORE_CASE), "километров")
        t = t.replace(Regex("\\bм\\b", RegexOption.IGNORE_CASE), "метров")

        // Нумерованные списки (УЛУЧШЕНО)
        t = t.replace(Regex("^(\\d+)\\.\\s+", RegexOption.MULTILINE)) {
            "${it.groupValues[1]}. <break time=\"150ms\"/>"
        }

        // Буллиты (УЛУЧШЕНО)
        t = t.replace(Regex("^[•·∙▪▫◦‣⁃]\\s+", RegexOption.MULTILINE)) {
            "— <break time=\"150ms\"/>"
        }

        // Форматирование тире
        t = t.replace(Regex("(?m)^[-•]\\s+"), "— ")
        t = t.replace(Regex(" - "), " — ")
        t = t.replace(Regex("\\.\\.\\."), "…")

        // Добавление переносов после предложений
        val abbrEnd = "(?<!т\\.д)(?<!т\\.п)(?<!млн)(?<!млрд)(?<!г)(?<!ул)(?<!просп)"
        t = t.replace(Regex("$abbrEnd(?<=[.!?])\\s+"), "\n\n")

        return t.trim()
    }

    // УЛУЧШЕННЫЙ метод для вычисления динамических пауз
    private fun calculatePauseDuration(sentenceLength: Int): String {
        return when {
            sentenceLength < 50 -> "400ms"
            sentenceLength < 100 -> "600ms"
            sentenceLength < 150 -> "800ms"
            else -> "1000ms"
        }
    }

    // ЗНАЧИТЕЛЬНО УЛУЧШЕННЫЙ метод для SSML
    private fun enhanceWithSSML(text: String): String {
        var enhanced = text

        // Добавляем короткие паузы после запятых
        enhanced = enhanced.replace(Regex(",\\s+"), ", <break time=\"200ms\"/> ")

        // Паузы после точки с запятой
        enhanced = enhanced.replace(Regex(";\\s+"), "; <break time=\"300ms\"/> ")

        // Паузы после тире в перечислениях
        enhanced = enhanced.replace(Regex("\\s+—\\s+"), " <break time=\"250ms\"/>— ")

        // Динамические паузы после предложений с учетом длины
        enhanced = enhanced.replace(Regex("([^.!?]+)([.!?])(?=\\s+[А-ЯЁA-Z]|$)")) { match ->
            val sentence = match.groupValues[1]
            val punctuation = match.groupValues[2]
            val pauseDuration = when (punctuation) {
                "." -> calculatePauseDuration(sentence.length)
                "!" -> "500ms"
                "?" -> "450ms"
                else -> "400ms"
            }
            "$sentence$punctuation <break time=\"$pauseDuration\"/>"
        }

        // Паузы после двоеточий (для перечислений)
        enhanced = enhanced.replace(Regex(":\\s+"), ": <break time=\"500ms\"/> ")

        // Паузы перед прямой речью
        enhanced = enhanced.replace(Regex(":\\s*\""), ": <break time=\"300ms\"/>\"")

        // Прямая речь с изменением темпа
        enhanced = enhanced.replace(Regex("\"([^\"]+)\"")) {
            "\"<prosody rate=\"95%\">${it.groupValues[1]}</prosody>\""
        }

        // Вопросительные предложения - замедляем конец
        enhanced = enhanced.replace(Regex("([^.!?]{20,})(\\?)")) { match ->
            val question = match.groupValues[1]
            val words = question.split(" ")
            if (words.size > 3) {
                val lastWords = words.takeLast(3).joinToString(" ")
                val firstPart = words.dropLast(3).joinToString(" ")
                "$firstPart <prosody rate=\"90%\">$lastWords</prosody>?"
            } else {
                "${match.groupValues[1]}?"
            }
        }

        // Паузы между абзацами
        enhanced = enhanced.replace(Regex("\\n{2,}"), "<break time=\"600ms\"/>")

        // Оборачиваем в SSML теги
        enhanced = "<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" xml:lang=\"ru-RU\">" +
                enhanced +
                "</speak>"

        return enhanced
    }

    // УЛУЧШЕННЫЙ метод форматирования для речи
    private fun formatForSpeech(text: String): String {
        var processed = text

        // Улучшаем названия организаций
        processed = processed.replace(Regex("\\b(НАТО|ЕС|США|ООН|ФСБ|МВД|СБУ|ЦРУ|ФБР)\\b")) {
            when(it.value) {
                "США" -> "США"
                "ЕС" -> "Европейский союз"
                "НАТО" -> "НАТО"
                "ООН" -> "Организация Объединенных Наций"
                "ФСБ" -> "Федеральная служба безопасности"
                "МВД" -> "Министерство внутренних дел"
                "СБУ" -> "Служба безопасности Украины"
                "ЦРУ" -> "Центральное разведывательное управление"
                "ФБР" -> "Федеральное бюро расследований"
                else -> it.value
            }
        }

        // Улучшаем даты для лучшего произношения
        processed = processed.replace(Regex("\\b(\\d{1,2})\\.\\s*(\\d{1,2})\\.\\s*(\\d{4})\\b")) {
            val day = it.groupValues[1]
            val month = it.groupValues[2]
            val year = it.groupValues[3]
            "$day число $month месяца $year года"
        }

        // Добавляем эмфазис для важных слов
        val importantWords = listOf("важно", "внимание", "срочно", "эксклюзив", "молния")
        importantWords.forEach { word ->
            processed = processed.replace(
                Regex("\\b($word)\\b", RegexOption.IGNORE_CASE),
                "<emphasis level=\"strong\">$1</emphasis>"
            )
        }

        // Правильные ударения для ключевых слов
        val keyWords = mapOf(
            "украина" to "Украина",
            "путин" to "Путин",
            "зеленский" to "Зеленский",
            "трамп" to "Трамп",
            "байден" to "Байден",
            "россия" to "Россия",
            "америка" to "Америка",
            "европа" to "Европа",
            "китай" to "Китай"
        )

        keyWords.forEach { (word, pronounced) ->
            processed = processed.replace(Regex("\\b$word\\b", RegexOption.IGNORE_CASE)) { pronounced }
        }

        return processed.trim()
    }

    private fun dropTrivial(texts: List<String>): List<String> {
        val trivial = Regex("^(фото|видео|аудио|ссылка|репост)\\b.*$", RegexOption.IGNORE_CASE)
        val subscribe = Regex("(?i)^.*\\b(подписывай(ся|тесь)?|подписка)\\b.*$", RegexOption.IGNORE_CASE)

        return texts.map { it.trim() }
            .filter { text ->
                val isTrivial = text.length < 8 || trivial.containsMatchIn(text)
                val hasSubscribe = subscribe.containsMatchIn(text)

                // Отладка
                if (hasSubscribe) {
                    Log.d("TTSManager", "⚠️ Найдена подписка: '$text'")
                }

                // Разрешить текст, если он не тривиальный и НЕ содержит только подписку
                !(isTrivial || hasSubscribe)
            }
    }

    private fun splitByParagraphs(text: String, maxChars: Int = 2800): List<String> {
        val paras = text.split(Regex("\\n{2,}")).map { it.trim() }.filter { it.isNotEmpty() }
        val parts = mutableListOf<String>()
        val cur = StringBuilder()

        fun flush() {
            if (cur.isNotEmpty()) {
                parts.add(cur.toString().trim())
                cur.clear()
            }
        }

        for (p in paras) {
            if (cur.length + p.length + 2 <= maxChars) {
                if (cur.isNotEmpty()) cur.append("\n\n")
                cur.append(p)
            } else if (p.length <= maxChars) {
                flush()
                cur.append(p)
            } else {
                splitTextSafely(p, maxChars).forEach { chunk ->
                    if (parts.isEmpty() || parts.last().length + chunk.length + 2 > maxChars) {
                        parts.add(chunk)
                    } else {
                        parts[parts.lastIndex] = parts.last() + "\n\n" + chunk
                    }
                }
                cur.clear()
            }
        }
        flush()
        return parts
    }

    private fun splitTextSafely(text: String, maxChars: Int = 2800): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val parts = mutableListOf<String>()
        var currentPart = ""
        val sentences = text.split(Regex("(?<=[.!?])\\s+"))

        for (sentence in sentences) {
            if ((currentPart + if (currentPart.isEmpty()) "" else " " + sentence).length <= maxChars) {
                currentPart += if (currentPart.isEmpty()) sentence else " $sentence"
            } else {
                if (currentPart.isNotEmpty()) {
                    parts.add(currentPart.trim())
                    currentPart = sentence
                } else {
                    val words = sentence.split(" ")
                    var wordPart = ""
                    for (word in words) {
                        if ((wordPart + if (wordPart.isEmpty()) "" else " " + word).length <= maxChars) {
                            wordPart += if (wordPart.isEmpty()) word else " $word"
                        } else {
                            if (wordPart.isNotEmpty()) {
                                parts.add(wordPart.trim())
                                wordPart = word
                            } else {
                                parts.add(word.take(maxChars))
                                wordPart = ""
                            }
                        }
                    }
                    if (wordPart.isNotEmpty()) currentPart = wordPart
                }
            }
        }
        if (currentPart.isNotEmpty()) parts.add(currentPart.trim())
        return parts.filter { it.isNotBlank() }
    }

    private fun readWavSampleRate(file: File): Int? {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(44)
                raf.readFully(header)
                val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                if (String(header.copyOfRange(0, 4)) != "RIFF" || String(header.copyOfRange(8, 12)) != "WAVE") {
                    return null
                }
                buf.position(24)
                buf.int
            }
        } catch (e: Exception) {
            Log.w("TTSManager", "Не удалось прочитать sampleRate WAV: ${file.name}", e)
            null
        }
    }

    private fun readWavDurationMs(file: File): Long? {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(44)
                raf.readFully(header)
                val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                if (String(header.copyOfRange(0, 4)) != "RIFF" || String(header.copyOfRange(8, 12)) != "WAVE") {
                    return null
                }
                val sampleRate = buf.getInt(24)
                val bitsPerSample = buf.getShort(34).toInt()
                val channels = buf.getShort(22).toInt()
                val byteRate = sampleRate * channels * bitsPerSample / 8
                val dataSize = buf.getInt(40)
                val durationSec = dataSize.toDouble() / byteRate.toDouble()
                (durationSec * 1000).toLong()
            }
        } catch (e: Exception) {
            Log.w("TTSManager", "Не удалось прочитать длительность WAV: ${file.name}", e)
            null
        }
    }

    // Новое: чтение полного формата WAV
    private data class WavMeta(val sampleRate: Int, val channels: Int, val bitsPerSample: Int, val durationMs: Long?)
    private fun readWavMeta(file: File): WavMeta? {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(44)
                raf.readFully(header)
                val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                if (String(header.copyOfRange(0, 4)) != "RIFF" || String(header.copyOfRange(8, 12)) != "WAVE") {
                    return null
                }
                val channels = buf.getShort(22).toInt()
                val sampleRate = buf.getInt(24)
                val bitsPerSample = buf.getShort(34).toInt()
                val byteRate = sampleRate * channels * bitsPerSample / 8
                val dataSize = buf.getInt(40)
                val durationSec = if (byteRate > 0) dataSize.toDouble() / byteRate.toDouble() else 0.0
                val durMs = (durationSec * 1000).toLong()
                WavMeta(sampleRate, channels, bitsPerSample, durMs)
            }
        } catch (e: Exception) {
            Log.w("TTSManager", "Не удалось прочитать метаданные WAV: ${file.name}", e)
            null
        }
    }

    private fun createSilenceWav(durationMs: Int, sampleRate: Int = 22050, channels: Int = 1, bitsPerSample: Int = 16): File {
        val numSamples = (durationMs.toLong() * sampleRate / 1000L).toInt()
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = numSamples * blockAlign
        val totalSize = 36 + dataSize

        val file = File(context.cacheDir, "silence_${durationMs}ms_${sampleRate}hz_${channels}ch_${bitsPerSample}bit.wav")
        file.outputStream().use { os ->
            fun writeLE(value: Int, bytes: Int) {
                repeat(bytes) { i -> os.write((value shr (8 * i)) and 0xFF) }
            }
            os.write("RIFF".toByteArray())
            writeLE(totalSize, 4)
            os.write("WAVE".toByteArray())
            os.write("fmt ".toByteArray())
            writeLE(16, 4)
            writeLE(1, 2) // PCM
            writeLE(channels, 2)
            writeLE(sampleRate, 4)
            writeLE(byteRate, 4)
            writeLE(blockAlign, 2)
            writeLE(bitsPerSample, 2)
            os.write("data".toByteArray())
            writeLE(dataSize, 4)

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

    private suspend fun synthesizePartToWav(text: String, partIndex: Int, baseUtteranceId: String): File? {
        return suspendCancellableCoroutine { continuation ->
            val utteranceId = "${baseUtteranceId}_part_${partIndex}"
            val tempWavFile = File(context.cacheDir, "${utteranceId}.wav")
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
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
                        tts?.setOnUtteranceProgressListener(null)
                        if (continuation.isActive) {
                            continuation.resume(tempWavFile)
                        }
                    }
                }

                @Deprecated("deprecated in API level 21")
                override fun onError(id: String?) {
                    if (id == utteranceId) {
                        Log.e("TTSManager", "❌ TTS ошибка (legacy) для части $partIndex ($utteranceId)")
                        tts?.setOnUtteranceProgressListener(null)
                        if (continuation.isActive) continuation.resume(null)
                        tempWavFile.delete()
                    }
                }

                override fun onError(id: String?, errorCode: Int) {
                    if (id == utteranceId) {
                        Log.e("TTSManager", "❌ TTS ошибка для части $partIndex ($utteranceId). Код: $errorCode")
                        tts?.setOnUtteranceProgressListener(null)
                        if (continuation.isActive) continuation.resume(null)
                        tempWavFile.delete()
                    }
                }

                override fun onStop(id: String?, interrupted: Boolean) {
                    if (id == utteranceId && interrupted) {
                        Log.w("TTSManager", "⏹️ TTS остановлен (прерван) для части $partIndex ($utteranceId)")
                        tts?.setOnUtteranceProgressListener(null)
                        if (continuation.isActive) continuation.resume(null)
                        tempWavFile.delete()
                    }
                }
            }

            tts?.setOnUtteranceProgressListener(listener)

            Log.d("TTSManager", "🎬 Начинаем TTS синтез части $partIndex: ${tempWavFile.absolutePath}")
            val result = tts?.synthesizeToFile(text, params, tempWavFile, utteranceId)

            if (result == TextToSpeech.ERROR) {
                Log.e("TTSManager", "❌ tts.synthesizeToFile ERROR для части $partIndex ($utteranceId)")
                tts?.setOnUtteranceProgressListener(null)
                if (continuation.isActive) continuation.resume(null)
                tempWavFile.delete()
            } else if (result == TextToSpeech.SUCCESS) {
                Log.d("TTSManager", "✅ tts.synthesizeToFile вызван для части $partIndex ($utteranceId)")
            }

            continuation.invokeOnCancellation {
                Log.d("TTSManager", "🚫 TTS Coroutine отменена для части $partIndex ($utteranceId)")
                tts?.stop()
                tts?.setOnUtteranceProgressListener(null)
                tempWavFile.delete()
            }
        }
    }

    // Новое: интерфейс для обратного вызова прогресса
    interface SynthesisProgressCallback {
        fun onProgress(current: Int, total: Int)
        fun onStarted(messageCount: Int)
        fun onCompleted()
    }

    // Старый метод оставляем для совместимости
    suspend fun convertToAudio(texts: List<String>, pauseMs: Int = 1200): File? {
        val res = convertToAudioWithChapters(texts, pauseMs)
        return res?.file
    }

    data class AudioWithChapters(
        val file: File,
        val chaptersMs: List<Long>
    )

    // Новое: утилита для печати логов FFmpeg-сессии
    private fun logFfmpegSession(prefix: String, session: com.arthenica.ffmpegkit.Session) {
        try {
            session.logs.forEach { logLine ->
                Log.d("TTSManager", "$prefix FFmpeg: ${logLine.message}")
            }
        } catch (_: Exception) { }
    }

    // Новое: ресемплинг WAV под эталонный формат
    private fun resampleWavToFormat(input: File, sampleRate: Int, channels: Int, bitsPerSample: Int): File? {
        val fmt = when (bitsPerSample) {
            8 -> "u8"
            16 -> "s16"
            24 -> "s32" // ближайший безопасный для ffmpeg sample_fmt
            32 -> "s32"
            else -> "s16"
        }
        val out = File(input.parentFile, input.nameWithoutExtension + "_resampled.wav")
        val cmd = arrayOf("-y", "-i", input.absolutePath, "-ar", sampleRate.toString(), "-ac", channels.toString(), "-sample_fmt", fmt, out.absolutePath)
        Log.d("TTSManager", "🎛️ Ресемплинг: ${input.name} -> ${out.name} (sr=$sampleRate, ch=$channels, bps=$bitsPerSample, fmt=$fmt)")
        val session = FFmpegKit.executeWithArguments(cmd)
        logFfmpegSession("RESAMPLE", session)
        return if (ReturnCode.isSuccess(session.returnCode) && out.exists() && out.length() > 0) {
            Log.d("TTSManager", "✅ Ресемплинг успешен: ${out.name} (${out.length()} байт)")
            out
        } else {
            Log.e("TTSManager", "❌ Ресемплинг не удался. Код: ${session.returnCode}")
            if (out.exists()) out.delete()
            null
        }
    }

    // Новый метод с обратным вызовом прогресса
    suspend fun convertToAudioWithChaptersWithCallback(
        texts: List<String>,
        pauseMs: Int = 1200,
        progressCallback: SynthesisProgressCallback?
    ): AudioWithChapters? {
        Log.d("TTSManager", "🎵 === convertToAudioWithChaptersWithCallback() НАЧАЛО ===")
        val stackTrace = Thread.currentThread().stackTrace
        Log.d("TTSManager", "📍 Стек вызовов convertToAudioWithChaptersWithCallback:")
        stackTrace.take(8).forEach { element ->
            Log.d("TTSManager", "   ${element.className}.${element.methodName}(${element.fileName}:${element.lineNumber})")
        }

        if (!ensureTtsInitialized() || tts == null) {
            Log.e("TTSManager", "TTS не инициализирован. Невозможно конвертировать в аудио.")
            return null
        }

        Log.d("TTSManager", "🎯 Начинаем конвертацию с уже установленными параметрами:")
        Log.d("TTSManager", "   pitch=${currentAppliedPitch}, rate=${currentAppliedRate}")
        Log.d("TTSManager", "   voiceParametersApplied=$voiceParametersApplied")
        Log.d("TTSManager", "   Счетчики: pitch=$pitchChangeCount, rate=$rateChangeCount, voice=$voiceChangeCount")

        // Вызываем стартовый callback
        progressCallback?.onStarted(texts.size)

        val filteredNews = dropTrivial(texts)
        if (filteredNews.isEmpty()) {
            Log.w("TTSManager", "Нет содержательных сообщений для синтеза.")
            progressCallback?.onCompleted()
            return null
        }

        val baseUtteranceId = "ttsAudioConversion_${System.currentTimeMillis()}"

        val wavFiles = mutableListOf<File>()
        var silenceFile: File? = null
        var baselineFormat: WavMeta? = null

        val chaptersMs = mutableListOf<Long>()
        var offsetMs = 0L
        var realNewsIndex = 0 // Счетчик только реальных новостей

        // Новые переменные для отслеживания прогресса
        val totalParts = filteredNews.sumOf { splitByParagraphs(it, 2800).size }
        var processedParts = 0

        filteredNews.forEachIndexed { newsIndex, raw ->
            val cleaned = cleanTextForTts(raw)
            val deduped = deduplicateLines(cleaned)
            val normalized = normalizeNumbers(deduped)
            val formatted = formatForIntonation(normalized)

            // Применяем форматирование для речи только к реальным новостям
            val speechReadyText = if (!formatted.matches(Regex("^Новости из канала.*:$"))) {
                formatForSpeech(formatted)  // Только для реальных новостей
            } else {
                formatted  // Заголовки каналов оставляем как есть
            }

            val finalText = enhanceWithSSML(speechReadyText)

            if (finalText.isBlank()) {
                Log.d("TTSManager", "⏭️ Пропущена пустая новость после форматирования (index=$newsIndex)")
                return@forEachIndexed
            }

            // Фиксируем начало главы для всех сообщений
            if (!formatted.matches(Regex("^Новости из канала.*:$"))) {
                // Это реальная новость
                chaptersMs.add(offsetMs)
                realNewsIndex++
                Log.d("TTSManager", "Глава фиксирована для реальной новости #$realNewsIndex (общий индекс $newsIndex)")
            } else {
                // Это заголовок канала - тоже создаем главу для навигации
                chaptersMs.add(offsetMs)
                Log.d("TTSManager", "Глава фиксирована для заголовка канала (index=$newsIndex)")
            }

            val parts = splitByParagraphs(formatted, 2800)
            Log.d("TTSManager", "📝 Новость ${newsIndex + 1}/${filteredNews.size}: частей=${parts.size}, длина=${formatted.length}")

            for (i in parts.indices) {
                val wav = synthesizePartToWav(parts[i], (newsIndex + 1) * 1000 + (i + 1), baseUtteranceId)
                if (wav == null || !wav.exists() || wav.length() == 0L) {
                    Log.e("TTSManager", "❌ Не удалось синтезировать часть ${i + 1} для новости ${newsIndex + 1}")
                    wavFiles.forEach { if (it.exists()) it.delete() }
                    silenceFile?.delete()
                    progressCallback?.onCompleted()
                    return null
                }

                // Читаем метаданные части
                val meta = readWavMeta(wav)
                if (meta == null) {
                    Log.e("TTSManager", "❌ Не удалось прочитать формат WAV части ${i + 1}")
                    wavFiles.forEach { if (it.exists()) it.delete() }
                    silenceFile?.delete()
                    progressCallback?.onCompleted()
                    return null
                }

                Log.d("TTSManager", "📄 WAV часть: file=${wav.name} sr=${meta.sampleRate}Hz ch=${meta.channels} bps=${meta.bitsPerSample} dur=${meta.durationMs}ms")

                // Фиксируем эталонный формат на первой части
                if (baselineFormat == null) {
                    baselineFormat = meta
                    Log.d("TTSManager", "📌 Эталонный формат: sr=${baselineFormat!!.sampleRate} ch=${baselineFormat!!.channels} bps=${baselineFormat!!.bitsPerSample}")
                    if (pauseMs > 0) {
                        silenceFile = createSilenceWav(pauseMs, sampleRate = baselineFormat!!.sampleRate, channels = baselineFormat!!.channels, bitsPerSample = baselineFormat!!.bitsPerSample)
                        Log.d("TTSManager", "🤫 Сгенерирован файл тишины: ${silenceFile?.name}")
                    }
                }

                // Если формат части отличается — пересэмплируем
                val usesBaseline = meta.sampleRate == baselineFormat!!.sampleRate &&
                        meta.channels == baselineFormat!!.channels &&
                        meta.bitsPerSample == baselineFormat!!.bitsPerSample

                val usedWav = if (!usesBaseline) {
                    Log.w("TTSManager", "⚠️ Формат части отличается от эталона. Будет выполнен ресемплинг.")
                    val resampled = resampleWavToFormat(
                        input = wav,
                        sampleRate = baselineFormat!!.sampleRate,
                        channels = baselineFormat!!.channels,
                        bitsPerSample = baselineFormat!!.bitsPerSample
                    )
                    if (resampled != null) {
                        // удаляем оригинал, используем ресемплированный
                        try { wav.delete() } catch (_: Exception) {}
                        resampled
                    } else {
                        Log.e("TTSManager", "❌ Ресемплинг части не удался")
                        wavFiles.forEach { if (it.exists()) it.delete() }
                        silenceFile?.delete()
                        progressCallback?.onCompleted()
                        return null
                    }
                } else {
                    wav
                }

                wavFiles.add(usedWav)

                // Прибавим длительность части к offset
                val dur = readWavDurationMs(usedWav) ?: meta.durationMs ?: 0L
                offsetMs += dur

                // Обновляем прогресс
                processedParts++
                if (totalParts > 0) {
                    val progress = (processedParts * 100 / totalParts).coerceAtMost(100)
                    progressCallback?.onProgress(progress, 100)
                }
            }

            // Пауза после каждой новости (кроме последней)
            if (newsIndex != filteredNews.lastIndex && pauseMs > 0 && silenceFile != null) {
                wavFiles.add(silenceFile!!)
                offsetMs += pauseMs
            }
        }

        // Обновляем прогресс до 100% перед объединением
        if (totalParts > 0) {
            progressCallback?.onProgress(100, 100)
        }

        if (wavFiles.isEmpty()) {
            Log.e("TTSManager", "❌ Не удалось создать ни одного WAV файла")
            progressCallback?.onCompleted()
            return null
        }

        val combinedWavFile = File(context.cacheDir, "${baseUtteranceId}_combined.wav")
        val concatSuccess = if (wavFiles.size == 1) {
            wavFiles.first().renameTo(combinedWavFile)
        } else {
            Log.d("TTSManager", "🔗 Объединяем ${wavFiles.size} WAV файлов в один (включая тишину между новостями)")
            AudioUtils.concatWavFiles(wavFiles, combinedWavFile)
        }

        wavFiles.forEach { file ->
            if (file.exists() && file != combinedWavFile && (silenceFile == null || file.absolutePath != silenceFile!!.absolutePath)) {
                file.delete()
                Log.d("TTSManager", "🗑️ Удалили временный WAV: ${file.name}")
            }
        }

        if (!concatSuccess || !combinedWavFile.exists() || combinedWavFile.length() == 0L) {
            Log.e("TTSManager", "❌ Не удалось объединить WAV файлы")
            combinedWavFile.delete()
            silenceFile?.delete()
            progressCallback?.onCompleted()
            return null
        }

        Log.d("TTSManager", "✅ Объединенный WAV создан: ${combinedWavFile.name} (${combinedWavFile.length()} байт)")

        val mp3File = convertToMp3(combinedWavFile)
        combinedWavFile.delete()
        silenceFile?.delete()

        if (mp3File != null) {
            Log.d("TTSManager", "🎉 Синтез завершен успешно: ${mp3File.name} (${mp3File.length()} байт)")
            Log.d("TTSManager", "📊 Итоговая статистика:")
            Log.d("TTSManager", "   Новостей: $realNewsIndex, пауза: ${pauseMs}мс")
            Log.d("TTSManager", "   Счетчики изменений: pitch=$pitchChangeCount, rate=$rateChangeCount, voice=$voiceChangeCount")
            Log.d("TTSManager", "🎵 === convertToAudioWithChaptersWithCallback() КОНЕЦ ===")
            progressCallback?.onCompleted()
            return AudioWithChapters(mp3File, chaptersMs)
        } else {
            Log.e("TTSManager", "❌ Не удалось конвертировать в MP3")
        }

        progressCallback?.onCompleted()
        return null
    }

    // Старый метод для совместимости
    suspend fun convertToAudioWithChapters(texts: List<String>, pauseMs: Int = 1200): AudioWithChapters? {
        return convertToAudioWithChaptersWithCallback(texts, pauseMs, null)
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

            // Дополнительные логи FFmpeg
            logFfmpegSession("MP3", session)

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
        Log.d("TTSManager", "🔌 === shutdown() НАЧАЛО ===")
        val stackTrace = Thread.currentThread().stackTrace
        Log.d("TTSManager", "📍 Стек вызовов shutdown:")
        stackTrace.take(6).forEach { element ->
            Log.d("TTSManager", "   ${element.className}.${element.methodName}(${element.fileName}:${element.lineNumber})")
        }

        Log.d("TTSManager", "📊 Финальные счетчики: pitch=$pitchChangeCount, rate=$rateChangeCount, voice=$voiceChangeCount")
        Log.d("TTSManager", "🔄 Выключаем TTS движок.")

        ttsInitialized.set(false)
        initializationContinuation?.cancel()
        initializationContinuation = null
        tts?.stop()
        tts?.shutdown()
        tts = null

        voiceParametersApplied = false
        currentAppliedPitch = null
        currentAppliedRate = null
        pitchChangeCount = 0
        rateChangeCount = 0
        voiceChangeCount = 0

        Log.d("TTSManager", "🔌 === shutdown() КОНЕЦ ===")
    }

    fun setVoiceByName(voiceName: String) {
        TTSDebugTracker.trackVoiceChange(voiceName, "setVoiceByName method")

        Log.d("TTSManager", "🎤 === setVoiceByName($voiceName) НАЧАЛО ===")
        val stackTrace = Thread.currentThread().stackTrace
        Log.d("TTSManager", "📍 Стек вызовов setVoiceByName:")
        stackTrace.take(8).forEach { element ->
            Log.d("TTSManager", "   ${element.className}.${element.methodName}(${element.fileName}:${element.lineNumber})")
        }

        val voice = tts?.voices?.firstOrNull { it.name == voiceName }
        voice?.let {
            val voiceEntry = VoiceMappings.mapVoice(voice)
            Log.d("TTSManager", "🔄 Применяем выбранный голос: ${voice.name} -> ${voiceEntry.displayName}")
            tts?.language = voice.locale
            tts?.voice = voice
            voiceChangeCount++
            PreferenceManager.saveTtsVoiceName(context, voiceName)

            Log.d("TTSManager", "💾 Голос сохранен и применен: $voiceName (параметры НЕ изменены)")
            Log.d("TTSManager", "📊 Текущие параметры остаются: pitch=${currentAppliedPitch}, rate=${currentAppliedRate}")
            Log.d("TTSManager", "📊 Счетчики: pitch=$pitchChangeCount, rate=$rateChangeCount, voice=$voiceChangeCount")
        } ?: run {
            Log.w("TTSManager", "❗ Голос '$voiceName' не найден среди доступных")
        }
        Log.d("TTSManager", "🎤 === setVoiceByName($voiceName) КОНЕЦ ===")
    }

    fun speak(text: String) {
        Log.d("TTSManager", "🗣️ === speak() НАЧАЛО ===")
        val stackTrace = Thread.currentThread().stackTrace
        Log.d("TTSManager", "📍 Стек вызовов speak:")
        stackTrace.take(6).forEach { element ->
            Log.d("TTSManager", "   ${element.className}.${element.methodName}(${element.fileName}:${element.lineNumber})")
        }

        if (ttsInitialized.get()) {
            val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "TTS_SAMPLE")
            Log.d("TTSManager", "🗣️ Говорим с текущими параметрами:")
            Log.d("TTSManager", "   pitch=${currentAppliedPitch}, rate=${currentAppliedRate}")
            Log.d("TTSManager", "   Результат speak: $result")
            Log.d("TTSManager", "   Счетчики: pitch=$pitchChangeCount, rate=$rateChangeCount, voice=$voiceChangeCount")
        } else {
            Log.w("TTSManager", "⚠️ TTS не инициализирован, speak пропущен.")
        }
        Log.d("TTSManager", "🗣️ === speak() КОНЕЦ ===")
    }

    fun refreshVoice() {
        TTSDebugTracker.trackTTSRefresh("refreshVoice method called")

        Log.d("TTSManager", "🔁 === refreshVoice() НАЧАЛО ===")
        val stackTrace = Thread.currentThread().stackTrace
        Log.d("TTSManager", "📍 Стек вызовов refreshVoice:")
        stackTrace.take(8).forEach { element ->
            Log.d("TTSManager", "   ${element.className}.${element.methodName}(${element.fileName}:${element.lineNumber})")
        }

        if (ttsInitialized.get()) {
            val savedVoiceName = PreferenceManager.getTtsVoiceName(context)
            Log.d("TTSManager", "💾 Сохраненное имя голоса: $savedVoiceName")

            val selectedVoice = tts?.voices?.find { it.name == savedVoiceName }
            selectedVoice?.let {
                val voiceEntry = VoiceMappings.mapVoice(it)
                Log.d("TTSManager", "🔁 refreshVoice(): применяем голос ${it.name} -> ${voiceEntry.displayName}")
                tts?.language = it.locale
                tts?.voice = it
                voiceChangeCount++
            } ?: run {
                Log.w("TTSManager", "❗ refreshVoice(): голос $savedVoiceName не найден среди доступных")
            }

            Log.d("TTSManager", "🔄 refreshVoice() завершен: голос обновлен, параметры НЕ ИЗМЕНЕНЫ")
            Log.d("TTSManager", "   pitch=${currentAppliedPitch}, rate=${currentAppliedRate}")
            Log.d("TTSManager", "   Счетчики: pitch=$pitchChangeCount, rate=$rateChangeCount, voice=$voiceChangeCount")
        }
        Log.d("TTSManager", "🔁 === refreshVoice() КОНЕЦ ===")
    }

    // 🔥 НОВЫЕ МЕТОДЫ ДЛЯ РАБОТЫ С ИНДИВИДУАЛЬНЫМИ НАСТРОЙКАМИ ГОЛОСОВ
    fun updatePitchForVoice(voiceName: String, pitch: Float) {
        Log.d("TTSManager", "🎚️ Сохраняем тембр для голоса $voiceName: $pitch")
        PreferenceManager.saveTtsPitchForVoice(context, voiceName, pitch)
    }

    fun updateRateForVoice(voiceName: String, rate: Float) {
        Log.d("TTSManager", "⏩ Сохраняем скорость для голоса $voiceName: $rate")
        PreferenceManager.saveTtsRateForVoice(context, voiceName, rate)
    }

    fun getPitchForVoice(voiceName: String): Float {
        val savedPitch = PreferenceManager.getTtsPitchForVoice(context, voiceName)
        Log.d("TTSManager", "🎚️ Загружен тембр для $voiceName: $savedPitch")
        return savedPitch
    }

    fun getRateForVoice(voiceName: String): Float {
        val savedRate = PreferenceManager.getTtsRateForVoice(context, voiceName)
        Log.d("TTSManager", "⏩ Загружена скорость для $voiceName: $savedRate")
        return savedRate
    }

    fun applyVoiceSettings(voiceName: String) {
        Log.d("TTSManager", "🎚️ Применяем настройки для голоса: $voiceName")
        val pitch = getPitchForVoice(voiceName)
        val rate = getRateForVoice(voiceName)

        val pitchResult = tts?.setPitch(pitch)
        val rateResult = tts?.setSpeechRate(rate)

        currentAppliedPitch = pitch
        currentAppliedRate = rate

        Log.d("TTSManager", "🎚️ Применен тембр: $pitch (результат: $pitchResult)")
        Log.d("TTSManager", "⏩ Применена скорость: $rate (результат: $rateResult)")
    }
}