package com.p2petrovich.telegramnewsreader.utils

import com.p2petrovich.telegramnewsreader.BuildConfig

/**
 * Обертка над Log для предотвращения утечек данных и лишних вычислений строк в релизе.
 */
object Logx {
    /**
     * Debug лог. Выполняется (включая лямбду) только если BuildConfig.DEBUG == true.
     */
    inline fun d(tag: String, msg: () -> String) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d(tag, msg())
        }
    }

    /**
     * Info лог. Выполняется (включая лямбду) только если BuildConfig.DEBUG == true.
     */
    inline fun i(tag: String, msg: () -> String) {
        if (BuildConfig.DEBUG) {
            android.util.Log.i(tag, msg())
        }
    }

    /**
     * Verbose лог. Выполняется (включая лямбду) только если BuildConfig.DEBUG == true.
     */
    inline fun v(tag: String, msg: () -> String) {
        if (BuildConfig.DEBUG) {
            android.util.Log.v(tag, msg())
        }
    }

    fun w(tag: String, msg: String) {
        android.util.Log.w(tag, msg)
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        android.util.Log.e(tag, msg, t)
    }
}
