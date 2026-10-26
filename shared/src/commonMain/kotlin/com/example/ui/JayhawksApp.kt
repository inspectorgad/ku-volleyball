package com.example.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.JayhawksViewModel
import com.example.data.normTeam
import com.example.data.sameTeam

enum class Tab(val label: String) {
    Roster("Roster"), Matches("Matches"), Leaders("Leaders"),
    Serving("Serving"), Opponents("Opponents"), Big12("Big 12")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JayhawksApp(viewModel: JayhawksViewModel) {
    var currentTab by rememberSaveable { mutableStateOf(Tab.Roster) }
    // Detail overlays: at most one is open at a time; back closes it.
    var openPlayerId by rememberSaveable { mutableStateOf<Long?>(null) }
    var openMatchId by rememberSaveable { mutableStateOf<Long?>(null) }
    var openOpponent by rememberSaveable { mutableStateOf<String?>(null) }
    var askOpen by rememberSaveable { mutableStateOf(false) }
    var simOpen by rememberSaveable { mutableStateOf(false) }
    var askPrefill by rememberSaveable { mutableStateOf<String?>(null) }

    val players by viewModel.players.collectAsStateWithLifecycle()
    val matches by viewModel.matches.collectAsStateWithLifecycle()
    val statLines by viewModel.statLines.collectAsStateWithLifecycle()
    val standings by viewModel.standings.collectAsStateWithLifecycle()
    val opponentStatLines by viewModel.opponentStatLines.collectAsStateWithLifecycle()
    val matchTeamStats by viewModel.matchTeamStats.collectAsStateWithLifecycle()
    val opponentRoster by viewModel.opponentRoster.collectAsStateWithLifecycle()
    val opponentSeasonStats by viewModel.opponentSeasonStats.collectAsStateWithLifecycle()
    val pollEntries by viewModel.pollEntries.collectAsStateWithLifecycle()
    val teamServing by viewModel.teamServing.collectAsStateWithLifecycle()
    val nationalLeaders by viewModel.nationalLeaders.collectAsStateWithLifecycle()
    val matchGoals by viewModel.matchGoals.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val dataUpdatedAt by viewModel.dataUpdatedAt.collectAsStateWithLifecycle()
    val lastCheckedMs by viewModel.lastCheckedMs.collectAsStateWithLifecycle()
    val syncFailure by viewModel.syncFailure.collectAsStateWithLifecycle()
    val simulation by viewModel.simulation.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.syncMessages.collect { snackbarHostState.showSnackbar(it) }
    }

    val showingDetail = openPlayerId != null || openMatchId != null || openOpponent != null || askOpen || simOpen
    // Back closes the top screen only: Ask opened from a match's Recap button
    // returns to that match.
    SystemBackHandler(enabled = showingDetail) {
        when {
            askOpen -> { askOpen = false; askPrefill = null }
            simOpen -> simOpen = false
            else -> {
                openPlayerId = null
                openMatchId = null
                openOpponent = null
            }
        }
    }
    // On any tab but the first, back returns to the first tab rather than
    // leaving the app - the usual Android pattern for bottom navigation. Only
    // from the first tab does back exit.
    SystemBackHandler(enabled = !showingDetail && currentTab != Tab.Roster) {
        currentTab = Tab.Roster
    }

    val openPlayer = openPlayerId?.let { id -> players.firstOrNull { it.id == id } }
    val openMatch = openMatchId?.let { id -> matches.firstOrNull { it.id == id } }

