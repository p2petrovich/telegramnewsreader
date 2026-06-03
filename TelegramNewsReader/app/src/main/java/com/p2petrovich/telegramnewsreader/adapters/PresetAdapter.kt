package com.p2petrovich.telegramnewsreader.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.p2petrovich.telegramnewsreader.R
import com.p2petrovich.telegramnewsreader.models.ChannelPreset

class PresetAdapter(
    private val presets: List<ChannelPreset>,
    private val activePresetId: String?,
    private val channelNames: Map<Long, String>,
    private val timePeriods: Array<String>,
    private val onPresetSelected: (ChannelPreset) -> Unit,
    private val onPresetDelete: (ChannelPreset) -> Unit,
    private val onPresetEdit: (ChannelPreset) -> Unit
) : RecyclerView.Adapter<PresetAdapter.PresetViewHolder>() {

    inner class PresetViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tv_preset_name)
        val tvDetails: TextView = view.findViewById(R.id.tv_preset_details)
        val tvChannels: TextView = view.findViewById(R.id.tv_preset_channels)
        val btnDelete: ImageButton = view.findViewById(R.id.btn_delete_preset)
        val btnEdit: ImageButton = view.findViewById(R.id.btn_edit_preset)
        val indicator: View = view.findViewById(R.id.view_active_indicator)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PresetViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_preset, parent, false)
        return PresetViewHolder(view)
    }

    override fun getItemCount(): Int = presets.size

    override fun onBindViewHolder(holder: PresetViewHolder, position: Int) {
        val preset = presets[position]
        val isActive = preset.id == activePresetId
        val context = holder.itemView.context

        holder.tvName.text = preset.name
        holder.indicator.visibility = if (isActive) View.VISIBLE else View.GONE

        val periodText = if (preset.timePeriodIndex in timePeriods.indices)
            timePeriods[preset.timePeriodIndex] else context.getString(R.string.period_minutes, 30)
        holder.tvDetails.text = context.getString(R.string.preset_channels_count, preset.channelIds.size, periodText)

        val names = preset.channelIds
            .mapNotNull { channelNames[it] }
            .take(3)
            .joinToString(", ")
        val suffix = if (preset.channelIds.size > 3)
            context.getString(R.string.and_more_n, preset.channelIds.size - 3) else ""
        holder.tvChannels.text = if (names.isNotEmpty()) "$names$suffix" else context.getString(R.string.channels_not_found)

        holder.itemView.alpha = if (isActive) 1.0f else 0.8f

        holder.itemView.setOnClickListener { onPresetSelected(preset) }
        holder.btnDelete.setOnClickListener { onPresetDelete(preset) }
        holder.btnEdit.setOnClickListener { onPresetEdit(preset) }
    }
}
