package com.example

import com.example.data.normTeam
import com.example.data.sameTeam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The spellings that actually occur, and the pairs that actually broke.
 *
 * Six opponents' rosters were collected in full and unreachable on the phone
 * because the screens looked them up by a name that did not match the one the
 * roster was filed under. Every pair below is one of those.
 */
class TeamNamesTest {

    @Test
    fun `the six spellings that hid a collected roster all match`() {
        // left: how the schedule writes it. right: how the roster is filed.
        val pairs = listOf(
            "Arizona State" to "Arizona St.",
            "Iowa State" to "Iowa St.",
            "Kansas State" to "Kansas St.",
            "Wichita St." to "Wichita State",
            "South Dakota St." to "South Dakota State",
            "Florida St." to "Florida State"
        )
        for ((schedule, roster) in pairs) {
            assertTrue("$schedule should find the roster filed as $roster", sameTeam(schedule, roster))
        }
    }

    @Test
    fun `a poll rank and an exhibition marker are not part of the name`() {
        assertTrue(sameTeam("#12 Arizona State", "Arizona St."))
        assertTrue(sameTeam("Creighton (Exh.)", "Creighton"))
        assertTrue(sameTeam("Texas (14)", "Texas"))
    }

    @Test
    fun `case and spacing do not matter`() {
        assertTrue(sameTeam("  west   virginia ", "West Virginia"))
        assertTrue(sameTeam("TCU", "tcu"))
    }

    @Test
    fun `different schools stay different`() {
        // The normaliser must not fold "state" so eagerly that two real schools
        // collide - these are four separate programmes.
        assertFalse(sameTeam("Kansas", "Kansas St."))
        assertFalse(sameTeam("Arizona", "Arizona St."))
        assertFalse(sameTeam("Iowa", "Iowa St."))
        assertFalse(sameTeam("Texas", "Texas Tech"))
    }

    @Test
    fun `the key is stable, so it can be used as a map key`() {
        assertEquals(normTeam("Arizona State"), normTeam("#12 arizona st."))
        assertEquals("arizona st", normTeam("Arizona State"))
        assertEquals("ole miss", normTeam("Ole Miss"))
    }
}
