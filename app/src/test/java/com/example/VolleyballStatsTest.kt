package com.example

import com.example.data.StatLine
import com.example.stats.aggregate
import com.example.stats.formatAverage
import com.example.stats.formatPerSet
import com.example.stats.summarize
import org.junit.Assert.assertEquals
import org.junit.Test

class VolleyballStatsTest {

    private fun line(
        setsPlayed: Int = 0,
        kills: Int = 0,
        attackErrors: Int = 0,
        attackAttempts: Int = 0,
        assists: Int = 0,
        serviceAces: Int = 0,
        serviceErrors: Int = 0,
        digs: Int = 0,
        blockSolos: Int = 0,
        blockAssists: Int = 0,
        receptionErrors: Int = 0,
        ballHandlingErrors: Int = 0
    ) = StatLine(
        playerId = 1, matchId = 1,
        setsPlayed = setsPlayed, kills = kills, attackErrors = attackErrors,
        attackAttempts = attackAttempts, assists = assists, serviceAces = serviceAces,
        serviceErrors = serviceErrors, digs = digs, blockSolos = blockSolos,
        blockAssists = blockAssists, receptionErrors = receptionErrors,
        ballHandlingErrors = ballHandlingErrors
    )

    @Test
    fun `empty aggregation is all zeros and safe to divide`() {
        val totals = aggregate(emptyList())
        assertEquals(0, totals.matches)
        assertEquals(0.0, totals.hittingPercentage, 0.0)
        assertEquals(0.0, totals.killsPerSet, 0.0)
        assertEquals(0.0, totals.points, 0.0)
    }

    @Test
    fun `aggregation sums counting stats across matches`() {
        val totals = aggregate(
            listOf(
                line(setsPlayed = 5, kills = 15, attackErrors = 6, attackAttempts = 36, digs = 3, blockAssists = 3),
                line(setsPlayed = 4, kills = 10, attackErrors = 2, attackAttempts = 24, digs = 5, serviceAces = 2),
                line(setsPlayed = 3, kills = 5, attackErrors = 2, attackAttempts = 20, digs = 4, blockSolos = 1)
            )
        )
        assertEquals(3, totals.matches)
        assertEquals(12, totals.setsPlayed)
        assertEquals(30, totals.kills)
        assertEquals(10, totals.attackErrors)
        assertEquals(80, totals.attackAttempts)
        assertEquals(12, totals.digs)
        assertEquals(2, totals.serviceAces)
        assertEquals(1, totals.blockSolos)
        assertEquals(3, totals.blockAssists)
        assertEquals(4, totals.totalBlocks)
    }

    @Test
    fun `hitting percentage is kills minus errors over attempts`() {
        val totals = aggregate(listOf(line(kills = 15, attackErrors = 6, attackAttempts = 36)))
        assertEquals(9.0 / 36.0, totals.hittingPercentage, 1e-9)
        assertEquals(".250", formatAverage(totals.hittingPercentage))
    }

    @Test
    fun `hitting percentage can go negative`() {
        val totals = aggregate(listOf(line(kills = 1, attackErrors = 3, attackAttempts = 8)))
        assertEquals(-0.25, totals.hittingPercentage, 1e-9)
        assertEquals("-.250", formatAverage(totals.hittingPercentage))
    }

    @Test
    fun `points count block assists as half`() {
        // 10 K + 2 SA + 1 BS + 3 BA -> 10 + 2 + 1 + 1.5 = 14.5
        val totals = aggregate(
            listOf(line(kills = 10, serviceAces = 2, blockSolos = 1, blockAssists = 3))
        )
        assertEquals(14.5, totals.points, 1e-9)
    }

    @Test
    fun `per-set rates divide by sets played`() {
        val totals = aggregate(
            listOf(line(setsPlayed = 4, kills = 10, digs = 8, assists = 44, serviceAces = 2))
        )
        assertEquals(2.5, totals.killsPerSet, 1e-9)
        assertEquals(2.0, totals.digsPerSet, 1e-9)
        assertEquals(11.0, totals.assistsPerSet, 1e-9)
        assertEquals(0.5, totals.acesPerSet, 1e-9)
        assertEquals("2.50", formatPerSet(totals.killsPerSet))
    }

    @Test
    fun `formatAverage uses volleyball notation`() {
        assertEquals(".000", formatAverage(0.0))
        assertEquals(".333", formatAverage(1.0 / 3.0))
        assertEquals(".500", formatAverage(0.5))
        assertEquals("1.000", formatAverage(1.0))
        assertEquals("-.050", formatAverage(-0.05))
    }

    @Test
    fun `summarize builds a readable match line`() {
        val summary = summarize(
            line(setsPlayed = 5, kills = 15, attackErrors = 6, attackAttempts = 36,
                digs = 10, serviceAces = 2, blockSolos = 1, blockAssists = 4)
        )
        assertEquals("15 K (.250), 10 D, 2 SA, 5 BLK", summary)
    }

    @Test
    fun `summarize handles bench and defensive-only lines`() {
        assertEquals("No stats", summarize(line()))
        assertEquals("3 sets played", summarize(line(setsPlayed = 3)))
        assertEquals("7 D", summarize(line(setsPlayed = 4, digs = 7)))
    }
}
