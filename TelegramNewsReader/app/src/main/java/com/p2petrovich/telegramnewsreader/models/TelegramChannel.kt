package com.p2petrovich.telegramnewsreader.models

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize
import android.os.Parcelable

@Parcelize
@Entity(tableName = "channels")
data class TelegramChannel(
    @PrimaryKey val id: Long,
    val name: String,
    val username: String,
    val category: String,
    val selected: Boolean,
    val lastSync: Long
) : Parcelable