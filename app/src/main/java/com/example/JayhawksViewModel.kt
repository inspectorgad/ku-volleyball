package com.example

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ConferenceStanding
import com.example.data.JayhawksDao
import com.example.data.Match
import com.example.data.MatchGoal
import com.example.data.MatchTeamStats
import com.example.data.NationalLeader
import com.example.data.OpponentRosterEntry
import com.example.data.OpponentSeasonStat
import com.example.data.OpponentStatLine
import com.example.data.Player
import com.example.data.PollEntry
import com.example.data.SeasonSimulator
import com.example.data.SeasonSync
import com.example.data.SimulationStore
import com.example.data.Seeder
import com.example.data.StatLine
import com.example.data.TeamServing
import com.example.data.SyncResult
import com.example.platform.Device
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Chores outside the app that follow the season data: on Android, the
 * home-screen widget and the background check that sends match alerts.
 */
interface Upkeep {
    /** The app opened: start or stop background checks to match the settings. */
    fun appOpened()

    /** New season data arrived: redraw anything outside the app that shows it. */
    fun dataChanged()
}

/**
 * The screens' data and actions. It is handed the database, the phone's
 * storage and [Upkeep] rather than taking them from Android, so the same
 * class can serve the iPhone app.
 */
class JayhawksViewModel(
    private val dao: JayhawksDao,
    private val device: Device,
    private val upkeep: Upkeep
) : ViewModel() {

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _dataUpdatedAt = MutableStateFlow(SeasonSync.lastGeneratedAt(device))
    val dataUpdatedAt: StateFlow<String?> = _dataUpdatedAt.asStateFlow()

    // When a feed last answered, and why the latest attempt failed if it did.
    // Shown on screen because the launch sync is silent: without these a
    // refresh that never reaches the feed looks exactly like a quiet news day.
    private val _lastCheckedMs = MutableStateFlow(SeasonSync.lastSuccessMs(device))
    val lastCheckedMs: StateFlow<Long> = _lastCheckedMs.asStateFlow()
    private val _syncFailure = MutableStateFlow(SeasonSync.lastFailure(device))
    val syncFailure: StateFlow<String?> = _syncFailure.asStateFlow()

    // The season simulator's inputs, refreshed with the data.
    private val _simulation = MutableStateFlow(SimulationStore.inputs(device))
    val simulation: StateFlow<SeasonSimulator.Inputs?> = _simulation.asStateFlow()

    // Snackbar messages, emitted only for user-initiated refreshes.
    private val _syncMessages = MutableSharedFlow<String>()
    val syncMessages: SharedFlow<String> = _syncMessages.asSharedFlow()

    init {
        viewModelScope.launch {
            // Bundled data first (also covers first launch offline), then a
            // throttled network sync for anything newer.
            Seeder.sync(device, dao)
            _simulation.value = SimulationStore.inputs(device)
            upkeep.dataChanged()
            upkeep.appOpened()
            if (SeasonSync.shouldAutoSync(device)) refreshInternal(manual = false)
        }
    }

    fun refresh() = viewModelScope.launch { refreshInternal(manual = true) }

    private suspend fun refreshInternal(manual: Boolean) {
        if (_isSyncing.value) return
        _isSyncing.value = true
        val result = try {
            SeasonSync.sync(device, dao)
        } finally {
            _isSyncing.value = false
        }
        _dataUpdatedAt.value = SeasonSync.lastGeneratedAt(device)
        _simulation.value = SimulationStore.inputs(device)
        upkeep.dataChanged()
        _lastCheckedMs.value = SeasonSync.lastSuccessMs(device)
        _syncFailure.value = SeasonSync.lastFailure(device)
        if (manual) {
            _syncMessages.emit(
                when (result) {
                    is SyncResult.Updated -> "Season data updated"
                    SyncResult.NoChange -> "Already up to date"
                    is SyncResult.Failed -> "Sync failed: ${result.reason}"
                }
            )
        }
    }

    val players: StateFlow<List<Player>> = dao.observePlayers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val matches: StateFlow<List<Match>> = dao.observeMatches()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val statLines: StateFlow<List<StatLine>> = dao.observeStatLines()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val standings: StateFlow<List<ConferenceStanding>> = dao.observeStandings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val pollEntries: StateFlow<List<PollEntry>> = dao.observePollEntries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val teamServing: StateFlow<List<TeamServing>> = dao.observeTeamServing()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val nationalLeaders: StateFlow<List<NationalLeader>> = dao.observeNationalLeaders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val matchGoals: StateFlow<List<MatchGoal>> = dao.observeMatchGoals()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val opponentStatLines: StateFlow<List<OpponentStatLine>> = dao.observeOpponentStatLines()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val matchTeamStats: StateFlow<List<MatchTeamStats>> = dao.observeMatchTeamStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val opponentRoster: StateFlow<List<OpponentRosterEntry>> = dao.observeOpponentRoster()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val opponentSeasonStats: StateFlow<List<OpponentSeasonStat>> = dao.observeOpponentSeasonStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun savePlayer(player: Player) = viewModelScope.launch {
        if (player.id == 0L) dao.insertPlayer(player) else dao.updatePlayer(player)
    }

    fun deletePlayer(player: Player) = viewModelScope.launch { dao.deletePlayer(player) }

    fun saveMatch(match: Match, onSaved: (Long) -> Unit = {}) = viewModelScope.launch {
        val id = if (match.id == 0L) dao.insertMatch(match) else {
            dao.updateMatch(match)
            match.id
        }
        onSaved(id)
    }

    fun deleteMatch(match: Match) = viewModelScope.launch { dao.deleteMatch(match) }

    fun saveStatLine(line: StatLine) = viewModelScope.launch { dao.upsertStatLine(line) }

    fun deleteStatLine(line: StatLine) = viewModelScope.launch { dao.deleteStatLine(line) }
}
