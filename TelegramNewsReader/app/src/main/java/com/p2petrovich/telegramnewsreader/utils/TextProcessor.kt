package com.p2petrovich.telegramnewsreader.utils

import com.p2petrovich.telegramnewsreader.services.NewsService

object TextProcessor {

    private const val TAG = "TextProcessor"

    private const val ANCHOR_MATCH_RATIO = 0.6
    private const val WORD_JACCARD_MIN = 0.4
    private const val MIN_ANCHORS = 3

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

    // ─── Символьный мусор (расширено) ───────────────────────────────
    // Все геометрические фигуры (квадраты/круги/ромбы/треугольники), буллеты,
    // цветные квадраты-эмодзи. Покрывает ◻ ◼ ▪ ▫ ⬜ ⬛ 🟥 и т.п.
    private val GEOMETRIC_SHAPES_PATTERN = Regex(
        "[\\u25A0-\\u25FF\\u2B00-\\u2BFF▪▫◻◼◽◾◦‣⁃•·∙▸▹►▻🔹🔸🔶🔷🔺🔻🟠🟡🟢🟣🟤🟥🟦🟧🟨🟩🟪🟫⬛⬜]"
    )
    // Вариационные селекторы и zero-width joiner (HEADER_MARKER проверяется
    // через isChannelHeader ДО чистки, поэтому тут безопасно).
    private val VARIATION_SELECTOR_PATTERN = Regex("[\\uFE00-\\uFE0F\\u200D]")

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
    private val TTS_RBK_MAX_PATTERN = Regex(
        "(?im)^\\s*[\\p{So}\\p{Sk}]?\\s*канал\\s+рбк\\s+в\\s+[\"«\"']?макс[а-я]*[\"»\"']?\\s*$"
    )
    private val TTS_RBK_APP_PATTERN = Regex(
        "(?im)^\\s*[\\p{So}\\p{Sk}]?\\s*приложение\\s+рбк\\s+для\\s+(ios|android)(\\s*(и|/|\\|)\\s*(ios|android))?\\s*$"
    )
    // "Больше инфографики/дайджестов/видео — в нашем канале в «Максе»."
    private val TTS_MAX_CHANNEL_TAIL_PATTERN = Regex(
        "(?im)^.*\\bв\\s+(?:нашем\\s+)?канале?\\s+в\\s+[\"«]?макс[а-я]*[\"»]?\\.?\\s*$"
    )
    // Два варианта: слитный международный (+79161234567) и разделённый (8 800 555-35-35).
    // (?!\d) после первого блока исключает диапазоны лет вида 2027-2028.
    private val TTS_PHONE_PATTERN = Regex(
        "(?:\\+\\d{1,3}\\d{7,10}" +
        "|(?<!\\d)\\+?\\d{1,3}(?!\\d)[\\s-]?\\(?\\d{1,4}\\)?[\\s-]?\\d{1,4}[\\s-]?\\d{1,4}[\\s-]?\\d{1,4}(?!\\d))"
    )
    private val TTS_EMOJI_PATTERN = Regex("[\\p{So}\\p{Sk}]")
    private val TTS_MARKDOWN_PATTERN = Regex("[*_`]+")
    private val TTS_QUOTES_PATTERN = Regex("[«»]")
    private val TTS_SUBSCRIBE_INLINE_PATTERN = Regex("(?i)подписывай(ся|тесь)?\\s+на\\s+[^\\n.]+")
    private val TTS_BAZA_FOOTER_PATTERN = Regex("(?i)Если у вас плохо прогружаются файлы.*BAZA.*канале в MAX")
    private val TTS_ELLIPSIS_PATTERN = Regex("\\.\\.\\.")
    private val TTS_MULTI_SPACE_PATTERN = Regex("[ \\t]{2,}")
    private val TTS_MULTI_NEWLINE = Regex("\\n{3,}")
    private val TTS_AI_ERROR_PATTERN = Regex("(?i)\\[AI Error.*?\\]")
    // Заглушки-ссылки вида [Read Full Article], [Full Story] и т.п.
    private val TTS_READ_MORE_PATTERN = Regex("(?im)^\\s*\\[\\s*(read\\s+(full\\s+)?article|full\\s+story|watch\\s+video|more|source)\\s*\\]\\s*$")

