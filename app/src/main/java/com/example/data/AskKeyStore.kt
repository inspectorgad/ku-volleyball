package com.example.data

import com.example.platform.Device

/**
 * Keeps the reader's Anthropic API key on this phone, encrypted, and the model
 * they picked.
 *
 * The key goes to the phone's own secure storage ([Device.saveSecret]; on
 * Android, encrypted with a key the Android Keystore holds). Nothing here is
 * ever sent anywhere; the key is read only to put in the header of a request
 * to api.anthropic.com.
 */
object AskKeyStore {
    private const val SECRET = "api_key"
    private const val MODEL_PREFS = "ask_prefs"
    private const val MODEL_SLOT = "model"

    /** The saved key, or null if none is saved or it can't be decrypted. */
    fun load(device: Device): String? = device.loadSecret(SECRET)

    /** Saves the key; false if this phone's secure storage couldn't be used. */
    fun save(device: Device, key: String): Boolean = device.saveSecret(SECRET, key)

    fun forget(device: Device) = device.forgetSecret(SECRET)

    fun model(device: Device): AskModel {
        val id = device.settings(MODEL_PREFS).string(MODEL_SLOT)
        return ASK_MODELS.firstOrNull { it.id == id } ?: DEFAULT_ASK_MODEL
    }

    fun saveModel(device: Device, model: AskModel) {
        device.settings(MODEL_PREFS).edit { putString(MODEL_SLOT, model.id) }
    }
}
