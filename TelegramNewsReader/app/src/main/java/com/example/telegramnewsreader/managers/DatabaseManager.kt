package com.example.telegramnewsreader.managers

import android.content.Context
import androidx.room.Room
import com.example.telegramnewsreader.db.AppDatabase
import com.example.telegramnewsreader.models.TelegramChannel

class DatabaseManager(private val context: Context) {
    private val db = Room.databaseBuilder(context, AppDatabase::class.java, "app_db").build()

    fun insertChannel(channel: TelegramChannel) {
        db.channelDao().insert(channel)
    }

    fun getAllChannels(): List<TelegramChannel> = db.channelDao().getAll()

    // Добавьте методы для других таблиц
}