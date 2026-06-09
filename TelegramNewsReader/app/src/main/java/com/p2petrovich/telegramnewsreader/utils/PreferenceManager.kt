package com.p2petrovich.telegramnewsreader.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.p2petrovich.telegramnewsreader.models.ProxyEntry

object PreferenceManager {
    private const val PREFS_NAME = "telegram_news_prefs"
    private const val AUTH_PREFS_NAME = "auth_status"
    private const val KEY_IS_AUTHORIZED = "is_authorized"
    private const val KEY_PHONE_NUMBER = "phone_number"
    private const val KEY_TTS_VOICE_NAME = "tts_voice_name"
    private const val KEY_TTS_PITCH = "tts_pitch"
    private const val KEY_TTS_RATE = "tts_rate"
    private const val KEY_HIDDEN_USERNAMES = "hidden_usernames"
    private const val KEY_HIDDEN_IDS = "hidden_ids"
    private const val KEY_HIDDEN_TITLES = "hidden_id_title_map"
    private const val KEY_COLOR_THEME = "color_theme"

    // Player state keys
    private const val KEY_PLAYER_PATHS = "player_paths"
    private const val KEY_PLAYER_INDEX = "player_index"
    private const val KEY_PLAYER_IS_PLAYING = "player_is_playing"

    // Deduplication settings
    private const val KEY_DEDUP_ENABLED = "dedup_enabled"
    private const val KEY_DEDUP_THRESHOLD = "dedup_threshold"
    private const val KEY_DEDUP_HISTORY_SIZE = "dedup_history_size"
    private const val KEY_DEDUP_TIME_WINDOW = "dedup_time_window"
    private const val KEY_AI_SUMMARY_ENABLED = "ai_summary_enabled"
    private const val KEY_AI_PROVIDER = "ai_provider"
    private const val KEY_AI_MODEL = "ai_model"
    private const val KEY_AI_STYLE = "ai_style"
    private const val KEY_NEWS_ORDER = "news_playback_order" // 0: ChNew, 1: ChOld, 2: ChronoNew, 3: ChronoOld

    // Proxy settings
    private const val KEY_PROXY_ENABLED = "proxy_enabled"
    private const val KEY_PROXY_LIST = "proxy_list_json"
    private const val KEY_PROXY_AUTO_SWITCH = "proxy_auto_switch"
    private const val KEY_PROXY_SWITCH_INTERVAL = "proxy_switch_interval"

    // Edge TTS settings
    private const val KEY_TTS_ENGINE  = "tts_engine"        // "android" | "edge"
    private const val KEY_EDGE_VOICE  = "edge_tts_voice"
    private const val KEY_EDGE_RATE   = "edge_tts_rate_pct"  // Int, -50..+100
    private const val KEY_EDGE_PITCH  = "edge_tts_pitch_hz"  // Int, -200..+200

    // AI API Keys (encrypted)
    private const val ENCRYPTED_PREFS_NAME = "secure_api_keys"
    private const val KEY_OPENROUTER_API_KEY = "openrouter_api_key"
    private const val KEY_GROQ_API_KEY = "groq_api_key"

    private const val PATHS_DELIMITER = "|||"

    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun getAuthPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(AUTH_PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isAiSummaryEnabled(context: Context): Boolean =
        getPreferences(context).getBoolean(KEY_AI_SUMMARY_ENABLED, false)

    fun setAiSummaryEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit().putBoolean(KEY_AI_SUMMARY_ENABLED, enabled).apply()
    }

    fun getAiProvider(context: Context): String =
        getPreferences(context).getString(KEY_AI_PROVIDER, "openrouter") ?: "openrouter"

    fun setAiProvider(context: Context, provider: String) {
        getPreferences(context).edit().putString(KEY_AI_PROVIDER, provider).apply()
    }

    fun getAiModel(context: Context): String =
        getPreferences(context).getString(KEY_AI_MODEL, "z-ai/glm-4.5-air:free") ?: "z-ai/glm-4.5-air:free"

