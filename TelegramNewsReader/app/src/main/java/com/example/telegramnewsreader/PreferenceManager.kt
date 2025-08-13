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

    // Кеш названий скрытых каналов по id
    private const val KEY_HIDDEN_TITLES = "hidden_id_title_map"

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

    // 🔥 НОВОЕ: Индивидуальные настройки для каждого голоса
    fun saveTtsPitchForVoice(context: Context, voiceName: String, pitch: Float) {
        val key = "${KEY_TTS_PITCH}_$voiceName"
        getPreferences(context).edit()
            .putFloat(key, pitch)
            .apply()
    }

    fun getTtsPitchForVoice(context: Context, voiceName: String, defaultPitch: Float = 1.0f): Float {
        val key = "${KEY_TTS_PITCH}_$voiceName"
        return getPreferences(context).getFloat(key, defaultPitch)
    }

    fun saveTtsRateForVoice(context: Context, voiceName: String, rate: Float) {
        val key = "${KEY_TTS_RATE}_$voiceName"
        getPreferences(context).edit()
            .putFloat(key, rate)
            .apply()
    }

    fun getTtsRateForVoice(context: Context, voiceName: String, defaultRate: Float = 1.0f): Float {
        val key = "${KEY_TTS_RATE}_$voiceName"
        return getPreferences(context).getFloat(key, defaultRate)
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

    // Кеш названий скрытых каналов по id
    fun saveHiddenTitleForId(context: Context, id: Long, title: String) {
        val map = getHiddenIdTitleMap(context).toMutableMap()
        map[id] = title
        saveHiddenIdTitleMap(context, map)
    }

    fun getHiddenTitleForId(context: Context, id: Long): String? {
        return getHiddenIdTitleMap(context)[id]
    }

    private fun getHiddenIdTitleMap(context: Context): Map<Long, String> {
        val raw = getPreferences(context).getString(KEY_HIDDEN_TITLES, null) ?: return emptyMap()
        return try {
            // простой сериализатор "id:title|||id2:title2"
            raw.split("|||").mapNotNull { pair ->
                val idx = pair.indexOf(':')
                if (idx <= 0) null else {
                    val id = pair.substring(0, idx).toLongOrNull() ?: return@mapNotNull null
                    val title = pair.substring(idx + 1)
                    id to title
                }
            }.toMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun saveHiddenIdTitleMap(context: Context, map: Map<Long, String>) {
        val raw = map.entries.joinToString("|||") { "${it.key}:${it.value}" }
        getPreferences(context).edit().putString(KEY_HIDDEN_TITLES, raw).apply()
    }

    fun clearAll(context: Context) {
        getPreferences(context).edit().clear().apply()
    }
    // 🔥 ДОБАВИТЬ В КОНЕЦ ФАЙЛА:

    private const val KEY_FAVORITE_CHANNELS = "favorite_channels"

    // Сохранение ID избранных каналов
    fun saveFavoriteChannelIds(context: Context, ids: Set<Long>) {
        val idStrings = ids.map { it.toString() }.toSet()
        getPreferences(context).edit()
            .putStringSet(KEY_FAVORITE_CHANNELS, idStrings)
            .apply()
    }

    // Получение ID избранных каналов
    fun getFavoriteChannelIds(context: Context): Set<Long> {
        val idStrings = getPreferences(context).getStringSet(KEY_FAVORITE_CHANNELS, emptySet()) ?: emptySet()
        return idStrings.mapNotNull { it.toLongOrNull() }.toSet()
    }

    // Добавление канала в избранное
    fun addFavoriteChannel(context: Context, channelId: Long) {
        val favorites = getFavoriteChannelIds(context).toMutableSet()
        favorites.add(channelId)
        saveFavoriteChannelIds(context, favorites)
    }

    // Удаление канала из избранного
    fun removeFavoriteChannel(context: Context, channelId: Long) {
        val favorites = getFavoriteChannelIds(context).toMutableSet()
        favorites.remove(channelId)
        saveFavoriteChannelIds(context, favorites)
    }

    // Проверка, является ли канал избранным
    fun isChannelFavorite(context: Context, channelId: Long): Boolean {
        return channelId in getFavoriteChannelIds(context)
    }
}