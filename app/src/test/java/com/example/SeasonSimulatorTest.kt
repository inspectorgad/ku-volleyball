package com.example

import com.example.data.SeasonSimulator
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The same fixture and the same pinned numbers as docs/sim.test.mjs: the app
 * and the dashboard must give identical answers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SeasonSimulatorTest {

    private val fixture = SeasonSimulator.parse(
        JSONObject(
            """
            {"confTotal": 6,
             "kansas": {"confW": 1, "confL": 1, "overallW": 5, "overallL": 2, "rating": 74},
             "remaining": [
               {"date": "2026-10-11", "opponent": "UCF", "venue": "A", "conference": true, "key": "ucf", "p": 0.6},
               {"date": "2026-10-18", "opponent": "TCU", "venue": "H", "conference": true, "key": "tcu", "p": 0.3},
               {"date": "2026-10-25", "opponent": "Baylor", "venue": "A", "conference": true, "key": "baylor", "p": 0.5},
               {"date": "2026-11-01", "opponent": "Utah", "venue": "H", "conference": true, "key": "utah", "p": 0.7}],
             "others": [
               {"team": "TCU", "key": "tcu", "confW": 2, "confL": 0, "remaining": 3, "pVsAverage": 0.75},
               {"team": "UCF", "key": "ucf", "confW": 1, "confL": 1, "remaining": 3, "pVsAverage": 0.45},
               {"team": "Baylor", "key": "baylor", "confW": 1, "confL": 1, "remaining": 3, "pVsAverage": 0.5},
               {"team": "Utah", "key": "utah", "confW": 0, "confL": 2, "remaining": 3, "pVsAverage": 0.4}],
             "rpi": {"wins": 5, "played": 7, "owp": 0.6, "oowp": 0.55,
                     "field": [0.72, 0.7, 0.66, 0.64, 0.6, 0.58], "atLargeCutoff": 3}}
            """
        )
    )!!

    @Test
    fun `matches the dashboard's simulator to the last run`() {
        val r = SeasonSimulator.simulate(fixture, runs = 5000)
        assertEquals(7, r.winsMid)
        assertEquals(11, r.games)
        assertEquals(0.0956, r.title, 1e-12)
        assertEquals(0.8908, r.top4, 1e-12)
        assertEquals(0.1586, r.shareOfTitle, 1e-12)
        assertEquals(7.0946, r.expectedWins, 1e-9)
        assertEquals(6, r.rpiRankMid)
        assertEquals(0.2848, SeasonSimulator.simulate(fixture, mapOf(1 to true), 5000).title, 1e-12)
    }

    @Test
    fun `forcing results moves the odds the right way`() {
        val base = SeasonSimulator.simulate(fixture, runs = 5000)
        val win = SeasonSimulator.simulate(fixture, mapOf(1 to true), 5000)
        val loss = SeasonSimulator.simulate(fixture, mapOf(1 to false), 5000)
        assertTrue(win.title > base.title && base.title > loss.title)
        val all = SeasonSimulator.simulate(fixture, (0..3).associateWith { true }, 2000)
        assertEquals(9, all.winsLow)
        assertEquals(9, all.winsHigh)
    }

    @Test
    fun `chances read in words at the ends`() {
        assertEquals("under 1%", SeasonSimulator.chance(0.001))
        assertEquals("over 99%", SeasonSimulator.chance(0.999))
        assertEquals("37%", SeasonSimulator.chance(0.374))
    }
}
