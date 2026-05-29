package com.p2petrovich.telegramnewsreader.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.p2petrovich.telegramnewsreader.models.TelegramChannel

@Dao
interface ChannelDao {
    @Insert
    fun insert(channel: TelegramChannel)

    @Insert
    fun insertAll(channels: List<TelegramChannel>)

    @Query("SELECT * FROM channels")
    fun getAll(): List<TelegramChannel>

    @Query("DELETE FROM channels")
    fun deleteAll()

    @Transaction
    fun replaceChannels(channels: List<TelegramChannel>) {
        deleteAll()
        insertAll(channels)
    }
}
