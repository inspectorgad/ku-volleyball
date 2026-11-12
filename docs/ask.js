// "Ask about the team": questions about the season, answered by Claude.
//
// Runs entirely in the reader's browser with the reader's own Anthropic API
// key. The key is never part of this site: it is typed into the page, kept in
// this browser only, and sent only to api.anthropic.com. Someone opening the
// dashboard without a key sees the box and nothing else happens.
//
// Accuracy is the point, so Claude is not asked to do arithmetic from memory.
// Claude gets a Python sandbox (code execution) and one tool, get_table, that
// its code calls to fetch any table of the season (ask-data.json, built
// nightly by scripts/ask_pack.py from the validated seed). This page answers
// those calls from the data it already has - programmatic tool calling - so
// the numbers are computed in code, and the tables go to the code without
// being billed as tokens. The instructions require every number to be
// computed that way. A short summary of the smaller tables rides in the
// prompt, cached, so a follow-up within a few minutes pays about a tenth of
// the price for it.
//
// An earlier version uploaded the data with the Files API. Anthropic does not
// allow that endpoint to be called from a web page (its CORS preflight answers
// "Disallowed CORS origin"), so every question failed as a connection error.
// Only /v1/messages is used now, which does allow browser calls.
//
// With the "Search the web" box ticked, Claude may also search the internet
// (Anthropic's web search tool, run on Anthropic's side) for what the tables
// cannot tell it. Pages it cites are numbered in the answer, [1], and listed
// under it - Anthropic requires the sources to be shown with the answer.
import { Anthropic } from "./vendor/anthropic-sdk-0.128.0.mjs";

// Opus 5.5 by default: the newest Opus, about 20% cheaper than Opus 5, and
// its "medium" thinking depth - set explicitly, since that is its default and
// the reader should not be surprised by a change - beat Opus 5 at "high" on
// analysis work in Anthropic's testing. Opus 5 and Sonnet 5 stay as choices.
// Prices are per million tokens; cacheRead is the fraction of the input price
// a cached read costs. They feed the cost line under each answer.
const MODELS = {
  "claude-opus-5-5": { label: "Claude Opus 5.5 — newest, about 20% cheaper than Opus 5", input: 4, output: 20, cacheRead: 0.05, fallbacks: true, effort: "medium" },
  "claude-opus-5": { label: "Claude Opus 5 — previous Opus", input: 5, output: 25, cacheRead: 0.1, fallbacks: true },
  "claude-sonnet-5": { label: "Claude Sonnet 5 — about half the cost of Opus 5.5", input: 2, output: 10, cacheRead: 0.1, fallbacks: false },
};
const DEFAULT_MODEL = "claude-opus-5-5";
// Round trips per question: pause_turn resumptions plus get_table answers.
const MAX_HOPS = 16;

// Sent in the reader's place when a paused answer is resumed; it isn't one of
// the reader's questions. Same words as the app's.
const CONTINUE_NOTE = "(Sent by the app, not the reader: Anthropic paused your answer partway through. " +
  "Carry on from where it stopped - run again any search that hadn't happened yet - " +
  "without repeating what you've already written.)";

// Sent in the reader's place when an answer is set back because Claude searched
// while its code waited on get_table; same words as the app's.
const SEPARATE_NOTE = "(Sent by the app, not the reader: your last step asked for a web search while your " +
  "Python was waiting on get_table, which the app can't handle, so it was set aside. " +
  "Start again, and run your searches and your Python in separate steps.)";
// Times an answer is set back and asked again for that.
const MAX_ASK_AGAIN = 1;

// Asked of Claude with every web-search question; same words as the app's.
const SEARCH_APART = "Search before or after running Python, not in the same step: the app can't " +
  "run a search while your code is waiting on get_table.";

const answeredIds = (content) => new Set(content.filter((b) => b.tool_use_id).map((b) => b.tool_use_id));
// A server tool call with no result after it: not carried out yet.
const isWaiting = (b, answered) => b.type === "server_tool_use" && !answered.has(b.id);

