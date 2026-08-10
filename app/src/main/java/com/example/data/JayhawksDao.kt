package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface JayhawksDao {

    // Players
    @Query("SELECT * FROM players ORDER BY name COLLATE NOCASE")
    fun observePlayers(): Flow<List<Player>>

    @Query("SELECT * FROM players")
    suspend fun playersOnce(): List<Player>

    @Query("SELECT * FROM matches")
    suspend fun matchesOnce(): List<Match>

    @Query("SELECT * FROM stat_lines")
    suspend fun statLinesOnce(): List<StatLine>

    @Insert
    suspend fun insertPlayer(player: Player): Long

    @Update
    suspend fun updatePlayer(player: Player)

    @Delete
    suspend fun deletePlayer(player: Player)

    // Matches
    @Query("SELECT * FROM matches ORDER BY date DESC, id DESC")
    fun observeMatches(): Flow<List<Match>>

    @Insert
    suspend fun insertMatch(match: Match): Long

    @Update
    suspend fun updateMatch(match: Match)

    @Delete
    suspend fun deleteMatch(match: Match)

    // Stat lines
    @Query("SELECT * FROM stat_lines")
    fun observeStatLines(): Flow<List<StatLine>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStatLine(line: StatLine): Long

    @Delete
    suspend fun deleteStatLine(line: StatLine)

    // Big 12 standings and poll snapshots. Scraper-owned derived data with no
    // user-entered fields, so each sync replaces a season's rows outright —
    // otherwise a team dropping out of the conference would linger forever.
    @Query("SELECT * FROM standings")
    fun observeStandings(): Flow<List<ConferenceStanding>>

    @Query("SELECT * FROM standings")
    suspend fun standingsOnce(): List<ConferenceStanding>

    @Query("DELETE FROM standings WHERE season = :season")
    suspend fun deleteStandingsForSeason(season: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStandings(rows: List<ConferenceStanding>)

    @Query("SELECT * FROM poll_entries")
    fun observePollEntries(): Flow<List<PollEntry>>

    @Query("SELECT * FROM poll_entries")
    suspend fun pollEntriesOnce(): List<PollEntry>

    @Query("DELETE FROM poll_entries WHERE season = :season")
    suspend fun deletePollForSeason(season: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPollEntries(rows: List<PollEntry>)

    // Opposing box scores and both sides' team totals. Scraper-owned like the
    // standings above, so a sync replaces a match's rows rather than gap-filling.
    @Query("SELECT * FROM opponent_stat_lines")
    fun observeOpponentStatLines(): Flow<List<OpponentStatLine>>

    @Query("SELECT * FROM opponent_stat_lines")
    suspend fun opponentStatLinesOnce(): List<OpponentStatLine>

    @Query("DELETE FROM opponent_stat_lines WHERE matchId = :matchId")
    suspend fun deleteOpponentStatLinesForMatch(matchId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOpponentStatLines(rows: List<OpponentStatLine>)

    @Query("SELECT * FROM match_team_stats")
    fun observeMatchTeamStats(): Flow<List<MatchTeamStats>>

    @Query("SELECT * FROM match_team_stats")
    suspend fun matchTeamStatsOnce(): List<MatchTeamStats>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMatchTeamStats(rows: List<MatchTeamStats>)

    // Opponents' published rosters and season form. Scraper-owned; a sync
    // replaces a team's rows so a departed player does not linger.
    @Query("SELECT * FROM opponent_roster")
    fun observeOpponentRoster(): Flow<List<OpponentRosterEntry>>

    @Query("SELECT * FROM opponent_roster")
    suspend fun opponentRosterOnce(): List<OpponentRosterEntry>

    @Query("DELETE FROM opponent_roster WHERE team = :team")
    suspend fun deleteOpponentRosterForTeam(team: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOpponentRoster(rows: List<OpponentRosterEntry>)

    @Query("SELECT * FROM opponent_season_stats")
    fun observeOpponentSeasonStats(): Flow<List<OpponentSeasonStat>>

    @Query("SELECT * FROM opponent_season_stats")
    suspend fun opponentSeasonStatsOnce(): List<OpponentSeasonStat>

    @Query("DELETE FROM opponent_season_stats WHERE team = :team")
    suspend fun deleteOpponentSeasonStatsForTeam(team: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOpponentSeasonStats(rows: List<OpponentSeasonStat>)
}
