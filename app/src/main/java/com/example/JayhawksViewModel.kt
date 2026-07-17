package com.example

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.JayhawksDatabase
import com.example.data.Match
import com.example.data.Player
import com.example.data.SeasonSync
import com.example.data.Seeder
import com.example.data.StatLine
import com.example.data.SyncResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class JayhawksViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = JayhawksDatabase.get(app).dao()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _dataUpdatedAt = MutableStateFlow(SeasonSync.lastGeneratedAt(app))
    val dataUpdatedAt: StateFlow<String?> = _dataUpdatedAt.asStateFlow()

    // Snackbar messages, emitted only for user-initiated refreshes.
    private val _syncMessages = MutableSharedFlow<String>()
    val syncMessages: SharedFlow<String> = _syncMessages.asSharedFlow()

    init {
        viewModelScope.launch {
            // Bundled data first (also covers first launch offline), then a
            // throttled network sync for anything newer.
            Seeder.sync(app, dao)
            if (SeasonSync.shouldAutoSync(app)) refreshInternal(manual = false)
        }
    }

    fun refresh() = viewModelScope.launch { refreshInternal(manual = true) }

    private suspend fun refreshInternal(manual: Boolean) {
        if (_isSyncing.value) return
        _isSyncing.value = true
        val result = try {
            SeasonSync.sync(getApplication(), dao)
        } finally {
            _isSyncing.value = false
        }
        _dataUpdatedAt.value = SeasonSync.lastGeneratedAt(getApplication())
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
