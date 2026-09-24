package com.example.ui

import com.example.data.Match
import com.example.data.normTeam
import org.json.JSONArray
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * The season read three more ways: how good the win model has been, what KU's
 * resume looks like to a selection committee, and how their matches have gone
 * set by set. Plain functions, kept out of the composables for the tests.
 */

private val Match.won: Boolean get() = (teamSets ?: 0) > (opponentSets ?: 0)

data class Scorecard(
    val graded: Int,
    // Matches where the side the model favoured won. A 50% call counts as KU.
    val called: Int,
    val expectedWins: Double,
    val actualWins: Int,
    // Mean squared error of the forecast against the result: 0 is perfect,
    // 0.25 is what saying 50% every time would score.
    val brier: Double
)

fun forecastScorecard(matches: List<Match>, season: String): Scorecard? {
    val graded = matches.filter { it.season == season && it.played && it.forecast != null }
    if (graded.isEmpty()) return null
    return Scorecard(
        graded = graded.size,
        called = graded.count { (it.forecast!! >= 0.5) == it.won },
        expectedWins = graded.sumOf { it.forecast!! },
        actualWins = graded.count { it.won },
        brier = graded.sumOf {
            val miss = it.forecast!! - if (it.won) 1.0 else 0.0
            miss * miss
        } / graded.size
    )
}

fun scorecardLine(s: Scorecard): String =
    "Called ${s.called} of ${s.graded} · expected " +
        "${"%.1f".format(java.util.Locale.US, s.expectedWins)} wins, won ${s.actualWins} · " +
        "Brier ${"%.2f".format(java.util.Locale.US, s.brier)} (0.25 = coin flip)"

data class RpiTier(val label: String, val wins: Int, val losses: Int)

data class Resume(
    val tiers: List<RpiTier>,
    val bestWins: List<Match>,
    val worstLosses: List<Match>,
    // Played matches whose opponent has no RPI rank in the table (not
    // Division I, or a name the table spells differently).
    val unrated: Int
)

/**
 * Record by the opponent's RPI band, the way committees read a resume:
 * wins over the top 25 and top 50 build a case, losses outside the top 100
 * damage one.
 */
fun rpiResume(matches: List<Match>, season: String): Resume? {
    val played = matches.filter { it.season == season && it.played }
    val rated = played.filter { it.opponentRpi != null }
    if (rated.isEmpty()) return null
    val bands = listOf("RPI 1-25" to 1..25, "26-50" to 26..50, "51-100" to 51..100, "101+" to 101..Int.MAX_VALUE)
    val tiers = bands.map { (label, range) ->
        val inBand = rated.filter { it.opponentRpi!! in range }
        RpiTier(label, inBand.count { it.won }, inBand.count { !it.won })
    }
    return Resume(
        tiers = tiers,
        // One entry per opponent: two wins over the same team are one data point here.
        bestWins = rated.filter { it.won }.sortedBy { it.opponentRpi }.distinctBy { normTeam(it.opponent) }.take(3),
        worstLosses = rated.filter { !it.won }.sortedByDescending { it.opponentRpi }
            .distinctBy { normTeam(it.opponent) }.take(2),
        unrated = played.size - rated.size
    )
}

fun resumeLine(r: Resume): String =
    r.tiers.joinToString(" · ") { "${it.label}: ${it.wins}-${it.losses}" }

/** Set scores from KU's side, e.g. "25-21, 18-25" -> [(25,21),(18,25)]. */
fun parseSets(setScores: String?): List<Pair<Int, Int>> =
    setScores.orEmpty().split(",").mapNotNull { part ->
        val bits = part.trim().split("-")
        if (bits.size != 2) null
        else bits[0].trim().toIntOrNull()?.let { a -> bits[1].trim().toIntOrNull()?.let { b -> a to b } }
    }

data class SetPatterns(
    // Sets won and lost by set number, first set first.
    val bySet: List<Pair<Int, Int>>,
    val wonFirstRecord: Pair<Int, Int>,
    val lostFirstRecord: Pair<Int, Int>,
    // Sets decided by two points - every deuce set, and 25-23.
    val closeSets: Pair<Int, Int>,
    val reverseSweeps: Int,
    val blownTwoNil: Int,
    val matches: Int
)

