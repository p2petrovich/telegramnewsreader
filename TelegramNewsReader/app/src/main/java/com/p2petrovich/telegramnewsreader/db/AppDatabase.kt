package com.p2petrovich.telegramnewsreader.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.p2petrovich.telegramnewsreader.models.TelegramChannel
import com.p2petrovich.telegramnewsreader.models.NewsMessage
import com.p2petrovich.telegramnewsreader.models.AudioTrack
import com.p2petrovich.telegramnewsreader.models.UserSettings

@Database(entities = [TelegramChannel::class, NewsMessage::class, AudioTrack::class, UserSettings::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun channelDao(): ChannelDao
    // Добавьте DAO для других сущностей
}
