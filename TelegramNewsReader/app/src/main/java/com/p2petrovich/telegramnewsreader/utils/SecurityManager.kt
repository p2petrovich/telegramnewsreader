package com.p2petrovich.telegramnewsreader.utils

import android.content.Context
import android.util.Base64
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

    /**
     * Возвращает ключ шифрования для базы данных TDLib.
     * Если ключ еще не создан, генерирует новый 32-байтный ключ и сохраняет его
     * в зашифрованном виде через EncryptedSharedPreferences.
     */
    fun getDatabaseEncryptionKey(context: Context): ByteArray? {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val sharedPreferences = EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            val savedKeyBase64 = sharedPreferences.getString(KEY_DB_ENCRYPTION, null)
            if (savedKeyBase64 != null) {
                Base64.decode(savedKeyBase64, Base64.DEFAULT)
            } else {
                val newKey = ByteArray(32).apply { SecureRandom().nextBytes(this) }
                sharedPreferences.edit()
                    .putString(KEY_DB_ENCRYPTION, Base64.encodeToString(newKey, Base64.DEFAULT))
                    .apply()
                newKey
            }
        } catch (e: Exception) {
            android.util.Log.e("SecurityManager", "Критическая ошибка Android Keystore: шифрование БД невозможно", e)
            null
        }
    }
}
