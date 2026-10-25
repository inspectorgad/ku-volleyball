package com.example.ui

import com.example.data.Match
import com.example.data.Player
import com.example.data.StatLine
import com.example.stats.formatAverage
import com.example.text.reformat

/**
 * A match result as a picture to send or post: score, set scores, the top
 * performers and the record after the match, on Kansas blue. The same card the
 * dashboard draws (renderResultImage in docs/index.html), from the same lines.
 */
data class ResultCard(
    val headline: String,
    val opponent: String,
    val sets: String,
    val dateLine: String,
    val performers: List<String>,
    val record: String
)

fun resultCard(match: Match, players: List<Player>, statLines: List<StatLine>, matches: List<Match>): ResultCard? {
    if (!match.played) return null
    val us = match.teamSets ?: 0
    val them = match.opponentSets ?: 0
    val names = players.associate { it.id to it.name }
    val lines = statLines.filter { it.matchId == match.id }
    fun name(l: StatLine) = names[l.playerId] ?: "?"
    val performers = buildList {
        lines.maxByOrNull { it.kills }?.takeIf { it.kills > 0 }?.let { l ->
            val pct = if (l.attackAttempts > 0)
                " on ${formatAverage((l.kills - l.attackErrors).toDouble() / l.attackAttempts)}" else ""
            add("${name(l)}: ${l.kills} kills$pct")
        }
        lines.maxByOrNull { it.digs }?.takeIf { it.digs > 0 }?.let { add("${name(it)}: ${it.digs} digs") }
        lines.maxByOrNull { it.assists }?.takeIf { it.assists >= 10 }?.let { add("${name(it)}: ${it.assists} assists") }
        lines.maxByOrNull { it.blockSolos * 2 + it.blockAssists }
            ?.takeIf { it.blockSolos + it.blockAssists >= 3 }
            ?.let {
                val blk = it.blockSolos + it.blockAssists / 2.0
                add("${name(it)}: ${if (blk % 1.0 == 0.0) blk.toInt().toString() else blk.toString()} blocks")
            }
    }
    val upTo = matches.filter {
        it.season == match.season && it.played && it.date <= match.date && !it.opponent.contains("(Exh", true)
    }
    val w = upTo.count { (it.teamSets ?: 0) > (it.opponentSets ?: 0) }
    return ResultCard(
        headline = if (us > them) "KANSAS WINS $us-$them" else "KANSAS FALLS $us-$them",
        opponent = "${match.versus} ${rankedOpponent(match)}",
        sets = match.setScores.orEmpty(),
        dateLine = listOf(reformat(match.date, "yyyy-MM-dd", "EEEE, MMMM d, yyyy") ?: match.date, match.venue)
            .filter { it.isNotBlank() }.joinToString(" · "),
        performers = performers,
        record = "Record ${w}-${upTo.size - w}"
    )
}
