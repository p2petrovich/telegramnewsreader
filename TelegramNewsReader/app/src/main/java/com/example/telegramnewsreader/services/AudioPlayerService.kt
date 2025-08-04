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

        const val EXTRA_FILE_PATHS = "extra.FILE_PATHS"
        const val EXTRA_START_INDEX = "extra.START_INDEX"
        const val EXTRA_TITLE = "extra.TITLE"
        const val EXTRA_CHAPTERS = "extra.CHAPTERS" // long[] таймкоды глав в мс

        private const val CHANNEL_ID = "audio_playback_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private var mediaPlayer: MediaPlayer? = null
    private var playlist: List<String> = emptyList()
    private var currentIndex = 0
    private var title: String = "Новости"

    // Главы внутри одного файла
    private var chapterStartsMs: List<Long> = emptyList()
    private var currentChapter = 0
    private var preparedButNotPlaying = false // подготовлено, но не автозапускать

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand action=${intent?.action} startId=$startId flags=$flags")
        try {
            when (intent?.action) {
                ACTION_SET_PLAYLIST -> {
                    val paths = intent.getStringArrayListExtra(EXTRA_FILE_PATHS) ?: arrayListOf()
                    val start = intent.getIntExtra(EXTRA_START_INDEX, 0)
                    title = intent.getStringExtra(EXTRA_TITLE) ?: "Новости"
                    val chapters = intent.getLongArrayExtra(EXTRA_CHAPTERS)?.toList() ?: emptyList()
                    Log.d(TAG, "ACTION_SET_PLAYLIST title='$title' size=${paths.size} start=$start chapters=${chapters.size}")
                    setPlaylist(paths, start, chapters)
                }
                ACTION_PLAY -> {
                    Log.d(TAG, "ACTION_PLAY")
                    play()
                }
                ACTION_PAUSE -> {
                    Log.d(TAG, "ACTION_PAUSE")
                    pause()
                }
                ACTION_STOP -> {
                    Log.d(TAG, "ACTION_STOP")
                    stopServiceSafely()
                    // ВАЖНО: не строим уведомление и не стартуем foreground после STOP
                    return START_NOT_STICKY
                }
                ACTION_NEXT -> {
                    Log.d(TAG, "ACTION_NEXT")
                    // Если плейлиста нет — не поднимаем сервис зря
                    if (playlist.isEmpty()) return START_NOT_STICKY
                    playNext()
                }
                else -> {
                    Log.d(TAG, "No action, updating notification only")
                }
            }

            // Переводим в foreground только когда сервис активен
            val n = buildNotification()
            startForeground(NOTIFICATION_ID, n)
        } catch (e: Exception) {
            Log.e(TAG, "onStartCommand exception", e)
        }
        // Сервис не должен перезапускаться автоматически
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        super.onDestroy()
        releasePlayer()
        // Подстраховка: снимаем foreground (если ещё висит)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) { }
    }

    private fun setPlaylist(paths: List<String>, startIndex: Int, chapters: List<Long>) {
        playlist = paths
        currentIndex = startIndex.coerceIn(0, (playlist.size - 1).coerceAtLeast(0))
        chapterStartsMs = if (paths.size == 1) chapters.sorted() else emptyList()
        currentChapter = 0
        preparedButNotPlaying = true // не автозапускаем
        Log.d(TAG, "Playlist set: size=${playlist.size}, startIndex=$currentIndex, chapters=${chapterStartsMs.size}")

        if (playlist.isNotEmpty()) {
            prepareCurrentSilently()
        } else {
            Log.w(TAG, "Playlist is empty -> pause()")
            pause()
        }
    }

    private fun hasChapters(): Boolean {
        return playlist.size == 1 && chapterStartsMs.size > 1
    }

    private fun prepareCurrentSilently() {
        if (playlist.isEmpty()) {
            Log.w(TAG, "prepareCurrentSilently: playlist is empty")
            return
        }
        val path = playlist[currentIndex]
        val f = File(path)
        Log.d(TAG, "prepareCurrentSilently: index=$currentIndex path=$path exists=${f.exists()} len=${f.length()}")

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
                    Log.d(TAG, "onPrepared (silent) -> NOT starting (wait for ACTION_PLAY)")
                    updateNotification()
                }
                setOnCompletionListener {
                    Log.d(TAG, "onCompletion -> next or stop")
                    onTrackCompletion()
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "onError what=$what extra=$extra (will try next/stop)")
                    playNext()
                    true
                }

                Log.d(TAG, "prepareAsync() (silent)")
                prepareAsync()
            } catch (e: Exception) {
                Log.e(TAG, "prepareCurrentSilently failed", e)
                playNext()
            }
        }

        updateNotification()
    }

    private fun prepareAndPlayCurrent(startFromMs: Int? = null) {
        if (playlist.isEmpty()) {
            Log.w(TAG, "prepareAndPlayCurrent: playlist is empty")
            return
        }

        val path = playlist[currentIndex]
        val f = File(path)
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
                    Log.d(TAG, "onPrepared -> start() (with optional seek)")
                    try {
                        startFromMs?.let { ms ->
                            seekTo(ms)
                        }
                        it.start()
                    } catch (e: Exception) {
                        Log.e(TAG, "start() failed after prepare", e)
                    }
                    updateNotification()
                }
                setOnCompletionListener {
                    Log.d(TAG, "onCompletion -> next or stop")
                    onTrackCompletion()
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "onError what=$what extra=$extra (will try next/stop)")
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

    private fun onTrackCompletion() {
        if (hasChapters() && currentChapter < chapterStartsMs.lastIndex) {
            currentChapter += 1
            val nextMs = chapterStartsMs[currentChapter].toInt()
            Log.d(TAG, "Completion: move to next chapter=$currentChapter ms=$nextMs")
            mediaPlayer?.seekTo(nextMs)
            try {
                mediaPlayer?.start()
            } catch (_: Exception) {}
            updateNotification()
        } else {
            if (currentIndex < playlist.lastIndex) {
                currentIndex += 1
                currentChapter = 0
                prepareAndPlayCurrent()
            } else {
                Log.d(TAG, "Completion: end -> stopServiceSafely()")
                stopServiceSafely()
            }
        }
    }

    private fun play() {
        Log.d(TAG, "play(): player=${mediaPlayer != null}, isPlaying=${mediaPlayer?.isPlaying}, preparedButNotPlaying=$preparedButNotPlaying chapterMode=${hasChapters()} chapter=$currentChapter/${chapterStartsMs.size}")

        if (playlist.isEmpty()) {
            Log.w(TAG, "play(): playlist is empty")
            return
        }

        if (mediaPlayer == null) {
            // Создаём и запускаем с учётом текущей главы
            preparedButNotPlaying = false
            val startMs = if (hasChapters() && currentChapter in chapterStartsMs.indices) {
                chapterStartsMs[currentChapter].toInt()
            } else null
            prepareAndPlayCurrent(startMs)
            return
        }

        try {
            // Если было молчаливое prepare после SET_PLAYLIST, стартуем с начала текущей главы
            if (preparedButNotPlaying && hasChapters() && currentChapter in chapterStartsMs.indices) {
                val toMs = chapterStartsMs[currentChapter].toInt()
                Log.d(TAG, "play(): preparedButNotPlaying -> seekTo currentChapter start $toMs ms, then start()")
                mediaPlayer?.seekTo(toMs)
            }
            preparedButNotPlaying = false
            mediaPlayer?.start()
        } catch (e: Exception) {
            Log.e(TAG, "play(): start() failed", e)
        }
        updateNotification()
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
            // Мы отличаем паузу пользователя от "ещё не запускали"
            preparedButNotPlaying = false
            updateNotification()
        }
    }

    private fun playNext() {
        Log.d(TAG, "playNext() currentIndex=$currentIndex size=${playlist.size} chapterMode=${hasChapters()} chapter=$currentChapter/${chapterStartsMs.size}")

        if (playlist.isEmpty()) return

        if (hasChapters()) {
            if (chapterStartsMs.isEmpty()) return

            val wasPlaying = mediaPlayer?.isPlaying == true
            val wasPreparedNoStart = preparedButNotPlaying

            if (currentChapter < chapterStartsMs.lastIndex) {
                currentChapter += 1
            } else {
                Log.d(TAG, "Next on last chapter -> stay at last chapter")
            }

            val toMs = chapterStartsMs[currentChapter].toInt()
            Log.d(TAG, "Next chapter -> $currentChapter at $toMs ms (wasPlaying=$wasPlaying, wasPreparedNoStart=$wasPreparedNoStart)")

            try {
                if (mediaPlayer == null) {
                    preparedButNotPlaying = false
                    prepareAndPlayCurrent(toMs)
                    return
                }

                mediaPlayer?.seekTo(toMs)

                // Стартуем, если до этого играло, либо это первый старт после "тихой" подготовки
                if (wasPlaying || wasPreparedNoStart) {
                    mediaPlayer?.start()
                }
                preparedButNotPlaying = false
            } catch (_: Exception) { }
            updateNotification()
            return
        }

        // Обычный плейлист без глав
        if (currentIndex < playlist.lastIndex) {
            currentIndex += 1
            Log.d(TAG, "playNext(): newIndex=$currentIndex")
            preparedButNotPlaying = false
            prepareAndPlayCurrent()
        } else {
            Log.d(TAG, "playNext(): end of playlist -> stopServiceSafely()")
            stopServiceSafely()
        }
    }

    private fun stopServiceSafely() {
        Log.d(TAG, "stopServiceSafely()")
        releasePlayer()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Полностью убрать уведомление
                stopForeground(Service.STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "stopForeground failed", e)
        }
        // Подстраховка на некоторых прошивках:
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(NOTIFICATION_ID)
        } catch (_: Exception) { }
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
        val chapterText = if (hasChapters()) " • Глава ${currentChapter + 1}/${chapterStartsMs.size}" else ""
        Log.d(TAG, "buildNotification(): isPlaying=$isPlaying pos=$positionText chapter=$chapterText title=$title")

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
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
            .setContentTitle("$title — $positionText$chapterText")
            .setContentText(if (isPlaying) "Воспроизведение" else "Пауза / Ожидание старта")
            .setContentIntent(openIntent)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
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