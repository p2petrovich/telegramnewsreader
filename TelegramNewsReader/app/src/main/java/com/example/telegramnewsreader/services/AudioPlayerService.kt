package com.example.telegramnewsreader.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.telegramnewsreader.activities.MainActivity
import java.io.File
import java.io.FileInputStream

class AudioPlayerService : Service() {

    companion object {
        private const val TAG = "AudioPlayerService"

        const val ACTION_SET_PLAYLIST = "player.SET_PLAYLIST"
        const val ACTION_PLAY = "player.PLAY"
        const val ACTION_PAUSE = "player.PAUSE"
        const val ACTION_STOP = "player.STOP"
        const val ACTION_NEXT = "player.NEXT"
        const val ACTION_PREV = "player.PREV"

        const val EXTRA_FILE_PATHS = "extra.FILE_PATHS"
        const val EXTRA_START_INDEX = "extra.START_INDEX"
        const val EXTRA_TITLE = "extra.TITLE"

        private const val CHANNEL_ID = "audio_playback_channel"
        private const val NOTIFICATION_ID = 1001

        // Persist
        private const val PREFS = "audio_state"
        private const val PREF_PLAYLIST = "playlist" // StringSet
        private const val PREF_INDEX = "index"
        private const val PREF_TITLE = "title"
    }

    private var mediaPlayer: MediaPlayer? = null
    private var playlist: MutableList<String> = mutableListOf()
    private var currentIndex = 0
    private var title: String = "Новости"

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        createChannel()
        restoreStateIfPossible()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand action=$action startId=$startId flags=$flags")
        try {
            when (action) {
                ACTION_SET_PLAYLIST -> {
                    val paths = intent.getStringArrayListExtra(EXTRA_FILE_PATHS) ?: arrayListOf()
                    val start = intent.getIntExtra(EXTRA_START_INDEX, 0)
                    title = intent.getStringExtra(EXTRA_TITLE) ?: "Новости"
                    Log.d(TAG, "ACTION_SET_PLAYLIST title='$title' size=${paths.size} start=$start")
                    setPlaylist(paths, start)
                }
                ACTION_PLAY -> {
                    ensureStateOrRestore()
                    Log.d(TAG, "ACTION_PLAY")
                    play()
                }
                ACTION_PAUSE -> {
                    ensureStateOrRestore()
                    Log.d(TAG, "ACTION_PAUSE")
                    pause()
                }
                ACTION_STOP -> {
                    Log.d(TAG, "ACTION_STOP")
                    stopServiceSafely()
                    // Не создавать foreground при стопе
                    return START_NOT_STICKY
                }
                ACTION_NEXT -> {
                    ensureStateOrRestore()
                    Log.d(TAG, "ACTION_NEXT")
                    playNext()
                }
                ACTION_PREV -> {
                    ensureStateOrRestore()
                    Log.d(TAG, "ACTION_PREV")
                    playPrev()
                }
                else -> {
                    // Поднят без экшена — просто обновим уведомление при наличии состояния
                    Log.d(TAG, "No action")
                }
            }

            // Создаём/обновляем foreground, если это не STOP
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            Log.e(TAG, "onStartCommand exception", e)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        super.onDestroy()
        releasePlayer()
    }

    // ====================== State persist/restore ======================

    private fun persistState() {
        try {
            val sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            sp.edit()
                .putStringSet(PREF_PLAYLIST, playlist.toSet())
                .putInt(PREF_INDEX, currentIndex)
                .putString(PREF_TITLE, title)
                .apply()
            Log.d(TAG, "persistState: size=${playlist.size} index=$currentIndex title=$title")
        } catch (e: Exception) {
            Log.w(TAG, "persistState failed", e)
        }
    }

