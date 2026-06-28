package com.p2petrovich.telegramnewsreader

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.jakewharton.threetenabp.AndroidThreeTen
import com.p2petrovich.telegramnewsreader.utils.PreferenceManager

class TelegramNewsApplication : Application() {
    companion object {
        lateinit var instance: TelegramNewsApplication
            private set

        fun getThemeResId(context: Context): Int {
            val savedTheme = PreferenceManager.getColorTheme(context)
            return when (savedTheme) {
                "teal" -> R.style.Theme_TelegramNewsReader_Teal
                "light" -> R.style.Theme_TelegramNewsReader_Light
                else -> R.style.Theme_TelegramNewsReader_Purple
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Set night mode based on saved theme
        val savedTheme = PreferenceManager.getColorTheme(this)
        val nightMode = when (savedTheme) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_YES
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
        instance = this
        AndroidThreeTen.init(this)
        com.p2petrovich.telegramnewsreader.utils.NewsCache.cleanup(this)
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
                        file.name.endsWith("_combined.wav") ||
                        file.name.startsWith("silence_")) {
                        file.delete()
                    }
                }
            }
            // Также вызываем очистку основного кэша новостей
            com.p2petrovich.telegramnewsreader.utils.NewsCache.cleanup(this)
        } catch (_: Exception) {}
    }
}
