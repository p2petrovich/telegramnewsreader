package com.example.telegramnewsreader.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.telegramnewsreader.models.TelegramChannel
import com.example.telegramnewsreader.models.NewsMessage
import com.example.telegramnewsreader.models.AudioTrack
import com.example.telegramnewsreader.models.UserSettings

@Database(entities = [TelegramChannel::class, NewsMessage::class, AudioTrack::class, UserSettings::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun channelDao(): ChannelDao
    // Добавьте DAO для других сущностей
}
