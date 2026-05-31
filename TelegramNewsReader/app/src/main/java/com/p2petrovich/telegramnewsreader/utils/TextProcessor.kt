package com.p2petrovich.telegramnewsreader.utils

import android.util.Log
import com.p2petrovich.telegramnewsreader.services.NewsService
import com.p2petrovich.telegramnewsreader.utils.Logx

object TextProcessor {

    private const val TAG = "TextProcessor"
    private const val CROSS_CHANNEL_JACCARD_THRESHOLD = 0.7

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
    private val TTS_AD_PATTERN = Regex("(?im)^.*\\b(реклама|промокод)\\b.*$")
    private val TTS_PROMO_PATTERN = Regex("(?im)^.*\\b(распродажа|купи)\\b.*$")
    private val TTS_PHOTO_LINE_PATTERN = Regex("^Фото:.*$", RegexOption.MULTILINE)
    private val TTS_RBK_PATTERN = Regex(
        "^[\\p{So}\\p{Sk}]?\\s*(Читать РБК в Telegram|Следить за новостями РБК в Telegram|(Другие видео|Картина дня).*в телеграм-канале РБК).*$",
        setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)
    )

    // ─── Дополнительные хвосты РБК (Макс, мобильные приложения) ────────────────
    // Срабатывают на строки вида:
    //   «Канал РБК в "Максе"»     /  «Канал РБК в «Максе»»  / «Канал РБК в Максе»
    //   «Приложение РБК для iOS и Android»  /  «Приложение РБК для iOS / Android»
    private val TTS_RBK_MAX_PATTERN = Regex(
        "(?im)^\\s*[\\p{So}\\p{Sk}]?\\s*канал\\s+рбк\\s+в\\s+[\"«\"']?макс[а-я]*[\"»\"']?\\s*$"
    )
    private val TTS_RBK_APP_PATTERN = Regex(
        "(?im)^\\s*[\\p{So}\\p{Sk}]?\\s*приложение\\s+рбк\\s+для\\s+(ios|android)(\\s*(и|/|\\|)\\s*(ios|android))?\\s*$"
    )

    private val TTS_PHONE_PATTERN = Regex("\\+?\\d{1,3}[\\s-]?\\(?\\d{1,4}\\)?[\\s-]?\\d{1,4}[\\s-]?\\d{1,4}[\\s-]?\\d{1,4}")
    private val TTS_COLORED_SQUARES_PATTERN = Regex("[🟩🟨🟥🟦🟪🟫⬛⬜]")
    private val TTS_EMOJI_PATTERN = Regex("[\\p{So}\\p{Sk}]")
    private val TTS_MARKDOWN_PATTERN = Regex("[*_`]+")
    private val TTS_QUOTES_PATTERN = Regex("[«»]")
    private val TTS_SUBSCRIBE_INLINE_PATTERN = Regex("(?i)подписывай(ся|тесь)?\\s+на\\s+[^\\n.]+")
    private val TTS_BAZA_FOOTER_PATTERN = Regex("(?i)Если у вас плохо прогружаются файлы.*BAZA.*канале в MAX")
    private val TTS_ELLIPSIS_PATTERN = Regex("\\.\\.\\.")
    private val TTS_MULTI_SPACE_PATTERN = Regex("[ \\t]{2,}")
    private val TTS_MULTI_NEWLINE = Regex("\\n{3,}")
    private val TTS_AI_ERROR_PATTERN = Regex("(?i)\\[AI Error.*?\\]")

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

    const val MAX_NEWS_DEFAULT = 500

    fun filterMessages(
        messages: List<String>,
        maxNews: Int = MAX_NEWS_DEFAULT,
        onFilterProgress: ((originalCount: Int, filteredCount: Int) -> Unit)? = null,
        onTruncated: ((kept: Int, dropped: Int) -> Unit)? = null
    ): List<String> {
        Logx.i(TAG) { "====== FILTER START: ${messages.size} messages ======" }

        var droppedTooShort = 0
        var droppedUrlOnly = 0
        var droppedEmojiOnly = 0
        var droppedBracketTime = 0
        var droppedPromo = 0
        var droppedAfterClean = 0
        var droppedTooLong = 0

        val cleanedList = messages.mapNotNull { original ->
            if (NewsService.isChannelHeader(original)) return@mapNotNull original

            val trimmed = original.trim()

            if (trimmed.length <= 3) {
                droppedTooShort++
                Logx.d(TAG) { "SPAM [too_short] len=${trimmed.length}" }
                return@mapNotNull null
            }

            if (trimmed.matches(Regex("^https?://.*$"))) {
                droppedUrlOnly++
                Logx.d(TAG) { "SPAM [url_only] len=${trimmed.length}" }
                return@mapNotNull null
            }

            if (trimmed.matches(Regex("^[\\p{So}\\p{Sk}\\s]+$"))) {
                droppedEmojiOnly++
                Logx.d(TAG) { "SPAM [emoji_only] len=${trimmed.length}" }
                return@mapNotNull null
            }

            if (trimmed.matches(Regex("^\\d{2}:\\d{2}\\s*—\\s*\\[.*]$"))) {
                droppedBracketTime++
                Logx.d(TAG) { "SPAM [media_no_text] len=${trimmed.length}" }
                return@mapNotNull null
            }

            val matchedPromo = PROMO_PATTERNS.firstOrNull { it.containsMatchIn(trimmed) }
            if (matchedPromo != null) {
                droppedPromo++
                Logx.d(TAG) { "SPAM [promo: ${matchedPromo.pattern.take(30)}] len=${trimmed.length}" }
                return@mapNotNull null
            }

            var cleaned = trimmed
            cleaned = MEDIA_PREFIX_PATTERN.replace(cleaned, "")
            cleaned = MULTI_NEWLINE_PATTERN.replace(cleaned, "\n\n")
            cleaned = URL_PATTERN.replace(cleaned, "")
            cleaned = HASHTAG_MENTION_PATTERN.replace(cleaned, " ")
            cleaned = EMOJI_PATTERN.replace(cleaned, " ")
            cleaned = SUBSCRIBE_PATTERN.replace(cleaned, "")
            cleaned = TTS_BAZA_FOOTER_PATTERN.replace(cleaned, "")
            cleaned = cleaned.trim()

            SUBSCRIBE_TAIL_PATTERNS.forEach { pattern ->
                cleaned = pattern.replace(cleaned, "")
            }

            if (cleaned.isBlank() || cleaned.length <= 5) {
                droppedAfterClean++
                Logx.d(TAG) { "SPAM [empty_after_clean] len=${trimmed.length}" }
                return@mapNotNull null
            }

            val finalMessage = if (cleaned.length > 5000) {
                droppedTooLong++
                Logx.d(TAG) { "TRIMMED [>5000] len=${trimmed.length}" }
                cleaned.take(4970) + "..."
            } else cleaned

            Logx.d(TAG) { "OK: len=${finalMessage.length}" }
            finalMessage
        }.distinct()

        val limited: List<String>
        if (maxNews <= 0) {
            limited = cleanedList
        } else {
            var newsCount = 0
            var droppedNews = 0
            limited = cleanedList.filter { item ->
                if (NewsService.isChannelHeader(item)) {
                    true
                } else if (newsCount < maxNews) {
                    newsCount++
                    true
                } else {
                    droppedNews++
                    false
                }
            }
            if (droppedNews > 0) {
                Logx.w(TAG, "filterMessages: truncated $droppedNews news (kept $newsCount / limit $maxNews)")
                onTruncated?.invoke(newsCount, droppedNews)
            }
        }

        val newsKept = limited.count { !NewsService.isChannelHeader(it) }
        Logx.i(TAG) { "====== FILTER RESULT: ${messages.size} -> ${limited.size} (news: $newsKept) ======" }
        Logx.i(TAG) { "  too_short=$droppedTooShort url=$droppedUrlOnly emoji=$droppedEmojiOnly" }
        Logx.i(TAG) { "  media=$droppedBracketTime promo=$droppedPromo empty_clean=$droppedAfterClean trim=$droppedTooLong" }

        onFilterProgress?.invoke(messages.size, limited.size)
        return limited
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
                    else (intersection.toDouble() / union) > CROSS_CHANNEL_JACCARD_THRESHOLD
                }
            }

            if (!isDuplicate) {
                result.add(msg)
                fingerprints.add(fp)
            } else {
                removedCount++
                Logx.d(TAG) { "DEDUP [jaccard>$CROSS_CHANNEL_JACCARD_THRESHOLD] len=${msg.length}" }
            }
        }

        Logx.i(TAG) { "dedup: ${messages.size} -> ${result.size} (removed $removedCount)" }
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
        t = TTS_RBK_MAX_PATTERN.replace(t, "")   // ← новое: «Канал РБК в Максе»
        t = TTS_RBK_APP_PATTERN.replace(t, "")   // ← новое: «Приложение РБК для iOS и Android»
        t = TTS_PHONE_PATTERN.replace(t, "")
        t = TTS_COLORED_SQUARES_PATTERN.replace(t, "")
        t = TTS_EMOJI_PATTERN.replace(t, " ")
        t = TTS_MARKDOWN_PATTERN.replace(t, "")
        t = TTS_QUOTES_PATTERN.replace(t, "\"")
        t = TTS_SUBSCRIBE_INLINE_PATTERN.replace(t, "")
        t = TTS_BAZA_FOOTER_PATTERN.replace(t, "")
        t = TTS_ELLIPSIS_PATTERN.replace(t, "…")
        t = TTS_MULTI_SPACE_PATTERN.replace(t, " ")
        t = TTS_MULTI_NEWLINE.replace(t, "\n\n")
        t = TTS_AI_ERROR_PATTERN.replace(t, "")
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

        // Валюты
        t = t.replace(Regex("\\$(\\d+)"), "$1 долларов")
        t = t.replace(Regex("€(\\d+)"), "$1 евро")
        t = t.replace(Regex("£(\\d+)"), "$1 фунтов")

        t = t.replace(Regex("на\\s+(\\d+[,.]?\\d*)\\s?%")) { "на ${it.groupValues[1]} процентов" }
        t = t.replace(Regex("(\\d+[,.]?\\d*)%-й")) { "${it.groupValues[1]}-процентный" }
        t = t.replace(Regex("(\\d+[,.]?\\d*)%-е")) { "${it.groupValues[1]}-процентные" }
        t = t.replace(Regex("\\b(\\d+[,.]?\\d*)\\s?%\\b")) { "${it.groupValues[1]} процентов" }

        // ─── Градусы и географические координаты ──────────────────────────────
        // Порядок важен: сначала координаты «45° с. ш.», потом обычные «10°C»,
        // в конце — одинокий «°» без буквы как fallback.

        // Координаты: «45° с. ш.», «45°с.ш.», «45° ю. ш.», «60° в. д.», «120° з. д.»
        t = t.replace(
            Regex("([+-]?\\d+[,.]?\\d*)\\s?°\\s?с\\.?\\s?ш\\.?", RegexOption.IGNORE_CASE)
        ) { "${it.groupValues[1]} градусов северной широты" }
        t = t.replace(
            Regex("([+-]?\\d+[,.]?\\d*)\\s?°\\s?ю\\.?\\s?ш\\.?", RegexOption.IGNORE_CASE)
        ) { "${it.groupValues[1]} градусов южной широты" }
        t = t.replace(
            Regex("([+-]?\\d+[,.]?\\d*)\\s?°\\s?в\\.?\\s?д\\.?", RegexOption.IGNORE_CASE)
        ) { "${it.groupValues[1]} градусов восточной долготы" }
        t = t.replace(
            Regex("([+-]?\\d+[,.]?\\d*)\\s?°\\s?з\\.?\\s?д\\.?", RegexOption.IGNORE_CASE)
        ) { "${it.groupValues[1]} градусов западной долготы" }

        // Температура с явной буквой: «10°C», «-5 °С» (латинская C или русская С)
        t = t.replace(Regex("([+-]?\\d+[,.]?\\d*)\\s?°\\s?[CС]\\b")) { "${it.groupValues[1]} градусов" }

        // Fallback: одинокий «°» без буквы — просто «градусов» (например, «45°»)
        t = t.replace(Regex("([+-]?\\d+[,.]?\\d*)\\s?°")) { "${it.groupValues[1]} градусов" }

        return t
    }

    fun formatForIntonation(text: String): String {
        if (NewsService.isChannelHeader(text)) return text

        var t = text

        t = t.replace(" в нем", " в нём")
        t = t.replace(Regex("\\s*‼‼‼\\s*"), "Главное... ")

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

        // Улучшение пауз между предложениями
        t = t.replace(Regex("(?<=[.!?…])\\s+"), "... ")

        return t.trim()
    }

    fun expandAbbreviations(text: String): String {
        if (NewsService.isChannelHeader(text)) return text

        var t = text
        val maps = mapOf(
            Regex("\\bг\\.\\b") to "город",
            Regex("\\bобл\\.\\b") to "область",
            Regex("\\bул\\.\\b") to "улица",
            Regex("\\bд\\.\\b(?=\\s?\\d)") to "дом",
            Regex("\\bт\\.д\\.\\b") to "так далее",
            Regex("\\bт\\.п\\.\\b") to "тому подобное",
            Regex("\\bсм\\.\\b") to "смотрите",
            Regex("\\bстр\\.\\b") to "страница",
            Regex("\\bтыс\\.\\b") to "тысяч",
            Regex("\\bчел\\.\\b") to "человек"
        )
        maps.forEach { (regex, replacement) ->
            t = t.replace(regex, replacement)
        }

        // Новостные аббревиатуры — \\b не затронет слова вроде "ЦБРФ"
        val newsAbbreviations = listOf(
            Regex("\\bРФ\\b")                               to "Россия",
            Regex("\\bСША\\b")                              to "Соединённые Штаты",
            Regex("\\bЕС\\b(?![а-яёА-ЯЁ])")                to "Евросоюз",
            Regex("\\bООН\\b")                              to "Организация Объединённых Наций",
            Regex("\\bЦБ\\b")                               to "Центробанк",
            Regex("\\bМВД\\b")                              to "министерство внутренних дел",
            Regex("\\bМИД\\b")                              to "министерство иностранных дел",
            Regex("\\bФСБ\\b")                              to "ФСБ",
            Regex("\\bМЧС\\b")                              to "МЧС",
            Regex("\\bВСУ\\b")                              to "украинские войска",
            Regex("\\bНАТО\\b")                             to "НАТО",
            Regex("\\bпр-т\\b")                             to "проспект",
            Regex("\\bгр\\.\\b")                            to "гражданин",
            Regex("\\bтрлн\\.?\\b", RegexOption.IGNORE_CASE) to "триллионов",
            Regex("\\bмлрд\\.?\\b", RegexOption.IGNORE_CASE) to "миллиардов",
            Regex("\\bмлн\\.?\\b",  RegexOption.IGNORE_CASE) to "миллионов"
        )
        newsAbbreviations.forEach { (regex, replacement) ->
            t = t.replace(regex, replacement)
        }

        return t
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
                "ВАЖНО. ${it.groupValues[1].lowercase()}... "
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
                    Logx.d(TAG) { "DROP [<8chars] len=${withoutTimePrefix.length}" }
                    false
                }
                TRIVIAL_PATTERN.containsMatchIn(withoutTimePrefix) -> {
                    droppedTrivial++
                    Logx.d(TAG) { "DROP [trivial] len=${withoutTimePrefix.length}" }
                    false
                }
                // Удаляем только если ВСЁ сообщение — короткий призыв подписаться
                SUBSCRIBE_CHECK_PATTERN.matches(trimmed) -> {
                    droppedSubscribe++
                    Logx.d(TAG) { "DROP [subscribe_short] len=${trimmed.length}" }
                    false
                }
                // Удаляем только если ВСЁ сообщение — короткий спам-призыв
                SPAM_CHECK_PATTERN.matches(trimmed) -> {
                    droppedSpam++
                    Logx.d(TAG) { "DROP [spam_short] len=${trimmed.length}" }
                    false
                }
                else -> true
            }
        }

        if (texts.size != result.size) {
            Logx.i(TAG) { "dropTrivial: ${texts.size} -> ${result.size} (short=$droppedShort trivial=$droppedTrivial subscribe=$droppedSubscribe spam=$droppedSpam)" }
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
