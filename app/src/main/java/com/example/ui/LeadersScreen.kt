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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.example.data.MatchGoal
import com.example.data.NationalLeader
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
    nationalLeaders: List<NationalLeader> = emptyList(),
    matchGoals: List<MatchGoal> = emptyList(),
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

            // Every board here ranks most-first, which for service errors means
            // the player who missed the most serves tops it. That is what was
            // asked for and it reads naturally beside the aces it is paid for -
            // the two together are the whole of a server's ledger.
            val countingCategories = listOf<Pair<String, (VolleyballTotals) -> Int>>(
                "Kills" to { it.kills },
                "Assists" to { it.assists },
                "Service Aces" to { it.serviceAces },
                "Service Errors" to { it.serviceErrors },
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

            // How often each goal is met over the season, which one match
            // cannot show: a target missed once is a bad night, a target missed
            // every time is a target worth arguing about.
            val seasonGoals = matchGoals.filter { it.matchId in seasonMatchIds }
            if (seasonGoals.isNotEmpty()) {
                item { SeasonGoalsCard(seasonGoals, modifier = Modifier.padding(top = 8.dp)) }
            }

            // The boards above rank the Jayhawks against each other. This one
            // ranks them against the country, which our own box scores cannot
            // do: we hold 34 teams and whoever they play, not all of Division I.
            //
            // It is filtered to the season the chips above have selected, so a
            // 2026 snapshot never appears under a 2025 heading. "All" shows the
            // most recent snapshot, since that is the only one the NCAA keeps.
            val natSeason =
                if (season == ALL_SEASONS) nationalLeaders.maxOfOrNull { it.season } else season
            val natRows = nationalLeaders.filter { it.season == natSeason }
            if (natRows.isNotEmpty()) {
                item { NationalLeadersCard(natRows, modifier = Modifier.padding(top = 8.dp)) }
            }

            // Under the boards, same as on the Serving screen: a reference is
            // looked up after something on the way down raised the question.
            item { StatGlossaryCard(modifier = Modifier.padding(top = 8.dp)) }
        }
    }
}

/**
 * How often each performance goal was met across the season.
 *
 * A match card says a night went sixteen out of twenty-two. This says which
 * goals keep going missing, which is the only way to tell a bad night from a
 * target nobody can reach: KU have met the 15.5 digs-per-set goal in none of
 * their ten matches, and their best night all season was 15.00.
 *
 * Ordered worst-first, because the rows worth reading are at that end.
 */
@Composable
fun SeasonGoalsCard(goals: List<MatchGoal>, modifier: Modifier = Modifier) {
    data class Tally(val name: String, val target: Double, val decimals: Int,
                     val ceiling: Boolean, val met: Int, val of: Int)

    val tallies = goals
        .filter { it.met != null }
        .groupBy { it.idx }
        .values
        .mapNotNull { rows ->
            val first = rows.first()
            Tally(first.name, first.target, first.decimals, first.ceiling,
                  rows.count { it.met == true }, rows.size)
        }
        .sortedWith(compareBy({ it.met.toDouble() / it.of }, { it.name }))
    if (tallies.isEmpty()) return
    val matchCount = goals.map { it.matchId }.distinct().size

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "Goals met by target",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Across $matchCount ${if (matchCount == 1) "match" else "matches"} · least often met first",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            tallies.forEach { t ->
                val share = t.met.toDouble() / t.of
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        t.name,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        (if (t.ceiling) "≤ " else "") +
                            if (t.decimals == 3)
                                String.format(java.util.Locale.US, "%.3f", t.target).removePrefix("0")
                            else String.format(java.util.Locale.US, "%.2f", t.target),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                    Text(
                        "${t.met}/${t.of}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        // Never met all season is worth reading as a warning
                        // about the target, not only about the team.
                        color = when {
                            t.met == 0 -> MaterialTheme.colorScheme.error
                            share >= 0.5 -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
            }
        }
    }
}

/** How many of the fifty are shown before the reader asks for the rest. */
private const val NATIONAL_PREVIEW = 10

/**
 * The NCAA's national top 50, one category at a time.
 *
 * Fifty rows across seventeen categories is more than a phone should render
 * eagerly, so only one category is on screen and only its first ten until the
 * reader asks for the rest. Any Kansas player on the list is picked out in the
 * primary colour - finding them is the reason most people will open this.
 */
@Composable
fun NationalLeadersCard(rows: List<NationalLeader>, modifier: Modifier = Modifier) {
    val categories = rows.map { it.category }.distinct().sorted()
    if (categories.isEmpty()) return
    // Kills per set is the headline category, so it opens on that when the NCAA
    // is publishing it and on whatever comes first alphabetically when not.
    val default = categories.firstOrNull { it.contains("Kills Per Set", ignoreCase = true) }
        ?: categories.first()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val category = selected?.takeIf { it in categories } ?: default
    var expanded by rememberSaveable { mutableStateOf(false) }

    val shown = rows.filter { it.category == category }.sortedBy { it.rank }
    val visible = if (expanded) shown else shown.take(NATIONAL_PREVIEW)
    val valueLabel = shown.firstOrNull()?.valueLabel.orEmpty()

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "NCAA National Leaders",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Top ${shown.size} in Division I" +
                    if (valueLabel.isNotBlank()) " · $valueLabel" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                categories.forEach { c ->
                    FilterChip(
                        selected = category == c,
                        onClick = { selected = c; expanded = false },
                        label = { Text(c) }
                    )
                }
            }

            visible.forEach { row ->
                // The NCAA writes it "Kansas"; nothing else in the file does.
                val isKU = row.team.equals("Kansas", ignoreCase = true)
                val color =
                    if (isKU) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${row.rank}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(32.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            row.player,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isKU) FontWeight.Bold else FontWeight.Normal,
                            color = color
                        )
                        Text(
                            listOf(row.team, row.position, row.cls)
                                .filter { it.isNotBlank() }
                                .joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        row.value,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = color
                    )
                }
            }

            if (shown.size > NATIONAL_PREVIEW) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Show top $NATIONAL_PREVIEW" else "Show all ${shown.size}")
                }
            }
        }
    }
}

@Composable
fun LeaderCard(title: String, entries: List<Pair<String, String>>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            // "Hitting % (min 24 TA)" carries a qualifier the glossary has no
            // entry for, so look up the bare term before the parenthesis.
            StatTooltip(title.substringBefore(" (").trim()) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
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
