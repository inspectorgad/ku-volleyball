package com.example.stats

import com.example.data.VolleyballLine
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

    /** Aces won less serves missed. Negative when a server gives away more than they take. */
    val serveDifferential: Int get() = serviceAces - serviceErrors

    /**
     * Serving efficiency, as net aces per set.
     *
     * Deliberately not the textbook (aces - errors) / attempts: an NCAA
     * volleyball box score publishes aces and errors but never the number of
     * serves attempted, so that denominator does not exist in any source we
     * have. Sets played is the one honest denominator available, and it does
     * the job a rate is for - comparing a server who plays every set against
     * one who plays three.
     */
    val servingEfficiency: Double get() = perSet(serveDifferential.toDouble())

    val killsPerSet: Double get() = perSet(kills.toDouble())
    val assistsPerSet: Double get() = perSet(assists.toDouble())
    val digsPerSet: Double get() = perSet(digs.toDouble())
    val acesPerSet: Double get() = perSet(serviceAces.toDouble())
    val blocksPerSet: Double get() = perSet(blockSolos + blockAssists.toDouble())
    val pointsPerSet: Double get() = perSet(points)

    private fun perSet(value: Double): Double =
        if (setsPlayed == 0) 0.0 else value / setsPlayed
}

/**
 * One match's serving, reduced to what a running total needs. Deliberately not
 * a Room entity: the cumulative arithmetic is worth testing on its own, and it
 * has no business knowing how a match is stored.
 *
 * [setsPlayed] is the length of the match in sets, not a sum over players. A
 * team serves as one unit through a five-set match, so five is the denominator
 * its rate belongs over; summing each player's sets would count the same match
 * six times and make the team look six times better at serving than it is.
 */
data class ServingMatch(
    val date: String,
    val opponent: String,
    val setsPlayed: Int,
    val aces: Int,
    val errors: Int
)

/** A match's serving alongside the season-to-date figures through that match. */
data class ServingProgressPoint(
    val match: ServingMatch,
    val cumulativeAces: Int,
    val cumulativeErrors: Int,
    val cumulativeSets: Int
) {
    val differential: Int get() = match.aces - match.errors
    val cumulativeDifferential: Int get() = cumulativeAces - cumulativeErrors

    /** Season-to-date serving efficiency: net aces per set, through this match. */
    val cumulativeEfficiency: Double
        get() = if (cumulativeSets == 0) 0.0
        else cumulativeDifferential.toDouble() / cumulativeSets
}

/**
 * Walks the season in date order, carrying the running serving totals forward.
 *
 * The point of a cumulative view is that each row answers "where did the season
 * stand after this match", so the input is sorted here rather than trusted to
 * arrive that way - a caller passing matches newest-first would otherwise get a
 * plausible-looking series running backwards.
 */
fun servingProgress(matches: List<ServingMatch>): List<ServingProgressPoint> {
    var aces = 0
    var errors = 0
    var sets = 0
    return matches.sortedBy { it.date }.map { m ->
        aces += m.aces
        errors += m.errors
        sets += m.setsPlayed
        ServingProgressPoint(m, aces, errors, sets)
    }
}

/**
 * Sums a set of stat lines into one totals row. Works for Kansas lines,
 * opposing lines, and stored team totals alike — [matches] is simply the number
 * of rows summed, so it counts matches for per-player or per-opponent rollups.
 */
fun aggregate(lines: Collection<VolleyballLine>): VolleyballTotals = VolleyballTotals(
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
fun summarize(line: VolleyballLine): String {
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
