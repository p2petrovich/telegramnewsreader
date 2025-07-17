package com.example.telegramnewsreader.services

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.example.telegramnewsreader.telegram.TelegramClient

class TelegramService : Service() {
    private lateinit var client: TelegramClient

    override fun onCreate() {
        super.onCreate()
        client = TelegramClient(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
