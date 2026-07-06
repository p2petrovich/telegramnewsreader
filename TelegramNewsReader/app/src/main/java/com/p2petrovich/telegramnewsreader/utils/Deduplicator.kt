package com.p2petrovich.telegramnewsreader.utils

import android.content.Context
import com.p2petrovich.telegramnewsreader.db.AppDatabase
import com.p2petrovich.telegramnewsreader.db.DedupEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.LinkedList

class Deduplicator(
    private val context: Context,
    val isEnabled: Boolean = true,
    private val matchThreshold: Float = 0.6f,
    private val historySize: Int = 500,
    private val timeWindowMinutes: Int = 60
) {
    private val TAG = "Deduplicator"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val db by lazy { AppDatabase.getInstance(context).dedupDao() }

    private data class HistoryEntry(
        val fingerprint: TextProcessor.Fingerprint,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val history = LinkedList<HistoryEntry>()
    private var skippedCount = 0
    private var isInitialized = false

    init {
        loadFromDb()
    }

    private fun loadFromDb() {
        scope.launch {
            try {
                val recent = db.getRecent(historySize)
                synchronized(this@Deduplicator) {
                    recent.reversed().forEach { entity ->
                        val fp = TextProcessor.Fingerprint(
                            words = entity.words.split("|").filter { it.isNotEmpty() }.toSet(),
                            anchors = entity.anchors.split("|").filter { it.isNotEmpty() }.toSet(),
                            numbers = entity.numbers.split("|").filter { it.isNotEmpty() }.toSet(),
                            strongAnchors = entity.strongAnchors.split("|").filter { it.isNotEmpty() }.toSet()
                        )
                        history.addLast(HistoryEntry(fp, entity.timestamp))
                    }
                    isInitialized = true
                }
                Logx.d(TAG) { "Loaded ${recent.size} entries from DB" }
            } catch (e: Exception) {
                Logx.e(TAG, "Failed to load history from DB", e)
            }
        }
    }

    fun isDuplicate(text: String): Boolean = synchronized(this) {
        if (!isEnabled) return false
        cleanOldEntries()

        val fingerprint = TextProcessor.extractFingerprint(text)

        if (fingerprint.words.size < 3) {
            if (DebugConfig.LOG_DEDUP_DETAILS) {
                Logx.d(TAG) { "SKIP CHECK (too_few_words=${fingerprint.words.size})" }
            }
            return false
        }

        val match = history.firstOrNull { TextProcessor.isSameEvent(it.fingerprint, fingerprint, matchThreshold.toDouble()) }
        if (match != null) {
            if (DebugConfig.LOG_DEDUP_DETAILS) {
                Logx.d(TAG) { "MATCH (skipped=$skippedCount, history_size=${history.size})" }
            }
            skippedCount++
            return true
        }

        return false
    }

    fun addToHistory(text: String) = synchronized(this) {
        if (!isEnabled) return
        val fingerprint = TextProcessor.extractFingerprint(text)
        
        if (fingerprint.words.size >= 3 && history.none { TextProcessor.isSameEvent(it.fingerprint, fingerprint, matchThreshold.toDouble()) }) {
            val entry = HistoryEntry(fingerprint)
            history.addLast(entry)
            if (history.size > historySize) history.removeFirst()

            // Save to DB
            scope.launch {
                try {
                    db.insert(DedupEntity(
                        words = fingerprint.words.joinToString("|"),
                        anchors = fingerprint.anchors.joinToString("|"),
                        numbers = fingerprint.numbers.joinToString("|"),
                        strongAnchors = fingerprint.strongAnchors.joinToString("|"),
                        timestamp = entry.timestamp
                    ))
                    // Periodically clean old entries in DB
                    if (Math.random() < 0.05) { // 5% chance to trigger cleanup on add
                        val cutoff = System.currentTimeMillis() - timeWindowMinutes * 60 * 1000L
                        db.deleteOldEntries(cutoff)
                    }
                } catch (e: Exception) {
                    Logx.e(TAG, "Failed to save entry to DB", e)
                }
            }
        }
    }

    fun getSkippedCount(): Int = synchronized(this) { skippedCount }

    fun resetSkippedCount() = synchronized(this) {
        skippedCount = 0
    }

    fun reset() = synchronized(this) {
        Logx.d(TAG) { "History reset requested. Current size: ${history.size}" }
        history.clear()
        skippedCount = 0
        scope.launch {
            try {
                db.deleteAll()
            } catch (e: Exception) {
                Logx.e(TAG, "Failed to clear DB history", e)
            }
        }
    }

    fun getHistorySize(): Int = synchronized(this) { history.size }

    private fun cleanOldEntries() {
        val cutoff = System.currentTimeMillis() - timeWindowMinutes * 60 * 1000L
        while (history.isNotEmpty() && history.first().timestamp < cutoff) {
            history.removeFirst()
        }
    }
}
