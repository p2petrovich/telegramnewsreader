package com.example.telegramnewsreader.managers

import java.io.File

class FavoriteManager {
    private val favorites = mutableListOf<File>() // Или используйте БД

    fun addFavorite(file: File) {
        favorites.add(file)
    }

    fun getFavorites(): List<File> = favorites

    fun removeFavorite(file: File) {
        favorites.remove(file)
    }
}
