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
import com.example.telegramnewsreader.utils.TTSDebugTracker
import android.widget.Toast


class VoiceAdapter(
    private val voiceEntries: List<VoiceEntry>,
    private val selectedVoiceName: String?,
    private val onVoiceSelected: (VoiceEntry) -> Unit,
    private val onVoicePlay: (VoiceEntry) -> Unit
) : RecyclerView.Adapter<VoiceAdapter.VoiceViewHolder>() {

    private var selectedIndex = voiceEntries.indexOfFirst { it.systemName == selectedVoiceName }.let { index ->
        if (index == -1 && voiceEntries.isNotEmpty()) 0 else index
    }

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

        val pitchValue: TextView? = try { view.findViewById(R.id.tvPitchValue) } catch (e: Exception) { null }
        val speedValue: TextView? = try { view.findViewById(R.id.tvSpeedValue) } catch (e: Exception) { null }

        val btnResetPitch: ImageButton? = try { view.findViewById(R.id.btnResetPitch) } catch (e: Exception) { null }
        val btnResetRate: ImageButton? = try { view.findViewById(R.id.btnResetRate) } catch (e: Exception) { null }

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

        val displayText = "${voiceEntry.getGenderIcon()} ${voiceEntry.displayName}"
        val engineInfo = if (voiceEntry.isNetwork) "сеть" else "локально"

        holder.radio.text = "$displayText ($engineInfo)"
        holder.radio.isChecked = position == selectedIndex

        holder.genderIcon?.text = voiceEntry.getGenderIcon()

        Log.d("VoiceAdapter", "📋 onBindViewHolder: position=$position, voice=${voiceEntry.displayName} (${voiceEntry.systemName}), isChecked=${holder.radio.isChecked}")

        holder.radio.setOnClickListener {
            val clickedPosition = holder.bindingAdapterPosition
            if (clickedPosition != RecyclerView.NO_POSITION) {
                TTSDebugTracker.trackUserAction("Voice selected via RadioButton: ${voiceEntries[clickedPosition].displayName}")
                Log.d("VoiceAdapter", "🔘 === RadioButton НАЖАТ ===")
                Log.d("VoiceAdapter", "   position=$clickedPosition, voice=${voiceEntries[clickedPosition].displayName}")
                val stackTrace = Thread.currentThread().stackTrace
                Log.d("VoiceAdapter", "📍 Стек вызовов RadioButton click:")
                stackTrace.take(6).forEach { element ->
                    Log.d("VoiceAdapter", "   ${element.className}.${element.methodName}(${element.fileName}:${element.lineNumber})")
                }

                val previousIndex = selectedIndex
                selectedIndex = clickedPosition
                voiceSelectionCount++

                Log.d("VoiceAdapter", "🔄 Изменение выбора: $previousIndex -> $selectedIndex")
                Log.d("VoiceAdapter", "📊 Счетчик выбора голосов: $voiceSelectionCount")

                if (previousIndex != -1 && previousIndex < voiceEntries.size) {
                    Log.d("VoiceAdapter", "🔄 Обновляем предыдущий элемент: $previousIndex")
                    notifyItemChanged(previousIndex)
                }
                Log.d("VoiceAdapter", "🔄 Обновляем текущий элемент: $selectedIndex")
                notifyItemChanged(selectedIndex)

                Log.d("VoiceAdapter", "🔄 Вызываем onVoiceSelected для: ${voiceEntries[clickedPosition].displayName}")
                onVoiceSelected(voiceEntries[clickedPosition])
                Log.d("VoiceAdapter", "✅ === RadioButton обработан ===")
            }
        }

        holder.play.setOnClickListener {
            val clickedPosition = holder.bindingAdapterPosition
            if (clickedPosition != RecyclerView.NO_POSITION) {
                Log.d("VoiceAdapter", "▶️ === Play button НАЖАТ ===")
                Log.d("VoiceAdapter", "   Голос: ${voiceEntries[clickedPosition].displayName}")
                val stackTrace = Thread.currentThread().stackTrace
                Log.d("VoiceAdapter", "📍 Стек вызовов Play button:")
                stackTrace.take(6).forEach { element ->
                    Log.d("VoiceAdapter", "   ${element.className}.${element.methodName}(${element.fileName}:${element.lineNumber})")
                }

                onVoicePlay(voiceEntries[clickedPosition])
                Log.d("VoiceAdapter", "✅ === Play button обработан ===")
            }
        }

        val context = holder.itemView.context
        val ttsManager = TTSManagerSingleton.getInstance(context)

        val savedPitch = ttsManager.getPitchForVoice(voiceEntry.systemName)
        val savedRate = ttsManager.getRateForVoice(voiceEntry.systemName)

        Log.d("VoiceAdapter", "📖 Считанные настройки из PreferenceManager для ${voiceEntry.displayName}:")
        Log.d("VoiceAdapter", "   savedPitch=$savedPitch, savedRate=$savedRate")

        holder.seekPitch.progress = (savedPitch * 100).toInt()
        holder.seekRate.progress = (savedRate * 100).toInt()

        updatePitchValue(holder, savedPitch)
        updateSpeedValue(holder, savedRate)

        holder.seekPitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val currentPosition = holder.bindingAdapterPosition
                    if (currentPosition != RecyclerView.NO_POSITION) {
                        val currentVoiceEntry = voiceEntries[currentPosition]
                        val newPitch = progress / 100f
                        TTSDebugTracker.trackUserAction("SeekBar pitch changed to $newPitch")
                        Log.d("VoiceAdapter", "🎚️ Изменен тембр для ${currentVoiceEntry.displayName}: $newPitch")

                        // 🔥 СОХРАНЯЕМ значение ТОЛЬКО для этого голоса
                        ttsManager.updatePitchForVoice(currentVoiceEntry.systemName, newPitch)

                        // ❗НЕ вызываем updatePitch(newPitch) - это влияет на глобальные параметры

                        updatePitchValue(holder, newPitch)
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        holder.seekRate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val currentPosition = holder.bindingAdapterPosition
                    if (currentPosition != RecyclerView.NO_POSITION) {
                        val currentVoiceEntry = voiceEntries[currentPosition]
                        val newRate = progress / 100f
                        TTSDebugTracker.trackUserAction("SeekBar rate changed to $newRate")
                        Log.d("VoiceAdapter", "⏩ Изменена скорость для ${currentVoiceEntry.displayName}: $newRate")

                        // 🔥 СОХРАНЯЕМ значение ТОЛЬКО для этого голоса
                        ttsManager.updateRateForVoice(currentVoiceEntry.systemName, newRate)

                        // ❗НЕ вызываем updateRate(newRate) - это влияет на глобальные параметры

                        updateSpeedValue(holder, newRate)
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        holder.btnResetPitch?.setOnClickListener {
            val currentPosition = holder.bindingAdapterPosition
            if (currentPosition != RecyclerView.NO_POSITION) {
                val currentVoiceEntry = voiceEntries[currentPosition]
                Log.d("VoiceAdapter", "↺ Сброс тембра на 1.0 для ${currentVoiceEntry.displayName}")
                holder.seekPitch.progress = 100
                val resetPitch = 1.0f
                TTSDebugTracker.trackUserAction("Pitch reset to 1.0 for ${currentVoiceEntry.displayName}")

                // 🔥 СБРАСЫВАЕМ значение ТОЛЬКО для этого голоса
                ttsManager.updatePitchForVoice(currentVoiceEntry.systemName, resetPitch)

                updatePitchValue(holder, resetPitch)
                Toast.makeText(context, "Тембр сброшен на 1.0", Toast.LENGTH_SHORT).show()
            }
        }

        holder.btnResetRate?.setOnClickListener {
            val currentPosition = holder.bindingAdapterPosition
            if (currentPosition != RecyclerView.NO_POSITION) {
                val currentVoiceEntry = voiceEntries[currentPosition]
                Log.d("VoiceAdapter", "↺ Сброс скорости на 1.0 для ${currentVoiceEntry.displayName}")
                holder.seekRate.progress = 100
                val resetRate = 1.0f
                TTSDebugTracker.trackUserAction("Rate reset to 1.0 for ${currentVoiceEntry.displayName}")

                // 🔥 СБРАСЫВАЕМ значение ТОЛЬКО для этого голоса
                ttsManager.updateRateForVoice(currentVoiceEntry.systemName, resetRate)

                updateSpeedValue(holder, resetRate)
                Toast.makeText(context, "Скорость сброшена на 1.0", Toast.LENGTH_SHORT).show()
            }
        }
    }

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

    fun getSelectedVoiceEntry(): VoiceEntry? {
        return if (selectedIndex >= 0 && selectedIndex < voiceEntries.size) {
            voiceEntries[selectedIndex]
        } else null
    }
}