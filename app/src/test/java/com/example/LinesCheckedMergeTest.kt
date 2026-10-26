package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.JayhawksDatabase
import com.example.data.SeasonSync
import com.example.data.Seeder
import com.example.data.StatLine
import com.example.data.SyncResult
import com.example.json.JSONObject
import com.example.platform.AndroidDevice
import com.example.platform.Device
import com.example.platform.Http
import com.example.platform.HttpResponse
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest

/**
 * The once-per-match correction of stat lines a phone already holds. The NCAA
 * feed credited every Kansas player on the team sheet with every set of a
 * match; the feed now takes sets played from Kansas Athletics' box score,
 * drops the lines of players who never came on, and marks each match it has
 * done so linesChecked. A phone corrects the lines it holds for a marked match
 * once - and only for a marked match - without losing what was edited by hand.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LinesCheckedMergeTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val device = AndroidDevice(context)
    private lateinit var db: JayhawksDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, JayhawksDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun line(player: String, sp: Int, k: Int = 0) =
        """{"player": "$player", "sp": $sp, "k": $k, "e": 0, "ta": ${if (k > 0) 30 else 0},
            "a": 0, "sa": 0, "se": 0, "d": 0, "bs": 0, "ba": 0, "re": 0, "bhe": 0, "sat": 0}"""

    private fun match(opponent: String, date: String, checked: Boolean, vararg lines: String) =
        """{"date": "$date", "opponent": "$opponent", "season": "2026",
            "teamSets": 3, "opponentSets": 0, "setScores": "25-20, 25-20, 25-20",
            ${if (checked) "\"linesChecked\": true," else ""}
            "lines": [${lines.joinToString(",")}]}"""

    /**
     * A whole feed around [matches], with the players and fixtures it takes to
     * pass the sync's sanity check.
     */
    private fun feed(vararg matches: String): JSONObject = JSONObject(
        """
        {
          "formatVersion": 1, "generatedAt": "2026-10-26T07:00:00Z",
          "players": [{"name": "Ada Alpha"}, {"name": "Bea Bench"}, {"name": "Cy Cover"},
                      ${(1..9).joinToString(",") { "{\"name\": \"Squad $it\"}" }}],
          "matches": [${(matches.toList() + fixtures).joinToString(",")}]
        }
        """
    )

    private val fixtures = (1..15).map {
        """{"date": "2026-11-${"%02d".format(it)}", "opponent": "Fixture $it", "season": "2026"}"""
    }

    /** What the NCAA feed sent: the bench player down for every set too. */
    private val baylorBefore = match("Baylor", "2026-10-25", false, line("Ada Alpha", 5, k = 13), line("Bea Bench", 5))

    /** Checked against Kansas Athletics: Ada played four sets, Bea none. */
    private val baylorChecked = match("Baylor", "2026-10-25", true, line("Ada Alpha", 4, k = 13))

    private val kStateBefore = match("Kansas St.", "2026-10-15", false, line("Ada Alpha", 3, k = 9), line("Bea Bench", 3))
    private val kStateChecked = match("Kansas St.", "2026-10-15", true, line("Ada Alpha", 2, k = 9))

    private suspend fun lines(opponent: String): Map<String, StatLine> {
        val dao = db.dao()
        val names = dao.playersOnce().associate { it.id to it.name }
        val matchId = dao.matchesOnce().single { it.opponent == opponent }.id
        return dao.statLinesOnce().filter { it.matchId == matchId }.associateBy { names.getValue(it.playerId) }
    }

    private fun checkedKeys(): String? =
        context.getSharedPreferences("season_seed", Context.MODE_PRIVATE).getString("lines_checked", null)

    @Test
    fun `a match the feed has not checked keeps its lines as they are, while a checked one is corrected`() = runBlocking {
        val dao = db.dao()
        Seeder.merge(feed(baylorBefore, kStateBefore), dao, device)

        // Edited by hand in the Baylor match: sets played, and a line typed in
        // for a player the feed never listed, with nothing on it but sets.
        dao.upsertStatLine(lines("Baylor").getValue("Ada Alpha").copy(setsPlayed = 3))
        val baylor = dao.matchesOnce().single { it.opponent == "Baylor" }
        val cy = dao.playersOnce().single { it.name == "Cy Cover" }
        dao.upsertStatLine(StatLine(playerId = cy.id, matchId = baylor.id, setsPlayed = 2))

        Seeder.merge(feed(baylorBefore, kStateChecked), dao, device)
        val b = lines("Baylor")
        assertEquals(3, b.getValue("Ada Alpha").setsPlayed)
        assertEquals(2, b.getValue("Cy Cover").setsPlayed)
        assertEquals(5, b.getValue("Bea Bench").setsPlayed)
        val k = lines("Kansas St.")
        assertEquals(2, k.getValue("Ada Alpha").setsPlayed)
        assertEquals(9, k.getValue("Ada Alpha").kills)
        assertNull(k["Bea Bench"])
        assertEquals("2026-10-15|kansas st", checkedKeys())

        // Marked later, Baylor is corrected then, though another match was
        // corrected before it; Kansas St. is left as it is.
        Seeder.merge(feed(baylorChecked, kStateChecked), dao, device)
        val b2 = lines("Baylor")
        assertEquals(4, b2.getValue("Ada Alpha").setsPlayed)
        assertNull(b2["Bea Bench"])
        assertNull(b2["Cy Cover"])
        val k2 = lines("Kansas St.")
        assertEquals(2, k2.getValue("Ada Alpha").setsPlayed)
        assertNull(k2["Bea Bench"])
        assertEquals("2026-10-15|kansas st\n2026-10-25|baylor", checkedKeys())
    }

    @Test
    fun `a match synced before it was checked is corrected once, when it is`() = runBlocking {
        val dao = db.dao()
        Seeder.merge(feed(baylorBefore), dao, device)
        Seeder.merge(feed(baylorBefore), dao, device)
        assertEquals(5, lines("Baylor").getValue("Ada Alpha").setsPlayed)
        assertNull(checkedKeys())

        // A kill added by hand, and a line typed in with a dig: neither is the
        // correction's to touch.
        dao.upsertStatLine(lines("Baylor").getValue("Ada Alpha").copy(kills = 14))
        val match = dao.matchesOnce().single { it.opponent == "Baylor" }
        val cy = dao.playersOnce().single { it.name == "Cy Cover" }
        dao.upsertStatLine(StatLine(playerId = cy.id, matchId = match.id, setsPlayed = 2, digs = 1))
        val server = dao.playersOnce().single { it.name == "Squad 1" }
        dao.upsertStatLine(StatLine(playerId = server.id, matchId = match.id, setsPlayed = 1, serveAttempts = 2))

        Seeder.merge(feed(baylorChecked), dao, device)
        val ada = lines("Baylor").getValue("Ada Alpha")
        assertEquals(4, ada.setsPlayed)
        assertEquals(14, ada.kills)
        assertEquals(30, ada.attackAttempts)
        assertNull(lines("Baylor")["Bea Bench"])
        assertEquals(1, lines("Baylor").getValue("Cy Cover").digs)
        assertEquals(2, lines("Baylor").getValue("Squad 1").serveAttempts)

        // Once only: the same feed again changes nothing...
        val after = dao.statLinesOnce().toSet()
        Seeder.merge(feed(baylorChecked), dao, device)
        assertEquals(after, dao.statLinesOnce().toSet())

        // ...and sets played edited by hand afterwards are left as edited.
        dao.upsertStatLine(lines("Baylor").getValue("Ada Alpha").copy(setsPlayed = 5))
        Seeder.merge(feed(baylorChecked, kStateChecked), dao, device)
        assertEquals(5, lines("Baylor").getValue("Ada Alpha").setsPlayed)
        assertEquals("2026-10-15|kansas st\n2026-10-25|baylor", checkedKeys())
    }

    @Test
    fun `lines taken fresh from a checked match are remembered, so a later edit stands`() = runBlocking {
        val dao = db.dao()
        Seeder.merge(feed(baylorChecked), dao, device)
        assertEquals("2026-10-25|baylor", checkedKeys())
        assertFalse(Seeder.correctionPending(feed(baylorChecked), device, dao))

        dao.upsertStatLine(lines("Baylor").getValue("Ada Alpha").copy(setsPlayed = 5))
        Seeder.merge(feed(baylorChecked), dao, device)
        assertEquals(5, lines("Baylor").getValue("Ada Alpha").setsPlayed)
    }

    @Test
    fun `with no phone to remember it on nothing is corrected, and a checked match without lines removes nothing`() =
        runBlocking {
            val dao = db.dao()
            Seeder.merge(feed(baylorBefore), dao, device)
            Seeder.merge(feed(baylorChecked), dao)
            assertEquals(5, lines("Baylor").getValue("Ada Alpha").setsPlayed)
            assertNull(checkedKeys())

            val noLines = feed(match("Baylor", "2026-10-25", true))
            assertTrue(Seeder.correctionPending(noLines, device, dao))
            Seeder.merge(noLines, dao, device)
            assertEquals(setOf("Ada Alpha", "Bea Bench"), lines("Baylor").keys)
            // Remembered all the same, so the sync is not left merging it for good.
            assertFalse(Seeder.correctionPending(noLines, device, dao))
        }

    /** Answers every URL with [bytes]. */
    private class FeedHttp(private val bytes: ByteArray) : Http {
        override suspend fun get(url: String, headers: Map<String, String>) = HttpResponse(200, bytes)
        override suspend fun post(url: String, headers: Map<String, String>, json: String) = error("not used")
    }

    private fun bytes(root: JSONObject) = root.toString().toByteArray()

    @Test
    fun `the sync corrects a match the feed checks`() = runBlocking {
        val dao = db.dao()
        assertTrue(SeasonSync.sync(device, dao, FeedHttp(bytes(feed(baylorBefore)))) is SyncResult.Updated)
        assertEquals(5, lines("Baylor").getValue("Ada Alpha").setsPlayed)

        assertTrue(SeasonSync.sync(device, dao, FeedHttp(bytes(feed(baylorChecked)))) is SyncResult.Updated)
        assertEquals(4, lines("Baylor").getValue("Ada Alpha").setsPlayed)
        assertNull(lines("Baylor")["Bea Bench"])
    }

    @Test
    fun `a feed merged before is merged again while it has a correction this phone has not made`() = runBlocking {
        val dao = db.dao()
        val checked = bytes(feed(baylorChecked))
        assertTrue(SeasonSync.sync(device, dao, FeedHttp(bytes(feed(baylorBefore)))) is SyncResult.Updated)

        // A build before the correction took the checked feed, ignored the
        // mark, and kept its hash; the bundled copy is held back, so nothing
        // else will merge it.
        Seeder.merge(feed(baylorChecked), dao)
        val hash = MessageDigest.getInstance("SHA-256").digest(checked).joinToString("") { "%02x".format(it) }
        context.getSharedPreferences("season_sync", Context.MODE_PRIVATE).edit().putString("last_hash", hash).commit()
        assertEquals(5, lines("Baylor").getValue("Ada Alpha").setsPlayed)
        assertTrue(Seeder.correctionPending(feed(baylorChecked), device, dao))

        // The same bytes: merged once for the correction, then no change.
        assertEquals(SyncResult.Updated("2026-10-26T07:00:00Z"), SeasonSync.sync(device, dao, FeedHttp(checked)))
        assertEquals(4, lines("Baylor").getValue("Ada Alpha").setsPlayed)
        assertNull(lines("Baylor")["Bea Bench"])
        assertEquals(SyncResult.NoChange, SeasonSync.sync(device, dao, FeedHttp(checked)))
    }

    @Test
    fun `the bundled copy corrects a checked match on launch, and a held-back one leaves it to the sync`() = runBlocking {
        val dao = db.dao()
        assertTrue(SeasonSync.sync(device, dao, FeedHttp(bytes(feed(baylorBefore)))) is SyncResult.Updated)

        // Held back: generated before the feed this phone last merged.
        val older = feed(baylorChecked).put("generatedAt", "2026-10-25T07:00:00Z")
        Seeder.sync(object : Device by device { override fun readAsset(name: String) = older.toString() }, dao)
        assertEquals(5, lines("Baylor").getValue("Ada Alpha").setsPlayed)
        assertNull(checkedKeys())

        // Not older: merged with the phone's settings, so corrected and remembered.
        val bundled = feed(baylorChecked)
        Seeder.sync(object : Device by device { override fun readAsset(name: String) = bundled.toString() }, dao)
        assertEquals(4, lines("Baylor").getValue("Ada Alpha").setsPlayed)
        assertNull(lines("Baylor")["Bea Bench"])
        assertEquals("2026-10-25|baylor", checkedKeys())
    }
}