// True when a reply that stopped for the page's tables also holds a web search
// Claude asked for at the same moment. Anthropic's guide says that search runs
// when the tables go back, but a web-search question of exactly that kind was
// refused there as a prefill.
function waitsOnSearch(content) {
  const answered = answeredIds(content);
  return content.some((b) => isWaiting(b, answered) && b.name === "web_search");
}

// True when a paused reply ends on a search or code run Anthropic hasn't carried
// out yet - the shape its guide resumes by sending the reply back as it is.
function endsOnWaitingServerTool(content) {
  return content.length > 0 && isWaiting(content[content.length - 1], answeredIds(content));
}

// A paused reply cut back to its last finished step: everything before the
// first search or code run Anthropic hadn't carried out yet, less any thinking
// left at the end. Only the end is cut, so what is kept reads exactly as Claude
// wrote it.
function finishedPart(content) {
  const answered = answeredIds(content);
  const pending = content.findIndex((b) => isWaiting(b, answered));
  const kept = pending < 0 ? content.slice() : content.slice(0, pending);
  while (kept.length && ["thinking", "redacted_thinking"].includes(kept[kept.length - 1].type)) kept.pop();
  return kept;
}

// A reply that was cut short (max_tokens), kept as far as it is complete: no
// search, code run or table request left half made, and no thinking at the end.
function settledPart(content) {
  const kept = finishedPart(content);
  while (kept.length && ["tool_use", "thinking", "redacted_thinking"].includes(kept[kept.length - 1].type)) kept.pop();
  return kept;
}

// After a refusal partway through a streamed answer, a fallback model carries
// on from it, marked by a "fallback" block. Before that block Anthropic takes
// back the text, the results of searches and code runs (including one whose
// call was in an earlier message), the calls that have their result here, and
// the block itself - not thinking, table requests or calls left waiting.
function echoable(content) {
  const cut = content.map((b) => b.type).lastIndexOf("fallback");
  if (cut < 0) return content;
  const answered = answeredIds(content);
  return content.filter((b, i) => i >= cut || b.type === "text" || b.type === "fallback" ||
    (b.type === "server_tool_use" && answered.has(b.id)) ||
    (b.tool_use_id && b.type !== "tool_result"));
}

// Where in the answer a request sits, added to a rejected request's error so a
// screenshot of it says which step failed and how the request ended.
function requestDetail(hop, previous) {
  const last = conversation[conversation.length - 1];
  const types = (last?.content || []).map((b) => b.type).slice(-3);
  const n = (previous?.content || []).length;
  const reply = previous ? ` Reply before it (${previous.model}, ${previous.stop_reason}, ` +
    `${n} block${n === 1 ? "" : "s"}): ${describe(previous.content || [])}.` : "";
  return `(For the developer: request ${hop + 1} of this answer; it ended with a ` +
    `${last?.role || "missing"} message: ${types.join(", ")}.${reply})`;
}

// A reply's blocks in a few words, for a rejected request's error: each block's
// type, the tool it calls and who called it, a search or code run not yet
// carried out, and a server tool's error. Long replies keep their first two
// and last six.
function describe(content) {
  const answered = answeredIds(content);
  const words = content.map((b) => [b.type,
    b.type.endsWith("tool_use") ? b.name : null,
    b.caller?.type ? `via ${b.caller.type}` : null,
    isWaiting(b, answered) ? "(waiting)" : null,
    b.content?.error_code ? `(error ${b.content.error_code})` : null].filter(Boolean).join(" "));
  if (!words.length) return "nothing";
  return (words.length > 8 ? [...words.slice(0, 2), "...", ...words.slice(-6)] : words).join(", ");
}

// Forgets the thread back to `size` messages. If what goes held Claude's code,
// its sandbox may be left paused on a call nobody will answer, so the next
// request starts a fresh one; the season tables come again through get_table.
function rollBack(size) {
  if (conversation.length <= size) return;
  const gone = conversation.slice(size);
  const blocks = gone.flatMap((m) => (Array.isArray(m.content) ? m.content : []));
  if (blocks.some((b) => (b.type || "").endsWith("tool_use"))) containerId = null;
  conversation.length = size;
}

