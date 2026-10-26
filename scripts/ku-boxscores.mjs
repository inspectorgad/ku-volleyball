// Reads Kansas Athletics' own box scores (kuathletics.com), for one thing the
// NCAA's live-stats feed gets wrong: who played. The feed lists every Kansas
// player dressed for a match and credits each of them with every set of it,
// including players who never came on. Heidi Devers played one set at Baylor
// on 10/25/2026 and none at most other matches, yet the feed had her down for
// all of them, so her matches and sets played were inflated and her per-set
// numbers diluted - nine bench players in all. Kansas Athletics' box score
// lists only the players who played, with the sets each one played, and it
// does the same for the other team, whose lines in the feed are inflated the
// same way. update-seed.py takes sets played from here and every other number
// from the NCAA, which agreed with Kansas Athletics on everything else.
//
// The parsing is kept apart from the page reading so it can be tested on saved
// pages (scripts/fixtures/ku-boxscore-*.json, tested by ku-boxscores.test.mjs).
// data-cross-check.mjs reads the same pages with its own code on purpose: it is
// the check on this pipeline, so it must not share this pipeline's mistakes.

const KU = 'https://kuathletics.com/sports/womens-volleyball';

/** A name as a comparable key: "Stanley, Taylor" and "Taylor Stanley" -> "taylor stanley". */
function nameKey(name) {
  let n = String(name ?? '').trim();
  if (n.includes(',')) {
    const [last, first] = n.split(',', 2);
    n = `${first} ${last}`;
  }
  return n.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase().replace(/[^a-z]+/g, ' ').trim();
}

/** "Aiono, Aisha" -> "Aisha Aiono"; a name with no comma is already in that order. */
function firstLast(name) {
  const n = String(name ?? '').replace(/\s+/g, ' ').trim();
  if (!n.includes(',')) return n;
  const [last, first] = n.split(',', 2);
  return `${first.trim()} ${last.trim()}`.trim();
}

const toInt = (s) => {
  const v = Number(String(s ?? '').trim());
  return Number.isFinite(v) ? Math.trunc(v) : 0;
};

const upper = (row) => (Array.isArray(row) ? row : []).map((c) => String(c ?? '').trim().toUpperCase());

/**
 * One Kansas Athletics box score page, as {title, tables} where each table is
 * its rows of cell text, read into
 *   { date: 'YYYY-MM-DD', opponent, sets: [[ku, opp], ...],
 *     players: [{ name: 'First Last', number, sp, k, e, ta, a, sa, se, bs, ba, d, re }],
 *     opponentPlayers: [the same, for the other team] }
 * Null when the page is not a box score this can read.
 *
 * [kuNames] is a Set of Kansas players' names ("first last", lower case).
 * The two teams' player tables look the same and their order is not fixed, so
 * Kansas's is the one with the most of those names in it, and the other
 * team's is the one left. opponentPlayers is empty unless exactly one is left.
 */
