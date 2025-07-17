package com.example.telegramnewsreader.managers

import com.example.telegramnewsreader.models.TelegramChannel

class CategoryManager {
    private val categories = mapOf(
        "Новости" to listOf("news_channel"), // Пример
        "Технологии" to listOf("tech_channel")
        // Добавьте по схеме
    )

    fun getCategoryForChannel(channel: TelegramChannel): String {
        categories.entries.forEach { (cat, keywords) ->
            if (keywords.any { channel.name.contains(it, ignoreCase = true) }) return cat
        }
        return "Другое"
    }

    fun filterByCategory(channels: List<TelegramChannel>, selectedCategories: List<String>): List<TelegramChannel> {
        return channels.filter { selectedCategories.contains(getCategoryForChannel(it)) }
    }
}
