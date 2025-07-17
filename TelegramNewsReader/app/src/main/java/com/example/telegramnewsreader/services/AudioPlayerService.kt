package com.example.telegramnewsreader.services

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.media.MediaPlayer
import androidx.core.app.NotificationCompat

class AudioPlayerService : Service() {
    private var mediaPlayer: MediaPlayer? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Логика воспроизведения с Notification
        val notification = NotificationCompat.Builder(this, "channel")
            .setContentTitle("Playing Audio")
            .build()
        startForeground(1, notification)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