const TABLES = ["matches", "sets", "ku_lines", "team_totals", "opponent_lines", "goals", "upcoming",
  "standings", "poll", "roster", "definitions",
  "opponent_rosters", "opponent_form", "team_serving", "national_leaders", "d1_results"];
// Anthropic's price for one web search ($10 per 1,000), and the most one
// question may use - the same as the app (AskEngine.kt).
const SEARCH_DOLLARS = 0.01;
const MAX_SEARCHES = 5;
// The basic web search, called by Claude directly rather than from its Python,
// so results and citations reach the answer as they are. Localized to
// Lawrence so local coverage ranks well.
const WEB_SEARCH = {
  type: "web_search_20250305", name: "web_search", max_uses: MAX_SEARCHES,
  user_location: { type: "approximate", city: "Lawrence", region: "Kansas", country: "US", timezone: "America/Chicago" },
};
const TOOLS = [
  { type: "code_execution_20260120", name: "code_execution" },
  {
    name: "get_table",
    description: "Returns one table of the Kansas volleyball season data (or, for the opponent_*, team_serving, " +
      "national_leaders and d1_results tables, data on other teams) as a JSON string: a list of " +
      "records (one per row) for every table except 'definitions' (a dict of column meanings) and " +
      "'poll' (a dict with season, updated and rows). Call it from Python and load it with " +
      "pandas, e.g. df = pd.DataFrame(json.loads(await get_table({'table': 'ku_lines'}))).",
    input_schema: {
      type: "object",
      properties: { table: { type: "string", enum: TABLES } },
      required: ["table"],
    },
    allowed_callers: ["code_execution_20260120"],
  },
];

const EXAMPLES = [
  "How does Taylor Stanley hit against ranked opponents compared with unranked ones?",
  "What is our record when we win the first set, and in matches that go five?",
  "Which performance goals do we miss most often in losses?",
  "Compare our serving this season with last season, per set.",
];

// The instructions and data summary Claude is given are built by the
// pipeline (scripts/ask_pack.py) and shipped in ask-data.json, so the app and
// this page send the same prompt.

const $ = (id) => document.getElementById(id);
const store = {
  get(k) { try { return localStorage.getItem(k) ?? sessionStorage.getItem(k); } catch { return null; } },
  set(k, v, remember) {
    try {
      (remember ? localStorage : sessionStorage).setItem(k, v);
      (remember ? sessionStorage : localStorage).removeItem(k);
    } catch { /* storage blocked: the key lives for this page only */ }
  },
  del(k) { try { localStorage.removeItem(k); sessionStorage.removeItem(k); } catch { /* ignore */ } },
};
const KEY_SLOT = "ku-ask-api-key";
const OLD_FILE_SLOT = "ku-ask-file"; // left by the Files API version; cleared on Forget key
// "-v2" since Opus 5.5 became the default: a choice saved under the old slot
// was usually just the old default, so it is not carried over.
const MODEL_SLOT = "ku-ask-model-v2";
const WEB_SLOT = "ku-ask-web";

let memoryKey = null; // used when browser storage is blocked
let pack = null;
let systemText = null;
let conversation = []; // Anthropic message params, appended in full each turn
let containerId = null;
let running = null; // the stream in flight, for Stop
let shown = []; // the messages the answer on screen was drawn from
let asking = false; // a question is in hand, from loading the data to the answer
// Whether this conversation declares the web search tool, settled by its first
// question (null before that). The tool list can't change partway through: on
// Claude Opus 5.5 each turn's reasoning is bound to the tools it was written
// with, and Anthropic rejects the conversation once they differ. Turning search
// on in a conversation begun without it therefore starts a new one.
let webDeclared = null;

// --- The data -------------------------------------------------------------------

async function loadPack() {
  if (pack) return pack;
  const r = await fetch("ask-data.json", { cache: "no-cache" });
  if (!r.ok) throw new Error(`couldn't load the season data (${r.status})`);
  pack = await r.json();
  systemText = pack.system_prompt;
  if (!systemText) throw new Error("the season data is out of date - reload the page");
  return pack;
}

