// models/ChannelPreset.kt

package com.p2petrovich.telegramnewsreader.models

data class ChannelPreset(
    val id: String,
    val name: String,
    val channelIds: Set<Long>,
    val timePeriodIndex: Int,
    val createdAt: Long = System.currentTimeMillis()
)
