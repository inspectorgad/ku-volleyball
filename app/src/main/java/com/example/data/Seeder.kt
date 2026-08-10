package com.example.data

import android.content.Context
import org.json.JSONObject

/**
 * Syncs the bundled assets/seed.json into the database on every launch,
 * gap-filling only — it never overwrites user-entered data:
 * - players are added if their name isn't already present
 * - matches are added if no match exists for that date + opponent
 * - an existing match gets seed results only if it has none
 * - an existing match gets seed stat lines only if it has none
 *
 * This lets an updated APK (with fresh season data baked in) install over the
 * old one and pick up the new matches while keeping local edits intact.
 *
 * Seed match shape:
 * {
 *   "date": "2025-08-29", "opponent": "Wisconsin", "season": "2025",
 *   "teamSets": 2, "opponentSets": 3,
 *   "setScores": "16-25, 25-18, 18-25, 28-26, 10-15",
 *   "lines": [{"player": "<player name>", "sp": 5, "k": 15, "e": 6, "ta": 36,
 *              "a": 1, "sa": 0, "se": 2, "d": 3, "bs": 0, "ba": 3,
 *              "re": 0, "bhe": 0}]
 * }
 */
object Seeder {

    suspend fun sync(context: Context, dao: JayhawksDao) {
        val json = runCatching {
            context.assets.open("seed.json").bufferedReader().use { it.readText() }
        }.getOrNull() ?: return

        runCatching { merge(JSONObject(json), dao) }
    }

    private fun matchKey(date: String, opponent: String) = "$date|${opponent.lowercase()}"

    /** Also used by [SeasonSync] for network-fetched season data. */
    suspend fun merge(root: JSONObject, dao: JayhawksDao) {
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
                val existing = existingByKey[name.lowercase()]
                if (existing == null) {
                    playerIdsByKey[name.lowercase()] = dao.insertPlayer(
                        Player(
                            name = name,
                            jerseyNumber = jersey,
                            position = position,
                            height = height,
                            active = active
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
                        active = active
                    )
                    if (updated != existing) dao.updatePlayer(updated)
                }
            }
        }

        // Tournament weekends can put two matches on nearby dates, so matches
        // are keyed by date + opponent rather than date alone.
        val matchesByKey = dao.matchesOnce().associateBy { matchKey(it.date, it.opponent) }
        val matchesWithLines = dao.statLinesOnce().map { it.matchId }.toSet()

        val matches = root.optJSONArray("matches") ?: return
        for (i in 0 until matches.length()) {
            val m = matches.getJSONObject(i)
            val date = m.getString("date")
            val opponent = m.getString("opponent")
            val seedTeamSets = if (m.has("teamSets")) m.getInt("teamSets") else null
            val seedOppSets = if (m.has("opponentSets")) m.getInt("opponentSets") else null
            val seedSetScores = m.optString("setScores").takeIf { it.isNotBlank() }

            val seedHome = if (m.has("home")) m.getBoolean("home") else null
            val seedNeutral = m.optBoolean("neutral")
            val seedVenue = m.optString("venue")
            val seedCity = m.optString("city")

            val existing = matchesByKey[matchKey(date, opponent)]
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
                        city = seedCity
                    )
                )
            } else {
                matchId = existing.id
                val fillResult = existing.teamSets == null && existing.opponentSets == null &&
                    (seedTeamSets != null || seedOppSets != null)
                // Venue facts fill in when missing (an upcoming match becoming a
                // played one learns where it happened) but never overwrite.
                val updated = existing.copy(
                    teamSets = if (fillResult) seedTeamSets else existing.teamSets,
                    opponentSets = if (fillResult) seedOppSets else existing.opponentSets,
                    setScores = existing.setScores ?: seedSetScores,
                    home = existing.home ?: seedHome,
                    neutral = existing.neutral || seedNeutral,
                    venue = existing.venue.ifBlank { seedVenue },
                    city = existing.city.ifBlank { seedCity }
                )
                if (updated != existing) dao.updateMatch(updated)
            }

            // Opponent lines and team totals are merged before the KU stat-line
            // guard below: a match whose KU lines are already recorded still
            // needs its opposing box score the first time one shows up.
            mergeOpponentBox(m, matchId, dao)

            if (existing != null && matchId in matchesWithLines) continue
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
                        ballHandlingErrors = l.optInt("bhe")
                    )
                )
            }
        }

        mergeStandings(root, dao)
        mergeOpponentRosters(root, dao)
    }

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
                            height = p.optString("height")
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
                        rpiRank = s.optInt("rpiRank").takeIf { it > 0 }
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
