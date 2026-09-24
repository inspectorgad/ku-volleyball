package com.example

import com.example.data.Match
import com.example.data.StatLine
import com.example.ui.formLine
import com.example.ui.milestoneLine
import com.example.ui.milestones
import com.example.ui.playerForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlayerFormTest {

    private val matches = (1L..8L).map {
        Match(id = it, date = "2026-09-%02d".format(it), opponent = "Team $it", season = "2026",
            teamSets = 3, opponentSets = 0)
    } + Match(id = 99, date = "2025-10-01", opponent = "Old", season = "2025", teamSets = 3, opponentSets = 1)

    // Three quiet matches, then five good ones: 6 kills a match, then 12.
    private val lines = (1L..8L).map {
        StatLine(playerId = 1, matchId = it, setsPlayed = 3,
            kills = if (it <= 3) 6 else 12, attackErrors = 2, attackAttempts = 25, digs = 3)
    } + StatLine(playerId = 1, matchId = 99, setsPlayed = 4, kills = 900, attackAttempts = 2000, blockSolos = 97)

    @Test
    fun `form compares the last five with the season`() {
        val form = playerForm(lines, matches, "2026")
        val kills = form.single { it.label == "Kills/set" }
        assertEquals(78.0 / 24, kills.season, 1e-9)  // 3x6 + 5x12 kills over 24 sets
        assertEquals(4.0, kills.recent, 1e-9)
        assertEquals("up", kills.trend)
        assertEquals("Kills/set: last 5 4.00 vs season 3.25 ▲", formLine(kills))
        assertEquals(8, kills.series.size)
        // 24 digs is enough for digs/set; no assists or blocks this season.
        assertEquals(listOf("Kills/set", "Hitting %", "Digs/set"), form.map { it.label })
    }

    @Test
    fun `no form until there are more matches than the window`() {
        assertTrue(playerForm(lines.take(5), matches, "2026").isEmpty())
    }

    @Test
    fun `milestones within reach at this season's pace`() {
        // 900 + 78 = 978 career kills; 9.75 a match this season -> 1000 in 3.
        val m = milestones(lines, matches, "2026")
        val kills = m.single { it.stat == "kills" }
        assertEquals(978, kills.current)
        assertEquals(1000, kills.target)
        assertEquals(3, kills.matchesAway)
        assertEquals("22 kills from 1000 (978 now) · about 3 matches at this season's pace", milestoneLine(kills))
        // 97 career blocks but none this season: no pace, so not "in reach".
        assertTrue(m.none { it.stat == "blocks" })
        assertEquals("1 block from 100 (99 now) · about 1 match at this season's pace",
            milestoneLine(com.example.ui.Milestone("blocks", 99, 100, 1)))
    }
}
