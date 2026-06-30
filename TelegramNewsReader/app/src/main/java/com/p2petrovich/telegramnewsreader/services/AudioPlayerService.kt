package com.p2petrovich.telegramnewsreader.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
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
    private var title: String = ""
    private var totalNewsCount = 0
    private var newsFileIndices: Set<Int> = emptySet()
    private var lastActionTime = 0L

    private lateinit var mediaSession: MediaSessionCompat

    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var playOnFocusGain = false

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.d(TAG, "AudioFocus: LOSS")
                pause()
                playOnFocusGain = false
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.d(TAG, "AudioFocus: LOSS_TRANSIENT")
                if (isActuallyPlaying()) {
                    pause()
                    playOnFocusGain = true
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.d(TAG, "AudioFocus: LOSS_TRANSIENT_CAN_DUCK")
                mediaPlayer?.setVolume(0.2f, 0.2f)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "AudioFocus: GAIN")
                mediaPlayer?.setVolume(1.0f, 1.0f)
                if (playOnFocusGain) {
                    play()
                    playOnFocusGain = false
                }
            }
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                Log.d(TAG, "Audio becoming noisy (headphones disconnected)")
                pause()
            }
        }
    }

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
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Initialize MediaSession
        mediaSession = MediaSessionCompat(this, TAG).apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { play() }
                override fun onPause() { pause() }
                override fun onSkipToNext() { playNext() }
                override fun onStop() { stopServiceSafely() }
            })
            isActive = true
        }
        
        // Регистрация ресивера для отключения наушников.
        // ACTION_AUDIO_BECOMING_NOISY рассылает система → требуется RECEIVER_EXPORTED.
        // На targetSdk 34 отсутствие флага у динамического ресивера → SecurityException.
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        ContextCompat.registerReceiver(
            this,
            noisyReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )

        createChannel()
        restoreState()
    }

    private fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val playbackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(playbackAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            audioManager.requestAudioFocus(audioFocusRequest!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
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
                    title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.news_default_title)
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
                ACTION_REQUEST_STATUS -> {
                    val (cur, total) = computeProgress()
                    sendProgress(cur, total, isActuallyPlaying())
                }
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
        abandonAudioFocus()
        mediaSession.release()
        try {
            unregisterReceiver(noisyReceiver)
        } catch (_: Exception) {}
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
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

        val (cur, total) = computeProgress()
        sendProgress(cur, total, false)
        if (playlist.isNotEmpty()) prepareCurrentSilently()
    }

    // ============ Prepare ============

    private fun prepareCurrentSilently() {
        if (playlist.isEmpty() || currentIndex !in playlist.indices) return
        releasePlayer()
        mediaPlayer = createMediaPlayer(playlist[currentIndex]) {
            updateNotification()
            val (cur, total) = computeProgress()
            sendProgress(cur, total, false)
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
        
        if (!requestAudioFocus()) {
            Log.w(TAG, "Failed to request audio focus")
            return
        }

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
        abandonAudioFocus()
        val (cur, total) = computeProgress()
        sendProgress(cur, total, false)
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
            stopForeground(STOP_FOREGROUND_REMOVE)
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
                mgr.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.audio_channel_name), NotificationManager.IMPORTANCE_LOW))
            }
        }
    }

    private fun buildNotification(): Notification {
        if (title.isEmpty()) title = getString(R.string.news_default_title)
        val isPlaying = isActuallyPlaying()
        val (cur, total) = computeProgress()
        val progressText = if (total > 0) getString(R.string.news_progress_format, cur, total) else ""

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
            .setContentText(if (isPlaying) getString(R.string.playing_status) else getString(R.string.paused_status))
            .setContentIntent(openIntent)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setColor(ContextCompat.getColor(this, R.color.purple_500))
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mediaSession.sessionToken)
                .setShowActionsInCompactView(0, 1))
            .addAction(
                if (isPlaying) R.drawable.ic_notif_pause else R.drawable.ic_notif_play,
                if (isPlaying) getString(R.string.pause) else getString(R.string.play), playPauseIntent)
            .addAction(R.drawable.ic_notif_next, getString(R.string.next), nextIntent)
            .build()
    }

    private fun updateNotification() {
        updateMediaSessionState()
        try { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, buildNotification()) } catch (_: Exception) {}
    }

    private fun updateMediaSessionState() {
        val isPlaying = isActuallyPlaying()
        val (cur, total) = computeProgress()
        
        val stateBuilder = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_STOP
            )
            .setState(
                if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                mediaPlayer?.currentPosition?.toLong() ?: 0L,
                1.0f
            )
        
        mediaSession.setPlaybackState(stateBuilder.build())

        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, if (title.isEmpty()) getString(R.string.news_default_title) else title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, getString(R.string.news_progress_format, cur, total))
            // .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, mediaPlayer?.duration?.toLong() ?: -1L)
        
        mediaSession.setMetadata(metadataBuilder.build())
    }

    private fun pendingFlag(): Int = PendingIntent.FLAG_IMMUTABLE
}