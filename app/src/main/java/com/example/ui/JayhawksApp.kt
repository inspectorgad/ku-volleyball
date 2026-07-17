package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.JayhawksViewModel

enum class Tab(val label: String) { Roster("Roster"), Matches("Matches"), Leaders("Leaders") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JayhawksApp(viewModel: JayhawksViewModel = viewModel()) {
    var currentTab by rememberSaveable { mutableStateOf(Tab.Roster) }
    // Detail overlays: at most one is open at a time; back closes it.
    var openPlayerId by rememberSaveable { mutableStateOf<Long?>(null) }
    var openMatchId by rememberSaveable { mutableStateOf<Long?>(null) }

    val players by viewModel.players.collectAsStateWithLifecycle()
    val matches by viewModel.matches.collectAsStateWithLifecycle()
    val statLines by viewModel.statLines.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val dataUpdatedAt by viewModel.dataUpdatedAt.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.syncMessages.collect { snackbarHostState.showSnackbar(it) }
    }

    val showingDetail = openPlayerId != null || openMatchId != null
    BackHandler(enabled = showingDetail) {
        openPlayerId = null
        openMatchId = null
    }

    val openPlayer = openPlayerId?.let { id -> players.firstOrNull { it.id == id } }
    val openMatch = openMatchId?.let { id -> matches.firstOrNull { it.id == id } }

    when {
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
            onSaveMatch = { viewModel.saveMatch(it) },
            onDeleteMatch = {
                viewModel.deleteMatch(it)
                openMatchId = null
            },
            onSaveStatLine = viewModel::saveStatLine,
            onDeleteStatLine = viewModel::deleteStatLine,
            onBack = { openMatchId = null }
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
                                        Tab.Roster -> Icons.Default.Groups
                                        Tab.Matches -> Icons.AutoMirrored.Filled.List
                                        Tab.Leaders -> Icons.Default.EmojiEvents
                                    },
                                    contentDescription = tab.label
                                )
                            },
                            label = { Text(tab.label) }
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
                        dataUpdatedAt = dataUpdatedAt
                    )
                }
            }
        }
    }
}
