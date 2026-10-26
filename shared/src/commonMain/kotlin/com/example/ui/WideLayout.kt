package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailDefaults
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// The app on a wide window: an unfolded iPhone Duo or Android foldable, a
// tablet, an Android phone turned on its side. The tabs move from the bottom
// bar to a rail down the left, and a player, match or opponent opens beside
// its list instead of covering it. Below WIDE_WINDOW nothing here runs:
// JayhawksApp draws the phone layout exactly as before.
//
// Every width is in dp, which are points on iPhone.

/**
 * The window width from which the wide frame replaces the phone layout.
 * Phones held upright are 320-448 wide and the folded Duo about 466, so all
 * of them stay below it. It is Material's own line between a phone and a
 * medium window.
 */
const val WIDE_WINDOW = 600f

/**
 * The content width (the window less the rail) from which a list and its
 * page sit side by side: the narrowest list, the divider and a page about as
 * wide as a big phone (320 + 1 + 420). The Pixel Fold (761) gets two panes;
 * a tablet held upright (720) and the unfolded Duo turned upright (546-589)
 * keep one column.
 */
const val TWO_PANES = 741f

/** The list pane is never narrower than this: a phone's list fits in it unchanged. */
const val LIST_PANE_MIN = 320f

/** Nor wider than this; the page beside it gets the rest. */
const val LIST_PANE_MAX = 400f

/**
 * The narrowest page the rules above can leave beside the list. The rules
 * test holds them to it; with the rail's 80dp the page is never under about
 * 409 in practice.
 */
const val DETAIL_MIN = 380f

/**
 * A tab shown on its own is centred in a column no wider than this, so cards
 * do not stretch, their values stay near their labels, and text runs about 75
 * characters a line. The Serving and Big 12 tables fit inside it whole.
 */
const val WIDE_COLUMN_MAX = 640f

/** True from [WIDE_WINDOW] up. Zero, negative and NaN widths (no window yet) are not wide. */
fun isWideWindow(widthDp: Float): Boolean = widthDp >= WIDE_WINDOW

/** True when [contentDp], the width right of the rail, has room for a list and a page. */
fun twoPanesFit(contentDp: Float): Boolean = contentDp >= TWO_PANES

/**
 * How wide the list pane is beside a page. The content starts after the rail,
 * so content minus half the window puts the divider in the middle of the
 * window, which is where the Duo's fold is when the app fills its inner
 * screen. It is held between [LIST_PANE_MIN] and [LIST_PANE_MAX], so on other
 * windows the list simply stays a phone's width.
 */
fun listPaneWidthDp(windowDp: Float, contentDp: Float): Float =
    (contentDp - windowDp / 2f).coerceIn(LIST_PANE_MIN, LIST_PANE_MAX)

/**
 * The width of the whole app window. Read from the pixel size through the
 * density, as Material's own adaptive library does, rather than from
 * containerDpSize, which tests that force a size do not change.
 */
@Composable
fun windowWidthDp(): Float {
    val widthPx = LocalWindowInfo.current.containerSize.width
    return with(LocalDensity.current) { widthPx.toDp() }.value
}

/** What the right-hand pane shows, in the order the phone layout picks it. */
internal enum class RightPane { Ask, Simulator, Player, Match, Opponent }

/** The tab's icon on the rail. The bottom bar in JayhawksApp keeps its own copy of this list. */
internal val Tab.icon: ImageVector
    get() = when (this) {
        Tab.Roster -> AppIcons.Groups
        Tab.Matches -> AppIcons.List
        Tab.Leaders -> AppIcons.EmojiEvents
        Tab.Serving -> AppIcons.SportsVolleyball
        Tab.Opponents -> AppIcons.Shield
        Tab.Big12 -> AppIcons.Leaderboard
    }

/**
 * The tabs as a rail down the left edge, with [content] in the rest of the
 * window. The rail is the bottom bar turned on its side: the same six tabs,
 * icons and labels, the bar's colour, its 64dp pill and its dark label.
 */
