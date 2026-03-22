package com.p2petrovich.telegramnewsreader.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
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

    fun updateChannels(newChannels: List<Channel>) {
        newChannels.forEach { it.isFavorite = PreferenceManager.isChannelFavorite(context, it.id) }

        val sorted = newChannels.sortedWith(
            compareBy<Channel> { !it.isFavorite }.thenBy { it.title.lowercase() }
        )

        val diffResult = DiffUtil.calculateDiff(ChannelDiffCallback(channels.toList(), sorted))
        channels.clear()
        channels.addAll(sorted)
        diffResult.dispatchUpdatesTo(this)
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
            binding.textNewMessages.text = if (channel.newMessagesCount > 0)
                "${channel.newMessagesCount} новых" else "Нет новостей"

            // Favorite icon
            channel.isFavorite = PreferenceManager.isChannelFavorite(context, channel.id)
            if (channel.isFavorite) {
                binding.imageFavorite.setImageResource(R.drawable.ic_star)
                binding.imageFavorite.visibility = android.view.View.VISIBLE
            } else {
                binding.imageFavorite.visibility = android.view.View.GONE
            }

            // Avatar
            val path = channel.photoPath
            if (!path.isNullOrBlank()) {
                val f = File(path)
                binding.ivAvatar.load(f) {
                    placeholder(R.drawable.ic_channel_placeholder)
                    error(R.drawable.ic_channel_placeholder)
                    transformations(CircleCropTransformation())
                    memoryCacheKey(f.absolutePath + "#" + f.lastModified())
                }
            } else {
                binding.ivAvatar.setImageResource(R.drawable.ic_channel_placeholder)
            }

            // Checkbox
            binding.checkboxChannel.setOnCheckedChangeListener(null)
            binding.checkboxChannel.isChecked = channel.isSelected
            binding.checkboxChannel.setOnCheckedChangeListener { _, isChecked ->
                channel.isSelected = isChecked
                onSelectionChanged(channel, isChecked)
            }

            // Favorite toggle on click
            binding.root.setOnClickListener {
                channel.isFavorite = !channel.isFavorite
                if (channel.isFavorite) {
                    PreferenceManager.addFavoriteChannel(context, channel.id)
                } else {
                    PreferenceManager.removeFavoriteChannel(context, channel.id)
                }
                resortAndUpdate()
            }

            binding.root.setOnLongClickListener {
                onHideRequest(channel)
                true
            }
        }
    }

    private fun resortAndUpdate() {
        val sorted = channels.sortedWith(
            compareBy<Channel> { !it.isFavorite }.thenBy { it.title.lowercase() }
        )
        val diffResult = DiffUtil.calculateDiff(ChannelDiffCallback(channels.toList(), sorted))
        channels.clear()
        channels.addAll(sorted)
        diffResult.dispatchUpdatesTo(this)
    }

    private class ChannelDiffCallback(
        private val oldList: List<Channel>,
        private val newList: List<Channel>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = oldList.size
        override fun getNewListSize() = newList.size
        override fun areItemsTheSame(oldPos: Int, newPos: Int) = oldList[oldPos].id == newList[newPos].id
        override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
            val old = oldList[oldPos]; val new = newList[newPos]
            return old.title == new.title && old.isSelected == new.isSelected &&
                   old.isFavorite == new.isFavorite && old.newMessagesCount == new.newMessagesCount &&
                   old.photoPath == new.photoPath
        }
    }
}
