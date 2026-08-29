package com.example.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.stats.VolleyballTotals
import com.example.stats.formatAverage
import com.example.stats.formatPerSet

// SRV is serving efficiency: net aces per set, so it sits beside the SA and SE
// counts it is derived from. These two lists are positional - a column added to
// one must be added to the other at the same index.
val STAT_COLUMNS = listOf(
    "MP", "SP", "K", "K/S", "E", "TA", "PCT", "A", "SA", "SE", "SRV",
    "D", "D/S", "BS", "BA", "BLK", "PTS"
)

fun statValues(t: VolleyballTotals): List<String> = listOf(
    t.matches.toString(), t.setsPlayed.toString(), t.kills.toString(),
    formatPerSet(t.killsPerSet), t.attackErrors.toString(), t.attackAttempts.toString(),
    formatAverage(t.hittingPercentage), t.assists.toString(), t.serviceAces.toString(),
    t.serviceErrors.toString(), formatPerSet(t.servingEfficiency),
    t.digs.toString(), formatPerSet(t.digsPerSet),
    t.blockSolos.toString(), t.blockAssists.toString(), t.totalBlocks.toString(),
    formatPerSet(t.points)
)

/** The serving table's headings. Public so a test can hold the glossary to them. */
val SERVING_COLUMNS = listOf("SP", "SA", "SE", "NET", "SRV", "SA/S", "SE/S")

/**
 * One stat, explained once.
 *
 * [aliases] are other headings that mean the same thing — a leaderboard card says
 * "Service Aces" where a table says "SA" — so the sentence is written in one place
 * and both look it up.
 */
data class StatDefinition(
    val term: String,
    val meaning: String,
    val aliases: List<String> = emptyList()
)

/**
 * What each abbreviation means, in the plainest words that are still true.
 *
 * Ordered for reading rather than by table position: the serving trio sits with SA
 * and SE it comes from. This is the list the glossary card draws, so a stat added
 * here explains itself everywhere at once.
 *
 * Every sentence leads with the longhand name, which is why the card shows no
 * separate alias column - "PCT: Hitting percentage: ..." already answers someone
 * who arrived from the "Hitting %" leaderboard.
 */
val STAT_DEFINITIONS: List<StatDefinition> = listOf(
    StatDefinition("MP", "Matches played."),
    StatDefinition("SP", "Sets played. A match is three to five sets."),
    StatDefinition(
        "K", "Kills. An attack that ends the rally and wins the point.",
        listOf("Kills")
    ),
    StatDefinition("K/S", "Kills per set."),
    StatDefinition(
        "E",
        "Attack errors. An attack that ends the rally for the other side — out, " +
            "into the net, or blocked straight down."
    ),
    StatDefinition("TA", "Total attacks. Every attack swing taken, kills and errors included."),
    StatDefinition(
        "PCT",
        "Hitting percentage: (kills − errors) ÷ total attacks. .300 is excellent, " +
            ".000 means as many errors as kills, and it can go negative.",
        listOf("Hitting %")
    ),
    StatDefinition(
        "A", "Assists. The pass that sets up a kill — almost always the setter's.",
        listOf("Assists")
    ),
    StatDefinition(
        "SA",
        "Service aces. A serve the receiving team cannot play, winning the point outright.",
        listOf("Service Aces")
    ),
    StatDefinition(
        "SE",
        "Service errors. A serve that misses: into the net, long, wide, or a foot fault.",
        listOf("Service Errors")
    ),
    StatDefinition(
        "SRV",
        "Serving efficiency: net aces per set, (aces − errors) ÷ sets played. A box " +
            "score never publishes serve attempts, so sets is the denominator. Below zero " +
            "means the serving cost more than it won, where most servers sit."
    ),
    StatDefinition("NET", "Aces minus errors. The raw serving ledger, before dividing by sets."),
    StatDefinition("SA/S", "Service aces per set."),
    StatDefinition("SE/S", "Service errors per set."),
    StatDefinition("D", "Digs. Keeping an attacked ball off the floor.", listOf("Digs")),
    StatDefinition("D/S", "Digs per set."),
    StatDefinition("BS", "Block solos. A block that ends the rally, made by one blocker."),
    StatDefinition(
        "BA",
        "Block assists. A block that ends the rally, shared by two or three blockers."
    ),
    StatDefinition("BLK", "Total blocks: solos plus assists.", listOf("Total Blocks")),
    StatDefinition(
        "PTS",
        "Points, scored NCAA-style: a kill, an ace and a solo block count one each, " +
            "and a block assist counts a half.",
        listOf("Points")
    )
)

