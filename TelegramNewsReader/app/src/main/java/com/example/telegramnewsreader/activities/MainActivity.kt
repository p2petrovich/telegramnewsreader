package com.example.telegramnewsreader.activities

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.telegramnewsreader.R
import com.example.telegramnewsreader.adapter.ChannelAdapter
import com.example.telegramnewsreader.databinding.ActivityMainBinding
import com.example.telegramnewsreader.model.Channel
import com.example.telegramnewsreader.service.NewsService
import com.example.telegramnewsreader.service.ProgressCallback
import com.example.telegramnewsreader.telegram.TelegramClient
import com.example.telegramnewsreader.telegram.TelegramClientManager
import com.example.telegramnewsreader.tts.TTSManager
import com.example.telegramnewsreader.tts.TTSManagerSingleton
import com.example.telegramnewsreader.utils.PreferenceManager
import com.example.telegramnewsreader.utils.TTSDebugTracker
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import androidx.lifecycle.Observer
import kotlinx.coroutines.CancellationException

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var telegramClient: TelegramClient
    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var ttsManager: TTSManager
    private lateinit var newsService: NewsService

    private var lastUsedVoice: String? = null
    private var isClientReady = false
    // Новое: Observer для отслеживания загрузки каналов
    private val channelsLoadedObserver = Observer<Boolean> { areLoaded ->
        // Этот observer может быть использован для других целей, если нужно
        Log.d("MainActivity", "Channels loaded observer triggered: areLoaded=$areLoaded")
    }

    private var currentPlaylist: List<File> = emptyList()
    private var currentChapters: List<Long> = emptyList()
    private var savedDurationInfo: String? = null

    private val pendingPhotos = mutableMapOf<Long, String>()

    private var progressHandler: Handler? = null
    private var progressRunnable: Runnable? = null
    private var progressExecutor: ScheduledExecutorService? = null
    private var estimatedTimeRemaining: Long = 0
    private var startTime: Long = 0
    private var totalProgressSteps: Int = 0
    private var currentProgressStep: Int = 0
    // Добавьте в объявление класса, рядом с другими переменными:
    private var newsCollectionJob: Job? = null

    companion object {
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1001
        private const val PROGRESS_UPDATE_INTERVAL_MS = 500
    }

    private val timePeriods = arrayOf(
        "10 минут",
        "20 минут",
        "30 минут",
        "1 час",
        "2 часа",
        "3 часа",
        "6 часов",
        "12 часов",
        "24 часа"
    )
    private val timeValues = arrayOf(0.16, 0.33, 0.5, 1.0, 2.0, 3.0, 6.0, 12.0, 24.0)

    private var currentTimePeriodIndex = 2

    // Переменные для отслеживания прогресса
    private var totalNewsToProcess = 0
    private var processedNewsCount = 0
    private var filteredNewsCount = 0
    private var synthesizedNewsCount = 0

    // Переменные для сохранения значений счетчиков
    private var lastTotalCollected = 0
    private var lastTotalFiltered = 0

    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            if (intent.action == com.example.telegramnewsreader.services.AudioPlayerService.ACTION_PROGRESS) {
                val cur = intent.getIntExtra(com.example.telegramnewsreader.services.AudioPlayerService.EXTRA_CURRENT_ITEM, 0)
                val total = intent.getIntExtra(com.example.telegramnewsreader.services.AudioPlayerService.EXTRA_TOTAL_ITEMS, 0)
                val isPlaying = intent.getBooleanExtra(com.example.telegramnewsreader.services.AudioPlayerService.EXTRA_IS_PLAYING, false)
                val text = if (total > 0 && cur in 1..total) {
                    if (isPlaying) "Воспроизводится: $cur из $total" else "Готово к воспроизведению: $cur из $total"
                } else {
                    ""
                }

                // Если есть сохраненная информация о длительности, добавляем её
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

        // Настройка Toolbar как ActionBar
        setSupportActionBar(binding.toolbar)

        // Обработчик кнопки настроек
        binding.btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        // Включаем скролл для TextView статуса
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
        setupClickListeners()
        initializeTelegramClient()

        lastUsedVoice = PreferenceManager.getTtsVoiceName(this)
        Log.d("MainActivity", "onCreate: начальный голос = $lastUsedVoice")

        // Скрываем панели прогресса по умолчанию
        //hideProgressPanels()
    }

    private fun hideProgressPanels() {
        binding.cardCollectionProgress.visibility = View.GONE
        binding.cardNewsPreview.visibility = View.GONE
        binding.cardChannelProgress.visibility = View.GONE
    }

    private fun showProgressPanels() {
        binding.cardCollectionProgress.visibility = View.VISIBLE
        binding.cardNewsPreview.visibility = View.VISIBLE
        binding.cardChannelProgress.visibility = View.VISIBLE
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED) {

                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_REQUEST_CODE
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            NOTIFICATION_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d("MainActivity", "Разрешение на уведомления получено")
                } else {
                    Log.d("MainActivity", "Разрешение на уведомления отклонено")
                }
            }
        }
    }

    private fun initComponents() {
        Log.d("MainActivity", "INIT: TelegramClient initialization")
        telegramClient = TelegramClientManager.getTelegramClient(this)

        ttsManager = TTSManagerSingleton.getInstance(this)
        newsService = NewsService(telegramClient, ttsManager)

        channelAdapter = ChannelAdapter(
            this,
            onSelectionChanged = { _, _ -> updateNewsCollectionButton() },
            onHideRequest = { channel -> confirmHideChannel(channel)
            })
        Log.d("MainActivity", "adapter instance=${System.identityHashCode(channelAdapter)}")

        binding.recyclerChannels.layoutManager = LinearLayoutManager(this)
        binding.recyclerChannels.adapter = channelAdapter
    }

    private fun initializeTelegramClient() {
        telegramClient.onClientReady = {
            Log.d("MainActivity", "CLIENT READY: TelegramClient готов")
            isClientReady = true
            runOnUiThread {
                telegramClient.onChannelPhotoUpdated = { channelId, path ->
                    Log.d("MainActivity", "onChannelPhotoUpdated: id=$channelId path=$path")
                    runOnUiThread {
                        val all = channelAdapter.getAllChannels()
                        val idx = all.indexOfFirst { it.id == channelId }
                        Log.i("MainActivity", "photo apply: id=$channelId idx=$idx size=${all.size} adapter=${System.identityHashCode(channelAdapter)}")
                        if (idx >= 0) {
                            channelAdapter.updateChannelPhoto(channelId, path)
                        } else {
                            pendingPhotos[channelId] = path
                            Log.d("MainActivity", "photo buffered: id=$channelId pending=${pendingPhotos.size}")
                        }
                    }
                }

                loadChannels()
                updateUIForReadyClient()
            }
        }

        if (telegramClient.checkAuthState()) {
            Log.d("MainActivity", "CLIENT READY: Клиент уже готов")
            isClientReady = true

            telegramClient.onChannelPhotoUpdated = { channelId, path ->
                Log.d("MainActivity", "onChannelPhotoUpdated: id=$channelId path=$path")
                runOnUiThread {
                    val all = channelAdapter.getAllChannels()
                    val idx = all.indexOfFirst { it.id == channelId }
                    Log.i("MainActivity", "photo apply: id=$channelId idx=$idx size=${all.size} adapter=${System.identityHashCode(channelAdapter)}")
                    if (idx >= 0) {
                        channelAdapter.updateChannelPhoto(channelId, path)
                    } else {
                        pendingPhotos[channelId] = path
                        Log.d("MainActivity", "photo buffered: id=$channelId pending=${pendingPhotos.size}")
                    }
                }
            }

            loadChannels()
            updateUIForReadyClient()
        } else {
            Log.d("MainActivity", "CLIENT INIT: Ожидаем готовности клиента...")
            updateStatus("Инициализация Telegram клиента...")
        }
    }

    private fun updateUIForReadyClient() {
        binding.btnCollectNews.isEnabled = channelAdapter.getSelectedChannels().isNotEmpty()
        updateStatus("Клиент готов. Выберите каналы для сбора новостей.")
    }

    private fun setupUI() {
        binding.btnTimePeriod.setOnClickListener {
            showTimePeriodDialog()
        }
        updateTimePeriodButton()

        binding.btnCollectNews.setOnClickListener { collectNews() }

        binding.btnPlay.setOnClickListener {
            if (currentPlaylist.isEmpty()) {
                Toast.makeText(this, "Сначала соберите новости", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startService(
                Intent(this, com.example.telegramnewsreader.services.AudioPlayerService::class.java)
                    .setAction(com.example.telegramnewsreader.services.AudioPlayerService.ACTION_PLAY)
            )
            updatePlayerButtons(isPlaying = true)
        }

        binding.btnPause.setOnClickListener {
            startService(
                Intent(this, com.example.telegramnewsreader.services.AudioPlayerService::class.java)
                    .setAction(com.example.telegramnewsreader.services.AudioPlayerService.ACTION_PAUSE)
            )
            updatePlayerButtons(isPlaying = false)
        }

        binding.btnStop.setOnClickListener {
            startService(
                Intent(this, com.example.telegramnewsreader.services.AudioPlayerService::class.java)
                    .setAction(com.example.telegramnewsreader.services.AudioPlayerService.ACTION_STOP)
            )
            resetPlayerButtons()
            binding.btnPlay.isEnabled = false
            binding.btnNext.isEnabled = false
            try {
                binding.llPlayer.visibility = View.GONE
            } catch (_: Exception) {
                binding.btnPlay.visibility = View.GONE
                binding.btnPause.visibility = View.GONE
            }
            binding.tvStatus.text = ""
            savedDurationInfo = null
        }

        binding.btnNext.setOnClickListener {
            startService(
                Intent(this, com.example.telegramnewsreader.services.AudioPlayerService::class.java)
                    .setAction(com.example.telegramnewsreader.services.AudioPlayerService.ACTION_NEXT)
            )
        }

        binding.btnCollectNews.isEnabled = false

        try {
            binding.llPlayer.visibility = View.GONE
        } catch (_: Exception) {
            binding.btnPlay.visibility = View.GONE
            binding.btnPause.visibility = View.GONE
        }

        binding.btnPlay.isEnabled = false
        binding.btnNext.isEnabled = false
        binding.btnPause.isEnabled = false
    }

    private fun setupClickListeners() {
        // Все обработчики теперь в setupUI
    }

    private fun openVoiceSettings() {
        val intent = Intent(this, VoiceSelectionActivity::class.java)
        startActivity(intent)
        Log.d("MainActivity", "🎯 VoiceSelectionActivity запущена")
    }

    private fun loadChannels() {
        if (!isClientReady) {
            Log.w("MainActivity", "Попытка загрузки каналов до готовности клиента")
            return
        }

        binding.progressBar.visibility = View.VISIBLE
        updateStatus("Загружаем каналы...")

        telegramClient.loadChannels { channels ->
            runOnUiThread {
                binding.progressBar.visibility = View.GONE
                if (channels.isNotEmpty()) {
                    val hiddenUsernames = PreferenceManager.getHiddenUsernames(this)
                    val hiddenIds = PreferenceManager.getHiddenIds(this)
                    val filtered = channels.filterNot { ch ->
                        val byUsername = !ch.username.isNullOrBlank() && hiddenUsernames.contains(ch.username!!)
                        val byId = hiddenIds.contains(ch.id.toString())
                        byUsername || byId
                    }

                    channelAdapter.updateChannels(filtered)

                    telegramClient.redownloadPendingPhotos()

                    if (pendingPhotos.isNotEmpty()) {
                        Log.d("MainActivity", "apply pending photos: ${pendingPhotos.size}")
                        pendingPhotos.forEach { (id, p) ->
                            Log.d("MainActivity", "apply pending -> id=$id")
                            channelAdapter.updateChannelPhoto(id, p)
                        }
                        pendingPhotos.clear()
                    } else {
                        Log.d("MainActivity", "no pending photos to apply")
                    }

                    updateChannelStats()
                    Log.d("MainActivity", "Загружено каналов: ${filtered.size}")

                    // 🔥 Вместо прямого вызова, проверяем, загружены ли каналы
                    // БЫЛО: loadInitialNewsForChannels(filtered)
                    // СТАЛО:
                    waitForChannelsAndLoadNews(filtered) // <<< ИЗМЕНЕНО ЗДЕСЬ

                } else {
                    updateStatus("Каналы не найдены")
                    val testChannels = listOf(
                        Channel(id = 1, accessHash = 0, title = "Test Channel 1", username = "", isSelected = false),
                        Channel(id = 2, accessHash = 0, title = "Test Channel 2", username = "", isSelected = false)
                    )
                    channelAdapter.updateChannels(testChannels)
                    updateChannelStats()
                    updateStatus("Тестовые каналы загружены")

                    // 🔥 Вместо прямого вызова, проверяем, загружены ли каналы
                    // БЫЛО: loadInitialNewsForChannels(testChannels)
                    // СТАЛО:
                    waitForChannelsAndLoadNews(testChannels) // <<< ИЗМЕНЕНО ЗДЕСЬ
                }
            }
        }
    }

    // 🔥 НОВОЕ: Метод для загрузки начального количества новостей для всех каналов
    private fun loadInitialNewsForChannels(channels: List<Channel>) {
        if (!isClientReady) {
            Log.w("MainActivity", "Клиент еще не готов для загрузки начального количества новостей")
            return
        }

        lifecycleScope.launch {
            try {
                // Используем период по умолчанию (30 минут = 0.5 часа)
                val defaultTimeHours = 0.5

                updateStatus("Загружаем количество новостей...")

                // Получаем количество новостей для каждого канала
                val newsCounts = newsService.getAllChannelsNewsCount(channels, defaultTimeHours)

                // Обновляем количество новостей для каждого канала
                channels.forEach { channel ->
                    val count = newsCounts[channel.id] ?: 0
                    channel.newMessagesCount = count
                }

                // Обновляем отображение в адаптере
                runOnUiThread {
                    channelAdapter.notifyDataSetChanged()
                    updateChannelStatsWithNewsCount()
                }

            } catch (e: Exception) {
                Log.e("MainActivity", "Ошибка загрузки начального количества новостей для каналов", e)
                runOnUiThread {
                    updateChannelStatsWithNewsCount()
                }
            }
        }
    }

    // 🔥 НОВОЕ: Метод для обновления статистики с количеством новостей
    private fun updateChannelStatsWithNewsCount() {
        val total = channelAdapter.getAllChannels().size
        val selected = channelAdapter.getSelectedChannels().size

        // Считаем общее количество новостей
        val totalNewsCount = channelAdapter.getAllChannels().sumOf { it.newMessagesCount }

        val channelInfo = if (selected > 0) {
            "Каналов: $total | Выбрано: $selected"
        } else {
            "Каналов: $total"
        }

        val newsInfo = if (totalNewsCount > 0) {
            "Новостей за 30 мин: $totalNewsCount"
        } else {
            "Нет новостей за 30 мин"
        }

        val statusText = if (isClientReady) {
            "Выберите каналы\n$channelInfo\n$newsInfo"
        } else {
            "Инициализация...\n$channelInfo\n$newsInfo"
        }

        // Сохраняем существующую дополнительную информацию (например, длительность)
        val currentText = binding.tvStatus.text.toString()
        if (currentText.contains("Примерная длительность:")) {
            // Извлекаем информацию о длительности
            val lines = currentText.split("\n")
            val durationLine = lines.find { it.contains("Примерная длительность:") }
            if (durationLine != null) {
                updateStatus("$statusText\n$durationLine")
                return
            }
        }

        updateStatus(statusText)
    }

    // Методы для обновления детального прогресса
    private fun updateDetailedProgress(status: String, progress: Int, total: Int) {
        runOnUiThread {
            binding.tvDetailedStatus.text = status
            val percentage = if (total > 0) (progress * 100 / total) else 0
            binding.progressBarDetailed.progress = percentage
            binding.tvProgressPercentage.text = "$percentage%"
            binding.tvProgressPercentage.setTextColor(android.graphics.Color.parseColor("#FFFFFF"))

            // Обновляем цвет прогресса (фиолетовый)
            binding.progressBarDetailed.progressTintList = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#9C27B0")
            )
        }
    }

    private fun updateCounters(collected: Int, filtered: Int, synthesized: Int) {
        runOnUiThread {
            binding.tvCollectedCount.text = "Собрано: $collected"
            binding.tvFilteredCount.text = "Отфильтр.: $filtered"
            binding.tvSynthesizedCount.text = "Озвучено: $synthesized"
        }
    }

    private fun updateNewsPreview(newsList: List<String>) {
        runOnUiThread {
            if (newsList.isEmpty()) {
                binding.tvNewsPreview.text = "Новости еще не собраны..."
                return@runOnUiThread
            }

            val previewText = newsList.take(3).joinToString("\n• ") {
                it.replace(Regex("^\\d{2}:\\d{2}\\s*—\\s*"), "").take(60) + "..."
            }
            binding.tvNewsPreview.text = "• $previewText"
        }
    }

    private fun updateChannelProgress(channels: List<Channel>) {
        runOnUiThread {
            binding.llChannelProgressList.removeAllViews()

            channels.forEach { channel ->
                val progressText = when {
                    channel.newMessagesCount > 0 -> "Новостей: ${channel.newMessagesCount}"
                    else -> "Обработка..."
                }

                val textView = android.widget.TextView(this).apply {
                    text = "${channel.title}: $progressText"
                    textSize = 12f
                    setPadding(0, 4, 0, 4)
                }
                binding.llChannelProgressList.addView(textView)
            }
        }
    }

    private fun updateETA(remainingSeconds: Long) {
        runOnUiThread {
            if (remainingSeconds > 0) {
                val hours = remainingSeconds / 3600
                val minutes = (remainingSeconds % 3600) / 60
                val seconds = remainingSeconds % 60

                val etaText = when {
                    hours > 0 -> "Осталось: $hours ч $minutes мин"
                    minutes > 0 -> "Осталось: $minutes мин $seconds сек"
                    else -> "Осталось: $seconds сек"
                }
                binding.tvEta.text = etaText
            } else {
                binding.tvEta.text = "Осталось: рассчет..."
            }
        }
    }

    private fun startTimer() {
        startTime = System.currentTimeMillis()
        progressExecutor = Executors.newScheduledThreadPool(1)
        progressExecutor?.scheduleAtFixedRate({
            if (totalProgressSteps > 0) {
                val elapsed = (System.currentTimeMillis() - startTime) / 1000
                val estimatedTotal = (elapsed * totalProgressSteps) / currentProgressStep
                val remaining = estimatedTotal - elapsed
                runOnUiThread {
                    updateETA(remaining)
                }
            }
        }, 0, 1, TimeUnit.SECONDS)
    }

    private fun stopTimer() {
        progressExecutor?.shutdown()
        progressExecutor = null
    }

    private fun collectNews() {
        if (!isClientReady || !telegramClient.checkAuthState()) {
            Toast.makeText(this, "Telegram клиент не готов. Попробуйте позже.", Toast.LENGTH_LONG).show()
            Log.e("MainActivity", "Попытка сбора новостей с неготовым клиентом")
            return
        }

        val selectedChannels = channelAdapter.getSelectedChannels()
        if (selectedChannels.isEmpty()) {
            Toast.makeText(this, "Выберите хотя бы один канал", Toast.LENGTH_SHORT).show()
            return
        }

        // Если уже идет сбор новостей, останавливаем его
        if (newsCollectionJob?.isActive == true) {
            newsCollectionJob?.cancel()
            updateStatus("Сбор новостей остановлен")
            binding.progressBar.visibility = View.GONE
            binding.btnCollectNews.text = "Собрать новости"
            binding.btnCollectNews.isEnabled = true
            stopTimer()
            return
        }

        val timeHours = timeValues[currentTimePeriodIndex]
        Log.d("MainActivity", "Начинаем сбор новостей: каналов=${selectedChannels.size}, период=${timeHours}ч")

        resetCollectionState()

        // Показываем панели прогресса
        showProgressPanels()
        resetProgressCounters()

        binding.progressBar.visibility = View.VISIBLE
        binding.btnCollectNews.text = "Остановить"
        binding.btnCollectNews.isEnabled = true

        // Запускаем таймер для ETA
        startTimer()

        // Запускаем сбор новостей с возможностью отмены
        newsCollectionJob = lifecycleScope.launch {
            // Проверяем, не отменена ли задача сразу после запуска
            //if (!isActive) {
               // Log.d("MainActivity", "Задача была отменена сразу после запуска")
                //return@launch
            //}
            try {
                updateStatus("Собираем новости из ${selectedChannels.size} каналов...")
                updateDetailedProgress("Начинаем сбор новостей...", 0, 100)
                updateChannelProgress(selectedChannels)

                // Вызов метода с callback для получения реального прогресса
                val audio = newsService.collectAndSynthesizeWithChapters(
                    channels = selectedChannels,
                    timeHours = timeHours,
                    progressCallback = object : ProgressCallback {
                        override fun onUpdateProgress(status: String, progress: Int, total: Int) {
                            // Проверяем, не отменена ли задача через Job
                            if (newsCollectionJob?.isActive == false) return

                            runOnUiThread {
                                updateDetailedProgress(status, progress, total)
                                if (total > 0) {
                                    // Обновляем ETAs
                                    val percentage = (progress * 100) / total
                                    val elapsed = (System.currentTimeMillis() - startTime) / 1000
                                    if (elapsed > 0 && percentage > 0) {
                                        val estimatedTotal = (elapsed * 100) / percentage
                                        val remaining = estimatedTotal - elapsed
                                        updateETA(remaining)
                                    }
                                }
                            }
                        }

                        override fun onUpdateCounters(collected: Int, filtered: Int, synthesized: Int) {
                            if (newsCollectionJob?.isActive == false) return

                            runOnUiThread {
                                // Сохраняем значения для последующего использования
                                lastTotalCollected = collected
                                lastTotalFiltered = filtered
                                updateCounters(collected, filtered, synthesized)
                            }
                        }

                        override fun onUpdateNewsPreview(newsList: List<String>) {
                            if (newsCollectionJob?.isActive == false) return

                            runOnUiThread {
                                updateNewsPreview(newsList)
                            }
                        }

                        override fun onUpdateChannelProgress(channels: List<Channel>) {
                            if (newsCollectionJob?.isActive == false) return

                            runOnUiThread {
                                updateChannelProgress(channels)
                            }
                        }

                        override fun onChannelProcessed(channel: Channel, messagesCount: Int) {
                            if (newsCollectionJob?.isActive == false) return

                            runOnUiThread {
                                channel.newMessagesCount = messagesCount
                                updateChannelProgress(selectedChannels)
                            }
                        }

                        override fun onMessageFiltered(originalCount: Int, filteredCount: Int) {
                            if (newsCollectionJob?.isActive == false) return

                            runOnUiThread {
                                // Отфильтровано = оригинальное количество - количество после фильтрации
                                updateCounters(originalCount, originalCount - filteredCount, 0)
                            }
                        }

                        override fun onSynthesisStarted(messageCount: Int) {
                            if (newsCollectionJob?.isActive == false) return

                            runOnUiThread {
                                updateDetailedProgress("Начинаем синтез речи...", 0, 100)
                                totalProgressSteps = messageCount
                                currentProgressStep = 0
                                // Используем сохраненные значения
                                updateCounters(lastTotalCollected, lastTotalFiltered, 0)
                            }
                        }

                        override fun onSynthesisProgress(current: Int, total: Int) {
                            if (newsCollectionJob?.isActive == false) return

                            runOnUiThread {
                                val progress = if (total > 0) (current * 100) / total else 0
                                updateDetailedProgress("Синтез речи: $current/$total", progress, 100)

                                // Обновляем текущий шаг для ETA
                                currentProgressStep = current

                                // Обновляем счетчики с сохраненными значениями "Собрано" и "Отфильтровано"
                                updateCounters(lastTotalCollected, lastTotalFiltered, current)

                                // Обновляем ETAs
                                val elapsed = (System.currentTimeMillis() - startTime) / 1000
                                if (elapsed > 0 && current > 0) {
                                    val estimatedTotal = (elapsed * total) / current
                                    val remaining = estimatedTotal - elapsed
                                    updateETA(remaining)
                                }
                            }
                        }

                        override fun onSynthesisCompleted() {
                            if (newsCollectionJob?.isActive == false) return

                            runOnUiThread {
                                updateDetailedProgress("Синтез завершен", 100, 100)
                                updateETA(0)
                                // Оставляем кнопку активной
                                binding.btnCollectNews.isEnabled = true
                                binding.btnCollectNews.text = "Собрать новости"
                                // Финальное обновление счетчиков
                                updateCounters(lastTotalCollected, lastTotalFiltered, lastTotalCollected)
                            }
                        }
                    }
                )

                stopTimer()

                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    binding.btnCollectNews.text = "Собрать новости"
                    binding.btnCollectNews.isEnabled = true

                    // Добавить это, чтобы обновить прогресс до 100%:
                    updateDetailedProgress("Сбор завершен", 100, 100)
                    updateETA(0)

                    if (audio != null) {
                        currentPlaylist = listOf(audio.file)
                        currentChapters = audio.chaptersMs
                        lastUsedVoice = PreferenceManager.getTtsVoiceName(this@MainActivity)

                        val realNewsCount = audio.realNewsCount

                        val durationMin = try {
                            val player = MediaPlayer().apply {
                                setDataSource(audio.file.absolutePath)
                                prepare()
                            }
                            val minutes = player.duration / 1000 / 60
                            player.release()
                            minutes
                        } catch (e: Exception) {
                            // Проверяем, является ли это отменой задачи
                            if (e is kotlinx.coroutines.CancellationException) {
                                Log.d("MainActivity", "Сбор новостей отменен пользователем")
                                stopTimer()
                                runOnUiThread {
                                    binding.progressBar.visibility = View.GONE
                                    binding.btnCollectNews.text = "Собрать новости"
                                    binding.btnCollectNews.isEnabled = true
                                    updateStatus("Сбор новостей отменен")
                                }
                                // УДАЛИТЕ return@launch - он не нужен здесь
                            } else {
                                Log.e("MainActivity", "Ошибка при сборе новостей", e)
                                stopTimer()
                                runOnUiThread {
                                    binding.progressBar.visibility = View.GONE
                                    binding.btnCollectNews.text = "Собрать новости"
                                    binding.btnCollectNews.isEnabled = true
                                    //hideProgressPanels() // Скрываем панели прогресса при ошибке
                                    updateStatus("Ошибка при обработке новостей: ${e.message}")
                                    Toast.makeText(this@MainActivity, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }

                        val baseStatus = "Готово! Найдено новостей: ${audio.realNewsCount}"
                        if (durationMin != null) {
                            val durationInfo = "Примерная длительность: ~${durationMin} минут"
                            savedDurationInfo = durationInfo  // Сохраняем информацию
                            val fullStatus = "$baseStatus\n$durationInfo"
                            Log.d("MainActivity", "Full status message: $fullStatus")
                            updateStatus(fullStatus)
                        } else {
                            updateStatus(baseStatus)
                        }

                        val paths = arrayListOf(audio.file.absolutePath)
                        val setIntent = Intent(
                            this@MainActivity,
                            com.example.telegramnewsreader.services.AudioPlayerService::class.java
                        ).apply {
                            action = com.example.telegramnewsreader.services.AudioPlayerService.ACTION_SET_PLAYLIST
                            putStringArrayListExtra(
                                com.example.telegramnewsreader.services.AudioPlayerService.EXTRA_FILE_PATHS,
                                paths
                            )
                            putExtra(
                                com.example.telegramnewsreader.services.AudioPlayerService.EXTRA_START_INDEX,
                                0
                            )
                            putExtra(
                                com.example.telegramnewsreader.services.AudioPlayerService.EXTRA_TITLE,
                                "Новости"
                            )
                            putExtra(
                                com.example.telegramnewsreader.services.AudioPlayerService.EXTRA_CHAPTERS,
                                currentChapters.toLongArray()
                            )
                        }
                        startService(setIntent)

                        TTSDebugTracker.trackChannelSwitch("Playlist prepared: files=${paths.size}, chapters=${currentChapters.size}, title='Новости'")

                        channelAdapter.notifyDataSetChanged()

                        showPlayerControls()
                        resetPlayerButtons()
                        binding.btnPlay.isEnabled = true
                        binding.btnNext.isEnabled = true

                        binding.tvStatus.text = ""

                        Toast.makeText(
                            this@MainActivity,
                            "Найдено $realNewsCount новых сообщений",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        updateStatus("Новых новостей не найдено")
                        Toast.makeText(
                            this@MainActivity,
                            "Новые новости не найдены в выбранный период",
                            Toast.LENGTH_LONG
                        ).show()
                        Log.d("MainActivity", "Новости не найдены или аудио не создано")
                    }
                }
            } catch (e: Exception) {
                // Проверяем, является ли это отменой задачи
                if (e is kotlinx.coroutines.CancellationException) {
                    Log.d("MainActivity", "Сбор новостей отменен пользователем")
                    stopTimer()
                    runOnUiThread {
                        binding.progressBar.visibility = View.GONE
                        binding.btnCollectNews.text = "Собрать новости"
                        binding.btnCollectNews.isEnabled = true
                        updateStatus("Сбор новостей отменен")
                    }
                    return@launch
                }

                Log.e("MainActivity", "Ошибка при сборе новостей", e)
                stopTimer()
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    binding.btnCollectNews.text = "Собрать новости"
                    binding.btnCollectNews.isEnabled = true
                    //hideProgressPanels() // Скрываем панели прогресса при ошибке
                    updateStatus("Ошибка при обработке новостей: ${e.message}")
                    Toast.makeText(this@MainActivity, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun resetProgressCounters() {
        totalNewsToProcess = 0
        processedNewsCount = 0
        filteredNewsCount = 0
        synthesizedNewsCount = 0
        updateCounters(0, 0, 0)
        updateNewsPreview(emptyList())
        updateETA(0)
    }

    private fun resetCollectionState() {
        startService(
            Intent(this, com.example.telegramnewsreader.services.AudioPlayerService::class.java)
                .setAction(com.example.telegramnewsreader.services.AudioPlayerService.ACTION_STOP)
        )

        currentPlaylist = emptyList()
        currentChapters = emptyList()
        savedDurationInfo = null  // Очищаем сохраненную информацию

        try {
            binding.llPlayer.visibility = View.GONE
        } catch (_: Exception) {
            binding.btnPlay.visibility = View.GONE
            binding.btnPause.visibility = View.GONE
        }
        binding.btnPlay.isEnabled = false
        binding.btnNext.isEnabled = false
        binding.btnPause.isEnabled = false

        binding.tvStatus.text = ""

        channelAdapter.getAllChannels().forEach { it.newMessagesCount = 0 }
        channelAdapter.notifyDataSetChanged()
    }

    private fun updatePlayerButtons(isPlaying: Boolean) {
        binding.btnPlay.isEnabled = !isPlaying
        binding.btnPause.isEnabled = isPlaying
    }

    private fun resetPlayerButtons() {
        binding.btnPlay.isEnabled = true
        binding.btnPause.isEnabled = false
    }

    private fun updateNewsCollectionButton() {
        val hasSelectedChannels = channelAdapter.getSelectedChannels().isNotEmpty()
        binding.btnCollectNews.isEnabled = hasSelectedChannels && isClientReady
        updateChannelStats()
    }

    private fun showPlayerControls() {
        try {
            binding.llPlayer.visibility = View.VISIBLE
        } catch (_: Exception) {
            binding.btnPlay.visibility = View.VISIBLE
            binding.btnPause.visibility = View.VISIBLE
        }
    }

    private fun updateStatus(message: String) {
        try {
            var finalMessage = message

            // Если есть сохраненная информация о длительности, добавляем её
            if (savedDurationInfo != null && !message.contains("Примерная длительность:")) {
                finalMessage = "$message\n$savedDurationInfo"
            }
            // Если новое сообщение содержит информацию о длительности, сохраняем её
            else if (message.contains("Примерная длительность:")) {
                val durationLine = message.lines().find { it.contains("Примерная длительность:") }
                if (durationLine != null) {
                    savedDurationInfo = durationLine
                    finalMessage = message
                }
            }

            Log.d("MainActivity", "Setting status: $finalMessage")
            Log.d("MainActivity", "Status lines count: ${finalMessage.lines().size}")

            binding.tvStatus.text = finalMessage

            // Принудительно прокручиваем TextView если есть скролл
            binding.tvStatus.movementMethod = ScrollingMovementMethod.getInstance()
        } catch (_: Exception) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateChannelStats() {
        val total = channelAdapter.getAllChannels().size
        val selected = channelAdapter.getSelectedChannels().size

        val message = if (selected > 0) {
            "Каналов: $total | Выбрано: $selected"
        } else {
            "Каналов: $total"
        }

        val statusText = if (isClientReady) {
            "Выберите каналы\n$message"
        } else {
            "Инициализация...\n$message"
        }

        // Сохраняем дополнительную информацию (новости, длительность и т.д.)
        val currentText = binding.tvStatus.text.toString()

        // Ищем дополнительную информацию, которую нужно сохранить
        val additionalInfoLines = mutableListOf<String>()

        // Проверяем наличие информации о длительности
        val durationLine = currentText.lines().find { it.contains("Примерная длительность:") }
        if (durationLine != null) {
            additionalInfoLines.add(durationLine)
        }

        // Проверяем наличие информации о новостях
        val newsInfoLines = currentText.lines().find { it.contains("Новостей за 30 мин:") || it.contains("Нет новостей за 30 мин") }
        if (newsInfoLines != null) {
            additionalInfoLines.add(0, newsInfoLines) // Добавляем в начало
        }

        if (additionalInfoLines.isNotEmpty()) {
            binding.tvStatus.text = "$statusText\n${additionalInfoLines.joinToString("\n")}"
        } else {
            binding.tvStatus.text = statusText
        }
    }

    override fun onResume() {
        super.onResume()
        if (::ttsManager.isInitialized) {
            val currentVoice = PreferenceManager.getTtsVoiceName(this)

            if (lastUsedVoice != null && lastUsedVoice != currentVoice) {
                updateStatus("Голос изменен. Пересоберите новости для применения нового голоса.")
                Toast.makeText(
                    this,
                    "Голос изменен. Нажмите 'Собрать новости' для применения.",
                    Toast.LENGTH_LONG
                ).show()
            }

            ttsManager.refreshVoice()
            Log.d("MainActivity", "onResume(): голос обновлен. Текущий: $currentVoice")
        }
    }

    override fun onStart() {
        super.onStart()
        val intentFilter = IntentFilter(com.example.telegramnewsreader.services.AudioPlayerService.ACTION_PROGRESS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.registerReceiver(
                this,
                progressReceiver,
                intentFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } else {
            ContextCompat.registerReceiver(
                this,
                progressReceiver,
                intentFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(progressReceiver)
        } catch (_: Exception) { }
        stopTimer()
    }

    private fun confirmHideChannel(channel: Channel) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Скрыть канал")
            .setMessage("Скрыть «${channel.title}» из списка?")
            .setPositiveButton("Скрыть") { _, _ -> hideChannel(channel) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun hideChannel(channel: Channel) {
        val hiddenUsernames = PreferenceManager.getHiddenUsernames(this)
        val hiddenIds = PreferenceManager.getHiddenIds(this)

        if (!channel.username.isNullOrBlank()) {
            hiddenUsernames.add(channel.username!!)
            PreferenceManager.saveHiddenUsernames(this, hiddenUsernames)
        } else {
            hiddenIds.add(channel.id.toString())
            PreferenceManager.saveHiddenIds(this, hiddenIds)
            PreferenceManager.saveHiddenTitleForId(this, channel.id, channel.title)
        }

        val current = channelAdapter.getAllChannels()
        val updated = current.filterNot { it.id == channel.id }
        channelAdapter.updateChannels(updated)
        updateChannelStats()
        Toast.makeText(this, "Канал скрыт", Toast.LENGTH_SHORT).show()
    }

    private fun showHiddenManager() {
        val hiddenUsernames = PreferenceManager.getHiddenUsernames(this)
        val hiddenIds = PreferenceManager.getHiddenIds(this)

        val items = mutableListOf<String>()
        val meta = mutableListOf<Pair<String, String>>()

        hiddenUsernames.forEach { u ->
            items.add("@$u")
            meta.add("u" to u)
        }

        hiddenIds.forEach { idStr ->
            val id = idStr.toLongOrNull()
            val title = id?.let { PreferenceManager.getHiddenTitleForId(this, it) }
            val label = if (!title.isNullOrBlank()) title else "Канал"
            items.add(label)
            meta.add("i" to idStr)
        }

        if (items.isEmpty()) {
            Toast.makeText(this, "Скрытых каналов нет", Toast.LENGTH_SHORT).show()
            return
        }

        val checked = BooleanArray(items.size) { false }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Скрытые каналы")
            .setMultiChoiceItems(items.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("Вернуть выбранные") { _, _ ->
                val toRestoreUsernames = mutableSetOf<String>()
                val toRestoreIds = mutableSetOf<String>()

                meta.forEachIndexed { index, (type, key) ->
                    if (checked[index]) {
                        if (type == "u") toRestoreUsernames.add(key) else toRestoreIds.add(key)
                    }
                }

                if (toRestoreUsernames.isEmpty() && toRestoreIds.isEmpty()) return@setPositiveButton

                val newHiddenUsernames = hiddenUsernames.apply { removeAll(toRestoreUsernames) }
                val newHiddenIds = hiddenIds.apply { removeAll(toRestoreIds) }
                PreferenceManager.saveHiddenUsernames(this, newHiddenUsernames)
                PreferenceManager.saveHiddenIds(this, newHiddenIds)

                loadChannels()
                Toast.makeText(this, "Выбранные каналы возвращены", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun setDefaultVoiceOnFirstLaunch() {
        val prefs = getPreferences()
        val isFirstLaunch = prefs.getBoolean("is_first_app_launch", true)

        if (isFirstLaunch) {
            val currentVoice = PreferenceManager.getTtsVoiceName(this)
            if (currentVoice == null) {
                PreferenceManager.saveTtsVoiceName(this, "Александр НД сеть")
                Log.d("MainActivity", "🔥 Первый запуск: голос установлен на Александр НД сеть")
            }
            prefs.edit().putBoolean("is_first_app_launch", false).apply()
        } else {
            Log.d("MainActivity", "Обычный запуск: первый запуск уже был")
        }
    }

    private fun getPreferences() = getSharedPreferences("telegram_news_prefs", Context.MODE_PRIVATE)

    private fun showTimePeriodDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Выберите период времени")
            .setItems(timePeriods) { _, which ->
                currentTimePeriodIndex = which
                updateTimePeriodButton()
                Log.d("MainActivity", "Выбран период времени: ${timePeriods[which]}")
            }
            .show()
    }

    private fun updateTimePeriodButton() {
        binding.btnTimePeriod.text = "Период: ${timePeriods[currentTimePeriodIndex]}"
    }

    // 🔥 НОВОЕ: Метод для отображения диалога настроек
    private fun showSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        // Находим кнопки в диалоге
        val btnManageHidden = dialogView.findViewById<Button>(R.id.btn_manage_hidden)
        val btnVoiceSettings = dialogView.findViewById<Button>(R.id.btn_voice_settings)
        val btnResetAuth = dialogView.findViewById<Button>(R.id.btn_reset_auth)

        // Устанавливаем обработчики кликов
        btnManageHidden.setOnClickListener {
            dialog.dismiss()
            showHiddenManager()
        }

        btnVoiceSettings.setOnClickListener {
            dialog.dismiss()
            openVoiceSettings()
        }

        btnResetAuth.setOnClickListener {
            dialog.dismiss()
            showResetAuthConfirmation()
        }

        dialog.show()
    }

    // 🔥 НОВОЕ: Метод для подтверждения сброса авторизации
    private fun showResetAuthConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Сброс авторизации")
            .setMessage("Вы уверены, что хотите сбросить авторизацию? Все данные будут удалены.")
            .setPositiveButton("Да") { _, _ ->
                resetAuthorization()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // 🔥 НОВОЕ: Метод сброса авторизации
    private fun resetAuthorization() {
        Log.d("MainActivity", "RESET AUTH: requested")

        // Блокируем UI во время сброса
        binding.btnCollectNews.isEnabled = false
        updateStatus("Сброс авторизации...")

        TelegramClientManager.logoutAndClearDb(this) {
            PreferenceManager.clearAll(this)
            TTSManagerSingleton.clearInstance()
            Log.d("MainActivity", "RESET AUTH: completed, opening AuthActivity")

            runOnUiThread {
                Toast.makeText(this, "Авторизация сброшена", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, AuthActivity::class.java))
                finish()
            }
        }
    }
    // 🔥 Новый метод: ждет загрузки каналов и вызывает loadInitialNewsForChannels
    private fun waitForChannelsAndLoadNews(channels: List<Channel>) {
        if (!isClientReady) {
            Log.w("MainActivity", "Клиент еще не готов, ждем...")
            return
        }

        if (channels.isNotEmpty()) {
            loadInitialNewsForChannels(channels)
        } else {
            Log.d("MainActivity", "Список каналов пуст, подписываемся на изменения")
            // Здесь можно подписаться на LiveData/Observer, когда каналы будут загружены
            loadInitialNewsForChannels(channels)
        }
    }
}