@Composable
fun WideFrame(
    currentTab: Tab,
    onSelectTab: (Tab) -> Unit,
    content: @Composable () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Row(modifier = Modifier.fillMaxSize()) {
            NavigationRail(
                modifier = Modifier.testTag("rail"),
                containerColor = NavigationBarDefaults.containerColor,
                // Material's rail keeps clear of the system bars only. Turned
                // sideways, an Android phone's camera cut-out can sit on this
                // edge too, so the rail keeps clear of that as well.
                windowInsets = NavigationRailDefaults.windowInsets.union(
                    WindowInsets.displayCutout.only(WindowInsetsSides.Start)
                )
            ) {
                // Six tabs need about 372dp; a phone on its side can be
                // shorter, so the tabs scroll rather than run off the end.
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .selectableGroup(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Tab.entries.forEach { tab ->
                        NavigationRailItem(
                            selected = currentTab == tab,
                            onClick = { onSelectTab(tab) },
                            icon = {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = tab.label,
                                    // As on the bottom bar: the pill is sized
                                    // from the icon, and 4dp a side makes it
                                    // the bar's 64dp rather than 56dp.
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                            },
                            label = { Text(tab.label) },
                            // The rail would colour the selected label KU
                            // crimson; keep it dark, as the bar does.
                            colors = NavigationRailItemDefaults.colors(
                                selectedTextColor = MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    // The rail has already kept clear of the start edge, so
                    // nothing to its right pads for it again; the screens'
                    // own Scaffolds and title bars skip what is used up here.
                    .consumeWindowInsets(
                        WindowInsets.systemBars.union(WindowInsets.displayCutout)
                            .only(WindowInsetsSides.Start)
                    )
                    // A phone on its side can have its camera on the far edge.
                    // Material's insets leave that out; upright it is zero.
                    .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.End))
            ) {
                content()
            }
        }
    }
}

/**
 * A tab's list with the page it opened, side by side when there is room
 * ([twoPanesFit]); otherwise one at a time, as on a phone.
 *
 * Side by side, the list is [listPaneWidthDp] wide and the page takes the
 * rest. With nothing open, the page side shows [placeholder] to say what will
 * appear there; a tab with no placeholder (Leaders, Serving, Big 12) shows its
 * list alone, centred in a [WIDE_COLUMN_MAX] column.
 *
 * The list, the divider and the page each stay at one place in the layout and
 * are only shown or hidden. So the list keeps its scroll and chips when a page
 * opens beside it, and a page keeps its state when the window narrows and the
 * list goes, or widens and it comes back.
 *
 * [windowWidth] is the whole window, used to put the divider on the Duo's
 * fold. The space this is given is measured here, after the rail.
 */
@Composable
fun ListDetailPanes(
    windowWidth: Dp,
    showsDetail: Boolean,
    placeholder: PaneHint?,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    snackbarHostState: SnackbarHostState,
    list: @Composable () -> Unit,
    detail: @Composable () -> Unit
) {
    // The content is measured after WideFrame pads a cut-out on the far edge;
    // added back, the divider stays on the window's middle.
    val endCutout = WindowInsets.displayCutout.only(WindowInsetsSides.End).asPaddingValues()
        .calculateEndPadding(LocalLayoutDirection.current).value
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val contentWidth = maxWidth.value
        val sideBySide = twoPanesFit(contentWidth) && (showsDetail || placeholder != null)
        val listWidth = listPaneWidthDp(windowWidth.value, contentWidth + endCutout).dp

        Row(modifier = Modifier.fillMaxSize()) {
            if (sideBySide || !showsDetail) {
                // Pull to refresh wraps the list only, as on the phone. The
                // tabs have no title bar, so the list keeps clear of the
                // status and navigation bars itself; on its own it also
                // reaches the far edge.
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = onRefresh,
                    modifier = (if (sideBySide) Modifier.width(listWidth) else Modifier.weight(1f))
                        .fillMaxHeight()
                        .windowInsetsPadding(
                            WindowInsets.systemBars.only(
                                if (sideBySide) WindowInsetsSides.Vertical
                                else WindowInsetsSides.Vertical + WindowInsetsSides.End
                            )
                        ),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .widthIn(max = WIDE_COLUMN_MAX.dp)
                            .fillMaxWidth()
                    ) {
                        list()
                    }
                }
            }
            if (sideBySide) {
                VerticalDivider(modifier = Modifier.testTag("pane-divider"))
            }
            if (sideBySide || showsDetail) {
                // The detail screens bring their own Scaffold and title bar,
                // which keep clear of the top, bottom and far edge.
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    if (showsDetail) {
                        detail()
                    } else if (placeholder != null) {
                        PanePlaceholder(placeholder)
                    }
                }
            }
        }

        // The phone shows sync messages under its tabs only; here one host is
        // always there, on the list's side, so a message shows at once and
        // never straddles the fold. Spanning the area on its own, it keeps
        // clear of a navigation bar on the far edge, as the phone's Scaffold does.
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .then(if (sideBySide) Modifier.width(listWidth) else Modifier.fillMaxWidth())
                .windowInsetsPadding(
                    WindowInsets.systemBars.only(
                        if (sideBySide) WindowInsetsSides.Bottom
                        else WindowInsetsSides.Bottom + WindowInsetsSides.End
                    )
                )
                .wrapContentWidth()
        )
    }
}

/** The words on the page side before anything is picked from the list. */
data class PaneHint(val title: String, val subtitle: String)

/** What the page side says on each tab that opens pages from a list; null on the others. */
fun placeholderFor(tab: Tab): PaneHint? = when (tab) {
    Tab.Roster -> PaneHint(
        "Pick a player",
        "Tap a player on the left to see her seasons, form and match log here. " +
            "The player you pick gets a blue outline."
    )
    Tab.Matches -> PaneHint(
        "Pick a match",
        "Tap a match on the left to see its box score, goals and scouting here. " +
            "A match you add opens here too. The match you pick gets a blue outline."
    )
    Tab.Opponents -> PaneHint(
        "Pick an opponent",
        "Tap an opponent on the left to see every meeting with Kansas here, from all " +
            "seasons. The team you pick gets a blue outline."
    )
    Tab.Leaders, Tab.Serving, Tab.Big12 -> null
}

/** [hint], centred in the empty page side. */
@Composable
fun PanePlaceholder(hint: PaneHint, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(
                WindowInsets.systemBars.only(WindowInsetsSides.Vertical + WindowInsetsSides.End)
            ),
        contentAlignment = Alignment.Center
    ) {
        EmptyState(
            title = hint.title,
            subtitle = hint.subtitle,
            modifier = Modifier.widthIn(max = WIDE_COLUMN_MAX.dp)
        )
    }
}

/**
 * The outline on the list card whose page is open beside the list, in KU
 * blue. Null when not selected, which is a Card's own default, so a phone's
 * cards are drawn exactly as before.
 */
@Composable
fun selectedCardBorder(selected: Boolean): BorderStroke? =
    if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null

/**
 * Tells screen readers (and tests) which card is open beside the list. Adds
 * nothing when not selected, so a phone's cards read exactly as before.
 */
fun Modifier.markSelected(selected: Boolean): Modifier =
    if (selected) semantics { this.selected = true } else this
