package com.p2petrovich.telegramnewsreader.utils

import android.content.Context
import android.content.SharedPreferences

object PreferenceManager {
    private const val PREFS_NAME = "telegram_news_prefs"
    private const val KEY_IS_AUTHORIZED = "is_authorized"
    private const val KEY_PHONE_NUMBER = "phone_number"
    private const val KEY_TTS_VOICE_NAME = "tts_voice_name"
    private const val KEY_TTS_PITCH = "tts_pitch"
    private const val KEY_TTS_RATE = "tts_rate"
    private const val KEY_HIDDEN_USERNAMES = "hidden_usernames"
    private const val KEY_HIDDEN_IDS = "hidden_ids"
    private const val KEY_HIDDEN_TITLES = "hidden_id_title_map"
    private const val KEY_FAVORITE_CHANNELS = "favorite_channels"
    private const val KEY_COLOR_THEME = "color_theme"

    // Player state keys
    private const val KEY_PLAYER_PATHS = "player_paths"
    private const val KEY_PLAYER_INDEX = "player_index"
    private const val KEY_PLAYER_IS_PLAYING = "player_is_playing"

    private const val PATHS_DELIMITER = "|||"

    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isAuthorized(context: Context): Boolean =
        getPreferences(context).getBoolean(KEY_IS_AUTHORIZED, false)

    fun setAuthorized(context: Context, isAuthorized: Boolean) {
        getPreferences(context).edit().putBoolean(KEY_IS_AUTHORIZED, isAuthorized).apply()
    }

    fun savePhoneNumber(context: Context, phoneNumber: String) {
        getPreferences(context).edit().putString(KEY_PHONE_NUMBER, phoneNumber).apply()
    }

    fun getPhoneNumber(context: Context): String? =
        getPreferences(context).getString(KEY_PHONE_NUMBER, null)

    fun resetAuthorization(context: Context) {
        getPreferences(context).edit()
            .putBoolean(KEY_IS_AUTHORIZED, false)
            .remove(KEY_PHONE_NUMBER)
            .apply()
    }

    // TTS Voice
    fun saveTtsVoiceName(context: Context, voiceName: String) {
        getPreferences(context).edit().putString(KEY_TTS_VOICE_NAME, voiceName).apply()
    }

    fun getTtsVoiceName(context: Context): String? =
        getPreferences(context).getString(KEY_TTS_VOICE_NAME, null)

    fun saveTtsPitch(context: Context, pitch: Float) {
        getPreferences(context).edit().putFloat(KEY_TTS_PITCH, pitch).apply()
    }

    fun getTtsPitch(context: Context): Float =
        getPreferences(context).getFloat(KEY_TTS_PITCH, 1.0f)

    fun saveTtsRate(context: Context, rate: Float) {
        getPreferences(context).edit().putFloat(KEY_TTS_RATE, rate).apply()
    }

    fun getTtsRate(context: Context): Float =
        getPreferences(context).getFloat(KEY_TTS_RATE, 1.0f)

    // Per-voice settings
    fun saveTtsPitchForVoice(context: Context, voiceName: String, pitch: Float) {
        getPreferences(context).edit().putFloat("${KEY_TTS_PITCH}_$voiceName", pitch).apply()
    }

    fun getTtsPitchForVoice(context: Context, voiceName: String, default: Float = 1.0f): Float =
        getPreferences(context).getFloat("${KEY_TTS_PITCH}_$voiceName", default)

    fun saveTtsRateForVoice(context: Context, voiceName: String, rate: Float) {
        getPreferences(context).edit().putFloat("${KEY_TTS_RATE}_$voiceName", rate).apply()
    }

    fun getTtsRateForVoice(context: Context, voiceName: String, default: Float = 1.0f): Float =
        getPreferences(context).getFloat("${KEY_TTS_RATE}_$voiceName", default)

    // Hidden channels (username)
    fun getHiddenUsernames(context: Context): MutableSet<String> =
        getPreferences(context).getStringSet(KEY_HIDDEN_USERNAMES, emptySet())?.toMutableSet() ?: mutableSetOf()

    fun saveHiddenUsernames(context: Context, set: Set<String>) {
        getPreferences(context).edit().putStringSet(KEY_HIDDEN_USERNAMES, set).apply()
    }