    private fun restoreStateIfPossible() {
        try {
            val sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val saved = sp.getStringSet(PREF_PLAYLIST, emptySet())?.toList().orEmpty()
            val idx = sp.getInt(PREF_INDEX, 0)
            val t = sp.getString(PREF_TITLE, "Новости") ?: "Новости"

            // Фильтруем несуществующие файлы
            val existing = saved.filter { File(it).exists() }
            if (existing.isNotEmpty()) {
                playlist.clear()
                playlist.addAll(existing)
                currentIndex = idx.coerceIn(0, (playlist.size - 1).coerceAtLeast(0))
                title = t
                Log.d(TAG, "restoreState: size=${playlist.size} index=$currentIndex title=$title")
            } else {
                Log.d(TAG, "restoreState: no valid saved playlist")
            }
        } catch (e: Exception) {
            Log.w(TAG, "restoreState failed", e)
        }
    }

    private fun ensureStateOrRestore() {
        if (playlist.isEmpty()) {
            Log.d(TAG, "ensureStateOrRestore: empty in-memory -> try restore")
            restoreStateIfPossible()
            if (playlist.isEmpty()) {
                Log.w(TAG, "ensureStateOrRestore: no playlist to operate on")
            }
        }
    }

    // ====================== Core control ======================

    private fun setPlaylist(paths: List<String>, startIndex: Int) {
        playlist.clear()
        // Фильтруем несуществующие пути
        val filtered = paths.filter { File(it).exists() }
        playlist.addAll(filtered)
        currentIndex = startIndex.coerceIn(0, (playlist.size - 1).coerceAtLeast(0))
        Log.d(TAG, "Playlist set: size=${playlist.size}, startIndex=$currentIndex")

        persistState()

        if (playlist.isNotEmpty()) {
            prepareAndPlayCurrent()
        } else {
            Log.w(TAG, "Playlist is empty -> pause()")
            pause()
        }
    }

    private fun prepareAndPlayCurrent() {
        if (playlist.isEmpty()) {
            Log.w(TAG, "prepareAndPlayCurrent: playlist is empty")
            return
        }

        val path = playlist[currentIndex]
        val f = File(path)
        if (!f.exists() || f.length() == 0L) {
            Log.w(TAG, "prepareAndPlayCurrent: file missing or empty, skip to next. path=$path")
            playNext()
            return
        }

        Log.d(TAG, "prepareAndPlayCurrent: index=$currentIndex path=$path exists=${f.exists()} len=${f.length()}")
        releasePlayer()

        mediaPlayer = MediaPlayer().apply {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setAudioStreamType(AudioManager.STREAM_MUSIC)
                }

                try {
                    Log.d(TAG, "setDataSource(path)")
                    setDataSource(path)
                } catch (e: Exception) {
                    Log.w(TAG, "setDataSource(path) failed, try FileDescriptor", e)
                    val fis = FileInputStream(f)
                    setDataSource(fis.fd)
                    try { fis.close() } catch (_: Exception) {}
                }

                setOnPreparedListener {
                    Log.d(TAG, "onPrepared -> start()")
                    it.start()
                    updateNotification()
                }
                setOnCompletionListener {
                    Log.d(TAG, "onCompletion -> next")
                    playNext()
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "onError what=$what extra=$extra (will try next)")
                    playNext()
                    true
                }

