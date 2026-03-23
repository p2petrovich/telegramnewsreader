// utils/PresetManager.kt

package com.p2petrovich.telegramnewsreader.utils

import android.content.Context
import android.content.SharedPreferences
import com.p2petrovich.telegramnewsreader.models.ChannelPreset
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object PresetManager {

    private const val PREFS_NAME = "channel_presets"
    private const val KEY_PRESETS = "presets_json"
    private const val KEY_ACTIVE_PRESET = "active_preset_id"
    private const val KEY_LAST_SELECTED = "last_selected_channels"
    private const val KEY_LAST_TIME_PERIOD = "last_time_period_index"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ============ Автосохранение последнего выбора ============

    fun saveLastSelection(context: Context, channelIds: Set<Long>, timePeriodIndex: Int) {
        prefs(context).edit()
            .putStringSet(KEY_LAST_SELECTED, channelIds.map { it.toString() }.toSet())
            .putInt(KEY_LAST_TIME_PERIOD, timePeriodIndex)
            .apply()
    }

    fun getLastSelectedIds(context: Context): Set<Long> {
        return prefs(context).getStringSet(KEY_LAST_SELECTED, emptySet())
            ?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
    }

    fun getLastTimePeriodIndex(context: Context): Int {
        return prefs(context).getInt(KEY_LAST_TIME_PERIOD, 2)
    }

    // ============ Пресеты ============

    fun getAllPresets(context: Context): List<ChannelPreset> {
        val json = prefs(context).getString(KEY_PRESETS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val idsArr = obj.getJSONArray("channelIds")
                val ids = (0 until idsArr.length()).map { idsArr.getLong(it) }.toSet()
                ChannelPreset(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    channelIds = ids,
                    timePeriodIndex = obj.optInt("timePeriodIndex", 2),
                    createdAt = obj.optLong("createdAt", 0L)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun savePreset(context: Context, preset: ChannelPreset) {
        val presets = getAllPresets(context).toMutableList()
        val existingIndex = presets.indexOfFirst { it.id == preset.id }
        if (existingIndex >= 0) {
            presets[existingIndex] = preset
        } else {
            presets.add(preset)
        }
        saveAllPresets(context, presets)
    }

    fun deletePreset(context: Context, presetId: String) {
        val presets = getAllPresets(context).filter { it.id != presetId }
        saveAllPresets(context, presets)
        if (getActivePresetId(context) == presetId) {
            setActivePresetId(context, null)
        }
    }

    fun createPreset(
        context: Context,
        name: String,
        channelIds: Set<Long>,
        timePeriodIndex: Int
    ): ChannelPreset {
        val preset = ChannelPreset(
            id = UUID.randomUUID().toString(),
            name = name,
            channelIds = channelIds,
            timePeriodIndex = timePeriodIndex
        )
        savePreset(context, preset)
        return preset
    }

    // ============ Активный пресет ============

    fun getActivePresetId(context: Context): String? =
        prefs(context).getString(KEY_ACTIVE_PRESET, null)

    fun setActivePresetId(context: Context, presetId: String?) {
        prefs(context).edit().putString(KEY_ACTIVE_PRESET, presetId).apply()
    }

    fun getActivePreset(context: Context): ChannelPreset? {
        val id = getActivePresetId(context) ?: return null
        return getAllPresets(context).find { it.id == id }
    }

    // ============ Internal ============

    private fun saveAllPresets(context: Context, presets: List<ChannelPreset>) {
        val arr = JSONArray()
        presets.forEach { p ->
            val obj = JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("timePeriodIndex", p.timePeriodIndex)
                put("createdAt", p.createdAt)
                val idsArr = JSONArray()
                p.channelIds.forEach { idsArr.put(it) }
                put("channelIds", idsArr)
            }
            arr.put(obj)
        }
        prefs(context).edit().putString(KEY_PRESETS, arr.toString()).apply()
    }
}
