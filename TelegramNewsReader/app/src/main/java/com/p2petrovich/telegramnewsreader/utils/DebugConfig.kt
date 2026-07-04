package com.p2petrovich.telegramnewsreader.utils

/**
 * Централизованное управление отладочными логами.
 * Позволяет быстро включать детализацию для разных модулей.
 */
object DebugConfig {
    /** Глобальный выключатель для всех DEBUG/INFO/VERBOSE логов */
    const val ENABLE_DEBUG_LOGS = true

    /** Глобальный выключатель для WARNING логов */
    const val ENABLE_WARN_LOGS = true

    /** Глобальный выключатель для ERROR логов */
    const val ENABLE_ERROR_LOGS = true

    /** Логирование этапов обработки новостей (RAW, FILTER, DEDUP и т.д.) */
    const val LOG_PIPELINE_STAGES = false

    /** Детальные логи запросов в Telegram (пагинация, чаты) */
    const val LOG_TG_HISTORY = false

    /** Логи дедупликатора (попадания в базу, отсев) */
    const val LOG_DEDUP_DETAILS = false

    /** Логи событий плеера и пометки новостей как прочитанных */
    const val LOG_PLAYER_EVENTS = false
}
