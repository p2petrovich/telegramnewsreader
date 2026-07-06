package com.p2petrovich.telegramnewsreader.utils

import com.p2petrovich.telegramnewsreader.BuildConfig

/**
 * Обертка над Log для предотвращения утечек данных и лишних вычислений строк в релизе.
 * Управляется централизованно через DebugConfig.
 */
object Logx {
    /**
     * Debug лог. Выполняется только если BuildConfig.DEBUG == true и DebugConfig.ENABLE_DEBUG_LOGS == true.
     */
    inline fun d(tag: String, msg: () -> String) {
        if (BuildConfig.DEBUG && DebugConfig.ENABLE_DEBUG_LOGS) {
            android.util.Log.d(tag, msg())
        }
    }

    /**
     * Info лог. Выполняется только если BuildConfig.DEBUG == true и DebugConfig.ENABLE_DEBUG_LOGS == true.
     */
    inline fun i(tag: String, msg: () -> String) {
        if (BuildConfig.DEBUG && DebugConfig.ENABLE_DEBUG_LOGS) {
            android.util.Log.i(tag, msg())
        }
    }

    /**
     * Verbose лог. Выполняется только если BuildConfig.DEBUG == true и DebugConfig.ENABLE_DEBUG_LOGS == true.
     */
    inline fun v(tag: String, msg: () -> String) {
        if (BuildConfig.DEBUG && DebugConfig.ENABLE_DEBUG_LOGS) {
            android.util.Log.v(tag, msg())
        }
    }

    /**
     * Warning лог. Выполняется если DebugConfig.ENABLE_WARN_LOGS == true.
     */
    fun w(tag: String, msg: String) {
        if (DebugConfig.ENABLE_WARN_LOGS) {
            android.util.Log.w(tag, msg)
        }
    }

    /**
     * Error лог. Выполняется если DebugConfig.ENABLE_ERROR_LOGS == true.
     */
    fun e(tag: String, msg: String, t: Throwable? = null) {
        if (DebugConfig.ENABLE_ERROR_LOGS) {
            android.util.Log.e(tag, msg, t)
        }
    }

    /**
     * Маскирует чувствительные данные (API ключи, токены).
     * Оставляет видимыми только первые 4 и последние 4 символа.
     */
    fun mask(secret: String?): String {
        if (secret.isNullOrBlank()) return "empty"
        if (secret.length <= 8) return "****"
        return "${secret.take(4)}...${secret.takeLast(4)}"
    }
}
