package com.example

import com.example.text.sha256
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The shared code has to give the same answers on iPhone as on Android.
 *
 * The Android app's tests check it against Java and Android themselves
 * (String.format, SimpleDateFormat, org.json, MessageDigest), none of which
 * exist on iPhone. These tests run on both phones over the same inputs and
 * compare with answers recorded on Android's JVM. A long list of answers is
 * compared as one digest, the SHA-256 of all of them, one per line; the spot
 * checks before each digest usually show which value differs when it fails.
 *
 * The recorded answers in Golden come from running Samples on the JVM. If
 * the shared code changes on purpose, record them again there.
 */
class PlatformAgreementTest {

    private fun digest(lines: List<String>) = hex(sha256(lines.joinToString("\n").encodeToByteArray()))

    @Test
    fun sha256_matches_the_standard_vectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hex(sha256(ByteArray(0))))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex(sha256("abc".encodeToByteArray())))
        assertEquals(Golden.SHA256, digest(Samples.sha256Lines()))
    }

    @Test
    fun numbers_are_written_as_on_android() {
        assertEquals(Golden.FIXED_SPOTS, Samples.fixedSpots())
        assertEquals(Golden.FIXED, digest(Samples.fixedLines()))
        assertEquals(Golden.ROUND, digest(Samples.roundLines()))
    }

    @Test
    fun json_reads_and_writes_as_on_android() {
        assertEquals(Golden.JSON_TEXT, Samples.jsonText())
        assertEquals(Golden.JSON_GETTERS, digest(Samples.jsonGetterLines()))
    }

    @Test
    fun dates_read_and_write_as_on_android() {
        assertEquals(Golden.DATE_SPOTS, Samples.dateSpots())
        assertEquals(Golden.DATES, digest(Samples.dateLines()))
        assertEquals(Golden.CENTRAL, digest(Samples.centralLines()))
    }
}
