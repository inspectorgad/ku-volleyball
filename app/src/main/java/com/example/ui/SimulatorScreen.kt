package com.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.SeasonSimulator
import com.example.data.SeasonSimulator.chance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val EXPLAIN_SIMULATOR =
    "The rest of the season played out 20,000 times. Each remaining match is won or lost at " +
        "random with the win model's chance for it (the Est. win on the schedule), and the " +
        "numbers here count what happened. Set a match to Win or Loss to see what that result " +
        "would change. Big 12: the other teams' remaining opponents are not published in advance, " +
        "so their games other than against Kansas are played against an average Big 12 side; ties " +
        "in the table are settled by a coin toss rather than the Big 12's tiebreakers. NCAA " +
        "tournament: Kansas is in if the conference title falls to Kansas (the automatic bid) or " +
        "Kansas's final RPI ranks inside the top 45, about where at-large bids usually stop, with " +
        "everyone else's RPI held where it is today. Estimates, not a forecast of what the " +
        "selection committee will do."

/** The headline numbers, shared with the Leaders card. */
fun simulatorLines(r: SeasonSimulator.Result): List<String> = listOf(
    "Projected record ${r.winsMid}-${r.games - r.winsMid} " +
        "(likely range ${r.winsLow}-${r.games - r.winsLow} to ${r.winsHigh}-${r.games - r.winsHigh})",
    "Big 12: top-four finish ${chance(r.top4)} · title ${chance(r.title)} " +
        "(a share ${chance(r.shareOfTitle)}) · average place ${"%.1f".format(java.util.Locale.US, r.averagePlace)}",
    "NCAA tournament ${chance(r.tournament)} · final RPI around #${r.rpiRankMid} " +
        "(#${r.rpiRankLow}-#${r.rpiRankHigh})"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimulatorScreen(inputs: SeasonSimulator.Inputs, onBack: () -> Unit) {
    val forced = remember { mutableStateMapOf<Int, Boolean>() }
    var result by remember { mutableStateOf<SeasonSimulator.Result?>(null) }
    // Re-run whenever a match is set; about a tenth of a second, off the main thread.
    LaunchedEffect(inputs, forced.toMap()) {
        val snapshot = forced.toMap()
        result = withContext(Dispatchers.Default) { SeasonSimulator.simulate(inputs, snapshot) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Season simulator") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                ExplainedCard(EXPLAIN_SIMULATOR) {
                    Text(
                        if (forced.isEmpty()) "If the rest of the season goes as the model expects"
                        else "With ${forced.size} result${if (forced.size == 1) "" else "s"} set by you",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    val r = result
                    if (r == null) {
                        Text("Simulating…", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        simulatorLines(r).forEach {
                            Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                    if (forced.isNotEmpty()) {
                        TextButton(onClick = { forced.clear() }) { Text("Reset to the model") }
                    }
                }
            }
            item {
                Text(
                    "Remaining matches",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            itemsIndexed(inputs.remaining) { i, m ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${shortDate(m.date)}  ${if (m.venue == "A") "at" else "vs"} ${m.opponent}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "model ${chance(m.p)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = forced[i] == null, onClick = { forced.remove(i) }, label = { Text("Model") })
                        FilterChip(selected = forced[i] == true, onClick = { forced[i] = true }, label = { Text("Win") })
                        FilterChip(selected = forced[i] == false, onClick = { forced[i] = false }, label = { Text("Loss") })
                    }
                }
            }
        }
    }
}

/** "2026-10-11" as "Oct 11". */
fun shortDate(iso: String): String {
    val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    val parts = iso.split("-")
    val m = parts.getOrNull(1)?.toIntOrNull() ?: return iso
    val d = parts.getOrNull(2)?.toIntOrNull() ?: return iso
    return "${months[m - 1]} $d"
}
