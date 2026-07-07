package com.p2petrovich.telegramnewsreader.telegram

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.p2petrovich.telegramnewsreader.ApiConfig
import com.p2petrovich.telegramnewsreader.utils.Logx
import com.p2petrovich.telegramnewsreader.utils.SecurityManager
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean // [FIX reset] для watchdog-страховки

object TelegramClientManager {

    @Volatile
    private var telegramClient: TelegramClient? = null

    // [FIX reset] Таймаут страховки: если TDLib не дойдёт до Closed (например,
    // LogOut завис без сети), всё равно завершаем сброс и не оставляем UI висеть.
    private const val LOGOUT_WATCHDOG_MS = 8_000L

    fun logoutAndClearDb(context: Context, onDone: (() -> Unit)? = null) {
        // [FIX reset] Используем applicationContext, чтобы колбэк не держал ссылку
        // на уничтожаемую Activity.
        val appCtx = context.applicationContext
        val c = telegramClient
        if (c == null) {
            deleteTdlibDirs(appCtx)
            Handler(Looper.getMainLooper()).post { onDone?.invoke() }
            return
        }

        // [FIX reset] Гарантируем однократное завершение: либо по Closed, либо по watchdog.
        val finished = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())

        fun finalize() {
            if (!finished.compareAndSet(false, true)) return
            handler.removeCallbacksAndMessages(null)
            telegramClient = null

            // Даем небольшую паузу, чтобы ОС успела освободить дескрипторы файлов
            handler.postDelayed({
                deleteTdlibDirs(appCtx)
                SecurityManager.resetKeyMarker(appCtx)
                onDone?.invoke()
            }, 500)
        }

        // Устанавливаем слушатель на окончательное закрытие клиента
        c.setOnLoggedOutListener {
            Logx.d("TelegramClientManager") { "Client reported Closed. Waiting before deletion..." }
            finalize()
        }

        // [FIX reset] Watchdog: принудительно завершаем сброс, если Closed не наступил.
        handler.postDelayed({
            Logx.w("TelegramClientManager", "Logout watchdog fired — forcing cleanup")
            finalize()
        }, LOGOUT_WATCHDOG_MS)

        // Запускаем процесс логаута. 
        // TDLib пройдет через LogOut -> Close -> Closed (onLoggedOut)
        c.logoutAndReset {
            c.close()
        }
    }

    private fun deleteTdlibDirs(context: Context) {
        val dbDir = ApiConfig.tdlibDatabaseDir(context)
        val filesDir = ApiConfig.tdlibFilesDir(context)
        
        Logx.d("TelegramClientManager") { "Deleting TDLib dirs: ${dbDir.absolutePath} and ${filesDir.absolutePath}" }
        
        try {
            val dbDeleted = dbDir.deleteRecursively()
            Logx.d("TelegramClientManager") { "DB dir deleted: $dbDeleted" }
        } catch (e: Exception) {
            Logx.e("TelegramClientManager", "Failed to delete DB dir", e)
        }
        
        try {
            val filesDeleted = filesDir.deleteRecursively()
            Logx.d("TelegramClientManager") { "Files dir deleted: $filesDeleted" }
        } catch (e: Exception) {
            Logx.e("TelegramClientManager", "Failed to delete files dir", e)
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
