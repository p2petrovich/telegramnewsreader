package com.p2petrovich.telegramnewsreader

object ApiConfig {
    @Suppress("ConstPropertyName")
    val TELEGRAM_API_ID: Int = BuildConfig.TELEGRAM_API_ID
    @Suppress("ConstPropertyName")
    val TELEGRAM_API_HASH: String = BuildConfig.TELEGRAM_API_HASH
    const val DATABASE_DIRECTORY = "tdlib"
    const val FILES_DIRECTORY = "files"
}