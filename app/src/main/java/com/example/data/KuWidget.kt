package com.example.data

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.ui.kickoffLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The home-screen widget: record and rank, the next match with its time and
 * the win model's chance, and the last result. Drawn from the app's own
 * database, so it shows what the app shows; redrawn after every data update
 * and by the 15-minute background check while it is on a home screen.
 */
class KuWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                updateAll(context)
            } finally {
                pending.finish()
            }
        }
    }

    // Placing the first widget starts the background check that keeps it
    // fresh; removing the last stops it unless match alerts still need it.
    override fun onEnabled(context: Context) = MatchAlertWorker.reschedule(context)
    override fun onDisabled(context: Context) = MatchAlertWorker.reschedule(context)

    companion object {
        fun isPlaced(context: Context): Boolean = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, KuWidget::class.java)).isNotEmpty()

        suspend fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, KuWidget::class.java))
            if (ids.isEmpty()) return
            val dao = JayhawksDatabase.get(context).dao()
            val lines = widgetLines(dao.matchesOnce(), dao.standingsOnce(), dao.pollEntriesOnce(), today())
            val views = RemoteViews(context.packageName, R.layout.widget_ku).apply {
                setTextViewText(R.id.widget_record, lines.record)
                setTextViewText(R.id.widget_next, lines.next)
                setTextViewText(R.id.widget_last, lines.last)
                setOnClickPendingIntent(
                    R.id.widget_root,
                    PendingIntent.getActivity(
                        context, 0, Intent(context, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
            }
            manager.updateAppWidget(ids, views)
        }

        private fun today(): String =
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
    }
}

data class WidgetLines(val record: String, val next: String, val last: String)

/** The widget's three lines, from the same tables the app's screens use. */
fun widgetLines(
    matches: List<Match>,
    standings: List<ConferenceStanding>,
    poll: List<PollEntry>,
    today: String
): WidgetLines {
    val season = matches.filter { it.played }.maxByOrNull { it.date }?.season
        ?: matches.maxByOrNull { it.date }?.season
    val played = matches.filter { it.season == season && it.played && !it.opponent.contains("(Exh", true) }
    val w = played.count { (it.teamSets ?: 0) > (it.opponentSets ?: 0) }
    val l = played.size - w
    val ku = standings.firstOrNull { it.season == season && sameTeam(it.team, "Kansas") }
    val rank = poll.firstOrNull { it.season == season && sameTeam(it.team, "Kansas") }?.rank
    val record = listOfNotNull(
        "$w-$l",
        ku?.let { "Big 12 ${it.confW}-${it.confL}" },
        rank?.let { "#$it AVCA" }
    ).joinToString(" · ")

    val next = matches.filter { !it.played && it.date >= today }.minByOrNull { it.date }?.let { m ->
        "Next: ${m.versus} ${m.opponent} · ${kickoffLine(m)}" +
            (m.winProbability?.let { " · ${Math.round(it * 100)}% win" } ?: "")
    } ?: "No match scheduled"

    val last = matches.filter { it.played }.maxByOrNull { it.date }?.let { m ->
        val us = m.teamSets ?: 0
        val them = m.opponentSets ?: 0
        "Last: ${if (us > them) "W" else "L"} $us-$them ${m.versus} ${m.opponent}"
    } ?: ""
    return WidgetLines(record, next, last)
}
