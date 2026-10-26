package com.example

import com.example.data.Seeder
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When the season data bundled in the app gives way to the newer copy the
 * phone already took from the online feed, on Android and iPhone alike.
 */
class SeederStampTest {

    @Test
    fun a_bundled_copy_older_than_the_feed_is_held_back() {
        assertTrue(Seeder.olderThan("2026-10-16T06:18:53Z", "2026-10-25T20:05:31Z"))
        assertTrue(Seeder.olderThan("2026-10-25T20:05:30Z", "2026-10-25T20:05:31Z"))
    }

    @Test
    fun the_same_or_a_newer_copy_is_applied() {
        assertFalse(Seeder.olderThan("2026-10-25T20:05:31Z", "2026-10-25T20:05:31Z"))
        assertFalse(Seeder.olderThan("2026-10-26T12:40:02Z", "2026-10-25T20:05:31Z"))
    }

    @Test
    fun a_missing_or_unreadable_stamp_never_holds_it_back() {
        assertFalse(Seeder.olderThan("2026-10-16T06:18:53Z", null))
        assertFalse(Seeder.olderThan(null, "2026-10-25T20:05:31Z"))
        assertFalse(Seeder.olderThan("", "2026-10-25T20:05:31Z"))
        assertFalse(Seeder.olderThan("2026-10-16", "2026-10-25T20:05:31Z"))
        assertFalse(Seeder.olderThan("2026-10-16T06:18:53Z", "Oct 25"))
    }
}
