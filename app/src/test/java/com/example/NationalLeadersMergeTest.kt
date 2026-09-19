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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NationalLeadersMergeTest {

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

    private fun seed(categories: String, season: String = "2026") = JSONObject(
        """
        {"players": [], "matches": [],
         "nationalLeaders": {"season":"$season","updated":"Sep 15, 2026",
                             "categories": [$categories]}}
        """
    )

    private val killsPerSet = """
        {"id":"2","name":"Kills Per Set","valueLabel":"Per Set","rows":[
          {"rank":1,"player":"Jane Doe","team":"LSU","cls":"Sr.","height":"6-2",
           "position":"OH","sets":33,"value":"5.70"},
          {"rank":2,"player":"Ayah Elnady","team":"Kansas","cls":"Jr.","height":"6-0",
           "position":"OH","sets":31,"value":"4.90"}]}
    """

    @Test
    fun `a category's rows land with their rank, team and formatted value`() = runTest {
        Seeder.merge(seed(killsPerSet), db.dao())
        val rows = db.dao().nationalLeadersOnce()
        assertEquals(2, rows.size)
        val ku = rows.single { it.team == "Kansas" }
        assertEquals(2, ku.rank)
        assertEquals("Ayah Elnady", ku.player)
        assertEquals("Kills Per Set", ku.category)
        // The value stays a string: it is already formatted the way the
        // category wants, and the ordering is carried by the rank.
        assertEquals("4.90", ku.value)
        assertEquals("Per Set", ku.valueLabel)
        assertEquals(31, ku.sets)
    }

    @Test
    fun `a later snapshot replaces the season rather than piling up`() = runTest {
        val dao = db.dao()
        Seeder.merge(seed(killsPerSet), dao)
        // Elnady has since dropped off the list and someone else has taken the
        // second spot. The old row must leave with her.
        Seeder.merge(
            seed(
                """{"id":"2","name":"Kills Per Set","valueLabel":"Per Set","rows":[
                     {"rank":1,"player":"Jane Doe","team":"LSU","value":"5.80"},
                     {"rank":2,"player":"Mary Smith","team":"Texas","value":"5.10"}]}"""
            ),
            dao
        )
        val rows = dao.nationalLeadersOnce()
        assertEquals(2, rows.size)
        assertTrue(rows.none { it.player == "Ayah Elnady" })
        assertEquals("5.80", rows.single { it.rank == 1 }.value)
    }

    @Test
    fun `categories are kept apart, and a rank is only unique within one`() = runTest {
        Seeder.merge(
            seed(
                killsPerSet + "," +
                    """{"id":"42","name":"Aces Per Set","valueLabel":"Per Set","rows":[
                         {"rank":1,"player":"Ann Ace","team":"Harvard","value":"0.75"},
                         {"rank":2,"player":"Ayah Elnady","team":"Kansas","value":"0.61"}]}"""
            ),
            db.dao()
        )
        val rows = db.dao().nationalLeadersOnce()
        assertEquals(4, rows.size)
        assertEquals(2, rows.count { it.rank == 1 })
        assertEquals(setOf("Kills Per Set", "Aces Per Set"), rows.map { it.category }.toSet())
        // Ordered by the query, so the UI's chips and lists are stable.
        assertEquals(listOf(1, 2, 1, 2), rows.map { it.rank })
    }

    @Test
    fun `a seed without the section leaves the table alone`() = runTest {
        val dao = db.dao()
        Seeder.merge(seed(killsPerSet), dao)
        Seeder.merge(JSONObject("""{"players": [], "matches": []}"""), dao)
        assertEquals(2, dao.nationalLeadersOnce().size)
    }

    @Test
    fun `rows without a rank or a name are dropped, not stored blank`() = runTest {
        Seeder.merge(
            seed(
                """{"id":"1","name":"Hitting Percentage","valueLabel":"Pct.","rows":[
                     {"rank":1,"player":"Jane Doe","team":"LSU","value":".552"},
                     {"rank":2,"player":"","team":"Nowhere","value":".500"},
                     {"player":"No Rank","team":"Nowhere","value":".499"}]}"""
            ),
            db.dao()
        )
        assertEquals(listOf("Jane Doe"), db.dao().nationalLeadersOnce().map { it.player })
    }
}
