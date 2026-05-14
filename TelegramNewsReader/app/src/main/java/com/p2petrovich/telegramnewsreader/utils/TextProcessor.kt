package com.p2petrovich.telegramnewsreader.utils

import android.util.Log
import com.p2petrovich.telegramnewsreader.services.NewsService

object TextProcessor {

    private const val TAG = "TextProcessor"

    private val PROMO_PATTERNS = listOf(
        Regex("^[🔹🔸🐚].*", RegexOption.IGNORE_CASE),
        Regex("^Фото:\\s*$", RegexOption.IGNORE_CASE),
        Regex("^Видео:\\s*$", RegexOption.IGNORE_CASE),
        Regex("^\\[.*]$"),
        Regex("^\\d{2}:\\d{2}\\s*—\\s*\\[.*]$"),
        Regex("^перейти в канал.*", RegexOption.IGNORE_CASE),
        Regex("^наш tg.*", RegexOption.IGNORE_CASE),
        Regex("^читать[ь]? больше.*", RegexOption.IGNORE_CASE),
        Regex("^все\\s+наши\\s+каналы\\b.*", RegexOption.IGNORE_CASE),
    )

    private val URL_PATTERN = Regex("https?://\\S+")
    private val HASHTAG_MENTION_PATTERN = Regex("(^|\\s)[#@][\\p{L}0-9_]+")
    private val EMOJI_PATTERN = Regex("[\\p{So}\\p{Sk}❗️!❤️💚💙💛💜🖤🤍🤎]+")
    private val SUBSCRIBE_PATTERN = Regex("(?i)подпис(аться|ывай(ся|тесь)?)\\s+на\\s+[^\\n.]+")
    private val MULTI_NEWLINE_PATTERN = Regex("\\n{3,}")
    private val MEDIA_PREFIX_PATTERN = Regex(
        "^\\d{2}:\\d{2}\\s*—\\s*(фото|видео|аудио|документ|gif|голосовое сообщение)[\\p{P}\\s]*",
        RegexOption.IGNORE_CASE
    )
    private val SUBSCRIBE_TAIL_PATTERNS = listOf(
        Regex("(?i)[\\s\\p{So}\\p{Sk}]*[\\\\/|•·—–-]\\s*подпис(аться|ывай(ся|тесь)?|ка)\\b.*$"),
        Regex("(?i)[\\s\\p{So}\\p{Sk}]*[\\\\/|•·—–-]\\s*все\\s+наши\\s+каналы\\b.*$"),
        Regex("(?i)[\\s\\p{So}\\p{Sk}]*[\\\\/|•·—–-]\\s*зеркал[оа]\\b.*$"),
        Regex("(?im)^.*\\bподпис(аться|ывай(ся|тесь)?|ка)\\b.*(\\||/|•|—|–).*$"),
        Regex("(?im)^\\s*[\\p{So}\\p{Sk}]*\\s*зеркал[оа]\\s*(канала|нашего)?\\b.*$"),
        Regex("(?im)^\\s*[\\p{So}\\p{Sk}]*\\s*все\\s+наши\\s+каналы\\b.*$"),
    )

