package com.example.telegramnewsreader.adapters


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

    private var selectedIndex = voices.indexOfFirst { it.name == selectedVoiceName }

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

        holder.radio.setOnClickListener {
            selectedIndex = position
            onVoiceSelected(voice)
            notifyDataSetChanged()
        }

        holder.play.setOnClickListener {
            onVoicePlay(voice)
        }
    }

}
