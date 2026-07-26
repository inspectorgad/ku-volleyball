package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Player::class, Match::class, StatLine::class,
        ConferenceStanding::class, PollEntry::class],
    version = 4,
    exportSchema = false
)
abstract class JayhawksDatabase : RoomDatabase() {
    abstract fun dao(): JayhawksDao

    companion object {
        @Volatile
        private var instance: JayhawksDatabase? = null

        // v1 -> v2: players gained the active (on current roster) flag.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE players ADD COLUMN active INTEGER NOT NULL DEFAULT 1")
            }
        }

        // v2 -> v3: Big 12 standings and national poll snapshots.
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS standings (
                        season TEXT NOT NULL, seo TEXT NOT NULL, team TEXT NOT NULL,
                        confW INTEGER NOT NULL, confL INTEGER NOT NULL,
                        overallW INTEGER NOT NULL, overallL INTEGER NOT NULL,
                        nationalRank INTEGER, rpiRank INTEGER,
                        PRIMARY KEY(season, seo))"""
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS poll_entries (
                        season TEXT NOT NULL, team TEXT NOT NULL, rank INTEGER NOT NULL,
                        rankLabel TEXT NOT NULL, record TEXT NOT NULL, points TEXT NOT NULL,
                        previous TEXT NOT NULL, firstPlaceVotes INTEGER NOT NULL,
                        big12 INTEGER NOT NULL, pollName TEXT NOT NULL, updated TEXT NOT NULL,
                        PRIMARY KEY(season, team))"""
                )
            }
        }

        // v3 -> v4: home/away/neutral and the venue it was decided from.
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE matches ADD COLUMN home INTEGER")
                db.execSQL("ALTER TABLE matches ADD COLUMN neutral INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE matches ADD COLUMN venue TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE matches ADD COLUMN city TEXT NOT NULL DEFAULT ''")
            }
        }

        fun get(context: Context): JayhawksDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    JayhawksDatabase::class.java,
                    "ku_volleyball.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build().also { instance = it }
            }
    }
}