    private val TTS_URL_PATTERN = Regex("(https?://|www\\.)\\S+")
    private val TTS_HASHTAG_PATTERN = Regex("(^|\\s)[#@][\\p{L}0-9_]+")
    private val TTS_FORWARD_PATTERN = Regex("(?im)^переслано из:?\\s.*$")
    private val TTS_EDIT_PATTERN = Regex("(?im)^ред\\.?\\s*:?\\s*\\d{1,2}:\\d{2}.*$")
    private val TTS_SUBSCRIBE_PATTERN = Regex("(?im)^\\s*(?:[\\p{So}\\p{Sk}❗️!❤️💚💙💛💜🖤🤍🤎]\\s*)*подписывай(ся|тесь)?\\b.*$")
    private val TTS_SUBSCRIPTION_PATTERN = Regex("(?im)^\\s*подписка\\b.*$")
    private val TTS_AD_PATTERN = Regex("(?im)^.*(реклама|промокод|скидк[аи])\\b.*$")
    private val TTS_PROMO_PATTERN = Regex("(?im)^.*(акци[яи]|распродажа|купи)\\b.*$")
    private val TTS_PHOTO_LINE_PATTERN = Regex("^Фото:.*$", RegexOption.MULTILINE)
    private val TTS_RBK_PATTERN = Regex(
        "^[\\p{So}\\p{Sk}]?\\s*(Читать РБК в Telegram|Следить за новостями РБК в Telegram|(Другие видео|Картина дня).*в телеграм-канале РБК).*$",
        setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)
    )
    private val TTS_PHONE_PATTERN = Regex("\\+?\\d{1,3}[\\s-]?\\(?\\d{1,4}\\)?[\\s-]?\\d{1,4}[\\s-]?\\d{1,4}[\\s-]?\\d{1,4}")
    private val TTS_COLORED_SQUARES_PATTERN = Regex("[🟩🟨🟥🟦🟪🟫⬛⬜]")
    private val TTS_EMOJI_PATTERN = Regex("[\\p{So}\\p{Sk}]")
    private val TTS_MARKDOWN_PATTERN = Regex("[*_`]+")
    private val TTS_QUOTES_PATTERN = Regex("[«»]")
    private val TTS_SUBSCRIBE_INLINE_PATTERN = Regex("(?i)подписывай(ся|тесь)?\\s+на\\s+[^\\n.]+")
    private val TTS_ELLIPSIS_PATTERN = Regex("\\.\\.\\.")
    private val TTS_MULTI_SPACE_PATTERN = Regex("[ \\t]{2,}")
    private val TTS_MULTI_NEWLINE = Regex("\\n{3,}")

    private val TRIVIAL_PATTERN = Regex("^(фото|видео|аудио|ссылка|репост)\\b.*$", RegexOption.IGNORE_CASE)

    // ПАТЧ 2: смягчённые проверки — удаляем сообщение целиком только если ОНО САМО является
    // коротким призывом подписаться/лайкнуть, а не если просто содержит такое слово где-то внутри.
    private val SUBSCRIBE_CHECK_PATTERN = Regex(
        "(?i)^\\s*(?:\\d{2}:\\d{2}\\s*—\\s*)?(подписывай(ся|тесь)?|подпишись|подписка на канал)\\b[\\s\\S]{0,80}$"
    )
    private val SPAM_CHECK_PATTERN = Regex(
        "(?i)^\\s*(?:\\d{2}:\\d{2}\\s*—\\s*)?(лайк|репост|поделись|нажми|кликни|переходи по ссылке)\\b[\\s\\S]{0,60}$"
    )

    // ============ Фильтрация ============

    fun filterMessages(
        messages: List<String>,
        onFilterProgress: ((originalCount: Int, filteredCount: Int) -> Unit)? = null
    ): List<String> {
        Log.e(TAG, "====== FILTER START: ${messages.size} messages ======")

        var droppedTooShort = 0
        var droppedUrlOnly = 0
        var droppedEmojiOnly = 0
        var droppedBracketTime = 0
        var droppedPromo = 0
        var droppedAfterClean = 0
        var droppedTooLong = 0

        val filtered = messages.mapNotNull { original ->
            if (NewsService.isChannelHeader(original)) return@mapNotNull original

            val trimmed = original.trim()
            val preview = trimmed.take(120).replace("\n", " | ")

            if (trimmed.length <= 3) {
                droppedTooShort++
                Log.w(TAG, "SPAM [too_short]: $preview")
                return@mapNotNull null
            }

            if (trimmed.matches(Regex("^https?://.*$"))) {
                droppedUrlOnly++
                Log.w(TAG, "SPAM [url_only]: $preview")
                return@mapNotNull null
            }

            if (trimmed.matches(Regex("^[\\p{So}\\p{Sk}\\s]+$"))) {
                droppedEmojiOnly++
                Log.w(TAG, "SPAM [emoji_only]: $preview")
                return@mapNotNull null
            }

            if (trimmed.matches(Regex("^\\d{2}:\\d{2}\\s*—\\s*\\[.*]$"))) {
                droppedBracketTime++
                Log.w(TAG, "SPAM [media_no_text]: $preview")
                return@mapNotNull null
            }

            val matchedPromo = PROMO_PATTERNS.firstOrNull { it.containsMatchIn(trimmed) }
            if (matchedPromo != null) {
                droppedPromo++
                Log.w(TAG, "SPAM [promo: ${matchedPromo.pattern.take(30)}]: $preview")
                return@mapNotNull null
            }

            var cleaned = trimmed
            cleaned = MEDIA_PREFIX_PATTERN.replace(cleaned, "")
            cleaned = MULTI_NEWLINE_PATTERN.replace(cleaned, "\n\n")
            cleaned = URL_PATTERN.replace(cleaned, "")
            cleaned = HASHTAG_MENTION_PATTERN.replace(cleaned, " ")
            cleaned = EMOJI_PATTERN.replace(cleaned, " ")
            cleaned = SUBSCRIBE_PATTERN.replace(cleaned, "")
            cleaned = cleaned.trim()

            SUBSCRIBE_TAIL_PATTERNS.forEach { pattern ->
                cleaned = pattern.replace(cleaned, "")
            }

            if (cleaned.isBlank() || cleaned.length <= 5) {
                droppedAfterClean++
                Log.w(TAG, "SPAM [empty_after_clean]: $preview")
                return@mapNotNull null
            }

            val finalMessage = if (cleaned.length > 5000) {
                droppedTooLong++
                Log.d(TAG, "TRIMMED [>5000]: $preview")
                cleaned.take(4970) + "..."
            } else cleaned

            Log.d(TAG, "OK: ${finalMessage.take(80).replace("\n", " | ")}")
            finalMessage
        }.distinct().take(200)

        Log.e(TAG, "====== FILTER RESULT: ${messages.size} -> ${filtered.size} ======")
        Log.e(TAG, "  too_short=$droppedTooShort url=$droppedUrlOnly emoji=$droppedEmojiOnly")
        Log.e(TAG, "  media=$droppedBracketTime promo=$droppedPromo empty_clean=$droppedAfterClean trim=$droppedTooLong")

        onFilterProgress?.invoke(messages.size, filtered.size)
        return filtered
    }

    // ============ Дедупликация между каналами ============

    fun deduplicateAcrossChannels(messages: List<String>): List<String> {
        if (messages.size <= 1) return messages

        val result = mutableListOf<String>()
        val fingerprints = mutableListOf<Set<String>>()
        var removedCount = 0

        for (msg in messages) {
            if (NewsService.isChannelHeader(msg)) {
                result.add(msg)
                continue
            }

            val fp = extractFingerprint(msg)
            if (fp.size < 3) {
                result.add(msg)
                fingerprints.add(fp)
                continue
            }

            val isDuplicate = fingerprints.any { existing ->
                if (existing.size < 3) false
                else {
                    val intersection = fp.intersect(existing).size
                    val union = fp.union(existing).size
                    if (union == 0) false
                    else (intersection.toDouble() / union) > 0.55
                }
            }

            if (!isDuplicate) {
                result.add(msg)
                fingerprints.add(fp)
            } else {
                removedCount++
                val preview = msg.take(100).replace("\n", " | ")
                Log.w(TAG, "DEDUP [jaccard>0.55]: $preview")
            }
        }

        Log.e(TAG, "dedup: ${messages.size} -> ${result.size} (removed $removedCount)")
        return result
    }

    private fun extractFingerprint(text: String): Set<String> {
        val cleaned = text.lowercase()
            .replace(Regex("^\\d{2}:\\d{2}\\s*—\\s*"), "")
            .replace(Regex("[^\\p{L}\\s]"), " ")

        val stopWords = setOf(
            "в", "на", "с", "и", "по", "к", "за", "из", "о", "от",
            "для", "что", "как", "это", "не", "но", "а", "же", "ли",
            "бы", "то", "вот", "все", "уже", "при", "до", "так",
            "его", "её", "их", "он", "она", "они", "мы", "вы"
        )

        return cleaned.split(Regex("\\s+"))
            .filter { it.length > 3 && it !in stopWords }
            .toSet()
    }

    // ============ TTS очистка ============

    fun cleanForTts(text: String): String {
        if (NewsService.isChannelHeader(text)) return text

        var t = text
        t = TTS_URL_PATTERN.replace(t, " ")
        t = TTS_HASHTAG_PATTERN.replace(t, " ")
        t = TTS_FORWARD_PATTERN.replace(t, "")
        t = TTS_EDIT_PATTERN.replace(t, "")
        t = TTS_SUBSCRIBE_PATTERN.replace(t, "")
        t = TTS_SUBSCRIPTION_PATTERN.replace(t, "")
        t = TTS_AD_PATTERN.replace(t, "")
        t = TTS_PROMO_PATTERN.replace(t, "")
        t = TTS_PHOTO_LINE_PATTERN.replace(t, "")
        t = TTS_RBK_PATTERN.replace(t, "")
        t = TTS_PHONE_PATTERN.replace(t, "")
        t = TTS_COLORED_SQUARES_PATTERN.replace(t, "")
        t = TTS_EMOJI_PATTERN.replace(t, " ")
        t = TTS_MARKDOWN_PATTERN.replace(t, "")
        t = TTS_QUOTES_PATTERN.replace(t, "\"")
        t = TTS_SUBSCRIBE_INLINE_PATTERN.replace(t, "")
        t = TTS_ELLIPSIS_PATTERN.replace(t, "…")
        t = TTS_MULTI_SPACE_PATTERN.replace(t, " ")
        t = TTS_MULTI_NEWLINE.replace(t, "\n\n")
        return t.trim()
    }

    fun deduplicateLines(text: String): String {
        if (NewsService.isChannelHeader(text)) return text

        val seen = HashSet<String>()
        return text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && seen.add(it.lowercase()) }
            .joinToString("\n")
    }

    fun normalizeNumbers(text: String): String {
        if (NewsService.isChannelHeader(text)) return text

        var t = text
        t = t.replace(Regex("\\b№\\s*(\\d+)"), "номер $1")

        t = t.replace(Regex("\\b(\\d+[\\d\\s]*)\\s*(млн|млрд)\\s*(?:₽|руб\\.?|р\\.)\\b", RegexOption.IGNORE_CASE)) { match ->
            val num = match.groupValues[1].replace("\\s".toRegex(), "")
            val scale = if (match.groupValues[2].lowercase() == "млн") "миллионов" else "миллиардов"
            "$num $scale рублей"
        }

        t = t.replace(Regex("\\b(\\d+[\\d\\s]*)(?:₽|руб\\.?|р\\.)\\b", RegexOption.IGNORE_CASE), "$1 рублей")

        t = t.replace(Regex("на\\s+(\\d+[,.]?\\d*)\\s?%")) { "на ${it.groupValues[1]} процентов" }
        t = t.replace(Regex("(\\d+[,.]?\\d*)%-й")) { "${it.groupValues[1]}-процентный" }
        t = t.replace(Regex("(\\d+[,.]?\\d*)%-е")) { "${it.groupValues[1]}-процентные" }
        t = t.replace(Regex("\\b(\\d+[,.]?\\d*)\\s?%\\b")) { "${it.groupValues[1]} процентов" }

        t = t.replace(Regex("([+-]?\\d+[,.]?\\d*)\\s?°C?\\b")) { "${it.groupValues[1]} градусов" }

        return t
    }

    fun formatForIntonation(text: String): String {
        if (NewsService.isChannelHeader(text)) return text

        var t = text

        t = t.replace(" в нем", " в нём")
        t = t.replace(Regex("\\s*‼‼‼\\s*"), "Главное")

        t = Regex("\\b(\\d{1,2})\\s+(января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря)\\b")
            .replace(t) { match ->
                val day = match.groupValues[1].toIntOrNull() ?: return@replace match.value
                "${numberToOrdinalRu(day)} ${match.groupValues[2]}"
            }

        t = Regex("\\b(\\d{1,2})\\s+(января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря)\\s+(\\d{4})\\b")
            .replace(t) { match ->
                val day = match.groupValues[1].toIntOrNull() ?: return@replace match.value
                "${numberToOrdinalRu(day)} ${match.groupValues[2]} ${match.groupValues[3]} года"
            }

        t = t.replace(Regex("(\\d{4})/(\\d{4})")) { "${it.groupValues[1]} – ${it.groupValues[2]}" }
        t = t.replace(Regex("(\\d{4})-(\\d{4})")) { "${it.groupValues[1]} – ${it.groupValues[2]}" }

        t = t.replace(Regex("\\bкм/ч\\b", RegexOption.IGNORE_CASE), "километров в час")
        t = t.replace(Regex("\\bкм\\b", RegexOption.IGNORE_CASE), "километров")

        t = t.replace(Regex("\\b(\\d+)\\s?\\+\\s?(\\d+)\\b")) { "${it.groupValues[1]} плюс ${it.groupValues[2]}" }
        t = t.replace(Regex("\\b(\\d+)\\s?\\*\\s?(\\d+)\\b")) { "${it.groupValues[1]} умножить на ${it.groupValues[2]}" }

        t = t.replace(Regex("^[•·∙▪▫◦‣⁃]\\s+", RegexOption.MULTILINE)) { "— " }
        t = t.replace(Regex("[◻️◻⬜▫□]+"), "")

        t = t.replace(Regex("(?m)^[-•]\\s+"), "— ")
        t = t.replace(Regex(" - "), " — ")
        t = t.replace(Regex("\\.\\.\\."), "…")

        t = t.replace(Regex(";\\s*(?=\\n{2,})"), ". ")
        t = t.replace(Regex("(?<=[.!?])\\s+"), "\n\n")

        return t.trim()
    }

    fun formatForSpeech(text: String): String {
        if (NewsService.isChannelHeader(text)) return text

        var t = text

        val techAbbreviations = mapOf(
            "IT" to "Ай-Ти", "AI" to "искусственный интеллект",
            "VR" to "виртуальная реальность", "AR" to "дополненная реальность",
            "GPS" to "Джи-Пи-Эс", "USB" to "ЮСБ", "WIFI" to "Вай-Фай"
        )
        techAbbreviations.forEach { (abbr, full) ->
            t = t.replace(Regex("\\b$abbr\\b", RegexOption.IGNORE_CASE)) { full }
        }

        t = t.replace(Regex("\\bMAX\\b"), "Макс")

        t = t.replace(Regex("\\b(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})\\b")) {
            "${it.groupValues[1]} число ${it.groupValues[2]} месяца ${it.groupValues[3]} года"
        }

        val importantWords = listOf("важно", "внимание", "срочно", "эксклюзив", "молния")
        importantWords.forEach { word ->
            t = t.replace(Regex("\\b($word)\\b", RegexOption.IGNORE_CASE)) {
                "ВАЖНО: ${it.groupValues[1].lowercase()}"
            }
        }

        return t.trim()
    }

    /**
     * ПАТЧ 2: dropTrivial теперь удаляет сообщение целиком только если ОНО САМО
     * является коротким призывом, а не если просто содержит такое слово.
     */
    fun dropTrivial(texts: List<String>): List<String> {
        var droppedShort = 0
        var droppedTrivial = 0
        var droppedSubscribe = 0
        var droppedSpam = 0

        val result = texts.filter { text ->
            if (NewsService.isChannelHeader(text)) return@filter true

            val trimmed = text.trim()
            val preview = trimmed.take(100).replace("\n", " | ")

            // "Чистая" версия для проверки длины — без префикса HH:mm
            val withoutTimePrefix = trimmed.replace(Regex("^\\d{2}:\\d{2}\\s*—\\s*"), "").trim()

            when {
                withoutTimePrefix.length < 8 -> {
                    droppedShort++
                    Log.w(TAG, "DROP [<8chars]: $preview")
                    false
                }
                TRIVIAL_PATTERN.containsMatchIn(withoutTimePrefix) -> {
                    droppedTrivial++
                    Log.w(TAG, "DROP [trivial]: $preview")
                    false
                }
                // Удаляем только если ВСЁ сообщение — короткий призыв подписаться
                SUBSCRIBE_CHECK_PATTERN.matches(trimmed) -> {
                    droppedSubscribe++
                    Log.w(TAG, "DROP [subscribe_short]: $preview")
                    false
                }
                // Удаляем только если ВСЁ сообщение — короткий спам-призыв
                SPAM_CHECK_PATTERN.matches(trimmed) -> {
                    droppedSpam++
                    Log.w(TAG, "DROP [spam_short]: $preview")
                    false
                }
                else -> true
            }
        }

        if (texts.size != result.size) {
            Log.e(TAG, "dropTrivial: ${texts.size} -> ${result.size} (short=$droppedShort trivial=$droppedTrivial subscribe=$droppedSubscribe spam=$droppedSpam)")
        }

        return result
    }

    fun splitByParagraphs(text: String, maxChars: Int = 1800): List<String> {
        val paras = text.split(Regex("\\n{2,}")).map { it.trim() }.filter { it.isNotEmpty() }
        val parts = mutableListOf<String>()
        val cur = StringBuilder()

        for (p in paras) {
            if (cur.length + p.length + 2 <= maxChars) {
                if (cur.isNotEmpty()) cur.append("\n\n")
                cur.append(p)
            } else if (p.length <= maxChars) {
                if (cur.isNotEmpty()) { parts.add(cur.toString().trim()); cur.clear() }
                cur.append(p)
            } else {
                if (cur.isNotEmpty()) { parts.add(cur.toString().trim()); cur.clear() }
                splitTextSafely(p, maxChars).forEach { parts.add(it) }
            }
        }
        if (cur.isNotEmpty()) parts.add(cur.toString().trim())
        return parts
    }

    private fun splitTextSafely(text: String, maxChars: Int = 1800): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val parts = mutableListOf<String>()
        var currentPart = ""
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
                    parts.add(sentence.take(maxChars))
                    currentPart = ""
                }
            }
        }
        if (currentPart.isNotEmpty()) parts.add(currentPart.trim())
        return parts.filter { it.isNotBlank() }
    }

    private fun numberToOrdinalRu(number: Int): String = when (number) {
        1 -> "первое"; 2 -> "второе"; 3 -> "третье"; 4 -> "четвёртое"; 5 -> "пятое"
        6 -> "шестое"; 7 -> "седьмое"; 8 -> "восьмое"; 9 -> "девятое"; 10 -> "десятое"
        11 -> "одиннадцатое"; 12 -> "двенадцатое"; 13 -> "тринадцатое"; 14 -> "четырнадцатое"
        15 -> "пятнадцатое"; 16 -> "шестнадцатое"; 17 -> "семнадцатое"; 18 -> "восемнадцатое"
        19 -> "девятнадцатое"; 20 -> "двадцатое"; 21 -> "двадцать первое"; 22 -> "двадцать второе"
        23 -> "двадцать третье"; 24 -> "двадцать четвёртое"; 25 -> "двадцать пятое"
        26 -> "двадцать шестое"; 27 -> "двадцать седьмое"; 28 -> "двадцать восьмое"
        29 -> "двадцать девятое"; 30 -> "тридцатое"; 31 -> "тридцать первое"
        else -> number.toString()
    }
}
