package com.example.telegramnewsreader.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.telegramnewsreader.databinding.ItemChannelBinding
import com.example.telegramnewsreader.model.Channel

class ChannelAdapter(
    private val onSelectionChanged: (Channel, Boolean) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.ChannelViewHolder>() {

    private val channels = mutableListOf<Channel>()

    fun updateChannels(newChannels: List<Channel>) {
        channels.clear()
        channels.addAll(newChannels)
        notifyDataSetChanged()
    }

    fun getSelectedChannels(): List<Channel> = channels.filter { it.isSelected }

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
            binding.textNewMessages.text = "0 новых"

            // Если есть поле avatarUrl, можно подгрузить изображение через Glide/Picasso
            binding.ivAvatar.setImageResource(com.example.telegramnewsreader.R.drawable.ic_channel_placeholder)

            // Отвязываем слушатель, чтобы избежать ложных вызовов при обновлении состояния чекбокса
            binding.checkboxChannel.setOnCheckedChangeListener(null)
            binding.checkboxChannel.isChecked = channel.isSelected
            binding.checkboxChannel.setOnCheckedChangeListener { _, isChecked ->
                channel.isSelected = isChecked
                onSelectionChanged(channel, isChecked)
            }

            // Клик по элементу переключает чекбокс
            binding.root.setOnClickListener {
                binding.checkboxChannel.toggle()
            }
        }
    }
}
