package com.example.telegramnewsreader.services

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.example.telegramnewsreader.tts.TTSManagerSingleton

class TTSService : Service() {
    
    override fun onCreate() {
        super.onCreate()
        Log.d("TTSService", "🚀 TTSService создан")
        
        // Используем Singleton для получения TTSManager
        val ttsManager = TTSManagerSingleton.getInstance(this)
        Log.d("TTSService", "🔊 TTSManager инициализирован в сервисе")
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.d("TTSService", "🔗 onBind вызван")
        return null
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("TTSService", "▶️ onStartCommand вызван")
        return START_STICKY // Перезапускать сервис при завершении системой
    }
    
    override fun onDestroy() {
        Log.d("TTSService", "🏁 TTSService уничтожается")
        // TTSManager будет очищен через Singleton при необходимости
        super.onDestroy()
    }
}