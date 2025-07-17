package com.example.telegramnewsreader.models

data class NewsMessage(
    val id: Long,
    val channelId: Long,
    val text: String,
    val date: Long,
    val isSpam: Boolean,
    val similarityHash: String
)
