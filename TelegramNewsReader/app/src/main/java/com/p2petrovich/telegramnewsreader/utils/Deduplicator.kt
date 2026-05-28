package com.p2petrovich.telegramnewsreader.utils

import java.util.LinkedList

class Deduplicator(
    val isEnabled: Boolean = true,
    private val matchThreshold: Float = 0.9f,
    private val historySize: Int = 500,
    private val timeWindowMinutes: Int = 60
) {
    private data class HistoryEntry(
        val fingerprint: Set<String>,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val history = LinkedList<HistoryEntry>()
    private var skippedCount = 0

    fun isDuplicate(text: String): Boolean {
        if (!isEnabled) return false
        cleanOldEntries()
        val fingerprint = normalize(text)

        if (history.any { it.fingerprint == fingerprint }) {
            skippedCount++
            return true
        }

        if (matchThreshold < 1.0f) {
            val threshold = matchThreshold
            if (history.any { similarity(it.fingerprint, fingerprint) >= threshold }) {
                skippedCount++
                return true
            }
        }

        history.addLast(HistoryEntry(fingerprint))
        if (history.size > historySize) history.removeFirst()
        return false
    }

    fun getSkippedCount(): Int = skippedCount
    fun reset() { history.clear(); skippedCount = 0 }
    fun getHistorySize(): Int = history.size

    private fun cleanOldEntries() {
        val cutoff = System.currentTimeMillis() - timeWindowMinutes * 60 * 1000L
        while (history.isNotEmpty() && history.first().timestamp < cutoff) {
            history.removeFirst()
        }
    }

    private fun normalize(text: String): Set<String> {
        return text
            .lowercase()
            .replace(Regex("^\\d{2}:\\d{2}\\s*—\\s*"), "")
            .replace(Regex("[^\\p{L}\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 3 }
            .toSet()
    }

    private fun similarity(s1: Set<String>, s2: Set<String>): Float {
        if (s1.isEmpty() || s2.isEmpty()) return 0.0f
        val intersect = s1.intersect(s2).size
        val union = s1.union(s2).size
        return intersect.toFloat() / union
    }
}
