package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.JayhawksDatabase
import com.example.data.Seeder
import com.example.data.StatLine
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MergeSyncTest {

    private lateinit var db: JayhawksDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, JayhawksDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun seedJson(): JSONObject = JSONObject(
        """
        {
          "players": [{"name": "Ada Alpha", "jerseyNumber": "1", "position": "OH"}],
          "matches": [
            {"date": "2025-08-29", "opponent": "Wisconsin", "season": "2025",
             "teamSets": 2, "opponentSets": 3,
             "setScores": "16-25, 25-18, 18-25, 28-26, 10-15",
             "lines": [{"player": "Ada Alpha", "sp": 5, "k": 15, "e": 6, "ta": 36,
                        "a": 1, "sa": 0, "se": 2, "d": 3, "bs": 0, "ba": 3,
                        "re": 0, "bhe": 0}]}
          ]
        }
        """
    )

    @Test
    fun `merge into empty database inserts everything`() = runTest {
        Seeder.merge(seedJson(), db.dao())
        assertEquals(1, db.dao().playersOnce().size)
        val match = db.dao().matchesOnce().single()
        assertEquals(2, match.teamSets)
        assertEquals(3, match.opponentSets)
        assertEquals("16-25, 25-18, 18-25, 28-26, 10-15", match.setScores)
        val line = db.dao().statLinesOnce().single()
        assertEquals(15, line.kills)
        assertEquals(36, line.attackAttempts)
        assertEquals(3, line.blockAssists)
    }

    @Test
    fun `merge is idempotent`() = runTest {
        Seeder.merge(seedJson(), db.dao())
        Seeder.merge(seedJson(), db.dao())
        assertEquals(1, db.dao().playersOnce().size)
        assertEquals(1, db.dao().matchesOnce().size)
        assertEquals(1, db.dao().statLinesOnce().size)
    }

    @Test
    fun `two matches on the same date but different opponents both merge`() = runTest {
        val json = seedJson().apply {
            getJSONArray("matches").put(
                JSONObject(
                    """{"date": "2025-08-29", "opponent": "Creighton", "season": "2025",
                        "teamSets": 3, "opponentSets": 0}"""
                )
            )
        }
        Seeder.merge(json, db.dao())
        assertEquals(2, db.dao().matchesOnce().size)
    }

    @Test
    fun `merge fills result of an existing resultless match but never changes an existing result`() =
        runTest {
            val dao = db.dao()
            dao.insertMatch(
                com.example.data.Match(date = "2025-08-29", opponent = "Wisconsin", season = "2025")
            )
            Seeder.merge(seedJson(), dao)
            assertEquals(2, dao.matchesOnce().single().teamSets)

            // A second merge with a different result must NOT overwrite.
            val altered = seedJson().apply {
                getJSONArray("matches").getJSONObject(0).put("teamSets", 3)
            }
            Seeder.merge(altered, dao)
            assertEquals(2, dao.matchesOnce().single().teamSets)
        }

    @Test
    fun `merge never adds lines to a match that already has any`() = runTest {
        val dao = db.dao()
        Seeder.merge(seedJson(), dao)
        val match = dao.matchesOnce().single()
        val player = dao.playersOnce().single()
        // User records their own corrected line set: one line only.
        dao.statLinesOnce().forEach { dao.deleteStatLine(it) }
        dao.upsertStatLine(
            StatLine(playerId = player.id, matchId = match.id, setsPlayed = 5, kills = 20)
        )

        Seeder.merge(seedJson(), dao)
        val lines = dao.statLinesOnce()
        assertEquals(1, lines.size)
        assertEquals(20, lines.single().kills)
    }

    @Test
    fun `unknown player in lines is skipped without error`() = runTest {
        val json = seedJson().apply {
            getJSONArray("matches").getJSONObject(0).getJSONArray("lines").getJSONObject(0)
                .put("player", "Nobody Known")
        }
        Seeder.merge(json, db.dao())
        assertEquals(0, db.dao().statLinesOnce().size)
        assertEquals(1, db.dao().matchesOnce().size)
    }
}
