package com.example.data

import com.example.json.JSONObject
import com.example.platform.Device
import com.example.platform.Settings

/**
 * Syncs the bundled assets/seed.json into the database on every launch,
 * gap-filling only — it never overwrites user-entered data, with the one
 * exception at the end of this list:
 * - players are added if their name isn't already present
 * - matches are added if no match exists for that date + opponent
 * - an existing match gets seed results only if it has none, or if what it has
 *   is the exact mirror of the feed's — the signature of a crossed box score
 * - an existing match gets seed stat lines only if it has none
 * - the exception: the KU stat lines held for a match the feed marks
 *   linesChecked (checked against Kansas Athletics' box score) are corrected
 *   once, unless this version of the app took them from the feed after it
 *   marked the match (lines an earlier build took are corrected like any
 *   others). Each line's sets played becomes the feed's for that player, and a
 *   line for a player the feed's lines leave out is deleted if it records
 *   nothing (every counting stat and serves taken zero). A hand edit to sets
 *   played made before then, or such a line typed in by hand, is lost.
 *   Nothing else on a line changes, an edit made after is kept, and a match
 *   the feed has not marked is never touched. It takes a [Device] to remember
 *   which matches are done; without one it is not made.
 *
 * This lets an updated APK (with fresh season data baked in) install over the
 * old one and pick up the new matches while keeping local edits intact.
 *
 * The fields the feed owns - standings, polls, ranks, forecasts, first-serve
 * times - are rewritten, though, so a bundled copy older than the season data
 * the phone already took from the online feed is left alone: applied, it would
 * roll those back on every launch, and the sync would not restore them while
 * the feed is unchanged.
 *
 * Seed match shape:
 * {
 *   "date": "2025-08-29", "opponent": "Wisconsin", "season": "2025",
 *   "teamSets": 2, "opponentSets": 3,
 *   "setScores": "16-25, 25-18, 18-25, 28-26, 10-15",
 *   "linesChecked": true,
 *   "lines": [{"player": "<player name>", "sp": 5, "k": 15, "e": 6, "ta": 36,
 *              "a": 1, "sa": 0, "se": 2, "d": 3, "bs": 0, "ba": 3,
 *              "re": 0, "bhe": 0}]
 * }
 * linesChecked is there only on a match whose lines the feed has checked.
 */
object Seeder {

    suspend fun sync(device: Device, dao: JayhawksDao) {
        val json = device.readAsset("seed.json") ?: return

        runCatching {
            val root = JSONObject(json)
            val older = olderThan(root.optString("generatedAt"), SeasonSync.lastGeneratedAt(device))
            // Builds before this check merged an older copy anyway, and the sync
            // never merged the unchanged feed again to undo it. So on the first
            // launch with the check, a held-back copy has the sync merge the feed
            // once more; a copy that is merged repairs it by itself.
            val settings = device.settings(PREFS)
            if (!settings.boolean(KEY_CHECKED)) {
                if (older) SeasonSync.mergeAgain(device)
                settings.edit { putBoolean(KEY_CHECKED, true) }
            }
            if (older) return
            merge(root, dao, device)
            SimulationStore.save(device, root)
        }
    }

    private const val PREFS = "season_seed"
    private const val KEY_CHECKED = "rollback_checked"
    // The matches whose stat lines this phone has corrected to the feed's
    // linesChecked lines, or taken from them in the first place: matchKey()
    // strings, one per line.
    private const val KEY_LINES_CHECKED = "lines_checked"

    private fun linesChecked(settings: Settings): MutableSet<String> =
        settings.string(KEY_LINES_CHECKED).orEmpty().split('\n').filter { it.isNotEmpty() }.toMutableSet()

    /**
     * Whether merging [root] would correct stat lines this phone holds: a match
     * the feed marks linesChecked has lines here, and they have not been
     * corrected yet (see [merge]). [SeasonSync] merges a feed it has merged
     * before when this is so. A phone could otherwise wait months for it: one
     * whose bundled copy is held back, and whose last sync took this very feed
     * on a build that ignored the mark, finds nothing new in it.
     */
    suspend fun correctionPending(root: JSONObject, device: Device, dao: JayhawksDao): Boolean {
        val done = linesChecked(device.settings(PREFS))
        val matches = root.optJSONArray("matches") ?: return false
        val marked = (0 until matches.length())
            .map { matches.getJSONObject(it) }
            .filter { it.optBoolean("linesChecked") }
            .map { matchKey(it.optString("date"), it.optString("opponent")) }
            .filter { it !in done }
            .toSet()
        if (marked.isEmpty()) return false
        val withLines = dao.statLinesOnce().map { it.matchId }.toSet()
        return dao.matchesOnce().any { it.id in withLines && matchKey(it.date, it.opponent) in marked }
    }

