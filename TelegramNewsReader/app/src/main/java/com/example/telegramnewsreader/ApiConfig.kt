package com.example.telegramnewsreader

import com.example.telegramnewsreader.BuildConfig.TELEGRAM_API_ID
import com.example.telegramnewsreader.BuildConfig.TELEGRAM_API_HASH

object ApiConfig {
    val API_ID: Int = TELEGRAM_API_ID
    val API_HASH: String = TELEGRAM_API_HASH
    const val DATABASE_DIRECTORY = "tdlib"
    const val FILES_DIRECTORY = "files"
}