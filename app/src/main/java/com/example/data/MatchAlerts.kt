package com.example.data

import com.example.ui.played
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * What a match-night alert says, worked out from the NCAA's feeds - the same
 * ones the nightly scrape reads (ncaa-api.henrygd.me): the day's scoreboard to
 * find the game, the game itself for the set scores, and its box score for the
 * top performers. Plain functions over JSON so they can be tested without a
 * phone; [MatchAlertWorker] does the fetching and the notifying.
 */
object MatchAlerts {
    const val API = "https://ncaa-api.henrygd.me"
    private const val KU_SEO = "kansas"

    /** Minutes before first serve the window opens, and how long after it stays open. */
    const val WINDOW_BEFORE_MIN = 15L
    const val WINDOW_AFTER_MIN = 4 * 60L

    /** First serve in UTC millis: the schedule's "18:00" is Central time; 6 p.m. when none is published. */
    fun firstServeUtc(date: String, time: String): Long? = runCatching {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("America/Chicago")
            isLenient = false
        }
        fmt.parse("$date ${time.ifBlank { "18:00" }}")!!.time
    }.getOrNull()

    /** The KU match whose window [nowMs] falls in, if any. */
    fun activeMatch(matches: List<Match>, nowMs: Long): Match? = matches.firstOrNull { m ->
        if (m.played) return@firstOrNull false
        val start = firstServeUtc(m.date, m.time) ?: return@firstOrNull false
        nowMs >= start - WINDOW_BEFORE_MIN * 60_000 && nowMs <= start + WINDOW_AFTER_MIN * 60_000
    }

    /** KU's game id on a scoreboard response, or null. */
    fun kuGameId(scoreboard: JSONObject): String? {
        val games = scoreboard.optJSONArray("games") ?: return null
        for (i in 0 until games.length()) {
            val wrap = games.optJSONObject(i) ?: continue
            val g = wrap.optJSONObject("game") ?: wrap
            val seos = listOf("home", "away").map { g.optJSONObject(it)?.optJSONObject("names")?.optString("seo") }
            if (KU_SEO in seos) return g.optString("gameID").takeIf { it.isNotBlank() }
        }
        return null
    }

    /** A match in progress or finished, from Kansas's side. */
    data class Live(
        val opponent: String,
        /** Each set's points, Kansas first. The last may still be in play. */
        val sets: List<Pair<Int, Int>>,
        /** How many of [sets] are finished. */
        val finished: Int,
        val kuSets: Int,
        val oppSets: Int,
        val final: Boolean
    )

    /**
     * Reads the game. Sets are counted from the set scores rather than taken
     * from the NCAA's sets-won field, which once said 2-0 for a 3-0 sweep; and
     * when the box score shows the two teams' blocks crossed - Kansas's players
     * filed under the opponent's id, as happened at Florida State - the sides
     * are swapped back, so an alert never reports the other team's result.
     */
    fun live(info: JSONObject, box: JSONObject?, kuNames: Set<String>): Live? {
        val contest = info.optJSONArray("contests")?.optJSONObject(0) ?: return null
        val teams = contest.optJSONArray("teams") ?: return null
        val all = (0 until teams.length()).mapNotNull { teams.optJSONObject(it) }
        val ku = all.firstOrNull { it.optString("seoname") == KU_SEO } ?: return null
        val opp = all.firstOrNull { it !== ku } ?: return null
        var kuHome = ku.optBoolean("isHome")
        val kuBlockId = box?.let { kuBlock(it, kuNames) }?.optString("teamId")
        if (kuBlockId != null && kuBlockId.isNotBlank() && kuBlockId != ku.optString("teamId")) kuHome = !kuHome

        val lines = contest.optJSONArray("linescores") ?: JSONArray()
        val sets = (0 until lines.length()).mapNotNull { lines.optJSONObject(it) }.map {
            val home = it.optString("home").toIntOrNull() ?: 0
            val visit = it.optString("visit").toIntOrNull() ?: 0
            if (kuHome) home to visit else visit to home
        }.filter { (a, b) -> a + b > 0 }
        val isFinal = contest.optString("gameState").equals("F", true) ||
            contest.optString("currentPeriod").equals("FINAL", true)
        var finished = 0
        var us = 0
        var them = 0
        for ((i, s) in sets.withIndex()) {
            val target = if (i == 4) 15 else 25
            val done = (maxOf(s.first, s.second) >= target && kotlin.math.abs(s.first - s.second) >= 2) ||
                (isFinal && i == sets.lastIndex)
            if (!done || us == 3 || them == 3) break
            finished++
            if (s.first > s.second) us++ else them++
        }
        return Live(
            opponent = opp.optString("nameShort").ifBlank { "the opponent" },
            sets = sets, finished = finished, kuSets = us, oppSets = them,
            final = isFinal || us == 3 || them == 3
        )
    }

    /** "Kansas wins set 2, 25-21 · Kansas leads 2-0". */
    fun setMessage(live: Live): Pair<String, String> {
        val n = live.finished
        val (a, b) = live.sets[n - 1]
        val title = if (a > b) "Kansas wins set $n, $a-$b" else "${live.opponent} takes set $n, $b-$a"
        val state = when {
            live.kuSets > live.oppSets -> "Kansas leads ${live.kuSets}-${live.oppSets}"
            live.kuSets < live.oppSets -> "Kansas trails ${live.kuSets}-${live.oppSets}"
            else -> "Tied ${live.kuSets}-${live.oppSets}"
        }
        return title to "$state vs ${live.opponent}"
    }

    /** "Final: Kansas def. Utah 3-0" with the set scores and the top performers. */
    fun finalMessage(live: Live, box: JSONObject?, kuNames: Set<String>): Pair<String, String> {
        val won = live.kuSets > live.oppSets
        val title = if (won) "Final: Kansas def. ${live.opponent} ${live.kuSets}-${live.oppSets}"
        else "Final: ${live.opponent} def. Kansas ${live.oppSets}-${live.kuSets}"
        val scores = live.sets.take(live.finished).joinToString(", ") { "${it.first}-${it.second}" }
        val leaders = box?.let { kuBlock(it, kuNames) }?.let { topPerformers(it) }
        return title to listOfNotNull(scores, leaders).joinToString("\n")
    }

    /** "Ptacek 14 K (.684) · Hasbrook 13 D · Messer 27 A". */
    fun topPerformers(block: JSONObject): String? {
        val players = block.optJSONArray("playerStats") ?: return null
        val rows = (0 until players.length()).mapNotNull { players.optJSONObject(it) }
        fun n(p: JSONObject, k: String) = p.optString(k).toIntOrNull() ?: 0
        fun last(p: JSONObject) = p.optString("lastName").ifBlank { p.optString("firstName") }
        val parts = mutableListOf<String>()
        rows.maxByOrNull { n(it, "kills") }?.takeIf { n(it, "kills") > 0 }?.let { p ->
            val ta = n(p, "attackAttempts")
            val pct = if (ta > 0) " (" + formatHit((n(p, "kills") - n(p, "attackErrors")).toDouble() / ta) + ")" else ""
            parts += "${last(p)} ${n(p, "kills")} K$pct"
        }
        rows.maxByOrNull { n(it, "digs") }?.takeIf { n(it, "digs") > 0 }?.let { parts += "${last(it)} ${n(it, "digs")} D" }
        rows.maxByOrNull { n(it, "assists") }?.takeIf { n(it, "assists") >= 10 }?.let { parts += "${last(it)} ${n(it, "assists")} A" }
        rows.maxByOrNull { n(it, "blockSolos") * 2 + n(it, "blockAssists") }
            ?.takeIf { n(it, "blockSolos") + n(it, "blockAssists") >= 3 }
            ?.let { parts += "${last(it)} ${n(it, "blockSolos") + n(it, "blockAssists") / 2.0} BLK".replace(".0 BLK", " BLK") }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    private fun formatHit(v: Double): String {
        val s = String.format(Locale.US, "%.3f", v)
        return if (s.startsWith("0")) s.substring(1) else if (s.startsWith("-0")) "-" + s.substring(2) else s
    }

    /** The box-score block whose players are Kansas's, by roster names; null if neither matches. */
    fun kuBlock(box: JSONObject, kuNames: Set<String>): JSONObject? {
        val blocks = box.optJSONArray("teamBoxscore") ?: return null
        return (0 until blocks.length()).mapNotNull { blocks.optJSONObject(it) }
            .map { b ->
                val ps = b.optJSONArray("playerStats") ?: JSONArray()
                val hits = (0 until ps.length()).count { i ->
                    val p = ps.optJSONObject(i)
                    "${p?.optString("firstName")} ${p?.optString("lastName")}".trim().lowercase() in kuNames
                }
                b to hits
            }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }?.first
    }
}
