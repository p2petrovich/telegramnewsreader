package com.p2petrovich.telegramnewsreader.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.p2petrovich.telegramnewsreader.models.TelegramChannel

@Dao
interface ChannelDao {
    @Insert
    fun insert(channel: TelegramChannel)

    @Query("SELECT * FROM channels")
    fun getAll(): List<TelegramChannel>
}
