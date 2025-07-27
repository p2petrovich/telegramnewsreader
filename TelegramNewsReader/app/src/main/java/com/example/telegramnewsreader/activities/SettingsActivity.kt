package com.example.telegramnewsreader.activities

import android.os.Bundle
import android.view.View
import android.widget.*
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.telegramnewsreader.utils.PreferenceManager
import com.example.telegramnewsreader.tts.TTSManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.example.telegramnewsreader.adapters.VoiceAdapter





class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 64, 32, 64)
        }

        setContentView(layout) // сразу показываем пустой layout

        val ttsManager = TTSManager(this)

        // Ждём инициализации TTS
        lifecycleScope.launch(Dispatchers.Main) {
            delay(500) // подождём немного, чтобы TTS успел инициализироваться
            val voiceList = ttsManager.getAvailableVoices().filter {
                it.locale.language == "ru"
            }

            val voiceNames = voiceList.map { voice -> voice.name }


            val selectedVoiceName = PreferenceManager.getTtsVoiceName(this@SettingsActivity)

            if (voiceList.isNotEmpty()) {
                val selectedVoiceName = PreferenceManager.getTtsVoiceName(this@SettingsActivity)

                layout.addView(TextView(this@SettingsActivity).apply {
                    text = "Выберите голос TTS:"
                    textSize = 16f
                    setPadding(0, 0, 0, 8)
                })

                val recyclerView = RecyclerView(this@SettingsActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@SettingsActivity)
                }

                layout.addView(recyclerView)

                val adapter = VoiceAdapter(
                    voiceList,
                    selectedVoiceName,
                    onVoiceSelected = { selected ->
                        PreferenceManager.saveTtsVoiceName(this@SettingsActivity, selected.name)
                        ttsManager.setVoiceByName(selected.name)
                    },
                    onVoicePlay = { voice ->
                        ttsManager.setVoiceByName(voice.name)
                        ttsManager.speak("Это тестовое сообщение этим голосом.")
                    }
                )
                recyclerView.adapter = adapter
            }

            else {
                layout.addView(TextView(this@SettingsActivity).apply {
                    text = "❗ Доступные голоса TTS не найдены.\nУбедитесь, что установлен голосовой движок (например, Google TTS)"
                    textSize = 16f
                })
            }
        }
    }
}