    fun setAiModel(context: Context, model: String) {
        getPreferences(context).edit().putString(KEY_AI_MODEL, model).apply()
    }

    fun getAiStyle(context: Context): String =
        getPreferences(context).getString(KEY_AI_STYLE, "balanced") ?: "balanced"

    fun setAiStyle(context: Context, style: String) {
        getPreferences(context).edit().putString(KEY_AI_STYLE, style).apply()
    }

    fun getNewsOrder(context: Context): Int =
        getPreferences(context).getInt(KEY_NEWS_ORDER, 0) // Default: Channel, New First

    fun setNewsOrder(context: Context, order: Int) {
        getPreferences(context).edit().putInt(KEY_NEWS_ORDER, order).apply()
    }

    fun isAuthorized(context: Context): Boolean =
        getAuthPreferences(context).getBoolean(KEY_IS_AUTHORIZED, false)

    fun setAuthorized(context: Context, isAuthorized: Boolean) {
        getAuthPreferences(context).edit().putBoolean(KEY_IS_AUTHORIZED, isAuthorized).apply()
    }

    fun savePhoneNumber(context: Context, phoneNumber: String) {
        getAuthPreferences(context).edit().putString(KEY_PHONE_NUMBER, phoneNumber).apply()
    }

    fun getPhoneNumber(context: Context): String? =
        getAuthPreferences(context).getString(KEY_PHONE_NUMBER, null)

    fun resetAuthorization(context: Context) {
        getAuthPreferences(context).edit()
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

    internal fun getHiddenIdTitleMap(context: Context): Map<Long, String> {
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

    internal fun saveHiddenIdTitleMap(context: Context, map: Map<Long, String>) {
        val raw = map.entries.joinToString("|||") { "${it.key}:${it.value}" }
        getPreferences(context).edit().putString(KEY_HIDDEN_TITLES, raw).apply()
    }

    fun clearAll(context: Context) {
        getPreferences(context).edit().clear().apply()
        getAuthPreferences(context).edit().clear().apply()
        try { getEncryptedPreferences(context).edit().clear().apply() } catch (_: Exception) {}
    }

    // Color theme
    fun saveColorTheme(context: Context, theme: String) {
        getPreferences(context).edit().putString(KEY_COLOR_THEME, theme).apply()
    }

    fun getColorTheme(context: Context): String =
        getPreferences(context).getString(KEY_COLOR_THEME, "light") ?: "light"

    // Player state
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

    // ===================== Deduplication settings =====================

    fun isDedupEnabled(context: Context): Boolean =
        getPreferences(context).getBoolean(KEY_DEDUP_ENABLED, true)

    fun setDedupEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit().putBoolean(KEY_DEDUP_ENABLED, enabled).apply()
    }

    fun getDedupThreshold(context: Context): Float =
        getPreferences(context).getFloat(KEY_DEDUP_THRESHOLD, 0.6f)

    fun setDedupThreshold(context: Context, threshold: Float) {
        getPreferences(context).edit().putFloat(KEY_DEDUP_THRESHOLD, threshold).apply()
    }

    fun getDedupHistorySize(context: Context): Int =
        getPreferences(context).getInt(KEY_DEDUP_HISTORY_SIZE, 500)

    fun setDedupHistorySize(context: Context, size: Int) {
        getPreferences(context).edit().putInt(KEY_DEDUP_HISTORY_SIZE, size).apply()
    }

    fun getDedupTimeWindow(context: Context): Int =
        getPreferences(context).getInt(KEY_DEDUP_TIME_WINDOW, 60)

    fun setDedupTimeWindow(context: Context, minutes: Int) {
        getPreferences(context).edit().putInt(KEY_DEDUP_TIME_WINDOW, minutes).apply()
    }

    // ===================== Proxy settings =====================

    fun isProxyEnabled(context: Context): Boolean =
        getPreferences(context).getBoolean(KEY_PROXY_ENABLED, false)

    fun setProxyEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit().putBoolean(KEY_PROXY_ENABLED, enabled).apply()
    }

