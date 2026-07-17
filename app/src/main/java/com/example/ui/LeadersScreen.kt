package com.example.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.Match
import com.example.data.Player
import com.example.data.StatLine
import com.example.stats.VolleyballTotals
import com.example.stats.aggregate
import com.example.stats.formatAverage
import com.example.stats.formatPerSet

private const val ALL_SEASONS = "All"

/** Hitting-percentage leaders must average at least this many attempts per team set. */
private const val MIN_TA_PER_TEAM_SET = 1

@Composable
fun LeadersScreen(
    players: List<Player>,
    matches: List<Match>,
    statLines: List<StatLine>,
    modifier: Modifier = Modifier,
    dataUpdatedAt: String? = null
) {
    // Seasons ordered most recent first; default selection is the current (latest) season.
    val seasons = matches.sortedByDescending { it.date }.map { it.season }.distinct()
    var selectedSeason by rememberSaveable { mutableStateOf<String?>(null) }
    val season = selectedSeason ?: seasons.firstOrNull() ?: ALL_SEASONS

    val seasonMatches =
        if (season == ALL_SEASONS) matches else matches.filter { it.season == season }
    val seasonMatchIds = seasonMatches.map { it.id }.toSet()
    val seasonLines = statLines.filter { it.matchId in seasonMatchIds }
    val playersById = players.associateBy { it.id }
    val totalsByPlayer: Map<Long, VolleyballTotals> = seasonLines
        .groupBy { it.playerId }
        .mapValues { (_, lines) -> aggregate(lines) }

    val wins = seasonMatches.count {
        it.teamSets != null && it.opponentSets != null && it.teamSets > it.opponentSets
    }
    val losses = seasonMatches.count {
        it.teamSets != null && it.opponentSets != null && it.teamSets < it.opponentSets
    }
    val setsFor = seasonMatches.sumOf { it.teamSets ?: 0 }
    val setsAgainst = seasonMatches.sumOf { it.opponentSets ?: 0 }
    val teamTotals = aggregate(seasonLines)
    // Total sets the team has played this season, for rate-stat qualification.
    val teamSetsPlayed = seasonMatches.sumOf { (it.teamSets ?: 0) + (it.opponentSets ?: 0) }
    val minAttempts = teamSetsPlayed * MIN_TA_PER_TEAM_SET

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            (seasons + ALL_SEASONS).forEach { s ->
                FilterChip(
                    selected = season == s,
                    onClick = { selectedSeason = s },
                    label = { Text(s) }
                )
            }
        }

        if (seasonMatches.isEmpty()) {
            EmptyState(
                title = "No matches recorded",
                subtitle = "Add matches and stat lines to see team totals and leaderboards here."
            )
            return@Column
        }

        LazyColumn(
            contentPadding = ListContentPadding,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "Jayhawks — $season",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Record ${wins}-${losses}" +
                                " · Sets $setsFor for / $setsAgainst against",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "Team ${formatAverage(teamTotals.hittingPercentage)} hitting · " +
                                "${formatPerSet(teamTotals.killsPerSet)} kills/set · " +
                                "${formatPerSet(teamTotals.digsPerSet)} digs/set",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        dataUpdatedAt?.let {
                            Text(
                                "Data updated ${it.take(16).replace('T', ' ')} UTC · pull down to refresh",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item {
                LeaderCard(
                    title = "Hitting %" + if (minAttempts > 0) " (min $minAttempts TA)" else "",
                    entries = totalsByPlayer
                        .filterValues { it.attackAttempts >= minAttempts && it.attackAttempts > 0 }
                        .entries
                        .sortedByDescending { it.value.hittingPercentage }
                        .take(3)
                        .mapNotNull { (playerId, totals) ->
                            playersById[playerId]?.let {
                                it.name to formatAverage(totals.hittingPercentage)
                            }
                        }
                )
            }

            val countingCategories = listOf<Pair<String, (VolleyballTotals) -> Int>>(
                "Kills" to { it.kills },
                "Assists" to { it.assists },
                "Service Aces" to { it.serviceAces },
                "Digs" to { it.digs },
                "Total Blocks" to { it.totalBlocks }
            )
            countingCategories.forEach { (title, selector) ->
                item {
                    LeaderCard(
                        title = title,
                        entries = totalsByPlayer.entries
                            .filter { selector(it.value) > 0 }
                            .sortedByDescending { selector(it.value) }
                            .take(3)
                            .mapNotNull { (playerId, totals) ->
                                playersById[playerId]?.let {
                                    it.name to selector(totals).toString()
                                }
                            }
                    )
                }
            }

            item {
                LeaderCard(
                    title = "Points",
                    entries = totalsByPlayer.entries
                        .filter { it.value.points > 0 }
                        .sortedByDescending { it.value.points }
                        .take(3)
                        .mapNotNull { (playerId, totals) ->
                            playersById[playerId]?.let {
                                val pts = totals.points
                                it.name to if (pts % 1.0 == 0.0) pts.toInt().toString()
                                else String.format(java.util.Locale.US, "%.1f", pts)
                            }
                        }
                )
            }
        }
    }
}

@Composable
fun LeaderCard(title: String, entries: List<Pair<String, String>>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            if (entries.isEmpty()) {
                Text(
                    "No qualifying players yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                entries.forEachIndexed { index, (name, value) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${index + 1}.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            name,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            value,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