                Log.d(TAG, "prepareAsync()")
                prepareAsync()
            } catch (e: Exception) {
                Log.e(TAG, "prepareAndPlayCurrent failed", e)
                playNext()
            }
        }

        updateNotification()
    }

    private fun play() {
        if (playlist.isEmpty()) {
            Log.w(TAG, "play(): playlist empty -> nothing to play")
            return
        }

        if (mediaPlayer == null) {
            Log.d(TAG, "play(): player=null -> prepareAndPlayCurrent()")
            prepareAndPlayCurrent()
        } else {
            val wasPlaying = mediaPlayer?.isPlaying == true
            Log.d(TAG, "play(): calling start(), wasPlaying=$wasPlaying")
            try {
                mediaPlayer?.start()
            } catch (e: Exception) {
                Log.e(TAG, "play(): start() failed", e)
                // Попробуем пересоздать
                prepareAndPlayCurrent()
            }
            updateNotification()
        }
    }

    private fun pause() {
        val playing = mediaPlayer?.isPlaying == true
        Log.d(TAG, "pause(): isPlaying=$playing")
        if (playing) {
            try {
                mediaPlayer?.pause()
            } catch (e: Exception) {
                Log.e(TAG, "pause(): pause() failed", e)
            }
        }
        updateNotification()
        persistState()
    }

    private fun playNext() {
        Log.d(TAG, "playNext() currentIndex=$currentIndex size=${playlist.size}")
        if (playlist.isEmpty()) {
            updateNotification()
            return
        }
        if (currentIndex < playlist.lastIndex) {
            currentIndex += 1
            Log.d(TAG, "playNext(): newIndex=$currentIndex")
            persistState()
            prepareAndPlayCurrent()
        } else {
            Log.d(TAG, "playNext(): end of playlist -> pause and keep service")
            // Не останавливаем сервис, чтобы кнопки продолжали работать
            pause()
        }
    }

    private fun playPrev() {
        Log.d(TAG, "playPrev() currentIndex=$currentIndex")
        if (playlist.isEmpty()) {
            updateNotification()
            return
        }
        if (currentIndex > 0) {
            currentIndex -= 1
            Log.d(TAG, "playPrev(): newIndex=$currentIndex")
            persistState()
            prepareAndPlayCurrent()
        } else {
            Log.d(TAG, "playPrev(): at start -> restart current from 0")
            mediaPlayer?.let {
                try {
                    it.seekTo(0)
                    it.start()
                } catch (_: Exception) {
                    prepareAndPlayCurrent()
                }
            } ?: run {
                prepareAndPlayCurrent()
            }
            updateNotification()
        }
    }

    private fun stopServiceSafely() {
        Log.d(TAG, "stopServiceSafely()")
        releasePlayer()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_DETACH)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "stopForeground failed", e)
        }
        stopSelf()
    }

    private fun releasePlayer() {
        Log.d(TAG, "releasePlayer()")
        try {
            mediaPlayer?.stop()
        } catch (_: Exception) { }
        try {
            mediaPlayer?.release()
        } catch (_: Exception) { }
        mediaPlayer = null
    }

    private fun createChannel() {
        Log.d(TAG, "createChannel()")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(CHANNEL_ID, "Аудио", NotificationManager.IMPORTANCE_LOW)
                mgr.createNotificationChannel(ch)
                Log.d(TAG, "Notification channel created")
            } else {
                Log.d(TAG, "Notification channel exists")
            }
        }
    }

    private fun buildNotification(): Notification {
        val isPlaying = mediaPlayer?.isPlaying == true
        val total = playlist.size
        val positionText = if (total > 0) "${currentIndex + 1}/$total" else "0/0"
        Log.d(TAG, "buildNotification(): isPlaying=$isPlaying pos=$positionText title=$title")

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or pendingFlag()
        )

        val prevIntent = PendingIntent.getService(
            this, 1,
            Intent(this, AudioPlayerService::class.java).setAction(ACTION_PREV),
            PendingIntent.FLAG_UPDATE_CURRENT or pendingFlag()
        )
        val playPauseAction = if (isPlaying) ACTION_PAUSE else ACTION_PLAY
        val playPauseIntent = PendingIntent.getService(
            this, 2,
            Intent(this, AudioPlayerService::class.java).setAction(playPauseAction),
            PendingIntent.FLAG_UPDATE_CURRENT or pendingFlag()
        )
        val nextIntent = PendingIntent.getService(
            this, 3,
            Intent(this, AudioPlayerService::class.java).setAction(ACTION_NEXT),
            PendingIntent.FLAG_UPDATE_CURRENT or pendingFlag()
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .setContentTitle("$title — $positionText")
            .setContentText("Управление воспроизведением")
            .setContentIntent(openIntent)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_previous, "Назад", prevIntent)
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "Пауза" else "Пуск",
                playPauseIntent
            )
            .addAction(android.R.drawable.ic_media_next, "Далее", nextIntent)

        return builder.build()
    }

    private fun updateNotification() {
        Log.d(TAG, "updateNotification()")
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun pendingFlag(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
    }
}