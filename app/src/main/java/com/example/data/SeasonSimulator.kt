package com.example.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * The season simulator: plays the rest of the season out many times from the
 * inputs the pipeline ships (seed.simulation, built by scripts/sim_inputs.py)
 * and counts what happened.
 *
 * A line-for-line copy of docs/sim.js. Both use the same small random-number
 * generator (mulberry32) from the same starting seed and draw in the same
 * order, so the app and the dashboard show identical numbers, and a what-if
 * toggled in one gives what it gives in the other. SeasonSimulatorTest.kt and
 * docs/sim.test.mjs pin the same fixture to the same results.
 */
object SeasonSimulator {
    const val RUNS = 20000
    private const val SEED = 20261

    data class Remaining(
        val date: String, val opponent: String, val venue: String,
        val conference: Boolean, val key: String, val p: Double
    )

    data class Other(
        val team: String, val key: String, val confW: Int, val confL: Int,
        val remaining: Int, val pVsAverage: Double
    )

    data class Inputs(
        val confW: Int, val confL: Int, val overallW: Int, val overallL: Int,
        val remaining: List<Remaining>, val others: List<Other>,
        val rpiWins: Int, val rpiPlayed: Int, val owp: Double, val oowp: Double,
        val field: List<Double>, val atLargeCutoff: Int
    )

    data class Result(
        val runs: Int,
        val expectedWins: Double, val expectedLosses: Double,
        val winsLow: Int, val winsMid: Int, val winsHigh: Int, val games: Int,
        val title: Double, val shareOfTitle: Double, val top4: Double, val averagePlace: Double,
        val rpiRankMid: Int, val rpiRankLow: Int, val rpiRankHigh: Int,
        val atLarge: Double, val tournament: Double
    )

    fun parse(o: JSONObject): Inputs? = runCatching {
        val ku = o.getJSONObject("kansas")
        val rem = o.getJSONArray("remaining")
        val oth = o.getJSONArray("others")
        val rpi = o.getJSONObject("rpi")
        val field = rpi.getJSONArray("field")
        Inputs(
            confW = ku.getInt("confW"), confL = ku.getInt("confL"),
            overallW = ku.getInt("overallW"), overallL = ku.getInt("overallL"),
            remaining = (0 until rem.length()).map { i ->
                rem.getJSONObject(i).let {
                    Remaining(
                        it.getString("date"), it.getString("opponent"), it.optString("venue"),
                        it.optBoolean("conference"), it.getString("key"), it.getDouble("p")
                    )
                }
            },
            others = (0 until oth.length()).map { i ->
                oth.getJSONObject(i).let {
                    Other(
                        it.getString("team"), it.getString("key"), it.getInt("confW"), it.getInt("confL"),
                        it.getInt("remaining"), it.getDouble("pVsAverage")
                    )
                }
            },
            rpiWins = rpi.getInt("wins"), rpiPlayed = rpi.getInt("played"),
            owp = rpi.getDouble("owp"), oowp = rpi.getDouble("oowp"),
            field = (0 until field.length()).map { field.getDouble(it) },
            atLargeCutoff = rpi.getInt("atLargeCutoff")
        )
    }.getOrNull()

    /** mulberry32, written so its Int arithmetic matches JavaScript's to the bit. */
    private class Rng(seed: Int) {
        private var a = seed
        fun next(): Double {
            a += 0x6D2B79F5
            var t = (a xor (a ushr 15)) * (1 or a)
            t = (t + (t xor (t ushr 7)) * (61 or t)) xor t
            return ((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL) / 4294967296.0
        }
    }

