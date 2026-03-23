package com.p2petrovich.telegramnewsreader.models

data class VoiceEntry(
    val systemName: String,
    val displayName: String,
    val gender: Gender,
    val language: String = "ru",
    val country: String = "RU",
    val isNetwork: Boolean = false
) {
    enum class Gender {
        MALE, FEMALE, NEUTRAL
    }

    fun getGenderIcon(): String = when (gender) {
        Gender.MALE -> "👨"
        Gender.FEMALE -> "👩"
        Gender.NEUTRAL -> "🎙️"
    }

    fun getGenderDescription(): String = when (gender) {
        Gender.MALE -> "Мужской голос"
        Gender.FEMALE -> "Женский голос"
        Gender.NEUTRAL -> "Нейтральный голос"
    }
}
