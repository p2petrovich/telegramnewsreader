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

    init {
        Log.d("VoiceAdapter", "🎯 Инициализация: selectedVoiceName=$selectedVoiceName, selectedIndex=$selectedIndex")
        if (selectedIndex >= 0 && selectedIndex < voiceEntries.size) {
            val selectedVoice = voiceEntries[selectedIndex]
            Log.d("VoiceAdapter", "✅ Выбранный голос: ${selectedVoice.displayName} (${selectedVoice.systemName})")
        }
    }

    inner class VoiceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val radio: RadioButton = view.findViewById(R.id.radioVoice)
        val play: ImageButton = view.findViewById(R.id.btnPlay)
        val seekPitch: SeekBar = view.findViewById(R.id.seekPitch)
        val seekRate: SeekBar = view.findViewById(R.id.seekRate)

        // 🔥 НОВОЕ: Добавляем TextView для отображения значений ползунков
        val pitchValue: TextView? = view.findViewById(R.id.tvPitchValue)
        val speedValue: TextView? = view.findViewById(R.id.tvSpeedValue)

        // 🔥 НОВОЕ: Добавляем TextView для иконки пола (если есть в layout)
        val genderIcon: TextView? = view.findViewById(R.id.tvGenderIcon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VoiceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_voice, parent, false)
        return VoiceViewHolder(view)
    }

    override fun getItemCount(): Int = voiceEntries.size

    override fun onBindViewHolder(holder: VoiceViewHolder, position: Int) {
        val voiceEntry = voiceEntries[position]

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
            Log.d("VoiceAdapter", "🔘 RadioButton clicked: position=$position, voice=${voiceEntry.displayName}")

            val previousIndex = selectedIndex
            selectedIndex = position

            // 🔥 ИСПРАВЛЕНИЕ: Обновляем только нужные элементы вместо notifyDataSetChanged()
            if (previousIndex != -1 && previousIndex < voiceEntries.size) {
                notifyItemChanged(previousIndex) // снимаем выделение с предыдущего
            }
            notifyItemChanged(selectedIndex) // устанавливаем выделение на новый

            // 🔥 ВАЖНО: Вызываем коллбэк ПОСЛЕ обновления UI
            Log.d("VoiceAdapter", "🔄 Вызываем onVoiceSelected для: ${voiceEntry.displayName}")
            onVoiceSelected(voiceEntry)
        }

        holder.play.setOnClickListener {
            Log.d("VoiceAdapter", "▶️ Play button clicked для: ${voiceEntry.displayName}")
            onVoicePlay(voiceEntry)
        }

        val context = holder.itemView.context
        val ttsManager = TTSManagerSingleton.getInstance(context)

        val savedPitch = PreferenceManager.getTtsPitch(context)
        val savedRate = PreferenceManager.getTtsRate(context)

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
                    Log.d("VoiceAdapter", "🎚️ Изменен тембр: $newPitch для ${voiceEntry.displayName}")
                    ttsManager.updatePitch(newPitch)

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
                    Log.d("VoiceAdapter", "⏩ Изменена скорость: $newRate для ${voiceEntry.displayName}")
                    ttsManager.updateRate(newRate)

                    // 🔥 НОВОЕ: Обновляем отображаемое значение
                    updateSpeedValue(holder, newRate)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
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