@file:OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)

package com.example.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryCreate
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.*
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * [Device] on iPhone. Settings go to UserDefaults (each group's keys
 * prefixed with its name), files to the app's Documents folder, bundled
 * files come from the app bundle, and secrets - the Ask API key - to the
 * Keychain, encrypted by iOS, readable only by this app and only on this
 * phone ("ThisDeviceOnly": never copied to a backup or another device).
 */
class IosDevice : Device {
    private val documents = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String

    override fun settings(name: String): Settings = DefaultsSettings(name)

    override fun readFile(name: String): String? =
        NSData.dataWithContentsOfFile("$documents/$name")?.toByteArray()?.decodeToString()

    override fun writeFile(name: String, text: String): Boolean =
        text.encodeToByteArray().toNSData().writeToFile("$documents/$name", atomically = true)

    override fun readAsset(name: String): String? {
        val path = NSBundle.mainBundle.pathForResource(name.substringBeforeLast('.'), ofType = name.substringAfterLast('.', ""))
            ?: return null
        return NSData.dataWithContentsOfFile(path)?.toByteArray()?.decodeToString()
    }

    override fun loadSecret(name: String): String? = Keychain.load(name)

    override fun saveSecret(name: String, value: String): Boolean = Keychain.save(name, value)

    override fun forgetSecret(name: String) = Keychain.delete(name)
}

private class DefaultsSettings(private val group: String) : Settings {
    private val defaults = NSUserDefaults.standardUserDefaults
    private fun k(key: String) = "$group.$key"
    private fun has(key: String) = defaults.objectForKey(k(key)) != null

    override fun string(key: String): String? = defaults.stringForKey(k(key))
    override fun long(key: String, default: Long): Long = if (has(key)) defaults.integerForKey(k(key)).toLong() else default
    override fun int(key: String, default: Int): Int = if (has(key)) defaults.integerForKey(k(key)).toInt() else default
    override fun boolean(key: String, default: Boolean): Boolean = if (has(key)) defaults.boolForKey(k(key)) else default

    override fun edit(changes: SettingsEditor.() -> Unit) {
        object : SettingsEditor {
            override fun putString(key: String, value: String?) {
                if (value == null) defaults.removeObjectForKey(k(key)) else defaults.setObject(value, forKey = k(key))
            }
            override fun putLong(key: String, value: Long) = defaults.setInteger(value.convert(), forKey = k(key))
            override fun putInt(key: String, value: Int) = defaults.setInteger(value.convert(), forKey = k(key))
            override fun putBoolean(key: String, value: Boolean) = defaults.setBool(value, forKey = k(key))
            override fun remove(key: String) = defaults.removeObjectForKey(k(key))
        }.changes()
    }
}

/**
 * Generic-password items under this app's service name, one per secret. The
 * Core Foundation calls are the ones multiplatform-settings' KeychainSettings
 * makes.
 */
private object Keychain {
    private const val SERVICE = "io.github.inspectorgad.kuvolleyball"

    fun load(account: String): String? = withCf(SERVICE, account) { service, acct ->
        val result = alloc<CFTypeRefVar>()
        val query = dictionary(
            kSecClass to kSecClassGenericPassword, kSecAttrService to service, kSecAttrAccount to acct,
            kSecReturnData to kCFBooleanTrue, kSecMatchLimit to kSecMatchLimitOne,
        )
        val status = SecItemCopyMatching(query, result.ptr)
        CFBridgingRelease(query)
        if (status != errSecSuccess) null else (CFBridgingRelease(result.value) as? NSData)?.toByteArray()?.decodeToString()
    }

    fun save(account: String, value: String): Boolean {
        delete(account)
        val data = value.encodeToByteArray().toNSData()
        return withCf(SERVICE, account) { service, acct ->
            val cfData = CFBridgingRetain(data)
            val query = dictionary(
                kSecClass to kSecClassGenericPassword, kSecAttrService to service, kSecAttrAccount to acct,
                kSecValueData to cfData, kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            )
            val status = SecItemAdd(query, null)
            CFBridgingRelease(query)
            CFBridgingRelease(cfData)
            status == errSecSuccess
        }
    }

    fun delete(account: String) {
        withCf(SERVICE, account) { service, acct ->
            val query = dictionary(kSecClass to kSecClassGenericPassword, kSecAttrService to service, kSecAttrAccount to acct)
            SecItemDelete(query)
            CFBridgingRelease(query)
        }
    }

    /** Runs [block] with the two strings as Core Foundation objects, released afterwards. */
    private inline fun <T> withCf(a: String, b: String, block: MemScope.(CFTypeRef?, CFTypeRef?) -> T): T = memScoped {
        val cfA = CFBridgingRetain(a)
        val cfB = CFBridgingRetain(b)
        try {
            block(cfA, cfB)
        } finally {
            CFBridgingRelease(cfA)
            CFBridgingRelease(cfB)
        }
    }

    private fun MemScope.dictionary(vararg items: Pair<CFStringRef?, CFTypeRef?>): CFDictionaryRef? {
        val keys = allocArrayOf(*items.map { it.first }.toTypedArray())
        val values = allocArrayOf(*items.map { it.second }.toTypedArray())
        return CFDictionaryCreate(kCFAllocatorDefault, keys.reinterpret(), values.reinterpret(), items.size.convert(), null, null)
    }
}
