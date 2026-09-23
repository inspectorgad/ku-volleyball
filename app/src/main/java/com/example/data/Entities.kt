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
 * A team's cumulative serving for one season: every Big 12 side and everybody
 * who has been in that season's poll, summed from the team totals of every
 * match the scraper has captured.
 *
 * Derived data with no user-entered fields, so sync replaces it wholesale.
 *
 * [serveAttempts] is the reason this is its own table rather than a column on
 * the standings: it lets a team's serving be judged on the textbook
 * denominator, serves taken, rather than per set.
 */
@Entity(tableName = "team_serving", primaryKeys = ["season", "team"])
data class TeamServing(
    val season: String,
    val team: String,
    val matches: Int = 0,
    val sets: Int = 0,
    val serviceAces: Int = 0,
    val serviceErrors: Int = 0,
    val serveAttempts: Int = 0,
    val big12: Boolean = false,
    val pollRank: Int? = null
) {
    /** Aces minus errors: what the serving actually returned. */
    val net: Int get() = serviceAces - serviceErrors

    /**
     * (aces − errors) ÷ serves taken. Nearly every team reads negative, which
     * is the sport rather than a fault: a serve hard enough to trouble a ranked
     * side is hard enough to miss.
     */
    val servingPercentage: Double
        get() = if (serveAttempts == 0) 0.0 else net.toDouble() / serveAttempts

    /** Serve faults per set, which is how the cost is usually quoted. */
    val errorsPerSet: Double
        get() = if (sets == 0) 0.0 else serviceErrors.toDouble() / sets

    val acesPerSet: Double
        get() = if (sets == 0) 0.0 else serviceAces.toDouble() / sets

    /** Serves taken per set: the shape of a team's service load. */
    val attemptsPerSet: Double
        get() = if (sets == 0) 0.0 else serveAttempts.toDouble() / sets
}

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
    /** Serves taken. Only Kansas lines carry it; zero means not recorded. */
    val serveAttempts: Int get() = 0
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
    val city: String = "",
    // How many of the coaching staff's game-by-game performance goals KU met in
    // this match, out of those the box score can settle. Computed by the feed
    // from that match's box score, so unlike the result these are never edited
    // here and a sync always wins. Null for a match with no box score - every
    // fixture not yet played, and the Creighton exhibition.
    val goalsMet: Int? = null,
    val goalsEvaluated: Int? = null,
    // The same count over the ten team goals alone, which is the half that does
    // not depend on who was on the floor.
    val teamGoalsMet: Int? = null,
    val teamGoalsEvaluated: Int? = null,
    // The win model's estimate for a match not yet played, 0..1. Carried on
    // matches with no result only: once one is played the question has been
    // answered on the floor. Subjective power ratings behind it, not an
    // official rating system - scripts/power-ratings.json is the whole model.
    val winProbability: Double? = null,
    // Where the opponent's rating behind that forecast came from: "poll" when
    // the current AVCA poll rates them, "preseason" for a number set by hand
    // before the season. Null alongside a null forecast.
    val ratingSource: String? = null,
    // AVCA ranks as they stood when the match was played, from the NCAA's box
    // score, and NCAA tournament seeds for the postseason. Null when unranked.
    val kuRank: Int? = null,
    val opponentRank: Int? = null,
    val kuSeed: Int? = null,
    val opponentSeed: Int? = null,
    // First serve in Central Time, 24h ("18:00"), and the broadcast, both off
    // the athletics schedule. Empty when not yet announced.
    val time: String = "",
    val tv: String = "",
    // Who is taking the spare ticket for this match. Typed in by hand and owned
    // by this device alone - it is not in the feed and never will be, so a sync
    // has to leave it alone. Empty means nobody is down for it yet.
    val guest: String = ""
) {
    /** Standard notation: "vs" for home and neutral games, "at" on the road. */
    val versus: String get() = if (home == false && !neutral) "at" else "vs"

    /** Share of the match's measurable goals KU met, 0..1, or null if none were. */
    val goalRate: Double?
        get() = goalsEvaluated?.takeIf { it > 0 }?.let { (goalsMet ?: 0).toDouble() / it }
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
    override val ballHandlingErrors: Int = 0,
    override val serveAttempts: Int = 0
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
    // From the opponent's roster page, joined by name — box scores omit height.
    val height: String = "",
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
 * One player on an opposing team's published roster, scraped from that school's
 * own athletics site. This is the only source of an opponent's line-up *before*
 * they have played anyone — the NCAA API has no roster endpoint — and the only
 * source of opposing players' heights, which box scores omit entirely.
 *
 * Keyed by team name rather than an id because that is all the schedule gives
 * us. Scraper-owned, so a sync replaces a team's roster wholesale.
 */
@Entity(tableName = "opponent_roster", primaryKeys = ["team", "playerName"])
data class OpponentRosterEntry(
    val team: String,
    val playerName: String,
    val jerseyNumber: String = "",
    val position: String = "",
    val height: String = ""
)

/**
 * A scheduled opponent's season-to-date production, aggregated from the box
 * scores of their *other* matches — the nightly scoreboard sweep already sees
 * every D1 game, so their season costs nothing extra to follow. Empty until
 * they have played once, which is the gap [OpponentRosterEntry] covers.
 */
@Entity(tableName = "opponent_season_stats", primaryKeys = ["team", "playerName"])
data class OpponentSeasonStat(
    val team: String,
    val playerName: String,
    val jerseyNumber: String = "",
    val position: String = "",
    val matchesPlayed: Int = 0,
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

/**
 * A row of the NCAA's national top 50 for one statistical category.
 *
 * Not derived from our own box scores and it could not be: we capture 34 teams
 * in full and whoever they play, so the national kills leader (LSU) and aces
 * leader (Harvard) never appear in them. The NCAA computes these across all of
 * Division I and publishes the top 50 of each category.
 *
 * [value] stays a string. It is already formatted the way the category wants -
 * "5.70" for a rate, ".552" for a percentage, "154" for a total - and the order
 * is carried by [rank], so nothing here needs it as a number.
 */
@Entity(tableName = "national_leaders", primaryKeys = ["season", "category", "idx"])
data class NationalLeader(
    val season: String,
    val category: String,
    /**
     * Where the row sits in the published list, which is what identifies it.
     *
     * The rank cannot: the NCAA gives tied players the same one - a quarter of
     * these rows are ties - so keying on it would have each tie evict the
     * player it ties with.
     */
    val idx: Int,
    val rank: Int,
    val player: String,
    val team: String,
    val position: String = "",
    val cls: String = "",
    val height: String = "",
    val sets: Int = 0,
    val value: String = "",
    /** What the category calls its headline number: "Per Set", "Pct.", "Kills". */
    val valueLabel: String = "",
    /**
     * How current the list is, in the NCAA's words: "Through games Thursday,
     * September 17, 2026". Shown on the card because the list can go stale
     * with nothing else changing - the NCAA's stats service returned errors
     * for every category on 23 Sep, and the last good copy is kept rather than
     * replaced with nothing, so without this line an old list reads as today's.
     */
    val asOf: String = ""
)

/**
 * One performance goal in one match: the staff's target, and what KU did.
 *
 * The feed keeps these apart - the twenty-two names and targets are published
 * once, and a match carries only its values in that order - because repeating
 * them on all 45 matches cost 200 KB against the 23 KB this shape costs. They
 * are flattened back together here, where 990 rows is nothing and a screen
 * wanting one match's goals should not have to join two lists by index.
 */
@Entity(
    tableName = "match_goals",
    primaryKeys = ["matchId", "idx"],
    foreignKeys = [
        ForeignKey(
            entity = Match::class,
            parentColumns = ["id"],
            childColumns = ["matchId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("matchId")]
)
data class MatchGoal(
    val matchId: Long,
    /** Position in the staff's own ordering, which is what keeps the list stable. */
    val idx: Int,
    val name: String,
    /** "team" for the ten that do not depend on the line-up, "role" for the rest. */
    val goalGroup: String,
    val target: Double,
    /** Null when the match gave this goal nothing to measure. */
    val value: Double? = null,
    /** 2 for a per-set count, 3 for a percentage - how the staff write it. */
    val decimals: Int = 3,
    /** True where the target is a ceiling: errors per set, the opponent's hit %. */
    val ceiling: Boolean = false,
    val role: String = "",
    /** Who filled that role in this match. Empty for a team goal. */
    val player: String = ""
) {
    /** Met, missed, or null when there was nothing to measure. */
    val met: Boolean?
        get() = value?.let { if (ceiling) it <= target else it >= target }
}
