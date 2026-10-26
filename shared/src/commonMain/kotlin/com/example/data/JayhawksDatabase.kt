package com.example.data

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor

@Database(
    entities = [Player::class, Match::class, StatLine::class,
        ConferenceStanding::class, PollEntry::class,
        OpponentStatLine::class, MatchTeamStats::class,
        OpponentRosterEntry::class, OpponentSeasonStat::class,
        TeamServing::class, NationalLeader::class, MatchGoal::class],
    version = 17,
    exportSchema = false
)
@ConstructedBy(JayhawksDatabaseConstructor::class)
abstract class JayhawksDatabase : RoomDatabase() {
    abstract fun dao(): JayhawksDao

    // Each phone opens the database its own way: JayhawksDatabase.get(context)
    // on Android (androidMain), createDatabase() on iPhone (iosMain).
    companion object
}

/** Builds the database class on every platform; Room's compiler writes each platform's version. */
@Suppress("KotlinNoActualForExpect")
expect object JayhawksDatabaseConstructor : RoomDatabaseConstructor<JayhawksDatabase> {
    override fun initialize(): JayhawksDatabase
}
