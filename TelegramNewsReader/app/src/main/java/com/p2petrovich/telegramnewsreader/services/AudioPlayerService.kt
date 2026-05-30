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
import com.p2petrovich.telegramnewsreader.utils.PreferenceManager
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
        const val ACTION_REQUEST_STATUS = "player.REQUEST_STATUS"

        const val EXTRA_FILE_PATHS = "extra.FILE_PATHS"
        const val EXTRA_START_INDEX = "extra.START_INDEX"
        const val EXTRA_TITLE = "extra.TITLE"
        const val EXTRA_REAL_NEWS_COUNT = "extra.REAL_NEWS_COUNT"
        const val EXTRA_NEWS_FILE_INDICES = "extra.NEWS_FILE_INDICES"

        const val ACTION_PROGRESS = "com.p2petrovich.telegramnewsreader.PLAYER_PROGRESS"
        const val EXTRA_CURRENT_ITEM = "extra_current_item"
        const val EXTRA_TOTAL_ITEMS = "extra_total_items"
        const val EXTRA_IS_PLAYING = "extra_is_playing"

        private const val CHANNEL_ID = "audio_playback_channel"
        private const val NOTIFICATION_ID = 1001
        private const val MIN_ACTION_INTERVAL_MS = 300L
    }

    private var mediaPlayer: MediaPlayer? = null
    private var playlist: List<String> = emptyList()
    private var currentIndex = 0
    private var title: String = "Новости"
    private var totalNewsCount = 0
    private var newsFileIndices: Set<Int> = emptySet()
    private var lastActionTime = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            try {
                val (cur, total) = computeProgress()
                sendProgress(cur, total, isActuallyPlaying())
            } catch (_: Exception) {}
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate() { 
        super.onCreate()
        createChannel()
        restoreState()
    }

    private fun restoreState() {
        val paths = PreferenceManager.getPlaylistPaths(this)
        if (paths.isNotEmpty()) {
            playlist = paths
            currentIndex = PreferenceManager.getPlayerIndex(this).coerceIn(0, (playlist.size - 1).coerceAtLeast(0))
            Log.d(TAG, "restoreState: restored ${playlist.size} files, index $currentIndex")
            prepareCurrentSilently()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_SET_PLAYLIST -> {
                    val paths = intent.getStringArrayListExtra(EXTRA_FILE_PATHS) ?: arrayListOf()
                    val start = intent.getIntExtra(EXTRA_START_INDEX, 0)
                    title = intent.getStringExtra(EXTRA_TITLE) ?: "Новости"
                    val realNews = intent.getIntExtra(EXTRA_REAL_NEWS_COUNT, 0)
                    val newsIndices = intent.getIntArrayExtra(EXTRA_NEWS_FILE_INDICES)?.toSet() ?: emptySet()
                    setPlaylist(paths, start, realNews, newsIndices)
                    
                    // Сохраняем состояние для восстановления
                    PreferenceManager.savePlaylistPaths(this, paths)
                    PreferenceManager.savePlayerIndex(this, start)
                }
                ACTION_PLAY -> { 
                    if (throttle()) return START_STICKY
                    play() 
                    PreferenceManager.savePlayerIsPlaying(this, true)
                }
                ACTION_PAUSE -> { 
                    if (throttle()) return START_STICKY
                    pause() 
                    PreferenceManager.savePlayerIsPlaying(this, false)
                }
                ACTION_STOP -> { 
                    stopServiceSafely()
                    PreferenceManager.clearPlayerState(this)
                    return START_STICKY 
                }
                ACTION_NEXT -> { 
                    if (throttle()) return START_STICKY
                    playNext() 
                }
                ACTION_REQUEST_STATUS -> { sendProgress(computeProgress().first, computeProgress().second, isActuallyPlaying()) }
            }
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) { Log.e(TAG, "onStartCommand exception", e) }
        return START_STICKY
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
        lastActionTime = now; return false
    }

    private fun isActuallyPlaying(): Boolean = try { mediaPlayer?.isPlaying == true } catch (_: Exception) { false }

    // ============ Playlist ============

    private fun setPlaylist(paths: List<String>, startIndex: Int, realNewsCount: Int, newsIndices: Set<Int>) {
        playlist = paths
        currentIndex = startIndex.coerceIn(0, (playlist.size - 1).coerceAtLeast(0))
        newsFileIndices = newsIndices
        totalNewsCount = if (realNewsCount > 0) realNewsCount else newsIndices.size.coerceAtLeast(playlist.size)

        Log.d(TAG, "setPlaylist: ${paths.size} files, news=$totalNewsCount, newsIndices=${newsIndices.size}")

        sendProgress(computeProgress().first, computeProgress().second, false)
        if (playlist.isNotEmpty()) prepareCurrentSilently()
    }

    // ============ Prepare ============

    private fun prepareCurrentSilently() {
        if (playlist.isEmpty() || currentIndex !in playlist.indices) return
        releasePlayer()
        mediaPlayer = createMediaPlayer(playlist[currentIndex]) {
            updateNotification()
            sendProgress(computeProgress().first, computeProgress().second, false)
        }
    }

    private fun prepareAndPlay() {
        if (playlist.isEmpty() || currentIndex !in playlist.indices) return
        releasePlayer()
        mediaPlayer = createMediaPlayer(playlist[currentIndex]) { mp ->
            safeStart(mp)
            updateNotification()
            startProgressUpdates()
        }
    }

    private fun createMediaPlayer(path: String, onPrepared: (MediaPlayer) -> Unit): MediaPlayer? {
        return try {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                try { setDataSource(path) } catch (_: Exception) {
                    val fis = FileInputStream(File(path))
                    setDataSource(fis.fd)
                    try { fis.close() } catch (_: Exception) {}
                }
                setOnPreparedListener { mp -> onPrepared(mp) }
                setOnCompletionListener { handler.post { onTrackCompletion() } }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                    handler.post { playNext() }
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "createMediaPlayer failed", e)
            handler.post { playNext() }
            null
        }
    }

    private fun safeStart(mp: MediaPlayer) {
        try { if (!mp.isPlaying) mp.start() } catch (e: Exception) { Log.e(TAG, "safeStart failed", e) }
    }

    // ============ Play / Pause / Next ============

    private fun play() {
        if (playlist.isEmpty()) return
        val mp = mediaPlayer
        if (mp == null) { prepareAndPlay(); return }
        safeStart(mp)
        startProgressUpdates()
        updateNotification()
    }

    private fun pause() {
        val mp = mediaPlayer ?: return
        try { if (mp.isPlaying) mp.pause() } catch (_: Exception) {}
        stopProgressUpdates()
        sendProgress(computeProgress().first, computeProgress().second, false)
        updateNotification()
    }

    private fun playNext() {
        if (playlist.isEmpty()) return
        if (currentIndex < playlist.lastIndex) {
            currentIndex++
            Log.d(TAG, "playNext: -> file $currentIndex/${playlist.size}")
            PreferenceManager.savePlayerIndex(this, currentIndex)
            prepareAndPlay()
        } else {
            Log.d(TAG, "playNext: end of playlist")
            PreferenceManager.clearPlayerState(this)
            stopServiceSafely()
        }
    }

    private fun onTrackCompletion() {
        Log.d(TAG, "onTrackCompletion: file $currentIndex done")
        playNext()
    }

    // ============ Stop / Release ============

    private fun stopServiceSafely() {
        stopProgressUpdates()
        releasePlayer()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        } catch (_: Exception) {}
        try { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID) } catch (_: Exception) {}
        sendProgress(0, 0, false)
        stopSelf()
    }

    private fun releasePlayer() {
        try { mediaPlayer?.stop() } catch (_: Exception) {}
        try { mediaPlayer?.release() } catch (_: Exception) {}
        mediaPlayer = null
    }

    // ============ Progress ============

    private fun computeProgress(): Pair<Int, Int> {
        if (playlist.isEmpty()) return 0 to 0
        return if (newsFileIndices.isNotEmpty()) {
            val newsPlayed = newsFileIndices.count { it <= currentIndex }
            newsPlayed.coerceAtLeast(1).coerceIn(1, totalNewsCount) to totalNewsCount
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

    private fun startProgressUpdates() { handler.removeCallbacks(progressRunnable); handler.post(progressRunnable) }
    private fun stopProgressUpdates() { handler.removeCallbacks(progressRunnable) }

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
        val isPlaying = isActuallyPlaying()
        val (cur, total) = computeProgress()
        val progressText = if (total > 0) "новость $cur/$total" else ""

        val openIntent = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or pendingFlag())

        val playPauseAction = if (isPlaying) ACTION_PAUSE else ACTION_PLAY
        val playPauseIntent = PendingIntent.getService(this, 2,
            Intent(this, AudioPlayerService::class.java).setAction(playPauseAction),
            PendingIntent.FLAG_UPDATE_CURRENT or pendingFlag())

        val nextIntent = PendingIntent.getService(this, 3,
            Intent(this, AudioPlayerService::class.java).setAction(ACTION_NEXT),
            PendingIntent.FLAG_UPDATE_CURRENT or pendingFlag())

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tnr)
            .setContentTitle("$title — $progressText")
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
        try { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, buildNotification()) } catch (_: Exception) {}
    }

    private fun pendingFlag(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
}