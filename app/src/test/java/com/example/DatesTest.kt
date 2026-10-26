package com.example

import com.example.text.centralToUtcMs
import com.example.text.reformat
import com.example.text.todayIso
import com.example.text.utcToLocal
import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * com.example.text's dates replace SimpleDateFormat, which iOS does not have.
 * Each check runs the same input through SimpleDateFormat, set up exactly as
 * the app used to set it up, and through the replacement.
 */
class DatesTest {

    private fun sdf(pattern: String, zone: String = "UTC", lenient: Boolean = true) =
        SimpleDateFormat(pattern, Locale.US).apply {
            timeZone = TimeZone.getTimeZone(zone)
            isLenient = lenient
        }

    // What MatchFacts.reformat used to do.
    private fun oldReformat(value: String, from: String, to: String): String? =
        runCatching { sdf(to).format(sdf(from, lenient = false).parse(value)!!) }.getOrNull()

    private val days = generateSequence(LocalDate.of(1995, 1, 1)) { it.plusDays(1) }
        .takeWhile { it.year <= 2035 }
        .map { it.toString() }
        .toList()

    @Test
    fun `every date the schedule can hold, in every pattern the app writes`() {
        for (day in days) {
            for (to in listOf("EEE MMM d", "EEEE, MMMM d, yyyy", "MMM ''yy", "d/M/yy", "EE dd MMM y")) {
                assertEquals("$day as $to", oldReformat(day, "yyyy-MM-dd", to), reformat(day, "yyyy-MM-dd", to))
            }
        }
    }

    @Test
    fun `every minute of the day as a 12-hour time`() {
        for (h in 0..23) for (m in 0..59) {
            val t = "%02d:%02d".format(h, m)
            assertEquals(t, oldReformat(t, "HH:mm", "h:mm a"), reformat(t, "HH:mm", "h:mm a"))
        }
    }

    @Test
    fun `what SimpleDateFormat refused or tolerated, this refuses or tolerates`() {
        val inputs = listOf(
            "", "x", "24:00", "12:60", "7:5", "07:05pm", "2026-02-30", "2026-13-01", "2026-00-10",
            "2025-02-29", "2024-02-29", "2026-10-15T19:00", "2026/10/15", " 2026-10-15", "2026- 10-15",
            "\t18:00", "18: 05",
        )
        for (input in inputs) {
            for ((from, to) in listOf("yyyy-MM-dd" to "EEE MMM d", "HH:mm" to "h:mm a")) {
                assertEquals("'$input' as $from", oldReformat(input, from, to), reformat(input, from, to))
            }
        }
    }

    @Test
    fun `Kansas first-serve times convert to UTC across daylight saving`() {
        // 1:00-2:59 is left out: on the two change-over nights those times are
        // missing or doubled, and no match starts then.
        val times = listOf("00:00", "00:45", "03:00", "09:30", "11:00", "12:00", "16:30", "18:00", "19:30", "23:59")
        for (day in days.filter { it >= "2020" && it < "2033" }) {
            for (t in times) {
                val old = runCatching { sdf("yyyy-MM-dd HH:mm", "America/Chicago", lenient = false).parse("$day $t")!!.time }
                    .getOrNull()
                assertEquals("$day $t", old, centralToUtcMs(day, t))
            }
        }
    }

    @Test
    fun `the data timestamp reads in the phone's own zone, wherever the phone is`() {
        val saved = TimeZone.getDefault()
        try {
            for (zone in listOf("America/Chicago", "America/Los_Angeles", "Asia/Kolkata", "Australia/Lord_Howe", "UTC")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                for (day in days.filter { it >= "2025" && it < "2028" }) {
                    for (t in listOf("00:00:00", "07:42:36", "23:59:59")) {
                        val iso = "${day}T${t}Z"
                        // What LeadersScreen.dataStatusLine used to do.
                        val old = runCatching {
                            SimpleDateFormat("MMM d, h:mm a", Locale.US).format(sdf("yyyy-MM-dd'T'HH:mm:ss'Z'").parse(iso)!!)
                        }.getOrNull()
                        assertEquals("$zone $iso", old, utcToLocal(iso, "MMM d, h:mm a"))
                    }
                }
                assertEquals(zone, SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()), todayIso())
            }
        } finally {
            TimeZone.setDefault(saved)
        }
    }
}
