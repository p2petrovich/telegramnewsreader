package com.p2petrovich.telegramnewsreader.utils

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Шифрование чувствительных полей ручного бэкапа ключом из Android Keystore.
 *
 * Вариант A: ключ несекспортируемый, существует только на этом устройстве.
 * При сбросе/смене телефона зашифрованная часть бэкапа не расшифруется —
 * это ожидаемо; несекретная часть (пресеты, каналы, настройки) импортируется
 * всегда (см. SettingsBackup.importFromJson — деградация при GeneralSecurityException).
 */
object BackupKey {
    private const val ALIAS = "tnr_backup_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val GCM_IV_LEN = 12
    private const val GCM_TAG_BITS = 128

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // НЕ требуем user-auth: бэкап должен работать без биометрии/PIN,
                // иначе фоновый экспорт невозможен без запроса у пользователя.
                .build()
        )
        return gen.generateKey()
    }

    /**
     * @return Base64(iv | ciphertext+tag).
     * IV генерирует Keystore-провайдер — не задаём вручную.
     */
    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        }
        val iv = cipher.iv                                  // провайдер генерит сам
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }

    /**
     * @throws GeneralSecurityException при неверном ключе / другом устройстве /
     * сброшенном Keystore / повреждении blob.
     */
    @Throws(GeneralSecurityException::class)
    fun decrypt(blob: String): String {
        val data = Base64.decode(blob, Base64.NO_WRAP)
        if (data.size <= GCM_IV_LEN) throw GeneralSecurityException("Слишком короткий blob")
        val iv = data.copyOfRange(0, GCM_IV_LEN)
        val ct = data.copyOfRange(GCM_IV_LEN, data.size)
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }
}
