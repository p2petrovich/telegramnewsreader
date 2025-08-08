package com.example.telegramnewsreader.activities

import android.os.Bundle
import android.view.View
import android.widget.*
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.telegramnewsreader.utils.PreferenceManager
import com.example.telegramnewsreader.tts.TTSManager
import com.example.telegramnewsreader.tts.TTSManagerSingleton // 🔥 НОВЫЙ ИМПОРТ
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.recyclerview.widget.RecyclerView
import com.example.telegramnewsreader.adapters.VoiceAdapter
import com.example.telegramnewsreader.models.VoiceMappings
import com.example.telegramnewsreader.models.VoiceEntry // 🔥 ИСПРАВЛЕН ИМПОРТ

class SettingsActivity : AppCompatActivity() {

    private lateinit var ttsManager: TTSManager // 🔥 ИЗМЕНЕНИЕ: объявляем как поле класса

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 64, 32, 64)
        }

        setContentView(layout) // сразу показываем пустой layout

        // 🔥 ИЗМЕНЕНИЕ: Используем синглтон TTSManager
        ttsManager = TTSManagerSingleton.getInstance(this)

        // Ждём инициализации TTS
        lifecycleScope.launch(Dispatchers.Main) {
            delay(500) // подождём немного, чтобы TTS успел инициализироваться
            
            // 🔥 ИСПРАВЛЕНИЕ: Используем новый метод для получения VoiceEntry
            val voiceEntries = ttsManager.getAvailableVoiceEntries()

            val selectedVoiceName = PreferenceManager.getTtsVoiceName(this@SettingsActivity)

            if (voiceEntries.isNotEmpty()) {
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
                    voiceEntries = voiceEntries, // 🔥 ИСПРАВЛЕНО: передаем voiceEntries
                    selectedVoiceName = selectedVoiceName,
                    onVoiceSelected = { voiceEntry -> // 🔥 ИСПРАВЛЕНО: параметр voiceEntry
                        android.util.Log.d("SettingsActivity", "🎯 onVoiceSelected вызван для: ${voiceEntry.displayName} (${voiceEntry.systemName})")

                        // Сохраняем в настройки системное имя
                        PreferenceManager.saveTtsVoiceName(this@SettingsActivity, voiceEntry.systemName)
                        android.util.Log.d("SettingsActivity", "💾 Голос сохранен в PreferenceManager: ${voiceEntry.systemName}")

                        // 🔥 ВАЖНО: Сразу применяем выбранный голос в TTS
                        ttsManager.setVoiceByEntry(voiceEntry) // 🔥 ИСПРАВЛЕНО: используем setVoiceByEntry
                        android.util.Log.d("SettingsActivity", "🔄 setVoiceByEntry вызван для: ${voiceEntry.displayName}")

                        // 🔥 ДОПОЛНИТЕЛЬНО: Принудительно обновляем голос
                        ttsManager.refreshVoice()
                        android.util.Log.d("SettingsActivity", "✅ refreshVoice завершен")
                    },
                    onVoicePlay = { voiceEntry -> // 🔥 ИСПРАВЛЕНО: параметр voiceEntry
                        android.util.Log.d("SettingsActivity", "▶️ onVoicePlay для: ${voiceEntry.displayName}")

                        // 🔥 ВРЕМЕННО: Сохраняем текущий выбранный голос
                        val currentSelectedVoice = PreferenceManager.getTtsVoiceName(this@SettingsActivity)
                        android.util.Log.d("SettingsActivity", "📌 Текущий сохраненный голос: $currentSelectedVoice")

                        // Применяем голос для тестирования
                        ttsManager.setVoiceByEntry(voiceEntry) // 🔥 ИСПРАВЛЕНО: используем setVoiceByEntry
                        ttsManager.speak("Пример сообщения этим голосом.")

                        // 🔥 ВОССТАНАВЛИВАЕМ: Возвращаем ранее выбранный голос после тестирования
                        lifecycleScope.launch {
                            delay(100) // небольшая задержка, чтобы speak успел запуститься
                            currentSelectedVoice?.let {
                                ttsManager.setVoiceByName(it)
                                android.util.Log.d("SettingsActivity", "🔙 Голос восстановлен: $it")
                            }
                        }
                    }
                )
                recyclerView.adapter = adapter
            } else {
                layout.addView(TextView(this@SettingsActivity).apply {
                    text = "❗ Доступные голоса TTS не найдены.\nУбедитесь, что установлен голосовой движок (например, Google TTS)"
                    textSize = 16f
                })
            }
        }
    }

    // 🔥 НОВОЕ: не нужно shutdown в onDestroy, так как используем синглтон
    override fun onDestroy() {
        super.onDestroy()
        // Не вызываем ttsManager.shutdown(), так как используем синглтон
        // который должен жить на протяжении всего жизненного цикла приложения
    }
}