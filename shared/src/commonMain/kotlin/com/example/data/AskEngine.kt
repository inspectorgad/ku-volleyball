package com.example.data

import com.example.json.JSONArray
import com.example.json.JSONObject
import com.example.platform.Http
import com.example.platform.HttpException
import com.example.platform.newHttp
import com.example.text.fixed
import com.example.text.roundHalfUp
import kotlinx.coroutines.CancellationException

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

/** A web page an answer cites; [number] matches the [n] marker in the text. */
data class AskSource(val number: Int, val title: String, val url: String)

/** One answered (or failed) question. */
data class AskAnswer(
    val text: String,
    val code: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
    val error: String? = null,
    val sources: List<AskSource> = emptyList(),
    val searches: List<String> = emptyList()
)

/** A plain-words failure, shown in place of an answer. */
/** [code] is Anthropic's HTTP status when it answered with an error. */
class AskException(message: String, val code: Int? = null) : Exception(message)

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
 * With web search on for a question, Claude may also search the web
 * (Anthropic's web search tool, run on Anthropic's side) for what the tables
 * cannot tell it - news, previews, injuries. Pages it cites are numbered in
 * the text, [1], and listed as [AskAnswer.sources]: Anthropic requires the
 * sources to be shown with the answer.
 *
 * Only /v1/messages on api.anthropic.com is called, and the key goes nowhere
 * else. Requests are not streamed: the response content is kept exactly as
 * Anthropic sent it and sent back verbatim, which is what the conversation
 * needs to continue, and no block type has to be rebuilt by hand. The Anthropic
 * Java SDK was not used because it would add about 30 MB of libraries to an
 * app that already has an HTTP client and a JSON parser, and it could not run
 * on iPhone.
 */