export function parseKuBoxScore({ title, tables } = {}, kuNames = new Set()) {
  // "Women's Volleyball vs Baylor on 10/25/2026 - Box Score - University of
  // Kansas". It says "vs" for road games too, so it says nothing about where.
  const t = String(title ?? '').match(/Volleyball\s+(?:vs\.?|at|versus)\s+(.+?)\s+on\s+(\d{1,2})\/(\d{1,2})\/(\d{4})/i);
  if (!t) return null;
  const opponent = t[1].replace(/^#\d+\s+/, '').trim();
  const date = `${t[4]}-${t[2].padStart(2, '0')}-${t[3].padStart(2, '0')}`;
  const rows = (Array.isArray(tables) ? tables : []).map((tb) => (Array.isArray(tb) ? tb : []));

  // The line score: "TEAM | 1 | 2 | 3 ...", then one row per team. Found by
  // its set-number header, the one table that has one.
  const lineScore = rows.find((tb) => {
    const h = upper(tb[0] ?? []).slice(1);
    return h.includes('1') && h.includes('2');
  });
  if (!lineScore) return null;
  const setCols = upper(lineScore[0]).map((c, j) => (j > 0 && /^[1-5]$/.test(c) ? j : -1)).filter((j) => j >= 0);
  const teamRows = lineScore.slice(1).filter((r) => r[0]);
  // "Kansas", or failing that the one row that is not the opponent's.
  const kuRow = teamRows.find((r) => nameKey(r[0]) === 'kansas') ??
    (teamRows.length === 2 ? teamRows.find((r) => nameKey(r[0]) !== nameKey(opponent)) : undefined);
  const oppRow = teamRows.find((r) => r !== kuRow);
  if (!kuRow || !oppRow) return null;
  // A set not played is a blank cell, not a 0-0 set - and on a layout that
  // fills it with "-" or 0-0 instead, still not a set. Taken for one, it would
  // never match the NCAA's set scores, and update-seed.py would never use the
  // box score.
  const whole = (c) => /^\d+$/.test(String(c ?? '').trim());
  const sets = setCols
    .filter((j) => whole(kuRow[j]) && whole(oppRow[j]))
    .map((j) => [toInt(kuRow[j]), toInt(oppRow[j])])
    .filter(([ku, opp]) => ku + opp > 0);

  // The player tables: a group-header row ("Attacks | Set | Serve ..."), then
  // "# | Player | SP | K | E | TA | PCT | A | E | SA | ...". The header is
  // looked for in the first few rows, so the group row can come or go.
  const names = new Set([...kuNames].map(nameKey));
  const playerTables = [];
  for (const tb of rows) {
    for (let i = 0; i < Math.min(tb.length, 4); i++) {
      const h = upper(tb[i]);
      if (h.includes('PLAYER') && h.includes('SP') && h.includes('K') && h.includes('TA')) {
        playerTables.push({ tb, at: i, h });
        break;
      }
    }
  }
  let best = null;
  let bestHits = 0;
  let tied = false;
  for (const x of playerTables) {
    const nameCol = x.h.indexOf('PLAYER');
    const hits = x.tb.slice(x.at + 1).filter((r) => names.has(nameKey(r[nameCol]))).length;
    if (hits > bestHits) {
      best = x;
      bestHits = hits;
      tied = false;
    } else if (hits === bestHits && hits > 0) {
      tied = true;
    }
  }
  // No roster names anywhere, or as many in one table as the other: no way to
  // tell which side is Kansas, and guessing would hand Kansas the other
  // team's sets played.
  if (!best || tied) return null;

  const others = playerTables.filter((x) => x !== best);
  return {
    date,
    opponent,
    sets,
    players: readPlayers(best),
    opponentPlayers: others.length === 1 ? readPlayers(others[0]) : [],
  };
}

/** The player rows of one player table found by parseKuBoxScore, {tb, at, h}. */
function readPlayers({ tb, at, h }) {
  // Two columns are headed "E": attack errors come first, set errors after A.
  const col = {
    number: h.indexOf('#'),
    name: h.indexOf('PLAYER'),
    sp: h.indexOf('SP'),
    k: h.indexOf('K'),
    e: h.indexOf('E'),
    ta: h.indexOf('TA'),
    a: h.indexOf('A'),
    sa: h.indexOf('SA'),
    se: h.indexOf('SE'),
    bs: h.indexOf('BS'),
    ba: h.indexOf('BA'),
    d: h.indexOf('DIG') >= 0 ? h.indexOf('DIG') : h.indexOf('DIGS'),
    re: h.indexOf('RE'),
  };
  const players = [];
  for (const r of tb.slice(at + 1)) {
    const name = String(r[col.name] ?? '').trim();
    const number = col.number >= 0 ? String(r[col.number] ?? '').trim() : '';
    // "TM | Team" carries what the scorer charged to nobody in particular, and
    // "Totals" is the sum; neither is a player.
    if (!name || /^team$/i.test(name) || /^tm$/i.test(number) || /^totals?$/i.test(String(r[0] ?? '').trim())) continue;
    const line = { name: firstLast(name), number };
    for (const f of ['sp', 'k', 'e', 'ta', 'a', 'sa', 'se', 'bs', 'ba', 'd', 're']) {
      line[f] = col[f] >= 0 ? toInt(r[col[f]]) : 0;
    }
    players.push(line);
  }
  return players;
}

// --- Page reading ------------------------------------------------------------

// The longest one page may take, from opening it to its tables being read. The
// page load has its own limit, but the scrolling and the reading after it have
// none, and one wedged renderer would otherwise hold the scrape until the job
// is killed - and a job killed for time commits nothing, the night's NCAA
// results included.
const PAGE_TIMEOUT_MS = 90_000;

/** A promise that rejects after [ms], and a way to call that off. */
function deadline(ms, what) {
  let timer;
  const promise = new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error(`${what} timed out after ${Math.round(ms / 1000)} s`)), ms);
  });
  return { promise, cancel: () => clearTimeout(timer) };
}

