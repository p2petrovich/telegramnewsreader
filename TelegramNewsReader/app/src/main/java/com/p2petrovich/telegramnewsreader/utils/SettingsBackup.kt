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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SettingsBackup {
    const val BACKUP_FILE_NAME = "telegram_news_backup.json"

    private fun getDatedFileName(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault())
        return "telegram_news_backup_${sdf.format(Date())}.json"
    }
    // Модель для отображения в списке
    data class BackupFile(
        val name: String,
        val uri: Uri?,
        val date: Long,
        val size: Long
    )
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
        if (getBackupFile(context).exists()) return true
        return getAvailableBackups(context).isNotEmpty()
    }
    // Получение списка всех доступных файлов бэкапа
    fun getAvailableBackups(context: Context): List<BackupFile> {
        val result = mutableListOf<BackupFile>()
        // 1. Проверка внутреннего хранилища
        val internalFile = getBackupFile(context)
        if (internalFile.exists()) {
            result.add(BackupFile(
                name = "Внутренняя копия (кэш)",
                uri = null,
                date = internalFile.lastModified(),
                size = internalFile.length()
            ))
        }
        // 2. Поиск во внешнем хранилище (Downloads)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.DATE_MODIFIED,
                MediaStore.Downloads.SIZE
            )
            val baseName = BACKUP_FILE_NAME.substringBeforeLast(".")
            val selection = "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("$baseName%.json")
            val sortOrder = "${MediaStore.Downloads.DATE_MODIFIED} DESC"
            context.contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol)
                    val date = cursor.getLong(dateCol) * 1000L
                    val size = cursor.getLong(sizeCol)
                    val uri = Uri.withAppendedPath(collection, id.toString())
                    result.add(BackupFile(name, uri, date, size))
                }
            }
        } else {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (downloadsDir.exists() && downloadsDir.isDirectory) {
                val baseName = BACKUP_FILE_NAME.substringBeforeLast(".")
                downloadsDir.listFiles { _, name -> name.startsWith(baseName) && name.endsWith(".json") }
                    ?.forEach { file -> result.add(BackupFile(file.name, null, file.lastModified(), file.length())) }
            }
        }
        return result.sortedByDescending { it.date }
    }
    // Импорт из конкретного выбранного файла
    fun importFromBackup(context: Context, backup: BackupFile): Boolean {
        return try {
            val jsonString = if (backup.uri != null) {
                context.contentResolver.openInputStream(backup.uri)?.use { it.bufferedReader().readText() }
            } else {
                val file = if (backup.name == "Внутренняя копия (кэш)") getBackupFile(context)
                else File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), backup.name)
                if (file.exists()) file.readText() else null
            }
            if (jsonString != null) importFromJson(context, jsonString) else false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
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

        // ИИ Настройки
        json.put("ai_summary_enabled", PreferenceManager.isAiSummaryEnabled(context))
        json.put("ai_model", PreferenceManager.getAiModel(context))
        json.put("ai_style", PreferenceManager.getAiStyle(context))

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
            val fileName = getDatedFileName()

            // 1. Сохраняем во внутреннее хранилище (как основной кэш)
            saveToInternalStorage(context, bytes, BACKUP_FILE_NAME)

            // 2. Дополнительно сохраняем с датой во внутреннее хранилище
            saveToInternalStorage(context, bytes, fileName)

            // 3. Сохраняем в Downloads с датой (для пользователя)
            saveFileToDownloads(context, bytes, fileName)

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun saveToInternalStorage(context: Context, data: ByteArray, fileName: String) {
        val backupFile = File(context.filesDir, fileName)
        backupFile.parentFile?.mkdirs()
        backupFile.writeBytes(data)
    }

    @SuppressLint("ObsoleteSdkInt")
    private fun saveFileToDownloads(context: Context, data: ByteArray, fileName: String): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToMediaStore(context, data, fileName)
        } else {
            legacySaveToFile(data, fileName)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveToMediaStore(context: Context, data: ByteArray, fileName: String): Boolean {
        return try {
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
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

    private fun legacySaveToFile(data: ByteArray, fileName: String): Boolean {
        return try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) downloadsDir.mkdirs()
            val backupFile = File(downloadsDir, fileName)
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

            // ИИ Настройки
            if (json.has("ai_summary_enabled")) {
                PreferenceManager.setAiSummaryEnabled(context, json.getBoolean("ai_summary_enabled"))
            }
            if (json.has("ai_model")) {
                PreferenceManager.setAiModel(context, json.getString("ai_model"))
            }
            if (json.has("ai_style")) {
                PreferenceManager.setAiStyle(context, json.getString("ai_style"))
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
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.DATE_MODIFIED
        )
        // Сортируем по дате изменения: сначала самые новые
        val sortOrder = "${MediaStore.Downloads.DATE_MODIFIED} DESC"

        // 1. Сначала ищем точное совпадение имени
        val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(BACKUP_FILE_NAME)

        context.contentResolver.query(
            collection, projection, selection, selectionArgs, sortOrder
        )?.use { cursor ->
            if (cursor.moveToFirst()) { // Берем только первый (самый новый)
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                val uri = Uri.withAppendedPath(collection, id.toString())

                try {
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        val jsonString = inputStream.bufferedReader().use { it.readText() }
                        return importFromJson(context, jsonString)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // 2. Если не нашли, ищем файлы с суффиксами (например, "backup (1).json")
        val baseName = BACKUP_FILE_NAME.substringBeforeLast(".")
        val extension = BACKUP_FILE_NAME.substringAfterLast(".")
        val likeSelection = "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?"
        val likeArgs = arrayOf("$baseName%$extension")

        context.contentResolver.query(
            collection, projection, likeSelection, likeArgs, sortOrder
        )?.use { cursor ->
            if (cursor.moveToFirst()) { // Берем самый новый из похожих
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                val uri = Uri.withAppendedPath(collection, id.toString())

                try {
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        val jsonString = inputStream.bufferedReader().use { it.readText() }
                        return importFromJson(context, jsonString)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        return false
    }

    private fun legacyLoadFromFile(context: Context): Boolean {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadsDir.exists() || !downloadsDir.isDirectory) return false

        val baseName = BACKUP_FILE_NAME.substringBeforeLast(".")
        val files = downloadsDir.listFiles { _, name ->
            name.startsWith(baseName) && name.endsWith(".json")
        }

        if (files.isNullOrEmpty()) return false

        // Сортируем по дате последнего изменения и берем самый свежий
        val latestFile = files.maxByOrNull { it.lastModified() } ?: return false

        return try {
            val jsonString = latestFile.readText()
            importFromJson(context, jsonString)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun getBackupFilePath(context: Context): String {
        return getBackupFile(context).absolutePath
    }
}