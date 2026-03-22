package com.p2petrovich.telegramnewsreader.activities

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.p2petrovich.telegramnewsreader.R
import com.p2petrovich.telegramnewsreader.adapters.VoiceAdapter
import com.p2petrovich.telegramnewsreader.tts.TTSManagerSingleton
import com.p2petrovich.telegramnewsreader.utils.PreferenceManager
import com.p2petrovich.telegramnewsreader.models.VoiceEntry

class VoiceSelectionActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var voiceAdapter: VoiceAdapter
    private var pendingVoiceRestore: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_voice_selection)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        supportActionBar?.apply {
            title = "Голос и речь"
            setDisplayHomeAsUpEnabled(true)
        }

        recyclerView = findViewById(R.id.recyclerVoices)
        recyclerView.layoutManager = LinearLayoutManager(this)

        loadVoices()
    }

    private fun loadVoices() {
        val ttsManager = TTSManagerSingleton.getInstance(this)
        val allVoiceEntries = ttsManager.getAvailableVoiceEntries()

        val russianVoices = allVoiceEntries.filter {
            it.language == "ru" || it.language.startsWith("ru", ignoreCase = true)
        }

        if (russianVoices.isEmpty()) {
            Toast.makeText(this, "Русские голоса TTS не найдены", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val savedVoiceName = PreferenceManager.getTtsVoiceName(this)
        val savedVoiceIsRussian = russianVoices.any { it.systemName == savedVoiceName }

        val currentSelectedVoice = if (savedVoiceIsRussian) {
            savedVoiceName
        } else {
            russianVoices.firstOrNull()?.systemName.also {
                if (it != null) PreferenceManager.saveTtsVoiceName(this, it)
            }
        }

        voiceAdapter = VoiceAdapter(
            voiceEntries = russianVoices,
            selectedVoiceName = currentSelectedVoice,
            onVoiceSelected = { voiceEntry -> onVoiceSelected(voiceEntry) },
            onVoicePlay = { voiceEntry -> onVoicePlay(voiceEntry) }
        )

        recyclerView.adapter = voiceAdapter
    }

    private fun onVoiceSelected(voiceEntry: VoiceEntry) {
        val ttsManager = TTSManagerSingleton.getInstance(this)
        ttsManager.setVoiceByEntry(voiceEntry)
        ttsManager.applyVoiceSettings(voiceEntry.systemName)
        PreferenceManager.saveTtsVoiceName(this, voiceEntry.systemName)
        Toast.makeText(this, "Голос изменён: ${voiceEntry.displayName}", Toast.LENGTH_SHORT).show()
    }

    private fun onVoicePlay(voiceEntry: VoiceEntry) {
        val ttsManager = TTSManagerSingleton.getInstance(this)
        val currentVoice = PreferenceManager.getTtsVoiceName(this)

        // Отменяем предыдущее восстановление
        pendingVoiceRestore?.let { recyclerView.removeCallbacks(it) }

        ttsManager.setVoiceByEntry(voiceEntry)
        ttsManager.applyVoiceSettings(voiceEntry.systemName)
        ttsManager.speak("Привет! Это голос ${voiceEntry.displayName}. Как вам качество звучания?")

        // Восстанавливаем предыдущий голос
        if (currentVoice != null && currentVoice != voiceEntry.systemName) {
            pendingVoiceRestore = Runnable {
                ttsManager.setVoiceByName(currentVoice)
                ttsManager.applyVoiceSettings(currentVoice)
            }
            recyclerView.postDelayed(pendingVoiceRestore!!, 3000)
        }

        Toast.makeText(this, "Тестирую: ${voiceEntry.displayName}", Toast.LENGTH_SHORT).show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        pendingVoiceRestore?.let { recyclerView.removeCallbacks(it) }
        pendingVoiceRestore = null
    }
}
