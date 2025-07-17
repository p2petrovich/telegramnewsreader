package com.example.telegramnewsreader

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate


class TelegramNewsApplication : Application() {
    companion object {
        lateinit var instance: TelegramNewsApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
		AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES) // Для тёмной
        instance = this
    }

    fun getAppContext(): Context = applicationContext
}