    // Hidden channels (id)
    fun getHiddenIds(context: Context): MutableSet<String> =
        getPreferences(context).getStringSet(KEY_HIDDEN_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()

    fun saveHiddenIds(context: Context, set: Set<String>) {
        getPreferences(context).edit().putStringSet(KEY_HIDDEN_IDS, set).apply()
    }

    fun saveHiddenTitleForId(context: Context, id: Long, title: String) {
        val map = getHiddenIdTitleMap(context).toMutableMap()
        map[id] = title
        saveHiddenIdTitleMap(context, map)
    }

    fun getHiddenTitleForId(context: Context, id: Long): String? =
        getHiddenIdTitleMap(context)[id]

    private fun getHiddenIdTitleMap(context: Context): Map<Long, String> {
        val raw = getPreferences(context).getString(KEY_HIDDEN_TITLES, null) ?: return emptyMap()
        return try {
            raw.split("|||").mapNotNull { pair ->
                val idx = pair.indexOf(':')
                if (idx <= 0) null else {
                    val id = pair.substring(0, idx).toLongOrNull() ?: return@mapNotNull null
                    val title = pair.substring(idx + 1)
                    id to title
                }
            }.toMap()
        } catch (_: Exception) { emptyMap() }
    }

    private fun saveHiddenIdTitleMap(context: Context, map: Map<Long, String>) {
        val raw = map.entries.joinToString("|||") { "${it.key}:${it.value}" }
        getPreferences(context).edit().putString(KEY_HIDDEN_TITLES, raw).apply()
    }

    // Favorites
    fun getFavoriteChannelIds(context: Context): Set<Long> {
        val strs = getPreferences(context).getStringSet(KEY_FAVORITE_CHANNELS, emptySet()) ?: emptySet()
        return strs.mapNotNull { it.toLongOrNull() }.toSet()
    }

    fun saveFavoriteChannelIds(context: Context, ids: Set<Long>) {
        getPreferences(context).edit()
            .putStringSet(KEY_FAVORITE_CHANNELS, ids.map { it.toString() }.toSet())
            .apply()
    }

    fun addFavoriteChannel(context: Context, channelId: Long) {
        val favs = getFavoriteChannelIds(context).toMutableSet()
        favs.add(channelId)
        saveFavoriteChannelIds(context, favs)
    }

    fun removeFavoriteChannel(context: Context, channelId: Long) {
        val favs = getFavoriteChannelIds(context).toMutableSet()
        favs.remove(channelId)
        saveFavoriteChannelIds(context, favs)
    }

    fun isChannelFavorite(context: Context, channelId: Long): Boolean =
        channelId in getFavoriteChannelIds(context)

    fun clearAll(context: Context) {
        getPreferences(context).edit().clear().apply()
    }

    // Color theme
    fun saveColorTheme(context: Context, theme: String) {
        getPreferences(context).edit().putString(KEY_COLOR_THEME, theme).apply()
    }

    fun getColorTheme(context: Context): String =
        getPreferences(context).getString(KEY_COLOR_THEME, "purple") ?: "purple"

    // Player state - save as delimited string to preserve order
    fun savePlaylistPaths(context: Context, paths: List<String>) {
        val pathsStr = paths.joinToString(PATHS_DELIMITER)
        getPreferences(context).edit().putString(KEY_PLAYER_PATHS, pathsStr).apply()
    }

    fun getPlaylistPaths(context: Context): List<String> {
        val pathsStr = getPreferences(context).getString(KEY_PLAYER_PATHS, "") ?: return emptyList()
        if (pathsStr.isEmpty()) return emptyList()
        return pathsStr.split(PATHS_DELIMITER)
    }

    fun savePlayerIndex(context: Context, index: Int) {
        getPreferences(context).edit().putInt(KEY_PLAYER_INDEX, index).apply()
    }

    fun getPlayerIndex(context: Context): Int {
        return getPreferences(context).getInt(KEY_PLAYER_INDEX, 0)
    }

    fun savePlayerIsPlaying(context: Context, isPlaying: Boolean) {
        getPreferences(context).edit().putBoolean(KEY_PLAYER_IS_PLAYING, isPlaying).apply()
    }

    fun getPlayerIsPlaying(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_PLAYER_IS_PLAYING, false)
    }

    fun clearPlayerState(context: Context) {
        getPreferences(context).edit()
            .remove(KEY_PLAYER_PATHS)
            .remove(KEY_PLAYER_INDEX)
            .remove(KEY_PLAYER_IS_PLAYING)
            .apply()
    }
}
