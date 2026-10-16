package com.example

import com.example.text.fixed
import com.example.text.hex2
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale
import kotlin.random.Random

/**
 * fixed() replaces String.format(Locale.US, "%.Nf", v) everywhere the app
 * shows a number, so it has to agree with it everywhere, not just on round
 * numbers. Each check here runs the same value through both.
 */
class NumbersTest {

    private fun java(v: Double, d: Int) = String.format(Locale.US, "%.${d}f", v)

    private fun check(v: Double) {
        for (d in 0..3) assertEquals("value $v at $d places", java(v, d), fixed(v, d))
    }

    @Test
    fun `every ratio a stat line can produce`() {
        // Hitting percentages, per-set rates and points: small integers over
        // small integers, positive and negative, scaled the ways the app shows them.
        for (a in -60..300) for (b in 1..160) {
            val r = a.toDouble() / b
            check(r)
            check(r * 100)
        }
    }

    @Test
    fun `exact halves round up, as Java does`() {
        assertEquals("0.063", fixed(0.0625, 3))
        assertEquals("0.13", fixed(0.125, 2))
        assertEquals("1.01", fixed(1.005, 2))
        assertEquals("13", fixed(12.5, 0))
        assertEquals("-0.063", fixed(-0.0625, 3))
    }

    @Test
    fun `zero, negative zero, tiny, huge and non-finite values`() {
        for (v in listOf(
            0.0, -0.0, 0.0005, 0.00049, -0.0004, 1e-5, 5e-10, 9.9996, 99.95, 999.9995,
            1e10, 1.5e15, 1e21, 123456789.987654321, Double.MIN_VALUE, Double.NaN,
            Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
        )) check(v)
    }

    @Test
    fun `random values across the range the app uses`() {
        val random = Random(20261)
        repeat(100_000) {
            check(random.nextDouble(-1.0, 1.0))
            check(random.nextDouble(-1000.0, 1000.0))
            // Values a few decimal places long, where ties actually happen.
            check(random.nextInt(-100_000, 100_000) / 10_000.0)
        }
    }

    @Test
    fun `hex bytes match the old format string`() {
        for (b in Byte.MIN_VALUE..Byte.MAX_VALUE) {
            assertEquals("%02x".format(b.toByte()), hex2(b.toByte()))
        }
    }
}
