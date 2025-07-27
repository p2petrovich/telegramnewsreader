package com.example.telegramnewsreader.adapters

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.RadioButton
import androidx.recyclerview.widget.RecyclerView
import com.example.telegramnewsreader.R
import android.speech.tts.Voice

class VoiceAdapter(
    private val voices: List<Voice>,
    private val selectedVoiceName: String?,
    private val onVoiceSelected: (Voice) -> Unit,
    private val onVoicePlay: (Voice) -> Unit
) : RecyclerView.Adapter<VoiceAdapter.VoiceViewHolder>() {

    // 🔥 ИСПРАВЛЕНИЕ: Правильная инициализация selectedIndex
    private var selectedIndex = voices.indexOfFirst { it.name == selectedVoiceName }.let { index ->
        if (index == -1 && voices.isNotEmpty()) 0 else index // если не найден, выбираем первый
    }

    init {
        Log.d("VoiceAdapter", "🎯 Инициализация: selectedVoiceName=$selectedVoiceName, selectedIndex=$selectedIndex")
        if (selectedIndex >= 0 && selectedIndex < voices.size) {
            Log.d("VoiceAdapter", "✅ Выбранный голос: ${voices[selectedIndex].name}")
        }
    }

    inner class VoiceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val radio: RadioButton = view.findViewById(R.id.radioVoice)
        val play: ImageButton = view.findViewById(R.id.btnPlay)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VoiceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_voice, parent, false)
        return VoiceViewHolder(view)
    }

    override fun getItemCount(): Int = voices.size

    override fun onBindViewHolder(holder: VoiceViewHolder, position: Int) {
        val voice = voices[position]

        val readableName = voice.name.replace("-", " ")
        val engineInfo = if (voice.name.contains("network")) "нейросеть" else "локально"
        val language = voice.locale.displayLanguage

        holder.radio.text = "$readableName ($language, $engineInfo)"
        holder.radio.isChecked = position == selectedIndex

        // 🔥 ИСПРАВЛЕНИЕ: Более точное логирование
        Log.d("VoiceAdapter", "📋 onBindViewHolder: position=$position, voice=${voice.name}, isChecked=${holder.radio.isChecked}")

        holder.radio.setOnClickListener {
            Log.d("VoiceAdapter", "🔘 RadioButton clicked: position=$position, voice=${voice.name}")

            val previousIndex = selectedIndex
            selectedIndex = position

            // 🔥 ИСПРАВЛЕНИЕ: Обновляем только нужные элементы вместо notifyDataSetChanged()
            if (previousIndex != -1 && previousIndex < voices.size) {
                notifyItemChanged(previousIndex) // снимаем выделение с предыдущего
            }
            notifyItemChanged(selectedIndex) // устанавливаем выделение на новый

            // 🔥 ВАЖНО: Вызываем коллбэк ПОСЛЕ обновления UI
            Log.d("VoiceAdapter", "🔄 Вызываем onVoiceSelected для: ${voice.name}")
            onVoiceSelected(voice)
        }

        holder.play.setOnClickListener {
            Log.d("VoiceAdapter", "▶️ Play button clicked для: ${voice.name}")
            onVoicePlay(voice)
        }
    }

    // 🔥 НОВЫЙ МЕТОД: Для принудительного обновления выбранного голоса извне
    fun updateSelectedVoice(voiceName: String) {
        val newIndex = voices.indexOfFirst { it.name == voiceName }
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

    // 🔥 НОВЫЙ МЕТОД: Получить текущий выбранный голос
    fun getSelectedVoice(): Voice? {
        return if (selectedIndex >= 0 && selectedIndex < voices.size) {
            voices[selectedIndex]
        } else null
    }
}