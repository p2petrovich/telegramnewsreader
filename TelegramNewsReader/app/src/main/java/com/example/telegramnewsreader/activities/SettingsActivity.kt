package com.example.telegramnewsreader.activities

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import android.view.View
import android.widget.*
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.telegramnewsreader.utils.PreferenceManager
import com.example.telegramnewsreader.tts.TTSManager
import com.example.telegramnewsreader.tts.TTSManagerSingleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.recyclerview.widget.RecyclerView
import com.example.telegramnewsreader.adapters.VoiceAdapter
import com.example.telegramnewsreader.models.VoiceMappings
import com.example.telegramnewsreader.models.VoiceEntry

class SettingsActivity : AppCompatActivity() {

    private lateinit var ttsManager: TTSManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 64, 32, 64)
        }

        setContentView(layout) // сразу показываем пустой layout

        // 🔥 ДИАГНОСТИКА: Выводим все доступные голоса в логи
        diagnosticsAllVoices()

        // Используем синглтон TTSManager
        ttsManager = TTSManagerSingleton.getInstance(this)

        // Ждём инициализации TTS
        lifecycleScope.launch(Dispatchers.Main) {
            delay(500) // подождём немного, чтобы TTS успел инициализироваться

            // Используем новый метод для получения VoiceEntry
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
                    voiceEntries = voiceEntries,
                    selectedVoiceName = selectedVoiceName,
                    onVoiceSelected = { voiceEntry ->
                        Log.d("SettingsActivity", "🎯 onVoiceSelected вызван для: ${voiceEntry.displayName} (${voiceEntry.systemName})")

                        // Сохраняем в настройки системное имя
                        PreferenceManager.saveTtsVoiceName(this@SettingsActivity, voiceEntry.systemName)
                        Log.d("SettingsActivity", "💾 Голос сохранен в PreferenceManager: ${voiceEntry.systemName}")

                        // Сразу применяем выбранный голос в TTS
                        ttsManager.setVoiceByEntry(voiceEntry)
                        Log.d("SettingsActivity", "🔄 setVoiceByEntry вызван для: ${voiceEntry.displayName}")

                        // Принудительно обновляем голос
                        ttsManager.refreshVoice()
                        Log.d("SettingsActivity", "✅ refreshVoice завершен")
                    },
                    onVoicePlay = { voiceEntry ->
                        Log.d("SettingsActivity", "▶️ onVoicePlay для: ${voiceEntry.displayName}")

                        // Сохраняем текущий выбранный голос
                        val currentSelectedVoice = PreferenceManager.getTtsVoiceName(this@SettingsActivity)
                        Log.d("SettingsActivity", "📌 Текущий сохраненный голос: $currentSelectedVoice")

                        // Применяем голос для тестирования
                        ttsManager.setVoiceByEntry(voiceEntry)
                        ttsManager.speak("Пример сообщения этим голосом.")

                        // Возвращаем ранее выбранный голос после тестирования
                        lifecycleScope.launch {
                            delay(100) // небольшая задержка, чтобы speak успел запуститься
                            currentSelectedVoice?.let {
                                ttsManager.setVoiceByName(it)
                                Log.d("SettingsActivity", "🔙 Голос восстановлен: $it")
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

    /**
     * 🔥 ДИАГНОСТИЧЕСКАЯ ФУНКЦИЯ: Выводит все доступные голоса TTS в логи
     * Используйте для отладки, затем удалите или закомментируйте
     */
    private fun diagnosticsAllVoices() {
        var ttsInstance: TextToSpeech? = null
        ttsInstance = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val voices = ttsInstance?.voices
                Log.d("VoiceDiagnostics", "=".repeat(50))
                Log.d("VoiceDiagnostics", "🎤 ДИАГНОСТИКА ГОЛОСОВ TTS")
                Log.d("VoiceDiagnostics", "=".repeat(50))
                Log.d("VoiceDiagnostics", "📊 Всего голосов: ${voices?.size ?: 0}")

                // Группируем по языкам
                val groupedVoices = voices?.groupBy { it.locale.language }

                groupedVoices?.forEach { (language, voiceList) ->
                    Log.d("VoiceDiagnostics", "\n🌍 Язык: $language (${voiceList.size} голосов)")

                    voiceList.forEach { voice ->
                        val networkStatus = if (voice.isNetworkConnectionRequired) "🌐 Сетевой" else "📱 Локальный"
                        val quality = when (voice.quality) {
                            Voice.QUALITY_VERY_HIGH -> "🌟 Отличное"
                            Voice.QUALITY_HIGH -> "⭐ Хорошее"
                            Voice.QUALITY_NORMAL -> "✓ Обычное"
                            else -> "? Неизвестное (${voice.quality})"
                        }

                        Log.d("VoiceDiagnostics", """
                            ├── 🎯 ${voice.name}
                            │   ├── Локаль: ${voice.locale}
                            │   ├── Тип: $networkStatus
                            │   ├── Качество: $quality
                            │   ├── Особенности: ${voice.features}
                            │   └── Доступен: ${if (!voice.features.isEmpty()) "✅" else "⚠️"}
                        """.trimIndent())
                    }
                }

                // Проверяем русские голоса отдельно
                val russianVoices = voices?.filter {
                    it.locale.language == "ru" || it.locale.toString().startsWith("ru")
                }

                Log.d("VoiceDiagnostics", "\n🇷🇺 РУССКИЕ ГОЛОСА (${russianVoices?.size ?: 0}):")
                russianVoices?.forEach { voice ->
                    Log.d("VoiceDiagnostics", "🔸 ${voice.name} (${voice.locale})")
                }

                // Проверяем английские голоса
                val englishVoices = voices?.filter {
                    it.locale.language == "en" || it.locale.toString().startsWith("en")
                }

                Log.d("VoiceDiagnostics", "\n🇺🇸 АНГЛИЙСКИЕ ГОЛОСА (${englishVoices?.size ?: 0}):")
                englishVoices?.forEach { voice ->
                    Log.d("VoiceDiagnostics", "🔸 ${voice.name} (${voice.locale})")
                }

                // Другие языки
                val otherVoices = voices?.filter {
                    it.locale.language != "ru" && it.locale.language != "en"
                }

                if (!otherVoices.isNullOrEmpty()) {
                    Log.d("VoiceDiagnostics", "\n🌏 ДРУГИЕ ЯЗЫКИ (${otherVoices.size}):")
                    otherVoices.groupBy { it.locale.language }.forEach { (lang, langVoices) ->
                        Log.d("VoiceDiagnostics", "📍 $lang: ${langVoices.map { it.name }}")
                    }
                }

                Log.d("VoiceDiagnostics", "=".repeat(50))
                Log.d("VoiceDiagnostics", "✅ Диагностика завершена")
                Log.d("VoiceDiagnostics", "=".repeat(50))

                ttsInstance?.shutdown()
            } else {
                Log.e("VoiceDiagnostics", "❌ TTS инициализация не удалась: статус $status")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Не вызываем ttsManager.shutdown(), так как используем синглтон
        // который должен жить на протяжении всего жизненного цикла приложения
    }
}