    /** [forced]: index into [Inputs.remaining] to true (KU wins) or false (KU loses). */
    fun simulate(inputs: Inputs, forced: Map<Int, Boolean> = emptyMap(), runs: Int = RUNS): Result {
        val rnd = Rng(SEED)
        val rem = inputs.remaining
        val others = inputs.others
        val index = others.withIndex().associate { (i, o) -> o.key to i }
        val overall = IntArray(rem.size + 1)
        var titles = 0
        var shares = 0
        var top4 = 0
        var atLarge = 0
        var inField = 0
        var placeSum = 0L
        val ranks = IntArray(runs)
        val confWins = IntArray(others.size)

        for (run in 0 until runs) {
            for (i in others.indices) confWins[i] = others[i].confW
            var wins = 0
            var kuConf = inputs.confW
            for (m in rem.indices) {
                val draw = rnd.next() // always drawn, so a forced result does not shift the rest
                val won = forced[m] ?: (draw < rem[m].p)
                if (won) {
                    wins++
                    if (rem[m].conference) kuConf++
                } else if (rem[m].conference) {
                    index[rem[m].key]?.let { confWins[it]++ }
                }
            }
            for (i in others.indices) {
                repeat(others[i].remaining) { if (rnd.next() < others[i].pVsAverage) confWins[i]++ }
            }
            // Ties settled by a coin toss, as in sim.js.
            val kuTie = rnd.next()
            var ahead = 0
            var best = true
            for (i in others.indices) {
                val tie = rnd.next()
                if (confWins[i] > kuConf || (confWins[i] == kuConf && tie > kuTie)) ahead++
                if (confWins[i] > kuConf) best = false
            }
            val place = ahead + 1

            val w = inputs.rpiWins + wins
            val p = inputs.rpiPlayed + rem.size
            val value = 0.25 * (w.toDouble() / p) + 0.5 * inputs.owp + 0.25 * inputs.oowp
            var rank = 1
            for (v in inputs.field) if (v > value) rank++

            overall[wins]++
            placeSum += place
            if (place == 1) titles++
            if (best) shares++
            if (place <= 4) top4++
            if (rank <= inputs.atLargeCutoff) atLarge++
            if (rank <= inputs.atLargeCutoff || place == 1) inField++
            ranks[run] = rank
        }

        ranks.sort()
        fun pct(q: Double) = ranks[minOf(ranks.size - 1, kotlin.math.floor(q * ranks.size).toInt())]
        fun winsAt(q: Double): Int {
            var seen = 0
            for (k in overall.indices) {
                seen += overall[k]
                if (seen > q * runs) return k
            }
            return overall.size - 1
        }
        val exp = overall.withIndex().sumOf { (k, n) -> n.toDouble() * k } / runs
        return Result(
            runs = runs,
            expectedWins = inputs.overallW + exp,
            expectedLosses = inputs.overallL + rem.size - exp,
            winsLow = inputs.overallW + winsAt(0.1),
            winsMid = inputs.overallW + winsAt(0.5),
            winsHigh = inputs.overallW + winsAt(0.9),
            games = inputs.overallW + inputs.overallL + rem.size,
            title = titles.toDouble() / runs,
            shareOfTitle = shares.toDouble() / runs,
            top4 = top4.toDouble() / runs,
            averagePlace = placeSum.toDouble() / runs,
            rpiRankMid = pct(0.5), rpiRankLow = pct(0.1), rpiRankHigh = pct(0.9),
            atLarge = atLarge.toDouble() / runs,
            tournament = inField.toDouble() / runs
        )
    }

    /** "37%", with the ends said in words rather than as a false 0% or 100%. */
    fun chance(p: Double): String = when {
        p < 0.005 -> "under 1%"
        p > 0.995 -> "over 99%"
        else -> "${Math.round(p * 100)}%"
    }
}

/**
 * Where the simulator's inputs are kept: a small file beside the database,
 * written from the bundled seed and from every sync, newest wins.
 */
object SimulationStore {
    private const val FILE = "simulation.json"

    fun save(context: Context, root: JSONObject) {
        val sim = root.optJSONObject("simulation") ?: return
        val generatedAt = root.optString("generatedAt")
        val file = File(context.filesDir, FILE)
        val stored = load(context)
        if (stored != null && stored.optString("generatedAt") > generatedAt) return
        runCatching {
            file.writeText(JSONObject().put("generatedAt", generatedAt).put("simulation", sim).toString())
        }
    }

    fun load(context: Context): JSONObject? = runCatching {
        JSONObject(File(context.filesDir, FILE).readText())
    }.getOrNull()

    fun inputs(context: Context): SeasonSimulator.Inputs? =
        load(context)?.optJSONObject("simulation")?.let { SeasonSimulator.parse(it) }
}
