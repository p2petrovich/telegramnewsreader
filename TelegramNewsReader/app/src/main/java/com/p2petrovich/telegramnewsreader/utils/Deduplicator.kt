package com.p2petrovich.telegramnewsreader.utils

import java.util.LinkedList

class Deduplicator(
    val isEnabled: Boolean = true,
    private val matchThreshold: Float = 0.9f,
    private val historySize: Int = 500,
    private val timeWindowMinutes: Int = 60
) {
    private data class HistoryEntry(
        val fingerprint: String,
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

    private fun normalize(text: String): String {
        return text
            .lowercase()
            .replace(Regex("^\\d{2}:\\d{2}\\s*—\\s*"), "")
            .replace(Regex("[^\\p{L}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun similarity(s1: String, s2: String): Float {
        if (s1 == s2) return 1.0f
        if (s1.isEmpty() || s2.isEmpty()) return 0.0f

        val maxLen = maxOf(s1.length, s2.length)
        val distance = levenshteinDistance(s1, s2)
        return (maxLen - distance).toFloat() / maxLen
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val len1 = s1.length
        val len2 = s2.length
        val dp = Array(len1 + 1) { IntArray(len2 + 1) }

        for (i in 0..len1) dp[i][0] = i
        for (j in 0..len2) dp[0][j] = j

        for (i in 1..len1) {
            for (j in 1..len2) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        return dp[len1][len2]
    }
}
