package com.example.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

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
