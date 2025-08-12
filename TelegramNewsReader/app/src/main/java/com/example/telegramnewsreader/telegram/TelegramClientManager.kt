package com.example.telegramnewsreader.telegram

import android.content.Context
import android.util.Log
import com.example.telegramnewsreader.ApiConfig
import java.io.File

object TelegramClientManager {

    @Volatile
    private var telegramClient: TelegramClient? = null

    fun logoutAndClearDb(context: Context, onDone: (() -> Unit)? = null) {
        val c = telegramClient
        if (c == null) {
            deleteTdlibDirs(context)
            onDone?.invoke()
            return
        }
        Log.d("TelegramClientManager", "logoutAndClearDb: start")
        c.logOut {
            Log.d("TelegramClientManager", "logoutAndClearDb: onLoggedOut (Closed)")
            try {
                c.close()
            } catch (_: Exception) {}
            telegramClient = null
            deleteTdlibDirs(context)
            onDone?.invoke()
        }
    }

    private fun deleteTdlibDirs(context: Context) {
        try {
            File(context.filesDir, ApiConfig.DATABASE_DIRECTORY).deleteRecursively()
        } catch (_: Exception) {}
        try {
            File(context.filesDir, ApiConfig.FILES_DIRECTORY).deleteRecursively()
        } catch (_: Exception) {}
    }

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