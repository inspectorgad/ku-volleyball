package com.example.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.FileProvider
import com.example.data.Match
import com.example.data.Player
import com.example.data.StatLine
import com.example.stats.formatAverage
import java.io.File

/**
 * A match result as a picture to send or post: score, set scores, the top
 * performers and the record after the match, on Kansas blue. The same card the
 * dashboard draws (renderResultImage in docs/index.html), from the same lines.
 */
data class ResultCard(
    val headline: String,
    val opponent: String,
    val sets: String,
    val dateLine: String,
    val performers: List<String>,
    val record: String
)

fun resultCard(match: Match, players: List<Player>, statLines: List<StatLine>, matches: List<Match>): ResultCard? {
    if (!match.played) return null
    val us = match.teamSets ?: 0
    val them = match.opponentSets ?: 0
    val names = players.associate { it.id to it.name }
    val lines = statLines.filter { it.matchId == match.id }
    fun name(l: StatLine) = names[l.playerId] ?: "?"
    val performers = buildList {
        lines.maxByOrNull { it.kills }?.takeIf { it.kills > 0 }?.let { l ->
            val pct = if (l.attackAttempts > 0)
                " on ${formatAverage((l.kills - l.attackErrors).toDouble() / l.attackAttempts)}" else ""
            add("${name(l)}: ${l.kills} kills$pct")
        }
        lines.maxByOrNull { it.digs }?.takeIf { it.digs > 0 }?.let { add("${name(it)}: ${it.digs} digs") }
        lines.maxByOrNull { it.assists }?.takeIf { it.assists >= 10 }?.let { add("${name(it)}: ${it.assists} assists") }
        lines.maxByOrNull { it.blockSolos * 2 + it.blockAssists }
            ?.takeIf { it.blockSolos + it.blockAssists >= 3 }
            ?.let {
                val blk = it.blockSolos + it.blockAssists / 2.0
                add("${name(it)}: ${if (blk % 1.0 == 0.0) blk.toInt().toString() else blk.toString()} blocks")
            }
    }
    val upTo = matches.filter {
        it.season == match.season && it.played && it.date <= match.date && !it.opponent.contains("(Exh", true)
    }
    val w = upTo.count { (it.teamSets ?: 0) > (it.opponentSets ?: 0) }
    return ResultCard(
        headline = if (us > them) "KANSAS WINS $us-$them" else "KANSAS FALLS $us-$them",
        opponent = "${match.versus} ${rankedOpponent(match)}",
        sets = match.setScores.orEmpty(),
        dateLine = listOf(reformat(match.date, "yyyy-MM-dd", "EEEE, MMMM d, yyyy") ?: match.date, match.venue)
            .filter { it.isNotBlank() }.joinToString(" · "),
        performers = performers,
        record = "Record ${w}-${upTo.size - w}"
    )
}

/** Draws the card at 1080 x 1350, the shape that fills a phone screen and an Instagram post. */
fun drawResultCard(card: ResultCard): Bitmap {
    val w = 1080
    val h = 1350
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawColor(Color.rgb(0, 81, 186))
    val crimson = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(232, 0, 13) }
    c.drawRect(RectF(0f, 0f, w.toFloat(), 18f), crimson)
    fun text(size: Float, bold: Boolean = false, alpha: Int = 255) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(alpha, 255, 255, 255)
        textSize = size
        typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }
    // Every line is shrunk until it fits between the margins, so a long
    // opponent or venue name never runs off the edge.
    val maxWidth = w - 160f
    fun Canvas.fitText(s: String, x: Float, y: Float, p: Paint) {
        while (p.measureText(s) > maxWidth && p.textSize > 20f) p.textSize -= 2f
        drawText(s, 0, s.length, x, y, p)
    }
    var y = 130f
    c.fitText("KANSAS VOLLEYBALL", 80f, y, text(40f, bold = true, alpha = 210))
    y += 150f
    c.fitText(card.headline, 80f, y, text(104f, bold = true))
    y += 90f
    c.fitText(card.opponent, 80f, y, text(64f))
    y += 90f
    c.fitText(card.sets, 80f, y, text(50f, alpha = 230))
    y += 120f
    c.drawRect(RectF(80f, y - 40f, 280f, y - 34f), crimson)
    for (line in card.performers) {
        y += 80f
        c.fitText(line, 80f, y, text(48f))
    }
    val foot = text(38f, alpha = 200)
    c.fitText(card.record, 80f, h - 170f, text(46f, bold = true))
    c.fitText(card.dateLine, 80f, h - 110f, foot)
    return bmp
}

/** Saves the card and opens Android's share sheet with it. */
fun shareResultCard(context: Context, card: ResultCard) {
    val dir = File(context.cacheDir, "share").apply { mkdirs() }
    val file = File(dir, "ku-result.png")
    file.outputStream().use { drawResultCard(card).compress(Bitmap.CompressFormat.PNG, 100, it) }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TEXT, "${card.headline.lowercase().replaceFirstChar { it.uppercase() }} ${card.opponent} (${card.sets})")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share result").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
