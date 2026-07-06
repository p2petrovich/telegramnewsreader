package com.p2petrovich.telegramnewsreader.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Сущность для хранения истории дедупликации в БД.
 * Позволяет сохранять "отпечатки" прослушанных новостей между перезапусками приложения.
 */
@Entity(tableName = "dedup_history")
data class DedupEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val words: String,         // Сериализованный Set<String>
    val anchors: String,       // Сериализованный Set<String> (устаревшее, для совместимости)
    val numbers: String,       // Сериализованный Set<String>
    val strongAnchors: String, // Сериализованный Set<String>
    val timestamp: Long = System.currentTimeMillis()
)