    // From 600dp wide (an unfolded iPhone Duo or Android foldable, a tablet, an
    // Android phone on its side) the tabs move to a rail and a page opens beside
    // its list: see WideLayout.kt. Everything above is shared by both layouts,
    // so folding or unfolding keeps the tab and whatever page was open. Narrower
    // windows - every phone held upright - skip this and run the phone layout
    // below exactly as before.
    val windowWidth = windowWidthDp()
    if (isWideWindow(windowWidth)) {
        // In two panes the list stays visible, so every tap must replace what is
        // open on the right rather than slide in behind it (the when below shows
        // Ask > Simulator > player > match > opponent).
        fun closeAll() {
            openPlayerId = null
            openMatchId = null
            openOpponent = null
            askOpen = false
            askPrefill = null
            simOpen = false
        }
        val sim = simulation
        // A renamed or not-yet-synced team shows the placeholder, not "0 meetings".
        val shownOpponent = openOpponent?.takeIf { name ->
            matches.any { sameTeam(it.opponent, name) && it.teamSets != null }
        }
        // The phone's order. Anything that does not resolve yet (just restored,
        // just saved, deleted by a sync) leaves the placeholder showing; its id
        // is kept, never cleared or replaced to fill the space.
        val right = when {
            askOpen -> RightPane.Ask
            simOpen && sim != null -> RightPane.Simulator
            openPlayer != null -> RightPane.Player
            openMatch != null -> RightPane.Match
            shownOpponent != null -> RightPane.Opponent
            else -> null
        }
        // Each page belongs to one tab, so choosing another tab closes it:
        // folding afterwards then never shows a page from a different tab.
        WideFrame(
            currentTab = currentTab,
            onSelectTab = { tab ->
                if (tab != currentTab) {
                    closeAll()
                    currentTab = tab
                }
            }
        ) {
            ListDetailPanes(
                windowWidth = windowWidth.dp,
                showsDetail = right != null,
                placeholder = placeholderFor(currentTab),
                isRefreshing = isSyncing,
                onRefresh = viewModel::refresh,
                snackbarHostState = snackbarHostState,
                list = {
                    when (currentTab) {
                        Tab.Roster -> RosterScreen(
                            players = players,
                            statLines = statLines,
                            onSavePlayer = viewModel::savePlayer,
                            onOpenPlayer = { closeAll(); openPlayerId = it.id },
                            selectedPlayerId = openPlayerId
                        )

                        Tab.Matches -> MatchesScreen(
                            matches = matches,
                            statLines = statLines,
                            // A slow save must not open its match after you
                            // have moved to another tab.
                            onSaveMatch = { match ->
                                viewModel.saveMatch(match) { id ->
                                    if (currentTab == Tab.Matches) {
                                        closeAll()
                                        openMatchId = id
                                    }
                                }
                            },
                            onOpenMatch = { closeAll(); openMatchId = it.id },
                            selectedMatchId = openMatchId
                        )

                        Tab.Leaders -> LeadersScreen(
                            players = players,
                            matches = matches,
                            statLines = statLines,
                            nationalLeaders = nationalLeaders,
                            matchGoals = matchGoals,
                            dataUpdatedAt = dataUpdatedAt,
                            lastCheckedMs = lastCheckedMs,
                            syncFailure = syncFailure,
                            pollEntries = pollEntries,
                            standings = standings,
                            onOpenAsk = { closeAll(); askOpen = true },
                            simulation = simulation,
                            onOpenSimulator = { closeAll(); simOpen = true }
                        )

                        Tab.Serving -> ServingScreen(
                            players = players,
                            matches = matches,
                            statLines = statLines
                        )

                        Tab.Opponents -> OpponentsScreen(
                            matches = matches,
                            matchTeamStats = matchTeamStats,
                            onOpenOpponent = { closeAll(); openOpponent = it },
                            selectedOpponent = openOpponent
                        )

                        Tab.Big12 -> StandingsScreen(
                            standings = standings,
                            pollEntries = pollEntries,
                            teamServing = teamServing
                        )
                    }
                },
                detail = {
                    // Each page is keyed on what it shows, so nothing it
                    // remembers - an open dialog, a delete confirmation, the
                    // scroll position - carries over to the next one picked
                    // from the list. The Simulator is keyed on its inputs too:
                    // a sync that changes them starts its picks afresh.
                    when (right) {
                        RightPane.Ask -> key("ask") {
                            AskScreen(onBack = { askOpen = false; askPrefill = null }, prefill = askPrefill)
                        }

                        RightPane.Simulator -> if (sim != null) {
                            key("sim", sim) {
                                SimulatorScreen(sim, onBack = { simOpen = false })
                            }
                        }

                        RightPane.Player -> if (openPlayer != null) {
                            key("player", openPlayer.id) {
                                PlayerDetailScreen(
                                    player = openPlayer,
                                    matches = matches,
                                    statLines = statLines,
                                    onSavePlayer = viewModel::savePlayer,
                                    onDeletePlayer = {
                                        viewModel.deletePlayer(it)
                                        openPlayerId = null
                                    },
                                    onBack = { openPlayerId = null }
                                )
                            }
                        }

                        RightPane.Match -> if (openMatch != null) {
                            key("match", openMatch.id) {
                                MatchDetailScreen(
                                    match = openMatch,
                                    players = players,
                                    statLines = statLines,
                                    matchGoals = matchGoals.filter { it.matchId == openMatch.id },
                                    matches = matches,
                                    opponentStatLines = opponentStatLines,
                                    matchTeamStats = matchTeamStats,
                                    opponentRoster = opponentRoster,
                                    opponentSeasonStats = opponentSeasonStats,
                                    onSaveMatch = { viewModel.saveMatch(it) },
                                    onDeleteMatch = {
                                        viewModel.deleteMatch(it)
                                        openMatchId = null
                                    },
                                    onSaveStatLine = viewModel::saveStatLine,
                                    onDeleteStatLine = viewModel::deleteStatLine,
                                    onBack = { openMatchId = null },
                                    // Ask opens over the match, which stays
                                    // open (and outlined) underneath: Ask's
                                    // arrow or Back brings it back.
                                    onAskClaude = { q -> askPrefill = q; askOpen = true }
                                )
                            }
                        }

                        RightPane.Opponent -> if (shownOpponent != null) {
                            key("opponent", normTeam(shownOpponent)) {
                                OpponentDetailScreen(
                                    opponentName = shownOpponent,
                                    matches = matches,
                                    opponentStatLines = opponentStatLines,
                                    matchTeamStats = matchTeamStats,
                                    opponentRoster = opponentRoster,
                                    onBack = { openOpponent = null }
                                )
                            }
                        }

                        null -> Unit
                    }
                }
            )
        }
        return
    }

