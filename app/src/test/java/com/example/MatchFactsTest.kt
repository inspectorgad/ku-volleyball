package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.JayhawksDatabase
import com.example.data.Match
import com.example.data.Seeder
import com.example.ui.kickoffLine
import com.example.ui.lastMeeting
import com.example.ui.meetingLabel
import com.example.ui.outlookLine
import com.example.ui.pollMovement
import com.example.ui.rankPath
import com.example.ui.rankedOpponent
import com.example.ui.rankedSplit
import com.example.ui.seasonOutlook
import com.example.ui.splitSchedule
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MatchFactsTest {

    private fun m(
        id: Long, date: String, opp: String, us: Int? = null, them: Int? = null,
        season: String = "2026", p: Double? = null, src: String? = null,
        oppRank: Int? = null, kuRank: Int? = null, home: Boolean? = true
    ) = Match(
        id = id, date = date, opponent = opp, season = season, teamSets = us, opponentSets = them,
        home = home, winProbability = p, ratingSource = src, opponentRank = oppRank, kuRank = kuRank
    )

    @Test
    fun `ranked opponent prefers the rank then the seed`() {
        assertEquals("#4 Pittsburgh", rankedOpponent(m(1, "2026-09-01", "Pittsburgh", oppRank = 4)))
        assertEquals("(1) Nebraska", rankedOpponent(m(1, "2025-12-01", "Nebraska").copy(opponentSeed = 1)))
        assertEquals("Tulsa", rankedOpponent(m(1, "2026-09-01", "Tulsa")))
    }

    @Test
    fun `kickoff line reads in Central with TV`() {
        val match = m(1, "2026-09-25", "Houston").copy(time = "18:00", tv = "ESPN+")
        assertEquals("Fri Sep 25 · 6:00 PM CT · ESPN+", kickoffLine(match))
        assertEquals("Sat Oct 31 · Time TBA", kickoffLine(m(2, "2026-10-31", "Utah")))
    }

    @Test
    fun `upcoming come first soonest first then results newest first`() {
        val all = listOf(
            m(1, "2026-09-01", "A", 3, 0), m(2, "2026-09-20", "B", 1, 3),
            m(3, "2026-10-01", "C"), m(4, "2026-09-25", "D"), m(5, "2026-09-10", "E")
        )
        val (upcoming, results) = splitSchedule(all, "2026-09-23")
        assertEquals(listOf(4L, 3L), upcoming.map { it.id })
        // The unplayed past date sits with the results.
        assertEquals(listOf(2L, 5L, 1L), results.map { it.id })
    }

    @Test
    fun `last meeting spans seasons and spellings`() {
        val all = listOf(
            m(1, "2025-11-19", "Utah", 0, 3, season = "2025"),
            m(2, "2025-09-10", "Utah", 3, 1, season = "2025"),
            m(3, "2026-10-01", "Utah", p = 0.77),
            m(4, "2025-10-01", "Kansas St.", 3, 2, season = "2025"),
            m(5, "2026-10-10", "Kansas State")
        )
        val last = lastMeeting(all[2], all)!!
        assertEquals(1L, last.id)
        assertEquals("L 0-3 (Nov '25)", meetingLabel(last))
        assertEquals(4L, lastMeeting(all[4], all)!!.id)
        assertNull(lastMeeting(m(9, "2026-10-01", "Houston"), all))
    }

    @Test
    fun `ranked split and rank path`() {
        val all = listOf(
            m(1, "2026-08-28", "A", 1, 3, oppRank = 2, kuRank = 15),
            m(2, "2026-08-30", "B", 3, 0, kuRank = 15),
            m(3, "2026-09-05", "C", 3, 1, kuRank = 16),
            m(4, "2026-09-12", "D", 0, 3, oppRank = 4, kuRank = 22),
            m(5, "2025-09-12", "E", 3, 0, season = "2025", oppRank = 1)
        )
        val split = rankedSplit(all, "2026")
        assertEquals(listOf(0, 2, 2, 0), listOf(split.rankedW, split.rankedL, split.unrankedW, split.unrankedL))
        assertEquals(listOf("15", "16", "22", "19"), rankPath(all, "2026", 19))
        assertEquals(listOf("15", "16", "22"), rankPath(all, "2026", 22))
        assertEquals(listOf("15", "16", "22", "NR"), rankPath(all, "2026", null))
    }

    @Test
    fun `outlook sums the forecasts and counts preseason ratings`() {
        val all = listOf(
            m(1, "2026-09-01", "Tulsa", 3, 0),
            m(2, "2026-09-02", "Pittsburgh", 1, 3),
            m(3, "2026-09-25", "Houston", p = 0.89, src = "preseason"),
            m(4, "2026-10-01", "Arizona St.", p = 0.26, src = "poll", home = false),
            m(5, "2026-10-05", "Utah", p = 0.6, src = "preseason")
        )
        val o = seasonOutlook(all, "2026", setOf("houston", "arizona st", "utah"))!!
        // 1 win + 1.75 expected = 2.75 -> ~3-2; conference 1.75 -> ~2-1.
        assertEquals(3, o.wins)
        assertEquals(2, o.losses)
        assertEquals(2, o.confWins)
        assertEquals(1, o.confLosses)
        assertEquals(
            "Projected ~3-2 (about 2-1 Big 12) · toughest: at Arizona St. ~26% · " +
                "easiest: vs Houston ~89% · 2 of 3 forecasts use preseason ratings",
            outlookLine(o)
        )
        assertNull(seasonOutlook(all.take(2), "2026", emptySet()))
    }

    @Test
    fun `poll movement`() {
        assertEquals("▲3", pollMovement(19, "22"))
        assertEquals("▼4", pollMovement(21, "17"))
        assertEquals("", pollMovement(1, "1"))
        assertEquals("NEW", pollMovement(25, "NR"))
        assertEquals("", pollMovement(5, ""))
    }

    @Test
    fun `sync writes ranks times and tv and always wins`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, JayhawksDatabase::class.java)
            .allowMainThreadQueries().build()
        fun seed(time: String, tv: String) = JSONObject(
            """{"players": [], "matches": [
              {"date":"2026-09-04","opponent":"Florida State","season":"2026",
               "teamSets":2,"opponentSets":3,"kuRank":16},
              {"date":"2026-09-25","opponent":"Houston","season":"2026","home":true,
               "time":"$time","tv":"$tv","winProbability":0.88,"ratingSource":"preseason"}]}"""
        )
        Seeder.merge(seed("18:00", "ESPN+"), db.dao())
        Seeder.merge(seed("19:00", "Big 12 Now"), db.dao())
        val byOpp = db.dao().matchesOnce().associateBy { it.opponent }
        assertEquals(16, byOpp["Florida State"]!!.kuRank)
        assertNull(byOpp["Florida State"]!!.opponentRank)
        assertEquals("19:00", byOpp["Houston"]!!.time)
        assertEquals("Big 12 Now", byOpp["Houston"]!!.tv)
        assertEquals("preseason", byOpp["Houston"]!!.ratingSource)
        db.close()
    }
}
