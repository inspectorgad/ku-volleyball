package com.example.platform

// What the shared code needs from the phone it runs on, as interfaces, so the
// same code can run on Android and on iPhone. AndroidDevice is Android's
// version (androidMain); the iPhone's comes with the iPhone app.

/** Storage the app keeps on this phone. */
interface Device {
    /** A named group of small saved values (SharedPreferences on Android). */
    fun settings(name: String): Settings

    /** The text of a file the app saved for itself, or null if there is none or it can't be read. */
    fun readFile(name: String): String?

    /** Saves [text] as that file, replacing it; false if it couldn't be written. */
    fun writeFile(name: String, text: String): Boolean

    /** The text of a file shipped inside the app (assets/ on Android), or null if it can't be read. */
    fun readAsset(name: String): String?

    /** A secret kept encrypted on this phone, or null if none is saved or it can't be decrypted. */
    fun loadSecret(name: String): String?

    /** Saves a secret, encrypted; false if this phone's secure storage couldn't be used. */
    fun saveSecret(name: String, value: String): Boolean

    fun forgetSecret(name: String)
}

/** Small saved values. A key never set reads as null, 0 or false. */
interface Settings {
    fun string(key: String): String?
    fun long(key: String, default: Long = 0): Long
    fun int(key: String, default: Int = 0): Int
    fun boolean(key: String, default: Boolean = false): Boolean

    /** Makes all of [changes] together. */
    fun edit(changes: SettingsEditor.() -> Unit)
}

interface SettingsEditor {
    /** A null [value] removes the key. */
    fun putString(key: String, value: String?)
    fun putLong(key: String, value: Long)
    fun putInt(key: String, value: Int)
    fun putBoolean(key: String, value: Boolean)
    fun remove(key: String)
}
