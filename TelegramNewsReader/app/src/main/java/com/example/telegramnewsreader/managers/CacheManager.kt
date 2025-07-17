package com.example.telegramnewsreader.managers

import android.content.Context
import java.io.File

class CacheManager(private val context: Context) {
    private val cacheDir = context.cacheDir

    fun cacheMessage(id: Long, text: String) {
        val file = File(cacheDir, "msg_$id.txt")
        file.writeText(text)
    }

    fun getCachedMessage(id: Long): String? {
        val file = File(cacheDir, "msg_$id.txt")
        return if (file.exists()) file.readText() else null
    }

    fun cleanCacheOlderThan(hours: Int) {
        // Логика очистки по времени (из схемы: автоочистка 24ч)
        cacheDir.listFiles()?.forEach {
            if (System.currentTimeMillis() - it.lastModified() > hours * 3600 * 1000) it.delete()
        }
    }
}
