package com.example

import com.example.data.Match
import com.example.data.Player
import com.example.data.StatLine
import com.example.ui.drawResultCard
import com.example.ui.resultCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ResultImageTest {

    private val players = listOf(
        Player(id = 1, name = "Taylor Stanley"), Player(id = 2, name = "Grace Nelson"),
        Player(id = 3, name = "Reese Messer"), Player(id = 4, name = "Aisha Aiono")
    )
    private val utah = Match(
        id = 10, date = "2026-10-01", opponent = "Utah", season = "2026", teamSets = 3, opponentSets = 0,
        setScores = "32-30, 25-20, 26-24", home = true, venue = "Horejsi Family Volleyball Arena", opponentRank = 23
    )
    private val lines = listOf(
        StatLine(playerId = 1, matchId = 10, kills = 13, attackErrors = 8, attackAttempts = 38),
        StatLine(playerId = 2, matchId = 10, digs = 12),
        StatLine(playerId = 3, matchId = 10, assists = 36),
        StatLine(playerId = 4, matchId = 10, blockSolos = 1, blockAssists = 5)
    )

    @Test
    fun `the card carries the score, sets, leaders and the record after the match`() {
        val earlier = Match(id = 9, date = "2026-09-27", opponent = "Texas Tech", season = "2026", teamSets = 3, opponentSets = 0)
        val later = Match(id = 11, date = "2026-10-02", opponent = "BYU", season = "2026", teamSets = 0, opponentSets = 3)
        val card = resultCard(utah, players, lines, listOf(earlier, utah, later))!!
        assertEquals("KANSAS WINS 3-0", card.headline)
        assertEquals("32-30, 25-20, 26-24", card.sets)
        assertEquals(
            listOf("Taylor Stanley: 13 kills on .132", "Grace Nelson: 12 digs", "Reese Messer: 36 assists", "Aisha Aiono: 3.5 blocks"),
            card.performers
        )
        assertEquals("Record 2-0", card.record)
        assertEquals("Thursday, October 1, 2026 · Horejsi Family Volleyball Arena", card.dateLine)
        val bmp = drawResultCard(card)
        assertEquals(1080, bmp.width)
        assertEquals(1350, bmp.height)
    }

    @Test
    fun `no card before the match is played`() {
        assertNull(resultCard(utah.copy(teamSets = null, opponentSets = null), players, lines, emptyList()))
    }
}
