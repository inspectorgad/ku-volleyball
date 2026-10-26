package com.example

import com.example.text.sha256
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random

/**
 * sha256() replaces MessageDigest in the season sync, whose saved hash decides
 * whether a download is news. Each check runs the same bytes through both.
 */
class Sha256Test {

    private fun java(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    @Test
    fun `the standard test vectors`() {
        fun hex(s: String) = sha256(s.toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hex(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex("abc"))
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
        )
    }

    @Test
    fun `every length across the padding boundaries`() {
        // 55, 56, 63 and 64 bytes are where the padding spills into a second block.
        val random = Random(1)
        for (size in 0..1100) {
            val bytes = random.nextBytes(size)
            assertArrayEquals("size $size", java(bytes), sha256(bytes))
        }
    }

    @Test
    fun `random payloads up to the size of a season file`() {
        val random = Random(2)
        repeat(2000) {
            val bytes = random.nextBytes(random.nextInt(0, 50_000))
            assertArrayEquals("size ${bytes.size}", java(bytes), sha256(bytes))
        }
    }

    @Test
    fun `the shipped seed`() {
        val seed = File("src/main/assets/seed.json").readBytes()
        assertArrayEquals(java(seed), sha256(seed))
    }
}
