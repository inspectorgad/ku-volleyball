package com.example.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * A model the reader can pick. Prices are dollars per million tokens;
 * [cacheRead] is the fraction of the input price a cached read costs.
 */
data class AskModel(
    val id: String,
    val label: String,
    val input: Double,
    val output: Double,
    val cacheRead: Double,
    val fallbacks: Boolean,
    val effort: String? = null
) {
    val shortName: String get() = label.substringBefore(" —")
}

/**
 * The same choices as the dashboard. Opus 5.5 is the default: the newest
 * Opus, about 20% cheaper than Opus 5, at its "medium" thinking depth (set
 * explicitly, since that is its default and a change should not surprise).
 */
val ASK_MODELS = listOf(
    AskModel("claude-opus-5-5", "Claude Opus 5.5 — newest, about 20% cheaper than Opus 5", 4.0, 20.0, 0.05, true, "medium"),
    AskModel("claude-opus-5", "Claude Opus 5 — previous Opus", 5.0, 25.0, 0.1, true),
    AskModel("claude-sonnet-5", "Claude Sonnet 5 — about half the cost of Opus 5.5", 2.0, 10.0, 0.1, false),
)
val DEFAULT_ASK_MODEL = ASK_MODELS.first()

/** One answered (or failed) question. */
data class AskAnswer(
    val text: String,
    val code: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
    val error: String? = null
)

/** A plain-words failure, shown in place of an answer. */
class AskException(message: String) : Exception(message)

/**
 * "Ask about the team" for the app: questions about the season, answered by
 * Claude with the reader's own Anthropic API key.
 *
 * It works exactly as the dashboard's box does (docs/ask.js). Claude gets a
 * Python sandbox (code execution) and one tool, get_table, that its code
 * calls to fetch any table of the season - ask-data.json, built nightly by
 * scripts/ask_pack.py from the validated seed. This class answers those calls
 * from the copy it downloaded, so every number is computed in code rather
 * than recalled, and the tables reach the code without being billed as
 * tokens. The instructions and the short summary of the smaller tables come
 * from the same file, so the app and the dashboard send the same prompt.
 *
 * Only /v1/messages on api.anthropic.com is called, and the key goes nowhere
 * else. Requests are not streamed: the response content is kept exactly as
 * Anthropic sent it and sent back verbatim, which is what the conversation
 * needs to continue, and no block type has to be rebuilt by hand. The Anthropic
 * Java SDK was not used because it would add about 30 MB of libraries to an
 * app that already has OkHttp and a JSON parser.
 */
