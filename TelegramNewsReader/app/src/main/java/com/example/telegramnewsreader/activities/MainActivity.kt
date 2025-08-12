package com.example.telegramnewsreader.activities

import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaPlayer
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.telegramnewsreader.R
import com.example.telegramnewsreader.adapter.ChannelAdapter
import com.example.telegramnewsreader.databinding.ActivityMainBinding
import com.example.telegramnewsreader.model.Channel
import com.example.telegramnewsreader.service.NewsService
import com.example.telegramnewsreader.telegram.TelegramClient
import com.example.telegramnewsreader.telegram.TelegramClientManager
import com.example.telegramnewsreader.tts.TTSManager
import com.example.telegramnewsreader.tts.TTSManagerSingleton
import com.example.telegramnewsreader.utils.PreferenceManager
import kotlinx.coroutines.launch
import java.io.File
import com.example.telegramnewsreader.activities.VoiceSelectionActivity
import com.example.telegramnewsreader.utils.TTSDebugTracker

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

    private val pendingPhotos = mutableMapOf<Long, String>()

    private val timePeriods = arrayOf(
        "Последние 10 минут",
        "Последние 30 минут",
        "Последний час",
        "Последние 2 часа",
        "Последние 4 часа",
        "Последние 8 часов",
        "Последние 15 часов"
    )
    private val timeValues = arrayOf(0.166, 0.5, 1.0, 2.0, 4.0, 8.0, 15.0)

    // Приём прогресса из AudioPlayerService
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
                binding.tvPlayingInfo.text = text
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!PreferenceManager.isAuthorized(this)) {
            startActivity(Intent(this, AuthActivity::class.java))
            finish()
            return
        }

        initComponents()
        setupUI()
        expandSpinnerPopupToFullWidth()
        setupClickListeners()
        initializeTelegramClient()

        lastUsedVoice = PreferenceManager.getTtsVoiceName(this)
        Log.d("MainActivity", "onCreate: начальный голос = $lastUsedVoice")
    }

    private fun initComponents() {
        Log.d("MainActivity", "INIT: TelegramClient initialization")
        telegramClient = TelegramClientManager.getTelegramClient(this)

        ttsManager = TTSManagerSingleton.getInstance(this)
        newsService = NewsService(telegramClient, ttsManager)

        channelAdapter = ChannelAdapter(
            onSelectionChanged = { _, _ -> updateNewsCollectionButton() },
            onHideRequest = { channel -> confirmHideChannel(channel) }
        )
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
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, timePeriods)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerTime.adapter = adapter

        binding.btnCollectNews.setOnClickListener { collectNews() }

        findViewById<View?>(R.id.btn_manage_hidden)?.setOnClickListener { showHiddenManager() }

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

        findViewById<View?>(R.id.btn_stop)?.setOnClickListener {
            startService(
                Intent(this, com.example.telegramnewsreader.services.AudioPlayerService::class.java)
                    .setAction(com.example.telegramnewsreader.services.AudioPlayerService.ACTION_STOP)
            )
            resetPlayerButtons()
            binding.btnPlay.isEnabled = false
            findViewById<View?>(R.id.btn_next)?.isEnabled = false
            try {
                binding.llPlayer.visibility = View.GONE
            } catch (_: Exception) {
                binding.btnPlay.visibility = View.GONE
                binding.btnPause.visibility = View.GONE
            }
            // очистим строку прогресса
            binding.tvPlayingInfo.text = ""
        }

        findViewById<View?>(R.id.btn_next)?.setOnClickListener {
            startService(
                Intent(this, com.example.telegramnewsreader.services.AudioPlayerService::class.java)
                    .setAction(com.example.telegramnewsreader.services.AudioPlayerService.ACTION_NEXT)
            )
        }

        binding.btnResetAuth.setOnClickListener {
            Log.d("MainActivity", "RESET AUTH: requested")
            binding.btnResetAuth.isEnabled = false
            TelegramClientManager.logoutAndClearDb(this) {
                PreferenceManager.clearAll(this)
                TTSManagerSingleton.clearInstance()
                Log.d("MainActivity", "RESET AUTH: completed, opening AuthActivity")
                Toast.makeText(this, "Авторизация сброшена", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, AuthActivity::class.java))
                finish()
            }
        }

        binding.btnCollectNews.isEnabled = false

        try {
            binding.llPlayer.visibility = View.GONE
        } catch (_: Exception) {
            binding.btnPlay.visibility = View.GONE
            binding.btnPause.visibility = View.GONE
        }

        binding.btnPlay.isEnabled = false
        findViewById<View?>(R.id.btn_next)?.isEnabled = false
        binding.btnPause.isEnabled = false
    }

    private fun setupClickListeners() {
        binding.btnOpenSettings.setOnClickListener {
            Log.d("MainActivity", "🔧 Открываем настройки TTS")
            openVoiceSettings()
        }
    }

    // Растягиваем выпадающий список спиннера на всю ширину экрана
    private fun expandSpinnerPopupToFullWidth() {
        val sp = binding.spinnerTime
        sp.post {
            try {
                val screenWidth = resources.displayMetrics.widthPixels
                val location = IntArray(2)
                sp.getLocationOnScreen(location)
                val leftOnScreen = location[0]
                sp.dropDownHorizontalOffset = -leftOnScreen
                sp.dropDownWidth = screenWidth
            } catch (e: Exception) {
                Log.w("MainActivity", "expandSpinnerPopupToFullWidth failed", e)
            }
        }
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
                } else {
                    updateStatus("Каналы не найдены")
                    val testChannels = listOf(
                        Channel(id = 1, accessHash = 0, title = "Test Channel 1", username = "", isSelected = false),
                        Channel(id = 2, accessHash = 0, title = "Test Channel 2", username = "", isSelected = false)
                    )
                    channelAdapter.updateChannels(testChannels)
                    updateChannelStats()
                    updateStatus("Тестовые каналы загружены")
                }
            }
        }
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

        val timeHours = timeValues[binding.spinnerTime.selectedItemPosition]
        Log.d("MainActivity", "Начинаем сбор новостей: каналов=${selectedChannels.size}, период=${timeHours}ч")

        resetCollectionState()

        binding.progressBar.visibility = View.VISIBLE
        binding.btnCollectNews.isEnabled = false

        lifecycleScope.launch {
            try {
                updateStatus("Собираем новости из ${selectedChannels.size} каналов...")

                val audio = newsService.collectAndSynthesizeWithChapters(
                    channels = selectedChannels,
                    timeHours = timeHours
                )

                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    binding.btnCollectNews.isEnabled = true

                    if (audio != null) {
                        currentPlaylist = listOf(audio.file)
                        currentChapters = audio.chaptersMs
                        lastUsedVoice = PreferenceManager.getTtsVoiceName(this@MainActivity)

                        val totalMessages = selectedChannels.sumOf { it.newMessagesCount }

                        val durationMin = try {
                            val player = MediaPlayer().apply {
                                setDataSource(audio.file.absolutePath)
                                prepare()
                            }
                            val minutes = player.duration / 1000 / 60
                            player.release()
                            minutes
                        } catch (e: Exception) {
                            Log.w("MainActivity", "Не удалось определить длительность аудио", e)
                            null
                        }

                        val baseStatus = "Готово! Обработано сообщений: $totalMessages"
                        if (durationMin != null) {
                            updateStatus("$baseStatus\nПримерная длительность: ~${durationMin} минут")
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
                        findViewById<View?>(R.id.btn_next)?.isEnabled = true

                        // очищаем прошлую строку прогресса — сервис пришлёт актуальную
                        binding.tvPlayingInfo.text = ""

                        Toast.makeText(
                            this@MainActivity,
                            "Найдено $totalMessages новых сообщений",
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
                Log.e("MainActivity", "Ошибка при сборе новостей", e)
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    binding.btnCollectNews.isEnabled = true
                    updateStatus("Ошибка при обработке новостей: ${e.message}")
                    Toast.makeText(this@MainActivity, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun resetCollectionState() {
        startService(
            Intent(this, com.example.telegramnewsreader.services.AudioPlayerService::class.java)
                .setAction(com.example.telegramnewsreader.services.AudioPlayerService.ACTION_STOP)
        )

        currentPlaylist = emptyList()
        currentChapters = emptyList()

        try {
            binding.llPlayer.visibility = View.GONE
        } catch (_: Exception) {
            binding.btnPlay.visibility = View.GONE
            binding.btnPause.visibility = View.GONE
        }
        binding.btnPlay.isEnabled = false
        findViewById<View?>(R.id.btn_next)?.isEnabled = false
        binding.btnPause.isEnabled = false

        binding.tvPlayingInfo.text = ""

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
            binding.tvStatus.text = message
            Log.d("MainActivity", "Status: $message")
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

        updateStatus(statusText)
    }

    override fun onResume() {
        super.onResume()
        expandSpinnerPopupToFullWidth()
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
        registerReceiver(
            progressReceiver,
            IntentFilter(com.example.telegramnewsreader.services.AudioPlayerService.ACTION_PROGRESS)
        )
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(progressReceiver)
        } catch (_: Exception) { }
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
}