    when {
        askOpen -> AskScreen(onBack = { askOpen = false; askPrefill = null }, prefill = askPrefill)

        simOpen && simulation != null -> SimulatorScreen(simulation!!, onBack = { simOpen = false })

        openPlayer != null -> PlayerDetailScreen(
            player = openPlayer,
            matches = matches,
            statLines = statLines,
            onSavePlayer = viewModel::savePlayer,
            onDeletePlayer = {
                viewModel.deletePlayer(it)
                openPlayerId = null
            },
            onBack = { openPlayerId = null }
        )

        openMatch != null -> MatchDetailScreen(
            match = openMatch,
            players = players,
            statLines = statLines,
            matchGoals = matchGoals.filter { it.matchId == openMatch.id },
            matches = matches,
            opponentStatLines = opponentStatLines,
            matchTeamStats = matchTeamStats,
            opponentRoster = opponentRoster,
            opponentSeasonStats = opponentSeasonStats,
            onSaveMatch = { viewModel.saveMatch(it) },
            onDeleteMatch = {
                viewModel.deleteMatch(it)
                openMatchId = null
            },
            onSaveStatLine = viewModel::saveStatLine,
            onDeleteStatLine = viewModel::deleteStatLine,
            onBack = { openMatchId = null },
            onAskClaude = { q -> askPrefill = q; askOpen = true }
        )

        openOpponent != null -> OpponentDetailScreen(
            opponentName = openOpponent!!,
            matches = matches,
            opponentStatLines = opponentStatLines,
            matchTeamStats = matchTeamStats,
            opponentRoster = opponentRoster,
            onBack = { openOpponent = null }
        )

        else -> Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = currentTab == tab,
                            onClick = { currentTab = tab },
                            icon = {
                                Icon(
                                    imageVector = when (tab) {
                                        Tab.Roster -> AppIcons.Groups
                                        Tab.Matches -> AppIcons.List
                                        Tab.Leaders -> AppIcons.EmojiEvents
                                        Tab.Serving -> AppIcons.SportsVolleyball
                                        Tab.Opponents -> AppIcons.Shield
                                        Tab.Big12 -> AppIcons.Leaderboard
                                    },
                                    contentDescription = tab.label,
                                    // The selected tab's pill is sized from the icon:
                                    // 4dp a side brings Material 3 1.4's 56dp pill
                                    // back to the 64dp it always was.
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                            },
                            label = { Text(tab.label) },
                            // Material 3 1.4 turned the selected label the
                            // secondary colour, KU crimson here; keep it dark.
                            colors = NavigationBarItemDefaults.colors(
                                selectedTextColor = MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }
                }
            }
        ) { innerPadding ->
            PullToRefreshBox(
                isRefreshing = isSyncing,
                onRefresh = viewModel::refresh,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when (currentTab) {
                    Tab.Roster -> RosterScreen(
                        players = players,
                        statLines = statLines,
                        onSavePlayer = viewModel::savePlayer,
                        onOpenPlayer = { openPlayerId = it.id }
                    )

                    Tab.Matches -> MatchesScreen(
                        matches = matches,
                        statLines = statLines,
                        onSaveMatch = { match -> viewModel.saveMatch(match) { openMatchId = it } },
                        onOpenMatch = { openMatchId = it.id }
                    )

                    Tab.Leaders -> LeadersScreen(
                        players = players,
                        matches = matches,
                        statLines = statLines,
                        nationalLeaders = nationalLeaders,
                        matchGoals = matchGoals,
                        dataUpdatedAt = dataUpdatedAt,
                        lastCheckedMs = lastCheckedMs,
                        syncFailure = syncFailure,
                        pollEntries = pollEntries,
                        standings = standings,
                        onOpenAsk = { askOpen = true },
                        simulation = simulation,
                        onOpenSimulator = { simOpen = true }
                    )

                    Tab.Serving -> ServingScreen(
                        players = players,
                        matches = matches,
                        statLines = statLines
                    )

                    Tab.Opponents -> OpponentsScreen(
                        matches = matches,
                        matchTeamStats = matchTeamStats,
                        onOpenOpponent = { openOpponent = it }
                    )

                    Tab.Big12 -> StandingsScreen(
                        standings = standings,
                        pollEntries = pollEntries,
                        teamServing = teamServing
                    )
                }
            }
        }
    }
}