/**
 * Opens [url] and returns its title, its tables (rows of cell text) and its
 * links, or throws if that takes longer than [timeoutMs] all told.
 */
async function readPage(context, url, settleMs, timeoutMs = PAGE_TIMEOUT_MS) {
  let page = null;
  let gaveUp = false;
  const read = (async () => {
    const opened = await context.newPage();
    if (gaveUp) {
      opened.close().catch(() => {});
      throw new Error('given up');
    }
    page = opened;
    await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 60_000 });
    await page.waitForTimeout(settleMs);
    for (let i = 0; i < 8; i++) {
      await page.evaluate(() => window.scrollBy(0, 1500));
      await page.waitForTimeout(250);
    }
    return await page.evaluate(() => {
      const clean = (s) => (s || '').replace(/\s+/g, ' ').trim();
      return {
        title: document.title,
        tables: [...document.querySelectorAll('table')].map((t) =>
          [...t.querySelectorAll('tr')].map((tr) => [...tr.querySelectorAll('th,td')].map((c) => clean(c.innerText)))),
        links: [...document.querySelectorAll('a[href]')].map((a) => a.href),
      };
    });
  })();
  const limit = deadline(timeoutMs, 'page');
  try {
    return await Promise.race([read, limit.promise]);
  } finally {
    gaveUp = true;
    limit.cancel();
    // A page that hung may hang on closing too, so that is not waited on long.
    if (page) {
      const closing = deadline(5_000, 'close');
      await Promise.race([page.close(), closing.promise]).catch(() => {});
      closing.cancel();
    }
  }
}

const DAY_MS = 86_400_000;

// Box scores are corrected after the match now and then (a stat moved from one
// player to another, a set's line fixed), so one is read again on each run
// until a copy taken three days after the match is in hand. After that it is
// taken as final and never asked for again. A later copy reaches the seed, new
// installs and the dashboard, but not a phone that has already corrected that
// match from an earlier copy: phones correct a marked match once (Seeder.merge).
const SETTLE_DAYS = 3;

// A page that opens but is not a box score this can read (a cancelled match,
// an exhibition laid out differently, a link that is not a box score at all)
// would otherwise be read again on every run, six times a day, for good. After
// this many such reads it is tried once a day; a copy already held is kept.
const PARSE_FAILURES = 3;

// Failed reads in a row - a page that would not load, or took too long - after
// which a season's remaining pages wait for the next run: the site is having
// trouble, and each failure can cost up to PAGE_TIMEOUT_MS.
const FAILS_IN_A_ROW = 3;

function needsReading(cached, now) {
  if ((cached?.failures ?? 0) >= PARSE_FAILURES && now - Date.parse(cached.failedAt) < DAY_MS) return false;
  if (!cached?.fetchedAt || !cached?.date) return true;
  return Date.parse(cached.fetchedAt) < Date.parse(`${cached.date}T00:00:00Z`) + SETTLE_DAYS * DAY_MS;
}

