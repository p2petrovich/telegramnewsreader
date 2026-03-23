package com.p2petrovich.telegramnewsreader.activities

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.p2petrovich.telegramnewsreader.R
import com.p2petrovich.telegramnewsreader.adapter.ChannelAdapter
import com.p2petrovich.telegramnewsreader.databinding.ActivityMainBinding
import com.p2petrovich.telegramnewsreader.model.Channel
import com.p2petrovich.telegramnewsreader.service.NewsService
import com.p2petrovich.telegramnewsreader.service.ProgressCallback
import com.p2petrovich.telegramnewsreader.telegram.TelegramClient
import com.p2petrovich.telegramnewsreader.telegram.TelegramClientManager
import com.p2petrovich.telegramnewsreader.tts.TTSManager
import com.p2petrovich.telegramnewsreader.tts.TTSManagerSingleton
import com.p2petrovich.telegramnewsreader.utils.PreferenceManager
import com.p2petrovich.telegramnewsreader.services.AudioPlayerService
import com.p2petrovich.telegramnewsreader.utils.NewsCache
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var telegramClient: TelegramClient
    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var ttsManager: TTSManager
    private lateinit var newsService: NewsService

    private var lastUsedVoice: String? = null
    private var isClientReady = false
    private var currentPlaylist: List<File> = emptyList()
    private var currentChapters: List<Long> = emptyList()
    private var currentRealNewsCount: Int = 0
    private var currentNewsChapterIndices: Set<Int> = emptySet()
    private var savedDurationInfo: String? = null
    private val pendingPhotos = mutableMapOf<Long, String>()

    private var progressExecutor: ScheduledExecutorService? = null
    private var startTime: Long = 0
    private var totalProgressSteps: Int = 0
    private var currentProgressStep: Int = 0
    private var newsCollectionJob: Job? = null

    private var lastTotalCollected = 0
    private var lastTotalToSynthesize = 0

    companion object {
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1001
    }

    private val timePeriods = arrayOf(
        "10 минут", "20 минут", "30 минут", "1 час", "2 часа",
        "3 часа", "6 часов", "12 часов", "24 часа"
    )
    private val timeValues = arrayOf(0.16, 0.33, 0.5, 1.0, 2.0, 3.0, 6.0, 12.0, 24.0)
    private var currentTimePeriodIndex = 2

    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioPlayerService.ACTION_PROGRESS) {
                val cur = intent.getIntExtra(AudioPlayerService.EXTRA_CURRENT_ITEM, 0)
                val total = intent.getIntExtra(AudioPlayerService.EXTRA_TOTAL_ITEMS, 0)
                val isPlaying = intent.getBooleanExtra(AudioPlayerService.EXTRA_IS_PLAYING, false)

                val text = if (total > 0 && cur in 1..total) {
                    if (isPlaying) "Воспроизводится: $cur из $total" else "Готово: $cur из $total"
                } else ""

                var finalText = text
                if (savedDurationInfo != null && text.isNotEmpty()) {
                    finalText = "$text\n$savedDurationInfo"
                } else if (text.isEmpty() && savedDurationInfo != null) {
                    finalText = savedDurationInfo!!
                }
                binding.tvStatus.text = finalText
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.btnSettings.setOnClickListener { showSettingsDialog() }
        binding.tvStatus.movementMethod = ScrollingMovementMethod.getInstance()

        requestNotificationPermission()
        setDefaultVoiceOnFirstLaunch()

        if (!PreferenceManager.isAuthorized(this)) {
            startActivity(Intent(this, AuthActivity::class.java))
            finish()
            return
        }

        initComponents()
        setupUI()
        initializeTelegramClient()

        lastUsedVoice = PreferenceManager.getTtsVoiceName(this)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST_CODE)
            }
        }
    }

    private fun initComponents() {
        telegramClient = TelegramClientManager.getTelegramClient(this)
        ttsManager = TTSManagerSingleton.getInstance(this)
        newsService = NewsService(telegramClient, ttsManager)

        channelAdapter = ChannelAdapter(
            this,
            onSelectionChanged = { _, _ -> updateNewsCollectionButton() },
            onHideRequest = { channel -> confirmHideChannel(channel) }
        )

        binding.recyclerChannels.layoutManager = LinearLayoutManager(this)
        binding.recyclerChannels.adapter = channelAdapter
    }

    private fun initializeTelegramClient() {
        val readyCallback: () -> Unit = {
            isClientReady = true
            runOnUiThread {
                telegramClient.onChannelPhotoUpdated = { channelId, path ->
                    runOnUiThread {
                        val idx = channelAdapter.getAllChannels().indexOfFirst { it.id == channelId }
                        if (idx >= 0) channelAdapter.updateChannelPhoto(channelId, path)
                        else pendingPhotos[channelId] = path
                    }
                }
                loadChannels()
                updateUIForReadyClient()
            }
        }

        telegramClient.onClientReady = readyCallback

        if (telegramClient.checkAuthState()) {
            readyCallback()
        } else {
            updateStatus("Инициализация Telegram клиента...")
        }
    }

    private fun updateUIForReadyClient() {
        binding.btnCollectNews.isEnabled = channelAdapter.getSelectedChannels().isNotEmpty()
        updateStatus("Клиент готов. Выберите каналы.")
    }

    private fun setupUI() {
        binding.btnTimePeriod.setOnClickListener { showTimePeriodDialog() }
        updateTimePeriodButton()

        binding.btnCollectNews.setOnClickListener { collectNews() }

        binding.btnPlay.setOnClickListener {
            if (currentPlaylist.isEmpty()) {
                Toast.makeText(this, "Сначала соберите новости", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startService(Intent(this, AudioPlayerService::class.java).setAction(AudioPlayerService.ACTION_PLAY))
            updatePlayerButtons(true)
        }

        binding.btnPause.setOnClickListener {
            startService(Intent(this, AudioPlayerService::class.java).setAction(AudioPlayerService.ACTION_PAUSE))
            updatePlayerButtons(false)
        }

        binding.btnStop.setOnClickListener {
            startService(Intent(this, AudioPlayerService::class.java).setAction(AudioPlayerService.ACTION_STOP))
            resetPlayerButtons()
            binding.llPlayer.visibility = View.GONE
            binding.tvStatus.text = ""
            savedDurationInfo = null
        }

        binding.btnNext.setOnClickListener {
            startService(Intent(this, AudioPlayerService::class.java).setAction(AudioPlayerService.ACTION_NEXT))
        }

        binding.btnCollectNews.isEnabled = false
        binding.llPlayer.visibility = View.GONE
        binding.btnPlay.isEnabled = false
        binding.btnNext.isEnabled = false
        binding.btnPause.isEnabled = false
    }

    private fun loadChannels() {
        if (!isClientReady) return

        binding.progressBar.visibility = View.VISIBLE
        updateStatus("Загружаем каналы...")

        telegramClient.loadChannels { channels ->
            runOnUiThread {
                binding.progressBar.visibility = View.GONE
                if (channels.isNotEmpty()) {
                    val hiddenUsernames = PreferenceManager.getHiddenUsernames(this)
                    val hiddenIds = PreferenceManager.getHiddenIds(this)
                    val filtered = channels.filterNot { ch ->
                        (!ch.username.isNullOrBlank() && hiddenUsernames.contains(ch.username!!)) ||
                                hiddenIds.contains(ch.id.toString())
                    }

                    channelAdapter.updateChannels(filtered)
                    telegramClient.redownloadPendingPhotos()

                    if (pendingPhotos.isNotEmpty()) {
                        pendingPhotos.forEach { (id, p) -> channelAdapter.updateChannelPhoto(id, p) }
                        pendingPhotos.clear()
                    }

                    updateChannelStats()
                    loadInitialNewsForChannels(filtered)
                } else {
                    updateStatus("Каналы не найдены")
                }
            }
        }
    }

    private fun loadInitialNewsForChannels(channels: List<Channel>) {
        if (!isClientReady) return

        lifecycleScope.launch {
            try {
                val newsCounts = newsService.getAllChannelsNewsCount(channels, 0.5)
                channels.forEach { it.newMessagesCount = newsCounts[it.id] ?: 0 }
                runOnUiThread { channelAdapter.notifyDataSetChanged() }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error loading initial news count", e)
            }
        }
    }

    private fun collectNews() {
        if (!isClientReady || !telegramClient.checkAuthState()) {
            Toast.makeText(this, "Telegram клиент не готов", Toast.LENGTH_LONG).show()
            return
        }

        val selectedChannels = channelAdapter.getSelectedChannels()
        if (selectedChannels.isEmpty()) {
            Toast.makeText(this, "Выберите хотя бы один канал", Toast.LENGTH_SHORT).show()
            return
        }

        if (newsCollectionJob?.isActive == true) {
            newsCollectionJob?.cancel()
            updateStatus("Сбор новостей остановлен")
            binding.progressBar.visibility = View.GONE
            binding.btnCollectNews.text = getString(R.string.collect_news)
            binding.btnCollectNews.isEnabled = true
            stopTimer()
            return
        }

        val timeHours = timeValues[currentTimePeriodIndex]
        resetCollectionState()
        showProgressPanels()
        resetProgressCounters()

        binding.progressBar.visibility = View.VISIBLE
        binding.btnCollectNews.text = "Остановить"
        binding.btnCollectNews.isEnabled = true
        startTimer()

        newsCollectionJob = lifecycleScope.launch {
            try {
                updateStatus("Собираем новости из ${selectedChannels.size} каналов...")
                updateDetailedProgress("Начинаем сбор новостей...", 0, 100)

                val audio = newsService.collectAndSynthesizeWithChapters(
                    channels = selectedChannels,
                    timeHours = timeHours,
                    progressCallback = createProgressCallback(selectedChannels)
                )

                stopTimer()
                runOnUiThread { handleCollectionResult(audio) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.d("MainActivity", "News collection cancelled")
                stopTimer()
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    binding.btnCollectNews.text = getString(R.string.collect_news)
                    binding.btnCollectNews.isEnabled = true
                    updateStatus("Сбор новостей отменен")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error collecting news", e)
                stopTimer()
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    binding.btnCollectNews.text = getString(R.string.collect_news)
                    binding.btnCollectNews.isEnabled = true
                    updateStatus("Ошибка: ${e.message}")
                    Toast.makeText(this@MainActivity, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun createProgressCallback(selectedChannels: List<Channel>): ProgressCallback {
        return object : ProgressCallback {
            override fun onUpdateProgress(status: String, progress: Int, total: Int) {
                runOnUiThread { updateDetailedProgress(status, progress, total) }
            }
            override fun onUpdateCounters(collected: Int, filtered: Int, synthesized: Int) {
                runOnUiThread {
                    lastTotalCollected = collected
                    lastTotalToSynthesize = filtered
                    updateCounters(collected, filtered, synthesized)
                }
            }
            override fun onUpdateNewsPreview(newsList: List<String>) {
                runOnUiThread { updateNewsPreview(newsList) }
            }
            override fun onUpdateChannelProgress(channels: List<Channel>) {
                runOnUiThread { updateChannelProgress(channels) }
            }
            override fun onChannelProcessed(channel: Channel, messagesCount: Int) {
                runOnUiThread {
                    channel.newMessagesCount = messagesCount
                    updateChannelProgress(selectedChannels)
                }
            }
            override fun onMessageFiltered(originalCount: Int, filteredCount: Int) {
                runOnUiThread {
                    Log.d("MainActivity", "Filter: $originalCount -> $filteredCount")
                }
            }
            override fun onSynthesisStarted(messageCount: Int) {
                runOnUiThread {
                    updateDetailedProgress("Начинаем синтез речи...", 0, 100)
                    totalProgressSteps = messageCount
                    currentProgressStep = 0
                    startTime = System.currentTimeMillis()
                }
            }
            override fun onSynthesisProgress(current: Int, total: Int) {
                runOnUiThread {
                    updateDetailedProgress("Синтез речи: $current из $total", current, total)
                    currentProgressStep = current
                    totalProgressSteps = total
                    updateETA()
                }
            }
            override fun onSynthesisCompleted() {
                runOnUiThread {
                    updateDetailedProgress("Синтез завершен", 100, 100)
                    binding.btnCollectNews.isEnabled = true
                    binding.btnCollectNews.text = getString(R.string.collect_news)
                }
            }
        }
    }

    private fun handleCollectionResult(audio: NewsService.AudioWithChapters?) {
        binding.progressBar.visibility = View.GONE
        binding.btnCollectNews.text = getString(R.string.collect_news)
        binding.btnCollectNews.isEnabled = true
        updateDetailedProgress("Сбор завершен", 100, 100)

        if (audio != null) {
            currentPlaylist = listOf(audio.file)
            currentChapters = audio.chaptersMs
            currentRealNewsCount = audio.realNewsCount
            currentNewsChapterIndices = audio.newsChapterIndices
            lastUsedVoice = PreferenceManager.getTtsVoiceName(this)

            var player: MediaPlayer? = null
            val durationMin = try {
                player = MediaPlayer().apply {
                    setDataSource(audio.file.absolutePath)
                    prepare()
                }
                player.duration / 1000 / 60
            } catch (_: Exception) { null }
            finally {
                try { player?.release() } catch (_: Exception) {}
            }

            val baseStatus = "Готово! Найдено новостей: ${audio.realNewsCount}"
            if (durationMin != null) {
                savedDurationInfo = "Примерная длительность: ~${durationMin} минут"
                updateStatus("$baseStatus\n$savedDurationInfo")
            } else {
                updateStatus(baseStatus)
            }

            val paths = arrayListOf(audio.file.absolutePath)
            startService(Intent(this, AudioPlayerService::class.java).apply {
                action = AudioPlayerService.ACTION_SET_PLAYLIST
                putStringArrayListExtra(AudioPlayerService.EXTRA_FILE_PATHS, paths)
                putExtra(AudioPlayerService.EXTRA_START_INDEX, 0)
                putExtra(AudioPlayerService.EXTRA_TITLE, "Новости")
                putExtra(AudioPlayerService.EXTRA_CHAPTERS, currentChapters.toLongArray())
                putExtra(AudioPlayerService.EXTRA_REAL_NEWS_COUNT, currentRealNewsCount)
                putExtra(AudioPlayerService.EXTRA_NEWS_CHAPTER_INDICES,
                    currentNewsChapterIndices.toIntArray())
            })

            channelAdapter.notifyDataSetChanged()
            binding.llPlayer.visibility = View.VISIBLE
            resetPlayerButtons()
            binding.btnPlay.isEnabled = true
            binding.btnNext.isEnabled = true

            Toast.makeText(this, "Найдено ${audio.realNewsCount} новых сообщений", Toast.LENGTH_SHORT).show()
        } else {
            updateStatus("Новых новостей не найдено")
            Toast.makeText(this, "Новые новости не найдены", Toast.LENGTH_LONG).show()
        }
    }

    // ============ Progress UI helpers ============

    private fun updateDetailedProgress(status: String, progress: Int, total: Int) {
        binding.tvDetailedStatus.text = status
        val percentage = if (total > 0) (progress * 100 / total).coerceIn(0, 100) else 0
        binding.progressBarDetailed.progress = percentage
        binding.tvProgressPercentage.text = "$percentage%"
    }

    private fun updateCounters(collected: Int, toSynthesize: Int, synthesized: Int) {
        binding.tvCollectedCount.text = "Собрано: $collected"
        binding.tvFilteredCount.text = "К озвучке: $toSynthesize"
        binding.tvSynthesizedCount.text = "Озвучено: $synthesized"
    }

    private fun updateNewsPreview(newsList: List<String>) {
        if (newsList.isEmpty()) {
            binding.tvNewsPreview.text = "Новости еще не собраны..."
            return
        }
        val previewText = newsList.take(3).joinToString("\n• ") {
            it.replace(Regex("^\\d{2}:\\d{2}\\s*—\\s*"), "").take(60) + "..."
        }
        binding.tvNewsPreview.text = "• $previewText"
    }

    private fun updateChannelProgress(channels: List<Channel>) {
        binding.llChannelProgressList.removeAllViews()
        channels.forEach { channel ->
            val text = if (channel.newMessagesCount > 0) "Новостей: ${channel.newMessagesCount}" else "Обработка..."
            binding.llChannelProgressList.addView(
                android.widget.TextView(this).apply {
                    this.text = "${channel.title}: $text"
                    textSize = 12f
                    setPadding(0, 4, 0, 4)
                }
            )
        }
    }

    private fun updateETA() {
        val elapsedMs = System.currentTimeMillis() - startTime
        val elapsedSec = elapsedMs / 1000

        if (elapsedSec < 3 || currentProgressStep <= 0 || totalProgressSteps <= 0) {
            binding.tvEta.text = "Осталось: расчёт..."
            return
        }

        val remainingSteps = totalProgressSteps - currentProgressStep

        if (remainingSteps <= 0) {
            binding.tvEta.text = "Осталось: завершение..."
            return
        }

        val msPerStep = elapsedMs.toDouble() / currentProgressStep
        val remainingSec = (msPerStep * remainingSteps / 1000).toLong()

        val etaText = when {
            remainingSec <= 0 -> "Осталось: завершение..."
            remainingSec < 60 -> "Осталось: ~$remainingSec сек"
            remainingSec < 3600 -> {
                val min = remainingSec / 60
                val sec = remainingSec % 60
                "Осталось: ~${min} мин ${sec} сек"
            }
            else -> {
                val hours = remainingSec / 3600
                val min = (remainingSec % 3600) / 60
                "Осталось: ~${hours} ч ${min} мин"
            }
        }

        binding.tvEta.text = etaText
    }

    private fun showProgressPanels() {
        binding.cardCollectionProgress.visibility = View.VISIBLE
        binding.cardNewsPreview.visibility = View.VISIBLE
        binding.cardChannelProgress.visibility = View.VISIBLE
    }

    private fun resetProgressCounters() {
        updateCounters(0, 0, 0)
        updateNewsPreview(emptyList())
        binding.tvEta.text = "Осталось: расчёт..."
    }

    private fun resetCollectionState() {
        startService(Intent(this, AudioPlayerService::class.java).setAction(AudioPlayerService.ACTION_STOP))
        currentPlaylist = emptyList()
        currentChapters = emptyList()
        currentRealNewsCount = 0
        savedDurationInfo = null
        binding.llPlayer.visibility = View.GONE
        binding.btnPlay.isEnabled = false
        binding.btnNext.isEnabled = false
        binding.btnPause.isEnabled = false
        binding.tvStatus.text = ""
        channelAdapter.getAllChannels().forEach { it.newMessagesCount = 0 }
        channelAdapter.notifyDataSetChanged()
    }

    private fun startTimer() {
        startTime = System.currentTimeMillis()
        progressExecutor = Executors.newScheduledThreadPool(1)
        progressExecutor?.scheduleAtFixedRate({
            runOnUiThread { updateETA() }
        }, 0, 1, TimeUnit.SECONDS)
    }

    private fun stopTimer() {
        progressExecutor?.shutdown()
        progressExecutor = null
    }

    // ============ Player buttons ============

    private fun updatePlayerButtons(isPlaying: Boolean) {
        binding.btnPlay.isEnabled = !isPlaying
        binding.btnPause.isEnabled = isPlaying
    }

    private fun resetPlayerButtons() {
        binding.btnPlay.isEnabled = true
        binding.btnPause.isEnabled = false
    }

    private fun updateNewsCollectionButton() {
        binding.btnCollectNews.isEnabled = channelAdapter.getSelectedChannels().isNotEmpty() && isClientReady
        updateChannelStats()
    }

    // ============ Status & stats ============

    private fun updateStatus(message: String) {
        try {
            var finalMessage = message
            if (savedDurationInfo != null && !message.contains("Примерная длительность:")) {
                finalMessage = "$message\n$savedDurationInfo"
            }
            binding.tvStatus.text = finalMessage
        } catch (_: Exception) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateChannelStats() {
        val total = channelAdapter.getAllChannels().size
        val selected = channelAdapter.getSelectedChannels().size
        val msg = if (selected > 0) "Каналов: $total | Выбрано: $selected" else "Каналов: $total"
        val statusText = if (isClientReady) "Выберите каналы\n$msg" else "Инициализация...\n$msg"
        binding.tvStatus.text = statusText
    }

    // ============ Settings & dialogs ============

    private fun showTimePeriodDialog() {
        AlertDialog.Builder(this)
            .setTitle("Выберите период времени")
            .setItems(timePeriods) { _, which ->
                currentTimePeriodIndex = which
                updateTimePeriodButton()
            }.show()
    }

    private fun updateTimePeriodButton() {
        binding.btnTimePeriod.text = "Период: ${timePeriods[currentTimePeriodIndex]}"
    }

    private fun showSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)
        val dialog = AlertDialog.Builder(this).setView(dialogView).create()

        dialogView.findViewById<Button>(R.id.btn_manage_hidden).setOnClickListener {
            dialog.dismiss(); showHiddenManager()
        }
        dialogView.findViewById<Button>(R.id.btn_voice_settings).setOnClickListener {
            dialog.dismiss(); startActivity(Intent(this, VoiceSelectionActivity::class.java))
        }
        dialogView.findViewById<Button>(R.id.btn_clear_cache).setOnClickListener {
            dialog.dismiss()
            val (count, bytes) = NewsCache.getStats(this)
            val sizeMb = bytes / (1024 * 1024)
            AlertDialog.Builder(this)
                .setTitle("Очистить кэш аудио")
                .setMessage("В кэше $count файлов ($sizeMb МБ).\nОчистить?")
                .setPositiveButton("Очистить") { _, _ ->
                    NewsCache.clearAll(this)
                    Toast.makeText(this, "Кэш очищен", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
        dialogView.findViewById<Button>(R.id.btn_reset_auth).setOnClickListener {
            dialog.dismiss(); showResetAuthConfirmation()
        }
        dialogView.findViewById<Button>(R.id.btn_about).setOnClickListener {
            dialog.dismiss(); showAboutDialog()
        }
        dialog.show()
    }

    private fun showAboutDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_about, null)

        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) {
            "1.0"
        }
        dialogView.findViewById<TextView>(R.id.tvVersion).text = "Версия: $versionName"

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Закрыть", null)
            .show()
    }

    private fun showResetAuthConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Сброс авторизации")
            .setMessage("Все данные будут удалены. Продолжить?")
            .setPositiveButton("Да") { _, _ -> resetAuthorization() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun resetAuthorization() {
        binding.btnCollectNews.isEnabled = false
        updateStatus("Сброс авторизации...")

        TelegramClientManager.logoutAndClearDb(this) {
            PreferenceManager.clearAll(this)
            TTSManagerSingleton.clearInstance()
            runOnUiThread {
                Toast.makeText(this, "Авторизация сброшена", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, AuthActivity::class.java))
                finish()
            }
        }
    }

    private fun confirmHideChannel(channel: Channel) {
        AlertDialog.Builder(this)
            .setTitle("Скрыть канал")
            .setMessage("Скрыть «${channel.title}» из списка?")
            .setPositiveButton("Скрыть") { _, _ -> hideChannel(channel) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun hideChannel(channel: Channel) {
        if (!channel.username.isNullOrBlank()) {
            val set = PreferenceManager.getHiddenUsernames(this)
            set.add(channel.username!!)
            PreferenceManager.saveHiddenUsernames(this, set)
        } else {
            val set = PreferenceManager.getHiddenIds(this)
            set.add(channel.id.toString())
            PreferenceManager.saveHiddenIds(this, set)
            PreferenceManager.saveHiddenTitleForId(this, channel.id, channel.title)
        }

        channelAdapter.updateChannels(channelAdapter.getAllChannels().filterNot { it.id == channel.id })
        updateChannelStats()
        Toast.makeText(this, "Канал скрыт", Toast.LENGTH_SHORT).show()
    }

    private fun showHiddenManager() {
        val hiddenUsernames = PreferenceManager.getHiddenUsernames(this)
        val hiddenIds = PreferenceManager.getHiddenIds(this)

        val items = mutableListOf<String>()
        val meta = mutableListOf<Pair<String, String>>()

        hiddenUsernames.forEach { u -> items.add("@$u"); meta.add("u" to u) }
        hiddenIds.forEach { idStr ->
            val title = idStr.toLongOrNull()?.let { PreferenceManager.getHiddenTitleForId(this, it) } ?: "Канал"
            items.add(title); meta.add("i" to idStr)
        }

        if (items.isEmpty()) {
            Toast.makeText(this, "Скрытых каналов нет", Toast.LENGTH_SHORT).show()
            return
        }

        val checked = BooleanArray(items.size)
        AlertDialog.Builder(this)
            .setTitle("Скрытые каналы")
            .setMultiChoiceItems(items.toTypedArray(), checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Вернуть выбранные") { _, _ ->
                val toRestoreU = mutableSetOf<String>()
                val toRestoreI = mutableSetOf<String>()
                meta.forEachIndexed { i, (type, key) ->
                    if (checked[i]) { if (type == "u") toRestoreU.add(key) else toRestoreI.add(key) }
                }
                if (toRestoreU.isEmpty() && toRestoreI.isEmpty()) return@setPositiveButton
                hiddenUsernames.removeAll(toRestoreU)
                hiddenIds.removeAll(toRestoreI)
                PreferenceManager.saveHiddenUsernames(this, hiddenUsernames)
                PreferenceManager.saveHiddenIds(this, hiddenIds)
                loadChannels()
                Toast.makeText(this, "Каналы возвращены", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun setDefaultVoiceOnFirstLaunch() {
        val prefs = getSharedPreferences("telegram_news_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("is_first_app_launch", true)) {
            if (PreferenceManager.getTtsVoiceName(this) == null) {
                PreferenceManager.saveTtsVoiceName(this, "ru-ru-x-ruf-network")
            }
            prefs.edit().putBoolean("is_first_app_launch", false).apply()
        }
    }

    // ============ Lifecycle ============

    override fun onResume() {
        super.onResume()
        if (::ttsManager.isInitialized) {
            val currentVoice = PreferenceManager.getTtsVoiceName(this)
            if (lastUsedVoice != null && lastUsedVoice != currentVoice) {
                updateStatus("Голос изменен. Пересоберите новости.")
            }
            ttsManager.refreshVoice()
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this, progressReceiver,
            IntentFilter(AudioPlayerService.ACTION_PROGRESS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(progressReceiver) } catch (_: Exception) {}
        stopTimer()
    }
}
