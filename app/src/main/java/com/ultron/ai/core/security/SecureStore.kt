package com.ultron.ai.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small secret vault. A non-exportable AES-256-GCM key lives in the Android Keystore; values
 * are stored encrypted (IV || ciphertext, Base64) in app-private preferences. Holds:
 *  - the optional backend access token the user enters (never a provider API key), and
 *  - the random passphrase used to encrypt the local SQLCipher database.
 */
class SecureStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("ultron_secure", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal(plain)
        return Base64.encodeToString(cipher.iv + ct, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): ByteArray? = try {
        val all = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = all.copyOfRange(0, IV_LEN)
        val ct = all.copyOfRange(IV_LEN, all.size)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        cipher.doFinal(ct)
    } catch (_: Exception) {
        null // key invalidated / data corrupted
    }

    fun putString(name: String, value: String?) {
        if (value.isNullOrEmpty()) prefs.edit().remove(name).apply()
        else prefs.edit().putString(name, encrypt(value.toByteArray(Charsets.UTF_8))).apply()
    }

    fun getString(name: String): String? =
        prefs.getString(name, null)?.let { decrypt(it) }?.toString(Charsets.UTF_8)

    /** Returns the DB passphrase and whether it was newly created (=> any old DB is unreadable). */
    fun databasePassphrase(): Pair<ByteArray, Boolean> {
        prefs.getString(DB_KEY, null)?.let { enc -> decrypt(enc)?.let { return it to false } }
        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(DB_KEY, encrypt(fresh)).apply()
        return fresh to true
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "ultron_master_key"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
        const val DB_KEY = "db_passphrase"
    }
}
