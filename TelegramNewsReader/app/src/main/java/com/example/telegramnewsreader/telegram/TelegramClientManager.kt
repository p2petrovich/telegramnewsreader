package com.example.telegramnewsreader.telegram

import android.content.Context
import android.util.Log

object TelegramClientManager {

    @Volatile
    private var telegramClient: TelegramClient? = null



    fun clearClient() {
        telegramClient?.close()
        telegramClient = null
    }
    fun getTelegramClient(context: Context): TelegramClient {
        return telegramClient ?: synchronized(this) {
            if (telegramClient == null) {
                Log.d("TelegramClientManager", "=== INIT TRACKING === Creating NEW TelegramClient instance")
            }
            telegramClient ?: TelegramClient(context.applicationContext).also {
                telegramClient = it
            }
        }
    }

}


