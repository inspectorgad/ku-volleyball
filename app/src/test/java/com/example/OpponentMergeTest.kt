package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.JayhawksDatabase
import com.example.data.Seeder
import com.example.stats.aggregate
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OpponentMergeTest {

    private lateinit var db: JayhawksDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, JayhawksDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    /** A match carrying both sides of the box score, as update-seed.py emits it. */
    private fun seed(
        opponentLines: String =
            """{"player":"Hailee Mack","jerseyNumber":"1","position":"L","sp":5,"k":0,"e":0,
                "ta":1,"a":5,"sa":0,"se":1,"d":28,"bs":0,"ba":0,"re":1,"bhe":0},
               {"player":"Sara Klimis","jerseyNumber":"7","position":"S","sp":5,"k":12,"e":4,
                "ta":30,"a":2,"sa":1,"se":0,"d":6,"bs":1,"ba":2,"re":0,"bhe":0}""",
        // Reception errors deliberately exceed the sum of the player lines: the
        // NCAA charges some to the team, which is why totals are stored, not summed.
        opponentStats: String =
            """{"sp":5,"k":61,"e":42,"ta":176,"a":58,"sa":5,"se":7,"d":59,"bs":4,"ba":13,
                "re":6,"bhe":0}"""
    ) = JSONObject(
        """
        {
          "players": [{"name": "Grace Nelson", "jerseyNumber": "1", "position": "Pin",
                       "height": "6-1"}],
          "matches": [
            {"date": "2025-08-23", "opponent": "Vanderbilt", "season": "2025",
             "teamSets": 3, "opponentSets": 2,
             "lines": [{"player": "Grace Nelson", "sp": 5, "k": 15, "e": 6, "ta": 40,
                        "a": 1, "sa": 0, "se": 2, "d": 3, "bs": 0, "ba": 3,
                        "re": 0, "bhe": 0}],
             "opponentLines": [$opponentLines],
             "teamStats": {"sp":5,"k":53,"e":25,"ta":151,"a":49,"sa":6,"se":2,"d":56,
                           "bs":1,"ba":29,"re":5,"bhe":0},
             "opponentStats": $opponentStats}
          ]
        }
        """
    )

    @Test
    fun `merge stores opponent lines and both sides of the team totals`() = runTest {
        Seeder.merge(seed(), db.dao())

        val lines = db.dao().opponentStatLinesOnce()
        assertEquals(2, lines.size)
        val mack = lines.single { it.playerName == "Hailee Mack" }
        assertEquals("L", mack.position)
        assertEquals(28, mack.digs)

        val totals = db.dao().matchTeamStatsOnce()
        assertEquals(2, totals.size)
        assertEquals(53, totals.single { !it.opponent }.kills)
        assertEquals(61, totals.single { it.opponent }.kills)
    }

    @Test
    fun `opponent players never reach the Kansas roster or its stat lines`() = runTest {
        Seeder.merge(seed(), db.dao())

        assertEquals(listOf("Grace Nelson"), db.dao().playersOnce().map { it.name })
        assertEquals(1, db.dao().statLinesOnce().size)
    }

    @Test
    fun `stored team totals are used rather than the sum of the player lines`() = runTest {
        Seeder.merge(seed(), db.dao())

        val stored = db.dao().matchTeamStatsOnce().single { it.opponent }
        val summed = aggregate(db.dao().opponentStatLinesOnce())
        // The team is charged a reception error no individual carries, so summing
        // the lines under-reports it. Everything else reconciles.
        assertEquals(6, stored.receptionErrors)
        assertEquals(1, summed.receptionErrors)
    }

    @Test
    fun `a revised box score replaces the previous one instead of accumulating`() = runTest {
        Seeder.merge(seed(), db.dao())
        // Same match, corrected: one player scratched, the other's digs revised.
        Seeder.merge(
            seed(
                opponentLines =
                    """{"player":"Hailee Mack","jerseyNumber":"1","position":"L","sp":5,"k":0,
                        "e":0,"ta":1,"a":5,"sa":0,"se":1,"d":24,"bs":0,"ba":0,"re":1,"bhe":0}""",
                opponentStats =
                    """{"sp":5,"k":60,"e":42,"ta":176,"a":58,"sa":5,"se":7,"d":55,"bs":4,
                        "ba":13,"re":6,"bhe":0}"""
            ),
            db.dao()
        )

        val lines = db.dao().opponentStatLinesOnce()
        assertEquals(1, lines.size)
        assertEquals(24, lines.single().digs)
        assertEquals(60, db.dao().matchTeamStatsOnce().single { it.opponent }.kills)
    }

    @Test
    fun `opponent box score loads for a match whose Kansas lines already exist`() = runTest {
        // The pre-opponent seed shape: results and KU lines, nothing else.
        val withoutOpponent = JSONObject(
            """
            {
              "players": [{"name": "Grace Nelson", "jerseyNumber": "1", "position": "Pin"}],
              "matches": [
                {"date": "2025-08-23", "opponent": "Vanderbilt", "season": "2025",
                 "teamSets": 3, "opponentSets": 2,
                 "lines": [{"player": "Grace Nelson", "sp": 5, "k": 15, "e": 6, "ta": 40,
                            "a": 1, "sa": 0, "se": 2, "d": 3, "bs": 0, "ba": 3,
                            "re": 0, "bhe": 0}]}
              ]
            }
            """
        )
        Seeder.merge(withoutOpponent, db.dao())
        assertTrue(db.dao().opponentStatLinesOnce().isEmpty())

        Seeder.merge(seed(), db.dao())
        assertEquals(2, db.dao().opponentStatLinesOnce().size)
    }

    @Test
    fun `a seed without opponent data leaves what is already stored alone`() = runTest {
        Seeder.merge(seed(), db.dao())

        // An older payload that predates opponent capture must not wipe it.
        Seeder.merge(
            JSONObject(
                """
                {"players": [], "matches": [
                  {"date": "2025-08-23", "opponent": "Vanderbilt", "season": "2025",
                   "teamSets": 3, "opponentSets": 2}]}
                """
            ),
            db.dao()
        )
        assertEquals(2, db.dao().opponentStatLinesOnce().size)
        assertEquals(2, db.dao().matchTeamStatsOnce().size)
    }

    @Test
    fun `opponent rosters merge and replace per team`() = runTest {
        val withRoster = JSONObject(
            """
            {"players": [], "matches": [],
             "opponentRosters": [
               {"team": "Pittsburgh", "players": [
                 {"player": "Maaike Heilig", "jerseyNumber": "2", "position": "MB", "height": "6-4"},
                 {"player": "Izzy Masten", "jerseyNumber": "7", "position": "L/DS", "height": "5-8"}]},
               {"team": "Stanford", "players": [
                 {"player": "Sarah Hickman", "jerseyNumber": "1", "position": "OPP", "height": "6-5"}]}
             ]}
            """
        )
        Seeder.merge(withRoster, db.dao())
        assertEquals(3, db.dao().opponentRosterOnce().size)
        assertEquals("6-4", db.dao().opponentRosterOnce()
            .single { it.playerName == "Maaike Heilig" }.height)

        // A player who leaves the roster has to actually disappear, and the
        // other team's roster must not be touched.
        Seeder.merge(
            JSONObject(
                """
                {"players": [], "matches": [],
                 "opponentRosters": [{"team": "Pittsburgh", "players": [
                   {"player": "Izzy Masten", "jerseyNumber": "7", "position": "L", "height": "5-8"}]}]}
                """
            ),
            db.dao()
        )
        val roster = db.dao().opponentRosterOnce()
        assertEquals(listOf("Izzy Masten", "Sarah Hickman"), roster.map { it.playerName }.sorted())
        assertEquals("L", roster.single { it.team == "Pittsburgh" }.position)
    }

    @Test
    fun `a seed without rosters leaves the stored ones alone`() = runTest {
        Seeder.merge(
            JSONObject(
                """{"players": [], "matches": [], "opponentRosters": [
                   {"team": "Tulsa", "players": [
                     {"player": "Lexi Dahl", "jerseyNumber": "1", "position": "S", "height": "5-7"}]}]}"""
            ),
            db.dao()
        )
        Seeder.merge(JSONObject("""{"players": [], "matches": []}"""), db.dao())
        assertEquals(1, db.dao().opponentRosterOnce().size)
    }

    @Test
    fun `opposing box-score lines carry the height the roster supplied`() = runTest {
        Seeder.merge(seed(), db.dao())
        // seed() has no heights on its opponent lines; update-seed.py adds them
        // from the roster, so a line that carries one must survive the merge.
        val withHeight = seed().apply {
            getJSONArray("matches").getJSONObject(0)
                .getJSONArray("opponentLines").getJSONObject(0)
                .put("height", "5-9")
        }
        Seeder.merge(withHeight, db.dao())
        assertEquals(
            "5-9",
            db.dao().opponentStatLinesOnce().single { it.playerName == "Hailee Mack" }.height
        )
    }

    @Test
    fun `height is stored and a blank height never erases a known one`() = runTest {
        Seeder.merge(seed(), db.dao())
        assertEquals("6-1", db.dao().playersOnce().single().height)

        Seeder.merge(
            JSONObject(
                """{"players": [{"name": "Grace Nelson", "jerseyNumber": "1",
                     "position": "Pin", "height": ""}], "matches": []}"""
            ),
            db.dao()
        )
        assertEquals("6-1", db.dao().playersOnce().single().height)
        assertNotNull(db.dao().playersOnce().single().name)
    }
}
