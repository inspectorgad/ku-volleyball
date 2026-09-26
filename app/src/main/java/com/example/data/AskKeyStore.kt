package com.example.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the reader's Anthropic API key on this phone, encrypted.
 *
 * The key is encrypted with an AES key held by the Android Keystore, which
 * never leaves the device, and only the encrypted form is written to the app's
 * preferences. That file is left out of backups (res/xml/backup_rules.xml and
 * data_extraction_rules.xml), since a copy restored to another phone could not
 * be decrypted there anyway. Nothing here is ever sent anywhere; the key is
 * read only to put in the header of a request to api.anthropic.com.
 */
object AskKeyStore {
    private const val PREFS = "ask_key"
    private const val SLOT = "api_key"
    private const val MODEL_PREFS = "ask_prefs"
    private const val MODEL_SLOT = "model"
    private const val ALIAS = "ku_ask_api_key"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    /** The saved key, or null if none is saved or it can't be decrypted. */
    fun load(context: Context): String? {
        val stored = prefs(context).getString(SLOT, null) ?: return null
        return try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes, 0, 12))
            String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    /** Saves the key; false if this phone's keystore couldn't be used. */
    fun save(context: Context, key: String): Boolean = try {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val sealed = cipher.iv + cipher.doFinal(key.toByteArray(Charsets.UTF_8))
        prefs(context).edit().putString(SLOT, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
        true
    } catch (e: Exception) {
        false
    }

    fun forget(context: Context) {
        prefs(context).edit().remove(SLOT).apply()
    }

    fun model(context: Context): AskModel {
        val id = context.getSharedPreferences(MODEL_PREFS, Context.MODE_PRIVATE).getString(MODEL_SLOT, null)
        return ASK_MODELS.firstOrNull { it.id == id } ?: DEFAULT_ASK_MODEL
    }

    fun saveModel(context: Context, model: AskModel) {
        context.getSharedPreferences(MODEL_PREFS, Context.MODE_PRIVATE).edit()
            .putString(MODEL_SLOT, model.id).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
