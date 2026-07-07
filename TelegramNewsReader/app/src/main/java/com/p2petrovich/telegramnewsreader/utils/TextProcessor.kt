package com.p2petrovich.telegramnewsreader.utils

/**
 * Центральный процессор текста.
 * Отвечает за:
 * 1. Фильтрацию мусора (реклама, ссылки, короткие сообщения).
 * 2. Подготовку текста к синтезу (нормализация чисел, сокращений, ударений).
 * 3. Извлечение "отпечатков" для дедупликации.
 */
object TextProcessor {

    private const val TAG = "TextProcessor"

    // --- Параметры алгоритмов ---
    private const val ANCHOR_MATCH_RATIO = 0.6
    private const val WORD_JACCARD_MIN = 0.4
    private const val MIN_ANCHORS = 3
    const val MAX_NEWS_DEFAULT = 500

    // =============================================================================================
    // ПУБЛИЧНЫЕ ТОЧКИ ВХОДА (PIPELINES)
    // =============================================================================================

    /**
     * Основной пайплайн подготовки текста к озвучке.
     * Проходит через серию трансформаций: чистка -> нормализация -> ударения.
     */
    fun prepareForSpeech(text: String): String {
        if (HeaderUtils.isChannelHeader(text)) return text
        
        return text
            .let { cleanForTts(it) }
            .let { deduplicateLines(it) }
            .let { normalizeNumbers(it) }
            .let { expandAbbreviations(it) }
            .let { formatForIntonation(it) }
            .let { formatForSpeech(it) }
            .let { applyStressMarks(it) }
            .trim()
    }

    // =============================================================================================
    // МОДУЛЬ 1: ФИЛЬТРАЦИЯ И ОЧИСТКА
    // =============================================================================================

    /**
     * Удаляет пустые заголовки каналов (за которыми не следует новостей).
     */
    fun dropEmptyHeaders(messages: List<String>): List<String> {
        val result = mutableListOf<String>()
        var i = 0
        while (i < messages.size) {
            val current = messages[i]
            if (HeaderUtils.isChannelHeader(current)) {
                if (i + 1 < messages.size && !HeaderUtils.isChannelHeader(messages[i + 1])) {
                    result.add(current)
                } else {
                    Logx.d(TAG) { "DROP empty header: $current" }
                }
            } else {
                result.add(current)
            }
            i++
        }
        return result
    }

    /**
     * Фильтрует список сообщений, удаляя спам, ссылки и слишком короткие тексты.
     */
    fun filterMessages(
        messages: List<String>,
        maxNews: Int = MAX_NEWS_DEFAULT,
        onFilterProgress: ((originalCount: Int, filteredCount: Int) -> Unit)? = null,
        onTruncated: ((kept: Int, dropped: Int) -> Unit)? = null
    ): List<String> {
        Logx.i(TAG) { "====== FILTER START: ${messages.size} messages ======" }

        var droppedCount = mutableMapOf("short" to 0, "url" to 0, "emoji" to 0, "media" to 0, "promo" to 0, "clean" to 0, "long" to 0)

        val cleanedList = messages.mapNotNull { original ->
            if (HeaderUtils.isChannelHeader(original)) return@mapNotNull original

            val trimmed = original.trim()

            // 1. Быстрые проверки на мусор
            if (trimmed.length <= 3) { droppedCount["short"] = (droppedCount["short"] ?: 0) + 1; return@mapNotNull null }
            if (trimmed.matches(Patterns.General.URL_ONLY)) { droppedCount["url"] = (droppedCount["url"] ?: 0) + 1; return@mapNotNull null }
            if (trimmed.matches(Patterns.General.EMOJI_ONLY)) { droppedCount["emoji"] = (droppedCount["emoji"] ?: 0) + 1; return@mapNotNull null }
            if (trimmed.matches(Patterns.General.BRACKET_TIME)) { droppedCount["media"] = (droppedCount["media"] ?: 0) + 1; return@mapNotNull null }

            // 2. Проверка на рекламные паттерны
            val matchedPromo = Patterns.Promo.ALL.firstOrNull { it.containsMatchIn(trimmed) }
            if (matchedPromo != null) { droppedCount["promo"] = (droppedCount["promo"] ?: 0) + 1; return@mapNotNull null }

            // 3. Глубокая чистка текста
            var cleaned = trimmed
            cleaned = Patterns.Cleaning.MEDIA_PREFIX.replace(cleaned, "")
            cleaned = Patterns.Cleaning.MULTI_NEWLINE.replace(cleaned, "\n\n")
            cleaned = Patterns.Cleaning.URL.replace(cleaned, "")
            cleaned = Patterns.Cleaning.HASHTAG_MENTION.replace(cleaned, " ")
            cleaned = Patterns.Cleaning.EMOJI.replace(cleaned, " ")
            cleaned = Patterns.Cleaning.SUBSCRIBE.replace(cleaned, "")
            cleaned = Patterns.Cleaning.BAZA_FOOTER.replace(cleaned, "")
            
            Patterns.Cleaning.SUBSCRIBE_TAILS.forEach { cleaned = it.replace(cleaned, "") }

            cleaned = cleaned.trim()

            if (cleaned.isBlank() || cleaned.length <= 5) { droppedCount["clean"] = (droppedCount["clean"] ?: 0) + 1; return@mapNotNull null }

            // 4. Ограничение длины
            val finalMessage = if (cleaned.length > 5000) {
                droppedCount["long"] = (droppedCount["long"] ?: 0) + 1
                cleaned.take(4970) + "..."
            } else cleaned

            finalMessage
        }.distinct()

        // 5. Ограничение количества
        val limited = applyLengthLimit(cleanedList, maxNews, onTruncated)

        Logx.i(TAG) { "====== FILTER RESULT: ${messages.size} -> ${limited.size} ======" }
        onFilterProgress?.invoke(messages.size, limited.size)
        return limited
    }

