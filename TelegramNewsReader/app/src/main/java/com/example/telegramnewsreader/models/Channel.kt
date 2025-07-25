package com.example.telegramnewsreader.model

data class Channel(
    val id: Long,
    val accessHash: Long,
    val title: String,
    val username: String? = null,
    var isSelected: Boolean = false,
    var newMessagesCount: Int = 0
)