    // Висячее тире/дефис в начале строки (остаётся после среза префикса времени)
    private val LEADING_DASH_PATTERN = Regex("(?m)^\\s*[—–-]\\s+")
    // Пробельные строки после удаления маркеров
    private val BLANK_LINES_PATTERN = Regex("(?m)^[ \\t]+$")
    // Пробел перед знаками препинания (появляется после чисток)
    private val SPACE_BEFORE_PUNCT_PATTERN = Regex("\\s+([,.;:!?…])")

    private val TRIVIAL_PATTERN = Regex("^(фото|видео|аудио|ссылка|репост)\\b.*$", RegexOption.IGNORE_CASE)

    // ─── Анонс видеовыпуска с тайм-кодами ───────────────────────────
    // Строки вида "00:00-04:10 — Иран нарушил…" — это оглавление видео РБК,
    // на слух бессмысленно ("ноль-ноль ноль-ноль дефис ноль-четыре десять").
    // Если в сообщении есть такая разметка тайм-кодов — дропаем новость целиком.
    private val TIMECODE_ANNOUNCE_PATTERN = Regex(
        "(?m)^\\s*\\d{1,2}:\\d{2}\\s*[–—-]\\s*\\d{1,2}:\\d{2}\\b"
    )

    private val SUBSCRIBE_CHECK_PATTERN = Regex(
        "(?i)^\\s*(?:\\d{2}:\\d{2}\\s*—\\s*)?(подписывай(ся|тесь)?|подпишись|подписка на канал)\\b[\\s\\S]{0,80}$"
    )
    private val SPAM_CHECK_PATTERN = Regex(
        "(?i)^\\s*(?:\\d{2}:\\d{2}\\s*—\\s*)?(лайк|репост|поделись|нажми|кликни|переходи по ссылке)\\b[\\s\\S]{0,60}$"
    )

    // Префикс времени — единое определение, используется везде
    private val TIME_PREFIX_PATTERN = Regex("^\\d{2}:\\d{2}\\s*—?\\s*")

    // ============ Стоп-слова (RU + EN) ============

    private val STOP_WORDS_RU = setOf(
        "в", "на", "с", "и", "по", "к", "за", "из", "о", "от",
        "для", "что", "как", "это", "не", "но", "а", "же", "ли",
        "бы", "то", "вот", "все", "уже", "при", "до", "так",
        "его", "её", "их", "он", "она", "они", "мы", "вы"
    )

    private val STOP_WORDS_EN = setOf(
        "the", "a", "an", "and", "or", "but", "of", "in", "on", "at",
        "to", "for", "with", "by", "from", "as", "is", "are", "was",
        "were", "be", "been", "it", "its", "this", "that", "these",
        "those", "he", "she", "they", "we", "you", "his", "her",
        "their", "our", "your", "has", "have", "had", "will", "would",
        "not", "no", "so", "than", "then", "into", "over", "after"
    )

    // ─── Шумовые якоря ──────────────────────────────────────────────
    // Слова/аббревиатуры, которые встречаются почти в каждой второй новости
    // текущего потока и потому НЕ должны служить основанием для склейки
    // разных событий. "бпла"/"дрон"/"атака" объединяли Крым и Новороссийск —
    // теперь не объединяют. Числовые якоря (310 и т.п.) при этом сохраняются
    // и продолжают корректно склеивать реальные дубли.
    private val NOISE_ANCHORS = setOf(
        "бпла", "бпла", "дрон", "дрона", "дронов", "дроны",
        "беспилотник", "беспилотника", "беспилотников",
        "атака", "атаки", "атаку", "удар", "удары", "удара",
        "россии", "россия", "рф", "украины", "украина", "украинских",
        "минобороны", "пво", "сообщает", "сообщили", "данным",
        "регион", "региона", "регионов", "регионам",
        "ночь", "ночью", "утра", "утром", "канал", "канала", "новости"
    )

    private fun isRussianText(text: String): Boolean {
        val cyr = text.count { it in '\u0400'..'\u04FF' }
        val lat = text.count { it.isLetter() && it !in '\u0400'..'\u04FF' }
        return cyr >= lat
    }

    private fun stopWordsFor(text: String): Set<String> =
        if (isRussianText(text)) STOP_WORDS_RU else STOP_WORDS_EN

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

    // ============ Дедупликация (якорная, языконезависимая) ============

