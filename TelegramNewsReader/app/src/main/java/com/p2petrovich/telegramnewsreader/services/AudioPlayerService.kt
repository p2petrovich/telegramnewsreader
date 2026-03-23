package com.p2petrovich.telegramnewsreader.services

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
import com.p2petrovich.telegramnewsreader.R
import com.p2petrovich.telegramnewsreader.activities.MainActivity
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
        const val EXTRA_CHAPTERS = "extra.CHAPTERS"
        const val EXTRA_REAL_NEWS_COUNT = "extra.REAL_NEWS_COUNT"
        const val EXTRA_NEWS_CHAPTER_INDICES = "extra.NEWS_CHAPTER_INDICES"

        const val ACTION_PROGRESS = "com.p2petrovich.telegramnewsreader.PLAYER_PROGRESS"
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
    private var totalNewsCount = 0
    // Индексы в chapterStartsMs, соответствующие новостям (не заголовкам) — для счётчика
    private var newsChapterIndices: Set<Int> = emptySet()

    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            try {
                val (cur, total) = computeProgress()
                sendProgress(cur, total, mediaPlayer?.isPlaying == true)
            } catch (_: Exception) {}
            progressHandler.postDelayed(this, 500)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_SET_PLAYLIST -> {
                    val paths = intent.getStringArrayListExtra(EXTRA_FILE_PATHS) ?: arrayListOf()
                    val start = intent.getIntExtra(EXTRA_START_INDEX, 0)
                    title = intent.getStringExtra(EXTRA_TITLE) ?: "Новости"
                    val chapters = intent.getLongArrayExtra(EXTRA_CHAPTERS)?.toList() ?: emptyList()
                    val realNews = intent.getIntExtra(EXTRA_REAL_NEWS_COUNT, 0)
                    val newsIndices = intent.getIntArrayExtra(EXTRA_NEWS_CHAPTER_INDICES)
                        ?.toSet() ?: emptySet()
                    setPlaylist(paths, start, chapters, realNews, newsIndices)
                }
                ACTION_PLAY -> play()
                ACTION_PAUSE -> pause()
                ACTION_STOP -> { stopServiceSafely(); return START_NOT_STICKY }
                ACTION_NEXT -> { if (playlist.isNotEmpty()) playNext() }
            }
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            Log.e(TAG, "onStartCommand exception", e)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopProgressUpdates()
        releasePlayer()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        } catch (_: Exception) {}
    }

    private fun setPlaylist(paths: List<String>, startIndex: Int, chapters: List<Long>,
                            realNewsCount: Int = 0, newsIndices: Set<Int> = emptySet()) {
        playlist = paths
        currentIndex = startIndex.coerceIn(0, (playlist.size - 1).coerceAtLeast(0))
        chapterStartsMs = if (paths.size == 1) chapters.sorted().distinct() else emptyList()
        newsChapterIndices = newsIndices

        // totalNewsCount = только новости (для счётчика "новость X из Y")
        totalNewsCount = if (realNewsCount > 0) realNewsCount else newsIndices.size.coerceAtLeast(chapterStartsMs.size)
        currentChapter = 0
        preparedButNotPlaying = true
        pendingSeekStart = false

        Log.d(TAG, "setPlaylist: chapters=${chapterStartsMs.size}, newsIndices=${newsIndices.size}, totalNewsCount=$totalNewsCount")

        sendProgress(computeProgress().first, computeProgress().second, false)
        if (playlist.isNotEmpty()) prepareCurrentSilently()
    }

    private fun hasChapters(): Boolean = playlist.size == 1 && chapterStartsMs.size > 1

    private fun prepareCurrentSilently() {
        if (playlist.isEmpty()) return
        val path = playlist[currentIndex]
        releasePlayer()

        mediaPlayer = MediaPlayer().apply {
            try {
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())

                try { setDataSource(path) } catch (_: Exception) {
                    val fis = FileInputStream(File(path))
                    setDataSource(fis.fd)
                    try { fis.close() } catch (_: Exception) {}
                }

                setOnPreparedListener {
                    updateNotification()
                    sendProgress(computeProgress().first, computeProgress().second, false)
                }
                setOnCompletionListener { onTrackCompletion() }
                setOnErrorListener { _, _, _ -> playNext(); true }
                setOnSeekCompleteListener {
                    if (pendingSeekStart) {
                        try { start() } catch (_: Exception) {}
                        pendingSeekStart = false
                        preparedButNotPlaying = false
                        updateNotification()
                        startProgressUpdates()
                    }
                }
                prepareAsync()
            } catch (_: Exception) { playNext() }
        }
    }

    private fun prepareAndPlayCurrent(startFromMs: Int? = null) {
        if (playlist.isEmpty()) return
        val path = playlist[currentIndex]
        releasePlayer()

        mediaPlayer = MediaPlayer().apply {
            try {
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())

                try { setDataSource(path) } catch (_: Exception) {
                    val fis = FileInputStream(File(path))
                    setDataSource(fis.fd)
                    try { fis.close() } catch (_: Exception) {}
                }

                setOnPreparedListener {
                    startFromMs?.let { ms -> seekTo(ms) }
                    start()
                    updateNotification()
                    startProgressUpdates()
                }
                setOnCompletionListener { onTrackCompletion() }
                setOnErrorListener { _, _, _ -> playNext(); true }
                setOnSeekCompleteListener {
                    if (pendingSeekStart) {
                        try { start() } catch (_: Exception) {}
                        pendingSeekStart = false
                        preparedButNotPlaying = false
                        updateNotification()
                        startProgressUpdates()
                    }
                }
                prepareAsync()
            } catch (_: Exception) { playNext() }
        }
    }

    private fun findNextChapterIndex(fromIndex: Int, currentPosMs: Int): Int {
        if (chapterStartsMs.isEmpty()) return -1
        var idx = (fromIndex + 1).coerceAtLeast(0)
        while (idx < chapterStartsMs.size && chapterStartsMs[idx] <= currentPosMs + 50) idx++
        return if (idx < chapterStartsMs.size) idx else -1
    }

    private fun onTrackCompletion() {
        if (hasChapters()) {
            val curPos = try { mediaPlayer?.currentPosition ?: 0 } catch (_: Exception) { 0 }
            val nextIdx = findNextChapterIndex(currentChapter, curPos)
            if (nextIdx != -1) {
                currentChapter = nextIdx
                pendingSeekStart = true
                try { mediaPlayer?.seekTo(chapterStartsMs[currentChapter].toInt()) } catch (_: Exception) {}
                updateNotification()
                sendProgress(currentChapter + 1, totalNewsCount, true)
            } else {
                stopServiceSafely()
            }
            return
        }

        if (currentIndex < playlist.lastIndex) {
            currentIndex++; currentChapter = 0
            preparedButNotPlaying = false; pendingSeekStart = false
            prepareAndPlayCurrent()
        } else {
            stopServiceSafely()
        }
    }

    private fun play() {
        if (playlist.isEmpty()) return
        if (mediaPlayer == null) {
            val startMs = if (hasChapters() && currentChapter in chapterStartsMs.indices)
                chapterStartsMs[currentChapter].toInt() else null
            prepareAndPlayCurrent(startMs)
            return
        }
        try {
            if (preparedButNotPlaying && hasChapters() && currentChapter in chapterStartsMs.indices) {
                pendingSeekStart = true
                mediaPlayer?.seekTo(chapterStartsMs[currentChapter].toInt())
            } else {
                preparedButNotPlaying = false
                mediaPlayer?.start()
                startProgressUpdates()
            }
        } catch (_: Exception) {}
        updateNotification()
    }

    private fun pause() {
        if (mediaPlayer?.isPlaying == true) {
            try { mediaPlayer?.pause() } catch (_: Exception) {}
            preparedButNotPlaying = false; pendingSeekStart = false
            stopProgressUpdates()
            sendProgress(computeProgress().first, computeProgress().second, false)
            updateNotification()
        }
    }

    private fun playNext() {
        if (playlist.isEmpty()) return

        if (hasChapters()) {
            val curPos = try { mediaPlayer?.currentPosition ?: 0 } catch (_: Exception) { 0 }
            val nextIdx = findNextChapterIndex(currentChapter, curPos)
            if (nextIdx == -1) { stopServiceSafely(); return }

            currentChapter = nextIdx
            val wasPlaying = mediaPlayer?.isPlaying == true || preparedButNotPlaying

            try {
                if (mediaPlayer == null) {
                    prepareAndPlayCurrent(chapterStartsMs[currentChapter].toInt())
                    return
                }
                pendingSeekStart = wasPlaying
                mediaPlayer?.seekTo(chapterStartsMs[currentChapter].toInt())
                preparedButNotPlaying = false
                sendProgress(currentChapter + 1, totalNewsCount, wasPlaying)
            } catch (_: Exception) {}
            updateNotification()
            return
        }

        if (currentIndex < playlist.lastIndex) {
            currentIndex++
            preparedButNotPlaying = false; pendingSeekStart = false
            prepareAndPlayCurrent()
        } else {
            stopServiceSafely()
        }
    }

    private fun stopServiceSafely() {
        stopProgressUpdates()
        releasePlayer()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        } catch (_: Exception) {}
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
        } catch (_: Exception) {}
        sendProgress(0, 0, false)
        stopSelf()
    }

    private fun releasePlayer() {
        pendingSeekStart = false
        try { mediaPlayer?.stop() } catch (_: Exception) {}
        try { mediaPlayer?.release() } catch (_: Exception) {}
        mediaPlayer = null
    }

    private fun computeProgress(): Pair<Int, Int> {
        return if (hasChapters()) {
            val pos = try { mediaPlayer?.currentPosition?.toLong() ?: 0L } catch (_: Exception) { 0L }
            // Находим текущую главу по позиции
            var chapterIdx = 0
            while (chapterIdx + 1 < chapterStartsMs.size && chapterStartsMs[chapterIdx + 1] <= pos) chapterIdx++

            if (newsChapterIndices.isEmpty()) {
                // Нет данных об индексах — показываем всё как есть
                (chapterIdx + 1).coerceIn(1, totalNewsCount.coerceAtLeast(1)) to totalNewsCount.coerceAtLeast(1)
            } else {
                // Считаем сколько новостей (не заголовков) уже прошло или играет сейчас
                val newsPlayed = newsChapterIndices.count { it <= chapterIdx }
                val cur = newsPlayed.coerceAtLeast(1)
                cur.coerceIn(1, totalNewsCount.coerceAtLeast(1)) to totalNewsCount.coerceAtLeast(1)
            }
        } else {
            (currentIndex + 1) to playlist.size
        }
    }

    private fun sendProgress(current: Int, total: Int, isPlaying: Boolean) {
        try {
            sendBroadcast(Intent(ACTION_PROGRESS).apply {
                `package` = applicationContext.packageName
                putExtra(EXTRA_CURRENT_ITEM, current)
                putExtra(EXTRA_TOTAL_ITEMS, total)
                putExtra(EXTRA_IS_PLAYING, isPlaying)
            })
        } catch (_: Exception) {}
    }

    private fun startProgressUpdates() {
        progressHandler.removeCallbacks(progressRunnable)
        progressHandler.post(progressRunnable)
    }

    private fun stopProgressUpdates() {
        progressHandler.removeCallbacks(progressRunnable)
    }

    // ============ Notification ============

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Аудио", NotificationManager.IMPORTANCE_LOW))
            }
        }
    }

    private fun buildNotification(): Notification {
        val isPlaying = mediaPlayer?.isPlaying == true
        val total = playlist.size
        val posText = if (total > 0) "${currentIndex + 1}/$total" else "0/0"
        val chapterText = if (hasChapters() && newsChapterIndices.isNotEmpty()) {
            val newsPlayed = newsChapterIndices.count { it <= currentChapter }.coerceAtLeast(
                if (currentChapter in newsChapterIndices) 1 else 0)
            " • новость $newsPlayed/$totalNewsCount"
        } else if (hasChapters()) {
            " • ${currentChapter + 1}/${chapterStartsMs.size}"
        } else ""

        val openIntent = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or pendingFlag())

        val playPauseAction = if (isPlaying) ACTION_PAUSE else ACTION_PLAY
        val playPauseIntent = PendingIntent.getService(this, 2,
            Intent(this, AudioPlayerService::class.java).setAction(playPauseAction),
            PendingIntent.FLAG_UPDATE_CURRENT or pendingFlag())

        val nextIntent = PendingIntent.getService(this, 3,
            Intent(this, AudioPlayerService::class.java).setAction(ACTION_NEXT),
            PendingIntent.FLAG_UPDATE_CURRENT or pendingFlag())

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tnr)
            .setContentTitle("$title — $posText$chapterText")
            .setContentText(if (isPlaying) "Воспроизведение" else "Пауза")
            .setContentIntent(openIntent)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setColor(ContextCompat.getColor(this, R.color.purple_500))
            .addAction(
                if (isPlaying) R.drawable.ic_notif_pause else R.drawable.ic_notif_play,
                if (isPlaying) "Пауза" else "Пуск", playPauseIntent)
            .addAction(R.drawable.ic_notif_next, "Далее", nextIntent)
            .build()
    }

    private fun updateNotification() {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    private fun pendingFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
}
