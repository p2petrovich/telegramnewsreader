package com.example.telegramnewsreader.adapters

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.RadioButton
import androidx.recyclerview.widget.RecyclerView
import com.example.telegramnewsreader.R
import android.widget.SeekBar
import android.widget.TextView
import com.example.telegramnewsreader.tts.TTSManagerSingleton
import com.example.telegramnewsreader.utils.PreferenceManager
import com.example.telegramnewsreader.models.VoiceEntry
import com.example.telegramnewsreader.utils.TTSDebugTracker // 🔥 ДОБАВЛЕНО
import android.widget.Toast // 🔥 ДОБАВИЛ ИМПОРТ


class VoiceAdapter(
    private val voiceEntries: List<VoiceEntry>,
    private val selectedVoiceName: String?,
    private val onVoiceSelected: (VoiceEntry) -> Unit,
    private val onVoicePlay: (VoiceEntry) -> Unit
) : RecyclerView.Adapter<VoiceAdapter.VoiceViewHolder>() {

    // 🔥 ИСПРАВЛЕНИЕ: Правильная инициализация selectedIndex с VoiceEntry
    private var selectedIndex = voiceEntries.indexOfFirst { it.systemName == selectedVoiceName }.let { index ->
        if (index == -1 && voiceEntries.isNotEmpty()) 0 else index // если не найден, выбираем первый
    }

    // 🔥 НОВОЕ: Счетчики для отслеживания операций
    private var pitchChangeCount = 0
    private var rateChangeCount = 0
    private var voiceSelectionCount = 0

    init {
        Log.d("VoiceAdapter", "🏗️ === VoiceAdapter ИНИЦИАЛИЗАЦИЯ ===")
        val stackTrace = Thread.currentThread().stackTrace
        Log.d("VoiceAdapter", "📍 Стек вызовов VoiceAdapter init:")
        stackTrace.take(8).forEach { element ->
            Log.d("VoiceAdapter", "   ${element.className}.${element.methodName}(${element.fileName}:${element.lineNumber})")
        }

        Log.d("VoiceAdapter", "🎯 Инициализация: selectedVoiceName=$selectedVoiceName, selectedIndex=$selectedIndex")
        Log.d("VoiceAdapter", "📋 Всего голосов: ${voiceEntries.size}")

        voiceEntries.forEachIndexed { index, voice ->
            Log.d("VoiceAdapter", "   [$index] ${voice.displayName} (${voice.systemName})")
        }

        if (selectedIndex >= 0 && selectedIndex < voiceEntries.size) {
            val selectedVoice = voiceEntries[selectedIndex]
            Log.d("VoiceAdapter", "✅ Выбранный голос: ${selectedVoice.displayName} (${selectedVoice.systemName})")
        }
        Log.d("VoiceAdapter", "🏗️ === VoiceAdapter ИНИЦИАЛИЗАЦИЯ ЗАВЕРШЕНА ===")
    }

    inner class VoiceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val radio: RadioButton = view.findViewById(R.id.radioVoice)
        val play: ImageButton = view.findViewById(R.id.btnPlay)
        val seekPitch: SeekBar = view.findViewById(R.id.seekPitch)
        val seekRate: SeekBar = view.findViewById(R.id.seekRate)

        // 🔥 НОВОЕ: Добавляем TextView для отображения значений ползунков
        val pitchValue: TextView? = try { view.findViewById(R.id.tvPitchValue) } catch (e: Exception) { null }
        val speedValue: TextView? = try { view.findViewById(R.id.tvSpeedValue) } catch (e: Exception) { null }

        // 🔥 НОВОЕ: Добавляем кнопки сброса
        val btnResetPitch: ImageButton? = try { view.findViewById(R.id.btnResetPitch) } catch (e: Exception) { null }
        val btnResetRate: ImageButton? = try { view.findViewById(R.id.btnResetRate) } catch (e: Exception) { null }

        // 🔥 НОВОЕ: Добавляем TextView для иконки пола (если есть в layout)
        val genderIcon: TextView? = try { view.findViewById(R.id.tvGenderIcon) } catch (e: Exception) { null }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VoiceViewHolder {
        Log.d("VoiceAdapter", "🏗️ onCreateViewHolder вызван")
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_voice, parent, false)
        return VoiceViewHolder(view)
    }

    override fun getItemCount(): Int = voiceEntries.size

    override fun onBindViewHolder(holder: VoiceViewHolder, position: Int) {
        val voiceEntry = voiceEntries[position]
        Log.d("VoiceAdapter", "🔗 === onBindViewHolder для позиции $position ===")
        Log.d("VoiceAdapter", "   Голос: ${voiceEntry.displayName} (${voiceEntry.systemName})")

        // 🔥 НОВОЕ: Используем понятные названия из VoiceEntry
        val displayText = "${voiceEntry.getGenderIcon()} ${voiceEntry.displayName}"
        val engineInfo = if (voiceEntry.isNetwork) "сеть" else "локально"

        holder.radio.text = "$displayText ($engineInfo)"
        holder.radio.isChecked = position == selectedIndex

        // 🔥 НОВОЕ: Устанавливаем иконку пола отдельно (если TextView есть)
        holder.genderIcon?.text = voiceEntry.getGenderIcon()

        // 🔥 ИСПРАВЛЕНИЕ: Более точное логирование
        Log.d("VoiceAdapter", "📋 onBindViewHolder: position=$position, voice=${voiceEntry.displayName} (${voiceEntry.systemName}), isChecked=${holder.radio.isChecked}")

        holder.radio.setOnClickListener {
            // 🔥 ДОБАВЛЕНО: Отслеживание пользовательского действия
            TTSDebugTracker.trackUserAction("Voice selected via RadioButton: ${voiceEntry.displayName}")
            Log.d("VoiceAdapter", "🔘 === RadioButton НАЖАТ ===")
            Log.d("VoiceAdapter", "   position=$position, voice=${voiceEntry.displayName}")
            val stackTrace = Thread.currentThread().stackTrace
            Log.d("VoiceAdapter", "📍 Стек вызовов RadioButton click:")
            stackTrace.take(6).forEach { element ->
                Log.d("VoiceAdapter", "   ${element.className}.${element.methodName}(${element.fileName}:${element.lineNumber})")
            }

            val previousIndex = selectedIndex
            selectedIndex = position
            voiceSelectionCount++

            Log.d("VoiceAdapter", "🔄 Изменение выбора: $previousIndex -> $selectedIndex")
            Log.d("VoiceAdapter", "📊 Счетчик выбора голосов: $voiceSelectionCount")

            // 🔥 ИСПРАВЛЕНИЕ: Обновляем только нужные элементы вместо notifyDataSetChanged()
            if (previousIndex != -1 && previousIndex < voiceEntries.size) {
                Log.d("VoiceAdapter", "🔄 Обновляем предыдущий элемент: $previousIndex")
                notifyItemChanged(previousIndex) // снимаем выделение с предыдущего
            }
            Log.d("VoiceAdapter", "🔄 Обновляем текущий элемент: $selectedIndex")
            notifyItemChanged(selectedIndex) // устанавливаем выделение на новый

            // 🔥 ВАЖНО: Вызываем коллбэк ПОСЛЕ обновления UI
            Log.d("VoiceAdapter", "🔄 Вызываем onVoiceSelected для: ${voiceEntry.displayName}")
            onVoiceSelected(voiceEntry)
            Log.d("VoiceAdapter", "✅ === RadioButton обработан ===")
        }

        holder.play.setOnClickListener {
            Log.d("VoiceAdapter", "▶️ === Play button НАЖАТ ===")
            Log.d("VoiceAdapter", "   Голос: ${voiceEntry.displayName}")
            val stackTrace = Thread.currentThread().stackTrace
            Log.d("VoiceAdapter", "📍 Стек вызовов Play button:")
            stackTrace.take(6).forEach { element ->
                Log.d("VoiceAdapter", "   ${element.className}.${element.methodName}(${element.fileName}:${element.lineNumber})")
            }

            onVoicePlay(voiceEntry)
            Log.d("VoiceAdapter", "✅ === Play button обработан ===")
        }

        val context = holder.itemView.context
        val ttsManager = TTSManagerSingleton.getInstance(context)

        // 🔥 НОВОЕ: Загружаем индивидуальные настройки для каждого голоса
        val savedPitch = ttsManager.getPitchForVoice(voiceEntry.systemName)
        val savedRate = ttsManager.getRateForVoice(voiceEntry.systemName)

        Log.d("VoiceAdapter", "📖 Считанные настройки из PreferenceManager для ${voiceEntry.displayName}:")
        Log.d("VoiceAdapter", "   savedPitch=$savedPitch, savedRate=$savedRate")

        // 🔥 ИСПРАВЛЕНИЕ: Корректная настройка SeekBar (0-200, значение по умолчанию 100)
        holder.seekPitch.progress = (savedPitch * 100).toInt()
        holder.seekRate.progress = (savedRate * 100).toInt()

        // 🔥 НОВОЕ: Устанавливаем начальные значения для TextView
        updatePitchValue(holder, savedPitch)
        updateSpeedValue(holder, savedRate)

        holder.seekPitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val newPitch = progress / 100f
                    TTSDebugTracker.trackUserAction("SeekBar pitch changed to $newPitch")  // 🔥 ДОБАВИТЬ
                    Log.d("VoiceAdapter", "🎚️ Изменен тембр: $newPitch для ${voiceEntry.displayName}")

                    // 🔥 НОВОЕ: Сохраняем настройки для конкретного голоса
                    ttsManager.updatePitchForVoice(voiceEntry.systemName, newPitch)
                    ttsManager.updatePitch(newPitch) // также применяем сразу

                    // 🔥 НОВОЕ: Обновляем отображаемое значение
                    updatePitchValue(holder, newPitch)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        holder.seekRate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val newRate = progress / 100f
                    TTSDebugTracker.trackUserAction("SeekBar rate changed to $newRate")  // 🔥 ДОБАВИТЬ
                    Log.d("VoiceAdapter", "⏩ Изменена скорость: $newRate для ${voiceEntry.displayName}")

                    // 🔥 НОВОЕ: Сохраняем настройки для конкретного голоса
                    ttsManager.updateRateForVoice(voiceEntry.systemName, newRate)
                    ttsManager.updateRate(newRate) // также применяем сразу

                    // 🔥 НОВОЕ: Обновляем отображаемое значение
                    updateSpeedValue(holder, newRate)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        // 🔥 НОВОЕ: Кнопки сброса параметров
        holder.btnResetPitch?.setOnClickListener {
            Log.d("VoiceAdapter", "↺ Сброс тембра на 1.0 для ${voiceEntry.displayName}")
            holder.seekPitch.progress = 100 // 1.0
            val resetPitch = 1.0f
            TTSDebugTracker.trackUserAction("Pitch reset to 1.0 for ${voiceEntry.displayName}")
            ttsManager.updatePitchForVoice(voiceEntry.systemName, resetPitch)
            ttsManager.updatePitch(resetPitch)
            updatePitchValue(holder, resetPitch)
            Toast.makeText(context, "Тембр сброшен на 1.0", Toast.LENGTH_SHORT).show()
        }

        holder.btnResetRate?.setOnClickListener {
            Log.d("VoiceAdapter", "↺ Сброс скорости на 1.0 для ${voiceEntry.displayName}")
            holder.seekRate.progress = 100 // 1.0
            val resetRate = 1.0f
            TTSDebugTracker.trackUserAction("Rate reset to 1.0 for ${voiceEntry.displayName}")
            ttsManager.updateRateForVoice(voiceEntry.systemName, resetRate)
            ttsManager.updateRate(resetRate)
            updateSpeedValue(holder, resetRate)
            Toast.makeText(context, "Скорость сброшена на 1.0", Toast.LENGTH_SHORT).show()
        }
    }

    // 🔥 НОВЫЕ МЕТОДЫ: Для обновления отображаемых значений ползунков
    private fun updatePitchValue(holder: VoiceViewHolder, pitch: Float) {
        val pitchText = String.format("%.1f", pitch)
        holder.pitchValue?.text = pitchText
        Log.d("VoiceAdapter", "🎚️ Обновлено значение тембра: $pitchText")
    }

    private fun updateSpeedValue(holder: VoiceViewHolder, rate: Float) {
        val rateText = String.format("%.1f", rate)
        holder.speedValue?.text = rateText
        Log.d("VoiceAdapter", "⏩ Обновлено значение скорости: $rateText")
    }

    // 🔥 НОВЫЙ МЕТОД: Для принудительного обновления выбранного голоса извне
    fun updateSelectedVoice(voiceName: String) {
        val newIndex = voiceEntries.indexOfFirst { it.systemName == voiceName }
        if (newIndex != -1 && newIndex != selectedIndex) {
            val previousIndex = selectedIndex
            selectedIndex = newIndex

            Log.d("VoiceAdapter", "🔄 updateSelectedVoice: $voiceName, newIndex=$newIndex")

            if (previousIndex != -1) {
                notifyItemChanged(previousIndex)
            }
            notifyItemChanged(selectedIndex)
        }
    }

    // 🔥 НОВЫЙ МЕТОД: Получить текущий выбранный VoiceEntry
    fun getSelectedVoiceEntry(): VoiceEntry? {
        return if (selectedIndex >= 0 && selectedIndex < voiceEntries.size) {
            voiceEntries[selectedIndex]
        } else null
    }
}