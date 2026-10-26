package com.example

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.JayhawksDatabase
import com.example.data.Match
import com.example.data.Player
import com.example.platform.AndroidDevice
import com.example.platform.Device
import com.example.text.currentTimeMs
import com.example.ui.JayhawksApp
import com.example.ui.LocalPhone
import com.example.ui.Phone
import com.example.ui.ResultCard
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The unfolded iPhone Duo held sideways, as in WideLayoutTest (an estimate, not Apple's figure). */
private const val DUO_OPEN = "w951dp-h669dp"

private const val AVERY = "Avery Testwood"
private const val BLAKE = "Blake Testwood"

/** How the Matches list and the match page title the home match below. */
private const val ZEPHYR = "vs Zephyr State"

/**
 * The real app, JayhawksApp with its view model and database, on a wide
 * window: what opens on the right, what closes it, which card is outlined,
 * and what Back does. WideLayoutTest covers the frame with stand-ins; these
 * cover the choices JayhawksApp makes inside it. One test at a Pixel 8's size
 * checks that the same app still draws the phone layout there.
 *
 * The rows are this test's own, with names no real player or team has. The
 * season data bundled in the app is left out (see [NoBundledSeason]), so the
 * lists hold just these rows, all on screen, and no network sync starts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WideNavigationTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val device: Device = NoBundledSeason(AndroidDevice(context))
    private lateinit var db: JayhawksDatabase
    private lateinit var viewModel: JayhawksViewModel

    // A tab's icon says nothing to a screen reader while its label shows, so
    // tabs are found as tabs with that label.
    private val isTab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    // The outline on the card whose page is open beside the list. The rail's
    // current tab is selected too, so tabs are left out.
    private val outlined = SemanticsMatcher.expectValue(SemanticsProperties.Selected, true) and !isTab

    /**
     * The phone's own storage, without the season data bundled in the app:
     * the view model merges that at start, and it would bury these rows among
     * the real roster and schedule, mostly off screen.
     */
    private class NoBundledSeason(phone: Device) : Device by phone {
        override fun readAsset(name: String): String? = null
    }

    /** What MainActivity's AndroidPhone gives the screens, minus the alerts card and the share sheet. */
    private class TestPhone(override val device: Device) : Phone {
        override val hasMatchAlerts: Boolean = false

        @Composable
        override fun AlertsCard() = Unit

        override val sharesResultPictures: Boolean = false

        override fun shareResult(card: ResultCard) = Unit
    }

    private object NoUpkeep : Upkeep {
        override fun appOpened() = Unit
        override suspend fun dataChanged() = Unit
    }

    @Before
    fun setUp() {
        // A feed that answered a moment ago, by the clock SeasonSync reads:
        // the view model starts no sync, so nothing from the network lands here.
        device.settings("season_sync").edit {
            putString("last_hash", "synced-just-now")
            putLong("last_success_ms", currentTimeMs())
        }
        db = Room.inMemoryDatabaseBuilder(context, JayhawksDatabase::class.java).allowMainThreadQueries().build()
        runBlocking {
            val dao = db.dao()
            dao.insertPlayer(Player(name = AVERY, jerseyNumber = "71"))
            dao.insertPlayer(Player(name = BLAKE, jerseyNumber = "72"))
            dao.insertMatch(
                Match(
                    date = "2025-09-12", opponent = "Zephyr State", season = "2025",
                    teamSets = 3, opponentSets = 1, setScores = "25-20, 22-25, 25-18, 25-21", home = true
                )
            )
            dao.insertMatch(
                Match(
                    date = "2025-09-05", opponent = "Quill Tech", season = "2025",
                    teamSets = 1, opponentSets = 3, setScores = "20-25, 25-23, 19-25, 21-25", home = false
                )
            )
        }
        // Built here, as MainActivity's viewModel {} is, not inside the
        // composition: it outlives what is drawn.
        viewModel = JayhawksViewModel(db.dao(), device, NoUpkeep)
    }

    @After
    fun tearDown() {
        // The view model's flows read the database, so they stop before it closes.
        if (::viewModel.isInitialized) viewModel.viewModelScope.cancel()
        if (::db.isInitialized) db.close()
    }

    /** The app as MainActivity draws it, once the roster has loaded. */
    private fun showApp() {
        rule.setContent {
            CompositionLocalProvider(LocalPhone provides TestPhone(device)) {
                MyApplicationTheme { JayhawksApp(viewModel) }
            }
        }
        waitForText(AVERY)
    }

    /** The lists come from the database off the main thread, so wait for them to arrive. */
    private fun waitForText(text: String) = rule.waitUntil(timeoutMillis = 10_000) {
        rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    /** A card in a list, rather than a page title or anything else with that text. */
    private fun card(text: String): SemanticsNodeInteraction = rule.onNode(hasText(text) and hasClickAction())

    private fun tab(label: String): SemanticsNodeInteraction = rule.onNode(isTab and hasText(label))

    /** Android's Back, as the system sends it: through the activity to the app's back handlers. */
    private fun pressBack() {
        val activity = rule.activity
        rule.runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    /**
     * With the list still beside the page, a second tap must replace the
     * first page rather than open behind it, and the outline must move with
     * it, so the list never points at a page that is not the one showing.
     */
    @Test
    @Config(qualifiers = DUO_OPEN)
    fun `picking another player replaces the open one, and only her card is outlined`() {
        showApp()
        rule.onNodeWithTag("rail").assertExists()
        rule.onNodeWithText("Pick a player").assertIsDisplayed()

        card(AVERY).performClick()
        rule.onNodeWithText("#71 $AVERY").assertIsDisplayed()
        card(AVERY).assert(outlined)
        card(BLAKE).assertIsDisplayed().assert(!outlined)

        card(BLAKE).performClick()
        rule.onNodeWithText("#72 $BLAKE").assertIsDisplayed()
        rule.onNodeWithText("#71 $AVERY").assertDoesNotExist()
        card(BLAKE).assert(outlined)
        card(AVERY).assert(!outlined)
        rule.onAllNodes(outlined).assertCountEquals(1)
    }

    /**
     * The iPhone has no Back button, so the page's own arrow is the way to
     * put it away: the list stays and the placeholder returns.
     */
    @Test
    @Config(qualifiers = DUO_OPEN)
    fun `the open page's back arrow brings the placeholder back`() {
        showApp()
        card(BLAKE).performClick()
        rule.onNodeWithText("#72 $BLAKE").assertIsDisplayed()

        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithText("Pick a player").assertIsDisplayed()
        rule.onNodeWithText("#72 $BLAKE").assertDoesNotExist()
        card(BLAKE).assertIsDisplayed().assert(!outlined)
    }

    /**
     * Each page belongs to its tab. Choosing another tab closes it, rather
     * than leaving a player beside the match list, or hidden there until
     * the Roster tab comes back.
     */
    @Test
    @Config(qualifiers = DUO_OPEN)
    fun `choosing another tab closes the open page`() {
        showApp()
        card(AVERY).performClick()
        rule.onNodeWithText("#71 $AVERY").assertIsDisplayed()

        tab("Matches").performClick()
        rule.onNodeWithText("Pick a match").assertIsDisplayed()
        rule.onNodeWithText("#71 $AVERY").assertDoesNotExist()

        tab("Roster").performClick()
        rule.onNodeWithText("Pick a player").assertIsDisplayed()
        rule.onNodeWithText("#71 $AVERY").assertDoesNotExist()
    }

    /**
     * Recap opens Ask in the match's place while the match stays open, and
     * outlined, underneath. Back then steps out one page at a time, in the
     * phone's order: Ask, the match, then the tab.
     */
    @Test
    @Config(qualifiers = DUO_OPEN)
    fun `Ask opened from a match stands in for it, and Back steps out one page at a time`() {
        showApp()
        tab("Matches").performClick()
        waitForText(ZEPHYR)
        card(ZEPHYR).performClick()
        rule.onNodeWithContentDescription("Edit match").assertIsDisplayed()
        card(ZEPHYR).assert(outlined)

        rule.onNodeWithText("Recap this match with Claude").performClick()
        rule.onNodeWithText("Ask about the team").assertIsDisplayed()
        rule.onNodeWithContentDescription("Edit match").assertDoesNotExist()
        card(ZEPHYR).assertIsDisplayed().assert(outlined)

        pressBack()
        rule.onNodeWithContentDescription("Edit match").assertIsDisplayed()
        rule.onNodeWithText("Ask about the team").assertDoesNotExist()
        card(ZEPHYR).assert(outlined)

        pressBack()
        rule.onNodeWithText("Pick a match").assertIsDisplayed()
        card(ZEPHYR).assert(!outlined)

        pressBack()
        tab("Roster").assertIsSelected()
        rule.onNodeWithText("Pick a player").assertIsDisplayed()
    }

    /**
     * Below the wide threshold JayhawksApp itself must still choose the phone
     * layout: the bottom tab bar, no rail, and a page that takes the whole
     * window until Back.
     */
    @Test
    @Config(qualifiers = RobolectricDeviceQualifiers.Pixel8)
    fun `an upright Pixel 8 keeps the phone layout`() {
        showApp()
        rule.onNodeWithTag("rail").assertDoesNotExist()
        rule.onAllNodes(isTab).assertCountEquals(6)
        rule.onNodeWithText("Pick a player").assertDoesNotExist()

        card(AVERY).performClick()
        rule.onNodeWithText("#71 $AVERY").assertIsDisplayed()
        rule.onNodeWithText(BLAKE).assertDoesNotExist()
        rule.onAllNodes(isTab).assertCountEquals(0)

        pressBack()
        card(BLAKE).assertIsDisplayed()
        rule.onNodeWithText("#71 $AVERY").assertDoesNotExist()
    }
}
