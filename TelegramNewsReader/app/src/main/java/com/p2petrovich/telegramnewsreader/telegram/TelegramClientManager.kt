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

        // Устанавливаем слушатель на окончательное закрытие клиента
        c.setOnLoggedOutListener {
            Log.d("TelegramClientManager", "Client reported Closed. Waiting before deletion...")
            telegramClient = null
            
            // Даем небольшую паузу, чтобы ОС успела освободить дескрипторы файлов
            Handler(Looper.getMainLooper()).postDelayed({
                deleteTdlibDirs(context)
                onDone?.invoke()
            }, 500)
        }

        // Запускаем процесс логаута. 
        // TDLib пройдет через LogOut -> Close -> Closed (onLoggedOut)
        c.logoutAndReset {
            c.close()
        }
    }

    private fun deleteTdlibDirs(context: Context) {
        val dbDir = ApiConfig.tdlibDatabaseDir(context)
        val filesDir = ApiConfig.tdlibFilesDir(context)
        
        Log.d("TelegramClientManager", "Deleting TDLib dirs: ${dbDir.absolutePath} and ${filesDir.absolutePath}")
        
        try {
            val dbDeleted = dbDir.deleteRecursively()
            Log.d("TelegramClientManager", "DB dir deleted: $dbDeleted")
        } catch (e: Exception) {
            Log.e("TelegramClientManager", "Failed to delete DB dir", e)
        }
        
        try {
            val filesDeleted = filesDir.deleteRecursively()
            Log.d("TelegramClientManager", "Files dir deleted: $filesDeleted")
        } catch (e: Exception) {
            Log.e("TelegramClientManager", "Failed to delete files dir", e)
        }
    }

    fun getTelegramClient(context: Context): TelegramClient {
        return telegramClient ?: synchronized(this) {
            telegramClient ?: TelegramClient(context.applicationContext).also {
                telegramClient = it
            }
        }
    }
}
