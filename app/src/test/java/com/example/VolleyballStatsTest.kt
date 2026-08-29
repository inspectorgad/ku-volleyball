package com.example

import com.example.data.StatLine
import com.example.stats.ServingMatch
import com.example.stats.aggregate
import com.example.stats.formatAverage
import com.example.stats.servingProgress
import com.example.stats.formatPerSet
import com.example.stats.summarize
import com.example.ui.SERVING_COLUMNS
import com.example.ui.STAT_COLUMNS
import com.example.ui.STAT_DEFINITIONS
import com.example.ui.STAT_GLOSSARY
import com.example.ui.statValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun `serving efficiency is net aces per set and goes negative`() {
        // A server who misses more than they win reads below zero: that is the
        // whole point of the column, so it must not be clamped or made absolute.
        val costly = aggregate(listOf(line(setsPlayed = 4, serviceAces = 1, serviceErrors = 5)))
        assertEquals(-4, costly.serveDifferential)
        assertEquals(-1.0, costly.servingEfficiency, 1e-9)

        val earning = aggregate(listOf(line(setsPlayed = 4, serviceAces = 6, serviceErrors = 2)))
        assertEquals(4, earning.serveDifferential)
        assertEquals(1.0, earning.servingEfficiency, 1e-9)

        // Same net, half the sets. The rate separates them where the raw
        // differential cannot, which is the reason it is a per-set figure.
        val efficient = aggregate(listOf(line(setsPlayed = 2, serviceAces = 6, serviceErrors = 2)))
        assertEquals(earning.serveDifferential, efficient.serveDifferential)
        assertEquals(2.0, efficient.servingEfficiency, 1e-9)
    }

    @Test
    fun `a player who never served reads zero rather than dividing by zero`() {
        assertEquals(0.0, aggregate(listOf(line(setsPlayed = 3))).servingEfficiency, 0.0)
        assertEquals(0.0, aggregate(emptyList()).servingEfficiency, 0.0)
    }

    @Test
    fun `serving progress carries totals forward match by match`() {
        val season = servingProgress(
            listOf(
                ServingMatch("2026-08-28", "Pittsburgh", setsPlayed = 4, aces = 3, errors = 17),
                ServingMatch("2026-08-30", "Stanford", setsPlayed = 5, aces = 8, errors = 6),
                ServingMatch("2026-09-03", "Lipscomb", setsPlayed = 3, aces = 5, errors = 4)
            )
        )
        assertEquals(3, season.size)

        // Each row is where the season stood once that match was over.
        assertEquals(3, season[0].cumulativeAces)
        assertEquals(-14, season[0].cumulativeDifferential)
        assertEquals(-3.5, season[0].cumulativeEfficiency, 1e-9)

        assertEquals(11, season[1].cumulativeAces)
        assertEquals(23, season[1].cumulativeErrors)
        assertEquals(9, season[1].cumulativeSets)
        assertEquals(-12, season[1].cumulativeDifferential)

        // The single match stands apart from the running figure: Stanford was
        // +2 on its own while the season was still deep underwater.
        assertEquals(2, season[1].differential)

        assertEquals(-11, season[2].cumulativeDifferential)
        assertEquals(12, season[2].cumulativeSets)
    }

    @Test
    fun `serving progress sorts by date rather than trusting the caller`() {
        // A caller handing over newest-first would otherwise get a series that
        // looks plausible and runs backwards.
        val shuffled = servingProgress(
            listOf(
                ServingMatch("2026-09-03", "Lipscomb", setsPlayed = 3, aces = 5, errors = 4),
                ServingMatch("2026-08-28", "Pittsburgh", setsPlayed = 4, aces = 3, errors = 17)
            )
        )
        assertEquals("2026-08-28", shuffled.first().match.date)
        assertEquals(3, shuffled.first().cumulativeAces)
        assertEquals(8, shuffled.last().cumulativeAces)
    }

    @Test
    fun `serving progress of an empty season is empty, not a divide by zero`() {
        assertEquals(emptyList<Any>(), servingProgress(emptyList()))
        val scoreless = servingProgress(
            listOf(ServingMatch("2026-08-28", "Pittsburgh", setsPlayed = 0, aces = 0, errors = 0))
        )
        assertEquals(0.0, scoreless.single().cumulativeEfficiency, 0.0)
    }

    @Test
    fun `every stat column has a matching value`() {
        // STAT_COLUMNS and statValues are positional: if they ever drift, every
        // cell after the gap silently sits under the wrong heading.
        assertEquals(STAT_COLUMNS.size, statValues(aggregate(emptyList())).size)
        assertEquals(
            STAT_COLUMNS.size,
            statValues(aggregate(listOf(line(setsPlayed = 4, kills = 9, serviceErrors = 3)))).size
        )
        assertEquals("SRV", STAT_COLUMNS[STAT_COLUMNS.indexOf("SE") + 1])
    }

    @Test
    fun `every stat column has a definition`() {
        // A heading with no glossary entry renders with no tooltip and gives no
        // hint that it is missing one, so the gap would only ever be found by
        // someone long-pressing it and getting nothing.
        val undefined = (STAT_COLUMNS + SERVING_COLUMNS).distinct()
            .filterNot { STAT_GLOSSARY.containsKey(it) }
        assertEquals(emptyList<String>(), undefined)
    }

    @Test
    fun `the glossary card lists every column the tables draw`() {
        // The card is built from STAT_DEFINITIONS while tooltips go through the
        // derived map, so an entry reachable only as an alias would explain itself
        // on long-press and then be missing from the list at the bottom of the
        // Serving screen - the one place someone goes to read them all.
        val listed = STAT_DEFINITIONS.map { it.term }.toSet()
        val drawn = (STAT_COLUMNS + SERVING_COLUMNS).distinct()
        assertEquals(emptyList<String>(), drawn.filterNot { it in listed })
    }

    @Test
    fun `leaderboard titles resolve to the same sentence as their abbreviation`() {
        // Spelled out here rather than read off LeadersScreen, so renaming a card
        // there without adding the alias fails instead of quietly losing a tooltip.
        val longhand = mapOf(
            "Kills" to "K", "Assists" to "A", "Service Aces" to "SA",
            "Service Errors" to "SE", "Digs" to "D", "Total Blocks" to "BLK",
            "Points" to "PTS", "Hitting %" to "PCT"
        )
        longhand.forEach { (title, abbreviation) ->
            assertEquals(
                "$title should mean exactly what $abbreviation means",
                STAT_GLOSSARY[abbreviation],
                STAT_GLOSSARY[title]
            )
        }
    }

    @Test
    fun `no heading is defined twice`() {
        // Aliases and terms share one namespace in the derived map, so a collision
        // would silently drop whichever was built first.
        val headings = STAT_DEFINITIONS.flatMap { listOf(it.term) + it.aliases }
        assertEquals(headings.size, headings.distinct().size)
        assertEquals(headings.size, STAT_GLOSSARY.size)
    }

    @Test
    fun `definitions are sentences that say more than the abbreviation does`() {
        STAT_GLOSSARY.forEach { (term, explanation) ->
            assertTrue("$term has an empty definition", explanation.isNotBlank())
            assertTrue(
                "$term's definition should end in a full stop: $explanation",
                explanation.trimEnd().endsWith(".")
            )
            // Deliberately not a length floor. "Kills per set." is a complete
            // definition of K/S and a character count would fail it, which says
            // more about the measure than about the sentence. What actually
            // makes an entry useless is restating the heading, so test that.
            assertTrue(
                "$term's definition just repeats the heading: $explanation",
                explanation.filter { it.isLetterOrDigit() }.lowercase() !=
                    term.filter { it.isLetterOrDigit() }.lowercase()
            )
        }
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
