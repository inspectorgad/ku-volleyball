package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.JayhawksDatabase
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
class MatchGoalsMergeTest {

    private lateinit var db: JayhawksDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, JayhawksDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    /** Three goals: a floor, a ceiling, and one belonging to a role. */
    private val defs = """
        [{"name":"Kills/set","group":"team","target":15.0,"decimals":2,"ceiling":false},
         {"name":"Errors/set","group":"team","target":7.5,"decimals":2,"ceiling":true},
         {"name":"L1 kill %","group":"role","role":"L1","target":0.42,"decimals":3,"ceiling":false}]
    """

    private fun seed(values: String, roles: String = """{"L1":"Grace Nelson"}""") = JSONObject(
        """
        {"players": [], "goalDefinitions": $defs,
         "matches": [{"date":"2026-09-20","opponent":"Grand Canyon","season":"2026",
                      "teamSets":3,"opponentSets":0,
                      "goals":{"met":2,"evaluated":3,"teamMet":1,"teamEvaluated":2,
                               "values":$values,"roles":$roles}}]}
        """
    )

    @Test
    fun `definitions and values are joined by position`() = runTest {
        Seeder.merge(seed("[16.0, 9.33, 0.368]"), db.dao())
        val rows = db.dao().matchGoalsOnce()
        assertEquals(3, rows.size)
        assertEquals(listOf("Kills/set", "Errors/set", "L1 kill %"), rows.map { it.name })
        assertEquals(listOf(16.0, 9.33, 0.368), rows.map { it.value })
        assertEquals(listOf(15.0, 7.5, 0.42), rows.map { it.target })
    }

    @Test
    fun `met is the value against the target, and a ceiling reads the other way`() = runTest {
        Seeder.merge(seed("[16.0, 9.33, 0.368]"), db.dao())
        val byName = db.dao().matchGoalsOnce().associateBy { it.name }
        // 16.0 clears a floor of 15.0.
        assertEquals(true, byName["Kills/set"]!!.met)
        // 9.33 is over a ceiling of 7.5, so it is missed - not met for being high.
        assertEquals(false, byName["Errors/set"]!!.met)
        assertEquals(false, byName["L1 kill %"]!!.met)
    }

    @Test
    fun `a goal with nothing to measure is stored, but is neither met nor missed`() = runTest {
        Seeder.merge(seed("[16.0, 9.33, null]"), db.dao())
        val role = db.dao().matchGoalsOnce().single { it.name == "L1 kill %" }
        assertNull(role.value)
        // The distinction that matters: not measured is not the same as missed.
        assertNull(role.met)
    }

    @Test
    fun `a role goal carries whoever filled the role, a team goal nobody`() = runTest {
        Seeder.merge(seed("[16.0, 9.33, 0.368]"), db.dao())
        val rows = db.dao().matchGoalsOnce().associateBy { it.name }
        assertEquals("Grace Nelson", rows["L1 kill %"]!!.player)
        assertEquals("L1", rows["L1 kill %"]!!.role)
        assertEquals("", rows["Kills/set"]!!.player)
    }

    @Test
    fun `a feed whose lists disagree in length writes nothing rather than mispairing`() =
        runTest {
            // Pairing by position is only safe while the two lists match. One
            // value short and every goal after it would show somebody else's
            // number against its target - and look entirely plausible doing it.
            Seeder.merge(seed("[16.0, 9.33]"), db.dao())
            assertTrue(db.dao().matchGoalsOnce().isEmpty())
        }

    @Test
    fun `a resync replaces the match's goals rather than doubling them`() = runTest {
        val dao = db.dao()
        Seeder.merge(seed("[16.0, 9.33, 0.368]"), dao)
        Seeder.merge(seed("[18.0, 6.0, 0.500]"), dao)
        val rows = dao.matchGoalsOnce()
        assertEquals(3, rows.size)
        assertEquals(listOf(18.0, 6.0, 0.5), rows.map { it.value })
        assertEquals(true, rows.single { it.name == "Errors/set" }.met)
    }

    @Test
    fun `a seed with no goal definitions leaves the table empty rather than failing`() =
        runTest {
            Seeder.merge(
                JSONObject(
                    """{"players": [], "matches":[{"date":"2026-09-20","opponent":"Grand Canyon",
                        "season":"2026","teamSets":3,"opponentSets":0}]}"""
                ),
                db.dao()
            )
            assertTrue(db.dao().matchGoalsOnce().isEmpty())
            assertEquals(1, db.dao().matchesOnce().size)
        }
}
