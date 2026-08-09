package com.example.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.Match
import com.example.data.MatchTeamStats
import com.example.data.OpponentStatLine
import com.example.stats.VolleyballTotals
import com.example.stats.aggregate
import com.example.stats.formatAverage

/**
 * Everything here is scoped to games against Kansas — the box scores we hold are
 * only KU's own matches, so an opponent's row is their record and production
 * *versus KU*, not their season. The UI says so rather than implying otherwise.
 */
data class OpponentSummary(
    val name: String,
    val matchCount: Int,
    val wins: Int,
    val losses: Int,
    val totals: VolleyballTotals
)

fun summarizeOpponents(
    matches: List<Match>,
    matchTeamStats: List<MatchTeamStats>,
    season: String?
): List<OpponentSummary> {
    val played = matches.filter {
        it.teamSets != null && it.opponentSets != null && (season == null || it.season == season)
    }
    val teamStatsByMatch = matchTeamStats.groupBy { it.matchId }

    return played.groupBy { it.opponent }.map { (name, theirMatches) ->
        val ids = theirMatches.map { it.id }
        OpponentSummary(
            name = name,
            matchCount = theirMatches.size,
            // Stated from their side, so the card reads as the opponent's record.
            wins = theirMatches.count { (it.opponentSets ?: 0) > (it.teamSets ?: 0) },
            losses = theirMatches.count { (it.opponentSets ?: 0) < (it.teamSets ?: 0) },
            totals = aggregate(
                ids.flatMap { id -> teamStatsByMatch[id].orEmpty().filter { it.opponent } }
            )
        )
    }.sortedBy { it.name }
}

@Composable
fun OpponentsScreen(
    matches: List<Match>,
    matchTeamStats: List<MatchTeamStats>,
    onOpenOpponent: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val seasons = matches.map { it.season }.distinct().sortedDescending()
    var selectedSeason by rememberSaveable { mutableStateOf(seasons.firstOrNull()) }
    val season = selectedSeason?.takeIf { it in seasons } ?: seasons.firstOrNull()

    val summaries = remember(matches, matchTeamStats, season) {
        summarizeOpponents(matches, matchTeamStats, season)
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (summaries.isEmpty()) {
            EmptyState(
                title = "No opponents yet",
                subtitle = "Opponent box scores appear here once matches have been played.",
                modifier = Modifier.align(Alignment.Center)
            )
            return@Box
        }
        LazyColumn(
            contentPadding = ListContentPadding,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (seasons.size > 1) {
                item(key = "seasons") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        seasons.forEach { s ->
                            FilterChip(
                                selected = s == season,
                                onClick = { selectedSeason = s },
                                label = { Text(s) }
                            )
                        }
                    }
                }
            }
            item(key = "caption") {
                Text(
                    "Records and stats below are against Kansas only, not each " +
                        "opponent's full season.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            items(summaries, key = { it.name }) { opponent ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenOpponent(opponent.name) }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                opponent.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "${opponent.wins}-${opponent.losses} vs KU · " +
                                    "${opponent.matchCount} " +
                                    if (opponent.matchCount == 1) "match" else "matches",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                formatAverage(opponent.totals.hittingPercentage),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "hit % · ${opponent.totals.kills} K",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpponentDetailScreen(
    opponentName: String,
    matches: List<Match>,
    opponentStatLines: List<OpponentStatLine>,
    matchTeamStats: List<MatchTeamStats>,
    onBack: () -> Unit
) {
    val theirMatches = matches
        .filter { it.opponent == opponentName && it.teamSets != null }
        .sortedByDescending { it.date }
    val ids = theirMatches.map { it.id }.toSet()
    val lines = opponentStatLines.filter { it.matchId in ids }
    val stats = matchTeamStats.filter { it.matchId in ids }

    // One row per opposing player across every meeting with Kansas.
    val byPlayer = lines.groupBy { it.playerName }
        .map { (name, rows) ->
            Triple(name, rows.first(), aggregate(rows))
        }
        .sortedByDescending { it.third.kills }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(opponentName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = ListContentPadding,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "Head to Head",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Totals from the ${theirMatches.size} " +
                                (if (theirMatches.size == 1) "meeting" else "meetings") +
                                " with Kansas.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        StatsTable(
                            rows = listOf(
                                opponentName to aggregate(stats.filter { it.opponent }),
                                "Kansas" to aggregate(stats.filter { !it.opponent })
                            ),
                            labelWidth = 104
                        )
                    }
                }
            }

            item {
                Text(
                    "Results",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            items(theirMatches, key = { it.id }) { match ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                match.date,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            match.setScores?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        // Stated from the opponent's side, matching this screen.
                        val theirs = match.opponentSets ?: 0
                        val ours = match.teamSets ?: 0
                        Text(
                            "${if (theirs > ours) "W" else "L"} $theirs-$ours",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (theirs > ours) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            if (byPlayer.isNotEmpty()) {
                item {
                    Text(
                        "Players vs Kansas",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                items(byPlayer, key = { it.first }) { (name, sample, totals) ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            JerseyBadge(sample.jerseyNumber)
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 12.dp)
                            ) {
                                Text(
                                    listOf(name, sample.position)
                                        .filter { it.isNotBlank() }.joinToString(" · "),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    "${totals.kills} K (${formatAverage(totals.hittingPercentage)}) · " +
                                        "${totals.assists} A · ${totals.digs} D · " +
                                        "${totals.totalBlocks} BLK",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
