package com.example.telegramnewsreader.utils

import android.content.Context
import android.content.SharedPreferences

object PreferenceManager {
    private const val PREFS_NAME = "telegram_news_prefs"
    private const val KEY_IS_AUTHORIZED = "is_authorized"
    private const val KEY_PHONE_NUMBER = "phone_number"
    private const val KEY_API_ID = "api_id"
    private const val KEY_API_HASH = "api_hash"

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

    // ✅ Добавлено: быстрый сброс авторизации (без очистки остального)
    fun resetAuthorization(context: Context) {
        getPreferences(context).edit()
            .putBoolean(KEY_IS_AUTHORIZED, false)
            .remove(KEY_PHONE_NUMBER)
            .apply()
    }

    fun clearAll(context: Context) {
        getPreferences(context).edit().clear().apply()
    }
}
