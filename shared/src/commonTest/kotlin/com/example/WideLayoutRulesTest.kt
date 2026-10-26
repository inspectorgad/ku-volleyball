package com.example

import com.example.ui.DETAIL_MIN
import com.example.ui.isWideWindow
import com.example.ui.listPaneWidthDp
import com.example.ui.twoPanesFit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When the app leaves the phone layout for the wide one, and how it splits a
 * wide window, on Android and iPhone alike. Widths are in dp, which are points
 * on iPhone. The iPhone Duo's sizes (466 folded; 669 by 951 or 626 by 890
 * open) are the best estimates available, not Apple's figures.
 */
class WideLayoutRulesTest {

    @Test
    fun upright_phones_and_the_folded_duo_keep_the_phone_layout() {
        // 0, negative and NaN are a window that has not been measured yet.
        listOf(0f, -1f, Float.NaN, 320f, 360f, 393f, 411f, 440f, 448f, 466f, 599.9f).forEach {
            assertFalse(isWideWindow(it), "$it dp should keep the phone layout")
        }
    }

    @Test
    fun the_wide_frame_starts_at_600() {
        listOf(600f, 626f, 669f, 841f, 951f, 1280f).forEach {
            assertTrue(isWideWindow(it), "$it dp should use the wide frame")
        }
    }

    @Test
    fun a_list_and_a_page_sit_side_by_side_from_741_right_of_the_rail() {
        listOf(546f, 589f, 610f, 640f, 720f, 740.9f).forEach {
            assertFalse(twoPanesFit(it), "$it dp should show one pane")
        }
        listOf(741f, 761f, 810f, 871f, 1200f).forEach {
            assertTrue(twoPanesFit(it), "$it dp should show two panes")
        }
    }

    @Test
    fun the_divider_lands_in_the_middle_of_the_window() {
        // The open Duo held sideways, the Pixel Fold, and the narrowest window
        // that gets two panes: in each, rail + list = half the window.
        assertEquals(395.5f, listPaneWidthDp(951f, 871f))
        assertEquals(340.5f, listPaneWidthDp(841f, 761f))
        assertEquals(330.5f, listPaneWidthDp(821f, 741f))
    }

    @Test
    fun the_list_stays_between_320_and_400_wide() {
        assertEquals(400f, listPaneWidthDp(1280f, 1200f))
        assertEquals(320f, listPaneWidthDp(669f, 589f))
    }

    @Test
    fun the_page_beside_the_list_is_never_narrower_than_380() {
        // Every window from just over 680 wide, less the 80dp rail.
        for (window in 681..2000) {
            val content = window - 80f
            if (!twoPanesFit(content)) continue
            val page = content - listPaneWidthDp(window.toFloat(), content) - 1f
            assertTrue(page >= DETAIL_MIN, "a $window dp window leaves a $page dp page")
        }
    }
}
