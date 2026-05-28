package com.p2petrovich.telegramnewsreader

object ApiConfig {
    @Suppress("ConstPropertyName")
    val TELEGRAM_API_ID: Int = BuildConfig.TELEGRAM_API_ID
    @Suppress("ConstPropertyName")
    val TELEGRAM_API_HASH: String = BuildConfig.TELEGRAM_API_HASH
    const val DATABASE_DIRECTORY = "tdlib"
    const val FILES_DIRECTORY = "files"

    // --- Edge TTS Configuration ---
    // Эти значения могут меняться со стороны Microsoft. Если Edge TTS перестает работать,
    // в первую очередь нужно обновить TOKEN и версию Chromium.
    const val EDGE_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    const val EDGE_WS_BASE = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
    const val EDGE_CHROMIUM_FULL_VERSION = "143.0.3650.75"
    const val EDGE_CHROMIUM_MAJOR_VERSION = "143"
}