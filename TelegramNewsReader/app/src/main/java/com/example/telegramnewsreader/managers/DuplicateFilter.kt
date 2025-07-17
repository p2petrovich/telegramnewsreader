package com.example.telegramnewsreader.managers

import java.security.MessageDigest

class DuplicateFilter {
    private val hashes = mutableSetOf<String>()

    fun isDuplicate(text: String, existing: List<String>): Boolean {
        val hash = text.toMD5()
        if (hashes.contains(hash)) return true
        existing.forEach {
            if (levenshteinDistance(text, it) < text.length * 0.2) return true // <80% схожесть
        }
        hashes.add(hash)
        return false
    }

    private fun String.toMD5(): String {
        return MessageDigest.getInstance("MD5").digest(this.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        // Простая реализация Levenshtein (из схемы)
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) { 0 } }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j
        for (i in 1..s1.length) for (j in 1..s2.length) {
            val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
            dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
        }
        return dp[s1.length][s2.length]
    }
}
