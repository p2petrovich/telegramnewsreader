package com.example.telegramnewsreader.managers

class SpamFilter {
    private val spamKeywords = listOf("реклама", "купить", "промо") // Из схемы

    fun isSpam(text: String): Boolean {
        return spamKeywords.any { text.contains(it, ignoreCase = true) } || text.length < 10 || text.all { it.isDigit() || it.isWhitespace() || it.isLetterOrDigit().not() } // Фильтр по длине/эмоји
    }
}