class AskEngine(
    private val client: OkHttpClient = defaultClient,
    private val baseUrl: String = "https://api.anthropic.com",
    private val loadPack: suspend () -> JSONObject = { fetchPack(defaultClient) }
) {
    private var pack: JSONObject? = null
    private val tableCache = mutableMapOf<String, String>()
    // Anthropic message params, appended in full each turn.
    private val conversation = mutableListOf<JSONObject>()
    private var containerId: String? = null

    val turns: Int get() = conversation.count { it.optString("role") == "user" && hasText(it) }

    /** Forgets the thread, so the next question starts fresh. */
    fun reset() {
        conversation.clear()
        containerId = null
    }

    /**
     * Asks one question and returns the answer. [onStatus] gets a short
     * progress line and the answer text written so far. Cancelling the
     * coroutine stops the request in flight and leaves the thread as it was
     * before the question.
     */
    suspend fun ask(
        question: String,
        apiKey: String,
        model: AskModel,
        today: String,
        onStatus: (status: String, partial: String) -> Unit = { _, _ -> }
    ): AskAnswer {
        val mark = conversation.size
        var done = false
        try {
            onStatus("Loading the season data…", "")
            val data = pack ?: loadPack().also { pack = it }
            val system = data.optString("system_prompt").takeIf { it.isNotBlank() }
                ?: throw AskException("The season data is out of date. Try again after the next refresh.")
            conversation += JSONObject()
                .put("role", "user")
                .put("content", JSONArray().put(textBlock("Today is $today. $question")))

            val usage = LongArray(4) // input, cache write, cache read, output
            var stop = ""
            for (hop in 0 until MAX_HOPS) {
                onStatus(if (hop == 0) "Thinking…" else "Still working…", answerText(mark))
                val reply = send(apiKey, requestBody(model, system))
                reply.optJSONObject("usage")?.let { u ->
                    usage[0] += u.optLong("input_tokens")
                    usage[1] += u.optLong("cache_creation_input_tokens")
                    usage[2] += u.optLong("cache_read_input_tokens")
                    usage[3] += u.optLong("output_tokens")
                }
                reply.optJSONObject("container")?.optString("id")?.takeIf { it.isNotEmpty() }
                    ?.let { containerId = it }
                stop = reply.optString("stop_reason")
                if (stop == "refusal") break
                val content = reply.optJSONArray("content") ?: JSONArray()
                conversation += JSONObject().put("role", "assistant").put("content", content)
                if (stop == "tool_use") {
                    // Claude's code asked for tables: answer every pending call
                    // in one message of tool_result blocks only.
                    val results = JSONArray()
                    blocks(content).filter { it.optString("type") == "tool_use" }
                        .forEach { results.put(tableResult(it, data)) }
                    conversation += JSONObject().put("role", "user").put("content", results)
                    onStatus("Running Python on the season data…", answerText(mark))
                    continue
                }
                if (stop != "pause_turn") break
            }

            val notes = mutableListOf<String>()
            if (stop == "refusal") {
                conversation.subList(mark, conversation.size).clear()
                done = true
                return AskAnswer(
                    text = "",
                    notes = listOf(costLine(model, usage)),
                    error = "Claude declined to answer that one. Try rephrasing it as a question about the stats."
                )
            }
            val text = answerText(mark)
            val code = codeRun(mark)
            if (stop == "tool_use" || stop == "pause_turn") {
                // Out of round trips mid-answer. The thread cannot continue
                // from a pending tool call, so the question is rolled back.
                conversation.subList(mark, conversation.size).clear()
                notes += "Claude was still working when the app stopped waiting. Try a narrower question."
            }
            if (stop == "max_tokens") notes += "The answer hit its length limit and was cut short."
            notes += costLine(model, usage)
            done = true
            return AskAnswer(text = text, code = code, notes = notes)
        } catch (e: AskException) {
            return AskAnswer(text = "", error = e.message)
        } finally {
            if (!done && conversation.size > mark) conversation.subList(mark, conversation.size).clear()
        }
    }

    private fun requestBody(model: AskModel, system: String): JSONObject {
        val ephemeral = { JSONObject().put("type", "ephemeral") }
        return JSONObject()
            .put("model", model.id)
            .put("max_tokens", 16000)
            .put("cache_control", ephemeral())
            .put("system", JSONArray().put(textBlock(system).put("cache_control", ephemeral())))
            .put("tools", tools())
            .put("messages", JSONArray(conversation))
            .apply {
                containerId?.let { put("container", it) }
                model.effort?.let { put("output_config", JSONObject().put("effort", it)) }
                if (model.fallbacks) put("fallbacks", "default")
            }
    }

    private suspend fun send(apiKey: String, body: JSONObject): JSONObject {
        val builder = Request.Builder()
            .url("$baseUrl/v1/messages?beta=true")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        if (body.has("fallbacks")) builder.header("anthropic-beta", FALLBACK_BETA)
        val call = client.newCall(builder.build())
        val response = try {
            call.await()
        } catch (e: IOException) {
            throw AskException("Couldn't reach api.anthropic.com. Check your connection - some networks block it.")
        }
        return response.use { r ->
            val text = withContext(Dispatchers.IO) { r.body?.string().orEmpty() }
            if (!r.isSuccessful) throw AskException(explainError(r.code, text))
            try {
                JSONObject(text)
            } catch (e: Exception) {
                throw AskException("Anthropic sent back something the app couldn't read.")
            }
        }
    }

    private fun tableResult(call: JSONObject, data: JSONObject): JSONObject {
        val name = call.optJSONObject("input")?.optString("table").orEmpty()
        val result = JSONObject().put("type", "tool_result").put("tool_use_id", call.optString("id"))
        if (call.optString("name") != "get_table" || name !in TABLES || !data.has(name)) {
            return result.put("is_error", true)
                .put("content", "Unknown request. Available tables: ${TABLES.joinToString(", ")}.")
        }
        val table = tableCache.getOrPut(name) { data.get(name).toString() }
        return result.put("content", table)
    }

    /** The text Claude has written for this question so far. */
    private fun answerText(mark: Int): String =
        conversation.drop(mark).filter { it.optString("role") == "assistant" }
            .flatMap { blocks(it.optJSONArray("content")) }
            .filter { it.optString("type") == "text" }
            .joinToString("\n\n") { it.optString("text") }
            .trim()

    /** The Python Claude ran, for anyone who wants to check the working. */
    private fun codeRun(mark: Int): List<String> =
        conversation.drop(mark).filter { it.optString("role") == "assistant" }
            .flatMap { blocks(it.optJSONArray("content")) }
            .filter { it.optString("type") == "server_tool_use" }
            .mapNotNull { b ->
                val input = b.optJSONObject("input") ?: JSONObject()
                if (b.optString("name") == "text_editor_code_execution") {
                    listOf(
                        "# ${input.optString("command")} ${input.optString("path")}".trim(),
                        input.optString("file_text")
                    ).filter { it.isNotBlank() && it != "#" }.joinToString("\n")
                } else {
                    input.optString("command").ifBlank { input.optString("code") }
                }.takeIf { it.isNotBlank() }
            }

    companion object {
        /** Round trips per question: pause_turn resumptions plus get_table answers. */
        const val MAX_HOPS = 16
        const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
        val TABLES = listOf(
            "matches", "sets", "ku_lines", "team_totals", "opponent_lines", "goals", "upcoming",
            "standings", "poll", "roster", "definitions"
        )

        /** The dashboard's copy first, the repository's as a fallback, as for the season data. */
        val PACK_URLS = listOf(
            "https://inspectorgad.github.io/ku-volleyball/ask-data.json",
            "https://raw.githubusercontent.com/inspectorgad/ku-volleyball/main/docs/ask-data.json",
        )

        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                // One round trip can include several runs of Claude's code.
                .readTimeout(5, TimeUnit.MINUTES)
                .build()
        }

        suspend fun fetchPack(client: OkHttpClient, urls: List<String> = PACK_URLS): JSONObject {
            for (url in urls) {
                val pack = try {
                    val r = client.newCall(Request.Builder().url(url).header("Cache-Control", "no-cache").build()).await()
                    r.use { if (it.isSuccessful) withContext(Dispatchers.IO) { it.body?.string() } else null }
                        ?.let { JSONObject(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (pack != null && pack.has("system_prompt")) return pack
            }
            throw AskException("Couldn't load the season data. Check your connection and try again.")
        }

        fun explainError(code: Int, body: String): String {
            val message = try {
                JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
            } catch (e: Exception) {
                ""
            }
            return when (code) {
                401 -> "That API key was rejected. Check it at console.anthropic.com and enter it again."
                403 -> "This API key isn't allowed to do that (permission denied). $message".trim()
                429 -> "Too many requests right now (rate limit). Wait a minute and try again."
                400, 404, 413 -> "Anthropic couldn't accept the request: ${message.ifBlank { "error $code" }}"
                529 -> "Anthropic's servers are busy right now. Try again in a moment."
                in 500..599 -> "Anthropic's servers had a problem. Try again in a moment."
                else -> "Anthropic returned an error ($code): ${message.ifBlank { "no details" }}"
            }
        }

        /** usage = input, cache write, cache read, output tokens. */
        fun costLine(model: AskModel, usage: LongArray): String {
            val dollars = (usage[0] * model.input + usage[1] * model.input * 1.25 +
                usage[2] * model.input * model.cacheRead + usage[3] * model.output) / 1e6
            val cents = dollars * 100
            val price = when {
                cents < 1 -> "under 1¢"
                cents < 10 -> "about ${"%.1f".format(java.util.Locale.US, cents)}¢"
                else -> "about ${Math.round(cents)}¢"
            }
            return "${model.shortName} · $price" +
                (if (usage[2] > 0) " (season data read from cache)" else "") +
                " · plus a little code-execution time"
        }

        private fun textBlock(text: String) = JSONObject().put("type", "text").put("text", text)

        private fun blocks(array: JSONArray?): List<JSONObject> =
            if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optJSONObject(it) }

        private fun hasText(message: JSONObject) =
            blocks(message.optJSONArray("content")).any { it.optString("type") == "text" }

        private fun tools(): JSONArray = JSONArray()
            .put(JSONObject().put("type", "code_execution_20260120").put("name", "code_execution"))
            .put(
                JSONObject()
                    .put("name", "get_table")
                    .put(
                        "description",
                        "Returns one table of the Kansas volleyball season data as a JSON string: a list of " +
                            "records (one per row) for every table except 'definitions' (a dict of column meanings) and " +
                            "'poll' (a dict with season, updated and rows). Call it from Python and load it with " +
                            "pandas, e.g. df = pd.DataFrame(json.loads(await get_table({'table': 'ku_lines'})))."
                    )
                    .put(
                        "input_schema",
                        JSONObject()
                            .put("type", "object")
                            .put(
                                "properties",
                                JSONObject().put("table", JSONObject().put("type", "string").put("enum", JSONArray(TABLES)))
                            )
                            .put("required", JSONArray().put("table"))
                    )
                    .put("allowed_callers", JSONArray().put("code_execution_20260120"))
            )
    }
}

/** Runs the call off the main thread; cancelling the coroutine cancels the call. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isCancelled) response.close() else cont.resume(response)
        }
    })
}
