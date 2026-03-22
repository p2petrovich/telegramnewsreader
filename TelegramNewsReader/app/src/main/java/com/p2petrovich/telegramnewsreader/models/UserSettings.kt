package com.p2petrovich.telegramnewsreader.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_settings")
data class UserSettings(
    @PrimaryKey val id: Int = 1,
    val voiceGender: String,
    val speechSpeed: Float,
    val language: String
)
