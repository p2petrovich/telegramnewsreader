package com.example.telegramnewsreader.services

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.example.telegramnewsreader.tts.TTSManager

class TTSService : Service() {
    private lateinit var ttsManager: TTSManager

    override fun onCreate() {
        super.onCreate()
        ttsManager = TTSManager(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
