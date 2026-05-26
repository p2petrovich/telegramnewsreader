package com.p2petrovich.telegramnewsreader.adapters

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.p2petrovich.telegramnewsreader.R
import com.p2petrovich.telegramnewsreader.databinding.ItemChannelBinding
import com.p2petrovich.telegramnewsreader.models.Channel
import com.p2petrovich.telegramnewsreader.utils.PreferenceManager
import java.io.File

class ChannelAdapter(
    private val context: Context,
    private val onSelectionChanged: (Channel, Boolean) -> Unit,
    private val onHideRequest: (Channel) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.ChannelViewHolder>() {

    private val allChannels = mutableListOf<Channel>()
    private val displayedChannels = mutableListOf<Channel>()
    private var isFiltered = false

    fun updateChannels(newChannels: List<Channel>) {
        newChannels.forEach { it.isFavorite = PreferenceManager.isChannelFavorite(context, it.id) }
        val sorted = newChannels.sortedWith(
            compareBy<Channel> { !it.isFavorite }.thenBy { it.title.lowercase() }
        )
        allChannels.clear()
        allChannels.addAll(sorted)
        if (isFiltered) {
            val filterIds = displayedChannels.map { it.id }.toSet()
            applyDisplayList(sorted.filter { it.id in filterIds })
        } else {
            applyDisplayList(sorted)
        }
    }

    fun filterByPreset(channelIds: Set<Long>) {
        isFiltered = true
        val filtered = allChannels.filter { it.id in channelIds }
        applyDisplayList(filtered)
    }

    fun clearFilter() {
        isFiltered = false
        applyDisplayList(allChannels.toList())
    }

    fun isFilterActive(): Boolean = isFiltered
    fun getSelectedChannels(): List<Channel> = allChannels.filter { it.isSelected }
    fun getAllChannels(): List<Channel> = allChannels.toList()

    fun updateChannelPhoto(channelId: Long, path: String) {
        val allIdx = allChannels.indexOfFirst { it.id == channelId }
        if (allIdx >= 0) allChannels[allIdx].photoPath = path
        val dispIdx = displayedChannels.indexOfFirst { it.id == channelId }
        if (dispIdx >= 0) {
            displayedChannels[dispIdx].photoPath = path
            notifyItemChanged(dispIdx)
        }
    }

    /**
     * Точечное обновление всех видимых элементов — нужно, когда поменялось
     * что-то у самих объектов Channel (например, newMessagesCount после
     * фоновой загрузки) и нужно перерисовать видимые холдеры.
     */
    fun refreshVisibleItems() {
        if (displayedChannels.isNotEmpty()) {
            notifyItemRangeChanged(0, displayedChannels.size)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val binding = ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ChannelViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        holder.bind(displayedChannels[position])
    }

    override fun getItemCount(): Int = displayedChannels.size

    inner class ChannelViewHolder(private val binding: ItemChannelBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(channel: Channel) {
            binding.textChannelName.text = channel.title
            binding.textNewMessages.text = if (channel.newMessagesCount > 0)
                context.getString(R.string.channel_news_count, channel.newMessagesCount)
            else
                context.getString(R.string.channel_no_news)

            channel.isFavorite = PreferenceManager.isChannelFavorite(context, channel.id)
            if (channel.isFavorite) {
                binding.imageFavorite.setIconResource(R.drawable.ic_star)
                binding.imageFavorite.visibility = View.VISIBLE
            } else {
                binding.imageFavorite.visibility = View.GONE
            }

            val path = channel.photoPath
            if (!path.isNullOrBlank()) {
                val f = File(path)
                binding.ivAvatar.load(f) {
                    placeholder(R.drawable.ic_channel_placeholder)
                    error(R.drawable.ic_channel_placeholder)
                    transformations(CircleCropTransformation())
                    memoryCacheKey("${f.absolutePath}#${f.lastModified()}")
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
                channel.isFavorite = !channel.isFavorite
                if (channel.isFavorite)
                    PreferenceManager.addFavoriteChannel(context, channel.id)
                else
                    PreferenceManager.removeFavoriteChannel(context, channel.id)
                resortAndUpdate()
            }
            binding.root.setOnLongClickListener {
                onHideRequest(channel)
                true
            }
        }
    }

    private fun applyDisplayList(newDisplayed: List<Channel>) {
        val diffResult = DiffUtil.calculateDiff(ChannelDiffCallback(displayedChannels.toList(), newDisplayed))
        displayedChannels.clear()
        displayedChannels.addAll(newDisplayed)
        diffResult.dispatchUpdatesTo(this)
    }

    private fun resortAndUpdate() {
        val sortedAll = allChannels.sortedWith(
            compareBy<Channel> { !it.isFavorite }.thenBy { it.title.lowercase() }
        )
        allChannels.clear()
        allChannels.addAll(sortedAll)
        val newDisplayed = if (isFiltered) {
            val displayedIds = displayedChannels.map { it.id }.toSet()
            sortedAll.filter { it.id in displayedIds }
        } else sortedAll.toList()
        applyDisplayList(newDisplayed)
    }

    private class ChannelDiffCallback(
        private val oldList: List<Channel>,
        private val newList: List<Channel>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = oldList.size
        override fun getNewListSize() = newList.size
        override fun areItemsTheSame(oldPos: Int, newPos: Int) = oldList[oldPos].id == newList[newPos].id
        override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
            val old = oldList[oldPos]
            val new = newList[newPos]
            return old.title == new.title && old.isSelected == new.isSelected &&
                    old.isFavorite == new.isFavorite && old.newMessagesCount == new.newMessagesCount &&
                    old.photoPath == new.photoPath
        }
    }
}
