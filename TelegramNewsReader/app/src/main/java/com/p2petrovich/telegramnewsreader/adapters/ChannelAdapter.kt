package com.p2petrovich.telegramnewsreader.adapter

import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.p2petrovich.telegramnewsreader.databinding.ItemChannelBinding
import com.p2petrovich.telegramnewsreader.model.Channel
import com.p2petrovich.telegramnewsreader.utils.PreferenceManager
import coil.load
import coil.transform.CircleCropTransformation
import com.p2petrovich.telegramnewsreader.R
import java.io.File

class ChannelAdapter(
    private val context: Context,
    private val onSelectionChanged: (Channel, Boolean) -> Unit,
    private val onHideRequest: (Channel) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.ChannelViewHolder>() {

    private val channels = mutableListOf<Channel>()

    // 🔥 НОВОЕ: Callback для избранного
    private var onFavoriteClick: ((Channel) -> Unit)? = null

    fun setOnFavoriteClickListener(onFavoriteClick: (Channel) -> Unit) {
        this.onFavoriteClick = onFavoriteClick
    }

    fun updateChannels(newChannels: List<Channel>) {
        Log.d("ChannelAdapter", "updateChannels: size=${newChannels.size}")
        channels.clear()

        // 🔥 Обновляем флаги избранного для всех каналов
        newChannels.forEach { channel ->
            channel.isFavorite = PreferenceManager.isChannelFavorite(context, channel.id)
        }

        // 🔥 Сначала избранные, потом остальные (все по алфавиту)
        val sortedChannels = newChannels.sortedWith(
            compareBy<Channel> { !it.isFavorite }.thenBy { it.title.lowercase() }
        )

        channels.addAll(sortedChannels)
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

            // 🔥 НОВОЕ: Проверка избранного при биндинге
            channel.isFavorite = PreferenceManager.isChannelFavorite(context, channel.id)
            if (channel.isFavorite) {
                binding.imageFavorite.setImageResource(R.drawable.ic_star)
                binding.imageFavorite.visibility = android.view.View.VISIBLE
            } else {
                binding.imageFavorite.visibility = android.view.View.GONE
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

            // 🔥 ИЗМЕНЕНО: Клик переключает избранное, долгий клик - скрытие
            binding.root.setOnClickListener {
                // Переключаем избранное
                channel.isFavorite = !channel.isFavorite
                if (channel.isFavorite) {
                    PreferenceManager.addFavoriteChannel(context, channel.id)
                    binding.imageFavorite.setImageResource(R.drawable.ic_star)
                    binding.imageFavorite.visibility = android.view.View.VISIBLE
                } else {
                    PreferenceManager.removeFavoriteChannel(context, channel.id)
                    binding.imageFavorite.visibility = android.view.View.GONE
                }
                // 🔥 Пересортировка после изменения избранного
                resortChannels()
                onFavoriteClick?.invoke(channel)
            }

            binding.root.setOnLongClickListener {
                onHideRequest(channel)
                true
            }
        }

        // 🔥 НОВОЕ: Метод для пересортировки каналов
        private fun resortChannels() {
            val sortedChannels = channels.sortedWith(
                compareBy<Channel> { !it.isFavorite }.thenBy { it.title.lowercase() }
            )
            channels.clear()
            channels.addAll(sortedChannels)
            notifyDataSetChanged()
        }
    }

    // 🔥 НОВОЕ: Публичный метод для пересортировки извне
    fun resortChannels() {
        val sortedChannels = channels.sortedWith(
            compareBy<Channel> { !it.isFavorite }.thenBy { it.title.lowercase() }
        )
        channels.clear()
        channels.addAll(sortedChannels)
        notifyDataSetChanged()
    }
}