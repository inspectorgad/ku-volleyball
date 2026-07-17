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
 * Downloads the latest parsed season data (published by CI next to the APK)
 * and folds it into the database via [Seeder.merge] — the same gap-filling
 * merge used for the bundled seed, so user-entered data is never overwritten.
 */
object SeasonSync {

    private const val DATA_URL =
        "https://github.com/inspectorgad/ku-volleyball/releases/latest/download/season-data.json"
    private const val PREFS = "season_sync"
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

    fun shouldAutoSync(context: Context): Boolean =
        System.currentTimeMillis() - prefs(context).getLong(KEY_SUCCESS_MS, 0) >
            AUTO_SYNC_INTERVAL_MS

    suspend fun sync(context: Context, dao: JayhawksDao): SyncResult =
        withContext(Dispatchers.IO) {
            val body = try {
                client.newCall(Request.Builder().url(DATA_URL).build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@withContext SyncResult.Failed("server returned ${resp.code}")
                    }
                    resp.body?.bytes()
                        ?: return@withContext SyncResult.Failed("empty response")
                }
            } catch (e: Exception) {
                return@withContext SyncResult.Failed(e.message ?: "network error")
            }

            val root = SeasonDataValidator.parse(body)
                ?: return@withContext SyncResult.Failed("downloaded data failed validation")

            val hash = MessageDigest.getInstance("SHA-256").digest(body)
                .joinToString("") { "%02x".format(it) }
            val p = prefs(context)
            if (hash == p.getString(KEY_HASH, null)) {
                p.edit().putLong(KEY_SUCCESS_MS, System.currentTimeMillis()).apply()
                return@withContext SyncResult.NoChange
            }

            try {
                Seeder.merge(root, dao)
            } catch (e: Exception) {
                return@withContext SyncResult.Failed("merge failed: ${e.message}")
            }

            val generatedAt = root.optString("generatedAt").takeIf { it.isNotBlank() }
            p.edit()
                .putString(KEY_HASH, hash)
                .putLong(KEY_SUCCESS_MS, System.currentTimeMillis())
                .putString(KEY_GENERATED_AT, generatedAt)
                .apply()
            SyncResult.Updated(generatedAt)
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
