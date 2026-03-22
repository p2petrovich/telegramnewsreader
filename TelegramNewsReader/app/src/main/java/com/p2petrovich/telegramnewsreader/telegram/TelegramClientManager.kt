package com.p2petrovich.telegramnewsreader.telegram

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.p2petrovich.telegramnewsreader.ApiConfig
import java.io.File

object TelegramClientManager {

    @Volatile
    private var telegramClient: TelegramClient? = null

    fun logoutAndClearDb(context: Context, onDone: (() -> Unit)? = null) {
        val c = telegramClient
        if (c == null) {
            deleteTdlibDirs(context)
            Handler(Looper.getMainLooper()).post { onDone?.invoke() }
            return
        }
        c.logOut {
            try { c.close() } catch (_: Exception) {}
            telegramClient = null
            deleteTdlibDirs(context)
            Handler(Looper.getMainLooper()).post { onDone?.invoke() }
        }
    }

    private fun deleteTdlibDirs(context: Context) {
        try { File(context.filesDir, ApiConfig.DATABASE_DIRECTORY).deleteRecursively() } catch (_: Exception) {}
        try { File(context.filesDir, ApiConfig.FILES_DIRECTORY).deleteRecursively() } catch (_: Exception) {}
    }

    fun clearClient() {
        telegramClient?.close()
        telegramClient = null
    }

    fun getTelegramClient(context: Context): TelegramClient {
        return telegramClient ?: synchronized(this) {
            telegramClient ?: TelegramClient(context.applicationContext).also {
                telegramClient = it
            }
        }
    }
}
