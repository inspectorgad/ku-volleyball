// "Ask about the team": questions about the season, answered by Claude.
//
// Runs entirely in the reader's browser with the reader's own Anthropic API
// key. The key is never part of this site: it is typed into the page, kept in
// this browser only, and sent only to api.anthropic.com. Someone opening the
// dashboard without a key sees the box and nothing else happens.
//
// Accuracy is the point, so Claude is not asked to do arithmetic from memory.
// The season is uploaded once as a data file (ask-data.json, built nightly by
// scripts/ask_pack.py from the validated seed) into Claude's Python sandbox,
// and the instructions require every number to be computed there. A short
// summary of the same data rides in the prompt, cached, so a follow-up within
// a few minutes pays about a tenth of the price for it.
import { Anthropic } from "./vendor/anthropic-sdk-0.128.0.mjs";

// Opus 5 by default; Sonnet 5 as the reader's choice for cheaper questions.
// Prices are per million tokens, for the cost line under each answer.
const MODELS = {
  "claude-opus-5": { label: "Claude Opus 5 — most thorough", input: 5, output: 25, fallbacks: true },
  "claude-sonnet-5": { label: "Claude Sonnet 5 — about 40% of the cost", input: 2, output: 10, fallbacks: false },
};
const DEFAULT_MODEL = "claude-opus-5";
const MAX_CONTINUATIONS = 4; // pause_turn resumptions per question
const FILE_TTL_SECONDS = 7 * 24 * 3600;

const EXAMPLES = [
  "How does Taylor Stanley hit against ranked opponents compared with unranked ones?",
  "What is our record when we win the first set, and in matches that go five?",
  "Which performance goals do we miss most often in losses?",
  "Compare our serving this season with last season, per set.",
];

const SYSTEM_RULES = `You are the analyst behind a Kansas Jayhawks women's volleyball dashboard. You answer questions from coaches and fans about the team, using only the season data you are given.

The complete data is the file ask-data.json in your code execution container - locate it with: find / -name ask-data.json -not -path '*/proc/*' 2>/dev/null | head -1. It holds flat tables (matches, ku_lines, team_totals, opponent_lines, goals, upcoming, standings, poll, roster) and a definitions dictionary; load them with pandas. A summary of the smaller tables is below for orientation.

How to answer:
- Compute every number with code from the file. Do not estimate, recall, or do arithmetic in your head, even for a simple total.
- Lead with the answer in a sentence or two. Add a small markdown table when it helps. End with one short line saying what the figures cover (which season, which matches, any filter).
- Use volleyball conventions: hitting percentage as .300, per-set rates to two decimals, team blocks as solos plus half of assists.
- Name small samples plainly (for example "only 3 matches").
- If the data cannot answer the question - injuries, practice, line-ups, serve-receive ratings, anything not in the box scores - say so in a sentence instead of guessing.
- Season 2026 is the current season. "This season" means 2026 unless the reader says otherwise.`;

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
const FILE_SLOT = "ku-ask-file";
const MODEL_SLOT = "ku-ask-model";

let memoryKey = null; // used when browser storage is blocked
let pack = null;
let systemText = null;
let conversation = []; // Anthropic message params, appended in full each turn
let containerId = null;
let running = null; // the stream in flight, for Stop

// --- The data -------------------------------------------------------------------

async function loadPack() {
  if (pack) return pack;
  const r = await fetch("ask-data.json", { cache: "no-cache" });
  if (!r.ok) throw new Error(`couldn't load the season data (${r.status})`);
  pack = await r.json();
  systemText = SYSTEM_RULES + "\n\n" + summarize(pack);
  return pack;
}

// Compact CSV of the smaller tables, in a fixed column order so the text - and
// the prompt cache - only changes when the data does.
function csv(rows, cols) {
  const cell = (v) => {
    if (v === null || v === undefined) return "";
    if (Array.isArray(v)) v = v.map((c) => `${c.team}: KU ${c.ku} / them ${c.them}`).join("; ");
    const s = String(v);
    return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
  };
  return [cols.join(","), ...rows.map((r) => cols.map((c) => cell(r[c])).join(","))].join("\n");
}

function summarize(p) {
  const current = p.upcoming[0]?.season ?? p.matches.at(-1)?.season;
  return [
    `Data generated ${p.generated_at}.`,
    "Definitions:\n" + Object.entries(p.definitions).map(([k, v]) => `- ${k}: ${v}`).join("\n"),
    p.rpi_note ? `RPI: ${p.rpi_note}.` : "",
    "Played matches:\n" + csv(p.matches, ["season", "date", "opponent", "site", "conference", "result",
      "ku_sets", "opp_sets", "set_scores", "ku_rank", "opp_rank", "opp_seed", "opp_rpi", "forecast"]),
    "Upcoming matches:\n" + csv(p.upcoming, ["date", "opponent", "site", "conference", "first_serve_ct",
      "tv", "win_probability", "rating_source", "common_opponents"]),
    `Big 12 standings ${current}:\n` + csv(p.standings.filter((s) => s.season === current),
      ["team", "confW", "confL", "overallW", "overallL", "nationalRank", "rpiRank", "rpiSource"]),
    p.poll ? `AVCA poll (${p.poll.updated}):\n` + csv(p.poll.rows, ["rank", "team", "record", "points", "previous"]) : "",
    "Roster:\n" + csv(p.roster, ["name", "jerseyNumber", "position", "height", "active"]),
  ].filter(Boolean).join("\n\n");
}

