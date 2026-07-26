package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.JayhawksDatabase
import com.example.data.Match
import com.example.data.Seeder
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HomeAwayTest {

    private lateinit var db: JayhawksDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), JayhawksDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `versus notation follows home away neutral`() {
        assertEquals("vs", Match(date = "2025-11-06", opponent = "Colorado", season = "2025", home = true).versus)
        assertEquals("at", Match(date = "2025-10-16", opponent = "Houston", season = "2025", home = false).versus)
        // A neutral-site game is written "vs", not "at" — KU never travelled there.
        assertEquals(
            "vs",
            Match(
                date = "2025-08-25", opponent = "Penn St.", season = "2025",
                home = false, neutral = true
            ).versus
        )
        // Unknown (hand-added) falls back to "vs" rather than claiming a road game.
        assertEquals("vs", Match(date = "2026-01-01", opponent = "Someone", season = "2026").versus)
    }

    @Test
    fun `merge carries home away and venue through`() = runTest {
        Seeder.merge(
            JSONObject(
                """{"players":[],"matches":[
                    {"date":"2025-08-25","opponent":"Penn St.","season":"2025","teamSets":1,
                     "opponentSets":3,"home":false,"neutral":true,
                     "venue":"Sanford Pentagon","city":"Sioux Falls, SD"},
                    {"date":"2025-11-06","opponent":"Colorado","season":"2025","teamSets":3,
                     "opponentSets":1,"home":true,"venue":"Horejsi Family Volleyball Arena",
                     "city":"Lawrence, KS"}]}"""
            ),
            db.dao()
        )
        val byOpponent = db.dao().matchesOnce().associateBy { it.opponent }
        val neutral = byOpponent.getValue("Penn St.")
        assertEquals(false, neutral.home)
        assertTrue(neutral.neutral)
        assertEquals("Sioux Falls, SD", neutral.city)
        assertEquals("vs", neutral.versus)
        val home = byOpponent.getValue("Colorado")
        assertEquals(true, home.home)
        assertEquals("vs", home.versus)
    }

    @Test
    fun `an upcoming match keeps its home flag when the result arrives`() = runTest {
        val dao = db.dao()
        // kuathletics supplies home/away before the match is played.
        Seeder.merge(
            JSONObject(
                """{"players":[],"matches":[{"date":"2026-08-28","opponent":"Pittsburgh",
                    "season":"2026","home":false}]}"""
            ),
            dao
        )
        assertEquals(false, dao.matchesOnce().single().home)
        // Later the NCAA result lands with venue detail; the flag must survive.
        Seeder.merge(
            JSONObject(
                """{"players":[],"matches":[{"date":"2026-08-28","opponent":"Pittsburgh",
                    "season":"2026","teamSets":3,"opponentSets":1,"home":false,
                    "venue":"Fitzgerald Field House","city":"Pittsburgh, PA"}]}"""
            ),
            dao
        )
        val m = dao.matchesOnce().single()
        assertEquals(false, m.home)
        assertEquals(3, m.teamSets)
        assertEquals("Pittsburgh, PA", m.city)
        assertEquals("at", m.versus)
    }

    @Test
    fun `a seed without home data leaves the flag unknown`() = runTest {
        Seeder.merge(
            JSONObject(
                """{"players":[],"matches":[{"date":"2025-09-21","opponent":"Creighton",
                    "season":"2025","teamSets":0,"opponentSets":3}]}"""
            ),
            db.dao()
        )
        assertNull(db.dao().matchesOnce().single().home)
    }
}