    fun getProxyList(context: Context): List<ProxyEntry> {
        val json = getPreferences(context).getString(KEY_PROXY_LIST, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<ProxyEntry>>() {}.type
            Gson().fromJson(json, type)
        } catch (_: Exception) { emptyList() }
    }

    fun saveProxyList(context: Context, list: List<ProxyEntry>) {
        val json = Gson().toJson(list)
        getPreferences(context).edit().putString(KEY_PROXY_LIST, json).apply()
    }

    fun isProxyAutoSwitchEnabled(context: Context): Boolean =
        getPreferences(context).getBoolean(KEY_PROXY_AUTO_SWITCH, false)

    fun setProxyAutoSwitchEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit().putBoolean(KEY_PROXY_AUTO_SWITCH, enabled).apply()
    }

    fun getProxySwitchInterval(context: Context): Int =
        getPreferences(context).getInt(KEY_PROXY_SWITCH_INTERVAL, 10) // default 10 min

    fun setProxySwitchInterval(context: Context, minutes: Int) {
        getPreferences(context).edit().putInt(KEY_PROXY_SWITCH_INTERVAL, minutes).apply()
    }

    // ===================== Edge TTS settings =====================

    fun getTtsEngine(context: Context): String =
        getPreferences(context).getString(KEY_TTS_ENGINE, "android") ?: "android"

    fun saveTtsEngine(context: Context, engine: String) {
        getPreferences(context).edit().putString(KEY_TTS_ENGINE, engine).apply()
    }

    fun getEdgeVoice(context: Context): String {
        val systemLang = context.resources.configuration.locales[0].language
        val default = if (systemLang == "ru") "ru-RU-DmitryNeural" else "en-US-GuyNeural"
        return getPreferences(context).getString(KEY_EDGE_VOICE, default) ?: default
    }

    fun saveEdgeVoice(context: Context, voice: String) {
        getPreferences(context).edit().putString(KEY_EDGE_VOICE, voice).apply()
    }

    fun getEdgeRate(context: Context): Int =
        getPreferences(context).getInt(KEY_EDGE_RATE, 0)

    fun saveEdgeRate(context: Context, rate: Int) {
        getPreferences(context).edit().putInt(KEY_EDGE_RATE, rate).apply()
    }

    fun getEdgePitch(context: Context): Int =
        getPreferences(context).getInt(KEY_EDGE_PITCH, 0)

    fun saveEdgePitch(context: Context, pitch: Int) {
        getPreferences(context).edit().putInt(KEY_EDGE_PITCH, pitch).apply()
    }

    fun getDefaultModelForProvider(provider: String): String = when (provider) {
        "groq"       -> "llama-3.3-70b-versatile"
        "openrouter" -> "z-ai/glm-4.5-air:free"
        else         -> "z-ai/glm-4.5-air:free"
    }

    // ===================== AI API Keys (encrypted storage) =====================
    // Ключи хранятся в EncryptedSharedPreferences — не попадают в BuildConfig/APK.

    private fun getEncryptedPreferences(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            ENCRYPTED_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getOpenRouterApiKey(context: Context): String =
        try { getEncryptedPreferences(context).getString(KEY_OPENROUTER_API_KEY, "") ?: "" }
        catch (_: Exception) { "" }

    fun saveOpenRouterApiKey(context: Context, key: String) {
        try { getEncryptedPreferences(context).edit().putString(KEY_OPENROUTER_API_KEY, key).apply() }
        catch (_: Exception) {}
    }

    fun getGroqApiKey(context: Context): String =
        try { getEncryptedPreferences(context).getString(KEY_GROQ_API_KEY, "") ?: "" }
        catch (_: Exception) { "" }

    fun saveGroqApiKey(context: Context, key: String) {
        try { getEncryptedPreferences(context).edit().putString(KEY_GROQ_API_KEY, key).apply() }
        catch (_: Exception) {}
    }
}