// The data file lives in the reader's own Anthropic account, uploaded once
// per data version and reused until it expires.
async function dataFileId(client, key) {
  const want = `${pack.generated_at}|${key.slice(-6)}`;
  try {
    const saved = JSON.parse(store.get(FILE_SLOT) || "null");
    if (saved?.version === want) {
      await client.files.retrieveMetadata(saved.id);
      return saved.id;
    }
  } catch (e) {
    if (!(e instanceof Anthropic.NotFoundError) && !(e instanceof SyntaxError)) throw e;
  }
  const file = new File([JSON.stringify(pack)], "ask-data.json", { type: "application/json" });
  const meta = await client.files.upload({ file, expires_in_seconds: FILE_TTL_SECONDS });
  store.set(FILE_SLOT, JSON.stringify({ version: want, id: meta.id }), true);
  return meta.id;
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

  const turn = addTurn(question);
  let mark = conversation.length;
  setBusy(true, "Loading the season data…");
  try {
    await loadPack();
    const content = [{ type: "text", text: `Today is ${new Date().toLocaleDateString("en-CA")}. ${question}` }];
    if (!conversation.length) {
      setBusy(true, "Uploading the season data to your Claude workspace…");
      content.push({ type: "container_upload", file_id: await dataFileId(client, key) });
    }
    conversation.push({ role: "user", content });

    const usage = { input: 0, write: 0, read: 0, output: 0 };
    let final = null;
    for (let hop = 0; hop <= MAX_CONTINUATIONS; hop++) {
      setBusy(true, hop ? "Still working…" : "Thinking…");
      const params = {
        model,
        max_tokens: 16000,
        cache_control: { type: "ephemeral" },
        system: [{ type: "text", text: systemText, cache_control: { type: "ephemeral" } }],
        tools: [{ type: "code_execution_20260521", name: "code_execution" }],
        messages: conversation,
        ...(containerId ? { container: containerId } : {}),
        ...(cfg.fallbacks ? { betas: ["server-side-fallback-2026-07-01"], fallbacks: "default" } : {}),
      };
      running = client.beta.messages.stream(params);
      running.on("streamEvent", (ev) => {
        if (ev.type === "content_block_start" && ev.content_block.type === "server_tool_use") {
          setBusy(true, "Running Python on the season data…");
        } else if (ev.type === "content_block_start" && ev.content_block.type === "text") {
          setBusy(true, "Writing the answer…");
        }
      });
      running.on("text", () => renderAnswer(turn, running.currentMessage));
      final = await running.finalMessage();
      running = null;
      const u = final.usage || {};
      usage.input += u.input_tokens || 0;
      usage.write += u.cache_creation_input_tokens || 0;
      usage.read += u.cache_read_input_tokens || 0;
      usage.output += u.output_tokens || 0;
      if (final.container?.id) containerId = final.container.id;
      if (final.stop_reason === "refusal") break;
      conversation.push({ role: "assistant", content: final.content });
      if (final.stop_reason !== "pause_turn") break;
    }

    if (final.stop_reason === "refusal") {
      conversation.length = mark; // the declined question leaves no trace in the thread
      turn.answer.replaceChildren(el("p", "ask-note",
        "Claude declined to answer that one. Try rephrasing it as a question about the stats."));
    } else {
      renderAnswer(turn, final);
      if (final.stop_reason === "pause_turn") note(turn, "Claude was still working when it paused - ask it to continue.");
      if (final.stop_reason === "max_tokens") note(turn, "The answer hit its length limit and was cut short.");
    }
    showCode(turn, conversation.slice(mark));
    note(turn, costLine(cfg, usage, model));
  } catch (e) {
    conversation.length = mark;
    if (e instanceof Anthropic.APIUserAbortError) {
      note(turn, "Stopped.");
    } else {
      turn.answer.replaceChildren(el("p", "ask-error", explainError(e)));
    }
  } finally {
    running = null;
    setBusy(false);
  }
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
  const dollars = (u.input * cfg.input + u.write * cfg.input * 1.25 + u.read * cfg.input * 0.1 + u.output * cfg.output) / 1e6;
  const cents = dollars * 100;
  const price = cents < 1 ? "under 1¢" : `about ${cents < 10 ? cents.toFixed(1) : Math.round(cents)}¢`;
  return `${MODELS[model].label.split(" —")[0]} · ${price}` +
    (u.read ? " (season data read from cache)" : "") + " · plus a little code-execution time";
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
function renderAnswer(turn, message) {
  if (!message) return;
  const text = message.content.filter((b) => b.type === "text").map((b) => b.text).join("");
  if (pendingRender) cancelAnimationFrame(pendingRender);
  pendingRender = requestAnimationFrame(() => { turn.answer.innerHTML = markdown(text); });
}

function note(turn, text) {
  turn.wrap.appendChild(el("p", "ask-note", text));
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
    store.del(FILE_SLOT);
    refreshKeyState();
  };
  const submit = () => {
    const q = $("ask-q").value.trim();
    if (!q || running) return;
    $("ask-q").value = "";
    ask(q);
  };
  $("ask-go").onclick = submit;
  $("ask-q").addEventListener("keydown", (e) => {
    if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); submit(); }
  });
  $("ask-stop").onclick = () => running?.abort();
  $("ask-new").onclick = () => {
    conversation = [];
    containerId = null;
    $("ask-thread").replaceChildren();
    $("ask-new").hidden = true;
  };
  const ex = $("ask-examples");
  for (const q of EXAMPLES) {
    const b = el("button", "chip ask-example", q);
    b.onclick = () => { $("ask-q").value = q; $("ask-q").focus(); };
    ex.appendChild(b);
  }
  refreshKeyState();
}

init();
