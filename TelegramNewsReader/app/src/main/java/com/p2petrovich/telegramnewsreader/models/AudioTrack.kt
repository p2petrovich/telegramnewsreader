package com.p2petrovich.telegramnewsreader.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "audio_tracks")
data class AudioTrack(
    @PrimaryKey val id: Long,
    val name: String,
    val filePath: String,
    val duration: Long
)
