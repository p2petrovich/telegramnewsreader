package com.p2petrovich.telegramnewsreader.utils

import android.content.Context
import java.io.File
import java.security.MessageDigest

object NewsCache {

    private const val TAG = "NewsCache"
    private const val CACHE_DIR = "news_wav_cache"
    private const val MAX_CACHE_FILES = 500
    private const val MAX_CACHE_SIZE_MB = 300

    fun getCacheDir(context: Context): File {
        val dir = File(context.cacheDir, CACHE_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun messageHash(text: String, voiceName: String?, pitch: Float, rate: Float): String {
        val content = "${voiceName ?: "default"}|$pitch|$rate|$text"
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(content.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) } // Используем полный хэш (256 бит) вместо 20 символов
    }

    fun findCachedWav(context: Context, hash: String): File? {
        val file = File(getCacheDir(context), "msg_$hash.wav")
        return if (file.exists() && file.length() > 0) {
            file.setLastModified(System.currentTimeMillis())
            Logx.d(TAG) { "Cache HIT: $hash" }
            file
        } else null
    }

    fun saveWavToCache(context: Context, hash: String, sourceWav: File): File? {
        val cacheFile = File(getCacheDir(context), "msg_$hash.wav")
        return try {
            if (sourceWav.absolutePath == cacheFile.absolutePath) return cacheFile
            sourceWav.copyTo(cacheFile, overwrite = true)
            Logx.d(TAG) { "Cached: $hash (${cacheFile.length() / 1024} KB)" }
            cacheFile
        } catch (e: Exception) {
            Logx.e(TAG, "Failed to cache", e)
            null
        }
    }

    fun cleanup(context: Context) {
        val dir = getCacheDir(context)
        val files = dir.listFiles()?.toMutableList() ?: return
        files.sortBy { it.lastModified() }

        while (files.size > MAX_CACHE_FILES) {
            val old = files.removeAt(0)
            try { old.delete() } catch (_: Exception) {}
        }

        var totalBytes = files.sumOf { it.length() }
        val maxBytes = MAX_CACHE_SIZE_MB.toLong() * 1024 * 1024
        while (totalBytes > maxBytes && files.isNotEmpty()) {
            val old = files.removeAt(0)
            totalBytes -= old.length()
            try { old.delete() } catch (_: Exception) {}
        }
    }

    fun getStats(context: Context): Pair<Int, Long> {
        val files = getCacheDir(context).listFiles() ?: return 0 to 0L
        return files.size to files.sumOf { it.length() }
    }

    fun clearAll(context: Context) {
        getCacheDir(context).listFiles()?.forEach {
            try { it.delete() } catch (_: Exception) {}
        }
    }
}
