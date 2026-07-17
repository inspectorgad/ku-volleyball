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
                val active = p.optBoolean("active", true)
                val existing = existingByKey[name.lowercase()]
                if (existing == null) {
                    playerIdsByKey[name.lowercase()] = dao.insertPlayer(
                        Player(
                            name = name,
                            jerseyNumber = jersey,
                            position = position,
                            active = active
                        )
                    )
                } else {
                    // Roster facts (name casing, number, position, current-roster
                    // status) are scraper-owned and refreshed on every sync; blank
                    // seed values never erase what's already there.
                    val updated = existing.copy(
                        name = name,
                        jerseyNumber = jersey.ifBlank { existing.jerseyNumber },
                        position = position.ifBlank { existing.position },
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
                        setScores = seedSetScores
                    )
                )
            } else {
                matchId = existing.id
                if (existing.teamSets == null && existing.opponentSets == null &&
                    (seedTeamSets != null || seedOppSets != null)
                ) {
                    dao.updateMatch(
                        existing.copy(
                            teamSets = seedTeamSets,
                            opponentSets = seedOppSets,
                            setScores = existing.setScores ?: seedSetScores
                        )
                    )
                }
            }

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
