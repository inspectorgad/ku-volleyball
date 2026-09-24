package com.example.ui

import com.example.data.ConferenceStanding
import com.example.data.Match
import com.example.data.VolleyballLine
import com.example.data.normTeam
import com.example.stats.VolleyballTotals
import com.example.stats.aggregate
import com.example.stats.formatAverage
import com.example.stats.formatPerSet

/*
 * Two ways to cut a season: conference against non-conference, and home
 * against away against neutral. Plain functions for the tests.
 */

enum class MatchScope(val label: String) {
    All("All matches"), Big12("Big 12"), NonConference("Non-conference")
}

/** Big 12 membership by season, from that season's standings. */
fun big12BySeason(standings: List<ConferenceStanding>): Map<String, Set<String>> =
    standings.groupBy { it.season }.mapValues { (_, rows) -> rows.map { normTeam(it.team) }.toSet() }

/**
 * Whether a match belongs to the scope. A season with no standings to judge
 * by counts every match as non-conference rather than guessing.
 */
fun inScope(match: Match, scope: MatchScope, big12: Map<String, Set<String>>): Boolean {
    val conference = normTeam(match.opponent) in big12[match.season].orEmpty()
    return when (scope) {
        MatchScope.All -> true
        MatchScope.Big12 -> conference
        MatchScope.NonConference -> !conference
    }
}

data class VenueSplit(val label: String, val wins: Int, val losses: Int, val totals: VolleyballTotals)

/**
 * Record and production at home, away and at neutral sites, over played
 * matches. A match with no location on record (a hand-added one) is left out.
 */
fun venueSplits(
    matches: List<Match>,
    lines: List<VolleyballLine>,
    matchIdOf: (VolleyballLine) -> Long,
    // True when `lines` are every player's lines for the team. Summed player
    // lines count each player's sets, so the team's per-set rates have to be
    // taken over the sets the matches actually had.
    team: Boolean = false
): List<VenueSplit> {
    val played = matches.filter { it.played }
    val venues = listOf<Pair<String, (Match) -> Boolean>>(
        "Home" to { it.home == true && !it.neutral },
        "Away" to { it.home == false && !it.neutral },
        "Neutral" to { it.neutral }
    )
    return venues.mapNotNull { (label, test) ->
        val here = played.filter(test)
        if (here.isEmpty()) return@mapNotNull null
        val ids = here.map { it.id }.toSet()
        VenueSplit(
            label = label,
            wins = here.count { (it.teamSets ?: 0) > (it.opponentSets ?: 0) },
            losses = here.count { (it.teamSets ?: 0) < (it.opponentSets ?: 0) },
            totals = aggregate(lines.filter { matchIdOf(it) in ids }).let { t ->
                if (team) t.copy(setsPlayed = here.sumOf { (it.teamSets ?: 0) + (it.opponentSets ?: 0) }) else t
            }
        )
    }
}

/** "Home 6-0 · .310 hitting · 14.10 K/S · 12.50 D/S" for the team. */
fun teamSplitLine(s: VenueSplit): String =
    "${s.label} ${s.wins}-${s.losses} · ${formatAverage(s.totals.hittingPercentage)} hitting · " +
        "${formatPerSet(s.totals.killsPerSet)} K/S · ${formatPerSet(s.totals.digsPerSet)} D/S"

/** "Home (6 matches): 4.90 K/S · .371 · 1.20 D/S" for one player. */
fun playerSplitLine(s: VenueSplit): String {
    val t = s.totals
    val parts = mutableListOf<String>()
    if (t.kills > 0) parts += "${formatPerSet(t.killsPerSet)} K/S"
    if (t.attackAttempts > 0) parts += "${formatAverage(t.hittingPercentage)} hitting"
    if (t.assists >= 10) parts += "${formatPerSet(t.assists.toDouble() / maxOf(1, t.setsPlayed))} A/S"
    if (t.digs > 0) parts += "${formatPerSet(t.digsPerSet)} D/S"
    return "${s.label} (${t.matches} ${if (t.matches == 1) "match" else "matches"}): " +
        parts.ifEmpty { listOf("no attacking or defensive stats") }.joinToString(" · ")
}
