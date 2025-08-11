package com.example.telegramnewsreader.models

/**
 * Модель данных для голоса TTS с понятным названием
 */
data class VoiceEntry(
    val systemName: String,        // Техническое имя голоса в системе
    val displayName: String,       // Понятное название для пользователя
    val gender: Gender,           // Пол голоса
    val language: String = "ru",  // Язык (по умолчанию русский)
    val country: String = "RU",   // Страна
    val isNetwork: Boolean = false // Является ли сетевым голосом
) {
    enum class Gender {
        MALE,     // Мужской
        FEMALE,   // Женский
        NEUTRAL   // Нейтральный
    }
    
    /**
     * Получить иконку в зависимости от пола
     */
    fun getGenderIcon(): String {
        return when (gender) {
            Gender.MALE -> "👨"
            Gender.FEMALE -> "👩"
            Gender.NEUTRAL -> "🤖"
        }
    }
    
    /**
     * Получить описание пола на русском
     */
    fun getGenderDescription(): String {
        return when (gender) {
            Gender.MALE -> "Мужской голос"
            Gender.FEMALE -> "Женский голос" 
            Gender.NEUTRAL -> "Нейтральный голос"
        }
    }

}