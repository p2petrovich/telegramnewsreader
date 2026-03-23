package com.p2petrovich.telegramnewsreader.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
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
        private const val MIN_ACTION_INTERVAL_MS = 500L
    }

    private var mediaPlayer: MediaPlayer? = null
    private var playlist: List<String> = emptyList()
    private var currentIndex = 0
    private var title: String = "Новости"

    private var chapterStartsMs: List<Long> = emptyList()
    private var currentChapter = 0
    private var isPreparedIdle = false
    private var isSeeking = false
    private var shouldPlayAfterSeek = false
    private var totalNewsCount = 0
    private var newsChapterIndices: Set<Int> = emptySet()
    private var lastActionTime = 0L

    // Защита: после seek на главу N, syncCurrentChapterWithPosition
    // не может откатить currentChapter ниже этого значения
    private var minChapterGuard = 0

    private val handler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            try {
                syncCurrentChapterWithPosition()
                val (cur, total) = computeProgress()
                sendProgress(cur, total, isActuallyPlaying())
            } catch (_: Exception) {}
            handler.postDelayed(this, 500)
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
                ACTION_PLAY -> {
                    if (throttle()) return START_NOT_STICKY
                    play()
                }
                ACTION_PAUSE -> {
                    if (throttle()) return START_NOT_STICKY
                    pause()
                }
                ACTION_STOP -> {
                    stopServiceSafely()
                    return START_NOT_STICKY
                }
                ACTION_NEXT -> {
                    if (throttle()) return START_NOT_STICKY
                    if (playlist.isNotEmpty()) playNext()
                }
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

    private fun throttle(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastActionTime < MIN_ACTION_INTERVAL_MS) return true
        lastActionTime = now
        return false
    }

    private fun isActuallyPlaying(): Boolean {
        return try { mediaPlayer?.isPlaying == true && !isSeeking } catch (_: Exception) { false }
    }

    private fun getCurrentPositionSafe(): Long {
        return try { mediaPlayer?.currentPosition?.toLong() ?: 0L } catch (_: Exception) { 0L }
    }

    // ============ Синхронизация главы с позицией — ТОЛЬКО ВПЕРЁД ============

    /**
     * Обновляет currentChapter по реальной позиции плеера.
     * Может только увеличивать currentChapter (или оставлять без изменений).
     * Никогда не откатывает назад — это исключает повторы.
     */
    private fun syncCurrentChapterWithPosition() {
        if (!hasChapters() || isSeeking || mediaPlayer == null) return

        val pos = getCurrentPositionSafe()
        val realChapter = findChapterByPosition(pos)

        // Только вперёд, и не ниже minChapterGuard
        val newChapter = maxOf(currentChapter, realChapter, minChapterGuard)
        if (newChapter != currentChapter) {
            Log.d(TAG, "syncChapter: $currentChapter -> $newChapter (pos=${pos}ms, guard=$minChapterGuard)")
            currentChapter = newChapter
        }
    }

    /**
     * Определяет главу по позиции в миллисекундах.
     * chapters = [0, 15000, 32000], pos = 20000 → возвращает 1
     */
    private fun findChapterByPosition(posMs: Long): Int {
        if (chapterStartsMs.isEmpty()) return 0
        var idx = 0
        for (i in chapterStartsMs.indices) {
            if (chapterStartsMs[i] <= posMs) {
                idx = i
            } else {
                break
            }
        }
        return idx
    }

    // ============ Playlist ============

    private fun setPlaylist(
        paths: List<String>, startIndex: Int, chapters: List<Long>,
        realNewsCount: Int = 0, newsIndices: Set<Int> = emptySet()
    ) {
        playlist = paths
        currentIndex = startIndex.coerceIn(0, (playlist.size - 1).coerceAtLeast(0))
        chapterStartsMs = if (paths.size == 1) chapters.sorted().distinct() else emptyList()
        newsChapterIndices = newsIndices
        totalNewsCount = if (realNewsCount > 0) realNewsCount
        else newsIndices.size.coerceAtLeast(chapterStartsMs.size)
        currentChapter = 0
        minChapterGuard = 0
        isPreparedIdle = true
        isSeeking = false
        shouldPlayAfterSeek = false

        Log.d(TAG, "setPlaylist: files=${paths.size}, chapters=${chapterStartsMs.size}, " +
                "newsIndices=${newsIndices.size}, totalNewsCount=$totalNewsCount")
        if (chapterStartsMs.isNotEmpty()) {
            Log.d(TAG, "Chapter timestamps: ${chapterStartsMs.joinToString(", ")}")
        }

        sendProgress(computeProgress().first, computeProgress().second, false)
        if (playlist.isNotEmpty()) prepareCurrentSilently()
    }

    private fun hasChapters(): Boolean = playlist.size == 1 && chapterStartsMs.size > 1

    // ============ Prepare ============

    private fun prepareCurrentSilently() {
        if (playlist.isEmpty()) return
        releasePlayer()

        mediaPlayer = createMediaPlayer(playlist[currentIndex]) { _ ->
            isPreparedIdle = true
            updateNotification()
            sendProgress(computeProgress().first, computeProgress().second, false)
        }
    }

    private fun prepareAndPlay(seekToMs: Long? = null) {
        if (playlist.isEmpty()) return
        releasePlayer()

        isPreparedIdle = false
        isSeeking = false
        shouldPlayAfterSeek = false

        mediaPlayer = createMediaPlayer(playlist[currentIndex]) { mp ->
            if (seekToMs != null && seekToMs > 0) {
                isSeeking = true
                shouldPlayAfterSeek = true
                seekSafe(mp, seekToMs)
            } else {
                safeStart(mp)
                updateNotification()
                startProgressUpdates()
            }
        }
    }

    private fun createMediaPlayer(
        path: String,
        onPrepared: (MediaPlayer) -> Unit
    ): MediaPlayer? {
        return try {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )

                try {
                    setDataSource(path)
                } catch (_: Exception) {
                    val fis = FileInputStream(File(path))
                    setDataSource(fis.fd)
                    try { fis.close() } catch (_: Exception) {}
                }

                setOnPreparedListener { mp -> onPrepared(mp) }
                setOnCompletionListener { handler.post { onTrackCompletion() } }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                    handler.post { onPlayerError() }
                    true
                }
                setOnSeekCompleteListener { handler.post { onSeekComplete() } }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "createMediaPlayer failed", e)
            handler.post { onPlayerError() }
            null
        }
    }

    // ============ Seek ============

    private fun seekSafe(mp: MediaPlayer, posMs: Long) {
        isSeeking = true
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                mp.seekTo(posMs, MediaPlayer.SEEK_CLOSEST)
            } else {
                mp.seekTo(posMs.toInt())
            }
            Log.d(TAG, "seekSafe: target=${posMs}ms")
        } catch (e: Exception) {
            Log.e(TAG, "seekSafe failed", e)
            isSeeking = false
            shouldPlayAfterSeek = false
        }
    }

    private fun onSeekComplete() {
        val actualPos = getCurrentPositionSafe()
        Log.d(TAG, "onSeekComplete: actualPos=${actualPos}ms, chapter=$currentChapter, shouldPlay=$shouldPlayAfterSeek")

        isSeeking = false
        if (shouldPlayAfterSeek) {
            shouldPlayAfterSeek = false
            isPreparedIdle = false
            mediaPlayer?.let { safeStart(it) }
            updateNotification()
            startProgressUpdates()
        }
        val (cur, total) = computeProgress()
        sendProgress(cur, total, isActuallyPlaying())
    }

    private fun safeStart(mp: MediaPlayer) {
        try {
            if (!mp.isPlaying) mp.start()
        } catch (e: Exception) {
            Log.e(TAG, "safeStart failed", e)
        }
    }

    // ============ Play / Pause / Next ============

    private fun play() {
        if (playlist.isEmpty()) return

        val mp = mediaPlayer
        if (mp == null) {
            val startMs = if (hasChapters() && currentChapter in chapterStartsMs.indices)
                chapterStartsMs[currentChapter] else null
            prepareAndPlay(startMs)
            return
        }

        if (isSeeking) {
            shouldPlayAfterSeek = true
            return
        }

        if (isPreparedIdle && hasChapters() && currentChapter in chapterStartsMs.indices) {
            isPreparedIdle = false
            isSeeking = true
            shouldPlayAfterSeek = true
            seekSafe(mp, chapterStartsMs[currentChapter])
        } else {
            isPreparedIdle = false
            safeStart(mp)
            startProgressUpdates()
        }

        updateNotification()
    }

    private fun pause() {
        if (isSeeking) {
            shouldPlayAfterSeek = false
            return
        }

        val mp = mediaPlayer ?: return
        try { if (mp.isPlaying) mp.pause() } catch (_: Exception) {}

        isPreparedIdle = false
        stopProgressUpdates()
        sendProgress(computeProgress().first, computeProgress().second, false)
        updateNotification()
    }

    /**
     * Переход к следующей главе.
     * Детерминистический: всегда currentChapter + 1.
     * НЕ зависит от текущей позиции плеера — это исключает повторы.
     */
    private fun playNext() {
        if (playlist.isEmpty()) return

        if (isSeeking) {
            Log.d(TAG, "playNext ignored: seeking in progress")
            return
        }

        if (hasChapters()) {
            val nextIdx = currentChapter + 1

            if (nextIdx >= chapterStartsMs.size) {
                Log.d(TAG, "playNext: no more chapters (current=$currentChapter, total=${chapterStartsMs.size}), stopping")
                stopServiceSafely()
                return
            }

            val wasPlaying = isActuallyPlaying() || isPreparedIdle
            currentChapter = nextIdx
            minChapterGuard = nextIdx // Защита от отката

            Log.d(TAG, "playNext: -> chapter $nextIdx at ${chapterStartsMs[nextIdx]}ms (wasPlaying=$wasPlaying)")

            val mp = mediaPlayer
            if (mp == null) {
                prepareAndPlay(chapterStartsMs[currentChapter])
                return
            }

            // Пауза перед seek — убирает треск/артефакты
            try { if (mp.isPlaying) mp.pause() } catch (_: Exception) {}

            isSeeking = true
            shouldPlayAfterSeek = wasPlaying
            seekSafe(mp, chapterStartsMs[currentChapter])

            val (cur, total) = computeProgress()
            sendProgress(cur, total, wasPlaying)
            updateNotification()
            return
        }

        // Множественные файлы
        if (currentIndex < playlist.lastIndex) {
            currentIndex++
            prepareAndPlay()
        } else {
            stopServiceSafely()
        }
    }

    private fun onTrackCompletion() {
        if (hasChapters()) {
            Log.d(TAG, "onTrackCompletion: WAV file ended, all chapters played")
            stopServiceSafely()
            return
        }

        if (currentIndex < playlist.lastIndex) {
            currentIndex++
            currentChapter = 0
            minChapterGuard = 0
            prepareAndPlay()
        } else {
            stopServiceSafely()
        }
    }

    private fun onPlayerError() {
        Log.e(TAG, "onPlayerError: attempting to skip to next")
        if (hasChapters()) {
            val nextIdx = currentChapter + 1
            if (nextIdx < chapterStartsMs.size) {
                currentChapter = nextIdx
                minChapterGuard = nextIdx
                prepareAndPlay(chapterStartsMs[nextIdx])
            } else {
                stopServiceSafely()
            }
        } else if (currentIndex < playlist.lastIndex) {
            currentIndex++
            prepareAndPlay()
        } else {
            stopServiceSafely()
        }
    }

    // ============ Stop / Release ============

    private fun stopServiceSafely() {
        stopProgressUpdates()
        releasePlayer()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        } catch (_: Exception) {}
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(NOTIFICATION_ID)
        } catch (_: Exception) {}
        sendProgress(0, 0, false)
        stopSelf()
    }

    private fun releasePlayer() {
        isSeeking = false
        shouldPlayAfterSeek = false
        isPreparedIdle = false
        try { mediaPlayer?.stop() } catch (_: Exception) {}
        try { mediaPlayer?.release() } catch (_: Exception) {}
        mediaPlayer = null
    }

    // ============ Progress ============

    private fun computeProgress(): Pair<Int, Int> {
        return if (hasChapters()) {
            if (newsChapterIndices.isEmpty()) {
                val cur = (currentChapter + 1).coerceIn(1, totalNewsCount.coerceAtLeast(1))
                cur to totalNewsCount.coerceAtLeast(1)
            } else {
                val newsPlayed = newsChapterIndices.count { it <= currentChapter }
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
        handler.removeCallbacks(progressRunnable)
        handler.post(progressRunnable)
    }

    private fun stopProgressUpdates() {
        handler.removeCallbacks(progressRunnable)
    }

    // ============ Notification ============

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Аудио", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    private fun buildNotification(): Notification {
        val isPlaying = isActuallyPlaying()
        val total = playlist.size
        val posText = if (total > 0) "${currentIndex + 1}/$total" else "0/0"

        val chapterText = if (hasChapters() && newsChapterIndices.isNotEmpty()) {
            val newsPlayed = newsChapterIndices.count { it <= currentChapter }
                .coerceAtLeast(if (currentChapter in newsChapterIndices) 1 else 0)
            " • новость $newsPlayed/$totalNewsCount"
        } else if (hasChapters()) {
            " • ${currentChapter + 1}/${chapterStartsMs.size}"
        } else ""

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
                if (isPlaying) "Пауза" else "Пуск", playPauseIntent
            )
            .addAction(R.drawable.ic_notif_next, "Далее", nextIntent)
            .build()
    }

    private fun updateNotification() {
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {}
    }

    private fun pendingFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
}