    /**
     * Отпечаток новости с раздельными типами якорей.
     * - numbers: числа-события (310, 50%, 12.2k) — точные, сильные сигналы.
     * - strongAnchors: имена собственные + аббревиатуры, КРОМЕ шумовых.
     * - words: значимые слова для запасного Jaccard-критерия.
     */
    data class Fingerprint(
        val words: Set<String>,
        val anchors: Set<String>,        // сохранено для обратной совместимости (numbers + strong)
        val numbers: Set<String> = emptySet(),
        val strongAnchors: Set<String> = emptySet()
    )

    fun deduplicateAcrossChannels(
        messages: List<String>,
        threshold: Double = ANCHOR_MATCH_RATIO
    ): List<String> {
        if (messages.size <= 1) return messages

        val result = mutableListOf<String>()
        val fingerprints = mutableListOf<Fingerprint>()
        var removedCount = 0

        for (msg in messages) {
            if (NewsService.isChannelHeader(msg)) {
                result.add(msg)
                continue
            }

            val fp = extractFingerprint(msg)

            if (fp.words.size < 3) {
                result.add(msg)
                fingerprints.add(fp)
                continue
            }

            val matched = fingerprints.firstOrNull { existing -> isSameEvent(fp, existing, threshold) }

            if (matched == null) {
                result.add(msg)
                fingerprints.add(fp)
            } else {
                removedCount++
                val common = fp.strongAnchors.intersect(matched.strongAnchors) +
                             fp.numbers.intersect(matched.numbers)
                Logx.d(TAG) { "DEDUP [match thr=$threshold] common=$common len=${msg.length}" }
            }
        }

        Logx.i(TAG) { "dedup: ${messages.size} -> ${result.size} (removed $removedCount, threshold=$threshold)" }
        return result
    }

    fun extractFingerprint(text: String): Fingerprint {
        val stop = stopWordsFor(text)

        val body = TIME_PREFIX_PATTERN.replace(text, "")

        // ── ЧИСЛА ────────────────────────────────────────────────────
        var joinedDigits = body.replace(Regex("(?<=\\d)[\\s\u00A0](?=\\d{3}\\b)"), "")
        joinedDigits = joinedDigits.replace(Regex("(?<=\\d),(?=\\d{3}\\b)"), "")

        val numbers = Regex("\\d+(?:[.,]\\d+)?")
            .findAll(joinedDigits)
            .map { it.value.replace(',', '.') }
            .filter { it.length >= 2 }
            .map { num ->
                val intPart = num.substringBefore('.')
                if (!num.contains('.') && intPart.length >= 4) {
                    intPart.take(3) + "k"
                } else {
                    num
                }
            }
            .toSet()

        // ── АББРЕВИАТУРЫ (кросс-язычные), исключая шум ──────────────
        val abbreviations = Regex("\\b\\p{Lu}{2,6}\\b")
            .findAll(body)
            .map { it.value.lowercase() }
            .filter { it !in stop && it !in NOISE_ANCHORS }
            .toSet()

        // ── ИМЕНА (заглавная не в начале предложения), исключая шум ──
        val properNames = Regex("(?<![.!?…]\\s)(?<!^)\\b\\p{Lu}\\p{Ll}{2,}\\b", RegexOption.MULTILINE)
            .findAll(body)
            .map { it.value.lowercase() }
            .filter { it !in stop && it !in NOISE_ANCHORS }
            .toSet()

        val strongAnchors = abbreviations + properNames
        val anchors = numbers + strongAnchors

        // ── ОБЫЧНЫЕ СЛОВА ────────────────────────────────────────────
        val cleaned = body.lowercase().replace(Regex("[^\\p{L}\\s]"), " ")
        val words = cleaned.split(Regex("\\s+"))
            .filter { it.length > 3 && it !in stop }
            .toSet()

        return Fingerprint(words, anchors, numbers, strongAnchors)
    }

