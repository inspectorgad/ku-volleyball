package com.example

import com.example.data.ConferenceStanding
import com.example.data.Match
import com.example.data.StatLine
import com.example.ui.MatchScope
import com.example.ui.big12BySeason
import com.example.ui.inScope
import com.example.ui.playerSplitLine
import com.example.ui.teamSplitLine
import com.example.ui.venueSplits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SplitsTest {

    private val standings = listOf(
        ConferenceStanding(season = "2026", seo = "houston", team = "Houston"),
        ConferenceStanding(season = "2026", seo = "kansas-st", team = "Kansas St.")
    )

    @Test
    fun `scope follows that season's standings`() {
        val b12 = big12BySeason(standings)
        val ksu = Match(id = 1, date = "2026-10-15", opponent = "Kansas State", season = "2026")
        val tulsa = Match(id = 2, date = "2026-09-13", opponent = "Tulsa", season = "2026")
        val oldKsu = Match(id = 3, date = "2025-10-15", opponent = "Kansas St.", season = "2025")
        assertTrue(inScope(ksu, MatchScope.Big12, b12))
        assertFalse(inScope(tulsa, MatchScope.Big12, b12))
        assertTrue(inScope(tulsa, MatchScope.NonConference, b12))
        // No 2025 standings held here: not guessed to be conference.
        assertFalse(inScope(oldKsu, MatchScope.Big12, b12))
        assertTrue(inScope(oldKsu, MatchScope.All, b12))
    }

    @Test
    fun `team splits use the matches' sets, not the players' summed sets`() {
        val matches = listOf(
            Match(id = 1, date = "2026-09-01", opponent = "A", season = "2026", teamSets = 3, opponentSets = 0, home = true),
            Match(id = 2, date = "2026-09-02", opponent = "B", season = "2026", teamSets = 1, opponentSets = 3, home = false),
            Match(id = 3, date = "2026-09-03", opponent = "C", season = "2026", teamSets = 3, opponentSets = 1, neutral = true, home = true)
        )
        // Two players in the home match: 3 sets each, 30 kills between them.
        val lines = listOf(
            StatLine(playerId = 1, matchId = 1, setsPlayed = 3, kills = 20, attackAttempts = 50, attackErrors = 5, digs = 9),
            StatLine(playerId = 2, matchId = 1, setsPlayed = 3, kills = 10, attackAttempts = 30, attackErrors = 3, digs = 6),
            StatLine(playerId = 1, matchId = 2, setsPlayed = 4, kills = 12, attackAttempts = 40, attackErrors = 8, digs = 8)
        )
        val team = venueSplits(matches, lines, { (it as StatLine).matchId }, team = true)
        assertEquals(listOf("Home", "Away", "Neutral"), team.map { it.label })
        // A neutral site is not counted as home even with home = true.
        assertEquals(1 to 0, team[0].wins to team[0].losses)
        assertEquals("Home 1-0 · .275 hitting · 10.00 K/S · 5.00 D/S", teamSplitLine(team[0]))
        assertEquals("Away 0-1 · .100 hitting · 3.00 K/S · 2.00 D/S", teamSplitLine(team[1]))

        val mine = venueSplits(matches.take(2), lines.filter { it.playerId == 1L }, { (it as StatLine).matchId })
        assertEquals("Home (1 match): 6.67 K/S · .300 hitting · 3.00 D/S", playerSplitLine(mine[0]))
    }
}