class AskEngine(
    private val http: Http = defaultHttp,
    private val baseUrl: String = "https://api.anthropic.com",
    private val loadPack: suspend () -> JSONObject = { fetchPack(defaultHttp) }
) {
    private var pack: JSONObject? = null
    private val tableCache = mutableMapOf<String, String>()
    // Anthropic message params, appended in full each turn.
    private val conversation = mutableListOf<JSONObject>()
    private var containerId: String? = null
    // Whether this conversation declares the web search tool, settled by its
    // first question; null before that. The tool list can't change partway
    // through: on Claude Opus 5.5 each turn's reasoning is bound to the tools
    // it was written with, and Anthropic rejects the conversation ("Invalid
    // signature in thinking block ... The tools list differs") once they do.
    private var webDeclared: Boolean? = null

    val turns: Int get() = conversation.count { it.optString("role") == "user" && hasText(it) && !isAppNote(it) }

    /** Forgets the thread, so the next question starts fresh. */
    fun reset() {
        conversation.clear()
        containerId = null
        webDeclared = null
    }

    /**
     * True when a question with [webSearch] on can't join the conversation
     * under way, because that conversation began without web search: [ask]
     * then starts a new one. Turning search off is fine - the tool stays
     * declared and the question says not to search.
     */
    fun startsNewConversation(webSearch: Boolean): Boolean =
        webSearch && conversation.isNotEmpty() && webDeclared == false

    /**
     * Asks one question and returns the answer. [webSearch] lets Claude search
     * the web for this question. [onStatus] gets a short progress line, the
     * answer text written so far, and the sources that text's [n] marks point
     * to, so a partial answer left on screen keeps them. Cancelling the
     * coroutine stops the request in flight and leaves the thread as it was
     * before the question.
     */
    suspend fun ask(
        question: String,
        apiKey: String,
        model: AskModel,
        today: String,
        webSearch: Boolean = false,
        onStatus: (status: String, partial: String, sources: List<AskSource>) -> Unit = { _, _, _ -> }
    ): AskAnswer {
        val restarted = startsNewConversation(webSearch)
        if (restarted) reset()
        if (conversation.isEmpty()) webDeclared = webSearch
        val mark = conversation.size
        var done = false
        var firstFailure: String? = null // why the first attempt stopped, if it was started over
        try {
            onStatus("Loading the season data…", "", emptyList())
            val data = pack ?: loadPack().also { pack = it }
            val system = data.optString("system_prompt").takeIf { it.isNotBlank() }
                ?: throw AskException("The season data is out of date. Try again after the next refresh.")
            conversation += JSONObject()
                .put("role", "user")
                .put("content", JSONArray().put(textBlock("Today is $today. ${webLine(webSearch)} $question")))
            // Fixed for the whole conversation (see webDeclared); the line above
            // says whether this question may search.
            val withWeb = webDeclared == true

            val usage = LongArray(5) // input, cache write, cache read, output, web searches
            var stop = ""
            var before: JSONObject? = null // the reply before this request, for a rejected request's error
            var asksAgain = 0
            var startedOver = false // after Anthropic refused to carry on (see below)
            var hops = 0
            var limit = MAX_HOPS
            while (hops < limit) {
                val hop = hops++
                answerWithSources(mark).let { (text, sources) ->
                    onStatus(if (hop == 0) "Thinking…" else "Still working…", text, sources)
                }
                val reply = try {
                    send(apiKey, requestBody(model, system, withWeb), requestDetail(hop, before))
                } catch (e: AskException) {
                    // Anthropic refused to carry on with an answer partway
                    // through: a reply of its own it wouldn't take back, the
                    // kind of quirk behind withClientCallsLast and the waiting
                    // search above. Rather than fail, the answer so far is set
                    // aside once and the question asked again. A first request
                    // that fails is a real error and goes to the reader.
                    val continuing = conversation.drop(mark + 1).any { it.optString("role") == "assistant" }
                    if (e.code != 400 || !continuing || startedOver) throw e
                    startedOver = true
                    firstFailure = e.message
                    rollBack(mark + 1)
                    containerId = null
                    conversation += JSONObject().put("role", "user")
                        .put("content", JSONArray().put(textBlock(RESTART_NOTE)))
                    limit = minOf(hops + MAX_HOPS, 2 * MAX_HOPS)
                    onStatus("Starting the answer over…", "", emptyList())
                    continue
                }
                before = reply
                reply.optJSONObject("usage")?.let { u ->
                    usage[0] += u.optLong("input_tokens")
                    usage[1] += u.optLong("cache_creation_input_tokens")
                    usage[2] += u.optLong("cache_read_input_tokens")
                    usage[3] += u.optLong("output_tokens")
                    usage[4] += u.optJSONObject("server_tool_use")?.optLong("web_search_requests") ?: 0L
                }
                reply.optJSONObject("container")?.optString("id")?.takeIf { it.isNotEmpty() }
                    ?.let { containerId = it }
                stop = reply.optString("stop_reason")
                if (stop == "refusal") break
                val content = reply.optJSONArray("content") ?: JSONArray()
                val calls = blocks(content).filter { it.optString("type") == "tool_use" }
                if (stop == "tool_use" && calls.isNotEmpty()) {
                    if (waitsOnSearch(content) && asksAgain < MAX_ASK_AGAIN) {
                        // Claude asked for a web search at the same moment its
                        // Python asked the app for a table. Anthropic's guide says
                        // the search runs when the table goes back, but that
                        // request is the one refused as a prefill. So the answer so
                        // far is set aside, paused code and all, and the question
                        // asked again with a note to keep the two apart.
                        asksAgain++
                        rollBack(mark + 1)
                        containerId = null // this reply's code is paused in it
                        conversation += JSONObject().put("role", "user")
                            .put("content", JSONArray().put(textBlock(SEPARATE_NOTE)))
                        limit = minOf(hops + MAX_HOPS, 2 * MAX_HOPS)
                        continue
                    }
                    // Claude's code asked for tables: answer every pending call
                    // in one message of tool_result blocks only, with the table
                    // requests last in the reply (see withClientCallsLast).
                    conversation += JSONObject().put("role", "assistant").put("content", withClientCallsLast(content))
                    val results = JSONArray()
                    calls.forEach { results.put(tableResult(it, data)) }
                    conversation += JSONObject().put("role", "user").put("content", results)
                    answerWithSources(mark).let { (text, sources) ->
                        onStatus("Running Python on the season data…", text, sources)
                    }
                    continue
                }
                if (stop == "pause_turn" || stop == "tool_use") {
                    // Paused: Anthropic stopped a long turn (a run of web
                    // searches, say), or stopped for a tool call the app has
                    // nothing to run.
                    if (endsOnWaitingServerTool(content)) {
                        // Anthropic's documented continuation: the reply goes
                        // back as it is, and the API resumes from the search or
                        // code run it hadn't carried out yet.
                        conversation += JSONObject().put("role", "assistant").put("content", content)
                    } else {
                        // Paused anywhere else, the reply as it is would read as
                        // a prefill. It is kept up to its last finished step and
                        // Claude is asked, in a message of its own, to carry on.
                        val finished = finishedPart(content)
                        if (finished.length() > 0) {
                            conversation += JSONObject().put("role", "assistant").put("content", finished)
                        }
                        if (!isContinueNote(conversation.last())) {
                            conversation += JSONObject().put("role", "user")
                                .put("content", JSONArray().put(textBlock(CONTINUE_NOTE)))
                        }
                    }
                    continue
                }
                // Finished, or cut short (max_tokens): kept as far as it is
                // complete, so a follow-up never comes after a half-made tool
                // call, and an empty reply isn't kept at all - the API refuses
                // an empty message anywhere but last.
                val kept = if (stop == "end_turn") content else settledPart(content)
                if (kept.length() > 0) {
                    conversation += JSONObject().put("role", "assistant").put("content", kept)
                }
                break
            }

            val notes = mutableListOf<String>()
            if (restarted) notes += NEW_CONVERSATION_NOTE
            if (asksAgain > 0 || startedOver) notes += STARTED_OVER_NOTE
            if (stop == "refusal") {
                rollBack(mark)
                done = true
                return AskAnswer(
                    text = "",
                    notes = listOf(costLine(model, usage)),
                    error = "Claude declined to answer that one. Try rephrasing it as a question about the stats."
                )
            }
            val (text, sources) = answerWithSources(mark)
            val code = codeRun(mark)
            val searches = searchesRun(mark)
            if (stop == "tool_use" || stop == "pause_turn") {
                // Out of round trips mid-answer. The thread cannot continue
                // from a pending tool call, so the question is rolled back.
                rollBack(mark)
                notes += "Claude was still working when the app stopped waiting. Try a narrower question."
            }
            if (stop == "max_tokens") notes += "The answer hit its length limit and was cut short."
            notes += costLine(model, usage)
            done = true
            return AskAnswer(text = text, code = code, notes = notes, sources = sources, searches = searches)
        } catch (e: AskException) {
            val error = listOfNotNull(e.message, firstFailure?.let { "Before starting over: $it" }).joinToString("\n\n")
            return AskAnswer(text = "", error = error)
        } finally {
            if (!done) rollBack(mark)
        }
    }

    /**
     * Forgets the thread back to [size] messages. If what goes held Claude's
     * code, its sandbox may be left paused on a call nobody will answer, so the
     * next request starts a fresh one; the season tables are fetched again
     * through get_table anyway.
     */
    private fun rollBack(size: Int) {
        if (conversation.size <= size) return
        val gone = conversation.subList(size, conversation.size)
        if (gone.any { m -> blocks(m.optJSONArray("content")).any { it.optString("type").endsWith("tool_use") } }) {
            containerId = null
        }
        gone.clear()
    }

    private fun requestBody(model: AskModel, system: String, withWeb: Boolean): JSONObject {
        val ephemeral = { JSONObject().put("type", "ephemeral") }
        return JSONObject()
            .put("model", model.id)
            .put("max_tokens", 16000)
            .put("cache_control", ephemeral())
            .put("system", JSONArray().put(textBlock(system).put("cache_control", ephemeral())))
            .put("tools", tools(withWeb))
            .put("messages", JSONArray(conversation))
            .apply {
                containerId?.let { put("container", it) }
                model.effort?.let { put("output_config", JSONObject().put("effort", it)) }
                if (model.fallbacks) put("fallbacks", "default")
            }
    }

    /**
     * Where in the answer a request sits, added to a rejected request's error
     * so a screenshot of it says which step failed and how the request ended.
     */
    private fun requestDetail(hop: Int, previous: JSONObject?): String {
        val last = conversation.lastOrNull()
        val types = blocks(last?.optJSONArray("content")).map { it.optString("type") }.takeLast(3)
        val reply = previous?.let { r ->
            val content = r.optJSONArray("content") ?: JSONArray()
            val n = content.length()
            " Reply before it (${r.optString("model")}, ${r.optString("stop_reason")}, " +
                "$n block${if (n == 1) "" else "s"}): ${describe(content)}."
        }.orEmpty()
        return "(For the developer: request ${hop + 1} of this answer; it ended with a " +
            "${last?.optString("role") ?: "missing"} message: ${types.joinToString(", ")}.$reply)"
    }

    private suspend fun send(apiKey: String, body: JSONObject, detail: String = ""): JSONObject {
        val headers = buildMap {
            put("x-api-key", apiKey)
            put("anthropic-version", "2023-06-01")
            if (body.has("fallbacks")) put("anthropic-beta", FALLBACK_BETA)
        }
        val response = try {
            http.post("$baseUrl/v1/messages?beta=true", headers, body.toString())
        } catch (e: HttpException) {
            // An answer that broke off partway is not a network that blocks
            // Anthropic: let it through to the "Something went wrong" message.
            if (e.code != null) throw e.cause ?: e
            val why = e.reason?.let { ": $it" }.orEmpty()
            throw AskException("Couldn't reach api.anthropic.com$why. Check your connection - some networks block it.")
        }
        val text = response.text()
        if (!response.isSuccessful) {
            val why = explainError(response.code, text)
            throw AskException(if (response.code == 400 && detail.isNotEmpty()) "$why\n\n$detail" else why, response.code)
        }
        return try {
            JSONObject(text)
        } catch (e: Exception) {
            throw AskException("Anthropic sent back something the app couldn't read.")
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

    /**
     * The answer text with each cited passage followed by its source numbers,
     * and the sources in order. A cited answer arrives as a run of text blocks
     * that together make up the sentences, so neighbouring text blocks are
     * joined as they are; text separated by Claude's code or searches starts a
     * new paragraph, as before.
     */
    private fun answerWithSources(mark: Int): Pair<String, List<AskSource>> {
        val out = StringBuilder()
        val sources = mutableListOf<AskSource>()
        for (message in conversation.drop(mark).filter { it.optString("role") == "assistant" }) {
            var joined = false
            for (block in blocks(message.optJSONArray("content"))) {
                if (block.optString("type") != "text") {
                    joined = false
                    continue
                }
                if (!joined && out.isNotBlank()) out.append("\n\n")
                out.append(block.optString("text"))
                val numbers = blocks(block.optJSONArray("citations")).mapNotNull { c ->
                    val url = c.optString("url")
                    if (!url.startsWith("https://") && !url.startsWith("http://")) return@mapNotNull null
                    // The API allows a null title; this JSON layer would read it as "null".
                    val title = if (c.isNull("title")) "" else c.optString("title")
                    (sources.firstOrNull { it.url == url } ?: AskSource(
                        sources.size + 1, title.ifBlank { url }, url
                    ).also { sources += it }).number
                }.distinct()
                if (numbers.isNotEmpty()) out.append(numbers.joinToString("") { " [$it]" })
                joined = true
            }
        }
        return out.toString().trim() to sources
    }

    /** What Claude searched the web for, in order. */
    private fun searchesRun(mark: Int): List<String> =
        conversation.drop(mark).filter { it.optString("role") == "assistant" }
            .flatMap { blocks(it.optJSONArray("content")) }
            .filter { it.optString("type") == "server_tool_use" && it.optString("name") == "web_search" }
            .mapNotNull { it.optJSONObject("input")?.optString("query")?.takeIf { q -> q.isNotBlank() } }

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
            "standings", "poll", "roster", "definitions",
            "opponent_rosters", "opponent_form", "team_serving", "national_leaders", "d1_results"
        )

        /** The dashboard's copy first, the repository's as a fallback, as for the season data. */
        val PACK_URLS = listOf(
            "https://inspectorgad.github.io/ku-volleyball/ask-data.json",
            "https://raw.githubusercontent.com/inspectorgad/ku-volleyball/main/docs/ask-data.json",
        )

        // One round trip can include several runs of Claude's code, hence the
        // five minutes allowed for an answer.
        val defaultHttp: Http by lazy { newHttp(connectSeconds = 15, readSeconds = 5 * 60) }

        suspend fun fetchPack(http: Http, urls: List<String> = PACK_URLS): JSONObject {
            for (url in urls) {
                val pack = try {
                    val r = http.get(url, mapOf("Cache-Control" to "no-cache"))
                    if (r.isSuccessful) JSONObject(r.text()) else null
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

        /** Anthropic's price for one web search: $10 per 1,000. */
        const val SEARCH_DOLLARS = 0.01

        /** Searches per question, at most: enough for a scouting report, and a cap on cost. */
        const val MAX_SEARCHES = 5

        const val NEW_CONVERSATION_NOTE =
            "Web search was turned on, so this question started a new conversation: Claude can't " +
                "take on web search partway through one."

        /** Told to Claude with every question, so it knows whether this one may search. */
        fun webLine(on: Boolean) =
            if (on) "Web search is on for this question. $SEARCH_APART" else "Web search is off for this question."

        /**
         * A search asked for while Claude's Python waits on the app's tables
         * sets the answer back (see [ask]), so Claude is asked not to.
         */
        const val SEARCH_APART =
            "Search before or after running Python, not in the same step: the app can't " +
                "run a search while your code is waiting on get_table."

        /**
         * Sent in the reader's place when an answer is set back because Claude
         * searched while its code waited on get_table (see [ask]).
         */
        const val SEPARATE_NOTE =
            "(Sent by the app, not the reader: your last step asked for a web search while your " +
                "Python was waiting on get_table, which the app can't handle, so it was set aside. " +
                "Start again, and run your searches and your Python in separate steps.)"

        /** Times an answer is set back and asked again for that. */
        const val MAX_ASK_AGAIN = 1

        /** usage = input, cache write, cache read, output tokens, and optionally web searches. */
        fun costLine(model: AskModel, usage: LongArray): String {
            val searches = usage.getOrElse(4) { 0L }
            val dollars = (usage[0] * model.input + usage[1] * model.input * 1.25 +
                usage[2] * model.input * model.cacheRead + usage[3] * model.output) / 1e6 +
                searches * SEARCH_DOLLARS
            val cents = dollars * 100
            val price = when {
                cents < 1 -> "under 1¢"
                cents < 10 -> "about ${fixed(cents, 1)}¢"
                else -> "about ${roundHalfUp(cents)}¢"
            }
            return "${model.shortName} · $price" +
                (if (usage[2] > 0) " (season data read from cache)" else "") +
                (if (searches > 0) " · ${searches} web search${if (searches == 1L) "" else "es"} included" else "") +
                " · plus a little code-execution time"
        }

        private fun textBlock(text: String) = JSONObject().put("type", "text").put("text", text)

        private fun blocks(array: JSONArray?): List<JSONObject> =
            if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optJSONObject(it) }

        private fun hasText(message: JSONObject) =
            blocks(message.optJSONArray("content")).any { it.optString("type") == "text" }

        /**
         * Sent in the reader's place when a paused answer is resumed (see
         * [ask]); it isn't one of the reader's questions. Same words as the
         * dashboard's.
         */
        const val CONTINUE_NOTE =
            "(Sent by the app, not the reader: Anthropic paused your answer partway through. " +
                "Carry on from where it stopped - run again any search that hadn't happened yet - " +
                "without repeating what you've already written.)"

        private fun isContinueNote(message: JSONObject) =
            blocks(message.optJSONArray("content")).any { it.optString("text") == CONTINUE_NOTE }

        /** One of the app's own notes to Claude, not a question of the reader's. */
        private fun isAppNote(message: JSONObject) =
            blocks(message.optJSONArray("content")).any {
                it.optString("text") in setOf(CONTINUE_NOTE, SEPARATE_NOTE, RESTART_NOTE)
            }

        /**
         * Sent in the reader's place when Anthropic refused to carry on with an
         * answer and it was started over (see [ask]).
         */
        const val RESTART_NOTE =
            "(Sent by the app, not the reader: Anthropic couldn't carry on with your answer, so it was set " +
                "aside. Start again. Run one piece of Python at a time, and run your searches and your " +
                "Python in separate steps.)"

        /** Shown under an answer that was started over, since its cost covers both attempts. */
        const val STARTED_OVER_NOTE =
            "Claude started this answer over partway through, because Anthropic couldn't continue the " +
                "first attempt. The cost below covers both."

        /** A server tool's result: a block that answers a call but isn't the app's own tool_result. */
        private fun isServerResult(b: JSONObject) =
            b.optString("tool_use_id").isNotEmpty() && b.optString("type") != "tool_result"

        /**
         * Claude's reply with any search or code results that came after its
         * table requests moved to just before them. When Claude runs two pieces
         * of Python at once, one can finish after the other has paused on
         * get_table; Anthropic then lists that result after the table request,
         * and refused the reply sent back that way ("tool_use ids were found
         * without tool_result blocks immediately after"). Results pair with
         * their calls by id, not position. Left as it is when anything else
         * follows a table request, or when a thinking block does, since moving
         * a block from before a thinking block invalidates it.
         */
        fun withClientCallsLast(content: JSONArray): JSONArray {
            val all = blocks(content)
            val first = all.indexOfFirst { it.optString("type") == "tool_use" }
            if (first < 0) return content
            val tail = all.drop(first)
            val results = tail.filter { isServerResult(it) }
            if (results.isEmpty()) return content
            if (tail.any { it.optString("type") != "tool_use" && !isServerResult(it) }) return content
            return JSONArray(all.take(first) + results + tail.filter { it.optString("type") == "tool_use" })
        }

        private fun answeredIds(all: List<JSONObject>): Set<String> =
            all.mapNotNull { b -> b.optString("tool_use_id").takeIf { it.isNotEmpty() } }.toSet()

        /** A server tool call in [all] with no result after it: not carried out yet. */
        private fun isWaiting(b: JSONObject, answered: Set<String>) =
            b.optString("type") == "server_tool_use" && b.optString("id") !in answered

        /**
         * True when a reply that stopped for the app's tables also holds a web
         * search Claude asked for at the same moment. Anthropic's guide says
         * that search runs when the tables go back, but a web-search question
         * of exactly that kind was refused there as a prefill.
         */
        fun waitsOnSearch(content: JSONArray): Boolean {
            val all = blocks(content)
            val answered = answeredIds(all)
            return all.any { isWaiting(it, answered) && it.optString("name") == "web_search" }
        }

        /**
         * True when a paused reply ends on a search or code run Anthropic hasn't
         * carried out yet - the shape its guide resumes by sending the reply
         * back as it is.
         */
        fun endsOnWaitingServerTool(content: JSONArray): Boolean {
            val all = blocks(content)
            return all.isNotEmpty() && isWaiting(all.last(), answeredIds(all))
        }

        /**
         * A reply's blocks in a few words, for a rejected request's error: each
         * block's type, the tool it calls and who called it, a search or code run
         * not yet carried out, and a server tool's error. Long replies keep their
         * first two and last six.
         */
        fun describe(content: JSONArray): String {
            val all = blocks(content)
            val answered = answeredIds(all)
            val words = all.map { b ->
                val type = b.optString("type")
                listOfNotNull(
                    type,
                    b.optString("name").takeIf { type.endsWith("tool_use") && it.isNotEmpty() },
                    b.optJSONObject("caller")?.optString("type")?.takeIf { it.isNotEmpty() }?.let { "via $it" },
                    "(waiting)".takeIf { isWaiting(b, answered) },
                    b.optJSONObject("content")?.optString("error_code")?.takeIf { it.isNotEmpty() }?.let { "(error $it)" }
                ).joinToString(" ")
            }
            return when {
                words.isEmpty() -> "nothing"
                words.size > 8 -> (words.take(2) + "..." + words.takeLast(6)).joinToString(", ")
                else -> words.joinToString(", ")
            }
        }

        /**
         * A reply that was cut short (max_tokens), kept as far as it is
         * complete: no search, code run or table request left half made, and no
         * thinking left at the end.
         */
        fun settledPart(content: JSONArray): JSONArray {
            var kept = blocks(finishedPart(content))
            while (kept.isNotEmpty() && kept.last().optString("type") in setOf("tool_use", "thinking", "redacted_thinking")) {
                kept = kept.dropLast(1)
            }
            return JSONArray(kept)
        }

        /**
         * A paused reply cut back to its last finished step: everything before
         * the first search or code run Anthropic hadn't carried out yet (a
         * server tool call with no result), less any thinking left at the end.
         * Only the end is cut, so what is kept reads exactly as Claude wrote it.
         */
        fun finishedPart(content: JSONArray): JSONArray {
            val all = blocks(content)
            val answered = answeredIds(all)
            val pending = all.indexOfFirst { isWaiting(it, answered) }
            var kept = if (pending < 0) all else all.subList(0, pending)
            while (kept.isNotEmpty() && kept.last().optString("type") in setOf("thinking", "redacted_thinking")) {
                kept = kept.dropLast(1)
            }
            return JSONArray(kept)
        }

        // The basic web search, called by Claude directly rather than from its
        // Python: its results and citations then reach the answer as they are,
        // and it cannot be confused with the code-execution sandbox the season
        // tables live in. Localized to Lawrence so local coverage ranks well.
        private fun webSearchTool(): JSONObject = JSONObject()
            .put("type", "web_search_20250305")
            .put("name", "web_search")
            .put("max_uses", MAX_SEARCHES)
            .put(
                "user_location",
                JSONObject().put("type", "approximate").put("city", "Lawrence").put("region", "Kansas")
                    .put("country", "US").put("timezone", "America/Chicago")
            )

        private fun tools(withWeb: Boolean): JSONArray = JSONArray()
            .put(JSONObject().put("type", "code_execution_20260120").put("name", "code_execution"))
            .put(
                JSONObject()
                    .put("name", "get_table")
                    .put(
                        "description",
                        "Returns one table of the Kansas volleyball season data (or, for the opponent_*, team_serving, " +
                            "national_leaders and d1_results tables, data on other teams) as a JSON string: a list of " +
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
            .apply { if (withWeb) put(webSearchTool()) }
    }
}
