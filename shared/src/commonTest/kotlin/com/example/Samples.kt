package com.example

import com.example.json.JSONArray
import com.example.json.JSONObject
import com.example.text.centralToUtcMs
import com.example.text.fixed
import com.example.text.reformat
import com.example.text.roundHalfUp
import com.example.text.sha256
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.random.Random

internal fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

/** The inputs, and the answers as text. Shared by the test and the JVM run that records [Golden]. */
internal object Samples {

    fun sha256Lines(): List<String> {
        val random = Random(1)
        return (0..300).map { size -> hex(sha256(random.nextBytes(size))) }
    }

    private val edgeNumbers = listOf(
        0.0, -0.0, 0.5, -0.5, 1.5, -1.5, 2.5, -2.5, 0.0625, 0.125, 1.005, 12.5, -0.0625, 0.0005, 0.00049,
        -0.0004, 1e-5, 5e-10, 9.9996, 99.95, 999.9995, 1e10, 1.5e15, 1e21, 123456789.987654321,
        0.49999999999999994, 4503599627370497.0, Double.MIN_VALUE, Double.MAX_VALUE, Double.NaN,
        Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
    )

    /** Ratios a stat line can produce, random values, and the awkward ones. */
    fun numbers(): List<Double> {
        val values = edgeNumbers.toMutableList()
        for (a in -60..300) for (b in 1..60) {
            values += a.toDouble() / b
            values += a.toDouble() / b * 100
        }
        val random = Random(20261)
        repeat(20_000) {
            values += random.nextDouble(-1.0, 1.0)
            values += random.nextDouble(-1000.0, 1000.0)
            values += random.nextInt(-100_000, 100_000) / 10_000.0
        }
        return values
    }

    fun fixedSpots(): String = edgeNumbers.joinToString(" | ") { v -> (0..3).joinToString(",") { fixed(v, it) } }

    fun fixedLines(): List<String> = numbers().map { v -> (0..3).joinToString(",") { fixed(v, it) } }

    fun roundLines(): List<String> = numbers().map { roundHalfUp(it).toString() }

    // Every kind of value the feeds carry, and the awkward ones; the same
    // sample JsonCompatTest checks against Android's org.json.
    private val awkward: String = run {
        val ls = Char(0x2028)
        val ps = Char(0x2029)
        val ctl = Char(1)
        """{"s": "Kansas", "empty": "", "numStr": "12", "padded": " 12 ", "decStr": "3.75",
         "boolStr": "TRUE", "falseStr": "false", "word": "twelve",
         "i": 7, "neg": -3, "zero": 0, "negZero": -0.0, "big": 3000000000,
         "huge": 123456789012345678901234567890, "d": 2.5, "whole": 2.0, "exp": 1e5,
         "small": 1.5e-7, "t": true, "f": false, "n": null,
         "o": {"a": 1, "b": [1, "x", null]}, "a": [1, 2.5, "3", null, true, {"k": "v"}, [], -0.0],
         "esc": "a/b \"q\" back\\slash tab\t nl\n cr\r ff\u000c bs\b $ctl $ls $ps é ☃ 🏐"}"""
    }

    fun jsonText(): String = JSONObject(awkward).toString()

    fun jsonGetterLines(): List<String> {
        val o = JSONObject(awkward)
        val keys = o.keySet().toList() + "missing"
        val lines = keys.map { k ->
            listOf(
                o.has(k), o.isNull(k), o.optString(k), o.optInt(k), o.optLong(k), o.optDouble(k),
                o.optBoolean(k), o.optJSONObject(k), o.optJSONArray(k),
            ).joinToString(" | ")
        }
        val a = o.getJSONArray("a")
        val items = (-1..a.length()).map { i ->
            listOf(a.isNull(i), a.optString(i), a.optInt(i), a.optDouble(i), a.optBoolean(i)).joinToString(" | ")
        }
        val built = JSONObject()
            .put("max_tokens", 16000).put("long", 9_000_000_000L).put("ratio", 2.0).put("half", 0.5)
            .put("tiny", 1.0e-7).put("big", 1.0e21).put("neg", -0.0).put("on", true)
            .put("list", JSONArray(listOf("a", 3, null, 2.5)))
            .toString()
        return lines + items + built
    }

    private val days: List<String> = buildList {
        var day = LocalDate(1995, 1, 1)
        while (day.year <= 2035) {
            add(day.toString())
            day = day.plus(1, DateTimeUnit.DAY)
        }
    }

    private val patterns = listOf("EEE MMM d", "EEEE, MMMM d, yyyy", "MMM ''yy", "d/M/yy", "EE dd MMM y")

    fun dateSpots(): String = listOf("2026-10-25", "2024-02-29", "2026-02-30", " 2026-10-15", "x").joinToString(" | ") { d ->
        patterns.joinToString(",") { reformat(d, "yyyy-MM-dd", it).toString() }
    } + " | " + listOf("00:00", "12:00", "18:30", "23:59", "24:00").joinToString(",") { reformat(it, "HH:mm", "h:mm a").toString() }

    fun dateLines(): List<String> =
        days.map { d -> patterns.joinToString(",") { reformat(d, "yyyy-MM-dd", it).toString() } } +
            (0..23).flatMap { h -> (0..59).map { m -> "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}" } }
                .map { reformat(it, "HH:mm", "h:mm a").toString() }

    fun centralLines(): List<String> {
        val times = listOf("00:00", "00:45", "03:00", "09:30", "11:00", "12:00", "16:30", "18:00", "19:30", "23:59")
        return days.filter { it >= "2020" && it < "2033" }.map { d -> times.joinToString(",") { centralToUtcMs(d, it).toString() } }
    }
}
