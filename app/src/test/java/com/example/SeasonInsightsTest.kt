package com.example

import com.example.data.Match
import com.example.ui.closeSetsLine
import com.example.ui.forecastScorecard
import com.example.ui.parseCommonOpponents
import com.example.ui.parseSets
import com.example.ui.rpiResume
import com.example.ui.resumeLine
import com.example.ui.scorecardLine
import com.example.ui.setPatternLines
import com.example.ui.setPatterns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SeasonInsightsTest {

    private fun m(
        id: Long, opp: String, us: Int, them: Int, sets: String? = null,
        rpi: Int? = null, forecast: Double? = null, season: String = "2026"
    ) = Match(
        id = id, date = "2026-09-%02d".format(id), opponent = opp, season = season,
        teamSets = us, opponentSets = them, setScores = sets, opponentRpi = rpi, forecast = forecast
    )

    @Test
    fun `scorecard grades only matches with a pre-match forecast`() {
        val all = listOf(
            m(1, "Grand Canyon", 3, 0, forecast = 0.78),
            m(2, "Pittsburgh", 1, 3, forecast = 0.40),
            m(3, "Tulsa", 3, 0, forecast = 0.30), // an upset the model missed
            m(4, "Lipscomb", 3, 0)
        )
        val s = forecastScorecard(all, "2026")!!
        assertEquals(3, s.graded)
        assertEquals(2, s.called)
        assertEquals(2, s.actualWins)
        assertEquals(1.48, s.expectedWins, 1e-9)
        // (0.22² + 0.40² + 0.70²) / 3
        assertEquals((0.0484 + 0.16 + 0.49) / 3, s.brier, 1e-9)
        assertEquals("Called 2 of 3 · expected 1.5 wins, won 2 · Brier 0.23 (0.25 = coin flip)", scorecardLine(s))
        assertNull(forecastScorecard(all.takeLast(1), "2026"))
    }

    @Test
    fun `resume bands by opponent RPI`() {
        val all = listOf(
            m(1, "Pittsburgh", 1, 3, rpi = 7),
            m(2, "Stanford", 3, 2, rpi = 24),
            m(3, "Tulsa", 3, 0, rpi = 72),
            m(4, "Ole Miss", 0, 3, rpi = 140),
            m(5, "Lipscomb", 3, 0, rpi = 91),
            m(6, "West Florida", 3, 0)
        )
        val r = rpiResume(all, "2026")!!
        assertEquals("RPI 1-25: 1-1 · 26-50: 0-0 · 51-100: 2-0 · 101+: 0-1", resumeLine(r))
        assertEquals(listOf("Stanford", "Tulsa", "Lipscomb"), r.bestWins.map { it.opponent })
        assertEquals(listOf("Ole Miss", "Pittsburgh"), r.worstLosses.map { it.opponent })
        assertEquals(1, r.unrated)
    }

    @Test
    fun `set patterns`() {
        assertEquals(listOf(25 to 21, 18 to 25), parseSets("25-21, 18-25"))
        val all = listOf(
            m(1, "A", 3, 2, "20-25, 23-25, 25-20, 27-25, 15-12"), // reverse sweep
            m(2, "B", 2, 3, "25-20, 25-22, 20-25, 23-25, 13-15"), // blew 2-0
            m(3, "C", 3, 0, "25-15, 25-17, 25-19")
        )
        val p = setPatterns(all, "2026")!!
        assertEquals(listOf(2 to 1, 2 to 1, 2 to 1, 1 to 1, 1 to 1), p.bySet)
        assertEquals(1 to 1, p.wonFirstRecord)
        assertEquals(1 to 0, p.lostFirstRecord)
        // 23-25, 27-25, 23-25 and 13-15 are the two-point sets; 15-12 is not.
        assertEquals(1 to 3, p.closeSets)
        assertEquals(1, p.reverseSweeps)
        assertEquals(1, p.blownTwoNil)
        assertEquals("Came back from 0-2 1× · lost from 2-0 up 1×", setPatternLines(p).last())
    }

    @Test
    fun `close sets line counts two-point sets and names the latest`() {
        val all = listOf(
            m(1, "Pittsburgh", 1, 3, "17-25, 25-14, 23-25, 20-25"),
            m(4, "Florida St.", 2, 3, "24-26, 25-21, 25-19, 26-28, 13-15"),
            m(8, "Wichita St.", 3, 0, "25-9, 25-21, 25-14")
        )
        assertEquals("Close sets (2 pts) 0-4 · latest 13-15 vs Florida St.", closeSetsLine(all, "2026"))
        assertNull(closeSetsLine(all.takeLast(1), "2026"))
    }

    @Test
    fun `common opponents parse and tolerate junk`() {
        val c = parseCommonOpponents("""[{"team":"Pittsburgh","ku":"L 1-3","them":"L 1-3"}]""")
        assertEquals("Pittsburgh", c.single().team)
        assertEquals(emptyList<Any>(), parseCommonOpponents(""))
    }
}
