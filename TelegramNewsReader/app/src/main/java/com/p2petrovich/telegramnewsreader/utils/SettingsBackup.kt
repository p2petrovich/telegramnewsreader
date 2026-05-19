package com.p2petrovich.telegramnewsreader.utils

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import com.p2petrovich.telegramnewsreader.db.AppDatabase
import com.p2petrovich.telegramnewsreader.models.ChannelPreset
import com.p2petrovich.telegramnewsreader.models.TelegramChannel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object SettingsBackup {
    const val BACKUP_FILE_NAME = "telegram_news_backup.json"

    private fun getBackupFile(context: Context): File {
        return File(context.filesDir, BACKUP_FILE_NAME)
    }

    private fun getDownloadsFile(): File {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadsDir.exists()) {
            downloadsDir.mkdirs()
        }
        return File(downloadsDir, BACKUP_FILE_NAME)
    }

    fun backupFileExists(context: Context): Boolean {
        // Сначала проверяем внутреннее хранилище
        if (getBackupFile(context).exists()) return true

        // Затем проверяем Downloads
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            backupFileExistsInMediaStore(context)
        } else {
            getDownloadsFile().exists()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun backupFileExistsInMediaStore(context: Context): Boolean {
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Downloads._ID)
        val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(BACKUP_FILE_NAME)

        context.contentResolver.query(
            collection, projection, selection, selectionArgs, null
        )?.use { cursor ->
            return cursor.count > 0
        }
        return false
    }

    fun exportToJson(context: Context): String {
        val json = JSONObject()

        json.put("is_authorized", PreferenceManager.isAuthorized(context))
        json.put("phone_number", PreferenceManager.getPhoneNumber(context) ?: "")

        json.put("tts_voice_name", PreferenceManager.getTtsVoiceName(context) ?: "")
        json.put("tts_pitch", PreferenceManager.getTtsPitch(context))
        json.put("tts_rate", PreferenceManager.getTtsRate(context))

        val hiddenUsernamesArray = JSONArray()
        PreferenceManager.getHiddenUsernames(context).forEach { hiddenUsernamesArray.put(it) }
        json.put("hidden_usernames", hiddenUsernamesArray)

        val hiddenIdsArray = JSONArray()
        PreferenceManager.getHiddenIds(context).forEach { hiddenIdsArray.put(it) }
        json.put("hidden_ids", hiddenIdsArray)

        val hiddenIdTitleMap = JSONObject()
        PreferenceManager.getHiddenIdTitleMap(context).forEach { (id, title) ->
            hiddenIdTitleMap.put(id.toString(), title)
        }
        json.put("hidden_id_title_map", hiddenIdTitleMap)

        val favoriteChannelsArray = JSONArray()
        PreferenceManager.getFavoriteChannelIds(context).forEach { favoriteChannelsArray.put(it.toString()) }
        json.put("favorite_channels", favoriteChannelsArray)

        json.put("color_theme", PreferenceManager.getColorTheme(context))

        // Дедупликация
        json.put("dedup_enabled", PreferenceManager.isDedupEnabled(context))
        json.put("dedup_threshold", PreferenceManager.getDedupThreshold(context).toDouble())
        json.put("dedup_history_size", PreferenceManager.getDedupHistorySize(context))
        json.put("dedup_time_window", PreferenceManager.getDedupTimeWindow(context))

        json.put("player_paths", JSONArray(PreferenceManager.getPlaylistPaths(context)))
        json.put("player_index", PreferenceManager.getPlayerIndex(context))
        json.put("player_is_playing", PreferenceManager.getPlayerIsPlaying(context))

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

        PresetManager.getActivePresetId(context)?.let {
            json.put("active_preset_id", it)
        }

        val lastSelectedIds = PresetManager.getLastSelectedIds(context)
        val lastSelectedArray = JSONArray()
        lastSelectedIds.forEach { lastSelectedArray.put(it.toString()) }
        json.put("last_selected_ids", lastSelectedArray)
        json.put("last_time_period_index", PresetManager.getLastTimePeriodIndex(context))

        val db = AppDatabase.getInstance(context)
        val channels = runBlocking { db.channelDao().getAll() }
        val channelsArray = JSONArray()
        channels.forEach { channel ->
            val channelObj = JSONObject()
            channelObj.put("id", channel.id)
            channelObj.put("name", channel.name)
            channelObj.put("username", channel.username)
            channelObj.put("category", channel.category)
            channelObj.put("selected", channel.selected)
            channelObj.put("lastSync", channel.lastSync)
            channelsArray.put(channelObj)
        }
        json.put("channels", channelsArray)

        return json.toString(4)
    }

    fun saveBackupToFile(context: Context): Boolean {
        return try {
            val jsonString = exportToJson(context)
            val bytes = jsonString.toByteArray(Charsets.UTF_8)

            // 1. Всегда сохраняем во внутреннее хранилище (надёжно)
            saveToInternalStorage(context, bytes)

            // 2. Дополнительно сохраняем в Downloads (для пользователя)
            saveFileToDownloads(context, bytes)

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun saveToInternalStorage(context: Context, data: ByteArray) {
        val backupFile = getBackupFile(context)
        backupFile.parentFile?.mkdirs()
        backupFile.writeBytes(data)
    }

    @SuppressLint("ObsoleteSdkInt")
    private fun saveFileToDownloads(context: Context, data: ByteArray): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToMediaStore(context, data)
        } else {
            legacySaveToFile(data)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveToMediaStore(context: Context, data: ByteArray): Boolean {
        return try {
            // Удаляем старый файл, если существует, чтобы избежать дублирования
            deleteFromMediaStore(context)

            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, BACKUP_FILE_NAME)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }

            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                contentValues
            ) ?: return false

            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(data)
            } ?: return false

            contentValues.clear()
            contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
            context.contentResolver.update(uri, contentValues, null, null)

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun deleteFromMediaStore(context: Context) {
        try {
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ?"
            val selectionArgs = arrayOf(BACKUP_FILE_NAME)
            context.contentResolver.delete(collection, selection, selectionArgs)
        } catch (_: Exception) {}
    }

    private fun legacySaveToFile(data: ByteArray): Boolean {
        return try {
            val backupFile = getDownloadsFile()
            backupFile.parentFile?.mkdirs()
            backupFile.writeBytes(data)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun importFromJson(context: Context, jsonString: String): Boolean {
        return try {
            val json = JSONObject(jsonString)

            if (json.has("is_authorized")) {
                PreferenceManager.setAuthorized(context, json.getBoolean("is_authorized"))
            }
            if (json.has("phone_number") && json.getString("phone_number").isNotEmpty()) {
                PreferenceManager.savePhoneNumber(context, json.getString("phone_number"))
            }

            if (json.has("tts_voice_name") && json.getString("tts_voice_name").isNotEmpty()) {
                PreferenceManager.saveTtsVoiceName(context, json.getString("tts_voice_name"))
            }
            if (json.has("tts_pitch")) {
                PreferenceManager.saveTtsPitch(context, json.getDouble("tts_pitch").toFloat())
            }
            if (json.has("tts_rate")) {
                PreferenceManager.saveTtsRate(context, json.getDouble("tts_rate").toFloat())
            }

            if (json.has("hidden_usernames")) {
                val arr = json.getJSONArray("hidden_usernames")
                val set = mutableSetOf<String>()
                for (i in 0 until arr.length()) {
                    set.add(arr.getString(i))
                }
                PreferenceManager.saveHiddenUsernames(context, set)
            }

            if (json.has("hidden_ids")) {
                val arr = json.getJSONArray("hidden_ids")
                val set = mutableSetOf<String>()
                for (i in 0 until arr.length()) {
                    set.add(arr.getString(i))
                }
                PreferenceManager.saveHiddenIds(context, set)
            }

            if (json.has("hidden_id_title_map")) {
                val obj = json.getJSONObject("hidden_id_title_map")
                val map = mutableMapOf<Long, String>()
                obj.keys().forEach { key ->
                    val id = key.toLongOrNull() ?: return@forEach
                    map[id] = obj.getString(key)
                }
                PreferenceManager.saveHiddenIdTitleMap(context, map)
            }

            if (json.has("favorite_channels")) {
                val arr = json.getJSONArray("favorite_channels")
                val set = mutableSetOf<Long>()
                for (i in 0 until arr.length()) {
                    arr.getString(i).toLongOrNull()?.let { set.add(it) }
                }
                PreferenceManager.saveFavoriteChannelIds(context, set)
            }

            if (json.has("color_theme")) {
                PreferenceManager.saveColorTheme(context, json.getString("color_theme"))
            }

            // Дедупликация
            if (json.has("dedup_enabled")) {
                PreferenceManager.setDedupEnabled(context, json.getBoolean("dedup_enabled"))
            }
            if (json.has("dedup_threshold")) {
                PreferenceManager.setDedupThreshold(context, json.getDouble("dedup_threshold").toFloat())
            }
            if (json.has("dedup_history_size")) {
                PreferenceManager.setDedupHistorySize(context, json.getInt("dedup_history_size"))
            }
            if (json.has("dedup_time_window")) {
                PreferenceManager.setDedupTimeWindow(context, json.getInt("dedup_time_window"))
            }

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
                presets.forEach { PresetManager.savePreset(context, it) }
            }

            if (json.has("active_preset_id")) {
                PresetManager.setActivePresetId(context, json.getString("active_preset_id"))
            }

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

            if (json.has("channels")) {
                val channelsArray = json.getJSONArray("channels")
                val channels = mutableListOf<TelegramChannel>()
                for (i in 0 until channelsArray.length()) {
                    val obj = channelsArray.getJSONObject(i)
                    channels.add(
                        TelegramChannel(
                            id = obj.getLong("id"),
                            name = obj.getString("name"),
                            username = obj.getString("username"),
                            category = obj.getString("category"),
                            selected = obj.optBoolean("selected", false),
                            lastSync = obj.optLong("lastSync", 0L)
                        )
                    )
                }
                val db = AppDatabase.getInstance(context)
                runBlocking {
                    db.channelDao().deleteAll()
                    db.channelDao().insertAll(channels)
                }
            }

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    @SuppressLint("ObsoleteSdkInt")
    fun loadBackupFromFile(context: Context): Boolean {
        return try {
            // 1. Сначала пробуем загрузить из внутреннего хранилища
            val internalFile = getBackupFile(context)
            if (internalFile.exists()) {
                val jsonString = internalFile.readText()
                return importFromJson(context, jsonString)
            }

            // 2. Затем пробуем из Downloads
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                loadFromMediaStore(context)
            } else {
                legacyLoadFromFile(context)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun loadFromMediaStore(context: Context): Boolean {
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME
        )
        val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(BACKUP_FILE_NAME)

        context.contentResolver.query(
            collection, projection, selection, selectionArgs, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(
                    cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                )
                val uri = Uri.withAppendedPath(collection, id.toString())

                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val jsonString = inputStream.bufferedReader().use { it.readText() }
                    return importFromJson(context, jsonString)
                }
            }
        }

        // Если не нашли по точному имени, ищем с LIKE (может быть суффикс (1), (2) и т.д.)
        val likeSelection = "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?"
        val likeArgs = arrayOf("$BACKUP_FILE_NAME%")
        context.contentResolver.query(
            collection, projection, likeSelection, likeArgs, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(
                    cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                )
                val uri = Uri.withAppendedPath(collection, id.toString())

                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val jsonString = inputStream.bufferedReader().use { it.readText() }
                    return importFromJson(context, jsonString)
                }
            }
        }

        return false
    }

    private fun legacyLoadFromFile(context: Context): Boolean {
        val backupFile = getDownloadsFile()
        if (!backupFile.exists()) return false
        val jsonString = backupFile.readText()
        return importFromJson(context, jsonString)
    }

    fun getBackupFilePath(context: Context): String {
        return getBackupFile(context).absolutePath
    }
}