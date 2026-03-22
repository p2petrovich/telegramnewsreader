package com.p2petrovich.telegramnewsreader.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "news_messages")
data class NewsMessage(
    @PrimaryKey val id: Long,
    val channelId: Long,
    val text: String,
    val date: Long,
    val isSpam: Boolean,
    val similarityHash: String
)