    private fun applyLengthLimit(list: List<String>, limit: Int, onTruncated: ((Int, Int) -> Unit)?): List<String> {
        if (limit <= 0) return list
        var newsCount = 0
        var droppedNews = 0
        val limited = list.filter { item ->
            if (HeaderUtils.isChannelHeader(item)) true
            else if (newsCount < limit) { newsCount++; true }
            else { droppedNews++; false }
        }
        if (droppedNews > 0) onTruncated?.invoke(newsCount, droppedNews)
        return limited
    }

    fun dropTrivial(texts: List<String>): List<String> {
        var dropped = mutableMapOf("short" to 0, "timecode" to 0, "trivial" to 0, "sub" to 0, "spam" to 0)
        val result = texts.filter { text ->
            if (HeaderUtils.isChannelHeader(text)) return@filter true
            val body = Patterns.General.TIME_PREFIX.replace(text.trim(), "").trim()

            when {
                body.length < 8 -> { dropped["short"] = (dropped["short"] ?: 0) + 1; false }
                Patterns.General.TIMECODE_ANNOUNCE.containsMatchIn(body) -> { dropped["timecode"] = (dropped["timecode"] ?: 0) + 1; false }
                Patterns.General.TRIVIAL.containsMatchIn(body) -> { dropped["trivial"] = (dropped["trivial"] ?: 0) + 1; false }
                Patterns.General.SUBSCRIBE_CHECK.matches(text.trim()) -> { dropped["sub"] = (dropped["sub"] ?: 0) + 1; false }
                Patterns.General.SPAM_CHECK.matches(text.trim()) -> { dropped["spam"] = (dropped["spam"] ?: 0) + 1; false }
                else -> true
            }
        }
        return result
    }

    // =============================================================================================
    // МОДУЛЬ 2: ДЕДУПЛИКАЦИЯ
    // =============================================================================================

    data class Fingerprint(
        val words: Set<String>,
        val anchors: Set<String>,
        val numbers: Set<String> = emptySet(),
        val strongAnchors: Set<String> = emptySet()
    )

    fun deduplicateAcrossChannels(messages: List<String>, threshold: Double = ANCHOR_MATCH_RATIO): List<String> {
        if (messages.size <= 1) return messages
        val result = mutableListOf<String>()
        val fingerprints = mutableListOf<Fingerprint>()

        for (msg in messages) {
            if (HeaderUtils.isChannelHeader(msg)) { result.add(msg); continue }
            val fp = extractFingerprint(msg)
            if (fp.words.size < 3) { result.add(msg); fingerprints.add(fp); continue }

            val matched = fingerprints.firstOrNull { isSameEvent(fp, it, threshold) }
            if (matched == null) {
                result.add(msg)
                fingerprints.add(fp)
            }
        }
        return result
    }