// --- Asking -----------------------------------------------------------------------

function apiKey() {
  return memoryKey || store.get(KEY_SLOT);
}

async function ask(question) {
  const key = apiKey();
  if (!key) { showError("Add your Anthropic API key first (the field above)."); return; }
  const model = $("ask-model").value in MODELS ? $("ask-model").value : DEFAULT_MODEL;
  const cfg = MODELS[model];
  const client = new Anthropic({ apiKey: key, dangerouslyAllowBrowser: true });

  asking = true;
  const web = $("ask-web").checked;
  const restarted = web && conversation.length > 0 && webDeclared === false;
  if (restarted) newConversation();
  if (!conversation.length) webDeclared = web;
  const turn = addTurn(question);
  if (restarted) note(turn, "Web search was turned on, so this question started a new conversation: " +
    "Claude can't take on web search partway through one.");
  let mark = conversation.length;
  shown = []; // nothing drawn yet for this question
  // Fixed for the whole conversation; the question says whether it may search.
  const tools = webDeclared ? [...TOOLS, WEB_SEARCH] : TOOLS;
  setBusy(true, "Loading the season data…");
  let detail = ""; // where the last request sat, for a rejected request's error
  try {
    await loadPack();
    conversation.push({ role: "user", content: [
      { type: "text", text: `Today is ${new Date().toLocaleDateString("en-CA")}. ` +
        `Web search is ${web ? "on" : "off"} for this question. ${web ? SEARCH_APART + " " : ""}${question}` }] });

    const usage = { input: 0, write: 0, read: 0, output: 0, searches: 0 };
    let final = null;
    let asksAgain = 0;
    for (let hop = 0; hop < MAX_HOPS; hop++) {
      setBusy(true, hop ? "Still working…" : "Thinking…");
      const params = {
        model,
        max_tokens: 16000,
        cache_control: { type: "ephemeral" },
        system: [{ type: "text", text: systemText, cache_control: { type: "ephemeral" } }],
        tools,
        messages: conversation,
        ...(containerId ? { container: containerId } : {}),
        ...(cfg.effort ? { output_config: { effort: cfg.effort } } : {}),
        ...(cfg.fallbacks ? { betas: ["server-side-fallback-2026-07-01"], fallbacks: "default" } : {}),
      };
      detail = requestDetail(hop, final);
      running = client.beta.messages.stream(params);
      running.on("streamEvent", (ev) => {
        if (ev.type === "content_block_start" && ev.content_block.type === "server_tool_use") {
          setBusy(true, ev.content_block.name === "web_search" ? "Searching the web…" : "Running Python on the season data…");
        } else if (ev.type === "content_block_start" && ev.content_block.type === "text") {
          setBusy(true, "Writing the answer…");
        }
      });
      const earlier = conversation.slice(mark);
      running.on("text", () => {
        shown = [...earlier, running.currentMessage];
        renderAnswer(turn, shown);
      });
      final = await running.finalMessage();
      running = null;
      const u = final.usage || {};
      usage.input += u.input_tokens || 0;
      usage.write += u.cache_creation_input_tokens || 0;
      usage.read += u.cache_read_input_tokens || 0;
      usage.output += u.output_tokens || 0;
      usage.searches += u.server_tool_use?.web_search_requests || 0;
      if (final.container?.id) containerId = final.container.id;
      if (final.stop_reason === "refusal") break;
      const content = echoable(final.content);
      const calls = content.filter((b) => b.type === "tool_use");
      if (final.stop_reason === "tool_use" && calls.length) {
        if (waitsOnSearch(content) && asksAgain < MAX_ASK_AGAIN) {
          // Claude asked for a web search at the same moment its Python asked
          // the page for a table. Anthropic's guide says the search runs when
          // the table goes back, but that request is the one refused as a
          // prefill. So the answer so far is set aside, paused code and all,
          // and the question asked again with a note to keep the two apart.
          asksAgain++;
          rollBack(mark + 1);
          containerId = null; // this reply's code is paused in it
          conversation.push({ role: "user", content: [{ type: "text", text: SEPARATE_NOTE }] });
          continue;
        }
        // Claude's code asked for tables: answer every pending call in one
        // message of tool_result blocks only, then let the code carry on.
        conversation.push({ role: "assistant", content });
        conversation.push({ role: "user", content: calls.map((c) => tableResult(c)) });
        setBusy(true, "Running Python on the season data…");
        continue;
      }
      if (final.stop_reason === "pause_turn" || final.stop_reason === "tool_use") {
        // Paused: Anthropic stopped a long turn (a run of web searches, say), or
        // stopped for a tool call the page has nothing to run.
        if (endsOnWaitingServerTool(content)) {
          // Anthropic's documented continuation: the reply goes back as it is,
          // and the API resumes from the search or code run it hadn't carried out.
          conversation.push({ role: "assistant", content });
        } else {
          // Paused anywhere else, the reply as it is would read as a prefill. It
          // is kept up to its last finished step and Claude is asked, in a
          // message of its own, to carry on.
          const finished = finishedPart(content);
          if (finished.length) conversation.push({ role: "assistant", content: finished });
          if (conversation[conversation.length - 1].content?.[0]?.text !== CONTINUE_NOTE) {
            conversation.push({ role: "user", content: [{ type: "text", text: CONTINUE_NOTE }] });
          }
        }
        continue;
      }
      // Finished, or cut short (max_tokens): kept as far as it is complete, so a
      // follow-up never comes after a half-made tool call, and an empty reply
      // isn't kept at all - the API refuses an empty message anywhere but last.
      const kept = final.stop_reason === "end_turn" ? content : settledPart(content);
      if (kept.length) conversation.push({ role: "assistant", content: kept });
      break;
    }

    // Taken before any rollback, so the sources and code shown always match
    // the text shown, [n] marks included.
    const answered = conversation.slice(mark);
    if (final.stop_reason === "refusal") {
      rollBack(mark); // the declined question leaves no trace in the thread
      cancelDraw(); // a draw of text streamed just before must not cover this
      turn.answer.replaceChildren(el("p", "ask-note",
        "Claude declined to answer that one. Try rephrasing it as a question about the stats."));
    } else {
      renderAnswer(turn, answered);
      if (final.stop_reason === "tool_use" || final.stop_reason === "pause_turn") {
        // Out of round trips mid-answer. The thread cannot continue from a
        // pending tool call, so it is rolled back; the reader can ask again.
        rollBack(mark);
        note(turn, "Claude was still working when this page stopped waiting. Try a narrower question.");
      }
      if (final.stop_reason === "max_tokens") note(turn, "The answer hit its length limit and was cut short.");
    }
    if (final.stop_reason !== "refusal") {
      showSources(turn, answered);
      showCode(turn, answered);
    }
    note(turn, costLine(cfg, usage, model));
  } catch (e) {
    // What was on screen when it stopped, so its [n] marks keep their sources.
    const partial = shown.length ? shown : conversation.slice(mark);
    rollBack(mark);
    if (e instanceof Anthropic.APIUserAbortError) {
      showSources(turn, partial);
      note(turn, "Stopped.");
    } else {
      cancelDraw();
      turn.answer.replaceChildren(el("p", "ask-error", explainError(e)));
      if (e instanceof Anthropic.BadRequestError && detail) turn.answer.append(el("p", "ask-note", detail));
    }
  } finally {
    running = null;
    asking = false;
    setBusy(false);
  }
}

