package com.p2petrovich.telegramnewsreader.utils

import android.annotation.SuppressLint
import android.content.ContentUris
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.GeneralSecurityException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Один сохранённый бэкап: отображаемое имя + Uri для чтения + дата. */
data class BackupItem(val displayName: String, val uri: Uri, val dateMillis: Long)

object SettingsBackup {
    private const val TAG = "SettingsBackup"
    private const val BACKUP_FILE_NAME = "telegram_news_backup.json"
    private const val APP_SIGNATURE = "telegram_news_reader"
    private const val BACKUP_VERSION = 1
    private const val NEWS_PREFS_FILE = "telegram_news_prefs"

    private val SENSITIVE_KEYS = setOf(
        "openrouter_api_key", "groq_api_key", "gemini_api_key"
    )

    private fun getDatedFileName(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault())
        return "telegram_news_backup_${sdf.format(Date())}.json"
    }

    suspend fun exportToJson(context: Context): String = withContext(Dispatchers.IO) {
        val json = JSONObject()
        json.put("app_signature", APP_SIGNATURE)
        json.put("backup_version", BACKUP_VERSION)

        // Сохраняем ВСЕ настройки из SharedPreferences (Общие и Авторизация),
        // чувствительные ключи (SENSITIVE_KEYS) вырезаем в отдельный шифрованный блок.
        val prefFiles = listOf(NEWS_PREFS_FILE, "auth_status")
        val allPrefsJson = JSONObject()
        val secretsJson = JSONObject()

        prefFiles.forEach { fileName ->
            val p = context.getSharedPreferences(fileName, Context.MODE_PRIVATE)
            val fileJson = JSONObject()
            p.all.forEach { (key, value) ->
                if (value == null) return@forEach
                if (key in SENSITIVE_KEYS) {
                    secretsJson.put(key, value.toString())  // ← в шифрованный блок
                    return@forEach
                }
                if (value is Set<*>) {
                    val array = JSONArray()
                    value.forEach { array.put(it) }
                    fileJson.put(key, array)
                } else {
                    fileJson.put(key, value)
                }
            }
            allPrefsJson.put(fileName, fileJson)
        }

        // API-ключи хранятся в отдельном secure-файле — добавляем в шифрованный блок.
        PreferenceManager.getOpenRouterApiKey(context).takeIf { it.isNotEmpty() }
            ?.let { secretsJson.put("openrouter_api_key", it) }
        PreferenceManager.getGroqApiKey(context).takeIf { it.isNotEmpty() }
            ?.let { secretsJson.put("groq_api_key", it) }
        PreferenceManager.getGeminiApiKey(context).takeIf { it.isNotEmpty() }
            ?.let { secretsJson.put("gemini_api_key", it) }

        json.put("all_pref_files", allPrefsJson)

        // Шифруем секреты ключом устройства (Android Keystore).
        // Файл в Downloads теперь безопасен: без ключа устройства secrets_enc бесполезен.
        if (secretsJson.length() > 0) {
            json.put("secrets_enc", BackupKey.encrypt(secretsJson.toString()))
        }

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
        val channels = db.channelDao().getAll()
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

        json.toString(4)
    }

    suspend fun saveBackupToFile(context: Context): String? = withContext(Dispatchers.IO) {
        try {
            val jsonString = exportToJson(context)
            val bytes = jsonString.toByteArray(Charsets.UTF_8)
            val fileName = getDatedFileName()

            // 1. Сохраняем во внутреннее хранилище (как основной кэш)
            saveToInternalStorage(context, bytes, BACKUP_FILE_NAME)

            // 2. Дополнительно сохраняем с датой во внутреннее хранилище
            saveToInternalStorage(context, bytes, fileName)

            // 3. Сохраняем в Downloads с датой (для пользователя)
            saveFileToDownloads(context, bytes, fileName)

            fileName
        } catch (e: Exception) {
            e.printStackTrace()
            null
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
            ) ?: throw Exception("MediaStore insert failed (returned null)")

            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(data)
            } ?: throw Exception("Failed to open output stream for Uri: $uri")

            contentValues.clear()
            contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
            context.contentResolver.update(uri, contentValues, null, null)

            android.util.Log.d(TAG, "Successfully saved to MediaStore: $fileName")
            true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "MediaStore save failed: ${e.message}", e)
            false
        }
    }

    private fun legacySaveToFile(data: ByteArray, fileName: String): Boolean {
        return try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) {
                val created = downloadsDir.mkdirs()
                android.util.Log.d(TAG, "Downloads dir created: $created")
            }
            val backupFile = File(downloadsDir, fileName)
            backupFile.writeBytes(data)
            android.util.Log.d(TAG, "Successfully saved to legacy storage: ${backupFile.absolutePath}")
            true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Legacy save failed: ${e.message}", e)
            false
        }
    }

    /** Список бэкапов из Downloads (новые сверху). */
    suspend fun listBackups(context: Context): List<BackupItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<BackupItem>()
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Files.getContentUri("external")
        }

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED
        )

        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("telegram_news_backup_%.json")
        val sort = "${MediaStore.MediaColumns.DATE_ADDED} DESC"

        try {
            context.contentResolver.query(collection, projection, selection, args, sort)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val uri = ContentUris.withAppendedId(collection, id)
                    items.add(
                        BackupItem(
                            displayName = c.getString(nameCol),
                            uri = uri,
                            dateMillis = c.getLong(dateCol) * 1000L
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        items
    }

    /** Восстановление из выбранного бэкапа. */
    suspend fun restoreFromUri(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val jsonString = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    reader.readText()
                }
            } ?: return@withContext false
            importFromJson(context, jsonString)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun importFromJson(context: Context, jsonString: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject(jsonString)
            
            // Валидация файла
            if (json.optString("app_signature") != APP_SIGNATURE) return@withContext false
            if (json.optInt("backup_version", 0) <= 0) return@withContext false

            if (json.has("all_pref_files")) {
                val allFilesJson = json.getJSONObject("all_pref_files")
                allFilesJson.keys().forEach { fileName ->
                    val fileContent = allFilesJson.getJSONObject(fileName)
                    val prefs = context.getSharedPreferences(fileName, Context.MODE_PRIVATE)
                    val editor = prefs.edit()
                    
                    // Полная очистка перед импортом, чтобы не смешивать старое и новое
                    editor.clear()
                    
                    fileContent.keys().forEach { key ->
                        val value = fileContent.get(key)
                        
                        if (key == "openrouter_api_key") {
                            PreferenceManager.saveOpenRouterApiKey(context, value.toString())
                            return@forEach
                        }
                        if (key == "groq_api_key") {
                            PreferenceManager.saveGroqApiKey(context, value.toString())
                            return@forEach
                        }
                        if (key == "gemini_api_key") {
                            PreferenceManager.saveGeminiApiKey(context, value.toString())
                            return@forEach
                        }

                        when (value) {
                            is Boolean -> editor.putBoolean(key, value)
                            is Int -> editor.putInt(key, value)
                            is Long -> editor.putLong(key, value)
                            is Double -> editor.putFloat(key, value.toFloat())
                            is String -> editor.putString(key, value)
                            is JSONArray -> {
                                val set = mutableSetOf<String>()
                                for (i in 0 until value.length()) {
                                    set.add(value.getString(i))
                                }
                                editor.putStringSet(key, set)
                            }
                        }
                    }
                    editor.commit() // Используем синхронный commit для надежности
                }
            } else if (json.has("all_preferences")) {
                // Обратная совместимость со старыми бэкапами
                val prefsJson = json.getJSONObject("all_preferences")
                val prefs = context.getSharedPreferences("telegram_news_prefs", Context.MODE_PRIVATE)
                val editor = prefs.edit()
                
                prefsJson.keys().forEach { key ->
                    val value = prefsJson.get(key)
                    if (key == "openrouter_api_key") {
                        PreferenceManager.saveOpenRouterApiKey(context, value.toString())
                        return@forEach
                    }
                    if (key == "groq_api_key") {
                        PreferenceManager.saveGroqApiKey(context, value.toString())
                        return@forEach
                    }
                    if (key == "gemini_api_key") {
                        PreferenceManager.saveGeminiApiKey(context, value.toString())
                        return@forEach
                    }
                    when (value) {
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Double -> editor.putFloat(key, value.toFloat())
                        is String -> editor.putString(key, value)
                        is JSONArray -> {
                            val set = mutableSetOf<String>()
                            for (i in 0 until value.length()) {
                                set.add(value.getString(i))
                            }
                            editor.putStringSet(key, set)
                        }
                    }
                }
                editor.commit()
            }

            // После загрузки всех настроек - инвалидируем кеш менеджера
            PreferenceManager.invalidate()

            // Расшифровка чувствительного блока (новый формат, Вариант A).
            // Деградация: если бэкап с другого устройства / Keystore сброшен —
            // несекретная часть уже восстановлена, секреты пропускаем без падения импорта.
            val encBlob = json.optString("secrets_enc", "")
            if (encBlob.isNotEmpty()) {
                try {
                    val secrets = JSONObject(BackupKey.decrypt(encBlob))
                    val keyIt = secrets.keys()
                    while (keyIt.hasNext()) {
                        val k = keyIt.next()
                        val v = secrets.getString(k)
                        when (k) {
                            "openrouter_api_key" -> PreferenceManager.saveOpenRouterApiKey(context, v)
                            "groq_api_key"       -> PreferenceManager.saveGroqApiKey(context, v)
                            "gemini_api_key"     -> PreferenceManager.saveGeminiApiKey(context, v)
                            else -> {
                                // Другие SENSITIVE_KEYS — в основной файл prefs
                                context.getSharedPreferences(NEWS_PREFS_FILE, Context.MODE_PRIVATE)
                                    .edit().putString(k, v).apply()
                            }
                        }
                    }
                } catch (e: GeneralSecurityException) {
                    // Другое устройство / сброшенный Keystore / повреждение blob —
                    // несекретная часть уже восстановлена, просто логируем и продолжаем.
                    android.util.Log.w(TAG, "Секреты бэкапа не расшифрованы: ${e.javaClass.simpleName}")
                }
            }

            if (json.has("presets")) {
                val presetsArray = json.getJSONArray("presets")
                for (i in 0 until presetsArray.length()) {
                    val obj = presetsArray.getJSONObject(i)
                    val idsArray = obj.getJSONArray("channelIds")
                    val ids = mutableSetOf<Long>()
                    for (j in 0 until idsArray.length()) {
                        ids.add(idsArray.getLong(j))
                    }
                    PresetManager.savePreset(context, ChannelPreset(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        channelIds = ids,
                        timePeriodIndex = obj.optInt("timePeriodIndex", 2),
                        createdAt = obj.optLong("createdAt", 0L)
                    ))
                }
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
                val timePeriodIndex = json.optInt("last_time_period_index", 2)
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
                db.channelDao().replaceChannels(channels)
            }

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
