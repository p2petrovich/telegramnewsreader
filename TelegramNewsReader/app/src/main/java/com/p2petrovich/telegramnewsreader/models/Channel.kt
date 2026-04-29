package com.p2petrovich.telegramnewsreader.models

data class Channel(
    val id: Long,
    val accessHash: Long,
    val title: String,
    val username: String? = null,
    var isSelected: Boolean = false,
    var newMessagesCount: Int = 0,
    var photoPath: String? = null,
    var isFavorite: Boolean = false
)
