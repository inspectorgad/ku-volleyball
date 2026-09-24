package com.example.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * A goal's number written the way the staff's own sheet writes it: a per-set
 * count as 21.33, a percentage as .362 with no leading zero.
 *
 * Shared by the match card, the season tally and this chart's caption, because
 * the same number appearing three ways on one screen reads as three numbers.
 */
fun formatGoal(value: Double?, decimals: Int): String = when {
    value == null -> "—"
    decimals == 3 -> String.format(java.util.Locale.US, "%.3f", value).removePrefix("0")
    else -> String.format(java.util.Locale.US, "%.2f", value)
}

/** One match's number for a goal, in the order the matches were played. */
data class GoalPoint(val label: String, val value: Double?)

/**
 * One goal's numbers across a season, with its target drawn across them.
 *
 * The tally beside it already says a goal was met four times in ten. What a
 * tally cannot say is whether the target is being missed narrowly or is out of
 * reach entirely, and those call for opposite responses. Digs per set sits
 * below its line in all ten matches and the line is nowhere near the points -
 * a shape that says the number is wrong far more clearly than "0/10" does.
 *
 * Deliberately spare: no axes, no gridlines, no legend. The target is the only
 * reference anyone needs, and the caption underneath carries the numbers that
 * would otherwise want axis labels. Colour repeats what the caption says rather
 * than being the only carrier of it.
 */
@Composable
fun GoalTrendChart(
    points: List<GoalPoint>,
    target: Double,
    ceiling: Boolean,
    format: (Double?) -> String,
    modifier: Modifier = Modifier,
    // What the dashed line is: the staff's target by default, or a season
    // average when the chart is showing form.
    targetLabel: String = "Target"
) {
    val measured = points.mapNotNull { it.value }
    if (measured.size < 2) return

    val met = measured.count { if (ceiling) it <= target else it >= target }
    // The axis has to include the target even when no match came close to it;
    // that gap is the point of the chart.
    val low = minOf(measured.min(), target)
    val high = maxOf(measured.max(), target)
    // A flat series would divide by zero, and a hair of padding keeps the
    // extreme points off the edges.
    val pad = ((high - low) * 0.15).takeIf { it > 0 } ?: (high.takeIf { it > 0 } ?: 1.0) * 0.1
    val top = high + pad
    val bottom = low - pad

    val lineColor = MaterialTheme.colorScheme.primary
    val missColor = MaterialTheme.colorScheme.error
    val targetColor = MaterialTheme.colorScheme.outline
    val best = if (ceiling) measured.min() else measured.max()
    val worst = if (ceiling) measured.max() else measured.min()

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .padding(top = 6.dp, bottom = 4.dp)
                .semantics {
                    contentDescription = "Trend over ${points.size} matches. " +
                        "$targetLabel ${format(target)}, at or above it $met of ${measured.size}. " +
                        "Best ${format(best)}, worst ${format(worst)}."
                }
        ) {
            fun y(v: Double) = (size.height * (top - v) / (top - bottom)).toFloat()
            fun x(i: Int) =
                if (points.size == 1) size.width / 2f
                else size.width * i / (points.size - 1f)

            // The target first, so the series reads on top of it.
            drawLine(
                color = targetColor,
                start = Offset(0f, y(target)),
                end = Offset(size.width, y(target)),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
            )

            // Joined only between consecutive measured matches: a gap is a match
            // the goal could not be judged on, and bridging it would invent a
            // line through a number nobody recorded.
            points.forEachIndexed { i, p ->
                val v = p.value ?: return@forEachIndexed
                val next = points.getOrNull(i + 1)?.value
                if (next != null) {
                    drawLine(
                        color = lineColor,
                        start = Offset(x(i), y(v)),
                        end = Offset(x(i + 1), y(next)),
                        strokeWidth = 3f,
                        cap = StrokeCap.Round
                    )
                }
            }
            points.forEachIndexed { i, p ->
                val v = p.value ?: return@forEachIndexed
                val ok = if (ceiling) v <= target else v >= target
                drawCircle(
                    color = if (ok) lineColor else missColor,
                    radius = 5f,
                    center = Offset(x(i), y(v))
                )
            }
        }
        Text(
            "$targetLabel ${if (ceiling) "≤ " else ""}${format(target)} · " +
                "best ${format(best)} · worst ${format(worst)} · " +
                "${points.first().label} to ${points.last().label}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
