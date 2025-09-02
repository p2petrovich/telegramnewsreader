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
            val savedVoiceName = PreferenceManager.getTtsVoiceName(context)
            if (savedVoiceName != null) {
                // Применяем индивидуальные настройки для сохраненного голоса
                applyVoiceSettings(savedVoiceName)
            } else {
                // Если голос не сохранен, применяем глобальные настройки
                val pitch = PreferenceManager.getTtsPitch(context)
                val rate = PreferenceManager.getTtsRate(context)
                Log.d("TTSManager", "📖 Считанные глобальные настройки: pitch=$pitch, rate=$rate")

                tts?.setPitch(pitch)
                tts?.setSpeechRate(rate)
                pitchChangeCount++
                rateChangeCount++

                TTSDebugTracker.trackPitchChange(pitch, "applyVoiceParametersOnce - global init")
                TTSDebugTracker.trackRateChange(rate, "applyVoiceParametersOnce - global init")

                currentAppliedPitch = pitch
                currentAppliedRate = rate
            }
            voiceParametersApplied = true

            Log.d("TTSManager", "🎯 Параметры голоса применены ЕДИНОКРАТНО")
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

    private fun numberToOrdinalRu(number: Int): String {
        return when (number) {
            1 -> "первое"; 2 -> "второе"; 3 -> "третье"; 4 -> "четвёртое"; 5 -> "пятое"
            6 -> "шестое"; 7 -> "седьмое"; 8 -> "восьмое"; 9 -> "девятое"; 10 -> "десятое"
            11 -> "одиннадцатое"; 12 -> "двенадцатое"; 13 -> "тринадцатое"; 14 -> "четырнадцатоео"
            15 -> "пятнадцатое"; 16 -> "шестнадцатое"; 17 -> "семнадцатое"; 18 -> "восемнадцатое"
            19 -> "девятнадцатое"; 20 -> "двадцатое"; 21 -> "двадцать первое"; 22 -> "двадцать второе"
            23 -> "двадцать третье"; 24 -> "двадцать четвёртое"; 25 -> "двадцать пятое"
            26 -> "двадцать шестое"; 27 -> "двадцать седьмое"; 28 -> "двадцать восьмое"
            29 -> "двадцать девятое"; 30 -> "тридцатое"; 31 -> "тридцать первое"
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

        // *** НАЧАЛО ВСТАВКИ ДЛЯ УДАЛЕНИЯ "Фото:" и "Следить за новостями" ***
        // Удалить строку, начинающуюся с "Фото:" (с учетом возможного текста после)
        t = t.replace(Regex("""^Фото:.*$""", RegexOption.MULTILINE), "")
        // - Заканчиваются на этой же строке
        t = t.replace(Regex("""^[\p{So}\p{Sk}]?\s*(Читать РБК в Telegram|Следить за новостями РБК в Telegram|(Другие видео|Картина дня).*в телеграм-канале РБК).*$""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)), "")
        // *** КОНЕЦ ОБНОВЛЕННОЙ ВСТАВКИ ***

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

        // УЛУЧШЕНО: Обработка больших денежных сумм
        t = t.replace(Regex("\\b(\\d+[\\d\\s]*)\\s*(млн|млрд)\\s*(?:₽|руб\\.?|р\\.)\\b", RegexOption.IGNORE_CASE)) { match ->
            val num = match.groupValues[1].replace("\\s".toRegex(), "")
            val scale = if (match.groupValues[2].lowercase() == "млн") "миллионов" else "миллиардов"
            "$num $scale рублей"
        }

        // Обычные суммы
        t = t.replace(Regex("\\b(\\d+[\\d\\s]*)(?:₽|руб\\.?|р\\.)\\b", RegexOption.IGNORE_CASE), "$1 рублей")

        // НОВОЕ: Улучшенная обработка процентов с контекстом
        t = t.replace(Regex("на\\s+(\\d+[,.]?\\d*)\\s?%")) { "на ${it.groupValues[1]} процентов" }
        t = t.replace(Regex("(\\d+[,.]?\\d*)%-й")) { "${it.groupValues[1]}-процентный" }
        t = t.replace(Regex("(\\d+[,.]?\\d*)%-е")) { "${it.groupValues[1]}-процентные" }
        t = t.replace(Regex("\\b(\\d+[,.]?\\d*)\\s?%\\b")) { "${it.groupValues[1]} процентов" }

        // НОВОЕ: Обработка времени
            /* t = t.replace(Regex("\\b(\\d{1,2}):(\\d{2})\\b")) { match ->
            val hour = match.groupValues[1].toInt()
            val minute = match.groupValues[2].toInt()
            when {
                minute == 0 -> "$hour часов"
                minute < 10 -> "$hour часов ноль $minute минут"
                else -> "$hour часов $minute минут"
            }
        }*/

        // НОВОЕ: Температура
        t = t.replace(Regex("([+-]?\\d+[,.]?\\d*)\\s?°C?\\b")) { "${it.groupValues[1]} градусов" }
        t = t.replace(Regex("([+-]?\\d+[,.]?\\d*)\\s?градусов\\s+цельсия", RegexOption.IGNORE_CASE)) { "${it.groupValues[1]} градусов цельсия" }

        return t
    }

    // ВНИМАНИЕ: никаких SSML-тегов внутри текста — Android TTS их произносит.
    private fun formatForIntonation(text: String): String {
        fun smallPause(): String = ".  .  "           // ~1 сек
        fun longPause(): String = ".  .  .  .  .      " // ~2–2.5 сек
        var t = text

        // Обработка местоимений ПЕРЕД всеми остальными заменами
        t = t.replace(" в нем", " в нём")

        // Замена заголовков RT на "Главное"
        t = t.replace(Regex("\\s*‼‼‼\\s*"), "Главное")



        // Даты
        val dateRegex = Regex("\\b(\\d{1,2})\\s+(января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря)\\b")
        t = dateRegex.replace(t) { match ->
            val day = match.groupValues[1].toIntOrNull() ?: return@replace match.value
            val month = match.groupValues[2]
            "${numberToOrdinalRu(day)} $month"
        }

        // Полные даты с годом
        t = t.replace(Regex("\\b(\\d{1,2})\\s+(января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря)\\s+(\\d{4})\\b")) { match ->
            val day = match.groupValues[1].toIntOrNull() ?: return@replace match.value
            val month = match.groupValues[2]
            val year = match.groupValues[3]
            "${numberToOrdinalRu(day)} $month $year года"
        }

        // СНАЧАЛА обрабатываем годы и диапазоны (ПЕРЕМЕСТИЛИ СЮДА)
        // 2025/2026 → 2025 – 2026
        t = t.replace(Regex("(\\d{4})/(\\d{4})")) { "${it.groupValues[1]} – ${it.groupValues[2]}" }
        // 2024-2025 → 2024 – 2025
        t = t.replace(Regex("(\\d{4})-(\\d{4})")) { "${it.groupValues[1]} – ${it.groupValues[2]}" }
        // Случаи вида "3/4 финала" → "три четверти финала"
        t = t.replace(Regex("\\b(\\d)\\/(\\d)\\s+финала\\b")) { "${it.groupValues[1]} четверти финала" }

        // Единицы измерения
        t = t.replace(Regex("\\bкм/ч\\b", RegexOption.IGNORE_CASE), "километров в час")
        t = t.replace(Regex("\\bкм\\b", RegexOption.IGNORE_CASE), "километров")
        t = t.replace(Regex("\\bм\\b(?!\\w)", RegexOption.IGNORE_CASE), "метров")
        t = t.replace(Regex("\\bсм\\b(?!\\w)", RegexOption.IGNORE_CASE), "сантиметров")
        t = t.replace(Regex("\\bмм\\b(?!\\w)", RegexOption.IGNORE_CASE), "миллиметров")
        t = t.replace(Regex("\\bкг\\b(?!\\w)", RegexOption.IGNORE_CASE), "килограммов")
        t = t.replace(Regex("\\bг\\b(?!\\w)(?<!\\d)", RegexOption.IGNORE_CASE), "граммов")

        // ПОТОМ математические операции (теперь годы уже обработаны)
        t = t.replace(Regex("\\b(\\d+)\\s?\\+\\s?(\\d+)\\b")) { "${it.groupValues[1]} плюс ${it.groupValues[2]}" }
        t = t.replace(Regex("\\b(\\d+)\\s?-\\s?(\\d+)\\b")) { "${it.groupValues[1]} минус ${it.groupValues[2]}" }
        t = t.replace(Regex("\\b(\\d+)\\s?\\*\\s?(\\d+)\\b")) { "${it.groupValues[1]} умножить на ${it.groupValues[2]}" }
        t = t.replace(Regex("\\b(\\d+)\\s?/\\s?(\\d+)\\b")) { "${it.groupValues[1]} разделить на ${it.groupValues[2]}" }

        // Нумерованные списки и буллиты
        t = t.replace(Regex("^(\\d+)\\.\\s+", RegexOption.MULTILINE)) { "${it.groupValues[1]}. " }
        t = t.replace(Regex("^[•·∙▪▫◦‣⁃]\\s+", RegexOption.MULTILINE)) { "— " }


        // Удаление белых квадратов
        t = t.replace(Regex("[◻️◻⬜▫□]+"), "")

        // Длинные паузы после организаций
        val orgs = listOf(
            "МЧС России", "МВД России", "ФСБ России",
            "Минобороны России", "Росгвардии", "Генпрокуратуры"
        )
        for (org in orgs) {
            t = t.replace(Regex("$org\\."), "$org.${longPause()}\n\n")
        }

        // Форматирование тире
        t = t.replace(Regex("(?m)^[-•]\\s+"), "— ")
        t = t.replace(Regex(" - "), " — ")
        t = t.replace(Regex("\\.\\.\\."), "…")

        // Заменить точку с запятой перед абзацным отступом на точку с отступом
        // Заменить точку с запятой перед абзацным отступом на точку с отступом
        // Заменить точку с запятой перед абзацным отступом на точку с отступом
        t = t.replace(Regex(";\\s*(?=\\n{2,})"), ". ") // Правильное экранирование \n

        // Переносы после предложений для пауз между абзацами
        val abbrEnd = "(?<!т\\.д)(?<!т\\.п)(?<!млн)(?<!млрд)(?<!г)(?<!ул)(?<!просп)(?<!др)(?<!и\\.т\\.д)(?<!и\\.т\\.п)"
        t = t.replace(Regex("$abbrEnd(?<=[.!?])\\s+"), "\n\n")

        // Маленькая пауза на каждом двойном переносе
        t = t.replace(Regex("\\n\\n"), smallPause() + "\n\n")

        // Маленькая пауза после закрывающих кавычек и скобок
        t = t.replace(Regex("»")) { "»${smallPause()}" }
        t = t.replace(Regex("\\)")) { ")${smallPause()}" }

        return t.trim()
    }




    private fun formatForSpeech(text: String): String {
        var processed = text

        // РАСШИРЕНО: Больше аббревиатур



        // НОВОЕ: Технические сокращения
        processed = processed.replace(Regex("\\b(IT|AI|VR|AR|GPS|USB|WiFi|Bluetooth|HTML|CSS|JS|API|SQL|XML|JSON)\\b", RegexOption.IGNORE_CASE)) {
            when (it.value.uppercase()) {
                "IT" -> "Ай-Ти"
                "AI" -> "искусственный интеллект"
                "VR" -> "виртуальная реальность"
                "AR" -> "дополненная реальность"
                "GPS" -> "Джи-Пи-Эс"
                "USB" -> "ЮСБ"
                "WIFI" -> "Вай-Фай"
                "BLUETOOTH" -> "Блютуз"
                "HTML" -> "ЭйчТиЭмЭль"
                "CSS" -> "Си-Эс-Эс"
                "JS" -> "Джава-Скрипт"
                "API" -> "АПИ"
                "SQL" -> "Эс-Кью-Эль"
                "XML" -> "ИксЭмЭль"
                "JSON" -> "Джейсон"
                else -> it.value
            }
        }

        processed = processed.replace(Regex("\\bMAX\\b"), "Макс")

        // УЛУЧШЕНО: Даты в формате ДД.ММ.ГГГГ
        processed = processed.replace(Regex("\\b(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})\\b")) {
            val day = it.groupValues[1]
            val month = it.groupValues[2]
            val year = it.groupValues[3]
            "$day число $month месяца $year года"
        }

        // НОВОЕ: Обработка веб-адресов, которые остались
        processed = processed.replace(Regex("\\b[a-zA-Z0-9]+\\.(com|ru|org|net)\\b", RegexOption.IGNORE_CASE)) {
            val parts = it.value.split(".")
            "${parts[0]} точка ${parts[1]}"
        }

        // НОВОЕ: Email-адреса
        processed = processed.replace(Regex("\\b[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}\\b")) {
            "электронная почта"
        }

        // Важные слова — без SSML
        val importantWords = listOf("важно", "внимание", "срочно", "эксклюзив", "молния", "Breaking", "BREAKING")
        importantWords.forEach { word ->
            processed = processed.replace(Regex("\\b($word)\\b", RegexOption.IGNORE_CASE)) {
                "ВАЖНО: ${it.groupValues[1].lowercase()}"
            }
        }

        // РАСШИРЕНО: Больше ключевых слов
        val keyWords = mapOf(
            "украина" to "Украина",
            "путин" to "Путин",
            "зеленский" to "Зеленский",
            "трамп" to "Трамп",
            "байден" to "Байден",
            "си цзиньпин" to "Си Цзиньпин",
            "россия" to "Россия",
            "америка" to "Америка",
            "европа" to "Европа",
            "китай" to "Китай",
            "япония" to "Япония",
            "германия" to "Германия",
            "франция" to "Франция",
            "великобритания" to "Великобритания",
            "индия" to "Индия",
            "иран" to "Иран",
            "израиль" to "Израиль"
        )
        keyWords.forEach { (word, pronounced) ->
            processed = processed.replace(Regex("\\b$word\\b", RegexOption.IGNORE_CASE)) { pronounced }
        }

        return processed.trim()
    }

    private fun dropTrivial(texts: List<String>): List<String> {
        val trivial = Regex("^(фото|видео|аудио|ссылка|репост)\\b.*$", RegexOption.IGNORE_CASE)
        val subscribe = Regex("(?i)^.*\\b(подписывай(ся|тесь)?|подписка)\\b.*$", RegexOption.IGNORE_CASE)
        // НОВОЕ: Дополнительные паттерны для фильтрации
        val spam = Regex("(?i)^.*(лайк|репост|поделись|нажми|кликни|переходи)\\b.*$", RegexOption.IGNORE_CASE)

        return texts.map { it.trim() }
            .filter { text ->
                val isTrivial = text.length < 8 || trivial.containsMatchIn(text)
                val hasSubscribe = subscribe.containsMatchIn(text)
                val hasSpam = spam.containsMatchIn(text)
                if (hasSubscribe || hasSpam) {
                    Log.d("TTSManager", "⚠️ Найден спам/подписка: '$text'")
                }
                !(isTrivial || hasSubscribe || hasSpam)
            }
    }

    // УЛУЧШЕНО: Уменьшен размер чанков для лучшего качества TTS
    private fun splitByParagraphs(text: String, maxChars: Int = 1800): List<String> {
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

    // УЛУЧШЕНО: Уменьшен размер чанков
    private fun splitTextSafely(text: String, maxChars: Int = 1800): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val parts = mutableListOf<String>()
        var currentPart = ""

        // УЛУЧШЕНО: Лучшее разделение по предложениям
        val sentences = text.split(Regex("(?<=[.!?…])\\s+"))

        for (sentence in sentences) {
            val newLength = currentPart.length + if (currentPart.isEmpty()) 0 else 1 + sentence.length

            if (newLength <= maxChars) {
                currentPart += if (currentPart.isEmpty()) sentence else " $sentence"
            } else {
                if (currentPart.isNotEmpty()) {
                    parts.add(currentPart.trim())
                    currentPart = sentence
                } else {
                    // Предложение слишком длинное, разбиваем по словам
                    val words = sentence.split(" ")
                    var wordPart = ""
                    for (word in words) {
                        val wordLength = wordPart.length + if (wordPart.isEmpty()) 0 else 1 + word.length
                        if (wordLength <= maxChars) {
                            wordPart += if (wordPart.isEmpty()) word else " $word"
                        } else {
                            if (wordPart.isNotEmpty()) {
                                parts.add(wordPart.trim())
                                wordPart = word
                            } else {
                                // Слишком длинное слово, принудительно обрезаем
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

    // Полные метаданные WAV
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

    // Логи FFmpeg-сессии
    private fun logFfmpegSession(prefix: String, session: com.arthenica.ffmpegkit.Session) {
        try {
            session.logs.forEach { logLine ->
                Log.d("TTSManager", "$prefix FFmpeg: ${logLine.message}")
            }
        } catch (_: Exception) { }
    }

    // Ресемплинг WAV под эталонный формат
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

    // ==== НОВОЕ: сегментация текста на текст/паузы без SSML ====

    private val silenceCache = mutableMapOf<Int, File>()

    private sealed class Segment {
        data class Text(val text: String) : Segment()
        data class Pause(val ms: Int) : Segment()
    }

    // Генерация сегментов (текст/пауза) по пунктуации и абзацам
    private fun buildSegmentsWithPauses(input: String): List<Segment> {
        var t = input

        // Базовые паузы по знакам
        //t = t.replace(Regex(",\\s+"), ", <<BR:100>> ")
        //t = t.replace(Regex("\\.\\s+"), ". <<BR:100>> ")
        //t = t.replace(Regex(";\\s+"), "; <<BR:200>> ")
        //t = t.replace(Regex(":\\s+"), ": <<BR:200>> ")

        // Паузы между абзацами
        //t = t.replace(Regex("\\n{2,}"), " <<BR:200>> ")

        // Парсим в сегменты
        val out = mutableListOf<Segment>()
        val br = Regex("\\s*<<BR:(\\d{2,4})>>\\s*")
        var last = 0
        br.findAll(t).forEach { m ->
            val start = m.range.first
            val chunk = t.substring(last, start).trim()
            if (chunk.isNotEmpty()) out += Segment.Text(chunk)
            val ms = m.groupValues[1].toInt()
            out += Segment.Pause(ms)
            last = m.range.last + 1
        }
        val tail = t.substring(last).trim()
        if (tail.isNotEmpty()) out += Segment.Text(tail)
        return out
    }

    // Новый метод с обратным вызовом прогресса (без SSML, паузы — реально вставляем тишину)
    suspend fun convertToAudioWithChaptersWithCallback(
        texts: List<String>,
        pauseMs: Int = 1000,
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

        progressCallback?.onStarted(texts.size)

        val filteredNews = dropTrivial(texts)
        if (filteredNews.isEmpty()) {
            Log.w("TTSManager", "Нет содержательных сообщений для синтеза.")
            progressCallback?.onCompleted()
            return null
        }

        // Подготовительный проход
        data class PreparedNews(
            val originalIndex: Int,
            val formattedForIntonation: String,
            val textForSplitting: String,
            val isHeader: Boolean
        )

        val prepared = mutableListOf<PreparedNews>()

        filteredNews.forEachIndexed { newsIndex, raw ->
            val cleaned = cleanTextForTts(raw)
            val deduped = deduplicateLines(cleaned)
            val normalized = normalizeNumbers(deduped)
            val formattedForIntonation = formatForIntonation(normalized)

            val isHeader = formattedForIntonation.matches(Regex("^Новости из канала.+?:\\s*$"))
            val textForSplitting = if (!isHeader) {
                formatForSpeech(formattedForIntonation)
            } else {
                formattedForIntonation
            }

            if (textForSplitting.isBlank()) {
                Log.d("TTSManager", "⏭️ Пропущена пустая новость после formatForSpeech (index=$newsIndex)")
            } else {
                prepared += PreparedNews(
                    originalIndex = newsIndex,
                    formattedForIntonation = formattedForIntonation,
                    textForSplitting = textForSplitting,
                    isHeader = isHeader
                )
            }
        }

        if (prepared.isEmpty()) {
            Log.w("TTSManager", "После предобработки не осталось текстов для синтеза.")
            progressCallback?.onCompleted()
            return null
        }

        // Корректный totalParts — по реальному тексту после formatForSpeech
        val totalParts = prepared.sumOf { splitByParagraphs(it.textForSplitting, 2800).size }
        var processedParts = 0

        val baseUtteranceId = "ttsAudioConversion_${System.currentTimeMillis()}"

        val wavFiles = mutableListOf<File>()
        var silenceFile: File? = null
        var baselineFormat: WavMeta? = null

        val chaptersMs = mutableListOf<Long>()
        var offsetMs = 0L
        var realNewsIndex = 0

        prepared.forEachIndexed { idx, item ->
            val newsIndex = item.originalIndex

            // Фиксируем главу (для заголовков и реальных новостей)
            chaptersMs.add(offsetMs)
            if (!item.isHeader) {
                realNewsIndex++
                Log.d("TTSManager", "Глава фиксирована для реальной новости #$realNewsIndex (общий индекс $newsIndex)")
            } else {
                Log.d("TTSManager", "Глава фиксирована для заголовка канала (index=$newsIndex)")
            }

            // Разделяем на части текст, прошедший formatForSpeech
            val parts = splitByParagraphs(item.textForSplitting, 2800)
            Log.d("TTSManager", "📝 Новость ${idx + 1}/${prepared.size}: частей=${parts.size}, длина=${item.textForSplitting.length}")

            for (i in parts.indices) {
                val currentPartText = parts[i]

                // Сегментация: текстовые сегменты + паузы (без SSML)
                val segments = buildSegmentsWithPauses(currentPartText)
                Log.d("TTSManager", "🔧 Сегментация части ${i + 1}: сегментов=${segments.size}")

                val pendingPauses = mutableListOf<Int>() // паузы до определения baseline
                var segIndex = 0

                for (seg in segments) {
                    when (seg) {
                        is Segment.Text -> {
                            // Уникальный индекс файла на основе новости/части/сегмента
                            val partIndex = ((newsIndex + 1) * 1000 + (i + 1)) * 100 + segIndex
                            segIndex++

                            val wav = synthesizePartToWav(seg.text, partIndex, baseUtteranceId)
                            if (wav == null || !wav.exists() || wav.length() == 0L) {
                                Log.e("TTSManager", "❌ Не удалось синтезировать сегмент для части ${i + 1} новости ${idx + 1}")
                                wavFiles.forEach { if (it.exists()) it.delete() }
                                silenceFile?.delete()
                                progressCallback?.onCompleted()
                                return null
                            }

                            val meta = readWavMeta(wav)
                            if (meta == null) {
                                Log.e("TTSManager", "❌ Не удалось прочитать формат WAV сегмента части ${i + 1}")
                                wavFiles.forEach { if (it.exists()) it.delete() }
                                silenceFile?.delete()
                                progressCallback?.onCompleted()
                                return null
                            }

                            // Инициализируем baseline форматом первой синтезированной части
                            if (baselineFormat == null) {
                                baselineFormat = meta
                                Log.d("TTSManager", "📌 Эталонный формат: sr=${baselineFormat!!.sampleRate} ch=${baselineFormat!!.channels} bps=${baselineFormat!!.bitsPerSample}")
                                if (pauseMs > 0) {
                                    silenceFile = createSilenceWav(
                                        durationMs = pauseMs,
                                        sampleRate = baselineFormat!!.sampleRate,
                                        channels = baselineFormat!!.channels,
                                        bitsPerSample = baselineFormat!!.bitsPerSample
                                    )
                                    Log.d("TTSManager", "🤫 Сгенерирован файл тишины для межновостных пауз: ${silenceFile?.name}")
                                }
                            }

                            // Если формат сегмента отличается — пересэмплируем к baseline
                            val usesBaseline = meta.sampleRate == baselineFormat!!.sampleRate &&
                                    meta.channels == baselineFormat!!.channels &&
                                    meta.bitsPerSample == baselineFormat!!.bitsPerSample

                            val usedWav = if (!usesBaseline) {
                                Log.w("TTSManager", "⚠️ Формат сегмента отличается от эталона. Будет выполнен ресемплинг.")
                                val resampled = resampleWavToFormat(
                                    input = wav,
                                    sampleRate = baselineFormat!!.sampleRate,
                                    channels = baselineFormat!!.channels,
                                    bitsPerSample = baselineFormat!!.bitsPerSample
                                )
                                if (resampled != null) {
                                    try { wav.delete() } catch (_: Exception) {}
                                    resampled
                                } else {
                                    Log.e("TTSManager", "❌ Ресемплинг сегмента не удался")
                                    wavFiles.forEach { if (it.exists()) it.delete() }
                                    silenceFile?.delete()
                                    progressCallback?.onCompleted()
                                    return null
                                }
                            } else {
                                wav
                            }

// Вставим отложенные паузы перед текстом (после инициализации
                            if (pendingPauses.isNotEmpty()) {
                                for (ms in pendingPauses) {
                                    val sil = silenceCache.getOrPut(ms) {
                                        createSilenceWav(
                                            durationMs = ms,
                                            sampleRate = baselineFormat!!.sampleRate,
                                            channels = baselineFormat!!.channels,
                                            bitsPerSample = baselineFormat!!.bitsPerSample
                                        )
                                    }
                                    wavFiles.add(sil)
                                    offsetMs += ms
                                }
                                pendingPauses.clear()
                            }


                            // Добавляем сам текстовый сегмент
                            wavFiles.add(usedWav)
                            val dur = readWavDurationMs(usedWav) ?: meta.durationMs ?: 0L
                            offsetMs += dur
                        }
                        is Segment.Pause -> {
                            if (baselineFormat == null) {
                                // Пока не знаем формат — отложим паузу
                                pendingPauses += seg.ms
                            } else {
                                val sil = silenceCache.getOrPut(seg.ms) {
                                    createSilenceWav(
                                        durationMs = seg.ms,
                                        sampleRate = baselineFormat!!.sampleRate,
                                        channels = baselineFormat!!.channels,
                                        bitsPerSample = baselineFormat!!.bitsPerSample
                                    )
                                }
                                wavFiles.add(sil)
                                offsetMs += seg.ms
                            }
                        }
                    }
                }

                // Прогресс считаем по «частям» (как и раньше в логике прогресса)
                processedParts++
                if (totalParts > 0) {
                    val progress = (processedParts * 100 / totalParts).coerceAtMost(100)
                    progressCallback?.onProgress(progress, 100)
                }
            }

            // Пауза между новостями (кроме последней)
            if (idx != prepared.lastIndex && pauseMs > 0 && silenceFile != null) {
                wavFiles.add(silenceFile!!)
                offsetMs += pauseMs
            }
        }

        // Прогресс на 100% перед объединением
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
            Log.d("TTSManager", "🔗 Объединяем ${wavFiles.size} WAV файлов в один (включая тишину между новостями и сегментами)")
            AudioUtils.concatWavFiles(wavFiles, combinedWavFile)
        }

        // Чистим временные WAV (кроме объединённого и файлов из silenceCache)
        val silencePaths = silenceCache.values.map { it.absolutePath }.toSet()
        wavFiles.forEach { file ->
            if (file.exists() &&
                file != combinedWavFile &&
                !silencePaths.contains(file.absolutePath)
            ) {
                try {
                    file.delete()
                    Log.d("TTSManager", "🗑️ Удалили временный WAV: ${file.name}")
                } catch (_: Exception) { }
            }
        }

        if (!concatSuccess || !combinedWavFile.exists() || combinedWavFile.length() == 0L) {
            Log.e("TTSManager", "❌ Не удалось объединить WAV файлы")
            try { combinedWavFile.delete() } catch (_: Exception) { }
            progressCallback?.onCompleted()
            return null
        }

        Log.d("TTSManager", "✅ Объединенный WAV создан: ${combinedWavFile.name} (${combinedWavFile.length()} байт)")

        val mp3File = convertToMp3(combinedWavFile)
        try { combinedWavFile.delete() } catch (_: Exception) { }

        // Кэш тишины оставляем (для повторного использования в рамках сессии). При желании можно чистить:
        // silenceCache.values.forEach { try { it.delete() } catch (_: Exception) {} }
        // silenceCache.clear()

        if (mp3File != null) {
            Log.d("TTSManager", "🎉 Синтез завершен успешно: ${mp3File.name} (${mp3File.length()} байт)")
            Log.d("TTSManager", "📊 Итоговая статистика:")
            Log.d("TTSManager", "   Новостей: $realNewsIndex, пауза между новостями: ${pauseMs}мс")
            Log.d("TTSManager", "   Счетчики изменений: pitch=$pitchChangeCount, rate=$rateChangeCount, voice=$voiceChangeCount")
            Log.d("TTSManager", "🎵 === convertToAudioWithChaptersWithCallback() КОНЕЦ ===")
            progressCallback?.onCompleted()
            return AudioWithChapters(mp3File, chaptersMs)
        } else {
            Log.e("TTSManager", "❌ Не удалось конвертировать в MP3")
            progressCallback?.onCompleted()
            return null
        }
    }

    // Старый метод для совместимости
    suspend fun convertToAudioWithChapters(texts: List<String>, pauseMs: Int = 1000): AudioWithChapters? {
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
                try {
                    wavFile.delete()
                    Log.d("TTSManager", "🗑️ Удалили временный WAV файл: ${wavFile.name}")
                } catch (_: Exception) { }
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

            // 🔥 ВАЖНО: Применяем настройки для этого конкретного голоса
            applyVoiceSettings(voiceName)

            Log.d("TTSManager", "💾 Голос сохранен и применен: $voiceName с параметрами pitch=${currentAppliedPitch}, rate=${currentAppliedRate}")
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

                // 🔥 ВАЖНО: Применяем настройки для этого голоса
                if (savedVoiceName != null) {
                    applyVoiceSettings(savedVoiceName)
                }
            } ?: run {
                Log.w("TTSManager", "❗ refreshVoice(): голос $savedVoiceName не найден среди доступных")
            }

            Log.d("TTSManager", "🔄 refreshVoice() завершен: голос и параметры обновлены")
            Log.d("TTSManager", "   pitch=${currentAppliedPitch}, rate=${currentAppliedRate}")
            Log.d("TTSManager", "   Счетчики: pitch=$pitchChangeCount, rate=$rateChangeCount, voice=$voiceChangeCount")
        }
        Log.d("TTSManager", "🔁 === refreshVoice() КОНЕЦ ===")
    }

    // Индивидуальные настройки голосов
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

    // 🔥 ОСНОВНОЙ МЕТОД ДЛЯ ПРИМЕНЕНИЯ НАСТРОЕК ГОЛОСА
    fun applyVoiceSettings(voiceName: String) {
        val pitch = getPitchForVoice(voiceName)
        val rate = getRateForVoice(voiceName)

        Log.d("TTSManager", "🎚️ Применяем настройки для голоса $voiceName: pitch=$pitch, rate=$rate")

        tts?.setPitch(pitch)
        tts?.setSpeechRate(rate)

        currentAppliedPitch = pitch
        currentAppliedRate = rate

        pitchChangeCount++
        rateChangeCount++

        Log.d("TTSManager", "📊 Установлены параметры: pitch=$pitch (счетчик=$pitchChangeCount), rate=$rate (счетчик=$rateChangeCount)")
    }
}