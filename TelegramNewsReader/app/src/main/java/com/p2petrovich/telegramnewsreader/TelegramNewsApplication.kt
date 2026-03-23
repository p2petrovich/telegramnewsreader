package com.p2petrovich.telegramnewsreader

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.jakewharton.threetenabp.AndroidThreeTen

class TelegramNewsApplication : Application() {
    companion object {
        lateinit var instance: TelegramNewsApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        instance = this
        AndroidThreeTen.init(this)
        cleanupOldTempFiles()
    }

    fun getAppContext(): Context = applicationContext

    private fun cleanupOldTempFiles() {
        try {
            val now = System.currentTimeMillis()
            val maxAge = 24 * 60 * 60 * 1000L

            cacheDir.listFiles()?.forEach { file ->
                if (file.isFile && (now - file.lastModified()) > maxAge) {
                    if (file.name.startsWith("tts_") ||
                        file.name.endsWith("_combined.wav")) {
                        file.delete()
                    }
                }
            }
        } catch (_: Exception) {}
    }
}
