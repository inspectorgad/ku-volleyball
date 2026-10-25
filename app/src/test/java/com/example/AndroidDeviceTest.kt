package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.platform.AndroidDevice
import com.example.platform.HttpException
import com.example.platform.OkHttpHttp
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * AndroidDevice must find everything where the app kept it before the shared
 * code existed - a phone that updates keeps its sync history, model choice and
 * simulator data - and OkHttpHttp must answer as the OkHttp calls it replaced.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidDeviceTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val device = AndroidDevice(context)

    @Test
    fun `settings read and write the same SharedPreferences the app always used`() {
        context.getSharedPreferences("season_sync", Context.MODE_PRIVATE).edit()
            .putLong("last_success_ms", 1_760_000_000_000)
            .putString("last_hash", "abc")
            .putInt("game_1_sets", 3)
            .putBoolean("game_1_final", true)
            .apply()
        val s = device.settings("season_sync")
        assertEquals(1_760_000_000_000, s.long("last_success_ms"))
        assertEquals("abc", s.string("last_hash"))
        assertEquals(3, s.int("game_1_sets"))
        assertTrue(s.boolean("game_1_final"))
        assertEquals(0L, s.long("never_set"))
        assertEquals(7, s.int("never_set", 7))
        assertNull(s.string("never_set"))
        assertFalse(s.boolean("never_set"))

        s.edit {
            putString("last_hash", null) // null removes, as in SharedPreferences
            putString("last_generated_at", "2026-10-25T07:42:36Z")
            putLong("last_failure_ms", 5)
        }
        val prefs = context.getSharedPreferences("season_sync", Context.MODE_PRIVATE)
        assertFalse(prefs.contains("last_hash"))
        assertEquals("2026-10-25T07:42:36Z", prefs.getString("last_generated_at", null))
        assertEquals(5L, prefs.getLong("last_failure_ms", 0))

        s.edit { remove("last_failure_ms") }
        assertFalse(prefs.contains("last_failure_ms"))
        // Separate names stay separate.
        assertNull(device.settings("ask_prefs").string("last_generated_at"))
    }

    @Test
    fun `files live in filesDir under their own names`() {
        assertNull(device.readFile("simulation.json"))
        File(context.filesDir, "simulation.json").writeText("{\"old\": true}")
        assertEquals("{\"old\": true}", device.readFile("simulation.json"))
        assertTrue(device.writeFile("simulation.json", "{\"new\": \"é ☃\"}"))
        assertEquals("{\"new\": \"é ☃\"}", File(context.filesDir, "simulation.json").readText())
    }

    @Test
    fun `assets are the files shipped in the app`() {
        assertEquals(File("src/main/assets/seed.json").readText(), device.readAsset("seed.json"))
        assertNull(device.readAsset("no-such-file.json"))
    }

    @Test
    fun `a secret either round-trips or reports that it could not be kept`() {
        // The test machine may have no Android Keystore; then saving must say
        // so rather than pretend.
        if (device.saveSecret("api_key", "sk-ant-test")) {
            assertEquals("sk-ant-test", device.loadSecret("api_key"))
        } else {
            assertNull(device.loadSecret("api_key"))
        }
        device.forgetSecret("api_key")
        assertNull(device.loadSecret("api_key"))
    }

    @Test
    fun `http answers carry the status and the exact bytes, errors included`() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val bytes = byteArrayOf(0x7b, 0x7d, 0, -1, 0x0a)
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
            server.enqueue(MockResponse().setResponseCode(404).setBody("missing"))
            server.enqueue(MockResponse().setBody("\uFEFF{\"a\":1}"))
            server.enqueue(MockResponse())
            val http = OkHttpHttp(connectSeconds = 10, readSeconds = 10)

            val ok = http.get(server.url("/data").toString(), mapOf("Cache-Control" to "no-cache"))
            assertEquals(200, ok.code)
            assertTrue(ok.isSuccessful)
            assertArrayEquals(bytes, ok.body)
            assertEquals("no-cache", server.takeRequest().getHeader("Cache-Control"))

            val missing = http.get(server.url("/gone").toString())
            assertEquals(404, missing.code)
            assertFalse(missing.isSuccessful)
            assertEquals("missing", missing.text())
            server.takeRequest()

            // A byte-order mark is dropped, as OkHttp's string() dropped it.
            assertEquals("{\"a\":1}", http.get(server.url("/bom").toString()).text())
            server.takeRequest()

            http.post(server.url("/v1/messages").toString(), mapOf("x-api-key" to "k"), "{\"q\":\"é\"}")
            val posted = server.takeRequest()
            assertEquals("POST", posted.method)
            assertEquals("k", posted.getHeader("x-api-key"))
            assertEquals("application/json; charset=utf-8", posted.getHeader("Content-Type"))
            assertEquals("{\"q\":\"é\"}", posted.body.readUtf8())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `an unreachable server is an HttpException`() = runBlocking {
        val server = MockWebServer().apply { start() }
        val url = server.url("/").toString()
        server.shutdown()
        val thrown = try {
            OkHttpHttp(connectSeconds = 5, readSeconds = 5).get(url)
            null
        } catch (e: HttpException) {
            e
        }
        assertTrue(thrown != null)
    }
}