    /**
     * Две новости — одно событие, если выполняется одно из:
     *  A) совпали ≥2 сильных якоря (имена/аббревиатуры), ИЛИ
     *  B) совпал ≥1 сильный якорь И совпало ≥1 число-событие, ИЛИ
     *  C) совпали ≥2 числа-события (редкий, но точный случай), ИЛИ
     *  D) высокий Jaccard по словам (запасной критерий).
     *
     * Порог threshold масштабирует требования: при высоком пороге требуем
     * более полного совпадения сильных якорей.
     */
    fun isSameEvent(
        a: Fingerprint,
        b: Fingerprint,
        threshold: Double = ANCHOR_MATCH_RATIO
    ): Boolean {
        val strongCommon = a.strongAnchors.intersect(b.strongAnchors).size
        val numberCommon = a.numbers.intersect(b.numbers).size
        val strongMin = minOf(a.strongAnchors.size, b.strongAnchors.size)

        // Доля совпавших сильных якорей относительно меньшего набора
        val strongRatio = if (strongMin > 0) strongCommon.toDouble() / strongMin else 0.0

        // A) Два и более сильных якоря совпали — и это заметная доля
        if (strongCommon >= 2 && strongRatio >= threshold) return true

        // B) Один сильный якорь + хотя бы одно общее число-событие
        if (strongCommon >= 1 && numberCommon >= 1) return true

        // C) Два и более общих числа (например "310" + "98") при очень коротких текстах
        if (numberCommon >= 2) return true

        // D) Запасной Jaccard по словам
        if (a.words.isNotEmpty() && b.words.isNotEmpty()) {
            val intersection = a.words.intersect(b.words).size
            val union = a.words.union(b.words).size
            val wordThreshold = threshold.coerceAtLeast(WORD_JACCARD_MIN)
            if (union > 0 && intersection.toDouble() / union >= wordThreshold) return true
        }

        return false
    }

    // ============ Единый речевой конвейер ============

    /**
     * Полная подготовка текста к синтезу. Применяется к КАЖДОЙ новости
     * непосредственно перед TTS — и при включённом AI, и при выключенном —
     * чтобы результат был идентичным независимо от режима.
     *
     * Вставка межфразовых пауз ("...") НЕ входит сюда: она нужна только
     * Android TTS и добавляется отдельным шагом в TTSManager.
     */
    fun prepareForSpeech(text: String): String {
        if (NewsService.isChannelHeader(text)) return text
        var t = cleanForTts(text)
        t = deduplicateLines(t)
        t = expandAbbreviations(t)
        t = normalizeNumbers(t)
        t = formatForIntonation(t)
        t = formatForSpeech(t)
        return t.trim()
    }

    // ============ TTS очистка ============