function newConversation() {
  conversation = [];
  containerId = null;
  webDeclared = null;
  $("ask-thread").replaceChildren();
  $("ask-new").hidden = true;
}

// One get_table call answered from the loaded data.
function tableResult(call) {
  const name = call.input?.table;
  if (call.name !== "get_table" || !TABLES.includes(name)) {
    return { type: "tool_result", tool_use_id: call.id, is_error: true,
      content: `Unknown request. Available tables: ${TABLES.join(", ")}.` };
  }
  return { type: "tool_result", tool_use_id: call.id, content: JSON.stringify(pack[name]) };
}

function explainError(e) {
  if (e instanceof Anthropic.AuthenticationError) return "That API key was rejected. Check it at console.anthropic.com and paste it again.";
  if (e instanceof Anthropic.PermissionDeniedError) return "This API key isn't allowed to do that (permission denied). " + (e.message || "");
  if (e instanceof Anthropic.RateLimitError) return "Too many requests right now (rate limit). Wait a minute and try again.";
  if (e instanceof Anthropic.BadRequestError) return "Anthropic couldn't accept the request: " + (e.error?.error?.message || e.message);
  if (e instanceof Anthropic.InternalServerError) return "Anthropic's servers had a problem. Try again in a moment.";
  if (e instanceof Anthropic.APIConnectionError) return "Couldn't reach api.anthropic.com. Check your connection - some networks block it.";
  if (e instanceof Anthropic.APIError) return `Anthropic returned an error (${e.status}): ${e.message}`;
  return "Something went wrong: " + (e?.message || e);
}