fun setPatterns(matches: List<Match>, season: String): SetPatterns? {
    val games = matches.filter { it.season == season && it.played }
        .map { it to parseSets(it.setScores) }
        .filter { it.second.isNotEmpty() }
    if (games.isEmpty()) return null
    val bySet = (0 until 5).map { i ->
        val sets = games.mapNotNull { it.second.getOrNull(i) }
        sets.count { it.first > it.second } to sets.count { it.first < it.second }
    }.filter { it.first + it.second > 0 }
    fun record(list: List<Pair<Match, List<Pair<Int, Int>>>>) =
        list.count { it.first.won } to list.count { !it.first.won }
    val (wonFirst, lostFirst) = games.partition { it.second[0].first > it.second[0].second }
    val all = games.flatMap { it.second }
    val close = all.filter { abs(it.first - it.second) <= 2 }
    fun downTwoNil(sets: List<Pair<Int, Int>>) = sets.size >= 2 && sets[0].first < sets[0].second && sets[1].first < sets[1].second
    fun upTwoNil(sets: List<Pair<Int, Int>>) = sets.size >= 2 && sets[0].first > sets[0].second && sets[1].first > sets[1].second
    return SetPatterns(
        bySet = bySet,
        wonFirstRecord = record(wonFirst),
        lostFirstRecord = record(lostFirst),
        closeSets = close.count { it.first > it.second } to close.count { it.first < it.second },
        reverseSweeps = games.count { downTwoNil(it.second) && it.first.won },
        blownTwoNil = games.count { upTwoNil(it.second) && !it.first.won },
        matches = games.size
    )
}

fun setPatternLines(p: SetPatterns): List<String> = listOfNotNull(
    p.bySet.mapIndexed { i, (w, l) -> "Set ${i + 1} $w-$l" }.joinToString(" · "),
    "Won set 1: ${p.wonFirstRecord.first}-${p.wonFirstRecord.second} · lost set 1: ${p.lostFirstRecord.first}-${p.lostFirstRecord.second}",
    "Close sets (2 pts): ${p.closeSets.first}-${p.closeSets.second}",
    listOfNotNull(
        p.reverseSweeps.takeIf { it > 0 }?.let { "came back from 0-2 $it×" },
        p.blownTwoNil.takeIf { it > 0 }?.let { "lost from 2-0 up $it×" }
    ).takeIf { it.isNotEmpty() }?.joinToString(" · ")?.replaceFirstChar { it.uppercase() }
)

/**
 * "Close sets (2 pts) 0-5 · latest 13-15 vs Florida St.": sets decided by two
 * points - every set that went past 25, and 25-23 - with the most recent.
 */
fun closeSetsLine(matches: List<Match>, season: String): String? {
    val close = matches.filter { it.season == season && it.played }
        .sortedBy { it.date }
        .flatMap { m -> parseSets(m.setScores).filter { abs(it.first - it.second) <= 2 }.map { m to it } }
    if (close.isEmpty()) return null
    val won = close.count { it.second.first > it.second.second }
    val (m, last) = close.last()
    return "Close sets (2 pts) $won-${close.size - won} · latest ${last.first}-${last.second} ${m.versus} ${m.opponent}"
}

/*
 * What each card's numbers mean, in plain words, shown under the card. Kept
 * word-for-word in step with EXPLAIN in docs/index.html so the app and the
 * dashboard explain things the same way.
 */
const val EXPLAIN_SUMMARY =
    "vs ranked: KU's record against teams in the AVCA top 25 on the day of the match. " +
        "KU rank: KU's poll rank at each match this season, then today's. " +
        "Close sets: sets won-lost when the final margin was two points. " +
        "Projected: the record so far plus the remaining win chances added up and rounded - " +
        "an estimate, not a forecast of the Big 12 standings."

const val EXPLAIN_RESUME =
    "RPI (Rating Percentage Index) is the NCAA's strength-of-record formula: 25% a team's " +
        "winning percentage, 50% its opponents', 25% its opponents' opponents'. The selection " +
        "committee uses it to choose and seed the NCAA tournament. Each band is KU's record " +
        "against teams ranked in that range today; wins over the top 50 help a resume most and " +
        "losses outside the top 100 hurt it most. Until the NCAA publishes this season's RPI, " +
        "the one shown is worked out here from every Division I result and marked provisional."

