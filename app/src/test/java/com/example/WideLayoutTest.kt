package com.example

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.example.ui.ListDetailPanes
import com.example.ui.PaneHint
import com.example.ui.Tab
import com.example.ui.WIDE_COLUMN_MAX
import com.example.ui.WideFrame
import com.example.ui.isWideWindow
import com.example.ui.placeholderFor
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.windowWidthDp
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The wide layout's frame on Android: the tab rail, and a list with the page
 * it opened. The list and page here are stand-ins, so these tests are about
 * the frame alone. Robolectric makes the window the size each test's
 * qualifiers ask for: 951 by 669 stands in for the unfolded iPhone Duo held
 * sideways, 669 by 951 for it held upright (both estimates, not Apple's
 * figures).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WideLayoutTest {

    @get:Rule val rule = createComposeRule()

    // A tab's icon says nothing to a screen reader while its label shows, so
    // tabs are found as tabs with that label.
    private val isTab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    private val snackbar = SnackbarHostState()
    private lateinit var scope: CoroutineScope

    /** The window width, in dp, that the first composition saw. */
    private var firstWidth: Float? = null

    /**
     * The frame as JayhawksApp draws it on the Roster tab, or with no
     * [placeholder] as on Leaders, Serving and Big 12. The list stand-in fills
     * whatever room the list is given, so its bounds are the list's.
     */
    private fun showFrame(
        showsDetail: Boolean,
        placeholder: PaneHint? = placeholderFor(Tab.Roster),
        onSelectTab: (Tab) -> Unit = {}
    ) {
        rule.setContent {
            val width = windowWidthDp()
            val composeScope = rememberCoroutineScope()
            SideEffect {
                if (firstWidth == null) firstWidth = width
                scope = composeScope
            }
            MyApplicationTheme {
                WideFrame(currentTab = Tab.Roster, onSelectTab = onSelectTab) {
                    ListDetailPanes(
                        windowWidth = width.dp,
                        showsDetail = showsDetail,
                        placeholder = placeholder,
                        isRefreshing = false,
                        onRefresh = {},
                        snackbarHostState = snackbar,
                        list = { Box(Modifier.fillMaxSize().testTag("list")) { Text("LIST") } },
                        detail = { Text("DETAIL") }
                    )
                }
            }
        }
    }

    /** The window width the first composition saw, with nothing else drawn. */
    private fun firstWindowWidth(): Float {
        rule.setContent {
            val width = windowWidthDp()
            SideEffect { if (firstWidth == null) firstWidth = width }
        }
        rule.waitForIdle()
        return firstWidth!!
    }

    @Test
    @Config(qualifiers = "w951dp-h669dp")
    fun `an unfolded Duo shows the tab rail, the list and what will open beside it`() {
        var picked: Tab? = null
        showFrame(showsDetail = false, onSelectTab = { picked = it })

        // The window had its real size from the first frame. Were it 0 there,
        // the app would show the phone layout first, and nothing below would
        // be tested at the size it claims.
        assertEquals(951f, firstWidth!!, 2f)
        assertTrue(isWideWindow(firstWidth!!))

        rule.onNodeWithText("LIST").assertIsDisplayed()
        rule.onNodeWithText("Pick a player").assertIsDisplayed()

        rule.onAllNodes(isTab and hasAnyAncestor(hasTestTag("rail"))).assertCountEquals(6)
        Tab.entries.forEach { rule.onNode(isTab and hasText(it.label)).assertIsDisplayed() }
        rule.onNode(isTab and hasText("Roster")).assertIsSelected()

        rule.onNode(isTab and hasText("Matches")).performClick()
        assertEquals(Tab.Matches, picked)
    }

    @Test
    @Config(qualifiers = "w951dp-h669dp")
    fun `the line between list and page runs down the middle, where the Duo folds`() {
        showFrame(showsDetail = false)
        val divider = rule.onNodeWithTag("pane-divider").getUnclippedBoundsInRoot()
        assertEquals(951f / 2, divider.left.value, 2f)
    }

    /**
     * The phone shows sync messages only under its tabs, so one that arrives
     * while a page is open waits for Back. Here a page is open, and the
     * message still shows at once, between the rail and the fold.
     */
    @Test
    @Config(qualifiers = "w951dp-h669dp")
    fun `a sync message shows at once while a page is open, on the list's side of the fold`() {
        showFrame(showsDetail = true)
        rule.onNodeWithText("DETAIL").assertIsDisplayed()
        // The clock is held so the message cannot time out before it is checked.
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { scope.launch { snackbar.showSnackbar("Synced") } }
        rule.mainClock.advanceTimeBy(1_000)

        val message = rule.onNodeWithText("Synced").assertIsDisplayed().getUnclippedBoundsInRoot()
        val rail = rule.onNodeWithTag("rail").getUnclippedBoundsInRoot()
        val divider = rule.onNodeWithTag("pane-divider").getUnclippedBoundsInRoot()
        assertTrue("message starts at ${message.left}, rail ends at ${rail.right}", message.left >= rail.right)
        assertTrue("message ends at ${message.right}, divider at ${divider.left}", message.right <= divider.left)
    }

    @Test
    @Config(qualifiers = "w951dp-h669dp")
    fun `an open page sits beside the list`() {
        showFrame(showsDetail = true)
        rule.onNodeWithText("LIST").assertIsDisplayed()
        rule.onNodeWithText("DETAIL").assertIsDisplayed()
        rule.onNodeWithText("Pick a player").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w669dp-h951dp")
    fun `on the Duo held upright an open page takes the list's place`() {
        showFrame(showsDetail = true)
        rule.onNode(isTab and hasText("Roster")).assertIsDisplayed()
        rule.onNodeWithText("DETAIL").assertIsDisplayed()
        rule.onNodeWithText("LIST").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w669dp-h951dp")
    fun `on the Duo held upright the list shows alone until something is picked`() {
        showFrame(showsDetail = false)
        rule.onNodeWithText("LIST").assertIsDisplayed()
        rule.onNodeWithText("Pick a player").assertDoesNotExist()
        rule.onNodeWithTag("pane-divider").assertDoesNotExist()
    }

    /**
     * Leaders, Serving and Big 12 have nothing to show beside them. On a wide
     * window they get no empty right half and no divider; their cards sit in
     * one column no wider than WIDE_COLUMN_MAX, centred in the room right of
     * the rail rather than stretched across it.
     */
    @Test
    @Config(qualifiers = "w951dp-h669dp")
    fun `a tab with no placeholder shows one centred column, with no divider`() {
        showFrame(showsDetail = false, placeholder = null)
        rule.onNodeWithTag("pane-divider").assertDoesNotExist()

        val list = rule.onNodeWithTag("list").assertIsDisplayed().getUnclippedBoundsInRoot()
        val rail = rule.onNodeWithTag("rail").getUnclippedBoundsInRoot()
        val window = rule.onRoot().getUnclippedBoundsInRoot()
        // 871dp right of the rail, so the column is held to its cap.
        assertEquals(WIDE_COLUMN_MAX, (list.right - list.left).value, 1f)
        val leftMargin = list.left - rail.right
        val rightMargin = window.right - list.right
        assertTrue("column starts at ${list.left}, rail ends at ${rail.right}", leftMargin.value > 0f)
        assertEquals("margins either side of the column", leftMargin.value, rightMargin.value, 1f)
    }

    /**
     * Opening Ask or the Simulator from Leaders moves Leaders from the whole
     * area into the left pane. It must stay the same list, not a new one, so
     * its scroll and season chips survive; a counter stands in for those.
     */
    @Test
    @Config(qualifiers = "w951dp-h669dp")
    fun `a list keeps its state when a page opens beside it`() {
        var showsDetail by mutableStateOf(false)
        rule.setContent {
            MyApplicationTheme {
                // The room right of the rail on the unfolded Duo.
                Box(Modifier.width(871.dp)) {
                    ListDetailPanes(
                        windowWidth = 951.dp,
                        showsDetail = showsDetail,
                        placeholder = null,
                        isRefreshing = false,
                        onRefresh = {},
                        snackbarHostState = snackbar,
                        list = {
                            var taps by rememberSaveable { mutableIntStateOf(0) }
                            Button(onClick = { taps++ }) { Text("Tapped $taps") }
                        },
                        detail = { Text("DETAIL") }
                    )
                }
            }
        }
        rule.onNodeWithTag("pane-divider").assertDoesNotExist()
        rule.onNodeWithText("Tapped 0").performClick()
        rule.onNodeWithText("Tapped 1").assertIsDisplayed()

        rule.runOnIdle { showsDetail = true }
        rule.onNodeWithTag("pane-divider").assertExists()
        rule.onNodeWithText("DETAIL").assertIsDisplayed()
        rule.onNodeWithText("Tapped 1").assertIsDisplayed()
    }

    /**
     * Only the measurement: an upright Pixel 8's window reads below the wide
     * threshold from the first frame. That JayhawksApp then really draws the
     * phone layout is WideNavigationTest's job.
     */
    @Test
    @Config(qualifiers = RobolectricDeviceQualifiers.Pixel8)
    fun `a Pixel 8's window measures 411dp, below the wide threshold`() {
        val width = firstWindowWidth()
        assertEquals(411f, width, 2f)
        assertFalse(isWideWindow(width))
    }

    /** Only the measurement, as above, for the window a test gets when it asks for no size. */
    @Test
    fun `Robolectric's default window measures 320dp, below the wide threshold`() {
        val width = firstWindowWidth()
        assertEquals(320f, width, 2f)
        assertFalse(isWideWindow(width))
    }

    @Test
    @Config(qualifiers = "w951dp-h669dp")
    fun `a page keeps its state while the list beside it comes and goes`() {
        var width by mutableStateOf(871.dp)
        rule.setContent {
            MyApplicationTheme {
                Box(Modifier.width(width)) {
                    ListDetailPanes(
                        windowWidth = 951.dp,
                        showsDetail = true,
                        placeholder = placeholderFor(Tab.Roster),
                        isRefreshing = false,
                        onRefresh = {},
                        snackbarHostState = snackbar,
                        list = { Text("LIST") },
                        detail = {
                            var taps by rememberSaveable { mutableIntStateOf(0) }
                            Button(onClick = { taps++ }) { Text("Tapped $taps") }
                        }
                    )
                }
            }
        }
        rule.onNodeWithText("LIST").assertIsDisplayed()
        rule.onNodeWithText("Tapped 0").performClick()
        rule.onNodeWithText("Tapped 1").assertIsDisplayed()

        // Narrower than two panes need: the page alone, still tapped once.
        rule.runOnIdle { width = 589.dp }
        rule.onNodeWithText("LIST").assertDoesNotExist()
        rule.onNodeWithText("Tapped 1").assertIsDisplayed()

        rule.runOnIdle { width = 871.dp }
        rule.onNodeWithText("LIST").assertIsDisplayed()
        rule.onNodeWithText("Tapped 1").assertIsDisplayed()
    }
}