/** Every heading that can be looked up - abbreviations and longhand alike. */
val STAT_GLOSSARY: Map<String, String> = buildMap {
    STAT_DEFINITIONS.forEach { definition ->
        put(definition.term, definition.meaning)
        definition.aliases.forEach { put(it, definition.meaning) }
    }
}

/** Tooltips are invisible until touched, so tables say out loud that they are there. */
const val TOOLTIP_HINT = "Press and hold a heading to see what it means."

/**
 * Wraps [content] in a long-press tooltip when [term] has a definition.
 *
 * Falls through untouched when it does not, so a heading added later shows up
 * plainly rather than with an empty bubble attached to it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatTooltip(term: String, content: @Composable () -> Unit) {
    val explanation = STAT_GLOSSARY[term]
    if (explanation == null) {
        content()
        return
    }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(explanation) } },
        state = rememberTooltipState()
    ) {
        content()
    }
}

/**
 * The whole glossary, spelled out.
 *
 * A tooltip only answers a question you already knew to ask, and only one at a
 * time. This is the same sentences laid out to be read straight through, for
 * anyone who would rather scroll a list than long-press twenty headings.
 */
@Composable
fun StatGlossaryCard(modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "What the stats mean",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            STAT_DEFINITIONS.forEach { definition ->
                Row(modifier = Modifier.padding(vertical = 3.dp)) {
                    Text(
                        definition.term,
                        modifier = Modifier.width(52.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        definition.meaning,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * A horizontally scrollable stats table. Each row is a label (e.g. season name)
 * plus one [VolleyballTotals]. The label column stays compact; stat cells are fixed width.
 */
@Composable
fun StatsTable(
    rows: List<Pair<String, VolleyballTotals>>,
    modifier: Modifier = Modifier,
    labelWidth: Int = 84
) {
    val scrollState = rememberScrollState()
    val cellWidth = 48.dp

    Column(modifier = modifier.fillMaxWidth()) {
        // The hint sits outside the horizontal scroll so it stays put while the
        // columns slide under it.
        Column(modifier = Modifier.horizontalScroll(scrollState)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TableCell("", width = labelWidth.dp, header = true)
                STAT_COLUMNS.forEach { column ->
                    StatTooltip(column) { TableCell(column, width = cellWidth, header = true) }
                }
            }
            HorizontalDivider()
            rows.forEach { (label, totals) ->
                Row(
                    modifier = Modifier.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TableCell(label, width = labelWidth.dp, header = true, align = TextAlign.Start)
                    statValues(totals).forEach { TableCell(it, width = cellWidth) }
                }
            }
        }
        Text(
            TOOLTIP_HINT,
            modifier = Modifier.padding(top = 6.dp),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TableCell(
    text: String,
    width: androidx.compose.ui.unit.Dp,
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

/** Compact numeric entry field used in the stat line editor. */
@Composable
fun NumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = { new -> onValueChange(new.filter { it.isDigit() }.take(3)) },
        label = { Text(label, fontSize = 11.sp) },
        modifier = modifier,
        singleLine = true,
        textStyle = TextStyle(fontSize = 14.sp, textAlign = TextAlign.Center),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}

@Composable
fun EmptyState(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

val ListContentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp)
