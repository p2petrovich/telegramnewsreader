package com.example.telegramnewsreader.activities

import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.RadioGroup
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
import com.example.telegramnewsreader.tts.TTSManager
import com.example.telegramnewsreader.tts.TTSManagerSingleton // 🔥 НОВЫЙ ИМПОРТ
import com.example.telegramnewsreader.utils.PreferenceManager
import kotlinx.coroutines.launch
import java.io.File
import com.example.telegramnewsreader.telegram.TelegramClientManager


class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var telegramClient: TelegramClient
    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var ttsManager: TTSManager
    private lateinit var newsService: NewsService
    private var mediaPlayer: MediaPlayer? = null
    private var currentAudioFile: File? = null

    // 🔥 НОВОЕ: для отслеживания изменения голоса
    private var lastUsedVoice: String? = null

    private val timePeriods = arrayOf(
        "Последние 5 минут",
        "Последние 10 минут",
        "Последние 30 минут",
        "Последний час",
        "Последние 2 часа",
        "Последние 4 часа"
    )
    private val timeValues = arrayOf(0.083, 0.166, 0.5, 1.0, 2.0, 4.0)


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

        // ✅ Загрузка каналов будет вызвана только после готовности клиента
        telegramClient.onClientReady = {
            Log.d("MainActivity", "=== INIT TRACKING === TelegramClient is READY")
            runOnUiThread {
                loadChannels()
            }
        }
        if (telegramClient.checkAuthState()) {
            Log.d("MainActivity", "=== INIT TRACKING === TelegramClient уже готов, вызываем loadChannels() напрямую")
            loadChannels()
        }

        // 🔥 НОВОЕ: инициализируем текущий голос
        lastUsedVoice = PreferenceManager.getTtsVoiceName(this)
        Log.d("MainActivity", "🎯 onCreate: начальный голос = $lastUsedVoice")
    }

    private fun initComponents() {
        Log.d("MainActivity", "=== INIT TRACKING === Starting TelegramClient initialization")
        telegramClient = TelegramClientManager.getTelegramClient(this)
        Log.d("MainActivity", "=== INIT TRACKING === TelegramClient object created: ${telegramClient != null}")

        // 🔥 ИЗМЕНЕНИЕ: Используем синглтон TTSManager
        ttsManager = TTSManagerSingleton.getInstance(this)
        Log.d("MainActivity", "=== INIT TRACKING === TTSManager получен из синглтона")

        newsService = NewsService(telegramClient, ttsManager)

        channelAdapter = ChannelAdapter { _, _ ->
            updateNewsCollectionButton()
        }

        binding.recyclerChannels.layoutManager = LinearLayoutManager(this)
        binding.recyclerChannels.adapter = channelAdapter
    }

    private fun setupUI() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, timePeriods)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerTime.adapter = adapter

        binding.btnCollectNews.setOnClickListener {
            collectNews()
        }
        binding.btnOpenSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnPlay.setOnClickListener {
            playAudio()
        }

        binding.btnPause.setOnClickListener {
            pauseAudio()
        }

        binding.btnStop.setOnClickListener {
            stopAudio()
        }
        binding.btnResetAuth.setOnClickListener {
            PreferenceManager.clearAll(this)
            TelegramClientManager.clearClient() // 🧩 ← ВАЖНО: очищаем singleton!
            TTSManagerSingleton.clearInstance() // 🔥 НОВОЕ: очищаем TTS singleton
            Toast.makeText(this, "Авторизация сброшена", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, AuthActivity::class.java))
            finish()
        }

        try {
            binding.llPlayer.visibility = View.GONE
        } catch (e: Exception) {
            binding.btnPlay.visibility = View.GONE
            binding.btnPause.visibility = View.GONE
            binding.btnStop.visibility = View.GONE
        }
    }

    private fun loadChannels() {
        binding.progressBar.visibility = View.VISIBLE
        updateStatus("Загружаем каналы...")

        Log.d("MainActivity", "=== INIT TRACKING === About to call loadChannels on telegramClient")
        Log.d("MainActivity", "=== INIT TRACKING === TelegramClient is null: ${!::telegramClient.isInitialized}")

        if (!::telegramClient.isInitialized) {
            Log.e("MainActivity", "=== INIT TRACKING === TelegramClient not initialized in MainActivity!")
            updateStatus("Ошибка: клиент не инициализирован")
            binding.progressBar.visibility = View.GONE
            return
        }

        telegramClient.loadChannels { channels ->
            runOnUiThread {
                binding.progressBar.visibility = View.GONE
                if (channels.isNotEmpty()) {
                    channelAdapter.updateChannels(channels)
                    updateChannelStats() // ✅ ← ДОБАВЬ ЗДЕСЬ
                } else {
                    updateStatus("Каналы не найдены")

                    // Можно добавить тестовые
                    val testChannels = listOf(
                        Channel(id = 1, accessHash = 0, title = "Test Channel 1", username = "", isSelected = false),
                        Channel(id = 2, accessHash = 0, title = "Test Channel 2", username = "", isSelected = false)
                    )
                    channelAdapter.updateChannels(testChannels)
                    updateChannelStats() // ✅ ← И ТУТ ТОЖЕ
                    updateStatus("Тестовые каналы загружены")
                }
            }
        }

    }

    private fun collectNews() {
        val selectedChannels = channelAdapter.getSelectedChannels()
        if (selectedChannels.isEmpty()) {
            Toast.makeText(this, "Выберите хотя бы один канал", Toast.LENGTH_SHORT).show()
            return
        }

        val timeHours = timeValues[binding.spinnerTime.selectedItemPosition]

        // ✅ ДОБАВЛЕНО: сброс перед сбором
        updateStatus("Собираем новости...")
        currentAudioFile = null
        stopAudio()
        try {
            binding.llPlayer.visibility = View.GONE
        } catch (e: Exception) {
            binding.btnPlay.visibility = View.GONE
            binding.btnPause.visibility = View.GONE
            binding.btnStop.visibility = View.GONE
        }
        selectedChannels.forEach { it.newMessagesCount = 0 }
        channelAdapter.notifyDataSetChanged()

        binding.progressBar.visibility = View.VISIBLE
        binding.btnCollectNews.isEnabled = false

        lifecycleScope.launch {
            try {
                val audioFile = newsService.collectAndProcessNews(
                    channels = selectedChannels,
                    timeHours = timeHours
                )

                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    binding.btnCollectNews.isEnabled = true

                    if (audioFile != null) {
                        currentAudioFile = audioFile
                        // 🔥 НОВОЕ: запоминаем голос, который использовался для создания аудио
                        lastUsedVoice = PreferenceManager.getTtsVoiceName(this@MainActivity)
                        Log.d("MainActivity", "🎯 Аудио создано с голосом: $lastUsedVoice")

                        updateStatus("Новости готовы к прослушиванию")
                        channelAdapter.notifyDataSetChanged()
                        showPlayerControls()
                        resetPlayerButtons()
                    } else {
                        updateStatus("Нет новых новостей")
                        Toast.makeText(this@MainActivity, "Новые новости не найдены", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    binding.btnCollectNews.isEnabled = true
                    updateStatus("Ошибка при обработке новостей")
                    Toast.makeText(this@MainActivity, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun playAudio() {
        currentAudioFile?.let { file ->
            try {
                if (mediaPlayer == null) {
                    mediaPlayer = MediaPlayer().apply {
                        setDataSource(file.absolutePath)
                        prepare()
                        setOnCompletionListener {
                            resetPlayerButtons()
                        }
                        setOnErrorListener { _, _, _ ->
                            Toast.makeText(this@MainActivity, "Ошибка воспроизведения", Toast.LENGTH_SHORT).show()
                            resetPlayerButtons()
                            true
                        }
                    }
                }
                mediaPlayer?.start()
                updatePlayerButtons(isPlaying = true)
            } catch (e: Exception) {
                Toast.makeText(this, "Ошибка воспроизведения: ${e.message}", Toast.LENGTH_SHORT).show()
                resetPlayerButtons()
            }
        }
    }

    private fun pauseAudio() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                updatePlayerButtons(isPlaying = false)
            }
        }
    }

    private fun stopAudio() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.stop()
            }
            it.release()
        }
        mediaPlayer = null
        resetPlayerButtons()
    }

    private fun updatePlayerButtons(isPlaying: Boolean) {
        binding.btnPlay.isEnabled = !isPlaying
        binding.btnPause.isEnabled = isPlaying
        binding.btnStop.isEnabled = true
    }

    private fun resetPlayerButtons() {
        binding.btnPlay.isEnabled = true
        binding.btnPause.isEnabled = false
        binding.btnStop.isEnabled = false
    }

    private fun updateNewsCollectionButton() {
        val hasSelectedChannels = channelAdapter.getSelectedChannels().isNotEmpty()
        binding.btnCollectNews.isEnabled = hasSelectedChannels
        updateChannelStats() // ✅ ← ДОБАВЬ ЭТУ СТРОКУ
    }

    private fun showPlayerControls() {
        try {
            binding.llPlayer.visibility = View.VISIBLE
        } catch (e: Exception) {
            binding.btnPlay.visibility = View.VISIBLE
            binding.btnPause.visibility = View.VISIBLE
            binding.btnStop.visibility = View.VISIBLE
        }
    }

    private fun updateStatus(message: String) {
        try {
            binding.tvStatus.text = message
        } catch (e: Exception) {
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

        updateStatus("Выберите каналы\n$message")
    }

    // 🔥 ИСПРАВЛЕННЫЙ: обновляем голос при возобновлении активности + проверяем изменения
    override fun onResume() {
        super.onResume()
        // Применяем актуальные настройки голоса при возврате в активность
        if (::ttsManager.isInitialized) {
            val currentVoice = PreferenceManager.getTtsVoiceName(this)

            // 🔥 КЛЮЧЕВОЕ ИСПРАВЛЕНИЕ: Проверяем, изменился ли голос
            if (lastUsedVoice != null && lastUsedVoice != currentVoice && currentAudioFile != null) {
                Log.d("MainActivity", "🔄 Голос изменился с '$lastUsedVoice' на '$currentVoice' - очищаем старое аудио")

                // Останавливаем воспроизведение
                stopAudio()

                // Удаляем старый аудиофайл
                currentAudioFile?.delete()
                currentAudioFile = null

                // Скрываем плеер
                try {
                    binding.llPlayer.visibility = View.GONE
                } catch (e: Exception) {
                    binding.btnPlay.visibility = View.GONE
                    binding.btnPause.visibility = View.GONE
                    binding.btnStop.visibility = View.GONE
                }

                // Обновляем статус
                updateStatus("Голос изменен. Пересоберите новости для применения нового голоса.")

                Toast.makeText(this, "Голос изменен. Нажмите 'Собрать новости' для применения.", Toast.LENGTH_LONG).show()
            }

            // Обновляем голос в TTS
            ttsManager.refreshVoice()
            Log.d("MainActivity", "🔄 onResume(): голос обновлен из настроек. Текущий: $currentVoice")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        // 🔥 ИЗМЕНЕНИЕ: не shutdown синглтон в onDestroy
        // if (::ttsManager.isInitialized) {
        //     ttsManager.shutdown()
        // }
    }

    override fun onPause() {
        super.onPause()
        if (mediaPlayer?.isPlaying == true) {
            pauseAudio()
        }
    }
}