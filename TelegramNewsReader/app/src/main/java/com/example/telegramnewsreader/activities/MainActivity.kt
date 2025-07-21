package com.example.telegramnewsreader.activities

import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.telegramnewsreader.R
import com.example.telegramnewsreader.activities.AuthActivity
import com.example.telegramnewsreader.adapter.ChannelAdapter
import com.example.telegramnewsreader.databinding.ActivityMainBinding
import com.example.telegramnewsreader.model.Channel
import com.example.telegramnewsreader.models.TelegramChannel
import com.example.telegramnewsreader.service.NewsService
import com.example.telegramnewsreader.telegram.TelegramClient
import com.example.telegramnewsreader.tts.TTSManager
import com.example.telegramnewsreader.utils.PreferenceManager
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var telegramClient: TelegramClient
    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var ttsManager: TTSManager
    private lateinit var newsService: NewsService
    private var mediaPlayer: MediaPlayer? = null
    private var currentAudioFile: File? = null

    private val timePeriods = arrayOf(
        "Последние 2 часа",
        "Последние 6 часов",
        "Последние 12 часов",
        "Последние сутки"
    )
    private val timeValues = arrayOf(2, 6, 12, 24)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Check authorization
        if (!PreferenceManager.isAuthorized(this)) {
            startActivity(Intent(this, AuthActivity::class.java))
            finish()
            return
        }

        initComponents()
        setupUI()

        // Запускаем загрузку каналов в корутине с задержкой
        lifecycleScope.launch {
            waitForClientAndLoadChannels()
        }
    }

    private fun initComponents() {
        telegramClient = TelegramClient(this)
        ttsManager = TTSManager(this)
        newsService = NewsService(telegramClient, ttsManager)

        // Setup channel adapter
        channelAdapter = ChannelAdapter { channel, isSelected ->
            // Handle channel selection
            updateNewsCollectionButton()
        }
        binding.recyclerChannels.layoutManager = LinearLayoutManager(this)
        binding.recyclerChannels.adapter = channelAdapter
    }

    private fun setupUI() {
        // Setup time spinner
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, timePeriods)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerTime.adapter = adapter

        // Setup voice selection - проверяем существование элементов
        try {
            binding.rbMale.isChecked = true
            binding.rgVoice.setOnCheckedChangeListener { _: RadioGroup, checkedId: Int ->
                val isMale = checkedId == R.id.rb_male
                ttsManager.setVoiceGender(isMale)
            }
        } catch (e: Exception) {
            // Если элементы не найдены, используем мужской голос по умолчанию
            ttsManager.setVoiceGender(true)
        }

        // Setup buttons
        binding.btnCollectNews.setOnClickListener {
            collectNews()
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

        // Initially hide player controls - проверяем существование элемента
        try {
            binding.llPlayer.visibility = View.GONE
        } catch (e: Exception) {
            // Если layoutPlayer не существует, скрываем отдельные кнопки
            binding.btnPlay.visibility = View.GONE
            binding.btnPause.visibility = View.GONE
            binding.btnStop.visibility = View.GONE
        }
    }

    // НОВЫЙ МЕТОД: Ожидание готовности клиента и загрузка каналов
    private suspend fun waitForClientAndLoadChannels() {
        binding.progressBar.visibility = View.VISIBLE
        updateStatus("Подключение к Telegram...")

        // Ждём пока клиент будет готов
        var attempts = 0
        val maxAttempts = 45 // 45 секунд максимум
        var lastAuthState = ""

        while (attempts < maxAttempts) {
            val currentAuthState = telegramClient.getCurrentAuthState()

            // Обновляем статус только если состояние изменилось
            if (currentAuthState != lastAuthState) {
                lastAuthState = currentAuthState
                updateStatus("Статус: $currentAuthState")
                android.util.Log.d("MainActivity", "Auth state changed to: $currentAuthState")
            }

            if (telegramClient.checkAuthState()) {
                // Клиент готов, загружаем каналы
                loadChannels()
                return
            }

            // Проверяем, нужна ли повторная авторизация
            if (telegramClient.needsReauth()) {
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    updateStatus("Требуется повторная авторизация")
                    Toast.makeText(this@MainActivity,
                        "Требуется повторная авторизация. Переходим к экрану входа.",
                        Toast.LENGTH_LONG).show()

                    // Очищаем флаг авторизации и переходим к AuthActivity
                    PreferenceManager.setAuthorized(this@MainActivity, false)
                    startActivity(Intent(this@MainActivity, AuthActivity::class.java))
                    finish()
                }
                return
            }

            attempts++
            kotlinx.coroutines.delay(1000) // Ждём 1 секунду

            // Более детальные статусы
            when (attempts) {
                3 -> updateStatus("Инициализация TDLib...")
                8 -> updateStatus("Проверка авторизации...")
                15 -> updateStatus("Подключение к серверам...")
                25 -> updateStatus("Загрузка данных профиля...")
                35 -> updateStatus("Финализация подключения...")
            }
        }

        // Тайм-аут
        runOnUiThread {
            binding.progressBar.visibility = View.GONE
            val finalState = telegramClient.getCurrentAuthState()
            updateStatus("Ошибка подключения. Состояние: $finalState")

            Toast.makeText(this@MainActivity,
                "Тайм-аут подключения к Telegram.\nТекущее состояние: $finalState\n\nПопробуйте:\n1. Перезапустить приложение\n2. Проверить интернет\n3. Переавторизоваться",
                Toast.LENGTH_LONG).show()

            // Предлагаем переавторизацию
            android.app.AlertDialog.Builder(this@MainActivity)
                .setTitle("Ошибка подключения")
                .setMessage("Не удалось подключиться к Telegram. Хотите попробовать переавторизоваться?")
                .setPositiveButton("Переавторизоваться") { _, _ ->
                    PreferenceManager.setAuthorized(this@MainActivity, false)
                    startActivity(Intent(this@MainActivity, AuthActivity::class.java))
                    finish()
                }
                .setNegativeButton("Повторить") { _, _ ->
                    lifecycleScope.launch {
                        waitForClientAndLoadChannels()
                    }
                }
                .setNeutralButton("Закрыть") { _, _ ->
                    finish()
                }
                .show()
        }
    }

    private fun loadChannels() {
        updateStatus("Загружаем каналы...")

        telegramClient.loadChannels { channels ->
            runOnUiThread {
                binding.progressBar.visibility = View.GONE
                if (channels.isNotEmpty()) {
                    channelAdapter.updateChannels(channels)
                    updateStatus("Выберите каналы для сбора новостей")
                } else {
                    updateStatus("Каналы не найдены")
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

        binding.progressBar.visibility = View.VISIBLE
        binding.btnCollectNews.isEnabled = false
        updateStatus("Собираем новости...")

        // Используем корутину для вызова suspend функции
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
                        updateStatus("Новости готовы к прослушиванию")
                        showPlayerControls()
                        resetPlayerButtons()
                    } else {
                        updateStatus("Ошибка при обработке новостей")
                        Toast.makeText(this@MainActivity, "Не удалось обработать новости", Toast.LENGTH_SHORT).show()
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
    }

    private fun showPlayerControls() {
        try {
            binding.llPlayer.visibility = View.VISIBLE
        } catch (e: Exception) {
            // Если layoutPlayer не существует, показываем отдельные кнопки
            binding.btnPlay.visibility = View.VISIBLE
            binding.btnPause.visibility = View.VISIBLE
            binding.btnStop.visibility = View.VISIBLE
        }
    }

    private fun updateStatus(message: String) {
        try {
            binding.tvStatus.text = message
        } catch (e: Exception) {
            // Если textStatus не существует, показываем Toast
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        if (::ttsManager.isInitialized) {
            ttsManager.shutdown()
        }
    }

    override fun onPause() {
        super.onPause()
        // Pause audio when activity is paused
        if (mediaPlayer?.isPlaying == true) {
            pauseAudio()
        }
    }
}