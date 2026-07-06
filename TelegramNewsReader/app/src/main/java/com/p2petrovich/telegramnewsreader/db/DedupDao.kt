package com.p2petrovich.telegramnewsreader.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface DedupDao {
    @Insert
    fun insert(entity: DedupEntity)

    @Query("SELECT * FROM dedup_history ORDER BY timestamp DESC LIMIT :limit")
    fun getRecent(limit: Int): List<DedupEntity>

    @Query("DELETE FROM dedup_history WHERE timestamp < :cutoff")
    fun deleteOldEntries(cutoff: Long)

    @Query("DELETE FROM dedup_history")
    fun deleteAll()

    @Query("SELECT COUNT(*) FROM dedup_history")
    fun getCount(): Int
}
