package com.example

import com.example.data.ASK_MODELS
import com.example.data.AskEngine
import com.example.json.JSONArray
import com.example.json.JSONObject
import com.example.platform.OkHttpHttp
import com.example.ui.MdBlock
import com.example.ui.inlineMarkdown
import com.example.ui.parseMarkdown
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AskEngineTest {

    private lateinit var server: MockWebServer
    private val opus = ASK_MODELS.first { it.id == "claude-opus-5-5" }
    private val sonnet = ASK_MODELS.first { it.id == "claude-sonnet-5" }
    private val pack = JSONObject()
        .put("system_prompt", "RULES\n\nsummary")
        .put("matches", JSONArray().put(JSONObject().put("opponent", "Houston").put("result", "W")))
        .put("definitions", JSONObject().put("k", "kills"))

    @Before
    fun start() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun stop() {
        // A request cancelled mid-response can still be closing when the test
        // ends, and shutdown then throws; that is cleanup, not a failure.
        try {
            server.shutdown()
        } catch (e: java.io.IOException) {
        }
    }

    private fun engine() = AskEngine(
        // OkHttp's own defaults, which these tests were written against.
        http = OkHttpHttp(connectSeconds = 10, readSeconds = 10),
        baseUrl = server.url("/").toString().trimEnd('/'),
        loadPack = { pack }
    )

    private fun reply(stop: String, vararg blocks: JSONObject, container: String? = "cont_1") =
        MockResponse().setBody(
            JSONObject()
                .put("type", "message")
                .put("role", "assistant")
                .put("model", "claude-opus-5-5")
                .put("stop_reason", stop)
                .put("content", JSONArray(blocks.toList()))
                .put("usage", JSONObject().put("input_tokens", 1000).put("output_tokens", 200)
                    .put("cache_read_input_tokens", 5000))
                .apply { container?.let { put("container", JSONObject().put("id", it)) } }
                .toString()
        )

    private fun text(t: String) = JSONObject().put("type", "text").put("text", t)

    private fun nextBody(): JSONObject =
        JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())

    @Test
    fun `a get_table call is answered from the season data and the code carries on`() = runBlocking {
        server.enqueue(
            reply(
                "tool_use",
                JSONObject().put("type", "server_tool_use").put("id", "srv_1").put("name", "code_execution")
                    .put("input", JSONObject().put("code", "df = await get_table({'table': 'matches'})")),
                JSONObject().put("type", "tool_use").put("id", "tu_1").put("name", "get_table")
                    .put("input", JSONObject().put("table", "matches"))
                    .put("caller", JSONObject().put("type", "code_execution_20260120").put("tool_id", "srv_1"))
            )
        )
        server.enqueue(reply("end_turn", text("KU are **1-0** against Houston.")))

        val answer = engine().ask("How did we do?", "sk-ant-test", opus, "2026-09-26")

        assertEquals("KU are **1-0** against Houston.", answer.text)
        assertEquals(listOf("df = await get_table({'table': 'matches'})"), answer.code)
        assertNull(answer.error)
        assertTrue(answer.notes.last().startsWith("Claude Opus 5.5 · "))

        val first = server.takeRequest()
        assertEquals("/v1/messages?beta=true", first.path)
        assertEquals("sk-ant-test", first.getHeader("x-api-key"))
        assertEquals(AskEngine.FALLBACK_BETA, first.getHeader("anthropic-beta"))
        val body1 = JSONObject(first.body.readUtf8())
        assertEquals("claude-opus-5-5", body1.getString("model"))
        assertEquals("medium", body1.getJSONObject("output_config").getString("effort"))
        assertEquals("default", body1.getString("fallbacks"))
        assertEquals("RULES\n\nsummary", body1.getJSONArray("system").getJSONObject(0).getString("text"))
        assertFalse(body1.has("container"))
        val tools = body1.getJSONArray("tools")
        assertEquals(2, tools.length()) // no web search unless the reader turns it on
        assertEquals("code_execution_20260120", tools.getJSONObject(0).getString("type"))
        assertEquals("code_execution_20260120",
            tools.getJSONObject(1).getJSONArray("allowed_callers").getString(0))
        assertEquals(
            "Today is 2026-09-26. Web search is off for this question. How did we do?",
            body1.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text")
        )

        // The follow-up: same container, the assistant turn sent back as
        // received, and a user turn holding only the tool result.
        val body2 = nextBody()
        assertEquals("cont_1", body2.getString("container"))
        val messages = body2.getJSONArray("messages")
        assertEquals(3, messages.length())
        assertEquals("tu_1", messages.getJSONObject(1).getJSONArray("content").getJSONObject(1).getString("id"))
        val results = messages.getJSONObject(2).getJSONArray("content")
        assertEquals(1, results.length())
        val result = results.getJSONObject(0)
        assertEquals("tool_result", result.getString("type"))
        assertEquals("tu_1", result.getString("tool_use_id"))
        assertEquals("Houston", JSONArray(result.getString("content")).getJSONObject(0).getString("opponent"))
    }

    private fun cited(t: String, vararg urls: String) = text(t).put(
        "citations",
        JSONArray(urls.map {
            JSONObject().put("type", "web_search_result_location").put("url", it)
                .put("title", "Page $it").put("encrypted_index", "idx").put("cited_text", "...")
        })
    )

    @Test
    fun `with web search on, the tool is offered and cited pages are numbered and listed`() = runBlocking {
        val engine = engine()
        server.enqueue(
            MockResponse().setBody(
                JSONObject().put("type", "message").put("role", "assistant").put("stop_reason", "end_turn")
                    .put("content", JSONArray(listOf(
                        text("Here is what I found."),
                        JSONObject().put("type", "server_tool_use").put("id", "srv_w").put("name", "web_search")
                            .put("input", JSONObject().put("query", "Iowa State volleyball injuries")),
                        JSONObject().put("type", "web_search_tool_result").put("tool_use_id", "srv_w")
                            .put("content", JSONArray().put(JSONObject().put("type", "web_search_result")
                                .put("url", "https://cyclones.com/a").put("title", "A")
                                .put("encrypted_content", "enc"))),
                        text("Iowa State "),
                        cited("lost their libero to injury", "https://cyclones.com/a"),
                        text(" and "),
                        cited("start a freshman setter.", "https://cyclones.com/b", "https://cyclones.com/a")
                    )))
                    .put("usage", JSONObject().put("input_tokens", 10_000).put("output_tokens", 1_000)
                        .put("server_tool_use", JSONObject().put("web_search_requests", 2)))
                    .toString()
            )
        )
        val answer = engine.ask("Any news on Iowa State?", "sk-ant-test", opus, "2026-11-12", webSearch = true)
        assertEquals(
            "Here is what I found.\n\nIowa State lost their libero to injury [1] and start a freshman setter. [2] [1]",
            answer.text
        )
        assertEquals(listOf("https://cyclones.com/a", "https://cyclones.com/b"), answer.sources.map { it.url })
        assertEquals(listOf(1, 2), answer.sources.map { it.number })
        assertEquals(listOf("Iowa State volleyball injuries"), answer.searches)
        // 10k at $4 + 1k at $20 = 6 cents, plus 2 searches at 1 cent.
        assertTrue(answer.notes.last(), answer.notes.last().startsWith("Claude Opus 5.5 · about 8.0¢ · 2 web searches included"))

        val body = nextBody()
        val web = body.getJSONArray("tools").getJSONObject(2)
        assertEquals("web_search_20250305", web.getString("type"))
        assertEquals(AskEngine.MAX_SEARCHES, web.getInt("max_uses"))
        assertTrue(body.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0)
            .getString("text").contains("Web search is on for this question."))

        // Turned off for a follow-up: the tool stays declared, since the tool
        // list can't change partway through, and the question says search is off.
        server.enqueue(reply("end_turn", text("From the tables only.")))
        engine.ask("And their serving?", "sk-ant-test", opus, "2026-11-12", webSearch = false)
        val body2 = nextBody()
        assertEquals(3, body2.getJSONArray("tools").length())
        val messages = body2.getJSONArray("messages")
        // The search results go back exactly as received, encrypted content and all.
        val result = messages.getJSONObject(1).getJSONArray("content").getJSONObject(2)
        assertEquals("enc", result.getJSONArray("content").getJSONObject(0).getString("encrypted_content"))
        assertTrue(messages.getJSONObject(2).getJSONArray("content").getJSONObject(0)
            .getString("text").contains("Web search is off for this question."))
    }

    @Test
    fun `a page without a title is listed by its address, and partial answers carry their sources`() = runBlocking {
        val noTitle = text("Reported out.").put(
            "citations",
            JSONArray().put(
                JSONObject().put("type", "web_search_result_location").put("url", "https://x.example/a")
                    .put("title", JSONObject.NULL).put("encrypted_index", "i").put("cited_text", "...")
            )
        )
        // A cited paragraph, then Claude's code asks for a table before finishing.
        server.enqueue(
            reply(
                "tool_use",
                noTitle,
                JSONObject().put("type", "tool_use").put("id", "tu_2").put("name", "get_table")
                    .put("input", JSONObject().put("table", "matches"))
            )
        )
        server.enqueue(reply("end_turn", text("Done.")))
        val partials = mutableListOf<Pair<String, List<String>>>()
        val answer = engine().ask("News?", "sk-ant-test", opus, "2026-11-12", webSearch = true) { _, text, sources ->
            partials += text to sources.map { it.title }
        }
        assertEquals(listOf("https://x.example/a"), answer.sources.map { it.title })
        assertTrue(partials.any { (text, titles) -> text.contains("[1]") && titles == listOf("https://x.example/a") })
    }

    private val thinking = JSONObject().put("type", "thinking").put("thinking", "").put("signature", "sig")
    private fun search(id: String, q: String) =
        JSONObject().put("type", "server_tool_use").put("id", id).put("name", "web_search")
            .put("input", JSONObject().put("query", q))
    private fun results(id: String) =
        JSONObject().put("type", "web_search_tool_result").put("tool_use_id", id)
            .put("content", JSONArray().put(JSONObject().put("type", "web_search_result")
                .put("url", "https://cubuffs.com/r").put("title", "Roster").put("encrypted_content", "enc")))
    private fun code(id: String) =
        JSONObject().put("type", "server_tool_use").put("id", id).put("name", "code_execution")
            .put("input", JSONObject().put("code", "await get_table({'table': 'opponent_rosters'})"))
    private fun tableCall(id: String, codeId: String) =
        JSONObject().put("type", "tool_use").put("id", id).put("name", "get_table")
            .put("input", JSONObject().put("table", "matches"))
            .put("caller", JSONObject().put("type", "code_execution_20260120").put("tool_id", codeId))
    private fun roles(body: JSONObject): List<String> {
        val m = body.getJSONArray("messages")
        return (0 until m.length()).map { m.getJSONObject(it).getString("role") }
    }

    @Test
    fun `a paused answer goes back as it is when it ends on a search not yet run, and is nudged otherwise`() = runBlocking {
        val engine = engine()
        // 1. Paused on a search not yet run: Anthropic's documented continuation.
        server.enqueue(reply("pause_turn", text("Checking the news."), thinking, search("srv_1", "Colorado injuries")))
        // 2. Paused after a finished search, with thinking at the end: cut back and nudged.
        server.enqueue(reply("pause_turn", results("srv_1"), search("srv_2", "Colorado volleyball injuries"),
            results("srv_2"), text("Colorado's lineup"), thinking))
        server.enqueue(reply("end_turn", text("has no reported injuries.")))

        val answer = engine.ask("Any injuries for Colorado?", "sk-ant-test", opus, "2026-11-12", webSearch = true)
        assertNull(answer.error)
        assertTrue(answer.text, answer.text.startsWith("Checking the news."))
        assertTrue(answer.text, answer.text.endsWith("has no reported injuries."))
        assertEquals(listOf("Colorado injuries", "Colorado volleyball injuries"), answer.searches)

        nextBody()
        val second = nextBody()
        assertEquals(listOf("user", "assistant"), roles(second))
        assertEquals(3, second.getJSONArray("messages").getJSONObject(1).getJSONArray("content").length())

        val third = nextBody()
        assertEquals(listOf("user", "assistant", "assistant", "user"), roles(third))
        val kept = third.getJSONArray("messages").getJSONObject(2).getJSONArray("content")
        assertEquals(4, kept.length()) // the trailing thinking is gone
        assertEquals("text", kept.getJSONObject(3).getString("type"))
        assertEquals(AskEngine.CONTINUE_NOTE, third.getJSONArray("messages").getJSONObject(3)
            .getJSONArray("content").getJSONObject(0).getString("text"))
        // The nudge isn't one of the reader's questions.
        assertEquals(1, engine.turns)

        assertEquals(0, AskEngine.finishedPart(JSONArray().put(search("a", "q"))).length())
        assertEquals(2, AskEngine.finishedPart(JSONArray().put(search("a", "q")).put(results("a"))).length())
        assertTrue(AskEngine.endsOnWaitingServerTool(JSONArray().put(text("x")).put(search("a", "q"))))
        assertFalse(AskEngine.endsOnWaitingServerTool(JSONArray().put(search("a", "q")).put(thinking)))
    }

    @Test
    fun `an empty or cut-short reply never leaves the thread unable to take a follow-up`() = runBlocking {
        val engine = engine()
        // A nudged pause, then an empty end_turn: nothing empty is kept.
        server.enqueue(reply("pause_turn", text("Colorado lists no injuries.")))
        server.enqueue(reply("end_turn"))
        engine.ask("Injuries?", "sk-ant-test", opus, "2026-11-12", webSearch = true)
        // Cut short with code half made: only the finished part is kept.
        server.enqueue(reply("max_tokens", text("Working it out."), code("srv_c")))
        engine.ask("Their serving?", "sk-ant-test", opus, "2026-11-12", webSearch = true)
        server.enqueue(reply("end_turn", text("Done.")))
        engine.ask("And ours?", "sk-ant-test", opus, "2026-11-12", webSearch = true)

        repeat(3) { nextBody() }
        val last = nextBody().getJSONArray("messages")
        for (i in 0 until last.length()) {
            assertTrue("message $i is empty", last.getJSONObject(i).getJSONArray("content").length() > 0)
        }
        // The cut-short reply kept its text and lost the half-made code run.
        val cut = last.getJSONObject(last.length() - 2).getJSONArray("content")
        assertEquals(listOf("text"), (0 until cut.length()).map { cut.getJSONObject(it).getString("type") })
        assertEquals(1, AskEngine.settledPart(JSONArray().put(text("x")).put(tableCall("tu", "srv_c"))).length())
    }

    @Test
    fun `a web search asked for while Claude's code waits on a table sets the answer back once`() = runBlocking {
        val engine = engine()
        server.enqueue(reply("tool_use", thinking, search("srv_w", "Colorado volleyball injuries"),
            code("srv_c"), tableCall("tu_9", "srv_c"), container = "cont_a"))
        server.enqueue(reply("tool_use", thinking, code("srv_d"), tableCall("tu_10", "srv_d"), container = "cont_b"))
        server.enqueue(reply("end_turn", text("No injuries reported."), container = "cont_b"))

        val answer = engine.ask("Injuries?", "sk-ant-test", opus, "2026-11-12", webSearch = true)
        assertNull(answer.error)
        assertEquals("No injuries reported.", answer.text)
        assertEquals(AskEngine.SET_ASIDE_NOTE, answer.notes.first())
        val first = nextBody().getJSONArray("messages").getJSONObject(0).getJSONArray("content")
            .getJSONObject(0).getString("text")
        assertTrue(first, first.contains(AskEngine.SEARCH_APART))

        // Asked again: the question and the app's note, in a fresh sandbox.
        val second = nextBody()
        assertEquals(listOf("user", "user"), roles(second))
        assertEquals(AskEngine.SEPARATE_NOTE, second.getJSONArray("messages").getJSONObject(1)
            .getJSONArray("content").getJSONObject(0).getString("text"))
        assertFalse(second.has("container"))

        val third = nextBody()
        assertEquals(listOf("user", "user", "assistant", "user"), roles(third))
        assertEquals("cont_b", third.getString("container"))
        assertEquals(1, engine.turns)

        // Only once: a second such reply goes back as Anthropic's guide says.
        val again = engine()
        server.enqueue(reply("tool_use", search("w1", "q"), code("c1"), tableCall("t1", "c1")))
        server.enqueue(reply("tool_use", search("w2", "q"), code("c2"), tableCall("t2", "c2")))
        server.enqueue(reply("end_turn", text("Done.")))
        again.ask("Injuries?", "sk-ant-test", opus, "2026-11-12", webSearch = true)
        repeat(2) { nextBody() }
        assertEquals(listOf("user", "user", "assistant", "user"), roles(nextBody()))
    }

    private fun rejected(message: String) = MockResponse().setResponseCode(400).setBody(
        JSONObject().put("type", "error").put("error", JSONObject().put("type", "invalid_request_error")
            .put("message", message)).toString()
    )

    @Test
    fun `a rejected request says which step failed, how it ended and what the reply before it held`() = runBlocking {
        server.enqueue(reply("pause_turn", text("Looking.")))
        server.enqueue(rejected("Something about the request."))
        // Refused partway through, the answer is started over once; refused again, the reader sees both.
        server.enqueue(rejected("Something else."))
        val answer = engine().ask("News?", "sk-ant-test", opus, "2026-11-12", webSearch = true)
        val error = answer.error!!
        assertTrue(error, error.startsWith("Anthropic couldn't accept the request: Something else."))
        // Request 3 starts over from the question, so it continues no earlier reply.
        assertTrue(error, error.contains(
            "(For the developer: request 3 of this answer; it ended with a user message: text.)"))
        assertTrue(error, error.contains(
            "Before starting over: Anthropic couldn't accept the request: Something about the request.\n\n" +
                "(For the developer: request 2 of this answer; it ended with a user message: text. " +
                "Reply before it (claude-opus-5-5, pause_turn, 1 block): text.)"))
        assertEquals(
            "thinking, server_tool_use web_search (waiting), server_tool_use code_execution (waiting), " +
                "tool_use get_table via code_execution_20260120",
            AskEngine.describe(JSONArray(listOf(thinking, search("w", "q"), code("c"), tableCall("t", "c"))))
        )
    }

    @Test
    fun `an answer Anthropic won't continue is started over once, and a first request that fails is not`() = runBlocking {
        val engine = engine()
        server.enqueue(reply("tool_use", code("srv_c"), tableCall("tu_1", "srv_c"), container = "cont_a"))
        server.enqueue(rejected("messages.1: `tool_use` ids were found without `tool_result` blocks immediately after"))
        server.enqueue(reply("end_turn", text("Done."), container = "cont_b"))
        val answer = engine.ask("Recap?", "sk-ant-test", opus, "2026-11-13", webSearch = true)
        assertNull(answer.error)
        assertEquals("Done.", answer.text)
        assertEquals(AskEngine.STARTED_OVER_NOTE, answer.notes.first())
        // Why the first attempt stopped stays visible even though the second worked.
        assertTrue(answer.notes[1], answer.notes[1].startsWith(
            "For the developer: started over after: Anthropic couldn't accept the request: messages.1:"))
        nextBody(); nextBody()
        val third = nextBody()
        assertEquals(listOf("user", "user"), roles(third))
        assertEquals(AskEngine.RESTART_NOTE, third.getJSONArray("messages").getJSONObject(1)
            .getJSONArray("content").getJSONObject(0).getString("text"))
        assertFalse(third.has("container"))
        assertEquals(1, engine.turns)

        // A first request Anthropic rejects is a real error: no second try.
        val fresh = engine()
        server.enqueue(rejected("Bad request."))
        val failed = fresh.ask("News?", "sk-ant-test", opus, "2026-11-13")
        assertTrue(failed.error!!, failed.error!!.startsWith("Anthropic couldn't accept the request: Bad request."))
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `results that come after a table request are moved before it`() = runBlocking {
        // Two code runs at once: one paused on get_table, the other finished after it.
        val finished = JSONObject().put("type", "code_execution_tool_result").put("tool_use_id", "srv_b")
            .put("content", JSONObject().put("type", "code_execution_result").put("stdout", "ok"))
        server.enqueue(reply("tool_use", thinking, code("srv_a"), code("srv_b"), tableCall("tu_1", "srv_a"), finished))
        server.enqueue(reply("end_turn", text("Done.")))
        engine().ask("Recap?", "sk-ant-test", opus, "2026-11-13")
        nextBody()
        val sent = nextBody().getJSONArray("messages").getJSONObject(1).getJSONArray("content")
        assertEquals(
            listOf("thinking", "server_tool_use", "server_tool_use", "code_execution_tool_result", "tool_use"),
            (0 until sent.length()).map { sent.getJSONObject(it).getString("type") }
        )
        // Left alone when a thinking block follows the table request, or when nothing needs moving.
        val withThinking = JSONArray(listOf(code("a"), tableCall("t", "a"), thinking, finished))
        assertEquals("tool_use", AskEngine.withClientCallsLast(withThinking).getJSONObject(1).getString("type"))
        val plain = JSONArray(listOf(code("a"), tableCall("t", "a")))
        assertTrue(AskEngine.withClientCallsLast(plain) === plain)
    }

    @Test
    fun `the tool list never changes partway through a conversation`() = runBlocking {
        val engine = engine()
        // On from the first question, off later, with no search ever made: still declared.
        server.enqueue(reply("end_turn", text("One.")))
        server.enqueue(reply("end_turn", text("Two.")))
        engine.ask("One?", "sk-ant-test", opus, "2026-11-12", webSearch = true)
        engine.ask("Two?", "sk-ant-test", opus, "2026-11-12", webSearch = false)
        assertEquals(3, nextBody().getJSONArray("tools").length())
        assertEquals(3, nextBody().getJSONArray("tools").length())

        // Off from the first question, then turned on: a new conversation, with a note.
        engine.reset()
        server.enqueue(reply("end_turn", text("Three.")))
        engine.ask("Three?", "sk-ant-test", opus, "2026-11-12", webSearch = false)
        assertEquals(2, nextBody().getJSONArray("tools").length())
        assertFalse(engine.startsNewConversation(false))
        assertTrue(engine.startsNewConversation(true))
        server.enqueue(reply("end_turn", text("Four.")))
        val four = engine.ask("Four?", "sk-ant-test", opus, "2026-11-12", webSearch = true)
        assertEquals(AskEngine.NEW_CONVERSATION_NOTE, four.notes.first())
        val body = nextBody()
        assertEquals(3, body.getJSONArray("tools").length())
        assertEquals(1, body.getJSONArray("messages").length())
        assertFalse(body.has("container"))
        assertFalse(engine.startsNewConversation(true))
    }

    @Test
    fun `a first question that fails leaves the next one free to choose`() = runBlocking {
        val engine = engine()
        server.enqueue(reply("refusal"))
        engine.ask("Off-topic", "sk-ant-test", opus, "2026-11-12", webSearch = false)
        server.takeRequest()
        // The thread is empty again, so turning search on is not a restart.
        assertFalse(engine.startsNewConversation(true))
        server.enqueue(reply("end_turn", text("OK.")))
        val answer = engine.ask("News?", "sk-ant-test", opus, "2026-11-12", webSearch = true)
        assertFalse(answer.notes.contains(AskEngine.NEW_CONVERSATION_NOTE))
        assertEquals(3, nextBody().getJSONArray("tools").length())
    }

    @Test
    fun `an unknown table comes back as an error the code can read`() = runBlocking {
        server.enqueue(
            reply(
                "tool_use",
                JSONObject().put("type", "tool_use").put("id", "tu_9").put("name", "get_table")
                    .put("input", JSONObject().put("table", "injuries"))
            )
        )
        server.enqueue(reply("end_turn", text("That isn't in the data.")))
        engine().ask("Who is injured?", "sk-ant-test", sonnet, "2026-09-26")
        server.takeRequest()
        val body2 = nextBody()
        val result = body2.getJSONArray("messages").getJSONObject(2).getJSONArray("content").getJSONObject(0)
        assertTrue(result.getBoolean("is_error"))
        assertTrue(result.getString("content").contains("ku_lines"))
    }

    @Test
    fun `follow-ups keep the thread, and Sonnet is sent without fallbacks`() = runBlocking {
        val engine = engine()
        server.enqueue(reply("end_turn", text("First answer.")))
        server.enqueue(reply("end_turn", text("Second answer.")))
        engine.ask("One?", "sk-ant-test", sonnet, "2026-09-26")
        engine.ask("Two?", "sk-ant-test", sonnet, "2026-09-26")
        val first = server.takeRequest()
        assertNull(first.getHeader("anthropic-beta"))
        val body1 = JSONObject(first.body.readUtf8())
        assertFalse(body1.has("fallbacks"))
        assertFalse(body1.has("output_config"))
        val body2 = nextBody()
        assertEquals(3, body2.getJSONArray("messages").length())
        assertEquals("cont_1", body2.getString("container"))

        engine.reset()
        server.enqueue(reply("end_turn", text("Fresh.")))
        engine.ask("Three?", "sk-ant-test", sonnet, "2026-09-26")
        val body3 = nextBody()
        assertEquals(1, body3.getJSONArray("messages").length())
        assertFalse(body3.has("container"))
    }

    @Test
    fun `a rejected key is explained and leaves no trace in the thread`() = runBlocking {
        val engine = engine()
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""")
        )
        val failed = engine.ask("One?", "sk-ant-bad", opus, "2026-09-26")
        assertTrue(failed.error!!.contains("API key was rejected"))
        server.takeRequest()

        server.enqueue(reply("end_turn", text("Fine now.")))
        engine.ask("Two?", "sk-ant-good", opus, "2026-09-26")
        assertEquals(1, nextBody().getJSONArray("messages").length())
    }

    @Test
    fun `a refusal is said plainly and rolled back`() = runBlocking {
        val engine = engine()
        server.enqueue(reply("refusal"))
        val answer = engine.ask("Something off-topic", "sk-ant-test", opus, "2026-09-26")
        assertTrue(answer.error!!.contains("declined"))
        server.takeRequest()
        server.enqueue(reply("end_turn", text("OK.")))
        engine.ask("Back on topic", "sk-ant-test", opus, "2026-09-26")
        assertEquals(1, nextBody().getJSONArray("messages").length())
    }

    @Test
    fun `stopping cancels the request and rolls the question back`() = runBlocking {
        val engine = engine()
        server.enqueue(reply("end_turn", text("Too late.")).setBodyDelay(3, TimeUnit.SECONDS))
        val job = async { engine.ask("Slow?", "sk-ant-test", opus, "2026-09-26") }
        // This thread must stay free for the request to go out: wait by suspending.
        while (server.requestCount == 0) delay(20)
        job.cancel()
        val stopped = try {
            job.await(); false
        } catch (e: CancellationException) {
            true
        }
        assertTrue(stopped)
        server.takeRequest()
        server.enqueue(reply("end_turn", text("Quick.")))
        engine.ask("Quick?", "sk-ant-test", opus, "2026-09-26")
        assertEquals(1, nextBody().getJSONArray("messages").length())
    }

    @Test
    fun `an answer that breaks off partway is not blamed on the network, and is rolled back`() = runBlocking {
        val engine = engine()
        server.enqueue(
            reply("end_turn", text("x".repeat(20_000))).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )
        // As before the HTTP layer moved: the read error itself comes out, and
        // the screen shows it as "Something went wrong", not "Couldn't reach".
        val thrown = try {
            engine.ask("Long?", "sk-ant-test", opus, "2026-09-26")
            null
        } catch (e: java.io.IOException) {
            e
        }
        assertTrue(thrown != null)
        server.takeRequest()
        server.enqueue(reply("end_turn", text("Short.")))
        engine.ask("Short?", "sk-ant-test", opus, "2026-09-26")
        assertEquals(1, nextBody().getJSONArray("messages").length())
    }

    @Test
    fun `an unreachable Anthropic is explained with the reason`() = runBlocking {
        val closed = MockWebServer().apply { start() }
        val url = closed.url("/").toString().trimEnd('/')
        closed.shutdown()
        val engine = AskEngine(http = OkHttpHttp(connectSeconds = 5, readSeconds = 5), baseUrl = url, loadPack = { pack })
        val answer = engine.ask("Anyone there?", "sk-ant-test", opus, "2026-10-25")
        assertEquals(
            "Couldn't reach api.anthropic.com: the connection was refused. Check your connection - some networks block it.",
            answer.error
        )
    }

    @Test
    fun `server errors and cost lines read plainly`() {
        assertTrue(AskEngine.explainError(429, "").contains("rate limit"))
        assertTrue(AskEngine.explainError(529, "").contains("busy"))
        assertTrue(
            AskEngine.explainError(400, """{"error":{"message":"max_tokens too large"}}""")
                .endsWith("max_tokens too large")
        )
        // 10k input at $4, 100k cached at 5% of $4, 2k output at $20 = 4 + 2 + 4 = 10 cents.
        assertEquals(
            "Claude Opus 5.5 · about 10¢ (season data read from cache) · plus a little code-execution time",
            AskEngine.costLine(ASK_MODELS[0], longArrayOf(10_000, 0, 100_000, 2_000))
        )
        assertTrue(AskEngine.costLine(ASK_MODELS[2], longArrayOf(100, 0, 0, 10)).contains("under 1¢"))
    }

    @Test
    fun `answers render headings, lists, tables and code`() {
        val blocks = parseMarkdown(
            """
            ## Serving
            KU served **better** in 2026.

            | Season | Aces/set |
            |---|---|
            | 2025 | 1.20 |
            | 2026 | 1.45 |

            - one
            - two

            ```
            print(1)
            ```
            """.trimIndent()
        )
        assertEquals(MdBlock.Heading("Serving"), blocks[0])
        assertEquals(MdBlock.Paragraph("KU served **better** in 2026."), blocks[1])
        assertEquals(
            MdBlock.Table(listOf("Season", "Aces/set"), listOf(listOf("2025", "1.20"), listOf("2026", "1.45"))),
            blocks[2]
        )
        assertEquals(MdBlock.ListBlock(listOf("one", "two"), ordered = false), blocks[3])
        assertEquals(MdBlock.Code("print(1)"), blocks[4])
        assertEquals("KU served better, a .300 clip", inlineMarkdown("KU served **better**, a `.300` *clip*").text)
    }
}
