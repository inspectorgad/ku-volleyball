@file:OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)

package com.example.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.*
import platform.posix.memcpy

// Bytes between Kotlin and Foundation, as Ktor's iOS engine converts them.

internal fun ByteArray.toNSData(): NSData = NSMutableData().apply {
    if (isEmpty()) return@apply
    this@toNSData.usePinned { appendBytes(it.addressOf(0), size.convert()) }
}

internal fun NSData.toByteArray(): ByteArray {
    val result = ByteArray(length.toInt())
    if (result.isEmpty()) return result
    result.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return result
}
