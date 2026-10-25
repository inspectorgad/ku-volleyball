package com.example.data

import com.example.json.JSONObject
import com.example.platform.Device
import com.example.platform.Http
import com.example.platform.newHttp
import com.example.text.currentTimeMs
import com.example.text.hex2
import com.example.text.sha256
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    private val defaultHttp by lazy { newHttp(connectSeconds = 10, readSeconds = 20) }

    fun lastGeneratedAt(device: Device): String? = prefs(device).string(KEY_GENERATED_AT)

    /** When a feed last answered - with news or without - or 0 if never. */
    fun lastSuccessMs(device: Device): Long = prefs(device).long(KEY_SUCCESS_MS)

    /**
     * Why the most recent attempt failed, or null if it succeeded. Kept so a
     * refresh that fails on launch - which shows no message, because nobody
     * asked for it - still leaves a trace on screen instead of vanishing.
     */
    fun lastFailure(device: Device): String? = prefs(device).let { p ->
        if (p.long(KEY_FAILURE_MS) > p.long(KEY_SUCCESS_MS)) p.string(KEY_FAILURE)
        else null
    }

    fun shouldAutoSync(device: Device): Boolean =
        currentTimeMs() - prefs(device).long(KEY_SUCCESS_MS) > AUTO_SYNC_INTERVAL_MS

    suspend fun sync(device: Device, dao: JayhawksDao, http: Http = defaultHttp): SyncResult =
        withContext(Dispatchers.IO) {
            val result = syncOnce(device, dao, http)
            if (result is SyncResult.Failed) {
                prefs(device).edit {
                    putString(KEY_FAILURE, result.reason)
                    putLong(KEY_FAILURE_MS, currentTimeMs())
                }
            }
            result
        }

    private suspend fun syncOnce(device: Device, dao: JayhawksDao, http: Http): SyncResult {
        // First source that returns valid data wins. Every failure is kept so
        // that if all of them fail, the message says what each one did.
        val failures = mutableListOf<String>()
        var fetched: Pair<ByteArray, JSONObject>? = null
        for (url in DATA_URLS) {
            val host = url.substringAfter("://").substringBefore('/')
            val body = try {
                val resp = http.get(url)
                if (resp.isSuccessful) resp.body else {
                    failures += "$host ${resp.code}"; null
                }
            } catch (e: CancellationException) {
                throw e
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

        val hash = sha256(body).joinToString("") { hex2(it) }
        val p = prefs(device)
        if (hash == p.string(KEY_HASH)) {
            p.edit { putLong(KEY_SUCCESS_MS, currentTimeMs()) }
            return SyncResult.NoChange
        }

        try {
            Seeder.merge(root, dao)
        } catch (e: Exception) {
            return SyncResult.Failed("merge failed: ${e.message}")
        }
        SimulationStore.save(device, root)

        val generatedAt = root.optString("generatedAt").takeIf { it.isNotBlank() }
        p.edit {
            putString(KEY_HASH, hash)
            putLong(KEY_SUCCESS_MS, currentTimeMs())
            putString(KEY_GENERATED_AT, generatedAt)
        }
        return SyncResult.Updated(generatedAt)
    }

    private fun prefs(device: Device) = device.settings(PREFS)
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
        val root = JSONObject(bytes.decodeToString())
        val valid = root.optInt("formatVersion", 1) <= SUPPORTED_FORMAT &&
            (root.optJSONArray("players")?.length() ?: 0) >= MIN_PLAYERS &&
            (root.optJSONArray("matches")?.length() ?: 0) >= MIN_MATCHES
        if (valid) root else null
    } catch (e: Exception) {
        null
    }
}