    fun cleanForTts(text: String): String {
        if (NewsService.isChannelHeader(text)) return text

        var t = text

        // 1) Структурный мусор (строки целиком)
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
        t = TTS_RBK_MAX_PATTERN.replace(t, "")
        t = TTS_RBK_APP_PATTERN.replace(t, "")
        t = TTS_MAX_CHANNEL_TAIL_PATTERN.replace(t, "")
        t = TTS_BAZA_FOOTER_PATTERN.replace(t, "")
        t = TTS_SUBSCRIBE_INLINE_PATTERN.replace(t, "")
        t = TTS_AI_ERROR_PATTERN.replace(t, "")
        t = TTS_READ_MORE_PATTERN.replace(t, "")

        // 2) Телефоны
        t = TTS_PHONE_PATTERN.replace(t, "")

        // 3) Символьный мусор: геометрия → селекторы → общий проход So/Sk
        t = GEOMETRIC_SHAPES_PATTERN.replace(t, " ")
        t = VARIATION_SELECTOR_PATTERN.replace(t, "")
        t = TTS_EMOJI_PATTERN.replace(t, " ")

        // 4) Висячие тире в начале строк (после среза маркеров/времени)
        t = LEADING_DASH_PATTERN.replace(t, "")

        // 5) Markdown, кавычки, многоточие
        t = TTS_MARKDOWN_PATTERN.replace(t, "")
        t = TTS_QUOTES_PATTERN.replace(t, "\"")
        t = TTS_ELLIPSIS_PATTERN.replace(t, "…")

        // 6) Нормализация пробелов и переносов
        t = BLANK_LINES_PATTERN.replace(t, "")
        t = TTS_MULTI_SPACE_PATTERN.replace(t, " ")
        t = TTS_MULTI_NEWLINE.replace(t, "\n\n")
        t = SPACE_BEFORE_PUNCT_PATTERN.replace(t, "$1")

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

        t = t.replace(
            Regex("\\$\\s?(\\d[\\d\\s,.]*)\\s*(млн|млрд)\\b", RegexOption.IGNORE_CASE)
        ) { m ->
            val num = m.groupValues[1].trim()
            val scale = if (m.groupValues[2].lowercase() == "млн") "миллионов" else "миллиардов"
            "$num $scale долларов"
        }
        t = t.replace(
            Regex("€(\\d+[\\d\\s,.]*)\\s*(млн|млрд)\\b", RegexOption.IGNORE_CASE)
        ) { m ->
            val num = m.groupValues[1].trim()
            val scale = if (m.groupValues[2].lowercase() == "млн") "миллионов" else "миллиардов"
            "$num $scale евро"
        }
        t = t.replace(
            Regex("£(\\d+[\\d\\s,.]*)\\s*(млн|млрд)\\b", RegexOption.IGNORE_CASE)
        ) { m ->
            val num = m.groupValues[1].trim()
            val scale = if (m.groupValues[2].lowercase() == "млн") "миллионов" else "миллиардов"
            "$num $scale фунтов"
        }
        t = t.replace(Regex("\\$\\s?(\\d+)"), "$1 долларов")
        t = t.replace(Regex("€(\\d+)"), "$1 евро")
        t = t.replace(Regex("£(\\d+)"), "$1 фунтов")

        t = t.replace(Regex("на\\s+(\\d+[,.]?\\d*)\\s?%")) { "на ${it.groupValues[1]} процентов" }
        t = t.replace(Regex("(\\d+[,.]?\\d*)%-й")) { "${it.groupValues[1]}-процентный" }
        t = t.replace(Regex("(\\d+[,.]?\\d*)%-е")) { "${it.groupValues[1]}-процентные" }
        t = t.replace(Regex("\\b(\\d+[,.]?\\d*)\\s?%\\b")) { "${it.groupValues[1]} процентов" }

        // ── Диапазоны чисел: "27–29", "2-7", "100 — 200" → "от 27 до 29" ──
        // Выполняется ПОСЛЕ обработки процентов/валют, но ДО координат.
        // Ограничиваем 1–3 значными числами, чтобы не задеть годы (2024-2025)
        // и телефоны. 4-значные годы остаются на formatForIntonation.
        // Отрицательные lookaround не дают срабатывать внутри 4-значных чисел (2027-2028).
        t = t.replace(Regex("(?<!\\d)\\b(\\d{1,3})\\s*[–—-]\\s*(\\d{1,3})\\b(?!\\d)")) {
            "от ${it.groupValues[1]} до ${it.groupValues[2]}"
        }

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

        t = t.replace(Regex("([+-]?\\d+[,.]?\\d*)\\s?°\\s?[CС]\\b")) { "${it.groupValues[1]} градусов" }
        t = t.replace(Regex("([+-]?\\d+[,.]?\\d*)\\s?°")) { "${it.groupValues[1]} градусов" }

        return t
    }

    fun formatForIntonation(text: String): String {
        if (NewsService.isChannelHeader(text)) return text

        var t = text

        t = t.replace(" в нем", " в нём")
        t = t.replace(Regex("\\s*‼‼‼\\s*"), "Главное... ")

        // СНАЧАЛА — дата С ГОДОМ (более специфичное правило, иначе год оставался голым)
        // Поглощаем хвостовое "года"/"г." если уже есть в источнике → нет задвоения
        t = Regex("\\b(\\d{1,2})\\s+(января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря)\\s+(\\d{4})(?:\\s*(?:года|г\\.))?\\b")
            .replace(t) { match ->
                val day = match.groupValues[1].toIntOrNull() ?: return@replace match.value
                "${numberToOrdinalRu(day)} ${match.groupValues[2]} ${match.groupValues[3]} года"
            }

        // ПОТОМ — дата без года
        t = Regex("\\b(\\d{1,2})\\s+(января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря)\\b")
            .replace(t) { match ->
                val day = match.groupValues[1].toIntOrNull() ?: return@replace match.value
                "${numberToOrdinalRu(day)} ${match.groupValues[2]}"
            }

        t = t.replace(Regex("(\\d{4})/(\\d{4})")) { "${it.groupValues[1]} – ${it.groupValues[2]}" }
        t = t.replace(Regex("(\\d{4})-(\\d{4})")) { "${it.groupValues[1]} – ${it.groupValues[2]}" }

        t = t.replace(Regex("\\bкм/ч\\b", RegexOption.IGNORE_CASE), "километров в час")
        t = t.replace(Regex("\\bкм\\b", RegexOption.IGNORE_CASE), "километров")

        t = t.replace(Regex("\\b(\\d+)\\s?\\+\\s?(\\d+)\\b")) { "${it.groupValues[1]} плюс ${it.groupValues[2]}" }
        t = t.replace(Regex("\\b(\\d+)\\s?\\*\\s?(\\d+)\\b")) { "${it.groupValues[1]} умножить на ${it.groupValues[2]}" }

        t = t.replace(Regex("^[•·∙▪▫◦‣⁃]\\s+", RegexOption.MULTILINE)) { "— " }
        // Страховочный проход по геометрии (на случай прямого вызова без cleanForTts)
        t = GEOMETRIC_SHAPES_PATTERN.replace(t, "")

        t = t.replace(Regex("(?m)^[-•]\\s+"), "— ")
        t = t.replace(Regex(" - "), " — ")
        t = t.replace(Regex("\\.\\.\\."), "…")

        t = t.replace(Regex(";\\s*(?=\\n{2,})"), ". ")

        return t.trim()
    }

