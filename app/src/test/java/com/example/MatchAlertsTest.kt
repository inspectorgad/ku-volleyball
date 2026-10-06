package com.example

import com.example.data.Match
import com.example.data.MatchAlerts
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MatchAlertsTest {

    // The real feed for Kansas-Utah on 2026-10-01: FINAL, three set scores
    // Kansas all won, and a sets-won field of 2-0.
    private val game = JSONObject(
        javaClass.classLoader!!.getResource("ncaa-game-utah-2026-10-01.json").readText()
    )
    private val info = game.getJSONObject("info")
    private val box = game.getJSONObject("box")
    private val kuNames = setOf("taylor stanley", "grace nelson", "reese messer", "aisha aiono", "reese ptacek")

    @Test
    fun `sets are counted from the set scores, so the 2-0 glitch reads 3-0`() {
        val live = MatchAlerts.live(info, box, kuNames)!!
        assertEquals("Utah", live.opponent)
        assertEquals(listOf(32 to 30, 25 to 20, 26 to 24), live.sets)
        assertEquals(3, live.kuSets)
        assertEquals(0, live.oppSets)
        assertTrue(live.final)
    }

    @Test
    fun `the final alert names the score, the sets and the top performers`() {
        val live = MatchAlerts.live(info, box, kuNames)!!
        val (title, body) = MatchAlerts.finalMessage(live, box, kuNames)
        assertEquals("Final: Kansas def. Utah 3-0", title)
        assertEquals("32-30, 25-20, 26-24\nStanley 13 K (.132) · Nelson 12 D · Messer 36 A · Aiono 3.5 BLK", body)
    }

    @Test
    fun `a set alert says who took it and where the match stands`() {
        // The same match one set later than the first: two sets in the line score.
        val contest = info.getJSONArray("contests").getJSONObject(0)
        val partial = JSONObject(info.toString())
        val c = partial.getJSONArray("contests").getJSONObject(0)
        c.put("gameState", "I").put("currentPeriod", "3RD")
        val lines = contest.getJSONArray("linescores")
        c.put("linescores", org.json.JSONArray().put(lines.get(0)).put(lines.get(1))
            .put(JSONObject().put("period", 3).put("home", "4").put("visit", "6")))
        val live = MatchAlerts.live(partial, box, kuNames)!!
        assertFalse(live.final)
        assertEquals(2, live.finished)
        assertEquals("Kansas wins set 2, 25-20" to "Kansas leads 2-0 vs Utah", MatchAlerts.setMessage(live))
    }

    @Test
    fun `the window opens 15 minutes before a Central first serve and closes four hours after`() {
        val m = Match(id = 1, date = "2026-10-11", opponent = "UCF", season = "2026", time = "12:00")
        // 12:00 CDT is 17:00 UTC.
        val start = MatchAlerts.firstServeUtc("2026-10-11", "12:00")!!
        assertEquals(java.util.TimeZone.getTimeZone("UTC").let {
            java.util.Calendar.getInstance(it).apply { set(2026, 9, 11, 17, 0, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis
        }, start)
        assertNull(MatchAlerts.activeMatch(listOf(m), start - 20 * 60_000))
        assertEquals(m, MatchAlerts.activeMatch(listOf(m), start - 10 * 60_000))
        assertEquals(m, MatchAlerts.activeMatch(listOf(m), start + 3 * 3600_000))
        assertNull(MatchAlerts.activeMatch(listOf(m), start + 5 * 3600_000))
    }

    @Test
    fun `finds Kansas on a scoreboard`() {
        val sb = JSONObject(
            """{"games": [
                {"game": {"gameID": "1", "home": {"names": {"seo": "utah"}}, "away": {"names": {"seo": "byu"}}}},
                {"game": {"gameID": "6625743", "home": {"names": {"seo": "kansas"}}, "away": {"names": {"seo": "byu"}}}}]}"""
        )
        assertEquals("6625743", MatchAlerts.kuGameId(sb))
    }

    @Test
    fun `the widget reads record, rank, next match and last result`() {
        val matches = listOf(
            Match(id = 1, date = "2026-10-01", opponent = "Utah", season = "2026", teamSets = 3, opponentSets = 0, home = true),
            Match(id = 2, date = "2026-10-02", opponent = "BYU", season = "2026", teamSets = 0, opponentSets = 3, home = true),
            Match(id = 3, date = "2026-10-11", opponent = "UCF", season = "2026", home = false, time = "12:00", winProbability = 0.6118)
        )
        val standings = listOf(com.example.data.ConferenceStanding(season = "2026", seo = "kansas", team = "Kansas", confW = 3, confL = 1))
        val poll = listOf(com.example.data.PollEntry(season = "2026", team = "Kansas", rank = 19, rankLabel = "19"))
        val lines = com.example.data.widgetLines(matches, standings, poll, "2026-10-06")
        assertEquals("1-1 · Big 12 3-1 · #19 AVCA", lines.record)
        assertEquals("Next: at UCF · Sun Oct 11 · 12:00 PM CT · 61% win", lines.next)
        assertEquals("Last: L 0-3 vs BYU", lines.last)
    }
}
