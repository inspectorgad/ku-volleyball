package com.example

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import com.example.json.JSONArray as KtArray
import com.example.json.JSONObject as KtObject
import org.json.JSONArray as AndroidArray
import org.json.JSONObject as AndroidObject

/**
 * com.example.json must answer exactly as Android's org.json does: the app's
 * readers were written and tested against Android's behaviour, and the point
 * of the replacement is that moving a file to it changes nothing. Under
 * Robolectric, org.json here is Android's own implementation, so every check
 * below runs the same input through both and compares.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class JsonCompatTest {

    // One of every kind of value the feeds carry, plus the awkward ones:
    // numbers sent as strings, padded numbers, exponents, negative zero,
    // integers too big for an Int and for a Long, nulls in objects and arrays,
    // and strings that need escaping.
    private val awkward = """
        {"s": "Kansas", "empty": "", "numStr": "12", "padded": " 12 ", "decStr": "3.75",
         "boolStr": "TRUE", "falseStr": "false", "word": "twelve",
         "i": 7, "neg": -3, "zero": 0, "negZero": -0.0, "big": 3000000000,
         "huge": 123456789012345678901234567890, "d": 2.5, "whole": 2.0, "exp": 1e5,
         "small": 1.5e-7, "t": true, "f": false, "n": null,
         "o": {"a": 1, "b": [1, "x", null]}, "a": [1, 2.5, "3", null, true, {"k": "v"}, [], -0.0],
         "esc": "a/b \"q\" back\\slash tab\t nl\n cr\r ff\f bs\b \u0001     é ☃ 🏐"}
    """.trimIndent()

    private val keys = listOf(
        "s", "empty", "numStr", "padded", "decStr", "boolStr", "falseStr", "word", "i", "neg", "zero",
        "negZero", "big", "huge", "d", "whole", "exp", "small", "t", "f", "n", "o", "a", "esc", "missing",
    )

    @Test
    fun `every getter gives Android's answer for every kind of value`() {
        val kt = KtObject(awkward)
        val android = AndroidObject(awkward)
        for (key in keys) {
            assertEquals("key $key", androidFacts(android, key), ktFacts(kt, key))
        }
    }

    @Test
    fun `array getters match too, including nulls and out-of-range indexes`() {
        val kt = KtObject(awkward).getJSONArray("a")
        val android = AndroidObject(awkward).getJSONArray("a")
        assertEquals(android.length(), kt.length())
        for (i in -1..android.length()) {
            assertEquals("index $i", androidFacts(android, i), ktFacts(kt, i))
        }
    }

    @Test
    fun `parsing and writing back gives Android's text`() {
        assertEquals(AndroidObject(awkward).toString(), KtObject(awkward).toString())
        assertEquals(AndroidArray("[1,2.0,null,\"/\"]").toString(), KtArray("[1,2.0,null,\"/\"]").toString())
    }

    @Test
    fun `the shipped seed reads back identically`() {
        // The real 700 KB seed: every number, null and string in it, through
        // both parsers and both writers.
        val seed = File("src/main/assets/seed.json").readText()
        assertEquals(AndroidObject(seed).toString(), KtObject(seed).toString())
    }

    @Test
    fun `the NCAA feed fixture reads back identically`() {
        val feed = javaClass.classLoader!!.getResource("ncaa-game-utah-2026-10-01.json").readText()
        assertEquals(AndroidObject(feed).toString(), KtObject(feed).toString())
    }

    @Test
    fun `building a request body writes what Android writes`() {
        val kt = KtObject()
            .put("model", "claude-opus-5-5")
            .put("max_tokens", 16000)
            .put("long", 9_000_000_000L)
            .put("ratio", 2.0)
            .put("half", 0.5)
            .put("on", true)
            .put("gone", "x")
            .put("gone", null as Any?)
            .put("text", "Today is 10/16. \"Who\" led?\n ")
            .put("messages", KtArray(listOf(KtObject().put("role", "user"), "plain", 3, null)))
            .put("tags", KtArray(listOf("a", "b")))
            .put("nested", KtArray().put(KtObject().put("type", "ephemeral")).put(1.5).put(false))
        val android = AndroidObject()
            .put("model", "claude-opus-5-5")
            .put("max_tokens", 16000)
            .put("long", 9_000_000_000L)
            .put("ratio", 2.0)
            .put("half", 0.5)
            .put("on", true)
            .put("gone", "x")
            .put("gone", null as Any?)
            .put("text", "Today is 10/16. \"Who\" led?\n ")
            .put("messages", AndroidArray(listOf(AndroidObject().put("role", "user"), "plain", 3, null)))
            .put("tags", AndroidArray(listOf("a", "b")))
            .put("nested", AndroidArray().put(AndroidObject().put("type", "ephemeral")).put(1.5).put(false))
        assertEquals(android.toString(), kt.toString())
        assertEquals(android.has("gone"), kt.has("gone"))
    }

    @Test
    fun `malformed input is a JSONException, as on Android`() {
        for (bad in listOf("", "{", "[1,", "{\"a\":}", "nope")) {
            val androidThrew = runCatching { AndroidObject(bad) }.exceptionOrNull() is org.json.JSONException
            val ktThrew = runCatching { KtObject(bad) }.exceptionOrNull() is com.example.json.JSONException
            assertEquals("input '$bad'", androidThrew, ktThrew)
        }
        // A well-formed array where an object is wanted, and the reverse.
        assertEquals(true, runCatching { KtObject("[1]") }.exceptionOrNull() is com.example.json.JSONException)
        assertEquals(true, runCatching { KtArray("{}") }.exceptionOrNull() is com.example.json.JSONException)
    }

    private fun attempt(block: () -> Any?): String =
        try {
            "=" + block().toString()
        } catch (e: Exception) {
            "threw"
        }

    private fun ktFacts(o: KtObject, k: String) = listOf(
        attempt { o.has(k) }, attempt { o.isNull(k) },
        attempt { o.optString(k) }, attempt { o.optString(k, "fb") }, attempt { o.getString(k) },
        attempt { o.optInt(k) }, attempt { o.optInt(k, -9) }, attempt { o.getInt(k) },
        attempt { o.optLong(k) }, attempt { o.getLong(k) },
        attempt { o.optDouble(k) }, attempt { o.optDouble(k, -1.0) }, attempt { o.getDouble(k) },
        attempt { o.optBoolean(k) }, attempt { o.optBoolean(k, true) }, attempt { o.getBoolean(k) },
        attempt { o.optJSONObject(k)?.toString() }, attempt { o.optJSONArray(k)?.toString() },
        attempt { o.getJSONObject(k).toString() }, attempt { o.getJSONArray(k).toString() },
    )

    private fun androidFacts(o: AndroidObject, k: String) = listOf(
        attempt { o.has(k) }, attempt { o.isNull(k) },
        attempt { o.optString(k) }, attempt { o.optString(k, "fb") }, attempt { o.getString(k) },
        attempt { o.optInt(k) }, attempt { o.optInt(k, -9) }, attempt { o.getInt(k) },
        attempt { o.optLong(k) }, attempt { o.getLong(k) },
        attempt { o.optDouble(k) }, attempt { o.optDouble(k, -1.0) }, attempt { o.getDouble(k) },
        attempt { o.optBoolean(k) }, attempt { o.optBoolean(k, true) }, attempt { o.getBoolean(k) },
        attempt { o.optJSONObject(k)?.toString() }, attempt { o.optJSONArray(k)?.toString() },
        attempt { o.getJSONObject(k).toString() }, attempt { o.getJSONArray(k).toString() },
    )

    private fun ktFacts(a: KtArray, i: Int) = listOf(
        attempt { a.isNull(i) },
        attempt { a.optString(i) }, attempt { a.optString(i, "fb") }, attempt { a.getString(i) },
        attempt { a.optInt(i) }, attempt { a.getInt(i) }, attempt { a.optLong(i) },
        attempt { a.optDouble(i) }, attempt { a.getDouble(i) },
        attempt { a.optBoolean(i) }, attempt { a.getBoolean(i) },
        attempt { a.optJSONObject(i)?.toString() }, attempt { a.optJSONArray(i)?.toString() },
        attempt { a.getJSONObject(i).toString() },
    )

    private fun androidFacts(a: AndroidArray, i: Int) = listOf(
        attempt { a.isNull(i) },
        attempt { a.optString(i) }, attempt { a.optString(i, "fb") }, attempt { a.getString(i) },
        attempt { a.optInt(i) }, attempt { a.getInt(i) }, attempt { a.optLong(i) },
        attempt { a.optDouble(i) }, attempt { a.getDouble(i) },
        attempt { a.optBoolean(i) }, attempt { a.getBoolean(i) },
        attempt { a.optJSONObject(i)?.toString() }, attempt { a.optJSONArray(i)?.toString() },
        attempt { a.getJSONObject(i).toString() },
    )
}