const val EXPLAIN_SETS =
    "Set 1, Set 2 ...: KU's sets won-lost in that set across all matches; only matches that " +
        "went four or five sets count toward Sets 4 and 5. Won/lost set 1: KU's match record " +
        "after winning or losing the opening set. Close sets: sets decided by exactly two " +
        "points - every set that went past 25, plus 25-23 (15-13 in a fifth). A comeback line " +
        "appears when KU wins from 0-2 down or loses from 2-0 up."

const val EXPLAIN_SCORECARD =
    "Grades the win % shown on the schedule, using the last forecast made before first serve. " +
        "Called: how often the team the model favoured won. Expected wins: the forecasts added " +
        "up; if it runs well ahead of the wins KU actually has, the ratings are too kind to KU. " +
        "Brier score: the average squared gap between forecast and result (1 for a win, 0 for a " +
        "loss). 0 is perfect, 0.25 is what saying 50% every time would score, and lower is " +
        "better. Matches before Sep 19, when the model started, are not graded."

const val EXPLAIN_COMMON =
    "Teams both KU and this opponent have played this season, and how each side did. A rough " +
        "guide only: venue, injuries and the point in the season all change what a result means."

const val EXPLAIN_FORM =
    "Form compares this player's last 5 matches with the whole season. ▲ or ▼ marks a " +
        "recent run clearly above or below the season figure (by at least 10%, or .030 for " +
        "hitting %). Tap a stat for its match-by-match chart: the dashed line is the season " +
        "average, and red dots are matches below it. Only stats the player has enough of are shown."

const val EXPLAIN_MILESTONES =
    "Round-number career totals within about five matches at this season's pace. Career " +
        "means every season this app holds (2025 onward), so totals from earlier years or " +
        "another school are not included."

const val EXPLAIN_FORECAST =
    "Est. win is the win model's chance that KU wins this match. Every team gets a power " +
        "rating: ranked teams from the AVCA coaches' poll, and unranked teams from their " +
        "results this season (their RPI), blended with a preseason estimate that counts for " +
        "less the more they play. The gap between KU's rating and the opponent's, plus a home " +
        "or road adjustment, becomes the percentage. It is an estimate from ratings, not a " +
        "betting line; the Win model scorecard on the Leaders tab shows how it has done."

/** The one sentence on where this opponent's rating comes from. */
fun forecastSourceLine(ratingSource: String?): String = when (ratingSource) {
    "poll" -> "This opponent is rated from the current AVCA poll."
    "results" -> "This opponent is unranked, so it is rated from its results this season blended with its preseason estimate."
    "preseason" -> "This opponent has played too few Division I matches to rate from results, so its preseason estimate is used."
    else -> ""
}

const val EXPLAIN_LAST_MEETING =
    "The most recent match between these two teams, from KU's side: the result, the set " +
        "scores, and the opponent's top attackers that day. Rosters change between seasons, " +
        "so an old meeting is a rough guide only."

const val EXPLAIN_ROSTER =
    "From the school's own roster page. Each player shows position, height, this season's " +
        "stats where the NCAA has them, and \"K vs KU\": kills against Kansas across every " +
        "meeting this app holds."

const val EXPLAIN_MATCH_GOALS =
    "The coaching staff's game-by-game targets, scored from this match's box score. Each row " +
        "shows the target, then what KU did: black is met, red is missed, and ≤ marks a goal " +
        "where lower is better. Team goals come first. Role goals (Setter, middles M1 and M2, " +
        "OPP for the opposite, L1 and L2 for the left sides) belong to whoever played that role " +
        "in this match, named under the goal."

const val EXPLAIN_SEASON_GOALS =
    "How often each target has been met this season, least often first. A goal that is " +
        "almost never met may be set out of reach rather than badly played. Tap a goal for " +
        "its match-by-match chart; the dashed line is the target and red dots are misses."

const val EXPLAIN_POLL =
    "The AVCA coaches' top 25. ▲ or ▼ is the move since the previous poll and NEW is a team " +
        "that was unranked last time. The number in brackets after a team is its first-place " +
        "votes. Big 12 teams are in bold."

data class CommonOpponent(val team: String, val ku: String, val them: String)

/** The feed's common-opponent list; empty for anything unreadable. */
fun parseCommonOpponents(json: String): List<CommonOpponent> = runCatching {
    val arr = JSONArray(json)
    (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        CommonOpponent(o.optString("team"), o.optString("ku"), o.optString("them"))
    }
}.getOrDefault(emptyList())

/** Percent, rounded, for the scorecard's per-match rows. */
fun pct(p: Double): String = "${(p * 100).roundToInt()}%"
