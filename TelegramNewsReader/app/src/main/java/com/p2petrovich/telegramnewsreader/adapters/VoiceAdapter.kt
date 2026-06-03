package com.p2petrovich.telegramnewsreader.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.p2petrovich.telegramnewsreader.R
import com.p2petrovich.telegramnewsreader.tts.TTSManagerSingleton
import com.p2petrovich.telegramnewsreader.models.VoiceEntry

class VoiceAdapter(
    private val voiceEntries: List<VoiceEntry>,
    private val selectedVoiceName: String?,
    private val onVoiceSelected: (VoiceEntry) -> Unit,
    private val onVoicePlay: (VoiceEntry) -> Unit
) : RecyclerView.Adapter<VoiceAdapter.VoiceViewHolder>() {

    private var selectedIndex = voiceEntries.indexOfFirst { it.systemName == selectedVoiceName }
        .let { if (it == -1 && voiceEntries.isNotEmpty()) 0 else it }

    inner class VoiceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val radio: RadioButton = view.findViewById(R.id.radioVoice)
        val play: ImageButton = view.findViewById(R.id.btnPlay)
        val seekPitch: SeekBar = view.findViewById(R.id.seekPitch)
        val seekRate: SeekBar = view.findViewById(R.id.seekRate)
        val pitchValue: TextView? = view.findViewById(R.id.tvPitchValue)
        val speedValue: TextView? = view.findViewById(R.id.tvSpeedValue)
        val btnResetPitch: ImageButton? = view.findViewById(R.id.btnResetPitch)
        val btnResetRate: ImageButton? = view.findViewById(R.id.btnResetRate)
        val genderIcon: TextView? = view.findViewById(R.id.tvGenderIcon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VoiceViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_voice, parent, false)
        return VoiceViewHolder(view)
    }

    override fun getItemCount(): Int = voiceEntries.size

    override fun onBindViewHolder(holder: VoiceViewHolder, position: Int) {
        val voiceEntry = voiceEntries[position]
        val context = holder.itemView.context
        val ttsManager = TTSManagerSingleton.getInstance(context)

        val engineInfo = if (voiceEntry.isNetwork) context.getString(R.string.engine_network) else context.getString(R.string.engine_local)
        holder.radio.text = "${voiceEntry.getGenderIcon()} ${voiceEntry.displayName} ($engineInfo)"
        holder.radio.isChecked = position == selectedIndex
        holder.genderIcon?.text = voiceEntry.getGenderIcon()

        // Load saved settings
        val savedPitch = ttsManager.getPitchForVoice(voiceEntry.systemName)
        val savedRate = ttsManager.getRateForVoice(voiceEntry.systemName)
        holder.seekPitch.progress = (savedPitch * 100).toInt()
        holder.seekRate.progress = (savedRate * 100).toInt()
        holder.pitchValue?.text = String.format("%.1f", savedPitch)
        holder.speedValue?.text = String.format("%.1f", savedRate)

        // Radio selection
        holder.radio.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION && pos != selectedIndex) {
                val prev = selectedIndex
                selectedIndex = pos
                if (prev in voiceEntries.indices) notifyItemChanged(prev)
                notifyItemChanged(selectedIndex)
                onVoiceSelected(voiceEntries[pos])
            }
        }

        // Play test
        holder.play.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onVoicePlay(voiceEntries[pos])
        }

        // Pitch SeekBar
        holder.seekPitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val pos = holder.bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        val newPitch = progress / 100f
                        ttsManager.updatePitchForVoice(voiceEntries[pos].systemName, newPitch)
                        holder.pitchValue?.text = String.format("%.1f", newPitch)
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Rate SeekBar
        holder.seekRate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val pos = holder.bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        val newRate = progress / 100f
                        ttsManager.updateRateForVoice(voiceEntries[pos].systemName, newRate)
                        holder.speedValue?.text = String.format("%.1f", newRate)
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Reset buttons
        holder.btnResetPitch?.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                holder.seekPitch.progress = 100
                ttsManager.updatePitchForVoice(voiceEntries[pos].systemName, 1.0f)
                holder.pitchValue?.text = "1.0"
                Toast.makeText(context, context.getString(R.string.pitch_reset), Toast.LENGTH_SHORT).show()
            }
        }

        holder.btnResetRate?.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                holder.seekRate.progress = 100
                ttsManager.updateRateForVoice(voiceEntries[pos].systemName, 1.0f)
                holder.speedValue?.text = "1.0"
                Toast.makeText(context, context.getString(R.string.rate_reset), Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun getSelectedVoiceEntry(): VoiceEntry? {
        return if (selectedIndex in voiceEntries.indices) voiceEntries[selectedIndex] else null
    }
}
