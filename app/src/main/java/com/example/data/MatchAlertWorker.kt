package com.example.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The app's background check, every 15 minutes while match alerts or the
 * home-screen widget are in use.
 *
 * Each run keeps the season data fresh (the same throttled sync the app does
 * on launch) and redraws the widget. With alerts on, it also looks for a KU
 * match whose window is open - 15 minutes before first serve to four hours
 * after - and if there is one, reads the NCAA feed and posts a notification for
 * each set that has finished since the last look and for the final. During a
 * window it checks again every 5 minutes. Android can delay background work to
 * save battery, so an alert can arrive a few minutes after the set ends.
 */
class MatchAlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val dao = JayhawksDatabase.get(ctx).dao()
        if (SeasonSync.shouldAutoSync(ctx)) runCatching { SeasonSync.sync(ctx, dao) }
        runCatching { KuWidget.updateAll(ctx) }
        if (!AlertSettings.enabled(ctx)) return Result.success()

        val match = MatchAlerts.activeMatch(dao.matchesOnce(), System.currentTimeMillis())
            ?: return Result.success()
        val finished = runCatching { checkMatch(ctx, dao, match) }.getOrDefault(false)
        // Look again soon while the window is open and the match is not over.
        if (!finished) scheduleSoon(ctx, 5)
        return Result.success()
    }

    /** True once the match is over and its final alert has gone out. */
    private suspend fun checkMatch(ctx: Context, dao: JayhawksDao, match: Match): Boolean {
        val (y, m, d) = match.date.split("-")
        val scoreboard = getJson("${MatchAlerts.API}/scoreboard/volleyball-women/d1/$y/$m/$d") ?: return false
        val id = MatchAlerts.kuGameId(scoreboard) ?: return false
        val info = getJson("${MatchAlerts.API}/game/$id") ?: return false
        val box = getJson("${MatchAlerts.API}/game/$id/boxscore")
        val kuNames = dao.playersOnce().map { it.name.lowercase() }.toSet()
        val live = MatchAlerts.live(info, box, kuNames) ?: return false

        val prefs = AlertSettings.prefs(ctx)
        val key = "game_$id"
        val notifiedSets = prefs.getInt("${key}_sets", 0)
        val notifiedFinal = prefs.getBoolean("${key}_final", false)
        if (live.final && !notifiedFinal) {
            val (title, body) = MatchAlerts.finalMessage(live, box, kuNames)
            notify(ctx, id.hashCode(), title, body)
            prefs.edit().putBoolean("${key}_final", true).putInt("${key}_sets", live.finished).apply()
            // The full result reaches the app once the nightly scrape has run,
            // which the match-night watcher starts within minutes of the final.
            scheduleSoon(ctx, 30, name = "after-final-sync")
        } else if (!live.final && live.finished > notifiedSets) {
            val (title, body) = MatchAlerts.setMessage(live)
            notify(ctx, id.hashCode(), title, body)
            prefs.edit().putInt("${key}_sets", live.finished).apply()
        }
        return live.final
    }

    private suspend fun getJson(url: String): JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(url).header("accept", "application/json").build()).execute().use {
                if (it.isSuccessful) it.body?.string()?.let(::JSONObject) else null
            }
        }.getOrNull()
    }

    companion object {
        private const val PERIODIC = "ku-background-check"
        private const val SOON = "ku-match-check"
        const val CHANNEL = "match_alerts"

        private val client by lazy {
            OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
        }

        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Starts or stops the 15-minute check to match what is in use. */
        fun reschedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            if (AlertSettings.enabled(context) || KuWidget.isPlaced(context)) {
                wm.enqueueUniquePeriodicWork(
                    PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<MatchAlertWorker>(15, TimeUnit.MINUTES).setConstraints(network).build()
                )
            } else {
                wm.cancelUniqueWork(PERIODIC)
                wm.cancelUniqueWork(SOON)
            }
        }

        fun scheduleSoon(context: Context, minutes: Long, name: String = SOON) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                name, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<MatchAlertWorker>()
                    .setInitialDelay(minutes, TimeUnit.MINUTES).setConstraints(network).build()
            )
        }

        fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(CHANNEL, "Match alerts", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Kansas volleyball set scores and final results" }
                context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            }
        }

        fun notify(context: Context, id: Int, title: String, body: String) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) return
            createChannel(context)
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(title)
                .setContentText(body.substringBefore('\n'))
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            // One notification per match, updated in place set by set.
            NotificationManagerCompat.from(context).notify(id, n)
        }
    }
}

/** The match-alerts switch, kept on the phone. */
object AlertSettings {
    fun prefs(context: Context) = context.getSharedPreferences("match_alerts", Context.MODE_PRIVATE)
    fun enabled(context: Context) = prefs(context).getBoolean("enabled", false)
    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("enabled", on).apply()
        MatchAlertWorker.reschedule(context)
    }
}