    fun extractFingerprint(text: String): Fingerprint {
        val stop = if (isRussianText(text)) Patterns.Lang.STOP_WORDS_RU else Patterns.Lang.STOP_WORDS_EN
        val body = Patterns.General.TIME_PREFIX.replace(text, "")

        // 1. Извлечение чисел
        val joinedDigits = body
            .replace(Regex("(?<=\\d)[\\s\u00A0](?=\\d{3}\\b)"), "")
            .replace(Regex("(?<=\\d),(?=\\d{3}\\b)"), "")
        
        val numbers = Patterns.Deduplication.NUMBERS.findAll(joinedDigits)
            .map { it.value.replace(',', '.') }
            .filter { it.length >= 2 }
            .map { num ->
                val intPart = num.substringBefore('.')
                if (!num.contains('.') && intPart.length >= 4) intPart.take(3) + "k" else num
            }.toSet()

        // 2. Аббревиатуры и имена собственные
        val abbreviations = Patterns.Deduplication.ABBREVIATIONS.findAll(body)
            .map { it.value.lowercase() }
            .filter { it !in stop && it !in Patterns.Lang.NOISE_ANCHORS }.toSet()

        val properNames = Patterns.Deduplication.PROPER_NAMES.findAll(body)
            .map { it.value.lowercase() }
            .filter { it !in stop && it !in Patterns.Lang.NOISE_ANCHORS }.toSet()

        val strongAnchors = abbreviations + properNames
        
        // 3. Обычные слова
        val words = body.lowercase().replace(Regex("[^\\p{L}\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 3 && it !in stop }.toSet()

        return Fingerprint(words, numbers + strongAnchors, numbers, strongAnchors)
    }

    fun isSameEvent(a: Fingerprint, b: Fingerprint, threshold: Double = ANCHOR_MATCH_RATIO): Boolean {
        val strongCommon = a.strongAnchors.intersect(b.strongAnchors).size
        val numberCommon = a.numbers.intersect(b.numbers).size
        val strongMin = minOf(a.strongAnchors.size, b.strongAnchors.size)
        val strongRatio = if (strongMin > 0) strongCommon.toDouble() / strongMin else 0.0

        if (strongCommon >= 2 && strongRatio >= threshold) return true
        if (strongCommon >= 1 && numberCommon >= 1) return true
        if (numberCommon >= 2) return true

        if (a.words.isNotEmpty() && b.words.isNotEmpty()) {
            val intersection = a.words.intersect(b.words).size
            val union = a.words.union(b.words).size
            val wordThreshold = threshold.coerceAtLeast(WORD_JACCARD_MIN)
            if (union > 0 && intersection.toDouble() / union >= wordThreshold) return true
        }
        return false
    }

    // =============================================================================================
    // МОДУЛЬ 3: ПОДГОТОВКА К TTS
    // =============================================================================================

    fun cleanForTts(text: String): String {
        if (HeaderUtils.isChannelHeader(text)) return text
        var t = text.replace("₽", " руб. ")

        t = Patterns.TtsCleaning.URL.replace(t, " ")
        t = Patterns.TtsCleaning.HASHTAG.replace(t, " ")
        t = Patterns.TtsCleaning.FORWARD.replace(t, "")
        t = Patterns.TtsCleaning.EDIT.replace(t, "")
        t = Patterns.TtsCleaning.SUBSCRIBE.replace(t, "")
        t = Patterns.TtsCleaning.SUBSCRIPTION.replace(t, "")
        t = Patterns.TtsCleaning.AD.replace(t, "")
        t = Patterns.TtsCleaning.PROMO.replace(t, "")
        t = Patterns.TtsCleaning.PHOTO_LINE.replace(t, "")
        t = Patterns.TtsCleaning.RBK.replace(t, "")
        t = Patterns.TtsCleaning.RBK_MAX.replace(t, "")
        t = Patterns.TtsCleaning.RBK_APP.replace(t, "")
        t = Patterns.TtsCleaning.MAX_CHANNEL_TAIL.replace(t, "")
        t = Patterns.TtsCleaning.BAZA_FOOTER.replace(t, "")
        t = Patterns.TtsCleaning.SUBSCRIBE_INLINE.replace(t, "")
        t = Patterns.TtsCleaning.AI_ERROR.replace(t, "")
        t = Patterns.TtsCleaning.READ_MORE.replace(t, "")
        t = Patterns.TtsCleaning.PHONE.replace(t, "")

        t = Patterns.Cleaning.GEOMETRIC_SHAPES.replace(t, " ")
        t = Patterns.Cleaning.VARIATION_SELECTOR.replace(t, "")
        t = Patterns.TtsCleaning.EMOJI.replace(t, " ")
        t = Patterns.TtsCleaning.LEADING_DASH.replace(t, "")
        t = Patterns.TtsCleaning.MARKDOWN.replace(t, "")

        t = t.replace("\"", "«").replace("'", " ")
        t = Patterns.TtsCleaning.QUOTES.replace(t, "«")
        t = Patterns.TtsCleaning.ELLIPSIS.replace(t, "…")

        t = Patterns.TtsCleaning.BLANK_LINES.replace(t, "")
        t = Patterns.TtsCleaning.MULTI_SPACE.replace(t, " ")
        t = Patterns.TtsCleaning.MULTI_NEWLINE.replace(t, "\n\n")
        t = Patterns.TtsCleaning.SPACE_BEFORE_PUNCT.replace(t, "$1")

        return t.trim()
    }

    fun normalizeNumbers(text: String): String {
        if (HeaderUtils.isChannelHeader(text)) return text
        var t = text.replace(Regex("(?<=\\d)[\\s\u00A0](?=\\d{3}(?:\\b|\\D))"), "")
        t = t.replace(Regex("(?<!\\w)№\\s*(\\d+)"), "номер $1")

        // 1. Сначала обрабатываем валюты С МАСШТАБОМ (чтобы избежать "долларов тысяч")
        // Пример: $50 млн -> 50 миллионов долларов
        Patterns.TtsNormalizing.CURRENCIES.forEach { (symbol, mainName, _) ->
            t = t.replace(Regex("$symbol\\s?(\\d[\\d,.]*)\\s*(тыс\\.?|млн|млрд|трлн)\\b", RegexOption.IGNORE_CASE)) { m ->
                val num = m.groupValues[1].replace(",", ".")
                val scaleRaw = m.groupValues[2].lowercase().trim('.')
                val scale = when {
                    scaleRaw.startsWith("тыс") -> "тысяч"
                    scaleRaw.startsWith("млн") -> "миллионов"
                    scaleRaw.startsWith("млрд") -> "миллиардов"
                    scaleRaw.startsWith("трлн") -> "триллионов"
                    else -> scaleRaw
                }
                "$num $scale $mainName"
            }
        }

        // 2. Затем обычные суммы с центами: $10.50 -> 10 долларов 50 центов
        Patterns.TtsNormalizing.CURRENCIES.forEach { (symbol, mainName, subType) ->
            t = t.replace(Regex("$symbol\\s?(\\d+)(?:[,.](\\d{1,2}))?\\b")) { m ->
                val main = m.groupValues[1]
                val subRaw = m.groupValues[2]
                if (subRaw.isEmpty()) "$main $mainName" else {
                    val subInt = if (subRaw.length == 1) subRaw.toInt() * 10 else subRaw.toInt()
                    "$main $mainName $subInt ${getSubunitName(subInt, subType)}"
                }
            }
        }

        // 3. Рубли (₽)
        t = t.replace(Patterns.TtsNormalizing.RUBLE_SCALE) { match ->
            val num = match.groupValues[1].replace(",", ".")
            val scaleRaw = match.groupValues[2].lowercase().trim('.')
            val scale = when {
                scaleRaw.startsWith("тыс") -> "тысяч"
                scaleRaw.startsWith("млн") -> "миллионов"
                scaleRaw.startsWith("млрд") -> "миллиардов"
                scaleRaw.startsWith("трлн") -> "триллионов"
                else -> scaleRaw
            }
            "$num $scale рублей"
        }
        t = t.replace(Patterns.TtsNormalizing.RUBLE_KOP) { match ->
            val rub = if (match.groupValues[2].isNotEmpty()) match.groupValues[2] else match.groupValues[4]
            val kopRaw = if (match.groupValues[3].isNotEmpty()) match.groupValues[3] else match.groupValues[5]
            if (kopRaw.isEmpty()) "$rub рублей" else {
                val kopInt = if (kopRaw.length == 1) kopRaw.toInt() * 10 else kopRaw.toInt()
                "$rub рублей $kopInt ${getSubunitName(kopInt, "kop")}"
            }
        }

        // 4. Проценты (исправлено "точка процентов")
        // Сначала десятичные: 9.5% -> 9 целых 5 десятых процента
        t = t.replace(Regex("(\\d+)[,.](\\d+)\\s?%")) { m ->
            val whole = m.groupValues[1]
            val fract = m.groupValues[2]
            val fractWord = if (fract.length == 1) "и $fract десятых" else "и $fract сотых"
            "$whole $fractWord процента"
        }
        // Затем целые: 9% -> 9 процентов
        t = t.replace(Regex("(\\d+)\\s?%")) { "${it.groupValues[1]} процентов" }
        
        t = t.replace(Patterns.TtsNormalizing.RANGE) { "от ${it.groupValues[1]} до ${it.groupValues[2]}" }
        
        t = t.replace(Patterns.TtsNormalizing.COORDS_S) { "${it.groupValues[1]} градусов северной широты" }
        t = t.replace(Patterns.TtsNormalizing.COORDS_N) { "${it.groupValues[1]} градусов южной широты" }
        t = t.replace(Patterns.TtsNormalizing.COORDS_E) { "${it.groupValues[1]} градусов восточной долготы" }
        t = t.replace(Patterns.TtsNormalizing.COORDS_W) { "${it.groupValues[1]} градусов западной долготы" }
        t = t.replace(Regex("([+-]?\\d+[,.]?\\d*)\\s?°")) { "${it.groupValues[1]} градусов" }

        return t
    }

    fun expandAbbreviations(text: String): String {
        if (HeaderUtils.isChannelHeader(text)) return text
        var t = text
        
        Patterns.TtsNormalizing.UNITS.forEach { (regex, repl) -> t = t.replace(regex, repl) }
        Patterns.TtsNormalizing.PHRASES.forEach { (regex, repl) -> t = t.replace(regex, repl) }
        
        t = t.replace(Regex("(\\d+)\\s*зв\\.?\\s*вел\\.?", RegexOption.IGNORE_CASE)) { m ->
            val n = m.groupValues[1].toIntOrNull()
            if (n != null) "${numberToOrdinalFeminineGenitive(n)} звёздной величины" else m.value
        }

        Patterns.TtsNormalizing.MAPS.forEach { (regex, repl) -> t = t.replace(regex, repl) }
        Patterns.TtsNormalizing.NEWS_ABBR.forEach { (regex, repl) -> t = t.replace(regex, repl) }
        Patterns.TtsNormalizing.CENTURIES.forEach { (roman, ordinal) ->
            t = t.replace(Regex("\\b${Regex.escape(roman)}\\s+(век[аеу]?)\\b")) { m -> "$ordinal ${m.groupValues[1]}" }
        }

        return t
    }

    fun applyStressMarks(text: String): String {
        if (HeaderUtils.isChannelHeader(text)) return text
        var t = text
        t = Patterns.TtsNormalizing.STRESS_ZVEZDY.replace(t) { m -> m.value.dropLast(1) + "ы" + Patterns.General.STRESS_SYMBOL }
        t = Patterns.TtsNormalizing.STRESS_PEREKACHIVAT.replace(t) { m -> "${m.groupValues[1]}а${Patterns.General.STRESS_SYMBOL}${m.groupValues[2]}" }
        t = Patterns.TtsNormalizing.STRESS_POLOTNO.replace(t) { m -> "${m.value}${Patterns.General.STRESS_SYMBOL}" }
        // Исправлено: ударение в "лишения"
        t = t.replace(Regex("(?i)\\bлишени(я|ю|ям|ями|ях)\\b")) { m -> "лиш${Patterns.General.STRESS_SYMBOL}ени${m.groupValues[1]}" }
        return t
    }

    fun formatForSpeech(text: String): String {
        if (HeaderUtils.isChannelHeader(text)) return text
        var t = text.replace(Regex("(?<![.\\w])IT(?![/\\w])")) { "Ай-Ти" }
        
        Patterns.TtsNormalizing.TECH_ABBR.forEach { (abbr, full) ->
            t = t.replace(Regex("\\b$abbr\\b", RegexOption.IGNORE_CASE)) { full }
        }
        
        t = t.replace(Regex("\\bMAX\\b"), "Макс")
        t = t.replace(Regex("\\b(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})\\b")) { "${it.groupValues[1]} число ${it.groupValues[2]} месяца ${it.groupValues[3]} года" }

        val important = listOf("важно", "внимание", "срочно", "эксклюзив")
        important.forEach { word ->
            t = t.replace(Regex("\\b($word)\\b", RegexOption.IGNORE_CASE)) { "ВАЖНО. ${it.groupValues[1].lowercase()}... " }
        }
        t = t.replace(Patterns.TtsNormalizing.MOLNIYA, "$1ВАЖНО. молния... ")
        return t.trim()
    }

    // =============================================================================================
    // ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ И СТРУКТУРЫ
    // =============================================================================================

    private fun isRussianText(text: String): Boolean {
        val cyr = text.count { it in '\u0400'..'\u04FF' }
        val lat = text.count { it.isLetter() && it !in '\u0400'..'\u04FF' }
        return cyr >= lat
    }

    private fun getSubunitName(amount: Int, type: String): String {
        val mod10 = amount % 10
        val mod100 = amount % 100
        return when (type) {
            "kop" -> when { mod100 in 11..14 -> "копеек"; mod10 == 1 -> "копейка"; mod10 in 2..4 -> "копейки"; else -> "копеек" }
            "cent" -> when { mod100 in 11..14 -> "центов"; mod10 == 1 -> "цент"; mod10 in 2..4 -> "цента"; else -> "центов" }
            "pence" -> when { mod100 in 11..14 -> "пенсов"; mod10 == 1 -> "пенс"; mod10 in 2..4 -> "пенса"; else -> "пенсов" }
            else -> ""
        }
    }

    fun deduplicateLines(text: String): String {
        if (HeaderUtils.isChannelHeader(text)) return text
        val seen = HashSet<String>()
        return text.lines().map { it.trim() }.filter { it.isNotEmpty() && seen.add(it.lowercase()) }.joinToString("\n")
    }

    fun formatForIntonation(text: String): String {
        if (HeaderUtils.isChannelHeader(text)) return text
        var t = text.replace(" в нем", " в нём").replace(Regex("\\s*‼‼‼\\s*"), "Главное... ")

        val dateFull = Regex("\\b(\\d{1,2})\\s+(января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря)\\s+(\\d{4})(?:\\s*(?:года|г\\.))?\\b")
        t = dateFull.replace(t) { m -> "${numberToOrdinalRu(m.groupValues[1].toInt())} ${m.groupValues[2]} ${m.groupValues[3]} года" }

        val dateShort = Regex("\\b(\\d{1,2})\\s+(января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря)\\b")
        t = dateShort.replace(t) { m -> "${numberToOrdinalRu(m.groupValues[1].toInt())} ${m.groupValues[2]}" }

        t = t.replace(Regex("(\\d{4})[/-](\\d{4})")) { "${it.groupValues[1]} – ${it.groupValues[2]}" }
        t = t.replace(Regex("\\bкм/ч\\b", RegexOption.IGNORE_CASE), "километров в час")
        t = t.replace(Regex("\\bкм\\b", RegexOption.IGNORE_CASE), "километров")
        t = t.replace(Regex("\\b(\\d+)\\s?\\+\\s?(\\d+)\\b")) { "${it.groupValues[1]} плюс ${it.groupValues[2]}" }
        t = t.replace(Regex("\\b(\\d+)\\s?\\*\\s?(\\d+)\\b")) { "${it.groupValues[1]} умножить на ${it.groupValues[2]}" }

        t = t.replace(Regex("^[•·∙▪▫◦‣⁃]\\s+", RegexOption.MULTILINE)) { "— " }
        t = Patterns.Cleaning.GEOMETRIC_SHAPES.replace(t, "")
        t = t.replace(Regex("(?m)^[-•]\\s+"), "— ").replace(" - ", " — ").replace("...", "…")
        t = t.replace(Regex(";\\s*(?=\\n{2,})"), ". ")

        return t.trim()
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
                if (currentPart.isNotEmpty()) { parts.add(currentPart.trim()); currentPart = sentence }
                else { parts.add(sentence.take(maxChars)); currentPart = "" }
            }
        }
        if (currentPart.isNotEmpty()) parts.add(currentPart.trim())
        return parts.filter { it.isNotBlank() }
    }

