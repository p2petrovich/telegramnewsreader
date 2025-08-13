package com.example.telegramnewsreader.activities

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.telegramnewsreader.R
import com.example.telegramnewsreader.adapters.VoiceAdapter
import com.example.telegramnewsreader.tts.TTSManagerSingleton
import com.example.telegramnewsreader.utils.PreferenceManager
import com.example.telegramnewsreader.models.VoiceEntry // 🔥 ИСПРАВЛЕН ИМПОРТ

class VoiceSelectionActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var voiceAdapter: VoiceAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_voice_selection)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupUI()
        loadVoices()
    }

    private fun setupUI() {
        // Настройка ActionBar
        supportActionBar?.apply {
            title = "Голос и речь"
            setDisplayHomeAsUpEnabled(true)
        }

        // Настройка RecyclerView
        recyclerView = findViewById(R.id.recyclerVoices)
        recyclerView.layoutManager = LinearLayoutManager(this)

        Log.d("VoiceSelectionActivity", "🎯 UI настроен")
    }

    private fun loadVoices() {
        Log.d("VoiceSelectionActivity", "🎵 loadVoices() начат")

        val ttsManager = TTSManagerSingleton.getInstance(this)
        val allVoiceEntries = ttsManager.getAvailableVoiceEntries()

        Log.d("VoiceSelectionActivity", "📊 Всего получено голосов: ${allVoiceEntries.size}")

        // 🔥 ФИЛЬТРУЕМ ТОЛЬКО РУССКИЕ ГОЛОСА
        val russianVoices = allVoiceEntries.filter { voice ->
            voice.language == "ru" || voice.language.startsWith("ru", ignoreCase = true)
        }

        Log.d("VoiceSelectionActivity", "🇷🇺 РУССКИХ ГОЛОСОВ НАЙДЕНО: ${russianVoices.size}")

        // Выводим список русских голосов в лог
        russianVoices.forEachIndexed { index, voice ->
            val networkInfo = if (voice.isNetwork) "сетевой" else "локальный"
            Log.d("VoiceSelectionActivity", "[$index] ${voice.displayName} | ${voice.systemName} | ${voice.language}-${voice.country} | $networkInfo | ${voice.getGenderIcon()}")
        }

        if (russianVoices.isEmpty()) {
            Log.e("VoiceSelectionActivity", "❌ НЕТ русских голосов!")
            Toast.makeText(this, "Русские голоса TTS не найдены", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val savedVoiceName = PreferenceManager.getTtsVoiceName(this)
        Log.d("VoiceSelectionActivity", "💾 Сохранённый голос: $savedVoiceName")

        // Проверяем, является ли сохраненный голос русским
        val savedVoiceIsRussian = russianVoices.any { it.systemName == savedVoiceName }
        Log.d("VoiceSelectionActivity", "🔍 Сохранённый голос является русским: $savedVoiceIsRussian")

        // Если сохраненный голос не русский, выбираем первый русский
        val currentSelectedVoice = if (savedVoiceIsRussian) {
            savedVoiceName
        } else {
            val firstRussianVoice = russianVoices.firstOrNull()
            if (firstRussianVoice != null) {
                Log.d("VoiceSelectionActivity", "🔄 Переключаемся на первый русский голос: ${firstRussianVoice.displayName}")
                PreferenceManager.saveTtsVoiceName(this, firstRussianVoice.systemName)
                firstRussianVoice.systemName
            } else {
                savedVoiceName // оставляем старый, если русских нет
            }
        }

        // Настройка адаптера ТОЛЬКО с русскими голосами
        voiceAdapter = VoiceAdapter(
            voiceEntries = russianVoices, // 🔥 ПЕРЕДАЕМ ТОЛЬКО РУССКИЕ ГОЛОСА
            selectedVoiceName = currentSelectedVoice,
            onVoiceSelected = { voiceEntry ->
                Log.d("VoiceSelectionActivity", "🎯 CALLBACK: onVoiceSelected для ${voiceEntry.displayName}")
                onVoiceSelected(voiceEntry)
            },
            onVoicePlay = { voiceEntry ->
                Log.d("VoiceSelectionActivity", "▶️ CALLBACK: onVoicePlay для ${voiceEntry.displayName}")
                onVoicePlay(voiceEntry)
            }
        )

        Log.d("VoiceSelectionActivity", "🎬 VoiceAdapter создан только с русскими голосами")

        recyclerView.adapter = voiceAdapter
        Log.d("VoiceSelectionActivity", "🔗 Adapter привязан к RecyclerView")

        Log.d("VoiceSelectionActivity", "✅ loadVoices() ЗАВЕРШЕН с ${russianVoices.size} русскими голосами")
    }

    private fun onVoiceSelected(voiceEntry: VoiceEntry) {
        Log.d("VoiceSelectionActivity", "🔊 Выбран голос: ${voiceEntry.displayName} (${voiceEntry.systemName})")

        val ttsManager = TTSManagerSingleton.getInstance(this)

        // 🔥 НОВОЕ: Применяем индивидуальные настройки выбранного голоса
        ttsManager.setVoiceByEntry(voiceEntry)
        ttsManager.applyVoiceSettings(voiceEntry.systemName) // применяем сохраненные настройки

        PreferenceManager.saveTtsVoiceName(this, voiceEntry.systemName) // сохраняем выбор

        Toast.makeText(
            this,
            "Голос изменён: ${voiceEntry.displayName}",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun onVoicePlay(voiceEntry: VoiceEntry) {
        Log.d("VoiceSelectionActivity", "▶️ Тестируем голос: ${voiceEntry.displayName}")

        val ttsManager = TTSManagerSingleton.getInstance(this)

        // 🔥 НОВОЕ: Применяем сохраненные настройки для тестируемого голоса
        val currentVoice = PreferenceManager.getTtsVoiceName(this)
        ttsManager.setVoiceByEntry(voiceEntry)
        ttsManager.applyVoiceSettings(voiceEntry.systemName) // применяем настройки голоса

        // Тестовый текст
        val testText = "Привет! Это голос ${voiceEntry.displayName}. Как вам качество звучания?"
        ttsManager.speak(testText)

        // Восстанавливаем предыдущий голос и его настройки
        if (currentVoice != null && currentVoice != voiceEntry.systemName) {
            // Отложенное восстановление через 3 секунды
            recyclerView.postDelayed({
                ttsManager.setVoiceByName(currentVoice)
                ttsManager.applyVoiceSettings(currentVoice) // восстанавливаем настройки
                Log.d("VoiceSelectionActivity", "🔄 Восстановлен предыдущий голос: $currentVoice с настройками")
            }, 3000)
        }

        Toast.makeText(
            this,
            "🎤 Тестирую: ${voiceEntry.displayName}",
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("VoiceSelectionActivity", "🏁 VoiceSelectionActivity уничтожена")
    }
}