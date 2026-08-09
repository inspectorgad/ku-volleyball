package com.example.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A Big 12 team's record for one season, computed by the scraper from the NCAA
 * scoreboard sweep. Unlike players/matches this is *derived* data with no
 * user-entered fields, so sync replaces it wholesale rather than gap-filling.
 */
@Entity(tableName = "standings", primaryKeys = ["season", "seo"])
data class ConferenceStanding(
    val season: String,
    val seo: String,
    val team: String,
    val confW: Int = 0,
    val confL: Int = 0,
    val overallW: Int = 0,
    val overallL: Int = 0,
    // AVCA national rank as last reported by the scoreboard that season.
    val nationalRank: Int? = null,
    val rpiRank: Int? = null
) {
    val confPct: Double get() = (confW + confL).let { if (it == 0) 0.0 else confW.toDouble() / it }
    val overallPct: Double get() = (overallW + overallL).let { if (it == 0) 0.0 else overallW.toDouble() / it }
}

/**
 * One row of a national poll snapshot (AVCA coaches top 25). The endpoint only
 * serves the current poll, so each season keeps the latest capture — which at
 * season's end is that season's final poll.
 */
@Entity(tableName = "poll_entries", primaryKeys = ["season", "team"])
data class PollEntry(
    val season: String,
    val team: String,
    val rank: Int,
    // Preserves ties as published, e.g. "T-22".
    val rankLabel: String,
    val record: String = "",
    val points: String = "",
    val previous: String = "",
    val firstPlaceVotes: Int = 0,
    val big12: Boolean = false,
    val pollName: String = "",
    val updated: String = ""
)

/**
 * The twelve counting stats a volleyball box score records, shared by Kansas
 * lines, opposing lines, and team totals so all three aggregate and format
 * through the same code in `com.example.stats`.
 */
interface VolleyballLine {
    val setsPlayed: Int
    val kills: Int
    val attackErrors: Int
    val attackAttempts: Int
    val assists: Int
    val serviceAces: Int
    val serviceErrors: Int
    val digs: Int
    val blockSolos: Int
    val blockAssists: Int
    val receptionErrors: Int
    val ballHandlingErrors: Int
}

@Entity(tableName = "players")
data class Player(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val jerseyNumber: String = "",
    val position: String = "",
    // Roster convention, e.g. "6-1". Empty when unknown: only kuathletics.com
    // publishes height and it lists the current roster only, so former players
    // keep whatever height was recorded while they were on it.
    val height: String = "",
    // On the current roster. Maintained by the nightly roster scrape; former
    // players keep their stats but are shown in a separate roster section.
    val active: Boolean = true
)

// Dates are stored as ISO yyyy-MM-dd strings so lexicographic order matches
// chronological order without needing java.time (minSdk 24).
@Entity(tableName = "matches")
data class Match(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val opponent: String,
    val season: String,
    // Volleyball result: sets won by each side (3-1, 3-2, ...). Null until played.
    val teamSets: Int? = null,
    val opponentSets: Int? = null,
    // Per-set points from KU's perspective, e.g. "25-16, 18-25, 25-18, 26-28, 15-10"
    val setScores: String? = null,
    // Null when unknown (a hand-added match). Determined by venue rather than
    // the NCAA's home designation, which marks one side home even at neutral
    // tournament sites.
    val home: Boolean? = null,
    val neutral: Boolean = false,
    val venue: String = "",
    val city: String = ""
) {
    /** Standard notation: "vs" for home and neutral games, "at" on the road. */
    val versus: String get() = if (home == false && !neutral) "at" else "vs"
}

@Entity(
    tableName = "stat_lines",
    foreignKeys = [
        ForeignKey(
            entity = Player::class,
            parentColumns = ["id"],
            childColumns = ["playerId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Match::class,
            parentColumns = ["id"],
            childColumns = ["matchId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("matchId"),
        Index(value = ["playerId", "matchId"], unique = true)
    ]
)
data class StatLine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playerId: Long,
    val matchId: Long,
    override val setsPlayed: Int = 0,
    override val kills: Int = 0,
    override val attackErrors: Int = 0,
    override val attackAttempts: Int = 0,
    override val assists: Int = 0,
    override val serviceAces: Int = 0,
    override val serviceErrors: Int = 0,
    override val digs: Int = 0,
    override val blockSolos: Int = 0,
    override val blockAssists: Int = 0,
    override val receptionErrors: Int = 0,
    override val ballHandlingErrors: Int = 0
) : VolleyballLine

/**
 * One opposing player's line in a single match.
 *
 * Opponents are deliberately *not* rows in [Player]: names collide across teams,
 * and the box scores only ever cover their games against Kansas, so there is no
 * season to aggregate them into and nothing for a KU leaderboard to rank. Number
 * and position are denormalized here for the same reason. Like standings, this
 * is scraper-owned derived data with no user-entered fields, so a sync replaces
 * a match's rows wholesale.
 */
@Entity(
    tableName = "opponent_stat_lines",
    foreignKeys = [
        ForeignKey(
            entity = Match::class,
            parentColumns = ["id"],
            childColumns = ["matchId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["matchId", "playerName"], unique = true)]
)
data class OpponentStatLine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val matchId: Long,
    val playerName: String,
    val jerseyNumber: String = "",
    val position: String = "",
    override val setsPlayed: Int = 0,
    override val kills: Int = 0,
    override val attackErrors: Int = 0,
    override val attackAttempts: Int = 0,
    override val assists: Int = 0,
    override val serviceAces: Int = 0,
    override val serviceErrors: Int = 0,
    override val digs: Int = 0,
    override val blockSolos: Int = 0,
    override val blockAssists: Int = 0,
    override val receptionErrors: Int = 0,
    override val ballHandlingErrors: Int = 0
) : VolleyballLine

/**
 * A match's official team totals for one side. Recorded rather than summed from
 * the player lines because reception errors do not always reconcile: the NCAA
 * charges some to the team instead of to a player, so summing under-reports them.
 * Every other stat does add up. Scraper-owned, replaced on sync.
 */
@Entity(tableName = "match_team_stats", primaryKeys = ["matchId", "opponent"])
data class MatchTeamStats(
    val matchId: Long,
    /** false = Kansas, true = the opponent. */
    val opponent: Boolean,
    override val setsPlayed: Int = 0,
    override val kills: Int = 0,
    override val attackErrors: Int = 0,
    override val attackAttempts: Int = 0,
    override val assists: Int = 0,
    override val serviceAces: Int = 0,
    override val serviceErrors: Int = 0,
    override val digs: Int = 0,
    override val blockSolos: Int = 0,
    override val blockAssists: Int = 0,
    override val receptionErrors: Int = 0,
    override val ballHandlingErrors: Int = 0
) : VolleyballLine