    private fun numberToOrdinalRu(number: Int): String = when (number) {
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

    private fun numberToOrdinalFeminineGenitive(number: Int): String = when (number) {
        1 -> "первой"; 2 -> "второй"; 3 -> "третьей"; 4 -> "четвёртой"; 5 -> "пятой"
        6 -> "шестой"; 7 -> "седьмой"; 8 -> "восьмой"; 9 -> "девятой"; 10 -> "десятой"
        else -> "${number}-й"
    }

    // =============================================================================================
    // ВНУТРЕННИЙ ОБЪЕКТ С ПАТТЕРНАМИ (РЕГУЛЯРНЫЕ ВЫРАЖЕНИЯ)
    // =============================================================================================

    private object Patterns {
        object General {
            val URL_ONLY = Regex("^https?://.*$")
            val EMOJI_ONLY = Regex("^[\\p{So}\\p{Sk}\\s]+$")
            val BRACKET_TIME = Regex("^\\d{2}:\\d{2}\\s*—\\s*\\[.*]$")
            val TIME_PREFIX = Regex("^\\d{2}:\\d{2}\\s*—?\\s*")
            val TIMECODE_ANNOUNCE = Regex("(?m)^\\s*\\d{1,2}:\\d{2}\\s*[–—-]\\s*\\d{1,2}:\\d{2}\\b")
            val TRIVIAL = Regex("^(фото|видео|аудио|ссылка|репост)\\b.*$", RegexOption.IGNORE_CASE)
            val SUBSCRIBE_CHECK = Regex("(?i)^\\s*(?:\\d{2}:\\d{2}\\s*—\\s*)?(подписывай(ся|тесь)?|подпишись|подписка на канал)\\b[\\s\\S]{0,80}$")
            val SPAM_CHECK = Regex("(?i)^\\s*(?:\\d{2}:\\d{2}\\s*—\\s*)?(лайк|репост|поделись|нажми|кликни|переходи по ссылке)\\b[\\s\\S]{0,60}$")
            const val STRESS_SYMBOL = "\u0301"
        }

        object Promo {
            val ALL = listOf(
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
        }

        object Cleaning {
            val MEDIA_PREFIX = Regex("^\\d{2}:\\d{2}\\s*—\\s*(фото|видео|аудио|документ|gif|голосовое сообщение)[\\p{P}\\s]*", RegexOption.IGNORE_CASE)
            val MULTI_NEWLINE = Regex("\\n{3,}")
            val URL = Regex("https?://\\S+")
            val HASHTAG_MENTION = Regex("(^|\\s)[#@][\\p{L}0-9_]+")
            val EMOJI = Regex("[\\p{So}\\p{Sk}❗️!❤️💚💙💛💜🖤🤍🤎]+")
            val SUBSCRIBE = Regex("(?i)подпис(аться|ывай(ся|тесь)?)\\s+на\\s+[^\\n.]+")
            val BAZA_FOOTER = Regex("(?i)Если у вас плохо прогружаются файлы.*BAZA.*канале в MAX")
            val GEOMETRIC_SHAPES = Regex("[\\u25A0-\\u25FF\\u2B00-\\u2BFF▪▫◻◼◽◾◦‣⁃•·∙▸▹►▻🔹🔸🔶🔷🔺🔻🟠🟡🟢🟣🟤🟥🟦🟧🟨🟩🟪🟫⬛⬜]")
            val VARIATION_SELECTOR = Regex("[\\uFE00-\\uFE0F\\u200D]")
            val SUBSCRIBE_TAILS = listOf(
                Regex("(?i)[\\s\\p{So}\\p{Sk}]*[\\\\/|•·—–-]\\s*подпис(аться|ывай(ся|тесь)?|ка)\\b.*$"),
                Regex("(?i)[\\s\\p{So}\\p{Sk}]*[\\\\/|•·—–-]\\s*все\\s+наши\\s+каналы\\b.*$"),
                Regex("(?i)[\\s\\p{So}\\p{Sk}]*[\\\\/|•·—–-]\\s*зеркал[оа]\\b.*$"),
                Regex("(?im)^.*\\bподпис(аться|ывай(ся|тесь)?|ка)\\b.*(\\||/|•|—|–).*$"),
                Regex("(?im)^\\s*[\\p{So}\\p{Sk}]*\\s*зеркал[оа]\\s*(канала|нашего)?\\b.*$"),
                Regex("(?im)^\\s*[\\p{So}\\p{Sk}]*\\s*все\\s+наши\\s+каналы\\b.*$"),
            )
        }

        object TtsCleaning {
            val URL = Regex("(https?://|www\\.)\\S+")
            val HASHTAG = Regex("(^|\\s)[#@][\\p{L}0-9_]+")
            val FORWARD = Regex("(?im)^переслано из:?\\s.*$")
            val EDIT = Regex("(?im)^ред\\.?\\s*:?\\s*\\d{1,2}:\\d{2}.*$")
            val SUBSCRIBE = Regex("(?im)^\\s*(?:[\\p{So}\\p{Sk}❗️!❤️💚💙💛💜🖤🤍🤎]\\s*)*подписывай(ся|тесь)?\\b.*$")
            val SUBSCRIPTION = Regex("(?im)^\\s*подписка\\b.*$")
            val AD = Regex("(?im)^.*\\b(реклама|промокод)\\b.*$")
            val PROMO = Regex("(?im)^.*\\b(распродажа|купи)\\b.*$")
            val PHOTO_LINE = Regex("^Фото:.*$", RegexOption.MULTILINE)
            val RBK = Regex("^[\\p{So}\\p{Sk}]?\\s*(Читать РБК в Telegram|Следить за новостями РБК в Telegram|(Другие видео|Картина дня).*в телеграм-канале РБК).*$", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
            val RBK_MAX = Regex("(?im)^\\s*[\\p{So}\\p{Sk}]?\\s*канал\\s+рбк\\s+в\\s+[\"«\"']?макс[а-я]*[\"»\"']?\\s*$")
            val RBK_APP = Regex("(?im)^\\s*[\\p{So}\\p{Sk}]?\\s*приложение\\s+рбк\\s+для\\s+(ios|android)(\\s*(и|/|\\|)\\s*(ios|android))?\\s*$")
            val MAX_CHANNEL_TAIL = Regex("(?im)^.*\\bв\\s+(?:нашем\\s+)?канале?\\s+в\\s+[\"«]?макс[а-я]*[\"»]?\\.?\\s*$")
            val BAZA_FOOTER = Regex("(?i)Если у вас плохо прогружаются файлы.*BAZA.*канале в MAX")
            val SUBSCRIBE_INLINE = Regex("(?i)подписывай(ся|тесь)?\\s+на\\s+[^\\n.]+")
            val AI_ERROR = Regex("(?i)\\[AI Error.*?\\]")
            val READ_MORE = Regex("(?im)^\\s*\\[\\s*(read\\s+(full\\s+)?article|full\\s+story|watch\\s+video|more|source)\\s*\\]\\s*$")
            val PHONE = Regex("(?:\\+\\d{1,3}\\d{7,10}|(?<!\\d)\\+?\\d{1,3}(?!\\d)[\\s-]?\\(?\\d{1,4}\\)?[\\s-]?\\d{1,4}[\\s-]?\\d{1,4}[\\s-]?\\d{1,4}(?!\\d))")
            val EMOJI = Regex("[\\p{So}\\p{Sk}]")
            val LEADING_DASH = Regex("(?m)^\\s*[—–-]\\s+")
            val MARKDOWN = Regex("[*_`]+")
            val QUOTES = Regex("[«»]")
            val ELLIPSIS = Regex("\\.\\.\\.")
            val BLANK_LINES = Regex("(?m)^[ \\t]+$")
            val MULTI_SPACE = Regex("[ \\t]{2,}")
            val MULTI_NEWLINE = Regex("\\n{3,}")
            val SPACE_BEFORE_PUNCT = Regex("\\s+([,.;:!?…])")
        }

        object TtsNormalizing {
            val CURRENCIES = listOf(Triple("\\$", "долларов", "cent"), Triple("€", "евро", "cent"), Triple("£", "фунтов", "pence"))
            val RUBLE_SCALE = Regex("(?i)(?:₽|руб\\.?|р\\.)?\\s*(\\d+[\\d,.]*)\\s*(тыс\\.?|млн\\.?|млрд\\.?|трлн\\.?)\\s*(?:₽|руб\\.?|р\\.)?")
            val RUBLE_KOP = Regex("(?i)(₽)\\s?(\\d+)(?:[,.](\\d{1,2}))?|(\\d+)(?:[,.](\\d{1,2}))?\\s?(?:₽|руб\\.?|р\\.)\\b")
            val RANGE = Regex("(?<!\\d)\\b(\\d{1,3})\\s*[–—-]\\s*(\\d{1,3})\\b(?!\\d)")
            val COORDS_S = Regex("([+-]?\\d+[,.]?\\d*)\\s?°\\s?с\\.?\\s?ш\\.?", RegexOption.IGNORE_CASE)
            val COORDS_N = Regex("([+-]?\\d+[,.]?\\d*)\\s?°\\s?ю\\.?\\s?ш\\.?", RegexOption.IGNORE_CASE)
            val COORDS_E = Regex("([+-]?\\d+[,.]?\\d*)\\s?°\\s?в\\.?\\s?д\\.?", RegexOption.IGNORE_CASE)
            val COORDS_W = Regex("([+-]?\\d+[,.]?\\d*)\\s?°\\s?з\\.?\\s?д\\.?", RegexOption.IGNORE_CASE)
            
            val UNITS = mapOf(Regex("\\bмм\\s*рт\\.?\\s*ст\\.?", RegexOption.IGNORE_CASE) to "миллиметров ртутного столба", Regex("\\bкв\\.?\\s*м\\b", RegexOption.IGNORE_CASE) to "квадратных метров", Regex("\\bм/с\\b", RegexOption.IGNORE_CASE) to "метров в секунду")
            val PHRASES = mapOf(Regex("\\bСК\\s+России\\b") to "Следственный комитет России", Regex("\\bВС\\s+России\\b") to "Вооружённые силы России", Regex("(?i)\\bAstro\\s+Channel\\b") to "Астро Ченнел", Regex("(?i)\\bTime\\s*Lapse\\b") to "тайм лапс")
            val MAPS = mapOf(Regex("(?<=\\d)\\s*г\\.\\b") to " года", Regex("\\bг\\.\\b") to "город", Regex("\\bобл\\.\\b") to "область", Regex("\\bул\\.\\b") to "улица", Regex("\\bд\\.\\b(?=\\s?\\d)") to "дом", Regex("\\bт\\.д\\.\\b") to "так далее", Regex("\\bт\\.п\\.\\b") to "тому подобное", Regex("\\bсм\\.\\b") to "смотрите", Regex("\\bстр\\.\\b") to "страница", Regex("(?i)\\bтыс\\.?(?!я)") to "тысяч", Regex("\\bчел\\.\\b") to "человек", Regex("(?i)\\bАЗС\\b") to "А ЗЭ ЭС")
            val NEWS_ABBR = listOf(Regex("\\bРФ\\b") to "Россия", Regex("\\bЕС\\b(?![а-яёА-ЯЁ])") to "Евросоюз", Regex("\\bООН\\b") to "Организация Объединённых Наций", Regex("\\bЦБ\\b") to "Центробанк", Regex("\\bМВД\\b") to "министерство внутренних дел", Regex("\\bМИД\\b") to "министерство иностранных дел", Regex("\\bФСБ\\b") to "ФСБ", Regex("\\bМЧС\\b") to "МЧС", Regex("\\bВСУ\\b") to "вэсэу", Regex("\\bБПЛА\\b") to "бэ-пэ-эл-а", Regex("\\bНАТО\\b") to "НАТО", Regex("\\bпр-т\\b") to "проспект", Regex("\\bгр\\.\\b") to "гражданин", Regex("\\bтрлн\\.?\\b", RegexOption.IGNORE_CASE) to "триллионов", Regex("\\bмлрд\\.?\\b", RegexOption.IGNORE_CASE) to "миллиардов", Regex("\\bмлн\\.?\\b", RegexOption.IGNORE_CASE) to "миллионов")
            val CENTURIES = linkedMapOf("XXII" to "двадцать второго", "XXI" to "двадцать первого", "XX" to "двадцатого", "XIX" to "девятнадцатого", "XVIII" to "восемнадцатого", "XVII" to "семнадцатого", "XVI" to "шестнадцатого", "XV" to "пятнадцатого", "XIV" to "четырнадцатого", "XIII" to "тринадцатого", "XII" to "двенадцатого", "XI" to "одиннадцатого", "X" to "десятого", "IX" to "девятого", "VIII" to "восьмого", "VII" to "седьмого", "VI" to "шестого", "V" to "пятого", "IV" to "четвёртого", "III" to "третьего", "II" to "второго", "I" to "первого", "ХХІ" to "двадцать первого", "ХХ" to "двадцатого", "ХІХ" to "девятнадцатого", "ХVIII" to "восемнадцатого", "ХVII" to "семнадцатого", "ХVI" to "шестнадцатого", "ХV" to "пятнадцатого", "ХIV" to "четырнадцатого", "ХIII" to "тринадцатого", "ХII" to "двенадцатого", "ХI" to "одиннадцатого", "Х" to "десятого")
            
            val STRESS_ZVEZDY = Regex("(?i)\\bвспышк[а-яё]*(?:\\s+[а-яё]+){0,3}?\\s+звезды\\b")
            val STRESS_PEREKACHIVAT = Regex("(?i)\\b(перек)а(чива[а-яё]*)\\b")
            val STRESS_POLOTNO = Regex("(?i)\\bполотно\\b")
            
            val TECH_ABBR = mapOf("AI" to "искусственный интеллект", "ИИ" to "искусственный интеллект", "VR" to "виртуальная реальность", "AR" to "дополненная реальность", "GPS" to "Джи-Пи-Эс", "USB" to "ЮСБ", "WIFI" to "Вай-Фай")
            val MOLNIYA = Regex("(?im)^(⚡+\\s*|\\d{2}:\\d{2}\\s*—?\\s*)(молния)(?=[\\s!.,]|$)")
        }

        object Deduplication {
            val NUMBERS = Regex("\\d+(?:[.,]\\d+)?")
            val ABBREVIATIONS = Regex("\\b\\p{Lu}{2,6}\\b")
            val PROPER_NAMES = Regex("\\b\\p{Lu}\\p{Ll}{2,}\\b", RegexOption.MULTILINE)
        }

        object Lang {
            val STOP_WORDS_RU = setOf("в", "на", "с", "и", "по", "к", "за", "из", "о", "от", "для", "что", "как", "это", "не", "но", "а", "же", "ли", "бы", "то", "вот", "все", "уже", "при", "до", "так", "его", "её", "их", "он", "она", "они", "мы", "вы")
            val STOP_WORDS_EN = setOf("the", "a", "an", "and", "or", "but", "of", "in", "on", "at", "to", "for", "with", "by", "from", "as", "is", "are", "was", "were", "be", "been", "it", "its", "this", "that", "these", "those", "he", "she", "they", "we", "you", "his", "her", "their", "our", "your", "has", "have", "had", "will", "would", "not", "no", "so", "than", "then", "into", "over", "after")
            val NOISE_ANCHORS = setOf("бпла", "дрон", "дрона", "дронов", "дроны", "беспилотник", "беспилотника", "беспилотников", "атака", "атаки", "атаку", "удар", "удары", "удара", "россии", "россия", "рф", "украины", "украина", "украинских", "минобороны", "пво", "сообщает", "сообщили", "данным", "регион", "региона", "регионов", "регионам", "ночь", "ночью", "утра", "утром", "канал", "канала", "новости")
        }
    }
}
