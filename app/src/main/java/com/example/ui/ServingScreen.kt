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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.Match
import com.example.data.Player
import com.example.data.StatLine
import com.example.stats.ServingMatch
import com.example.stats.aggregate
import com.example.stats.formatPerSet
import com.example.stats.servingProgress

private const val ALL_SEASONS = "All"

/**
 * Serving, followed cumulatively.
 *
 * Two views of the same thing. The player table is season-to-date, so it grows
 * with every match played and is already the cumulative answer for each server.
 * The progression card underneath makes that accumulation visible for the team:
 * one row per match, each carrying where the season stood once that match was
 * over.
 */
@Composable
fun ServingScreen(
    players: List<Player>,
    matches: List<Match>,
    statLines: List<StatLine>,
    modifier: Modifier = Modifier
) {
    val seasons = matches.sortedByDescending { it.date }.map { it.season }.distinct()
    var selectedSeason by rememberSaveable { mutableStateOf<String?>(null) }
    val season = selectedSeason ?: seasons.firstOrNull() ?: ALL_SEASONS

    val seasonMatches =
        if (season == ALL_SEASONS) matches else matches.filter { it.season == season }
    // Only matches that were actually played carry serving; an unplayed fixture
    // would otherwise contribute a zero row and flatten the running rate.
    val played = seasonMatches.filter { it.teamSets != null && it.opponentSets != null }
    val playedIds = played.map { it.id }.toSet()
    val seasonLines = statLines.filter { it.matchId in playedIds }

    val playersById = players.associateBy { it.id }
    // Servers only: a libero who never went back to serve has nothing to say
    // here, and listing them at .00 buries the players who do.
    val servers = seasonLines
        .groupBy { it.playerId }
        .mapValues { (_, lines) -> aggregate(lines) }
        .filterValues { it.serviceAces > 0 || it.serviceErrors > 0 }
        .mapNotNull { (id, totals) -> playersById[id]?.let { it to totals } }
        .sortedWith(
            compareByDescending<Pair<Player, com.example.stats.VolleyballTotals>> {
                it.second.servingEfficiency
            }.thenByDescending { it.second.serviceAces }
        )

    val progress = servingProgress(
        played.map { m ->
            val lines = seasonLines.filter { it.matchId == m.id }
            ServingMatch(
                date = m.date,
                opponent = m.opponent,
                setsPlayed = (m.teamSets ?: 0) + (m.opponentSets ?: 0),
                aces = lines.sumOf { it.serviceAces },
                errors = lines.sumOf { it.serviceErrors }
            )
        }
    )
    val latest = progress.lastOrNull()
    val newestFirst = progress.asReversed()

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

        if (progress.isEmpty()) {
            EmptyState(
                title = "No serving recorded yet",
                subtitle = "Serving totals appear here once a match with stat lines is played."
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
                            "Team serving — $season",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        latest?.let {
                            Text(
                                "${it.cumulativeAces} aces · ${it.cumulativeErrors} errors · " +
                                    "net ${withSign(it.cumulativeDifferential)}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "${formatPerSet(it.cumulativeEfficiency)} net aces per set " +
                                    "over ${it.cumulativeSets} sets",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Serving efficiency is net aces per set. A box score never " +
                                "publishes serve attempts, so sets played is the denominator. " +
                                "Below zero means the serving cost more than it won, which is " +
                                "where most servers sit. This team figure counts every server " +
                                "over the match, so it runs larger than the per-player rows " +
                                "below — those are one server over their own sets.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "By player — season to date",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        if (servers.isEmpty()) {
                            Text(
                                "No player has served yet this season",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            ServingTable(servers)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                TOOLTIP_HINT,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    "Through the season",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            // Newest first: the running figure people want is the current one.
            items(newestFirst, key = { it.match.date + it.match.opponent }) { point ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "${point.match.date} · ${point.match.opponent}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "${point.match.aces} SA, ${point.match.errors} SE " +
                                    "(${withSign(point.differential)} this match)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "Season to date: ${point.cumulativeAces} SA, " +
                                    "${point.cumulativeErrors} SE, " +
                                    "net ${withSign(point.cumulativeDifferential)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            formatPerSet(point.cumulativeEfficiency),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (point.cumulativeDifferential >= 0)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

/** Reads "+4" / "-14" / "0", so a net figure never has to be squinted at. */
private fun withSign(value: Int): String = if (value > 0) "+$value" else value.toString()

private val SERVING_COLUMNS = listOf("SP", "SA", "SE", "NET", "SRV", "SA/S", "SE/S")

@Composable
private fun ServingTable(rows: List<Pair<Player, com.example.stats.VolleyballTotals>>) {
    val cellWidth = 48.dp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ServingCell("", width = 108.dp, header = true)
            SERVING_COLUMNS.forEach { column ->
                StatTooltip(column) { ServingCell(column, width = cellWidth, header = true) }
            }
        }
        HorizontalDivider()
        rows.forEach { (player, t) ->
            Row(
                modifier = Modifier.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ServingCell(player.name, width = 108.dp, header = true, align = TextAlign.Start)
                ServingCell(t.setsPlayed.toString(), cellWidth)
                ServingCell(t.serviceAces.toString(), cellWidth)
                ServingCell(t.serviceErrors.toString(), cellWidth)
                ServingCell(withSign(t.serveDifferential), cellWidth)
                ServingCell(formatPerSet(t.servingEfficiency), cellWidth)
                ServingCell(formatPerSet(t.acesPerSet), cellWidth)
                ServingCell(
                    formatPerSet(
                        if (t.setsPlayed == 0) 0.0
                        else t.serviceErrors.toDouble() / t.setsPlayed
                    ),
                    cellWidth
                )
            }
        }
    }
}

@Composable
private fun ServingCell(
    text: String,
    width: Dp,
    header: Boolean = false,
    align: TextAlign = TextAlign.Center
) {
    Text(
        text = text,
        modifier = Modifier
            .width(width)
            .padding(vertical = 4.dp),
        fontSize = 12.sp,
        fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
        textAlign = align,
        maxLines = 1,
        color = if (header) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurfaceVariant
    )
}
