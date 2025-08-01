package com.example.telegramnewsreader.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.telegramnewsreader.databinding.ItemChannelBinding
import com.example.telegramnewsreader.model.Channel

class ChannelAdapter(
    private val onSelectionChanged: (Channel, Boolean) -> Unit,
    private val onHideRequest: (Channel) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.ChannelViewHolder>() {


    private val channels = mutableListOf<Channel>()

    fun updateChannels(newChannels: List<Channel>) {
        channels.clear()
        channels.addAll(newChannels.sortedBy { it.title.lowercase() }) // ⬅️ сортировка по имени
        notifyDataSetChanged()
    }

    fun getSelectedChannels(): List<Channel> = channels.filter { it.isSelected }

    fun getAllChannels(): List<Channel> = channels.toList()

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

            // ✅ Обновляем количество новых сообщений, если есть
            binding.textNewMessages.text = if (channel.newMessagesCount > 0) {
                "${channel.newMessagesCount} новых"
            } else {
                "Нет новостей"
            }

            // ✅ Плейсхолдер-аватар
            binding.ivAvatar.setImageResource(com.example.telegramnewsreader.R.drawable.ic_channel_placeholder)

            // ✅ Сброс слушателя перед установкой нового
            binding.checkboxChannel.setOnCheckedChangeListener(null)
            binding.checkboxChannel.isChecked = channel.isSelected
            binding.checkboxChannel.setOnCheckedChangeListener { _, isChecked ->
                channel.isSelected = isChecked
                onSelectionChanged(channel, isChecked)
            }

            // ✅ Клик по элементу переключает чекбокс
            binding.root.setOnClickListener {
                binding.checkboxChannel.toggle()
            }

            // ✅ Длинный тап -> запрос на скрытие
            binding.root.setOnLongClickListener {
                onHideRequest(channel)
                true
            }
        }
    }
}