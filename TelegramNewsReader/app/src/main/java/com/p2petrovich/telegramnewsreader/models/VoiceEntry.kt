package com.p2petrovich.telegramnewsreader.models

import android.content.Context
import com.p2petrovich.telegramnewsreader.R

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

    fun getGenderDescription(context: Context): String = when (gender) {
        Gender.MALE -> context.getString(R.string.gender_male)
        Gender.FEMALE -> context.getString(R.string.gender_female)
        Gender.NEUTRAL -> context.getString(R.string.gender_neutral)
    }
}
