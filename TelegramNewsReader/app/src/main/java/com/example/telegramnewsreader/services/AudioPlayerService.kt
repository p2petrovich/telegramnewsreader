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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.telegramnewsreader.R
import com.example.telegramnewsreader.activities.MainActivity
import java.io.File
import java.io.FileInputStream
import com.example.telegramnewsreader.utils.TTSDebugTracker

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
        const val EXTRA_CHAPTERS = "extra.CHAPTERS"

        // Новое: прогресс для UI
        const val ACTION_PROGRESS = "com.example.telegramnewsreader.services.AudioPlayerService.PROGRESS"
        const val EXTRA_CURRENT_ITEM = "extra_current_item"
        const val EXTRA_TOTAL_ITEMS = "extra_total_items"
        const val EXTRA_IS_PLAYING = "extra_is_playing"

        private const val CHANNEL_ID = "audio_playback_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private var mediaPlayer: MediaPlayer? = null
    private var playlist: List<String> = emptyList()
    private var currentIndex = 0
    private var title: String = "Новости"

    private var chapterStartsMs: List<Long> = emptyList()
    private var currentChapter = 0
    private var preparedButNotPlaying = false
    private var pendingSeekStart = false
    private var totalNewsCount = 0 // Новое поле для правильного подсчета новостей

    // Новое: таймер прогресса
    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            try {
                val (cur, total) = computeProgress()
                val isPlaying = mediaPlayer?.isPlaying == true
                sendProgress(cur, total, isPlaying)
            } catch (_: Exception) { }
            progressHandler.postDelayed(this, 500)
        }
    }

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
                    TTSDebugTracker.trackChannelSwitch("SET_PLAYLIST title='$title' size=${paths.size} start=$start chapters=${chapters.size}")
                    setPlaylist(paths, start, chapters)
                }
                ACTION_PLAY -> {
                    Log.d(TAG, "ACTION_PLAY")
                    TTSDebugTracker.trackChannelSwitch("ACTION_PLAY")
                    play()
                }
                ACTION_PAUSE -> {
                    Log.d(TAG, "ACTION_PAUSE")
                    TTSDebugTracker.trackChannelSwitch("ACTION_PAUSE")
                    pause()
                }
                ACTION_STOP -> {
                    Log.d(TAG, "ACTION_STOP")
                    TTSDebugTracker.trackChannelSwitch("ACTION_STOP")
                    stopServiceSafely()
                    return START_NOT_STICKY
                }
                ACTION_NEXT -> {
                    Log.d(TAG, "ACTION_NEXT")
                    TTSDebugTracker.trackChannelSwitch("ACTION_NEXT")
                    if (playlist.isEmpty()) return START_NOT_STICKY
                    playNext()
                }
                else -> {
                    Log.d(TAG, "No action, updating notification only")
                }
            }

            val n = buildNotification()
            startForeground(NOTIFICATION_ID, n)
        } catch (e: Exception) {
            Log.e(TAG, "onStartCommand exception", e)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        super.onDestroy()
        stopProgressUpdates()
        releasePlayer()
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

        // ИСПРАВЛЕНО: Не добавляем 0L дважды
        chapterStartsMs = if (paths.size == 1) {
            // chapters уже содержит 0L как первый элемент (от TTSManager)
            val normalized = chapters.sorted().distinct()
            Log.d(TAG, "Chapters normalized: raw=${chapters.joinToString()} -> ${normalized.joinToString()}")
            TTSDebugTracker.trackChannelSwitch("CHAPTERS NORMALIZED raw=${chapters.size} -> unique=${normalized.size}")
            normalized
        } else emptyList()

        // НОВОЕ: Сохраняем правильное количество новостей
        totalNewsCount = chapterStartsMs.size

        currentChapter = 0
        preparedButNotPlaying = true
        pendingSeekStart = false
        Log.d(TAG, "Playlist set: size=${playlist.size}, startIndex=$currentIndex, chapters=${chapterStartsMs.size}")
        TTSDebugTracker.trackChannelSwitch("PLAYLIST SET size=${playlist.size} startIndex=$currentIndex chapters=${chapterStartsMs.size}")

        // Отправим стартовый прогресс
        val (cur, total) = computeProgress()
        sendProgress(cur, total, false)

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

    private fun attachCommonListeners(mp: MediaPlayer, startFromMs: Int? = null, autoStart: Boolean) {
        mp.setOnPreparedListener {
            Log.d(TAG, "onPrepared -> ${if (autoStart) "start()" else "silent"} (with optional seek)")
            try {
                startFromMs?.let { ms -> mp.seekTo(ms) }
                if (autoStart) mp.start()
            } catch (e: Exception) {
                Log.e(TAG, "start() failed after prepare", e)
            }
            TTSDebugTracker.trackChannelSwitch("PREPARED index=$currentIndex duration=${mp.duration}ms autoStart=$autoStart seekTo=${startFromMs ?: -1}")
            updateNotification()
            if (autoStart) {
                startProgressUpdates()
            } else {
                val (cur, total) = computeProgress()
                sendProgress(cur, total, false)
            }
        }
        mp.setOnCompletionListener {
            Log.d(TAG, "onCompletion -> next or stop")
            TTSDebugTracker.trackChannelSwitch("COMPLETE index=$currentIndex chapterMode=${hasChapters()} currentChapter=$currentChapter")
            onTrackCompletion()
        }
        mp.setOnErrorListener { _, what, extra ->
            Log.e(TAG, "onError what=$what extra=$extra (will try next/stop)")
            TTSDebugTracker.trackChannelSwitch("ERROR what=$what extra=$extra at index=$currentIndex")
            playNext()
            true
        }
        mp.setOnSeekCompleteListener {
            Log.d(TAG, "onSeekComplete pendingSeekStart=$pendingSeekStart")
            if (pendingSeekStart) {
                try {
                    mp.start()
                } catch (_: Exception) { }
                pendingSeekStart = false
                preparedButNotPlaying = false
                updateNotification()
                startProgressUpdates()
            } else {
                val (cur, total) = computeProgress()
                sendProgress(cur, total, mediaPlayer?.isPlaying == true)
            }
        }
    }

    private fun prepareCurrentSilently() {
        if (playlist.isEmpty()) {
            Log.w(TAG, "prepareCurrentSilently: playlist is empty")
            return
        }
        val path = playlist[currentIndex]
        val f = File(path)
        Log.d(TAG, "prepareCurrentSilently: index=$currentIndex path=$path exists=${f.exists()} len=${f.length()}")
        TTSDebugTracker.trackChannelSwitch("PREPARE(silent) index=$currentIndex path=$path")

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

                attachCommonListeners(this, startFromMs = null, autoStart = false)
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
        TTSDebugTracker.trackChannelSwitch("PREPARE&PLAY index=$currentIndex path=$path startFrom=${startFromMs ?: -1}")

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

                attachCommonListeners(this, startFromMs = startFromMs, autoStart = true)
                Log.d(TAG, "prepareAsync()")
                prepareAsync()
            } catch (e: Exception) {
                Log.e(TAG, "prepareAndPlayCurrent failed", e)
                playNext()
            }
        }

        updateNotification()
    }

    private fun findNextChapterIndex(fromIndex: Int, currentPosMs: Int): Int {
        if (chapterStartsMs.isEmpty()) return -1
        var idx = (fromIndex + 1).coerceAtLeast(0)
        val epsilon = 50
        while (idx < chapterStartsMs.size && chapterStartsMs[idx] <= currentPosMs + epsilon) {
            idx++
        }
        return if (idx < chapterStartsMs.size) idx else -1
    }

    private fun onTrackCompletion() {
        if (hasChapters()) {
            val curPos = try { mediaPlayer?.currentPosition ?: 0 } catch (_: Exception) { 0 }
            val nextIdx = findNextChapterIndex(currentChapter, curPos)
            if (nextIdx != -1) {
                currentChapter = nextIdx
                val nextMs = chapterStartsMs[currentChapter].toInt()
                Log.d(TAG, "Completion: move to next chapter=$currentChapter ms=$nextMs")
                try {
                    pendingSeekStart = true
                    mediaPlayer?.seekTo(nextMs)
                } catch (_: Exception) { }
                updateNotification()
                // ИСПРАВЛЕНО: Используем правильный счетчик новостей
                sendProgress(currentChapter + 1, totalNewsCount, mediaPlayer?.isPlaying == true)
            } else {
                Log.d(TAG, "Completion: last chapter -> stopServiceSafely()")
                TTSDebugTracker.trackChannelSwitch("COMPLETE no further chapter -> STOP")
                stopServiceSafely()
            }
            return
        }

        if (currentIndex < playlist.lastIndex) {
            currentIndex += 1
            currentChapter = 0
            preparedButNotPlaying = false
            pendingSeekStart = false
            prepareAndPlayCurrent()
            sendProgress(currentIndex + 1, playlist.size, true)
        } else {
            Log.d(TAG, "Completion: end -> stopServiceSafely()")
            stopServiceSafely()
        }
    }

    private fun play() {
        Log.d(TAG, "play(): player=${mediaPlayer != null}, isPlaying=${mediaPlayer?.isPlaying}, preparedButNotPlaying=$preparedButNotPlaying chapterMode=${hasChapters()} chapter=$currentChapter/${chapterStartsMs.size}")
        TTSDebugTracker.trackChannelSwitch("PLAY requested: idx=$currentIndex chaptersMode=${hasChapters()} chapter=$currentChapter")

        if (playlist.isEmpty()) {
            Log.w(TAG, "play(): playlist is empty")
            return
        }

        if (mediaPlayer == null) {
            preparedButNotPlaying = false
            pendingSeekStart = false
            val startMs = if (hasChapters() && currentChapter in chapterStartsMs.indices) {
                chapterStartsMs[currentChapter].toInt()
            } else null
            prepareAndPlayCurrent(startMs)
            return
        }

        try {
            if (preparedButNotPlaying && hasChapters() && currentChapter in chapterStartsMs.indices) {
                val toMs = chapterStartsMs[currentChapter].toInt()
                Log.d(TAG, "play(): preparedButNotPlaying -> seekTo currentChapter start $toMs ms, will start onSeekComplete")
                pendingSeekStart = true
                mediaPlayer?.seekTo(toMs)
            } else {
                preparedButNotPlaying = false
                mediaPlayer?.start()
                startProgressUpdates()
            }
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
            preparedButNotPlaying = false
            pendingSeekStart = false
            stopProgressUpdates()
            val (cur, total) = computeProgress()
            sendProgress(cur, total, false)
            updateNotification()
        }
    }

    private fun playNext() {
        Log.d(TAG, "playNext() currentIndex=$currentIndex size=${playlist.size} chapterMode=${hasChapters()} chapter=$currentChapter/${chapterStartsMs.size}")
        TTSDebugTracker.trackChannelSwitch("NEXT requested: idx=$currentIndex chapterMode=${hasChapters()} chapter=$currentChapter")

        if (playlist.isEmpty()) return

        if (hasChapters()) {
            if (chapterStartsMs.isEmpty()) return

            val wasPlaying = mediaPlayer?.isPlaying == true
            val wasPreparedNoStart = preparedButNotPlaying
            val curPos = try { mediaPlayer?.currentPosition ?: 0 } catch (_: Exception) { 0 }

            val nextIdx = findNextChapterIndex(currentChapter, curPos)
            if (nextIdx == -1) {
                Log.d(TAG, "Next on last/invalid chapter -> stop")
                TTSDebugTracker.trackChannelSwitch("NEXT no further chapter -> STOP")
                stopServiceSafely()
                return
            }

            currentChapter = nextIdx
            val toMs = chapterStartsMs[currentChapter].toInt()
            Log.d(TAG, "Next chapter -> $currentChapter at $toMs ms (wasPlaying=$wasPlaying, wasPreparedNoStart=$wasPreparedNoStart)")

            try {
                if (mediaPlayer == null) {
                    preparedButNotPlaying = false
                    pendingSeekStart = false
                    prepareAndPlayCurrent(toMs)
                    return
                }

                pendingSeekStart = wasPlaying || wasPreparedNoStart
                mediaPlayer?.seekTo(toMs)
                preparedButNotPlaying = false
                // ИСПРАВЛЕНО: Используем правильный счетчик новостей
                sendProgress(currentChapter + 1, totalNewsCount, wasPlaying || wasPreparedNoStart)
            } catch (_: Exception) { }
            updateNotification()
            return
        }

        if (currentIndex < playlist.lastIndex) {
            currentIndex += 1
            Log.d(TAG, "playNext(): newIndex=$currentIndex")
            TTSDebugTracker.trackChannelSwitch("NEXT track -> index=$currentIndex")
            preparedButNotPlaying = false
            pendingSeekStart = false
            prepareAndPlayCurrent()
            sendProgress(currentIndex + 1, playlist.size, true)
        } else {
            Log.d(TAG, "playNext(): end of playlist -> stopServiceSafely()")
            TTSDebugTracker.trackChannelSwitch("NEXT at end -> STOP")
            stopServiceSafely()
        }
    }

    private fun stopServiceSafely() {
        Log.d(TAG, "stopServiceSafely()")
        TTSDebugTracker.trackChannelSwitch("STOP service")
        stopProgressUpdates()
        releasePlayer()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "stopForeground failed", e)
        }
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(NOTIFICATION_ID)
        } catch (_: Exception) { }
        // Сообщим UI, что всё остановлено
        sendProgress(0, 0, false)
        stopSelf()
    }

    private fun releasePlayer() {
        Log.d(TAG, "releasePlayer()")
        pendingSeekStart = false
        try { mediaPlayer?.stop() } catch (_: Exception) { }
        try { mediaPlayer?.release() } catch (_: Exception) { }
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
            .setSmallIcon(R.drawable.ic_stat_tnr)
            .setContentTitle("$title — $positionText$chapterText")
            .setContentText(if (isPlaying) "Воспроизведение" else "Пауза / Ожидание старта")
            .setContentIntent(openIntent)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setColor(ContextCompat.getColor(this, R.color.purple_500))
            .addAction(
                if (isPlaying) R.drawable.ic_notif_pause else R.drawable.ic_notif_play,
                if (isPlaying) "Пауза" else "Пуск",
                playPauseIntent
            )
            .addAction(R.drawable.ic_notif_next, "Далее", nextIntent)

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

    // Новое: вычисление и отправка прогресса
    private fun chapterIndexForPosition(positionMs: Long): Int {
        if (chapterStartsMs.isEmpty()) return 0
        var i = 0
        while (i + 1 < chapterStartsMs.size && chapterStartsMs[i + 1] <= positionMs) i++
        return i.coerceIn(0, (chapterStartsMs.size - 1).coerceAtLeast(0))
    }

    // ИСПРАВЛЕНО: Используем правильные счетчики
    private fun computeProgress(): Pair<Int, Int> {
        return if (hasChapters()) {
            val pos = try { mediaPlayer?.currentPosition?.toLong() ?: 0L } catch (_: Exception) { 0L }
            val currentChapterIndex = chapterIndexForPosition(pos)

            // ИСПРАВЛЕНО: Используем totalNewsCount вместо chapterStartsMs.size
            val currentNews = currentChapterIndex + 1
            val totalNews = totalNewsCount

            // Ограничиваем диапазон для безопасности
            val clampedCurrent = currentNews.coerceIn(1, totalNews.coerceAtLeast(1))
            val clampedTotal = totalNews.coerceAtLeast(1)

            clampedCurrent to clampedTotal
        } else {
            (currentIndex + 1) to playlist.size
        }
    }



    private fun sendProgress(current: Int, total: Int, isPlaying: Boolean) {
        val i = Intent(ACTION_PROGRESS).apply {
            // 🔥 ИСПРАВЛЕНО: Добавляем package для безопасности
            `package` = applicationContext.packageName
            putExtra(EXTRA_CURRENT_ITEM, current)
            putExtra(EXTRA_TOTAL_ITEMS, total)
            putExtra(EXTRA_IS_PLAYING, isPlaying)
        }
        try {
            sendBroadcast(i)
        } catch (_: Exception) { }
    }
    private fun startProgressUpdates() {
        progressHandler.removeCallbacks(progressRunnable)
        progressHandler.post(progressRunnable)
    }
    private fun stopProgressUpdates() {
        progressHandler.removeCallbacks(progressRunnable)
    }
}