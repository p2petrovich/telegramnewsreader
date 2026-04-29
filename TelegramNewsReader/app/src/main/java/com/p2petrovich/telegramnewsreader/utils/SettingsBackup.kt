package com.p2petrovich.telegramnewsreader.utils

import android.content.Context
import android.os.Environment
import com.p2petrovich.telegramnewsreader.models.ChannelPreset
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object SettingsBackup {
    private const val BACKUP_FILE_NAME = "telegram_news_backup.json"

    // Сохраняем в папку Downloads на внешнем хранилище, чтобы файл остался после удаления приложения
    private fun getBackupFile(): File {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadsDir.exists()) {
            downloadsDir.mkdirs()
        }
        return File(downloadsDir, BACKUP_FILE_NAME)
    }

    fun exportToJson(context: Context): String {
        val json = JSONObject()

        // Basic settings
        json.put("is_authorized", PreferenceManager.isAuthorized(context))
        json.put("phone_number", PreferenceManager.getPhoneNumber(context) ?: "")

        // TTS settings
        json.put("tts_voice_name", PreferenceManager.getTtsVoiceName(context) ?: "")
        json.put("tts_pitch", PreferenceManager.getTtsPitch(context))
        json.put("tts_rate", PreferenceManager.getTtsRate(context))

        // Hidden channels (usernames)
        val hiddenUsernamesArray = JSONArray()
        PreferenceManager.getHiddenUsernames(context).forEach { hiddenUsernamesArray.put(it) }
        json.put("hidden_usernames", hiddenUsernamesArray)

        // Hidden channels (ids)
        val hiddenIdsArray = JSONArray()
        PreferenceManager.getHiddenIds(context).forEach { hiddenIdsArray.put(it) }
        json.put("hidden_ids", hiddenIdsArray)

        // Hidden id title map
        val hiddenIdTitleMap = JSONObject()
        PreferenceManager.getHiddenIdTitleMap(context).forEach { (id, title) ->
            hiddenIdTitleMap.put(id.toString(), title)
        }
        json.put("hidden_id_title_map", hiddenIdTitleMap)

        // Favorite channels
        val favoriteChannelsArray = JSONArray()
        PreferenceManager.getFavoriteChannelIds(context).forEach { favoriteChannelsArray.put(it.toString()) }
        json.put("favorite_channels", favoriteChannelsArray)

        // Color theme
        json.put("color_theme", PreferenceManager.getColorTheme(context))

        // Player state
        json.put("player_paths", JSONArray(PreferenceManager.getPlaylistPaths(context)))
        json.put("player_index", PreferenceManager.getPlayerIndex(context))
        json.put("player_is_playing", PreferenceManager.getPlayerIsPlaying(context))

        // Presets
        val presets = PresetManager.getAllPresets(context)
        val presetsArray = JSONArray()
        presets.forEach { preset ->
            val presetObj = JSONObject()
            presetObj.put("id", preset.id)
            presetObj.put("name", preset.name)
            presetObj.put("timePeriodIndex", preset.timePeriodIndex)
            presetObj.put("createdAt", preset.createdAt)
            val channelIdsArray = JSONArray()
            preset.channelIds.forEach { channelIdsArray.put(it) }
            presetObj.put("channelIds", channelIdsArray)
            presetsArray.put(presetObj)
        }
        json.put("presets", presetsArray)

        // Active preset
        PresetManager.getActivePresetId(context)?.let {
            json.put("active_preset_id", it)
        }

        // Last selection
        val lastSelectedIds = PresetManager.getLastSelectedIds(context)
        val lastSelectedArray = JSONArray()
        lastSelectedIds.forEach { lastSelectedArray.put(it.toString()) }
        json.put("last_selected_ids", lastSelectedArray)
        json.put("last_time_period_index", PresetManager.getLastTimePeriodIndex(context))

        return json.toString(4)
    }

    fun saveBackupToFile(context: Context): Boolean {
        return try {
            val jsonString = exportToJson(context)
            val backupFile = getBackupFile()
            backupFile.writeText(jsonString)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun importFromJson(context: Context, jsonString: String): Boolean {
        return try {
            val json = JSONObject(jsonString)

            // Basic settings
            if (json.has("is_authorized")) {
                PreferenceManager.setAuthorized(context, json.getBoolean("is_authorized"))
            }
            if (json.has("phone_number") && json.getString("phone_number").isNotEmpty()) {
                PreferenceManager.savePhoneNumber(context, json.getString("phone_number"))
            }

            // TTS settings
            if (json.has("tts_voice_name") && json.getString("tts_voice_name").isNotEmpty()) {
                PreferenceManager.saveTtsVoiceName(context, json.getString("tts_voice_name"))
            }
            if (json.has("tts_pitch")) {
                PreferenceManager.saveTtsPitch(context, json.getDouble("tts_pitch").toFloat())
            }
            if (json.has("tts_rate")) {
                PreferenceManager.saveTtsRate(context, json.getDouble("tts_rate").toFloat())
            }

            // Hidden channels (usernames)
            if (json.has("hidden_usernames")) {
                val arr = json.getJSONArray("hidden_usernames")
                val set = mutableSetOf<String>()
                for (i in 0 until arr.length()) {
                    set.add(arr.getString(i))
                }
                PreferenceManager.saveHiddenUsernames(context, set)
            }

            // Hidden channels (ids)
            if (json.has("hidden_ids")) {
                val arr = json.getJSONArray("hidden_ids")
                val set = mutableSetOf<String>()
                for (i in 0 until arr.length()) {
                    set.add(arr.getString(i))
                }
                PreferenceManager.saveHiddenIds(context, set)
            }

            // Hidden id title map
            if (json.has("hidden_id_title_map")) {
                val obj = json.getJSONObject("hidden_id_title_map")
                val map = mutableMapOf<Long, String>()
                obj.keys().forEach { key ->
                    val id = key.toLongOrNull() ?: return@forEach
                    map[id] = obj.getString(key)
                }
                PreferenceManager.saveHiddenIdTitleMap(context, map)
            }

            // Favorite channels
            if (json.has("favorite_channels")) {
                val arr = json.getJSONArray("favorite_channels")
                val set = mutableSetOf<Long>()
                for (i in 0 until arr.length()) {
                    arr.getString(i).toLongOrNull()?.let { set.add(it) }
                }
                PreferenceManager.saveFavoriteChannelIds(context, set)
            }

            // Color theme
            if (json.has("color_theme")) {
                PreferenceManager.saveColorTheme(context, json.getString("color_theme"))
            }

            // Player state
            if (json.has("player_paths")) {
                val arr = json.getJSONArray("player_paths")
                val list = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    list.add(arr.getString(i))
                }
                PreferenceManager.savePlaylistPaths(context, list)
            }
            if (json.has("player_index")) {
                PreferenceManager.savePlayerIndex(context, json.getInt("player_index"))
            }
            if (json.has("player_is_playing")) {
                PreferenceManager.savePlayerIsPlaying(context, json.getBoolean("player_is_playing"))
            }

            // Presets
            if (json.has("presets")) {
                val presetsArray = json.getJSONArray("presets")
                val presets = mutableListOf<ChannelPreset>()
                for (i in 0 until presetsArray.length()) {
                    val obj = presetsArray.getJSONObject(i)
                    val idsArray = obj.getJSONArray("channelIds")
                    val ids = mutableSetOf<Long>()
                    for (j in 0 until idsArray.length()) {
                        ids.add(idsArray.getLong(j))
                    }
                    presets.add(
                        ChannelPreset(
                            id = obj.getString("id"),
                            name = obj.getString("name"),
                            channelIds = ids,
                            timePeriodIndex = obj.optInt("timePeriodIndex", 2),
                            createdAt = obj.optLong("createdAt", 0L)
                        )
                    )
                }
                // Сохраняем пресеты
                presets.forEach { PresetManager.savePreset(context, it) }
            }

            // Active preset
            if (json.has("active_preset_id")) {
                PresetManager.setActivePresetId(context, json.getString("active_preset_id"))
            }

            // Last selection
            if (json.has("last_selected_ids")) {
                val arr = json.getJSONArray("last_selected_ids")
                val ids = mutableSetOf<Long>()
                for (i in 0 until arr.length()) {
                    arr.getString(i).toLongOrNull()?.let { ids.add(it) }
                }
                val timePeriodIndex = if (json.has("last_time_period_index")) {
                    json.getInt("last_time_period_index")
                } else 2
                PresetManager.saveLastSelection(context, ids, timePeriodIndex)
            }

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun loadBackupFromFile(context: Context): Boolean {
        return try {
            val backupFile = getBackupFile()
            if (!backupFile.exists()) return false
            val jsonString = backupFile.readText()
            importFromJson(context, jsonString)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun getBackupFilePath(): String {
        return getBackupFile().absolutePath
    }
}
