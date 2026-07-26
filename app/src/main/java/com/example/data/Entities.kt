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

@Entity(tableName = "players")
data class Player(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val jerseyNumber: String = "",
    val position: String = "",
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
    val setScores: String? = null
)

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
)
