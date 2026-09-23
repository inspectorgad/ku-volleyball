package com.example.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

sealed interface SyncResult {
    data class Updated(val generatedAt: String?) : SyncResult
    data object NoChange : SyncResult
    data class Failed(val reason: String) : SyncResult
}

/**
 * Downloads the latest parsed season data and folds it into the database via
 * [Seeder.merge] — the same gap-filling merge used for the bundled seed, so
 * user-entered data is never overwritten.
 */
object SeasonSync {

    /**
     * The same file, published in three places, tried in order until one
     * answers with something that passes [SeasonDataValidator].
     *
     * This used to be the release asset alone. A release download redirects to
     * release-assets.githubusercontent.com, and a network that blocks that host
     * - the one the APK download stalls on - stalled every refresh as well,
     * silently, so the app sat on whatever season its APK was built with.
     *
     * The dashboard's copy on github.io comes first: it is a different host
     * family, it is rewritten on every scrape rather than only after an APK
     * build passes, and it is the address a phone that can open the dashboard
     * can already reach. The raw repository copy is the next best thing, and
     * the release asset stays last so nothing that worked before stops working.
     */
    private val DATA_URLS = listOf(
        "https://inspectorgad.github.io/ku-volleyball/season-data.json",
        "https://raw.githubusercontent.com/inspectorgad/ku-volleyball/main/app/src/main/assets/seed.json",
        "https://github.com/inspectorgad/ku-volleyball/releases/latest/download/season-data.json",
    )
    private const val PREFS = "season_sync"
    private const val KEY_FAILURE = "last_failure"
    private const val KEY_FAILURE_MS = "last_failure_ms"
    private const val KEY_HASH = "last_hash"
    private const val KEY_SUCCESS_MS = "last_success_ms"
    private const val KEY_GENERATED_AT = "last_generated_at"
    private const val AUTO_SYNC_INTERVAL_MS = 6L * 60 * 60 * 1000

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    fun lastGeneratedAt(context: Context): String? =
        prefs(context).getString(KEY_GENERATED_AT, null)

    /** When a feed last answered - with news or without - or 0 if never. */
    fun lastSuccessMs(context: Context): Long = prefs(context).getLong(KEY_SUCCESS_MS, 0)

    /**
     * Why the most recent attempt failed, or null if it succeeded. Kept so a
     * refresh that fails on launch - which shows no message, because nobody
     * asked for it - still leaves a trace on screen instead of vanishing.
     */
    fun lastFailure(context: Context): String? = prefs(context).let { p ->
        if (p.getLong(KEY_FAILURE_MS, 0) > p.getLong(KEY_SUCCESS_MS, 0)) p.getString(KEY_FAILURE, null)
        else null
    }

    fun shouldAutoSync(context: Context): Boolean =
        System.currentTimeMillis() - prefs(context).getLong(KEY_SUCCESS_MS, 0) >
            AUTO_SYNC_INTERVAL_MS

    suspend fun sync(context: Context, dao: JayhawksDao): SyncResult =
        withContext(Dispatchers.IO) {
            val result = syncOnce(context, dao)
            if (result is SyncResult.Failed) {
                prefs(context).edit()
                    .putString(KEY_FAILURE, result.reason)
                    .putLong(KEY_FAILURE_MS, System.currentTimeMillis())
                    .apply()
            }
            result
        }

    private suspend fun syncOnce(context: Context, dao: JayhawksDao): SyncResult {
        // First source that returns valid data wins. Every failure is kept so
        // that if all of them fail, the message says what each one did.
        val failures = mutableListOf<String>()
        var fetched: Pair<ByteArray, JSONObject>? = null
        for (url in DATA_URLS) {
            val host = url.substringAfter("://").substringBefore('/')
            val body = try {
                client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.bytes() else {
                        failures += "$host ${resp.code}"; null
                    }
                }
            } catch (e: Exception) {
                failures += "$host unreachable"; null
            } ?: continue
            val root = SeasonDataValidator.parse(body)
            if (root == null) { failures += "$host sent invalid data"; continue }
            fetched = body to root
            break
        }
        val (body, root) = fetched
            ?: return SyncResult.Failed(failures.joinToString("; "))

        val hash = MessageDigest.getInstance("SHA-256").digest(body)
            .joinToString("") { "%02x".format(it) }
        val p = prefs(context)
        if (hash == p.getString(KEY_HASH, null)) {
            p.edit().putLong(KEY_SUCCESS_MS, System.currentTimeMillis()).apply()
            return SyncResult.NoChange
        }

        try {
            Seeder.merge(root, dao)
        } catch (e: Exception) {
            return SyncResult.Failed("merge failed: ${e.message}")
        }

        val generatedAt = root.optString("generatedAt").takeIf { it.isNotBlank() }
        p.edit()
            .putString(KEY_HASH, hash)
            .putLong(KEY_SUCCESS_MS, System.currentTimeMillis())
            .putString(KEY_GENERATED_AT, generatedAt)
            .apply()
        return SyncResult.Updated(generatedAt)
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * Sanity gate for downloaded season data: a truncated, garbled, or
 * newer-format payload is rejected before it can reach the database.
 */
object SeasonDataValidator {
    const val SUPPORTED_FORMAT = 1
    const val MIN_PLAYERS = 10
    const val MIN_MATCHES = 15

    fun parse(bytes: ByteArray): JSONObject? = try {
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        val valid = root.optInt("formatVersion", 1) <= SUPPORTED_FORMAT &&
            (root.optJSONArray("players")?.length() ?: 0) >= MIN_PLAYERS &&
            (root.optJSONArray("matches")?.length() ?: 0) >= MIN_MATCHES
        if (valid) root else null
    } catch (e: Exception) {
        null
    }
}
