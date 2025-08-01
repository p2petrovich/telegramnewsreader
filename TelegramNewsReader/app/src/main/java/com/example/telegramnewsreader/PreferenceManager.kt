package com.example.telegramnewsreader.utils

import android.content.Context
import android.content.SharedPreferences

object PreferenceManager {
    private const val PREFS_NAME = "telegram_news_prefs"
    private const val KEY_IS_AUTHORIZED = "is_authorized"
    private const val KEY_PHONE_NUMBER = "phone_number"
    private const val KEY_API_ID = "api_id"
    private const val KEY_API_HASH = "api_hash"


    private const val KEY_TTS_VOICE_NAME = "tts_voice_name"
    private const val KEY_TTS_PITCH = "tts_pitch"
    private const val KEY_TTS_RATE = "tts_rate"

    // Новые ключи для скрытых каналов
    private const val KEY_HIDDEN_USERNAMES = "hidden_usernames"
    private const val KEY_HIDDEN_IDS = "hidden_ids"

    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isAuthorized(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_IS_AUTHORIZED, false)
    }

    fun setAuthorized(context: Context, isAuthorized: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_IS_AUTHORIZED, isAuthorized)
            .apply()
    }

    fun savePhoneNumber(context: Context, phoneNumber: String) {
        getPreferences(context).edit()
            .putString(KEY_PHONE_NUMBER, phoneNumber)
            .apply()
    }

    fun getPhoneNumber(context: Context): String? {
        return getPreferences(context).getString(KEY_PHONE_NUMBER, null)
    }

    fun saveApiCredentials(context: Context, apiId: String, apiHash: String) {
        getPreferences(context).edit()
            .putString(KEY_API_ID, apiId)
            .putString(KEY_API_HASH, apiHash)
            .apply()
    }

    fun getApiId(context: Context): String? {
        return getPreferences(context).getString(KEY_API_ID, null)
    }

    fun getApiHash(context: Context): String? {
        return getPreferences(context).getString(KEY_API_HASH, null)
    }

    fun resetAuthorization(context: Context) {
        getPreferences(context).edit()
            .putBoolean(KEY_IS_AUTHORIZED, false)
            .remove(KEY_PHONE_NUMBER)
            .apply()
    }

    fun saveTtsVoiceName(context: Context, voiceName: String) {
        getPreferences(context).edit()
            .putString(KEY_TTS_VOICE_NAME, voiceName)
            .apply()
    }

    fun getTtsVoiceName(context: Context): String? {
        return getPreferences(context).getString(KEY_TTS_VOICE_NAME, null)
    }

    fun saveTtsPitch(context: Context, pitch: Float) {
        getPreferences(context).edit()
            .putFloat(KEY_TTS_PITCH, pitch)
            .apply()
    }

    fun getTtsPitch(context: Context): Float {
        return getPreferences(context).getFloat(KEY_TTS_PITCH, 1.0f)
    }

    fun saveTtsRate(context: Context, rate: Float) {
        getPreferences(context).edit()
            .putFloat(KEY_TTS_RATE, rate)
            .apply()
    }

    fun getTtsRate(context: Context): Float {
        return getPreferences(context).getFloat(KEY_TTS_RATE, 1.0f)
    }

    // Хранилище скрытых каналов (username)
    fun getHiddenUsernames(context: Context): MutableSet<String> {
        return getPreferences(context).getStringSet(KEY_HIDDEN_USERNAMES, emptySet())?.toMutableSet()
            ?: mutableSetOf()
    }

    fun saveHiddenUsernames(context: Context, set: Set<String>) {
        getPreferences(context).edit().putStringSet(KEY_HIDDEN_USERNAMES, set).apply()
    }

    // Хранилище скрытых каналов (id как строка)
    fun getHiddenIds(context: Context): MutableSet<String> {
        return getPreferences(context).getStringSet(KEY_HIDDEN_IDS, emptySet())?.toMutableSet()
            ?: mutableSetOf()
    }

    fun saveHiddenIds(context: Context, set: Set<String>) {
        getPreferences(context).edit().putStringSet(KEY_HIDDEN_IDS, set).apply()
    }

    fun clearAll(context: Context) {
        getPreferences(context).edit().clear().apply()
    }
}
