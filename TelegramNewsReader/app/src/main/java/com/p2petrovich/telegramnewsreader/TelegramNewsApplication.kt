package com.p2petrovich.telegramnewsreader

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.jakewharton.threetenabp.AndroidThreeTen
import com.p2petrovich.telegramnewsreader.utils.Logx
import com.p2petrovich.telegramnewsreader.utils.PreferenceManager

class TelegramNewsApplication : Application() {
    companion object {
        lateinit var instance: TelegramNewsApplication
            private set

        /**
         * Возвращает ID темы на основе настроек пользователя.
         * DayNight режим позволяет автоматически переключаться между светлым и темным.
         */
        fun getThemeResId(context: Context): Int {
            val savedTheme = PreferenceManager.getColorTheme(context)
            return when (savedTheme) {
                "teal" -> R.style.Theme_TelegramNewsReader_Teal
                "light" -> R.style.Theme_TelegramNewsReader_Light
                "purple" -> R.style.Theme_TelegramNewsReader_Purple
                else -> R.style.Theme_TelegramNewsReader // Базовая DayNight тема
            }
        }
        
        /**
         * Применяет режим ночи (светлая/темная/авто) глобально для приложения.
         */
        fun applyNightMode(context: Context) {
            val savedTheme = PreferenceManager.getColorTheme(context)
            val nightMode = when (savedTheme) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "auto" -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                else -> AppCompatDelegate.MODE_NIGHT_YES // Для Purple и Teal используем темный режим
            }
            AppCompatDelegate.setDefaultNightMode(nightMode)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        applyNightMode(this)
        
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
        } catch (e: Exception) {
            Logx.e("Application", "Failed to cleanup temp files", e)
        }
    }
}
