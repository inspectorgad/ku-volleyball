package com.example

import com.example.data.Match
import com.example.data.MatchTeamStats
import com.example.ui.summarizeOpponents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpponentSummaryTest {

    private val matches = listOf(
        // Kansas won: 3-1. From the opponent's side that is a loss.
        Match(id = 1, date = "2025-09-05", opponent = "Iowa St.", season = "2025",
            teamSets = 3, opponentSets = 1),
        // Kansas lost: 2-3. From the opponent's side that is a win.
        Match(id = 2, date = "2025-10-16", opponent = "Iowa St.", season = "2025",
            teamSets = 2, opponentSets = 3),
        Match(id = 3, date = "2025-09-20", opponent = "Baylor", season = "2025",
            teamSets = 3, opponentSets = 0),
        // Unplayed, and a different season: neither should be counted.
        Match(id = 4, date = "2026-09-01", opponent = "Baylor", season = "2026"),
        Match(id = 5, date = "2026-09-08", opponent = "Houston", season = "2026")
    )

    private val teamStats = listOf(
        MatchTeamStats(matchId = 1, opponent = true, kills = 40, attackErrors = 20,
            attackAttempts = 100),
        MatchTeamStats(matchId = 1, opponent = false, kills = 50, attackErrors = 10,
            attackAttempts = 100),
        MatchTeamStats(matchId = 2, opponent = true, kills = 60, attackErrors = 20,
            attackAttempts = 100),
        MatchTeamStats(matchId = 3, opponent = true, kills = 30, attackErrors = 10,
            attackAttempts = 100)
    )

    @Test
    fun `records are stated from the opponent's side`() {
        val iowaState = summarizeOpponents(matches, teamStats, "2025")
            .single { it.name == "Iowa St." }
        assertEquals(2, iowaState.matchCount)
        assertEquals(1, iowaState.wins)
        assertEquals(1, iowaState.losses)
    }

    @Test
    fun `totals combine every meeting and exclude Kansas' own line`() {
        val iowaState = summarizeOpponents(matches, teamStats, "2025")
            .single { it.name == "Iowa St." }
        assertEquals(100, iowaState.totals.kills)
        assertEquals(40, iowaState.totals.attackErrors)
        // (100 - 40) / 200, not polluted by the Kansas rows for the same matches.
        assertEquals(0.300, iowaState.totals.hittingPercentage, 0.0001)
    }

    @Test
    fun `unplayed matches and other seasons are excluded`() {
        val summaries = summarizeOpponents(matches, teamStats, "2025")
        assertEquals(listOf("Baylor", "Iowa St."), summaries.map { it.name })
        assertTrue(summarizeOpponents(matches, teamStats, "2026").isEmpty())
    }

    @Test
    fun `a null season spans every played match`() {
        val summaries = summarizeOpponents(matches, teamStats, null)
        assertEquals(listOf("Baylor", "Iowa St."), summaries.map { it.name })
    }
}
