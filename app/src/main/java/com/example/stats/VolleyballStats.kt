package com.example.stats

import com.example.data.StatLine
import java.util.Locale

/**
 * Aggregated volleyball totals for any collection of stat lines
 * (one player's match, a season, a career, or the whole team).
 */
data class VolleyballTotals(
    val matches: Int = 0,
    val setsPlayed: Int = 0,
    val kills: Int = 0,
    val attackErrors: Int = 0,
    val attackAttempts: Int = 0,
    val assists: Int = 0,
    val serviceAces: Int = 0,
    val serviceErrors: Int = 0,
    val digs: Int = 0,
    val blockSolos: Int = 0,
    val blockAssists: Int = 0,
    val receptionErrors: Int = 0,
    val ballHandlingErrors: Int = 0
) {
    val totalBlocks: Int get() = blockSolos + blockAssists

    // NCAA scoring: kills + aces + solo blocks count 1, block assists 0.5.
    val points: Double get() = kills + serviceAces + blockSolos + 0.5 * blockAssists

    // Hitting percentage can legitimately be negative (more errors than kills).
    val hittingPercentage: Double
        get() = if (attackAttempts == 0) 0.0
        else (kills - attackErrors).toDouble() / attackAttempts

    val killsPerSet: Double get() = perSet(kills.toDouble())
    val assistsPerSet: Double get() = perSet(assists.toDouble())
    val digsPerSet: Double get() = perSet(digs.toDouble())
    val acesPerSet: Double get() = perSet(serviceAces.toDouble())
    val blocksPerSet: Double get() = perSet(blockSolos + blockAssists.toDouble())
    val pointsPerSet: Double get() = perSet(points)

    private fun perSet(value: Double): Double =
        if (setsPlayed == 0) 0.0 else value / setsPlayed
}

/** Sums a set of stat lines into one totals row. */
fun aggregate(lines: Collection<StatLine>): VolleyballTotals = VolleyballTotals(
    matches = lines.size,
    setsPlayed = lines.sumOf { it.setsPlayed },
    kills = lines.sumOf { it.kills },
    attackErrors = lines.sumOf { it.attackErrors },
    attackAttempts = lines.sumOf { it.attackAttempts },
    assists = lines.sumOf { it.assists },
    serviceAces = lines.sumOf { it.serviceAces },
    serviceErrors = lines.sumOf { it.serviceErrors },
    digs = lines.sumOf { it.digs },
    blockSolos = lines.sumOf { it.blockSolos },
    blockAssists = lines.sumOf { it.blockAssists },
    receptionErrors = lines.sumOf { it.receptionErrors },
    ballHandlingErrors = lines.sumOf { it.ballHandlingErrors }
)

/** Formats hitting percentage volleyball-style: .314, -.050, 1.000 */
fun formatAverage(value: Double): String {
    val formatted = String.format(Locale.US, "%.3f", value)
    return formatted
        .replace("0.", ".")
        .let { if (it == "-.000") ".000" else it }
}

/** Formats a per-set rate: 3.71, 0.35 */
fun formatPerSet(value: Double): String = String.format(Locale.US, "%.2f", value)

/** Short human summary of a single match line, e.g. "15 K (.250), 10 D, 2 SA, 5 BLK". */
fun summarize(line: StatLine): String {
    val parts = mutableListOf<String>()
    if (line.kills > 0 || line.attackAttempts > 0) {
        val pct = if (line.attackAttempts == 0) 0.0
        else (line.kills - line.attackErrors).toDouble() / line.attackAttempts
        parts.add("${line.kills} K (${formatAverage(pct)})")
    }
    if (line.assists > 0) parts.add("${line.assists} A")
    if (line.digs > 0) parts.add("${line.digs} D")
    if (line.serviceAces > 0) parts.add("${line.serviceAces} SA")
    val blocks = line.blockSolos + line.blockAssists
    if (blocks > 0) parts.add("$blocks BLK")
    if (parts.isEmpty()) {
        return if (line.setsPlayed > 0) "${line.setsPlayed} sets played" else "No stats"
    }
    return parts.joinToString(", ")
}