function costLine(cfg, u, model) {
  const searches = u.searches || 0;
  const dollars = (u.input * cfg.input + u.write * cfg.input * 1.25 + u.read * cfg.input * cfg.cacheRead + u.output * cfg.output) / 1e6 +
    searches * SEARCH_DOLLARS;
  const cents = dollars * 100;
  const price = cents < 1 ? "under 1¢" : `about ${cents < 10 ? cents.toFixed(1) : Math.round(cents)}¢`;
  return `${MODELS[model].label.split(" —")[0]} · ${price}` +
    (u.read ? " (season data read from cache)" : "") +
    (searches ? ` · ${searches} web search${searches === 1 ? "" : "es"} included` : "") +
    " · plus a little code-execution time";
}

// --- Rendering --------------------------------------------------------------------

const el = (tag, cls, text) => {
  const n = document.createElement(tag);
  if (cls) n.className = cls;
  if (text !== undefined) n.textContent = text;
  return n;
};

function addTurn(question) {
  const wrap = el("div", "ask-turn");
  wrap.appendChild(el("div", "ask-q", question));
  const answer = el("div", "ask-a");
  wrap.appendChild(answer);
  $("ask-thread").appendChild(wrap);
  $("ask-new").hidden = false;
  wrap.scrollIntoView({ behavior: "smooth", block: "nearest" });
  return { wrap, answer };
}

