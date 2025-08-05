package com.example.telegramnewsreader.adapter

import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.telegramnewsreader.databinding.ItemChannelBinding
import com.example.telegramnewsreader.model.Channel
import coil.load
import coil.transform.CircleCropTransformation
import com.example.telegramnewsreader.R
import java.io.File

class ChannelAdapter(
    private val onSelectionChanged: (Channel, Boolean) -> Unit,
    private val onHideRequest: (Channel) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.ChannelViewHolder>() {


    private val channels = mutableListOf<Channel>()

    fun updateChannels(newChannels: List<Channel>) {
        Log.d("ChannelAdapter", "updateChannels: size=${newChannels.size}")
        channels.clear()
        channels.addAll(newChannels.sortedBy { it.title.lowercase() })
        notifyDataSetChanged()
    }

    fun getSelectedChannels(): List<Channel> = channels.filter { it.isSelected }

    fun getAllChannels(): List<Channel> = channels.toList()

    fun updateChannelPhoto(channelId: Long, path: String) {
        val idx = channels.indexOfFirst { it.id == channelId }
        if (idx >= 0) {
            channels[idx].photoPath = path
            notifyItemChanged(idx)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val binding = ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ChannelViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        holder.bind(channels[position])
    }

    override fun getItemCount(): Int = channels.size

    inner class ChannelViewHolder(private val binding: ItemChannelBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(channel: Channel) {
            binding.textChannelName.text = channel.title

            binding.textNewMessages.text = if (channel.newMessagesCount > 0) {
                "${channel.newMessagesCount} новых"
            } else {
                "Нет новостей"
            }

            val path = channel.photoPath
            if (!path.isNullOrBlank()) {
                val f = File(path)
                binding.ivAvatar.load(f) {
                    placeholder(R.drawable.ic_channel_placeholder)
                    error(R.drawable.ic_channel_placeholder)
                    transformations(CircleCropTransformation())
                    val key = f.absolutePath + "#" + f.lastModified()
                    memoryCacheKey(key)
                    diskCacheKey(key)
                }
            } else {
                binding.ivAvatar.setImageResource(R.drawable.ic_channel_placeholder)
            }

            binding.checkboxChannel.setOnCheckedChangeListener(null)
            binding.checkboxChannel.isChecked = channel.isSelected
            binding.checkboxChannel.setOnCheckedChangeListener { _, isChecked ->
                channel.isSelected = isChecked
                onSelectionChanged(channel, isChecked)
            }

            binding.root.setOnClickListener {
                binding.checkboxChannel.toggle()
            }

            binding.root.setOnLongClickListener {
                onHideRequest(channel)
                true
            }
        }
    }
}