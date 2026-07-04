package com.p2petrovich.telegramnewsreader.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom

/**
 * Менеджер безопасности для управления зашифрованными данными и ключами.
 * Использует Android Keystore для хранения мастер-ключа.
 */
object SecurityManager {
    private const val TAG = "SecurityManager"
    private const val PREFS_NAME = "secret_prefs"
    private const val KEY_DB_ENCRYPTION = "db_encryption_key"
    // Флаг-маркер в ОБЫЧНЫХ (не шифрованных) prefs: ключ когда-либо создавался.
    private const val MARKER_PREFS = "secret_marker"
    private const val KEY_MARKER = "db_key_was_created"

    sealed class KeyResult {
        data class Ok(val key: ByteArray) : KeyResult()
        // Keystore сломался, ключ существовал ранее — БД нужно сбросить.
        object LostNeedsWipe : KeyResult()
        // Keystore физически недоступен — запускаться нельзя.
        object Unavailable : KeyResult()
    }

    /**
     * Возвращает ключ шифрования для базы данных TDLib с проверкой целостности.
     */
    fun getDatabaseEncryptionKeyChecked(context: Context): KeyResult {
        val markerPrefs = context.getSharedPreferences(MARKER_PREFS, Context.MODE_PRIVATE)
        val keyWasCreatedBefore = markerPrefs.getBoolean(KEY_MARKER, false)

        Logx.d(TAG) { "getDatabaseEncryptionKeyChecked: keyWasCreatedBefore=$keyWasCreatedBefore" }

        val prefs = try {
            buildEncryptedPrefs(context).also {
                Logx.d(TAG) { "buildEncryptedPrefs: SUCCESS" }
            }
        } catch (e: Exception) {
            Logx.e(TAG, "buildEncryptedPrefs: FAILED", e)
            return if (keyWasCreatedBefore) {
                recreateEncryptedPrefs(context)
                    ?.let { generateAndStore(it, markerPrefs) }
                    ?: KeyResult.LostNeedsWipe
            } else {
                recreateEncryptedPrefs(context)
                    ?.let { generateAndStore(it, markerPrefs) }
                    ?: KeyResult.Unavailable
            }
        }

        val savedKeyBase64 = try {
            prefs.getString(KEY_DB_ENCRYPTION, null).also {
                Logx.d(TAG) { "savedKeyBase64: ${if (it != null) "FOUND (len=${it.length})" else "NULL"}" }
            }
        } catch (e: Exception) {
            Logx.e(TAG, "Не удалось прочитать ключ (повреждение)", e)
            return if (keyWasCreatedBefore) {
                KeyResult.LostNeedsWipe
            } else {
                recreateEncryptedPrefs(context)
                    ?.let { p -> generateAndStore(p, markerPrefs) }
                    ?: KeyResult.Unavailable
            }
        }

        return if (savedKeyBase64 != null) {
            try {
                KeyResult.Ok(Base64.decode(savedKeyBase64, Base64.DEFAULT))
            } catch (e: Exception) {
                Logx.e(TAG, "Ошибка декодирования ключа", e)
                if (keyWasCreatedBefore) KeyResult.LostNeedsWipe else KeyResult.Unavailable
            }
        } else if (keyWasCreatedBefore) {
            // Ключа нет, но маркер говорит, что он был -> потеря.
            KeyResult.LostNeedsWipe
        } else {
            // Действительно первый запуск.
            generateAndStore(prefs, markerPrefs)
        }
    }

    /**
     * Сбрасывает маркер "ключ когда-то существовал".
     * Вызывать ТОЛЬКО вместе с удалением базы TDLib (wipe),
     * чтобы следующий getDatabaseEncryptionKeyChecked сгенерировал свежий ключ
     * для чистой базы, а не пытался восстановить старый.
     *
     * Без этого вызова при wipe возникает бесконечный цикл:
     * KEY_MARKER=true → LostNeedsWipe → wipe → retry → KEY_MARKER всё ещё true → LostNeedsWipe...
     */
    fun resetKeyMarker(context: Context) {
        context.getSharedPreferences(MARKER_PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_MARKER).commit()
        Logx.d(TAG) { "KEY_MARKER сброшен" }
    }

    private fun generateAndStore(prefs: SharedPreferences, markerPrefs: SharedPreferences): KeyResult {
        return try {
            val newKey = ByteArray(32).apply { SecureRandom().nextBytes(this) }
            val success = prefs.edit()
                .putString(KEY_DB_ENCRYPTION, Base64.encodeToString(newKey, Base64.DEFAULT))
                .commit()

            if (success) {
                markerPrefs.edit().putBoolean(KEY_MARKER, true).commit()
                Logx.d(TAG) { "Ключ успешно создан и сохранён" }
                KeyResult.Ok(newKey)
            } else {
                Logx.e(TAG, "Не удалось сохранить ключ через commit()")
                KeyResult.Unavailable
            }
        } catch (e: Exception) {
            Logx.e(TAG, "Ошибка при генерации/сохранении ключа", e)
            KeyResult.Unavailable
        }
    }

    private fun buildEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context, PREFS_NAME, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * Полное восстановление: удаляем физический файл prefs с диска И мастер-ключ
     * из Keystore, затем пересоздаём с нуля.
     *
     * ИСПРАВЛЕНО по сравнению с оригиналом: старый код делал clear() через обычный
     * (незашифрованный) SharedPreferences с тем же именем — это не удаляло зашифрованный
     * файл, и buildEncryptedPrefs снова падал. Теперь удаляем физический файл и чистим
     * Keystore.
     */
    private fun recreateEncryptedPrefs(context: Context): SharedPreferences? {
        return try {
            // 1. Удаляем физический файл зашифрованных prefs
            val prefsFile = File(context.filesDir.parentFile, "shared_prefs/${PREFS_NAME}.xml")
            if (prefsFile.exists()) {
                val deleted = prefsFile.delete()
                Logx.d(TAG) { "Удалён файл prefs ($deleted): ${prefsFile.absolutePath}" }
            }

            // 2. Удаляем мастер-ключ из Android Keystore
            try {
                val keyStore = KeyStore.getInstance("AndroidKeyStore")
                keyStore.load(null)
                val alias = MasterKey.DEFAULT_MASTER_KEY_ALIAS
                if (keyStore.containsAlias(alias)) {
                    keyStore.deleteEntry(alias)
                    Logx.d(TAG) { "MasterKey удалён из Keystore (alias=$alias)" }
                }
            } catch (e: Exception) {
                // Не критично — buildEncryptedPrefs создаст новый ключ поверх
                Logx.w(TAG, "Не удалось очистить Keystore (продолжаем): ${e.message}")
            }

            // 3. Пересоздаём — теперь нет ни файла, ни старого ключа
            buildEncryptedPrefs(context).also {
                Logx.d(TAG) { "EncryptedSharedPreferences успешно пересозданы" }
            }
        } catch (e: Exception) {
            Logx.e(TAG, "Пересоздание prefs не удалось", e)
            null
        }
    }

    @Deprecated("Use getDatabaseEncryptionKeyChecked instead", ReplaceWith("getDatabaseEncryptionKeyChecked(context)"))
    fun getDatabaseEncryptionKey(context: Context): ByteArray? {
        return when (val result = getDatabaseEncryptionKeyChecked(context)) {
            is KeyResult.Ok -> result.key
            else -> null
        }
    }
}