    private val STAMP = Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ""")

    /**
     * Whether the bundled data, generated at [seed], is older than the feed
     * data last merged, generated at [feed]. Both are the scraper's UTC stamps
     * ("2026-10-25T07:42:36Z"), which sort as text. The same stamp is not older,
     * so a copy matching the feed is applied, which puts back anything an older
     * copy rolled back; a missing or unreadable stamp never holds it back.
     */
    internal fun olderThan(seed: String?, feed: String?): Boolean =
        seed != null && feed != null && STAMP.matches(seed) && STAMP.matches(feed) && seed < feed

    private fun matchKey(date: String, opponent: String) = "$date|${normTeam(opponent)}"

    /**
     * Mirrors norm_team() in scripts/update-seed.py.
     *
     * The two sources spell schools differently — the NCAA box score says
     * "Florida St." where kuathletics' schedule says "Florida State" — so a raw
     * lowercase name filed one match as two.
     */
    /**
     * Also used by [SeasonSync] for network-fetched season data.
     *
     * [device] is where the stat-line correction below remembers which matches
     * it has done; without one (the tests that only exercise the merge) the
     * correction is not made and nothing is remembered.
     */
    suspend fun merge(root: JSONObject, dao: JayhawksDao, device: Device? = null) {
        val players = root.optJSONArray("players")

        // Sources capitalize names inconsistently ("McCarthy" vs "Mccarthy"),
        // so all player matching is case-insensitive, and duplicates that
        // older seeds created are merged before anything else.
        val seedNameByKey = mutableMapOf<String, String>()
        if (players != null) {
            for (i in 0 until players.length()) {
                val name = players.getJSONObject(i).getString("name")
                seedNameByKey[name.lowercase()] = name
            }
        }
        healCaseDuplicates(dao, seedNameByKey)

        val existingByKey = dao.playersOnce().associateBy { it.name.lowercase() }
        val playerIdsByKey = existingByKey.mapValues { it.value.id }.toMutableMap()

        if (players != null) {
            for (i in 0 until players.length()) {
                val p = players.getJSONObject(i)
                val name = p.getString("name")
                val jersey = p.optString("jerseyNumber", "")
                val position = p.optString("position", "")
                val height = p.optString("height", "")
                val active = p.optBoolean("active", true)
                val classYear = p.optString("classYear", "")
                val hometown = p.optString("hometown", "")
                val highSchool = p.optString("highSchool", "")
                val previousSchool = p.optString("previousSchool", "")
                val existing = existingByKey[name.lowercase()]
                if (existing == null) {
                    playerIdsByKey[name.lowercase()] = dao.insertPlayer(
                        Player(
                            name = name,
                            jerseyNumber = jersey,
                            position = position,
                            height = height,
                            active = active,
                            classYear = classYear,
                            hometown = hometown,
                            highSchool = highSchool,
                            previousSchool = previousSchool
                        )
                    )
                } else {
                    // Roster facts (name casing, number, position, height,
                    // current-roster status) are scraper-owned and refreshed on
                    // every sync; blank seed values never erase what's there.
                    val updated = existing.copy(
                        name = name,
                        jerseyNumber = jersey.ifBlank { existing.jerseyNumber },
                        position = position.ifBlank { existing.position },
                        height = height.ifBlank { existing.height },
                        active = active,
                        classYear = classYear.ifBlank { existing.classYear },
                        hometown = hometown.ifBlank { existing.hometown },
                        highSchool = highSchool.ifBlank { existing.highSchool },
                        previousSchool = previousSchool.ifBlank { existing.previousSchool }
                    )
                    if (updated != existing) dao.updatePlayer(updated)
                }
            }
        }

        // Tournament weekends can put two matches on nearby dates, so matches
        // are keyed by date + opponent rather than date alone.
        val storedMatches = dao.matchesOnce()
        val storedLines = dao.statLinesOnce()
        val matchesWithLines = storedLines.map { it.matchId }.toSet()
        val storedLinesByKey = storedLines.associateBy { it.playerId to it.matchId }

        // Before matchKey normalised the name, a sync that saw both spellings on
        // the same day filed two rows for one match — which is what happened to
        // Florida State on 2026-09-04, when the box score landed while the
        // fixture was still dated today. The feed healed itself the next day;
        // a device that had already synced did not, because matches are never
        // deleted. So the leftover is dropped here, and only ever a row with no
        // result, no stat lines and no opposing box score, sitting on a date and
        // team another row already covers. Nothing a person entered by hand
        // looks like that.
        val withOpponentLines = dao.opponentStatLinesOnce().map { it.matchId }.toSet()
        val redundant = storedMatches
            .groupBy { matchKey(it.date, it.opponent) }
            .values
            .filter { it.size > 1 }
            .flatMap { group ->
                val bare = group.filter {
                    it.teamSets == null && it.opponentSets == null &&
                        it.id !in matchesWithLines && it.id !in withOpponentLines
                }
                // If every row in the group is bare, one of them still stays:
                // the duplicate is the problem, not the fixture itself.
                if (bare.size == group.size) bare.drop(1) else bare
            }
        redundant.forEach { dao.deleteMatch(it) }

        val matchesByKey = (storedMatches - redundant.toSet())
            .associateBy { matchKey(it.date, it.opponent) }

        // The goal names and targets are published once at the top of the feed
        // and a match carries only its values in that order, so the two are put
        // back together here. Read before the loop: it is the same list for
        // every match.
        val goalDefs = root.optJSONArray("goalDefinitions")

        // Stat lines already on the phone are never merged again (see below),
        // so a mistake the feed corrects stays wrong on every phone that synced
        // it. The NCAA feed's was one: it credited every Kansas player on the
        // team sheet with every set of the match, including players who never
        // came on, so bench players' matches and sets played were inflated and
        // their per-set numbers diluted. The feed now takes sets played from
        // Kansas Athletics' box scores and drops the lines of players who did not
        // play, match by match as each box score is read - which can be days
        // after the match, or a run or two into a backfill - and marks each
        // match it has done linesChecked. A phone holding lines for a marked
        // match corrects them, once per match: sets played from the feed, and a
        // line the feed does not have removed if it records nothing. Nothing
        // else on a line is touched, so a hand edit to a kill or a dig survives;
        // once done, a later hand edit to sets played does too. A match the
        // feed has not marked is never touched, whatever its lines say.
        val seedSettings = device?.settings(PREFS)
        val checked = seedSettings?.let { linesChecked(it) } ?: mutableSetOf()
        val checkedBefore = checked.toSet()

        val matches = root.optJSONArray("matches") ?: return
        for (i in 0 until matches.length()) {
            val m = matches.getJSONObject(i)
            val date = m.getString("date")
            val opponent = m.getString("opponent")
            val seedTeamSets = if (m.has("teamSets")) m.getInt("teamSets") else null
            val seedOppSets = if (m.has("opponentSets")) m.getInt("opponentSets") else null
            val seedSetScores = m.optString("setScores").takeIf { it.isNotBlank() }

            // Goal counts are derived from the box score, not entered here, so
            // they are read straight off the feed with none of the care the
            // result below needs. A recount that changes them - a corrected box
            // score, a target the staff moves - should land on the next sync.
            val seedGoals = m.optJSONObject("goals")
            // Likewise the forecast: model output, recomputed every run, and a
            // match that has since been played simply stops carrying one.
            val seedWinProbability =
                if (m.has("winProbability")) m.getDouble("winProbability") else null

            // Ranks, seeds, first serve and TV are all the feed's to say, so a
            // sync always wins - a time moved by the conference lands tonight.
            fun optIntOrNull(key: String) = if (m.has(key)) m.getInt(key) else null
            val seedRatingSource = m.optString("ratingSource").takeIf { it.isNotBlank() }
            val seedKuRank = optIntOrNull("kuRank")
            val seedOpponentRank = optIntOrNull("opponentRank")
            val seedKuSeed = optIntOrNull("kuSeed")
            val seedOpponentSeed = optIntOrNull("opponentSeed")
            val seedTime = m.optString("time")
            val seedTv = m.optString("tv")
            val seedOpponentRpi = optIntOrNull("opponentRpi")
            val seedForecast = if (m.has("forecast")) m.getDouble("forecast") else null
            val seedCommon = m.optJSONArray("commonOpponents")?.toString() ?: ""

            val seedHome = if (m.has("home")) m.getBoolean("home") else null
            val seedNeutral = m.optBoolean("neutral")
            val seedVenue = m.optString("venue")
            val seedCity = m.optString("city")

            val key = matchKey(date, opponent)
            val existing = matchesByKey[key]
            val matchId: Long
            if (existing == null) {
                matchId = dao.insertMatch(
                    Match(
                        date = date,
                        opponent = opponent,
                        season = m.getString("season"),
                        teamSets = seedTeamSets,
                        opponentSets = seedOppSets,
                        setScores = seedSetScores,
                        home = seedHome,
                        neutral = seedNeutral,
                        venue = seedVenue,
                        city = seedCity,
                        goalsMet = seedGoals?.optInt("met"),
                        goalsEvaluated = seedGoals?.optInt("evaluated"),
                        teamGoalsMet = seedGoals?.optInt("teamMet"),
                        teamGoalsEvaluated = seedGoals?.optInt("teamEvaluated"),
                        winProbability = seedWinProbability,
                        ratingSource = seedRatingSource,
                        kuRank = seedKuRank,
                        opponentRank = seedOpponentRank,
                        kuSeed = seedKuSeed,
                        opponentSeed = seedOpponentSeed,
                        time = seedTime,
                        tv = seedTv,
                        opponentRpi = seedOpponentRpi,
                        forecast = seedForecast,
                        commonOpponents = seedCommon
                    )
                )
            } else {
                matchId = existing.id
                val fillResult = existing.teamSets == null && existing.opponentSets == null &&
                    (seedTeamSets != null || seedOppSets != null)
                // A stored result that is the exact mirror of the feed's was not
                // typed by anybody — it is the signature of an upstream contest
                // whose two sides were crossed. That is what put the Florida
                // State match on every synced phone as a Kansas win: the NCAA
                // had Kansas down as winning 3-2 when it lost 2-3. Fixing the
                // feed could not fix those phones, because a result is
                // otherwise never overwritten, and it should not be: the app
                // lets people edit a score by hand. So only the mirror is
                // corrected. An edit that is not a mirror still stands, which
                // is what the merge has always promised.
                val mirrored = !fillResult &&
                    seedTeamSets != null && seedOppSets != null &&
                    existing.teamSets == seedOppSets && existing.opponentSets == seedTeamSets &&
                    seedTeamSets != seedOppSets
                // The same reasoning for a result the feed got wrong and later
                // fixed without mirroring it: Utah on 2026-10-01 went out as
                // 2-0 beside three set scores Kansas all won. A stored result
                // that its own set scores contradict, where those set scores
                // are the feed's and the feed's result now agrees with them,
                // was never typed by anybody either.
                val stale = !fillResult && !mirrored &&
                    seedTeamSets != null && seedOppSets != null &&
                    existing.setScores != null && existing.setScores == seedSetScores &&
                    setsFromScores(seedSetScores) == (seedTeamSets to seedOppSets) &&
                    setsFromScores(existing.setScores) != (existing.teamSets to existing.opponentSets)
                val takeSeedResult = fillResult || mirrored || stale
                // Venue facts fill in when missing (an upcoming match becoming a
                // played one learns where it happened) but never overwrite.
                val updated = existing.copy(
                    teamSets = if (takeSeedResult) seedTeamSets else existing.teamSets,
                    opponentSets = if (takeSeedResult) seedOppSets else existing.opponentSets,
                    setScores = if (mirrored) seedSetScores ?: existing.setScores
                        else existing.setScores ?: seedSetScores,
                    home = existing.home ?: seedHome,
                    neutral = existing.neutral || seedNeutral,
                    venue = existing.venue.ifBlank { seedVenue },
                    city = existing.city.ifBlank { seedCity },
                    goalsMet = seedGoals?.optInt("met") ?: existing.goalsMet,
                    goalsEvaluated = seedGoals?.optInt("evaluated") ?: existing.goalsEvaluated,
                    teamGoalsMet = seedGoals?.optInt("teamMet") ?: existing.teamGoalsMet,
                    teamGoalsEvaluated = seedGoals?.optInt("teamEvaluated")
                        ?: existing.teamGoalsEvaluated,
                    // Not `?:` like the rest: the feed drops the forecast the
                    // moment a match is played, and that dropping is the point.
                    // Keeping the last one would leave an estimate sitting
                    // beside a final score.
                    winProbability = seedWinProbability,
                    ratingSource = seedRatingSource,
                    kuRank = seedKuRank,
                    opponentRank = seedOpponentRank,
                    kuSeed = seedKuSeed,
                    opponentSeed = seedOpponentSeed,
                    time = seedTime,
                    tv = seedTv,
                    opponentRpi = seedOpponentRpi,
                    forecast = seedForecast,
                    commonOpponents = seedCommon
                )
                if (updated != existing) dao.updateMatch(updated)
            }

            // Opponent lines and team totals are merged before the KU stat-line
            // guard below: a match whose KU lines are already recorded still
            // needs its opposing box score the first time one shows up.
            mergeOpponentBox(m, matchId, dao)
            mergeMatchGoals(goalDefs, seedGoals, matchId, dao)

            // Only with a device to remember it on: see above.
            val marked = seedSettings != null && m.optBoolean("linesChecked")

            if (existing != null && matchId in matchesWithLines) {
                // Stat lines are never re-merged once a match has them, because
                // they can be edited here. Serve attempts are the exception: they
                // were not carried until v13, nobody can type them, and a zero
                // is simply "not recorded yet". So that one field is filled in
                // where it is still zero, and nothing else on the line is touched.
                // The once-per-match correction of sets played (above) is the
                // other.
                val correct = marked && key !in checked
                val seedLines = m.optJSONArray("lines")
                val seedPlayerIds = mutableSetOf<Long>()
                if (seedLines != null) for (j in 0 until seedLines.length()) {
                    val l = seedLines.getJSONObject(j)
                    val playerId = playerIdsByKey[l.getString("player").lowercase()] ?: continue
                    seedPlayerIds += playerId
                    val stored = storedLinesByKey[playerId to matchId] ?: continue
                    var fixed = stored
                    val sat = l.optInt("sat")
                    if (sat > 0 && stored.serveAttempts == 0) fixed = fixed.copy(serveAttempts = sat)
                    if (correct) fixed = fixed.copy(setsPlayed = l.optInt("sp"))
                    if (fixed != stored) dao.upsertStatLine(fixed)
                }
                if (correct) {
                    // A feed with no lines for the match says nothing about who
                    // played, so it removes nothing.
                    if (seedPlayerIds.isNotEmpty()) {
                        storedLines
                            .filter { it.matchId == matchId && it.playerId !in seedPlayerIds && it.recordsNothing() }
                            .forEach { dao.deleteStatLine(it) }
                    }
                    // Recorded whatever the feed's lines held, so that
                    // correctionPending() is never left true for good.
                    checked += key
                }
                continue
            }
            // Lines taken fresh from a marked match need no correcting later,
            // and must not get one: anything changed from here on is an edit.
            if (marked) checked += key
            val lines = m.optJSONArray("lines") ?: continue
            for (j in 0 until lines.length()) {
                val l = lines.getJSONObject(j)
                val playerId = playerIdsByKey[l.getString("player").lowercase()] ?: continue
                dao.upsertStatLine(
                    StatLine(
                        playerId = playerId,
                        matchId = matchId,
                        setsPlayed = l.optInt("sp"),
                        kills = l.optInt("k"),
                        attackErrors = l.optInt("e"),
                        attackAttempts = l.optInt("ta"),
                        assists = l.optInt("a"),
                        serviceAces = l.optInt("sa"),
                        serviceErrors = l.optInt("se"),
                        digs = l.optInt("d"),
                        blockSolos = l.optInt("bs"),
                        blockAssists = l.optInt("ba"),
                        receptionErrors = l.optInt("re"),
                        ballHandlingErrors = l.optInt("bhe"),
                        serveAttempts = l.optInt("sat")
                    )
                )
            }
        }
        if (seedSettings != null && checked != checkedBefore) {
            seedSettings.edit { putString(KEY_LINES_CHECKED, checked.sorted().joinToString("\n")) }
        }

        mergeStandings(root, dao)
        mergeOpponentRosters(root, dao)
    }

    /**
     * Whether a line records nothing done on the floor: every counting stat and
     * serves taken are zero. Sets played is left out, being the figure in
     * question. update-seed.py asks the same before it drops a player the NCAA
     * listed, and also counts serve receptions, which a line here does not
     * carry.
     */
    private fun StatLine.recordsNothing(): Boolean = listOf(
        kills, attackErrors, attackAttempts, assists, serviceAces, serviceErrors,
        digs, blockSolos, blockAssists, receptionErrors, ballHandlingErrors, serveAttempts
    ).all { it == 0 }

    /**
     * Opponents' published rosters and season form, replaced per team for the
     * same reason the standings are: scraper-owned, nothing user-entered, and a
     * player who leaves the roster has to actually disappear. A seed without
     * these keys leaves whatever is stored alone.
     */
    private suspend fun mergeOpponentRosters(root: JSONObject, dao: JayhawksDao) {
        root.optJSONArray("opponentRosters")?.let { teams ->
            for (i in 0 until teams.length()) {
                val t = teams.getJSONObject(i)
                val team = t.optString("team").takeIf { it.isNotBlank() } ?: continue
                val players = t.optJSONArray("players") ?: continue
                val rows = (0 until players.length()).mapNotNull { j ->
                    val p = players.getJSONObject(j)
                    val name = p.optString("player").takeIf { it.isNotBlank() }
                    name?.let {
                        OpponentRosterEntry(
                            team = team,
                            playerName = it,
                            jerseyNumber = p.optString("jerseyNumber"),
                            position = p.optString("position"),
                            height = p.optString("height"),
                            classYear = p.optString("classYear"),
                            hometown = p.optString("hometown"),
                            highSchool = p.optString("highSchool"),
                            previousSchool = p.optString("previousSchool")
                        )
                    }
                }
                if (rows.isEmpty()) continue
                dao.deleteOpponentRosterForTeam(team)
                dao.insertOpponentRoster(rows)
            }
        }

        root.optJSONArray("opponentForm")?.let { teams ->
            for (i in 0 until teams.length()) {
                val t = teams.getJSONObject(i)
                val team = t.optString("team").takeIf { it.isNotBlank() } ?: continue
                val players = t.optJSONArray("players") ?: continue
                val rows = (0 until players.length()).mapNotNull { j ->
                    val p = players.getJSONObject(j)
                    val name = p.optString("player").takeIf { it.isNotBlank() }
                    name?.let {
                        OpponentSeasonStat(
                            team = team,
                            playerName = it,
                            jerseyNumber = p.optString("jerseyNumber"),
                            position = p.optString("position"),
                            matchesPlayed = p.optInt("mp"),
                            setsPlayed = p.optInt("sp"),
                            kills = p.optInt("k"),
                            attackErrors = p.optInt("e"),
                            attackAttempts = p.optInt("ta"),
                            assists = p.optInt("a"),
                            serviceAces = p.optInt("sa"),
                            serviceErrors = p.optInt("se"),
                            digs = p.optInt("d"),
                            blockSolos = p.optInt("bs"),
                            blockAssists = p.optInt("ba"),
                            receptionErrors = p.optInt("re"),
                            ballHandlingErrors = p.optInt("bhe")
                        )
                    }
                }
                if (rows.isEmpty()) continue
                dao.deleteOpponentSeasonStatsForTeam(team)
                dao.insertOpponentSeasonStats(rows)
            }
        }
    }

    /**
     * The opposing side of one match's box score, plus both teams' official
     * totals. Like the standings below this is scraper-owned with no
     * user-entered fields, so a match's rows are replaced outright — that way a
     * corrected box score actually corrects, and a player scratched from a
     * revised line-up disappears instead of lingering. A match whose seed
     * carries no opponent data is left exactly as it is.
     */
    /**
     * Rebuilds one match's performance goals from the feed.
     *
     * The definitions and the values arrive apart and are joined by position, so
     * a mismatched length is a corrupt feed rather than something to paper over:
     * pairing a value with the wrong goal would show a hitting percentage
     * against a per-set target and look plausible doing it. Nothing is written
     * in that case, leaving the previous sync's rows alone.
     *
     * Whether a goal was met is not stored; it is the value against the target,
     * and [MatchGoal.met] works it out on demand.
     */
    private suspend fun mergeMatchGoals(
        defs: com.example.json.JSONArray?,
        goals: JSONObject?,
        matchId: Long,
        dao: JayhawksDao
    ) {
        val values = goals?.optJSONArray("values")
        if (defs == null || values == null || defs.length() != values.length()) return
        val roles = goals.optJSONObject("roles")
        val rows = (0 until defs.length()).map { i ->
            val d = defs.getJSONObject(i)
            val role = d.optString("role")
            MatchGoal(
                matchId = matchId,
                idx = i,
                name = d.optString("name"),
                goalGroup = d.optString("group"),
                target = d.optDouble("target", 0.0),
                // JSONArray turns a JSON null into JSONObject.NULL, which
                // optDouble would quietly read as NaN.
                value = if (values.isNull(i)) null else values.optDouble(i),
                decimals = d.optInt("decimals", 3),
                ceiling = d.optBoolean("ceiling"),
                role = role,
                player = if (role.isBlank()) "" else roles?.optString(role).orEmpty()
            )
        }
        dao.deleteMatchGoalsForMatch(matchId)
        dao.insertMatchGoals(rows)
    }

    private suspend fun mergeOpponentBox(m: JSONObject, matchId: Long, dao: JayhawksDao) {
        m.optJSONArray("opponentLines")?.takeIf { it.length() > 0 }?.let { arr ->
            val rows = (0 until arr.length()).map { j ->
                val l = arr.getJSONObject(j)
                OpponentStatLine(
                    matchId = matchId,
                    playerName = l.getString("player"),
                    jerseyNumber = l.optString("jerseyNumber"),
                    position = l.optString("position"),
                    height = l.optString("height"),
                    setsPlayed = l.optInt("sp"),
                    kills = l.optInt("k"),
                    attackErrors = l.optInt("e"),
                    attackAttempts = l.optInt("ta"),
                    assists = l.optInt("a"),
                    serviceAces = l.optInt("sa"),
                    serviceErrors = l.optInt("se"),
                    digs = l.optInt("d"),
                    blockSolos = l.optInt("bs"),
                    blockAssists = l.optInt("ba"),
                    receptionErrors = l.optInt("re"),
                    ballHandlingErrors = l.optInt("bhe")
                )
            }
            dao.deleteOpponentStatLinesForMatch(matchId)
            dao.insertOpponentStatLines(rows)
        }

        val totals = listOfNotNull(
            m.optJSONObject("teamStats")?.let { teamStats(it, matchId, opponent = false) },
            m.optJSONObject("opponentStats")?.let { teamStats(it, matchId, opponent = true) }
        )
        if (totals.isNotEmpty()) dao.upsertMatchTeamStats(totals)
    }

    private fun teamStats(o: JSONObject, matchId: Long, opponent: Boolean) = MatchTeamStats(
        matchId = matchId,
        opponent = opponent,
        setsPlayed = o.optInt("sp"),
        kills = o.optInt("k"),
        attackErrors = o.optInt("e"),
        attackAttempts = o.optInt("ta"),
        assists = o.optInt("a"),
        serviceAces = o.optInt("sa"),
        serviceErrors = o.optInt("se"),
        digs = o.optInt("d"),
        blockSolos = o.optInt("bs"),
        blockAssists = o.optInt("ba"),
        receptionErrors = o.optInt("re"),
        ballHandlingErrors = o.optInt("bhe")
    )

    /**
     * Big 12 standings and poll snapshots are scraper-derived and change after
     * every result, so they are replaced per season rather than gap-filled —
     * the one deliberate exception to this file's never-overwrite rule, safe
     * because no field here is ever user-entered. Seeds that omit these keys
     * (older payloads) leave whatever is already stored untouched.
     */
    private suspend fun mergeStandings(root: JSONObject, dao: JayhawksDao) {
        root.optJSONArray("standings")?.let { arr ->
            val bySeason = mutableMapOf<String, MutableList<ConferenceStanding>>()
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                val season = s.optString("season").takeIf { it.isNotBlank() } ?: continue
                val seo = s.optString("seo").takeIf { it.isNotBlank() }
                    ?: s.optString("team").lowercase().replace(' ', '-')
                bySeason.getOrPut(season) { mutableListOf() }.add(
                    ConferenceStanding(
                        season = season,
                        seo = seo,
                        team = s.optString("team"),
                        confW = s.optInt("confW"),
                        confL = s.optInt("confL"),
                        overallW = s.optInt("overallW"),
                        overallL = s.optInt("overallL"),
                        nationalRank = s.optInt("nationalRank").takeIf { it > 0 },
                        rpiRank = s.optInt("rpiRank").takeIf { it > 0 },
                        rpiSource = s.optString("rpiSource")
                    )
                )
            }
            for ((season, rows) in bySeason) {
                dao.deleteStandingsForSeason(season)
                dao.insertStandings(rows)
            }
        }

        root.optJSONArray("polls")?.let { arr ->
            for (i in 0 until arr.length()) {
                val poll = arr.getJSONObject(i)
                val season = poll.optString("season").takeIf { it.isNotBlank() } ?: continue
                val name = poll.optString("name")
                val updated = poll.optString("updated")
                val rows = poll.optJSONArray("rows") ?: continue
                val entries = (0 until rows.length()).mapNotNull { j ->
                    val r = rows.getJSONObject(j)
                    val team = r.optString("team").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    PollEntry(
                        season = season,
                        team = team,
                        rank = r.optInt("rank"),
                        rankLabel = r.optString("rankLabel").ifBlank { r.optInt("rank").toString() },
                        record = r.optString("record"),
                        points = r.optString("points"),
                        previous = r.optString("previous"),
                        firstPlaceVotes = r.optInt("firstPlaceVotes"),
                        big12 = r.optBoolean("big12"),
                        pollName = name,
                        updated = updated
                    )
                }
                dao.deletePollForSeason(season)
                dao.insertPollEntries(entries)
            }
        }

        // Cumulative team serving, grouped by season and replaced a season at a
        // time - so a team that leaves the tracked set leaves the table with it
        // rather than freezing at the totals it held when it dropped out.
        root.optJSONArray("teamServing")?.let { arr ->
            val bySeason = mutableMapOf<String, MutableList<TeamServing>>()
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                val season = r.optString("season").takeIf { it.isNotBlank() } ?: continue
                val team = r.optString("team").takeIf { it.isNotBlank() } ?: continue
                bySeason.getOrPut(season) { mutableListOf() }.add(
                    TeamServing(
                        season = season,
                        team = team,
                        matches = r.optInt("matches"),
                        sets = r.optInt("sets"),
                        serviceAces = r.optInt("serviceAces"),
                        serviceErrors = r.optInt("serviceErrors"),
                        serveAttempts = r.optInt("serveAttempts"),
                        big12 = r.optBoolean("big12"),
                        pollRank = if (r.has("pollRank")) r.optInt("pollRank") else null
                    )
                )
            }
            for ((season, rows) in bySeason) {
                dao.deleteTeamServingForSeason(season)
                dao.insertTeamServing(rows)
            }
        }

        // The NCAA's national top 50 per category, flattened from
        // {season, categories:[{name, valueLabel, rows:[...]}]} into one row
        // per player per category. The season's rows are replaced wholesale:
        // this is a snapshot of a live ranking, so a player who has dropped off
        // the list should leave with it rather than linger at their old rank.
        root.optJSONObject("nationalLeaders")?.let { nl ->
            val season = nl.optString("season").takeIf { it.isNotBlank() } ?: return@let
            val cats = nl.optJSONArray("categories") ?: return@let
            val rows = mutableListOf<NationalLeader>()
            for (i in 0 until cats.length()) {
                val cat = cats.getJSONObject(i)
                val name = cat.optString("name").takeIf { it.isNotBlank() } ?: continue
                val label = cat.optString("valueLabel")
                // "Friday, September 18, 2026 9:09 am - Through games Thursday,
                // September 17, 2026": the second half is the part that says
                // what the list covers.
                val asOf = nl.optString("updated").substringAfter(" - ", nl.optString("updated"))
                val list = cat.optJSONArray("rows") ?: continue
                for (j in 0 until list.length()) {
                    val r = list.getJSONObject(j)
                    val player = r.optString("player")
                    if (player.isBlank()) continue
                    rows.add(
                        NationalLeader(
                            season = season,
                            category = name,
                            // The feed numbers the rows; j is the fallback for
                            // a seed written before it did.
                            idx = if (r.has("idx")) r.optInt("idx") else j,
                            rank = r.optInt("rank"),
                            player = player,
                            team = r.optString("team"),
                            position = r.optString("position"),
                            cls = r.optString("cls"),
                            height = r.optString("height"),
                            sets = r.optInt("sets"),
                            value = r.optString("value"),
                            valueLabel = label,
                            asOf = asOf
                        )
                    )
                }
            }
            if (rows.isNotEmpty()) {
                dao.deleteNationalLeadersForSeason(season)
                dao.insertNationalLeaders(rows)
            }
        }
    }

    /**
     * Merges player rows whose names differ only by capitalization (created by
     * seeds that predate case-insensitive matching): stat lines move to the
     * surviving row, then the duplicates are deleted. The row matching the
     * seed's spelling survives; ties keep the first row.
     */
    private suspend fun healCaseDuplicates(dao: JayhawksDao, seedNameByKey: Map<String, String>) {
        val groups = dao.playersOnce().groupBy { it.name.lowercase() }
        for ((key, dupes) in groups) {
            if (dupes.size < 2) continue
            val canonical = seedNameByKey[key]
            val keeper = dupes.firstOrNull { it.name == canonical } ?: dupes.first()
            for (dupe in dupes) {
                if (dupe.id == keeper.id) continue
                dao.statLinesOnce()
                    .filter { it.playerId == dupe.id }
                    // REPLACE on the (playerId, matchId) unique index absorbs the
                    // rare case where both rows have a line for the same match.
                    .forEach { dao.upsertStatLine(it.copy(playerId = keeper.id)) }
                dao.deletePlayer(dupe)
            }
        }
    }
}

/**
 * (KU sets, opponent sets) counted from "25-20, 23-25, ..." or null unless
 * every set is a finished one (first to 25, 15 in a fifth, won by two) and
 * the match is decided at three sets.
 */
fun setsFromScores(scores: String?): Pair<Int, Int>? {
    if (scores.isNullOrBlank()) return null
    var us = 0
    var them = 0
    scores.split(",").forEachIndexed { i, set ->
        val parts = set.trim().split("-").mapNotNull { it.trim().toIntOrNull() }
        if (parts.size != 2) return null
        val (a, b) = parts
        val target = if (i == 4) 15 else 25
        if (maxOf(a, b) < target || kotlin.math.abs(a - b) < 2) return null
        if (a > b) us++ else them++
    }
    return if (maxOf(us, them) == 3 && minOf(us, them) <= 2) us to them else null
}
