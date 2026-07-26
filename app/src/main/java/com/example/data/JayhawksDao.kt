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
}
