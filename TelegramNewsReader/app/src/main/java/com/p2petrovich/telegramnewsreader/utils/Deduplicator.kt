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

    fun isDuplicate(text: String): Boolean {
        if (!isEnabled) return false
        cleanOldEntries()

        val fingerprint = TextProcessor.extractFingerprint(text)

        // Слишком мало признаков — не сравниваем, считаем уникальным
        if (fingerprint.words.size < 3) {
            history.addLast(HistoryEntry(fingerprint))
            if (history.size > historySize) history.removeFirst()
            return false
        }

        val match = history.firstOrNull { TextProcessor.isSameEvent(it.fingerprint, fingerprint, matchThreshold.toDouble()) }
        if (match != null) {
            val common = fingerprint.anchors.intersect(match.fingerprint.anchors)
            Logx.d("Deduplicator") { "MATCH (anchors=$common, skipped=$skippedCount)" }
            skippedCount++
            return true
        }

        history.addLast(HistoryEntry(fingerprint))
        if (history.size > historySize) history.removeFirst()
        return false
    }

    fun getSkippedCount(): Int = skippedCount

    fun reset() {
        Logx.d("Deduplicator") { "History reset requested. Current size: ${history.size}" }
        history.clear()
        skippedCount = 0
    }

    fun getHistorySize(): Int = history.size

    private fun cleanOldEntries() {
        val cutoff = System.currentTimeMillis() - timeWindowMinutes * 60 * 1000L
        while (history.isNotEmpty() && history.first().timestamp < cutoff) {
            history.removeFirst()
        }
    }
}
