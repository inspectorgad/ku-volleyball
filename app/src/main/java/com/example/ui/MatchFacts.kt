package com.example.ui

import com.example.data.Match
import com.example.data.normTeam
import com.example.data.sameTeam
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/*
 * Plain functions behind the schedule screens, kept out of the composables so
 * the unit tests can pin them down.
 */

val Match.played: Boolean get() = teamSets != null && opponentSets != null

/**
 * The opponent as the poll stood when the match was played, "#4 Pittsburgh",
 * or its NCAA tournament seed, "(1) Nebraska", for a tournament match.
 */
fun rankedOpponent(match: Match): String = when {
    // A tournament seed wins: a tournament match's rank is filled from the
    // season's final poll, which was published after the tournament, so the
    // seed is the only number that describes the night itself.
    match.opponentSeed != null -> "(${match.opponentSeed}) ${match.opponent}"
    match.opponentRank != null -> "#${match.opponentRank} ${match.opponent}"
    else -> match.opponent
}

// SimpleDateFormat rather than java.time, which needs API 26 (minSdk is 24).
// Pinned to UTC on both sides so a date never shifts with the phone's zone.
private fun reformat(value: String, from: String, to: String): String? = runCatching {
    val inFmt = SimpleDateFormat(from, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }
    val outFmt = SimpleDateFormat(to, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    outFmt.format(inFmt.parse(value)!!)
}.getOrNull()

/**
 * "Fri Sep 25 · 6:00 PM CT · ESPN+". The schedule lists every time in Central,
 * so that is what is shown, labelled, rather than converted to a zone the
 * listing never said.
 */
fun kickoffLine(match: Match): String {
    val day = reformat(match.date, "yyyy-MM-dd", "EEE MMM d") ?: match.date
    val time = match.time.takeIf { it.isNotBlank() }
        ?.let { t -> reformat(t, "HH:mm", "h:mm a")?.let { "$it CT" } }
        ?: "Time TBA"
    return listOf(day, time, match.tv).filter { it.isNotBlank() }.joinToString(" · ")
}

/**
 * The schedule as someone opening the app wants it: what is next, soonest
 * first, then what has happened, newest first. A past date with no result
 * (a match never entered) sits with the results, where "No result" says so.
 */
fun splitSchedule(matches: List<Match>, today: String): Pair<List<Match>, List<Match>> {
    val upcoming = matches.filter { !it.played && it.date >= today }.sortedBy { it.date }
    val results = (matches - upcoming.toSet()).sortedByDescending { it.date }
    return upcoming to results
}

/** The most recent played meeting with this match's opponent before its date. */
fun lastMeeting(match: Match, matches: List<Match>): Match? =
    matches
        .filter { it.played && it.id != match.id && it.date < match.date && sameTeam(it.opponent, match.opponent) }
        .maxByOrNull { it.date }

/** "L 0-3 (Nov '25)", from KU's side. */
fun meetingLabel(meeting: Match): String {
    val us = meeting.teamSets ?: 0
    val them = meeting.opponentSets ?: 0
    val month = reformat(meeting.date, "yyyy-MM-dd", "MMM ''yy") ?: meeting.date
    return "${if (us > them) "W" else "L"} $us-$them ($month)"
}

data class RankedSplit(
    val rankedW: Int, val rankedL: Int,
    val unrankedW: Int, val unrankedL: Int
)

/** KU's record against teams ranked at the time of the match, and everybody else. */
fun rankedSplit(matches: List<Match>, season: String): RankedSplit {
    val played = matches.filter { it.season == season && it.played }
    val (ranked, unranked) = played.partition { it.opponentRank != null }
    fun w(l: List<Match>) = l.count { (it.teamSets ?: 0) > (it.opponentSets ?: 0) }
    return RankedSplit(w(ranked), ranked.size - w(ranked), w(unranked), unranked.size - w(unranked))
}

/**
 * KU's rank at each match of the season with repeats collapsed, then the
 * current poll if it has moved since the last match: 15 → 16 → 22 → 19.
 * "NR" marks a stretch outside the top 25.
 */
fun rankPath(matches: List<Match>, season: String, currentRank: Int?): List<String> {
    val path = mutableListOf<String>()
    matches.filter { it.season == season && it.played }.sortedBy { it.date }.forEach {
        val label = it.kuRank?.toString() ?: "NR"
        if (path.lastOrNull() != label) path += label
    }
    val now = currentRank?.toString() ?: if (path.isNotEmpty()) "NR" else null
    if (now != null && path.lastOrNull() != now) path += now
    return path
}

data class SeasonOutlook(
    val wins: Int,
    val losses: Int,
    // Null when there is no Big 12 list to judge conference matches by.
    val confWins: Int?,
    val confLosses: Int?,
    val toughest: Match?,
    val easiest: Match?,
    val forecasts: Int,
    val preseason: Int
)

/**
 * Where the season is heading if every forecast comes in at its expected
 * value: results so far plus the sum of the remaining win chances. Rounded,
 * and shown as "about", because summed estimates are all this is - it is not a
 * record anyone is promising, and a Big 12 finishing place cannot be read off
 * it since the other teams' remaining matches are not modelled at all.
 */
fun seasonOutlook(matches: List<Match>, season: String, big12: Set<String>): SeasonOutlook? {
    val all = matches.filter { it.season == season }
    val remaining = all.filter { !it.played && it.winProbability != null }
    if (remaining.isEmpty()) return null
    val played = all.filter { it.played }
    val playedWins = played.count { (it.teamSets ?: 0) > (it.opponentSets ?: 0) }
    val expected = remaining.sumOf { it.winProbability ?: 0.0 }
    val total = played.size + remaining.size
    val wins = (playedWins + expected).roundToInt()

    val isConf = { m: Match -> normTeam(m.opponent) in big12 }
    var confWins: Int? = null
    var confLosses: Int? = null
    if (big12.isNotEmpty()) {
        val confPlayed = played.filter(isConf)
        val confRemaining = remaining.filter(isConf)
        val cw = (confPlayed.count { (it.teamSets ?: 0) > (it.opponentSets ?: 0) } +
            confRemaining.sumOf { it.winProbability ?: 0.0 }).roundToInt()
        confWins = cw
        confLosses = confPlayed.size + confRemaining.size - cw
    }
    return SeasonOutlook(
        wins = wins,
        losses = total - wins,
        confWins = confWins,
        confLosses = confLosses,
        toughest = remaining.minByOrNull { it.winProbability ?: 1.0 },
        easiest = remaining.maxByOrNull { it.winProbability ?: 0.0 },
        forecasts = remaining.size,
        preseason = remaining.count { it.ratingSource == "preseason" }
    )
}

/** Win chances rounded to the nearest percent and marked approximate. */
fun roughPercent(p: Double?): String = p?.let { "~${(it * 100).roundToInt()}%" } ?: ""

fun outlookLine(o: SeasonOutlook): String = buildString {
    append("Projected ~${o.wins}-${o.losses}")
    if (o.confWins != null && o.confLosses != null && o.confWins + o.confLosses > 0) {
        append(" (about ${o.confWins}-${o.confLosses} Big 12)")
    }
    o.toughest?.let { append(" · toughest: ${it.versus} ${it.opponent} ${roughPercent(it.winProbability)}") }
    o.easiest?.takeIf { it != o.toughest }?.let {
        append(" · easiest: ${it.versus} ${it.opponent} ${roughPercent(it.winProbability)}")
    }
    if (o.preseason > 0) {
        append(" · ${o.preseason} of ${o.forecasts} forecasts use preseason ratings")
    }
}

/** Poll movement from the previous week's rank: "▲3", "▼2", "NEW", or "". */
fun pollMovement(rank: Int, previous: String): String {
    val before = previous.trim().toIntOrNull()
    return when {
        before == null -> if (previous.isBlank()) "" else "NEW"
        before > rank -> "▲${before - rank}"
        before < rank -> "▼${rank - before}"
        else -> ""
    }
}