let pendingRender = null;
function cancelDraw() {
  if (pendingRender) cancelAnimationFrame(pendingRender);
  pendingRender = null;
}
// The answer text with each cited passage followed by its source numbers, and
// the sources in order - the same as the app's AskEngine. A cited answer
// arrives as a run of text blocks that together make up the sentences, so
// neighbouring text blocks are joined as they are; text separated by code or
// searches starts a new paragraph.
function answerWithSources(messages) {
  let out = "";
  const sources = [];
  for (const m of messages.filter((x) => x && x.role === "assistant")) {
    let joined = false;
    for (const b of m.content || []) {
      if (b.type !== "text") { joined = false; continue; }
      if (!joined && out.trim()) out += "\n\n";
      out += b.text;
      const numbers = [];
      for (const c of b.citations || []) {
        if (!/^https?:\/\//.test(c.url || "")) continue;
        let s = sources.find((x) => x.url === c.url);
        if (!s) { s = { number: sources.length + 1, title: c.title || c.url, url: c.url }; sources.push(s); }
        if (!numbers.includes(s.number)) numbers.push(s.number);
      }
      out += numbers.map((n) => ` [${n}]`).join("");
      joined = true;
    }
  }
  return { text: out.trim(), sources };
}

function renderAnswer(turn, messages) {
  const { text } = answerWithSources(messages);
  if (pendingRender) cancelAnimationFrame(pendingRender);
  pendingRender = requestAnimationFrame(() => { turn.answer.innerHTML = markdown(text); });
}

function note(turn, text) {
  turn.wrap.appendChild(el("p", "ask-note", text));
}

// The web pages the answer cites, numbered as in its text, and what Claude
// searched for.
function showSources(turn, messages) {
  const { sources } = answerWithSources(messages);
  if (sources.length) {
    const box = el("div", "ask-sources");
    box.appendChild(el("p", "ask-label", "Sources from the web"));
    const ol = el("ol");
    for (const s of sources) {
      const li = el("li");
      const a = el("a", null, s.title);
      a.href = s.url; // only http(s), checked when collected
      a.target = "_blank";
      a.rel = "noopener noreferrer";
      li.appendChild(a);
      ol.appendChild(li);
    }
    box.appendChild(ol);
    turn.wrap.appendChild(box);
  }
  const searches = messages.filter((m) => m.role === "assistant").flatMap((m) => m.content)
    .filter((b) => b.type === "server_tool_use" && b.name === "web_search" && b.input?.query)
    .map((b) => `"${b.input.query}"`);
  if (searches.length) note(turn, "Searched the web for: " + searches.join("; "));
}

// The Python Claude ran, for anyone who wants to check the working.
function showCode(turn, messages) {
  const code = messages.filter((m) => m.role === "assistant").flatMap((m) => m.content)
    .filter((b) => b.type === "server_tool_use")
    .map((b) => b.name === "text_editor_code_execution"
      ? [`# ${b.input?.command ?? ""} ${b.input?.path ?? ""}`.trim(), b.input?.file_text].filter(Boolean).join("\n")
      : String(b.input?.command ?? b.input?.code ?? ""))
    .filter(Boolean);
  if (!code.length) return;
  const d = el("details", "ask-code");
  d.appendChild(el("summary", null, `Show the code Claude ran (${code.length} step${code.length === 1 ? "" : "s"})`));
  for (const c of code) d.appendChild(el("pre", null, c));
  turn.wrap.appendChild(d);
}

function setBusy(on, status) {
  $("ask-go").disabled = on;
  $("ask-stop").hidden = !on;
  $("ask-status").textContent = on ? status || "" : "";
}

function showError(text) {
  $("ask-status").textContent = text;
}

// A small, escape-first markdown renderer: paragraphs, headings, lists, code,
// bold/italic, and pipe tables. Everything is HTML-escaped before any tag is
// added, so nothing in an answer can inject markup.
function markdown(src) {
  const esc = (s) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  const inline = (s) => esc(s)
    .replace(/`([^`]+)`/g, "<code>$1</code>")
    .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
    .replace(/(^|[^*])\*([^*\s][^*]*)\*/g, "$1<em>$2</em>");
  const lines = src.replace(/\r/g, "").split("\n");
  const out = [];
  for (let i = 0; i < lines.length;) {
    const line = lines[i];
    if (/^```/.test(line)) {
      const body = [];
      for (i++; i < lines.length && !/^```/.test(lines[i]); i++) body.push(lines[i]);
      i++;
      out.push(`<pre>${esc(body.join("\n"))}</pre>`);
    } else if (/^\s*\|.*\|\s*$/.test(line) && /^\s*\|?\s*:?-{2,}/.test(lines[i + 1] || "")) {
      const cells = (l) => l.trim().replace(/^\||\|$/g, "").split("|").map((c) => c.trim());
      const head = cells(line);
      const rows = [];
      for (i += 2; i < lines.length && /^\s*\|.*\|\s*$/.test(lines[i]); i++) rows.push(cells(lines[i]));
      out.push('<div class="ask-table"><table><thead><tr>' + head.map((h) => `<th>${inline(h)}</th>`).join("") +
        "</tr></thead><tbody>" + rows.map((r) => "<tr>" + r.map((c) => `<td>${inline(c)}</td>`).join("") + "</tr>").join("") +
        "</tbody></table></div>");
    } else if (/^#{1,4}\s/.test(line)) {
      out.push(`<h3>${inline(line.replace(/^#+\s*/, ""))}</h3>`);
      i++;
    } else if (/^\s*([-*]|\d+\.)\s+/.test(line)) {
      const ordered = /^\s*\d+\./.test(line);
      const items = [];
      for (; i < lines.length && /^\s*([-*]|\d+\.)\s+/.test(lines[i]); i++) items.push(lines[i].replace(/^\s*([-*]|\d+\.)\s+/, ""));
      out.push(`<${ordered ? "ol" : "ul"}>` + items.map((t) => `<li>${inline(t)}</li>`).join("") + `</${ordered ? "ol" : "ul"}>`);
    } else if (!line.trim()) {
      i++;
    } else {
      const para = [];
      for (; i < lines.length && lines[i].trim() && !/^(```|#{1,4}\s|\s*([-*]|\d+\.)\s|\s*\|)/.test(lines[i]); i++) para.push(lines[i]);
      if (!para.length) { para.push(lines[i]); i++; }
      out.push(`<p>${inline(para.join(" "))}</p>`);
    }
  }
  return out.join("");
}

// --- Wiring -------------------------------------------------------------------------

function refreshKeyState() {
  const has = !!apiKey();
  $("ask-key-set").hidden = !has;
  $("ask-key-form").hidden = has;
}

function init() {
  const sel = $("ask-model");
  for (const [id, m] of Object.entries(MODELS)) {
    const o = el("option", null, m.label);
    o.value = id;
    sel.appendChild(o);
  }
  sel.value = store.get(MODEL_SLOT) in MODELS ? store.get(MODEL_SLOT) : DEFAULT_MODEL;
  sel.onchange = () => store.set(MODEL_SLOT, sel.value, true);
  const web = $("ask-web");
  web.checked = store.get(WEB_SLOT) === "1";
  web.onchange = () => store.set(WEB_SLOT, web.checked ? "1" : "0", true);

  $("ask-key-save").onclick = () => {
    const v = $("ask-key").value.trim();
    if (!/^sk-ant-/.test(v)) { showError("That doesn't look like an Anthropic API key (they start with sk-ant-)."); return; }
    memoryKey = v;
    store.set(KEY_SLOT, v, $("ask-remember").checked);
    $("ask-key").value = "";
    showError("");
    refreshKeyState();
  };
  $("ask-key-forget").onclick = () => {
    memoryKey = null;
    store.del(KEY_SLOT);
    store.del(OLD_FILE_SLOT);
    refreshKeyState();
  };
  const submit = () => {
    const q = $("ask-q").value.trim();
    if (!q || running || asking) return;
    $("ask-q").value = "";
    ask(q);
  };
  $("ask-go").onclick = submit;
  $("ask-q").addEventListener("keydown", (e) => {
    if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); submit(); }
  });
  $("ask-stop").onclick = () => running?.abort();
  $("ask-new").onclick = newConversation;
  const ex = $("ask-examples");
  for (const q of EXAMPLES) {
    const b = el("button", "chip ask-example", q);
    b.onclick = () => { $("ask-q").value = q; $("ask-q").focus(); };
    ex.appendChild(b);
  }
  refreshKeyState();

  // The Preview and Recap buttons elsewhere on the page hand a question over
  // here: asked at once when a key is saved, otherwise left in the box with a
  // note that a key is needed.
  window.kuAskAbout = (q) => {
    $("ask-wrap").scrollIntoView({ behavior: "smooth", block: "start" });
    if (running || asking) return;
    if (apiKey()) {
      ask(q);
    } else {
      $("ask-q").value = q;
      showError("Add your Anthropic API key above, then press Ask.");
    }
  };
}

// The Preview and Recap buttons' questions, word for word the app's
// previewQuestion() and recapQuestion() in AskScreen.kt.
window.kuPreviewQuestion = (date, versus, opponent) =>
  `Give me a short scouting report for the ${date} match ${versus} ${opponent}: their season so far and ` +
  "what they do well, our last meeting and any common opponents, the players to watch on each " +
  "side, how we match up, the win model's estimate, and, if web search is on, any injury or " +
  "lineup news for either team.";
window.kuRecapQuestion = (date, versus, opponent) =>
  `Recap the ${date} match ${versus} ${opponent}: the result and how each set went, our standout ` +
  "players and theirs, how the night compared with our season averages, and which performance " +
  "goals we met or missed.";

init();
