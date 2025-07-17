package com.example.telegramnewsreader.models

data class UserSettings(
    val voiceGender: String, // "male" or "female"
    val speechSpeed: Float,
    val language: String
)
