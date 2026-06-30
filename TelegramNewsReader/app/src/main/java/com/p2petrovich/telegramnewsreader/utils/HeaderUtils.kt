package com.p2petrovich.telegramnewsreader.utils

import android.content.Context
import com.p2petrovich.telegramnewsreader.R

/**
 * Утилиты для работы с заголовками каналов в ленте новостей.
 * Вынесено отдельно для разрыва круговой зависимости между TextProcessor и NewsService.
 */
object HeaderUtils {
    const val HEADER_MARKER = "\u200B\u200C\u200B"

    fun isChannelHeader(text: String): Boolean = text.contains(HEADER_MARKER)

    fun makeChannelHeader(title: String, context: Context): String =
        "${HEADER_MARKER}${context.getString(R.string.channel_header_format, title)}"
}
