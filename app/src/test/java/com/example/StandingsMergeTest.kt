package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.ConferenceStanding
import com.example.data.JayhawksDatabase
import com.example.data.Seeder
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StandingsMergeTest {

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

    private fun seed(standings: String, polls: String = "") = JSONObject(
        """
        {"players": [], "matches": [], "standings": [$standings]
         ${if (polls.isNotBlank()) ", \"polls\": [$polls]" else ""}}
        """
    )

    @Test
    fun `standings merge inserts rows with ranks`() = runTest {
        Seeder.merge(
            seed(
                """{"season":"2025","seo":"arizona-st","team":"Arizona St.","confW":15,"confL":1,
                    "overallW":28,"overallL":4,"nationalRank":12,"rpiRank":6}"""
            ),
            db.dao()
        )
        val row = db.dao().standingsOnce().single()
        assertEquals("Arizona St.", row.team)
        assertEquals(15, row.confW)
        assertEquals(12, row.nationalRank)
        assertEquals(6, row.rpiRank)
        assertEquals(15.0 / 16.0, row.confPct, 1e-9)
    }

    @Test
    fun `standings are replaced per season, not accumulated`() = runTest {
        val dao = db.dao()
        Seeder.merge(
            seed("""{"season":"2025","seo":"kansas","team":"Kansas","confW":10,"confL":6,
                     "overallW":24,"overallL":11}"""),
            dao
        )
        // A later sync with an updated record must not leave the old row behind.
        Seeder.merge(
            seed("""{"season":"2025","seo":"kansas","team":"Kansas","confW":11,"confL":6,
                     "overallW":25,"overallL":11}"""),
            dao
        )
        val rows = dao.standingsOnce()
        assertEquals(1, rows.size)
        assertEquals(11, rows.single().confW)
    }

    @Test
    fun `a team leaving the conference disappears instead of lingering`() = runTest {
        val dao = db.dao()
        Seeder.merge(
            seed(
                """{"season":"2026","seo":"kansas","team":"Kansas","confW":1,"confL":0},
                   {"season":"2026","seo":"departed","team":"Departed U","confW":0,"confL":1}"""
            ),
            dao
        )
        assertEquals(2, dao.standingsOnce().size)
        Seeder.merge(
            seed("""{"season":"2026","seo":"kansas","team":"Kansas","confW":2,"confL":0}"""),
            dao
        )
        val rows = dao.standingsOnce()
        assertEquals(1, rows.size)
        assertEquals("Kansas", rows.single().team)
    }

    @Test
    fun `replacing one season leaves other seasons intact`() = runTest {
        val dao = db.dao()
        Seeder.merge(
            seed(
                """{"season":"2025","seo":"kansas","team":"Kansas","confW":10,"confL":6},
                   {"season":"2026","seo":"kansas","team":"Kansas","confW":1,"confL":0}"""
            ),
            dao
        )
        Seeder.merge(
            seed("""{"season":"2026","seo":"kansas","team":"Kansas","confW":3,"confL":0}"""),
            dao
        )
        val bySeason = dao.standingsOnce().associateBy { it.season }
        assertEquals(10, bySeason.getValue("2025").confW)
        assertEquals(3, bySeason.getValue("2026").confW)
    }

    @Test
    fun `poll rows preserve tie labels and first-place votes`() = runTest {
        Seeder.merge(
            seed(
                """{"season":"2025","seo":"kansas","team":"Kansas","confW":10,"confL":6}""",
                """{"season":"2025","name":"AVCA Coaches Poll","updated":"Through Games DEC. 21, 2025",
                    "rows":[
                      {"rank":1,"rankLabel":"1","team":"Texas A&M","record":"29-4","points":"1525",
                       "previous":"6","firstPlaceVotes":61,"big12":false},
                      {"rank":22,"rankLabel":"T-22","team":"Colorado","record":"23-9","points":"232",
                       "previous":"24","firstPlaceVotes":0,"big12":true}]}"""
            ),
            db.dao()
        )
        val entries = db.dao().pollEntriesOnce().sortedBy { it.rank }
        assertEquals(2, entries.size)
        assertEquals(61, entries.first().firstPlaceVotes)
        assertEquals("T-22", entries.last().rankLabel)
        assertTrue(entries.last().big12)
        assertEquals("AVCA Coaches Poll", entries.last().pollName)
    }

    @Test
    fun `a seed without standings still merges and leaves existing rows alone`() = runTest {
        val dao = db.dao()
        dao.insertStandings(
            listOf(ConferenceStanding(season = "2025", seo = "kansas", team = "Kansas", confW = 10))
        )
        // Mirrors an older season-data.json produced before this feature existed:
        // it must merge cleanly rather than throwing or wiping standings.
        Seeder.merge(
            JSONObject(
                """{"players":[{"name":"Ada Alpha"}],
                    "matches":[{"date":"2025-08-29","opponent":"Wisconsin","season":"2025"}]}"""
            ),
            dao
        )
        assertEquals(1, dao.standingsOnce().size)
        assertEquals(10, dao.standingsOnce().single().confW)
        assertEquals(1, dao.matchesOnce().size)
        assertNull(dao.standingsOnce().single().rpiRank)
    }
}
