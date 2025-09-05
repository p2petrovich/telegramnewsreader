package com.p2petrovich.telegramnewsreader

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.jakewharton.threetenabp.AndroidThreeTen  // ✅ Добавлен импорт

class TelegramNewsApplication : Application() {
    companion object {
        lateinit var instance: TelegramNewsApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES) // Для тёмной
        instance = this

        // ✅ Инициализация ThreeTenABP
        AndroidThreeTen.init(this)
    }

    fun getAppContext(): Context = applicationContext
}