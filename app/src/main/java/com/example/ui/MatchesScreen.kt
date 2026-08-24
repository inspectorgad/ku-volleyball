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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.Match
import com.example.data.MatchTeamStats
import com.example.data.OpponentRosterEntry
import com.example.data.OpponentSeasonStat
import com.example.data.OpponentStatLine
import com.example.data.Player
import com.example.data.StatLine
import com.example.stats.aggregate
import com.example.stats.summarize

@Composable
fun MatchesScreen(
    matches: List<Match>,
    statLines: List<StatLine>,
    onSaveMatch: (Match) -> Unit,
    onOpenMatch: (Match) -> Unit,
    modifier: Modifier = Modifier
) {
    var showAddDialog by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        if (matches.isEmpty()) {
            EmptyState(
                title = "No matches yet",
                subtitle = "Pull down to sync the season, or add matches manually to track stats.",
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            LazyColumn(
                contentPadding = ListContentPadding,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(matches, key = { it.id }) { match ->
                    val lineCount = statLines.count { it.matchId == match.id }
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenMatch(match) }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "${match.versus} ${match.opponent}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    LocationBadge(match)
                                }
                                Text(
                                    "${match.date} · ${match.season}" +
                                        (match.city.takeIf { it.isNotBlank() && (match.neutral || match.home != true) }
                                            ?.let { " · $it" } ?: "") +
                                        if (lineCount > 0) " · $lineCount player${if (lineCount == 1) "" else "s"}" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                match.setScores?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                // Only shown once someone is down for the spare
                                // ticket, so the matches still going begging are
                                // the ones with nothing on this line.
                                match.guest.takeIf { it.isNotBlank() }?.let {
                                    Text(
                                        "Ticket: $it",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            ResultText(match)
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { showAddDialog = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add match")
        }
    }

    if (showAddDialog) {
        MatchDialog(
            match = null,
            defaultSeason = matches.maxByOrNull { it.date }?.season ?: "",
            onDismiss = { showAddDialog = false },
            onSave = {
                onSaveMatch(it)
                showAddDialog = false
            }
        )
    }
}

/**
 * H / A / N chip. The letter carries the meaning, so the tint is reinforcement
 * rather than the only signal.
 */
@Composable
private fun LocationBadge(match: Match) {
    val (letter, description) = when {
        match.neutral -> "N" to "Neutral site"
        match.home == true -> "H" to "Home"
        match.home == false -> "A" to "Away"
        else -> return
    }
    // Tinted on the strength of the badge, not the raw flag: a neutral-site
    // match can still carry home = true, because a sync fills a match's facts in
    // but never overwrites them, and the designated home team of an early-season
    // event was recorded as playing at home before its venue was known.
    val atHome = !match.neutral && match.home == true
    Surface(
        color = if (atHome) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier.padding(start = 6.dp)
    ) {
        Text(
            letter,
            modifier = Modifier
                .semantics { contentDescription = description }
                .padding(horizontal = 5.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (atHome) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ResultText(match: Match) {
    val us = match.teamSets
    val them = match.opponentSets
    if (us == null || them == null) {
        Text(
            "No result",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        val won = us > them
        Text(
            "${if (won) "W" else "L"} $us–$them",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (won) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error
        )
    }
}

@Composable
fun MatchDialog(
    match: Match?,
    defaultSeason: String,
    onDismiss: () -> Unit,
    onSave: (Match) -> Unit
) {
    var date by remember { mutableStateOf(match?.date ?: "") }
    var opponent by remember { mutableStateOf(match?.opponent ?: "") }
    var season by remember { mutableStateOf(match?.season ?: defaultSeason) }
    var teamSets by remember { mutableStateOf(match?.teamSets?.toString() ?: "") }
    var oppSets by remember { mutableStateOf(match?.opponentSets?.toString() ?: "") }
    var setScores by remember { mutableStateOf(match?.setScores ?: "") }
    var guest by remember { mutableStateOf(match?.guest ?: "") }
    // Home / Away / Neutral, defaulting to whatever the match already says.
    var site by remember {
        mutableStateOf(
            when {
                match == null -> "Home"
                match.neutral -> "Neutral"
                match.home == false -> "Away"
                else -> "Home"
            }
        )
    }

    val dateValid = Regex("""\d{4}-\d{2}-\d{2}""").matches(date)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (match == null) "Add Match" else "Edit Match") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it.take(10) },
                    label = { Text("Date (YYYY-MM-DD)") },
                    singleLine = true,
                    isError = date.isNotEmpty() && !dateValid,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = opponent,
                    onValueChange = { opponent = it },
                    label = { Text("Opponent") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = season,
                    onValueChange = { season = it },
                    label = { Text("Season (e.g. 2026)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(
                        label = "Our sets",
                        value = teamSets,
                        onValueChange = { teamSets = it.take(1) },
                        modifier = Modifier.weight(1f)
                    )
                    NumberField(
                        label = "Their sets",
                        value = oppSets,
                        onValueChange = { oppSets = it.take(1) },
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = setScores,
                    onValueChange = { setScores = it },
                    label = { Text("Set scores (25-20, 25-23, …)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = guest,
                    onValueChange = { guest = it },
                    label = { Text("Going with me (spare ticket)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Home", "Away", "Neutral").forEach { option ->
                        FilterChip(
                            selected = site == option,
                            onClick = { site = option },
                            label = { Text(option) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = dateValid && opponent.isNotBlank() && season.isNotBlank(),
                onClick = {
                    // Copied from the match being edited rather than rebuilt
                    // field by field, so anything this form does not ask about -
                    // the venue and city a sync filled in - survives an edit
                    // instead of being quietly dropped.
                    val edited = (match ?: Match(date = date, opponent = opponent, season = season))
                        .copy(
                            date = date,
                            opponent = opponent.trim(),
                            season = season.trim(),
                            teamSets = teamSets.toIntOrNull(),
                            opponentSets = oppSets.toIntOrNull(),
                            setScores = setScores.trim().ifBlank { null },
                            home = site == "Home",
                            neutral = site == "Neutral",
                            guest = guest.trim()
                        )
                    onSave(edited)
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchDetailScreen(
    match: Match,
    players: List<Player>,
    statLines: List<StatLine>,
    opponentStatLines: List<OpponentStatLine>,
    matchTeamStats: List<MatchTeamStats>,
    opponentRoster: List<OpponentRosterEntry>,
    opponentSeasonStats: List<OpponentSeasonStat>,
    onSaveMatch: (Match) -> Unit,
    onDeleteMatch: (Match) -> Unit,
    onSaveStatLine: (StatLine) -> Unit,
    onDeleteStatLine: (StatLine) -> Unit,
    onBack: () -> Unit
) {
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var editingLineFor by remember { mutableStateOf<Player?>(null) }

    val matchLines = statLines.filter { it.matchId == match.id }
    val linesByPlayer = matchLines.associateBy { it.playerId }

    // Kills first: on a volleyball box score that is the line everyone reads.
    val oppLines = opponentStatLines
        .filter { it.matchId == match.id }
        .sortedWith(compareByDescending<OpponentStatLine> { it.kills }.thenBy { it.playerName })
    val teamTotals = matchTeamStats.filter { it.matchId == match.id }.associateBy { it.opponent }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${match.versus} ${match.opponent}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showEditDialog = true }) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit match")
                    }
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete match")
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
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "${match.date} · ${match.season} · " + when {
                                    match.neutral -> "neutral site"
                                    match.home == true -> "home"
                                    match.home == false -> "away"
                                    else -> "location unknown"
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                            listOfNotNull(
                                match.venue.takeIf { it.isNotBlank() },
                                match.city.takeIf { it.isNotBlank() }
                            ).takeIf { it.isNotEmpty() }?.let {
                                Text(
                                    it.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            match.setScores?.let {
                                Text(
                                    "Sets: $it",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                match.guest.takeIf { it.isNotBlank() }
                                    ?.let { "Spare ticket: $it" }
                                    ?: "Spare ticket unclaimed — tap Edit to name someone.",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (match.guest.isBlank())
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.primary
                            )
                            Text(
                                "Tap a player below to enter their stat line.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        ResultText(match)
                    }
                }
            }

            // Before the match: the opponent's published roster, and how they
            // have played so far this season if they have played at all. This is
            // the whole point of scraping their site — the NCAA has no roster
            // endpoint, so nothing else can show a line-up in advance.
            if (match.teamSets == null) {
                val roster = opponentRoster.filter { it.team.equals(match.opponent, true) }
                val form = opponentSeasonStats
                    .filter { it.team.equals(match.opponent, true) }
                    .associateBy { it.playerName.lowercase() }
                if (roster.isEmpty()) {
                    item {
                        EmptyState(
                            title = "No roster yet for ${match.opponent}",
                            subtitle = "Rosters are pulled from each school's own site and " +
                                "refresh weekly. Pull down to sync."
                        )
                    }
                } else {
                    item {
                        Text(
                            "${match.opponent} Roster",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    items(roster.sortedBy { it.jerseyNumber.toIntOrNull() ?: 99 },
                        key = { it.playerName }) { p ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                JerseyBadge(p.jerseyNumber)
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = 12.dp)
                                ) {
                                    Text(
                                        p.playerName,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        listOfNotNull(
                                            p.position.takeIf { it.isNotBlank() },
                                            p.height.takeIf { it.isNotBlank() },
                                            form[p.playerName.lowercase()]?.let { summarize(it) }
                                        ).joinToString(" · ").ifBlank { "No details published" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Team totals come from the box score rather than the sum of the
            // lines below: the NCAA charges some reception errors to the team.
            if (teamTotals.isNotEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "Team Stats",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            StatsTable(
                                rows = listOfNotNull(
                                    teamTotals[false]?.let { "Kansas" to aggregate(listOf(it)) },
                                    teamTotals[true]?.let { match.opponent to aggregate(listOf(it)) }
                                ),
                                labelWidth = 104
                            )
                        }
                    }
                }
            }

            if (oppLines.isNotEmpty()) {
                item {
                    Text(
                        "${match.opponent} Box Score",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                items(oppLines, key = { it.id }) { line ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            JerseyBadge(line.jerseyNumber)
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 12.dp)
                            ) {
                                Text(
                                    listOf(line.playerName, line.position, line.height)
                                        .filter { it.isNotBlank() }.joinToString(" · "),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    summarize(line),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                item {
                    Text(
                        "Kansas",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            if (players.isEmpty()) {
                item {
                    EmptyState(
                        title = "No players on the roster",
                        subtitle = "Add players on the Roster tab first, then record their stats here."
                    )
                }
            } else {
                // Former players only clutter stat entry unless they actually
                // played in this match (e.g. seeded 2025 box scores).
                val relevant = players.filter { it.active || it.id in linesByPlayer }
                items(relevant, key = { it.id }) { player ->
                    val line = linesByPlayer[player.id]
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { editingLineFor = player }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            JerseyBadge(player.jerseyNumber)
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 12.dp)
                            ) {
                                Text(
                                    player.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    line?.let { summarize(it) } ?: "Did not play — tap to add",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (line != null) {
                                IconButton(onClick = { onDeleteStatLine(line) }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Remove stat line",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editingLineFor?.let { player ->
        StatLineDialog(
            player = player,
            existing = linesByPlayer[player.id],
            matchId = match.id,
            onDismiss = { editingLineFor = null },
            onSave = {
                onSaveStatLine(it)
                editingLineFor = null
            }
        )
    }

    if (showEditDialog) {
        MatchDialog(
            match = match,
            defaultSeason = match.season,
            onDismiss = { showEditDialog = false },
            onSave = {
                onSaveMatch(it)
                showEditDialog = false
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete this match?") },
            text = { Text("This removes the match and every stat line recorded for it. This cannot be undone.") },
            confirmButton = {
                Button(onClick = { onDeleteMatch(match) }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun StatLineDialog(
    player: Player,
    existing: StatLine?,
    matchId: Long,
    onDismiss: () -> Unit,
    onSave: (StatLine) -> Unit
) {
    fun init(value: Int?) = value?.takeIf { it != 0 }?.toString() ?: ""

    var sp by remember { mutableStateOf(init(existing?.setsPlayed)) }
    var kills by remember { mutableStateOf(init(existing?.kills)) }
    var errors by remember { mutableStateOf(init(existing?.attackErrors)) }
    var attempts by remember { mutableStateOf(init(existing?.attackAttempts)) }
    var assists by remember { mutableStateOf(init(existing?.assists)) }
    var aces by remember { mutableStateOf(init(existing?.serviceAces)) }
    var serviceErrors by remember { mutableStateOf(init(existing?.serviceErrors)) }
    var digs by remember { mutableStateOf(init(existing?.digs)) }
    var blockSolos by remember { mutableStateOf(init(existing?.blockSolos)) }
    var blockAssists by remember { mutableStateOf(init(existing?.blockAssists)) }
    var receptionErrors by remember { mutableStateOf(init(existing?.receptionErrors)) }
    var bhe by remember { mutableStateOf(init(existing?.ballHandlingErrors)) }

    fun num(s: String) = s.toIntOrNull() ?: 0

    val killsPlusErrorsExceedAttempts = num(kills) + num(errors) > num(attempts)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${player.name} — Match Line") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatFieldRow(
                    "SP" to sp to { v: String -> sp = v },
                    "K" to kills to { v: String -> kills = v },
                    "E" to errors to { v: String -> errors = v }
                )
                StatFieldRow(
                    "TA" to attempts to { v: String -> attempts = v },
                    "A" to assists to { v: String -> assists = v },
                    "SA" to aces to { v: String -> aces = v }
                )
                StatFieldRow(
                    "SE" to serviceErrors to { v: String -> serviceErrors = v },
                    "D" to digs to { v: String -> digs = v },
                    "BS" to blockSolos to { v: String -> blockSolos = v }
                )
                StatFieldRow(
                    "BA" to blockAssists to { v: String -> blockAssists = v },
                    "RE" to receptionErrors to { v: String -> receptionErrors = v },
                    "BHE" to bhe to { v: String -> bhe = v }
                )
                if (killsPlusErrorsExceedAttempts) {
                    Text(
                        "Kills + errors can't exceed total attempts.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !killsPlusErrorsExceedAttempts,
                onClick = {
                    onSave(
                        StatLine(
                            id = existing?.id ?: 0,
                            playerId = player.id,
                            matchId = matchId,
                            setsPlayed = num(sp),
                            kills = num(kills),
                            attackErrors = num(errors),
                            attackAttempts = num(attempts),
                            assists = num(assists),
                            serviceAces = num(aces),
                            serviceErrors = num(serviceErrors),
                            digs = num(digs),
                            blockSolos = num(blockSolos),
                            blockAssists = num(blockAssists),
                            receptionErrors = num(receptionErrors),
                            ballHandlingErrors = num(bhe)
                        )
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun StatFieldRow(
    vararg fields: Pair<Pair<String, String>, (String) -> Unit>
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        fields.forEach { (labelAndValue, onChange) ->
            val (label, value) = labelAndValue
            NumberField(
                label = label,
                value = value,
                onValueChange = onChange,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
