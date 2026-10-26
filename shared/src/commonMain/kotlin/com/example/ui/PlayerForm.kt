package com.example.ui

import com.example.data.Match
import com.example.data.StatLine
import com.example.stats.VolleyballTotals
import com.example.stats.aggregate
import com.example.stats.formatAverage
import com.example.stats.formatPerSet
import kotlin.math.ceil

/*
 * A player's form - how the last few matches compare with the season - and
 * the career milestones the player is closing on. Plain functions for the tests.
 */

/** How many recent matches "form" means. */
const val FORM_MATCHES = 5

data class FormStat(
    val label: String,
    val season: Double,
    val recent: Double,
    // One value per match this season, oldest first, null where the match
    // gives nothing to measure (no attempts for hitting %).
    val series: List<Pair<String, Double?>>,
    val format: (Double?) -> String
) {
    /** "up" / "down" / "" for a recent run clearly off the season figure. */
    val trend: String
        get() {
            val gap = recent - season
            val threshold = if (label == "Hitting %") 0.030 else maxOf(0.25, season * 0.10)
            return when {
                gap >= threshold -> "up"
                gap <= -threshold -> "down"
                else -> ""
            }
        }
}

private fun perSet(value: Int, sets: Int) = if (sets == 0) 0.0 else value.toDouble() / sets

private val fmtPerSet: (Double?) -> String = { v -> v?.let { formatPerSet(it) } ?: "—" }
private val fmtPct: (Double?) -> String = { v -> v?.let { formatAverage(it) } ?: "—" }

private data class StatDef(
    val label: String,
    // Enough season volume for the rate to mean something for this player.
    val enough: (VolleyballTotals) -> Boolean,
    val value: (VolleyballTotals) -> Double?,
    val format: (Double?) -> String
)

private val FORM_STATS = listOf(
    StatDef("Kills/set", { it.kills >= 10 }, { t -> t.setsPlayed.takeIf { it > 0 }?.let { perSet(t.kills, it) } }, fmtPerSet),
    StatDef("Hitting %", { it.attackAttempts >= 30 }, { t -> t.attackAttempts.takeIf { it > 0 }?.let { t.hittingPercentage } }, fmtPct),
    StatDef("Assists/set", { it.assists >= 30 }, { t -> t.setsPlayed.takeIf { it > 0 }?.let { perSet(t.assists, it) } }, fmtPerSet),
    StatDef("Digs/set", { it.digs >= 10 }, { t -> t.setsPlayed.takeIf { it > 0 }?.let { perSet(t.digs, it) } }, fmtPerSet),
    StatDef("Blocks/set", { it.totalBlocks >= 5 }, { t -> t.setsPlayed.takeIf { it > 0 }?.let { perSet(t.totalBlocks, it) } }, fmtPerSet)
)

/**
 * The season's form lines for one player, for the stats with enough volume.
 * Needs more matches than the form window, or recent and season are the same.
 */
fun playerForm(lines: List<StatLine>, matches: List<Match>, season: String): List<FormStat> {
    val byId = matches.associateBy { it.id }
    val seasonLines = lines
        .filter { byId[it.matchId]?.season == season && it.setsPlayed > 0 }
        .sortedBy { byId[it.matchId]?.date }
    if (seasonLines.size <= FORM_MATCHES) return emptyList()
    val seasonTotals = aggregate(seasonLines)
    val recentTotals = aggregate(seasonLines.takeLast(FORM_MATCHES))
    return FORM_STATS.filter { it.enough(seasonTotals) }.mapNotNull { def ->
        val s = def.value(seasonTotals) ?: return@mapNotNull null
        val r = def.value(recentTotals) ?: return@mapNotNull null
        FormStat(
            label = def.label,
            season = s,
            recent = r,
            series = seasonLines.map { line ->
                (byId[line.matchId]?.opponent ?: "") to def.value(aggregate(listOf(line)))
            },
            format = def.format
        )
    }
}

fun formLine(f: FormStat): String =
    "${f.label}: last $FORM_MATCHES ${f.format(f.recent)} vs season ${f.format(f.season)}" +
        when (f.trend) { "up" -> " ▲"; "down" -> " ▼"; else -> "" }

data class Milestone(val stat: String, val current: Int, val target: Int, val matchesAway: Int?)

private val MILESTONES = listOf<Triple<String, (VolleyballTotals) -> Int, List<Int>>>(
    Triple("kills", { it.kills }, listOf(100, 250, 500, 750, 1000, 1250, 1500, 2000)),
    Triple("digs", { it.digs }, listOf(100, 250, 500, 750, 1000, 1250, 1500, 2000)),
    Triple("assists", { it.assists }, listOf(250, 500, 1000, 1500, 2000, 2500, 3000, 4000)),
    Triple("aces", { it.serviceAces }, listOf(25, 50, 75, 100, 150, 200)),
    Triple("blocks", { it.totalBlocks }, listOf(50, 100, 150, 200, 300, 400, 500))
)

/**
 * Round-number career totals within reach: the next one up for each stat,
 * shown when this season's per-match pace would get there within `within`
 * matches. "Career" is every season held here, which for a transfer or a
 * senior is not the whole career - the explanation under the card says so.
 */
fun milestones(lines: List<StatLine>, matches: List<Match>, season: String, within: Int = 5): List<Milestone> {
    val byId = matches.associateBy { it.id }
    val career = aggregate(lines)
    val seasonLines = lines.filter { byId[it.matchId]?.season == season }
    val seasonTotals = aggregate(seasonLines)
    val played = seasonLines.size
    return MILESTONES.mapNotNull { (stat, get, marks) ->
        val now = get(career)
        val next = marks.firstOrNull { it > now } ?: return@mapNotNull null
        val pace = if (played == 0) 0.0 else get(seasonTotals).toDouble() / played
        val away = if (pace > 0) ceil((next - now) / pace).toInt() else null
        if (away == null || away > within) null else Milestone(stat, now, next, away)
    }
}

fun milestoneLine(m: Milestone): String =
    "${m.target - m.current} ${if (m.target - m.current == 1) m.stat.removeSuffix("s") else m.stat} " +
        "from ${m.target} (${m.current} now)" +
        (m.matchesAway?.let { " · about $it match${if (it == 1) "" else "es"} at this season's pace" } ?: "")
