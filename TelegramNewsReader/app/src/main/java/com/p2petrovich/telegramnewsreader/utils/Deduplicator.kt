package com.p2petrovich.telegramnewsreader.utils

import java.util.LinkedList

class Deduplicator(
    val isEnabled: Boolean = true,
    private val matchThreshold: Float = 0.9f,   // оставлен для совместимости конструктора
    private val historySize: Int = 500,
    private val timeWindowMinutes: Int = 60
) {
    private data class HistoryEntry(
        val fingerprint: TextProcessor.Fingerprint,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val history = LinkedList<HistoryEntry>()
    private var skippedCount = 0

    fun isDuplicate(text: String): Boolean = synchronized(this) {
        if (!isEnabled) return false
        cleanOldEntries()

        val fingerprint = TextProcessor.extractFingerprint(text)

        // Слишком мало признаков — не сравниваем, считаем уникальным
        if (fingerprint.words.size < 3) {
            if (DebugConfig.LOG_DEDUP_DETAILS) {
                Logx.d("Deduplicator") { "SKIP CHECK (too_few_words=${fingerprint.words.size})" }
            }
            return false
        }

        val match = history.firstOrNull { TextProcessor.isSameEvent(it.fingerprint, fingerprint, matchThreshold.toDouble()) }
        if (match != null) {
            val common = fingerprint.anchors.intersect(match.fingerprint.anchors)
            if (DebugConfig.LOG_DEDUP_DETAILS) {
                Logx.d("Deduplicator") { "MATCH (anchors=$common, skipped=$skippedCount, history_size=${history.size})" }
            }
            skippedCount++
            return true
        }

        return false
    }

    /**
     * Добавляет новость в историю "прочитанных".
     * Должно вызываться только когда новость реально попала в плейлист/была прослушана.
     */
    fun addToHistory(text: String) = synchronized(this) {
        if (!isEnabled) return
        val fingerprint = TextProcessor.extractFingerprint(text)
        
        // Не добавляем в историю то, что уже там есть или слишком коротко
        if (fingerprint.words.size >= 3 && history.none { TextProcessor.isSameEvent(it.fingerprint, fingerprint, matchThreshold.toDouble()) }) {
            if (DebugConfig.LOG_DEDUP_DETAILS) {
                Logx.d("Deduplicator") { "ADD to history (history_size=${history.size})" }
            }
            history.addLast(HistoryEntry(fingerprint))
            if (history.size > historySize) history.removeFirst()
        }
    }

    fun getSkippedCount(): Int = synchronized(this) { skippedCount }

    fun resetSkippedCount() = synchronized(this) {
        skippedCount = 0
    }

    fun reset() = synchronized(this) {
        Logx.d("Deduplicator") { "History reset requested. Current size: ${history.size}" }
        val oldSize = history.size
        history.clear()
        skippedCount = 0
        Logx.d("Deduplicator") { "History reset completed. Previous size: $oldSize" }
    }

    fun getHistorySize(): Int = synchronized(this) { history.size }

    private fun cleanOldEntries() {
        val cutoff = System.currentTimeMillis() - timeWindowMinutes * 60 * 1000L
        while (history.isNotEmpty() && history.first().timestamp < cutoff) {
            history.removeFirst()
        }
    }
}