    fun expandAbbreviations(text: String): String {
        if (NewsService.isChannelHeader(text)) return text

        var t = text

        // ── Составные единицы (раскрываем ДО одиночных сокращений, иначе "кв."
        //    и "мм рт. ст." разорвутся на части и TTS вставит лишние паузы) ──
        t = t.replace(Regex("\\bмм\\s*рт\\.?\\s*ст\\.?", RegexOption.IGNORE_CASE), "миллиметров ртутного столба")
        t = t.replace(Regex("\\bкв\\.?\\s*м\\b", RegexOption.IGNORE_CASE), "квадратных метров")
        t = t.replace(Regex("\\bм/с\\b", RegexOption.IGNORE_CASE), "метров в секунду")

        val maps = mapOf(
            Regex("\\bг\\.\\b") to "город",
            Regex("\\bобл\\.\\b") to "область",
            Regex("\\bул\\.\\b") to "улица",
            Regex("\\bд\\.\\b(?=\\s?\\d)") to "дом",
            Regex("\\bт\\.д\\.\\b") to "так далее",
            Regex("\\bт\\.п\\.\\b") to "тому подобное",
            Regex("\\bсм\\.\\b") to "смотрите",
            Regex("\\bстр\\.\\b") to "страница",
            Regex("\\bтыс\\.") to "тысяч",
            Regex("\\bчел\\.\\b") to "человек"
        )
        maps.forEach { (regex, replacement) ->
            t = t.replace(regex, replacement)
        }

        // США намеренно НЕ раскрываем: замена на "Соединённые Штаты" ломала падеж
        // ("из Соединённые Штаты"). TTS-движок читает "США" корректно как есть.
        val newsAbbreviations = listOf(
            Regex("\\bРФ\\b")                               to "Россия",
            Regex("\\bЕС\\b(?![а-яёА-ЯЁ])")                to "Евросоюз",
            Regex("\\bООН\\b")                              to "Организация Объединённых Наций",
            Regex("\\bЦБ\\b")                               to "Центробанк",
            Regex("\\bМВД\\b")                              to "министерство внутренних дел",
            Regex("\\bМИД\\b")                              to "министерство иностранных дел",
            Regex("\\bФСБ\\b")                              to "ФСБ",
            Regex("\\bМЧС\\b")                              to "МЧС",
            Regex("\\bВСУ\\b")                              to "вэ-эс-у",
            Regex("\\bБПЛА\\b")                             to "бэ-пэ-эл-а",
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

        val centuryMap = linkedMapOf(
            "XXII"  to "двадцать второго",
            "XXI"   to "двадцать первого",
            "XX"    to "двадцатого",
            "XIX"   to "девятнадцатого",
            "XVIII" to "восемнадцатого",
            "XVII"  to "семнадцатого",
            "XVI"   to "шестнадцатого",
            "XV"    to "пятнадцатого",
            "XIV"   to "четырнадцатого",
            "XIII"  to "тринадцатого",
            "XII"   to "двенадцатого",
            "XI"    to "одиннадцатого",
            "X"     to "десятого",
            "IX"    to "девятого",
            "VIII"  to "восьмого",
            "VII"   to "седьмого",
            "VI"    to "шестого",
            "V"     to "пятого",
            "IV"    to "четвёртого",
            "III"   to "третьего",
            "II"    to "второго",
            "I"     to "первого",
            "ХХІ"   to "двадцать первого",
            "ХХ"    to "двадцатого",
            "ХІХ"   to "девятнадцатого",
            "ХVIII" to "восемнадцатого",
            "ХVII"  to "семнадцатого",
            "ХVI"   to "шестнадцатого",
            "ХV"    to "пятнадцатого",
            "ХIV"   to "четырнадцатого",
            "ХIII"  to "тринадцатого",
            "ХII"   to "двенадцатого",
            "ХI"    to "одиннадцатого",
            "Х"     to "десятого"
        )
        centuryMap.forEach { (roman, ordinal) ->
            t = t.replace(
                Regex("\\b${Regex.escape(roman)}\\s+(век[аеу]?)\\b")
            ) { m -> "$ordinal ${m.groupValues[1]}" }
        }

        return t
    }

    fun formatForSpeech(text: String): String {
        if (NewsService.isChannelHeader(text)) return text

        var t = text

        // IT обрабатываем отдельно: исключаем .it (домены типа cnn.it/...)
        // и IT внутри слов/URL. Остальные аббревиатуры — стандартно.
        t = t.replace(Regex("(?<![.\\w])IT(?![/\\w])")) { "Ай-Ти" }

        val techAbbreviations = mapOf(
            "AI" to "искусственный интеллект",
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

        // «Важно / Внимание / Срочно / Эксклюзив» — усиливаем везде в тексте.
        // «Молния» — только как заголовочное слово (начало строки или после ⚡/времени),
        // чтобы «шаровая молния» и «удар молнии» не превращались в «ВАЖНО. молния...».
        val importantWords = listOf("важно", "внимание", "срочно", "эксклюзив")
        importantWords.forEach { word ->
            t = t.replace(Regex("\\b($word)\\b", RegexOption.IGNORE_CASE)) {
                "ВАЖНО. ${it.groupValues[1].lowercase()}... "
            }
        }
        // «Молния» как заголовок: строка начинается с ⚡, «Молния» или времени + «Молния»
        t = t.replace(
            Regex("(?im)^(⚡+\\s*|\\d{2}:\\d{2}\\s*—?\\s*)(молния)(?=[\\s!.,]|\$)"),
            "$1ВАЖНО. молния... "
        )

        return t.trim()
    }

    fun dropTrivial(texts: List<String>): List<String> {
        var droppedShort = 0
        var droppedTrivial = 0
        var droppedSubscribe = 0
        var droppedSpam = 0
        var droppedTimecode = 0

        val result = texts.filter { text ->
            if (NewsService.isChannelHeader(text)) return@filter true

            val trimmed = text.trim()

            val withoutTimePrefix = TIME_PREFIX_PATTERN.replace(trimmed, "").trim()

            when {
                withoutTimePrefix.length < 8 -> {
                    droppedShort++
                    Logx.d(TAG) { "DROP [<8chars] len=${withoutTimePrefix.length}" }
                    false
                }
                // Анонс видеовыпуска РБК с тайм-кодами ("00:00-04:10 — …") — дропаем целиком
                TIMECODE_ANNOUNCE_PATTERN.containsMatchIn(withoutTimePrefix) -> {
                    droppedTimecode++
                    Logx.d(TAG) { "DROP [timecode_announce] len=${withoutTimePrefix.length}" }
                    false
                }
                TRIVIAL_PATTERN.containsMatchIn(withoutTimePrefix) -> {
                    droppedTrivial++
                    Logx.d(TAG) { "DROP [trivial] len=${withoutTimePrefix.length}" }
                    false
                }
                SUBSCRIBE_CHECK_PATTERN.matches(trimmed) -> {
                    droppedSubscribe++
                    Logx.d(TAG) { "DROP [subscribe_short] len=${trimmed.length}" }
                    false
                }
                SPAM_CHECK_PATTERN.matches(trimmed) -> {
                    droppedSpam++
                    Logx.d(TAG) { "DROP [spam_short] len=${trimmed.length}" }
                    false
                }
                else -> true
            }
        }

        if (texts.size != result.size) {
            Logx.i(TAG) { "dropTrivial: ${texts.size} -> ${result.size} (short=$droppedShort trivial=$droppedTrivial subscribe=$droppedSubscribe spam=$droppedSpam timecode=$droppedTimecode)" }
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

    // Родительный падеж: "восьмого июня", "первого января", "тридцать первого декабря"
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
}
