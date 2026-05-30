package com.p2petrovich.telegramnewsreader.utils

import android.content.Context
import android.util.Log
import com.p2petrovich.telegramnewsreader.ApiConfig
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Утилита для динамического управления версией Chromium для Edge TTS.
 * Позволяет избегать ошибок 403 Forbidden при повышении требований Microsoft.
 */
object EdgeConfig {
    private const val PREFS = "edge_runtime_cfg"
    private const val KEY_FULL_VERSION = "chromium_full_version"
    private const val KEY_UPDATED_AT   = "version_updated_at"
    private const val MAX_AGE_MS = 24L * 60 * 60 * 1000 // 24 часа
    private const val VERSION_ENDPOINT =
        "https://versionhistory.googleapis.com/v1/chrome/platforms/win/channels/stable/versions"

    private val http = HttpClients.shared

    fun fullVersion(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_FULL_VERSION, null) ?: ApiConfig.EDGE_CHROMIUM_FULL_VERSION

    fun majorVersion(context: Context): String =
        fullVersion(context).substringBefore(".").ifBlank { ApiConfig.EDGE_CHROMIUM_MAJOR_VERSION }

    /** Сбрасывает время последнего обновления, заставляя refreshIfNeeded выполниться при следующем вызове. */
    fun invalidate(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_UPDATED_AT).apply()
    }

    /** Обновляет версию Chromium из внешнего API, если пришло время. */
    suspend fun refreshIfNeeded(context: Context, force: Boolean = false) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastUpdate = p.getLong(KEY_UPDATED_AT, 0)
        
        if (!force && System.currentTimeMillis() - lastUpdate < MAX_AGE_MS) {
            return
        }

        try {
            Log.d("EdgeConfig", "Refreshing Chromium version from $VERSION_ENDPOINT")
            val request = Request.Builder().url(VERSION_ENDPOINT).build()
            http.newCall(request).execute().use { resp ->
                val body = resp.body?.string() ?: return
                if (!resp.isSuccessful) {
                    Log.w("EdgeConfig", "API response not successful: ${resp.code}")
                    return
                }
                
                val versions = JSONObject(body).optJSONArray("versions") ?: return
                if (versions.length() == 0) return
                
                val latest = versions.getJSONObject(0).optString("version")
                if (latest.count { it == '.' } == 3) {
                    p.edit()
                        .putString(KEY_FULL_VERSION, latest)
                        .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
                        .apply()
                    Log.i("EdgeConfig", "Chromium version updated to $latest")
                } else {
                    Log.w("EdgeConfig", "Unexpected version format: $latest")
                }
            }
        } catch (e: Exception) {
            Log.w("EdgeConfig", "Failed to refresh Chromium version: ${e.message}")
        }
    }
}
