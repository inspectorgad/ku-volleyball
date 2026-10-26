package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.JayhawksDatabase
import com.example.data.SeasonSync
import com.example.data.Seeder
import com.example.data.SimulationStore
import com.example.data.SyncResult
import com.example.json.JSONArray
import com.example.json.JSONObject
import com.example.platform.AndroidDevice
import com.example.platform.Http
import com.example.platform.HttpException
import com.example.platform.HttpResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest

/**
 * The season sync, end to end against made-up answers from the three feeds:
 * which feed wins, what a failure says, what is saved, and that saved values
 * keep the form the app has always written, so a phone that updates carries on
 * from its existing sync history.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SeasonSyncTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val device = AndroidDevice(context)
    private lateinit var db: JayhawksDatabase

    private val pages = "https://inspectorgad.github.io/ku-volleyball/season-data.json"
    private val raw = "https://raw.githubusercontent.com/inspectorgad/ku-volleyball/main/app/src/main/assets/seed.json"
    private val release = "https://github.com/inspectorgad/ku-volleyball/releases/latest/download/season-data.json"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, JayhawksDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** Answers each URL from [answers]; a missing URL fails the test. */
    private class FakeHttp(private val answers: Map<String, suspend () -> HttpResponse>) : Http {
        val asked = mutableListOf<String>()
        override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
            asked += url
            return answers.getValue(url)()
        }

        override suspend fun post(url: String, headers: Map<String, String>, json: String) = error("not used")
    }

    private fun season(generatedAt: String): ByteArray = JSONObject()
        .put("formatVersion", 1)
        .put("generatedAt", generatedAt)
        .put("players", JSONArray().apply { repeat(12) { put(JSONObject().put("name", "Player $it")) } })
        .put("matches", JSONArray().apply {
            repeat(16) {
                put(
                    JSONObject().put("date", "2026-09-%02d".format(it + 1)).put("opponent", "Opponent $it")
                        .put("season", "2026")
                )
            }
        })
        .put("simulation", JSONObject().put("note", generatedAt))
        .toString().toByteArray()

    private fun answer(code: Int, bytes: ByteArray): suspend () -> HttpResponse = { HttpResponse(code, bytes) }
    private fun ok(bytes: ByteArray) = answer(200, bytes)
    private val unreachable: suspend () -> HttpResponse = { throw HttpException("no route") }

    @Test
    fun `when every feed fails, the message says what each one did and is kept`() = runBlocking {
        val http = FakeHttp(
            mapOf(
                pages to unreachable,
                raw to answer(404, "Not Found".toByteArray()),
                release to ok("{\"players\": []}".toByteArray()),
            )
        )
        val result = SeasonSync.sync(device, db.dao(), http)
        val reason = "inspectorgad.github.io unreachable; raw.githubusercontent.com 404; github.com sent invalid data"
        assertEquals(SyncResult.Failed(reason), result)
        assertEquals(listOf(pages, raw, release), http.asked)
        assertEquals(reason, SeasonSync.lastFailure(device))
        assertEquals(0L, SeasonSync.lastSuccessMs(device))
        assertTrue(SeasonSync.shouldAutoSync(device))
    }

    @Test
    fun `an answer that breaks off reads as its status, or as unreachable if it was a success`() = runBlocking {
        fun brokeOff(code: Int): suspend () -> HttpResponse = { throw HttpException("stopped answering", code = code) }
        val http = FakeHttp(mapOf(pages to brokeOff(200), raw to brokeOff(502), release to unreachable))
        assertEquals(
            SyncResult.Failed("inspectorgad.github.io unreachable; raw.githubusercontent.com 502; github.com unreachable"),
            SeasonSync.sync(device, db.dao(), http),
        )
    }

    @Test
    fun `the first good feed wins, then the same data again is no change`() = runBlocking {
        val bytes = season("2026-10-25T07:42:36Z")
        val http = FakeHttp(mapOf(pages to answer(503, ByteArray(0)), raw to ok(bytes)))

        assertEquals(SyncResult.Updated("2026-10-25T07:42:36Z"), SeasonSync.sync(device, db.dao(), http))
        assertEquals(listOf(pages, raw), http.asked)
        assertEquals(12, db.dao().playersOnce().size)
        assertEquals(16, db.dao().matchesOnce().size)
        assertEquals("2026-10-25T07:42:36Z", SeasonSync.lastGeneratedAt(device))
        assertNull(SeasonSync.lastFailure(device))
        assertTrue(SeasonSync.lastSuccessMs(device) > 0)
        assertFalse(SeasonSync.shouldAutoSync(device))
        assertEquals("2026-10-25T07:42:36Z", SimulationStore.load(device)!!.getJSONObject("simulation").getString("note"))

        // Saved where, and in the form, the app always saved it: the hex of
        // MessageDigest's SHA-256 in the "season_sync" preferences.
        val prefs = context.getSharedPreferences("season_sync", Context.MODE_PRIVATE)
        val javaHash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(javaHash, prefs.getString("last_hash", null))

        assertEquals(SyncResult.NoChange, SeasonSync.sync(device, db.dao(), http))
    }

    @Test
    fun `the season data bundled in the app never rolls back newer data from the feed`() = runBlocking {
        // The feed's data is newer than the app's own copy (this repo's seed.json).
        val newer = FakeHttp(mapOf(pages to ok(season("2099-01-01T00:00:00Z"))))
        assertEquals(SyncResult.Updated("2099-01-01T00:00:00Z"), SeasonSync.sync(device, db.dao(), newer))
        Seeder.sync(device, db.dao())
        assertEquals(16, db.dao().matchesOnce().size)

        // The feed's data is older: the app's own copy is merged in at launch.
        val older = FakeHttp(mapOf(pages to ok(season("2000-01-01T00:00:00Z"))))
        assertEquals(SyncResult.Updated("2000-01-01T00:00:00Z"), SeasonSync.sync(device, db.dao(), older))
        Seeder.sync(device, db.dao())
        assertTrue(db.dao().matchesOnce().size > 16)
    }

    @Test
    fun `data an older build rolled back is put back by merging the feed once more, at once`() = runBlocking {
        val http = FakeHttp(mapOf(pages to ok(season("2099-01-01T00:00:00Z"))))
        assertEquals(SyncResult.Updated("2099-01-01T00:00:00Z"), SeasonSync.sync(device, db.dao(), http))
        assertFalse(SeasonSync.shouldAutoSync(device))

        // First launch with the check: the bundled copy is held back, and the
        // unchanged feed is merged again straight away.
        Seeder.sync(device, db.dao())
        assertTrue(SeasonSync.shouldAutoSync(device))
        assertEquals(SyncResult.Updated("2099-01-01T00:00:00Z"), SeasonSync.sync(device, db.dao(), http))

        // Only that once.
        Seeder.sync(device, db.dao())
        assertFalse(SeasonSync.shouldAutoSync(device))
        assertEquals(SyncResult.NoChange, SeasonSync.sync(device, db.dao(), http))
    }

    @Test
    fun `a failure after a success is shown until the next success`() = runBlocking {
        val good = FakeHttp(mapOf(pages to ok(season("2026-10-24T07:00:00Z"))))
        SeasonSync.sync(device, db.dao(), good)
        // Failure times are compared to success times, so make the failure later.
        Thread.sleep(5)
        val down = FakeHttp(mapOf(pages to unreachable, raw to unreachable, release to unreachable))
        assertTrue(SeasonSync.sync(device, db.dao(), down) is SyncResult.Failed)
        assertNotNull(SeasonSync.lastFailure(device))
        Thread.sleep(5)
        assertEquals(SyncResult.Updated("2026-10-25T07:00:00Z"),
            SeasonSync.sync(device, db.dao(), FakeHttp(mapOf(pages to ok(season("2026-10-25T07:00:00Z"))))))
        assertNull(SeasonSync.lastFailure(device))
    }

    @Test
    fun `stopping a sync stops it, rather than recording a failure`() = runBlocking {
        val asked = CompletableDeferred<Unit>()
        val hang: suspend () -> HttpResponse = {
            asked.complete(Unit)
            awaitCancellation()
        }
        val http = FakeHttp(mapOf(pages to hang))
        val job = async { SeasonSync.sync(device, db.dao(), http) }
        asked.await()
        job.cancel()
        val stopped = try {
            job.await(); false
        } catch (e: CancellationException) {
            true
        }
        assertTrue(stopped)
        assertNull(SeasonSync.lastFailure(device))
        assertEquals(listOf(pages), http.asked)
    }
}