/**
 * Reads every box score [season]'s cumulative stats page links to, except
 * those [cache] already holds in final form, and returns the cache with them
 * added: { "<box score id>": { ...parseKuBoxScore(), source, fetchedAt } }.
 * A page that opened but could not be read as a box score is recorded as
 * { failures, failedAt, source } beside whatever copy is held.
 *
 * Kansas's names, for telling the two player tables apart, come from that
 * season's own player table on the same page, plus [rosterNames] (the current
 * roster). The roster alone would not do for last season: half of it has left.
 * With no names at all nothing could be told apart, so nothing is read.
 *
 * [budget] is shared across calls: .left caps how many pages are read, and
 * .until (a Date.now() time) is when to stop opening pages, checked before
 * each one. The scrape job has a time limit, and the first run has two seasons
 * to read, about fifty-five pages; what is not read now is read on the next
 * run. [now] and [pageTimeoutMs] are there for the tests.
 */
export async function readKuBoxScores(context, season, cache = {}, {
  rosterNames = [], budget = { left: Infinity }, log = console.log, now = Date.now, pageTimeoutMs = PAGE_TIMEOUT_MS,
} = {}) {
  const out = { ...cache };
  const late = () => now() > (budget.until ?? Infinity);
  if (late()) {
    log(`kuathletics box scores ${season}: out of time, all left for the next run`);
    return out;
  }
  const cume = await readPage(context, `${KU}/stats/${season}`, 6_000, pageTimeoutMs);

  const kuNames = new Set(rosterNames.map(nameKey).filter(Boolean));
  for (const tb of cume.tables) {
    const at = tb.slice(0, 4).findIndex((r) => {
      const h = upper(r);
      return h.includes('PLAYER') && h.includes('SP') && h.includes('MP');
    });
    if (at < 0) continue;
    const nameCol = upper(tb[at]).indexOf('PLAYER');
    for (const r of tb.slice(at + 1)) {
      // Some layouts put the shirt number in front of the name.
      const name = String(r[nameCol] ?? '').replace(/^\s*#?\d+\s+/, '');
      if (name && !/^(totals?|opponents?|team)$/i.test(name.trim())) kuNames.add(nameKey(name));
    }
  }
  // Every page would be read and thrown away (parseKuBoxScore will not guess
  // which side is Kansas), spending the run's reads for nothing.
  if (kuNames.size === 0) {
    log(`kuathletics box scores ${season}: no Kansas names on the roster or the stats page; nothing read`);
    return out;
  }

  const pattern = new RegExp(`/sports/womens-volleyball/stats/${season}/[^/?#]+/boxscore/(\\d+)`);
  const links = new Map();
  for (const href of cume.links) {
    const m = pattern.exec(href);
    if (m && !links.has(m[1])) links.set(m[1], href.split('#')[0]);
  }

  let read = 0;
  let waiting = 0;
  let failsInARow = 0;
  let stopped = '';
  const failed = [];
  for (const [id, url] of links) {
    if (!needsReading(out[id], now())) continue;
    if (!stopped && late()) stopped = 'out of time';
    if (!stopped && budget.left <= 0) stopped = 'read cap reached';
    if (stopped) {
      waiting++;
      continue;
    }
    budget.left--;
    let page;
    try {
      page = await readPage(context, url, 4_000, pageTimeoutMs);
    } catch (e) {
      // A copy already held stays as it was; one never read is tried next run.
      failed.push(`${id} (${String(e?.message ?? e).split('\n')[0]})`);
      if (++failsInARow >= FAILS_IN_A_ROW) stopped = `${FAILS_IN_A_ROW} failed reads in a row`;
      continue;
    }
    failsInARow = 0;
    const box = parseKuBoxScore(page, kuNames);
    if (!box) {
      out[id] = { ...out[id], source: url, failures: (out[id]?.failures ?? 0) + 1, failedAt: new Date(now()).toISOString() };
      failed.push(`${id} (not read as a box score: "${page.title}")`);
      continue;
    }
    out[id] = { ...box, source: url, fetchedAt: new Date(now()).toISOString() };
    read++;
  }
  log(`kuathletics box scores ${season}: ${links.size} listed, ${read} read this run` +
    (waiting ? `, ${waiting} left for the next run (${stopped})` : '') +
    `, ${Object.values(out).filter((b) => b?.players).length} held (${kuNames.size} Kansas names to tell the teams apart)`);
  if (failed.length) log(`  not read: ${failed.join(', ')}`);
  return out;
}
