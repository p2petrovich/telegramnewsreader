package com.p2petrovich.telegramnewsreader.utils

/**
 * Централизованное управление отладочными логами.
 * Позволяет быстро включать детализацию для разных модулей.
 */
object DebugConfig {
    /** Логирование этапов обработки новостей (RAW, FILTER, DEDUP и т.д.) */
    const val LOG_PIPELINE_STAGES = true

    /** Детальные логи запросов в Telegram (пагинация, чаты) */
    const val LOG_TG_HISTORY = true

    /** Логи дедупликатора (попадания в базу, отсев) */
    const val LOG_DEDUP_DETAILS = true

    /** Логи событий плеера и пометки новостей как прочитанных */
    const val LOG_PLAYER_EVENTS = true
}
