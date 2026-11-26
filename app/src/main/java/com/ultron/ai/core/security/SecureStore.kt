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
 *  - the user's own Gemini API key, and
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

    /** True if a value is stored under [name] but cannot be decrypted (Keystore key changed/invalidated or data corrupted). */
    fun isUnreadable(name: String): Boolean = prefs.contains(name) && getString(name) == null

    sealed interface DbKey {
        val passphrase: ByteArray
        /** Existing, readable passphrase. */
        class Existing(override val passphrase: ByteArray) : DbKey
        /** First run (nothing stored, no database file). */
        class Created(override val passphrase: ByteArray) : DbKey
        /** The old passphrase could not be recovered (or is missing while a database file exists). The old file must be kept aside, not deleted. */
        class Replaced(override val passphrase: ByteArray) : DbKey
    }

    /**
     * Returns the database passphrase. A failed decryption is retried (Keystore errors can be transient, e.g. right
     * after boot) and is NEVER treated as permission to delete anything: the unreadable encrypted blob is copied to a
     * separate preference so a future key restore could still use it, and the caller is told to keep the old database
     * file aside.
     */
    fun databaseKey(dbFileExists: Boolean, now: Long = System.currentTimeMillis()): DbKey {
        val enc = prefs.getString(DB_KEY, null)
        var decrypted: ByteArray? = null
        if (enc != null) {
            for (attempt in 0 until 3) {
                decrypted = decrypt(enc)
                if (decrypted != null) break
                try { Thread.sleep(150L * (attempt + 1)) } catch (_: InterruptedException) { break }
            }
        }
        return when (com.ultron.ai.domain.DbKeyPolicy.decide(enc != null, decrypted != null, dbFileExists)) {
            com.ultron.ai.domain.DbKeyPolicy.Action.USE_EXISTING -> DbKey.Existing(decrypted!!)
            com.ultron.ai.domain.DbKeyPolicy.Action.CREATE_NEW -> DbKey.Created(storeFreshPassphrase())
            com.ultron.ai.domain.DbKeyPolicy.Action.QUARANTINE_AND_CREATE -> {
                if (enc != null) prefs.edit().putString("${DB_KEY}_unreadable_$now", enc).apply()
                DbKey.Replaced(storeFreshPassphrase())
            }
        }
    }

    private fun storeFreshPassphrase(): ByteArray {
        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(DB_KEY, encrypt(fresh)).apply()
        return fresh
    }

    /** Removes the preserved unreadable passphrase copies (only used by the explicit "reset everything"). */
    fun deleteUnreadablePassphrases() {
        val e = prefs.edit(); prefs.all.keys.filter { it.startsWith("${DB_KEY}_unreadable_") }.forEach { e.remove(it) }; e.apply()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "ultron_master_key"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
        const val DB_KEY = "db_passphrase"
    }
}
