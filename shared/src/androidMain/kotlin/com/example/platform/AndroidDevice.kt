package com.example.platform

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [Device] on Android. Every value lives where the app kept it before the
 * shared code existed - the same SharedPreferences files and keys, the same
 * files under filesDir, the same Keystore key - so an update finds everything
 * a phone already has.
 */
class AndroidDevice(context: Context) : Device {
    private val context = context.applicationContext

    override fun settings(name: String): Settings =
        PreferencesSettings(context.getSharedPreferences(name, Context.MODE_PRIVATE))

    override fun readFile(name: String): String? = runCatching { File(context.filesDir, name).readText() }.getOrNull()

    override fun writeFile(name: String, text: String): Boolean =
        runCatching { File(context.filesDir, name).writeText(text) }.isSuccess

    override fun readAsset(name: String): String? =
        runCatching { context.assets.open(name).bufferedReader().use { it.readText() } }.getOrNull()

    /**
     * Secrets are encrypted with an AES key held by the Android Keystore,
     * which never leaves the phone, and only the encrypted form is saved, in
     * the "ask_key" preferences (named for the first secret, the Ask API
     * key). That file is left out of backups (res/xml/backup_rules.xml and
     * data_extraction_rules.xml), since a copy restored to another phone could
     * not be decrypted there anyway.
     */
    override fun loadSecret(name: String): String? {
        val stored = secrets().getString(name, null) ?: return null
        return try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes, 0, 12))
            String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    override fun saveSecret(name: String, value: String): Boolean = try {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val sealed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        secrets().edit().putString(name, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
        true
    } catch (e: Exception) {
        false
    }

    override fun forgetSecret(name: String) {
        secrets().edit().remove(name).apply()
    }

    private fun secrets() = context.getSharedPreferences(SECRETS, Context.MODE_PRIVATE)

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

    private companion object {
        const val SECRETS = "ask_key"
        const val ALIAS = "ku_ask_api_key"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}

private class PreferencesSettings(private val prefs: SharedPreferences) : Settings {
    override fun string(key: String): String? = prefs.getString(key, null)
    override fun long(key: String, default: Long): Long = prefs.getLong(key, default)
    override fun int(key: String, default: Int): Int = prefs.getInt(key, default)
    override fun boolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)

    override fun edit(changes: SettingsEditor.() -> Unit) {
        val editor = prefs.edit()
        object : SettingsEditor {
            override fun putString(key: String, value: String?) { editor.putString(key, value) }
            override fun putLong(key: String, value: Long) { editor.putLong(key, value) }
            override fun putInt(key: String, value: Int) { editor.putInt(key, value) }
            override fun putBoolean(key: String, value: Boolean) { editor.putBoolean(key, value) }
            override fun remove(key: String) { editor.remove(key) }
        }.changes()
        editor.apply()
    }
}
