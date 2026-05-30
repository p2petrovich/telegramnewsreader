package com.p2petrovich.telegramnewsreader.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/**
 * Менеджер безопасности для управления зашифрованными данными и ключами.
 * Использует Android Keystore для хранения мастер-ключа.
 */
object SecurityManager {
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

        val prefs = try {
            buildEncryptedPrefs(context)
        } catch (e: Exception) {
            Log.e("SecurityManager", "EncryptedSharedPreferences недоступны", e)
            // Keystore сломан. Если ключ когда-то существовал — БД зашифрована потерянным ключом.
            return if (keyWasCreatedBefore) {
                recreateEncryptedPrefs(context)?.let { return generateAndStore(it, markerPrefs) }
                    ?: KeyResult.LostNeedsWipe
            } else {
                // Если ключа никогда не было, но мы даже создать prefs не можем — это фатально.
                KeyResult.Unavailable
            }
        }

        val savedKeyBase64 = try {
            prefs.getString(KEY_DB_ENCRYPTION, null)
        } catch (e: Exception) {
            Log.e("SecurityManager", "Не удалось прочитать ключ (повреждение)", e)
            return if (keyWasCreatedBefore) {
                KeyResult.LostNeedsWipe
            } else {
                // повреждение на чистой установке — пересоздаём prefs и генерируем
                recreateEncryptedPrefs(context)?.let { p -> generateAndStore(p, markerPrefs) }
                    ?: KeyResult.Unavailable
            }
        }

        return if (savedKeyBase64 != null) {
            try {
                KeyResult.Ok(Base64.decode(savedKeyBase64, Base64.DEFAULT))
            } catch (e: Exception) {
                Log.e("SecurityManager", "Ошибка декодирования ключа", e)
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

    private fun generateAndStore(prefs: SharedPreferences, markerPrefs: SharedPreferences): KeyResult {
        return try {
            val newKey = ByteArray(32).apply { SecureRandom().nextBytes(this) }
            val success = prefs.edit()
                .putString(KEY_DB_ENCRYPTION, Base64.encodeToString(newKey, Base64.DEFAULT))
                .commit()
            
            if (success) {
                markerPrefs.edit().putBoolean(KEY_MARKER, true).commit()
                KeyResult.Ok(newKey)
            } else {
                Log.e("SecurityManager", "Не удалось сохранить ключ через commit()")
                KeyResult.Unavailable
            }
        } catch (e: Exception) {
            Log.e("SecurityManager", "Ошибка при генерации/сохранении ключа", e)
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

    // При повреждении: удаляем файл prefs и пробуем создать заново.
    private fun recreateEncryptedPrefs(context: Context): SharedPreferences? {
        return try {
            // Удаляем файл вручную, так как deleteSharedPreferences может не сработать для Encrypted
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
            // В современных версиях это также может потребовать удаления файла из /shared_prefs/
            buildEncryptedPrefs(context)
        } catch (e: Exception) {
            Log.e("SecurityManager", "Пересоздание prefs не удалось", e)
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
