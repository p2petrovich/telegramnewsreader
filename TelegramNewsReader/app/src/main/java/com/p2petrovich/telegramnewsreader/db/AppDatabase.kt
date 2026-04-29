package com.p2petrovich.telegramnewsreader.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.p2petrovich.telegramnewsreader.models.TelegramChannel
import com.p2petrovich.telegramnewsreader.models.NewsMessage
import com.p2petrovich.telegramnewsreader.models.AudioTrack
import com.p2petrovich.telegramnewsreader.models.UserSettings

@Database(
    entities = [TelegramChannel::class, NewsMessage::class, AudioTrack::class, UserSettings::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun channelDao(): ChannelDao
    // Добавьте DAO для других сущностей

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "telegram_news_db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
