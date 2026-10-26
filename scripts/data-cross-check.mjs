// Checks the app's season data against official pages it is NOT built from.
// The nightly scrape reads box scores from the NCAA's live-stats feed and
// works the Big 12 table out from results; this reads Kansas Athletics' own
// stats pages and box scores, and ncaa.com's rankings and leaders, so a
// mistake anywhere in that pipeline shows up as a difference.
//
// Runs in GitHub Actions (data-cross-check.yml), which can reach those sites.
//
//   node scripts/data-cross-check.mjs check [season]
//       Compares the data phones download (season-data.json) with the
//       official pages and prints every difference, plus a summary for the
//       run page. Exits 0 either way: a difference is a finding, not a crash.
//   node scripts/data-cross-check.mjs probe [url...]
//       Prints what each page holds - its text, tables and links - for
//       writing a reader against a page that changed.

import { chromium } from 'playwright';
import fs from 'fs';

const APP_DATA = [
  'https://inspectorgad.github.io/ku-volleyball/season-data.json',
  'https://raw.githubusercontent.com/inspectorgad/ku-volleyball/main/app/src/main/assets/seed.json',
];
const KU = 'https://kuathletics.com/sports/womens-volleyball';
const NCAA = 'https://www.ncaa.com';
const STANDINGS_PAGES = [
  'https://big12sports.com/standings.aspx?path=wvball',
  `${NCAA}/standings/volleyball-women/d1/big-12`,
  `${NCAA}/standings/volleyball-women/d1`,
  'https://www.espn.com/womens-college-volleyball/standings',
];
const PROBE_PAGES = [
  `${KU}/stats/2026`,
  `${KU}/schedule`,
  `${NCAA}/rankings/volleyball-women/d1/avca-rankings`,
  `${NCAA}/rankings/volleyball-women/d1/ncaa-womens-volleyball-rpi`,
  `${NCAA}/stats/volleyball-women/d1`,
];

// --- Page reading ------------------------------------------------------------

async function newContext(browser) {
  const context = await browser.newContext({
    userAgent: 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36',
    locale: 'en-US',
    viewport: { width: 1366, height: 900 },
  });
  // Some sites (the Big 12's) turn away a browser that says it is automated.
  await context.addInitScript(() => Object.defineProperty(navigator, 'webdriver', { get: () => undefined }));
  return context;
}

const launch = () => chromium.launch({ args: ['--disable-blink-features=AutomationControlled'] });

/** Opens [url] and returns its text, tables (rows of cell text) and links. */
async function read(context, url, { settleMs = 6_000 } = {}) {
  const page = await context.newPage();
  try {
    const resp = await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 60_000 });
    await page.waitForTimeout(settleMs);
    for (let i = 0; i < 8; i++) {
      await page.evaluate(() => window.scrollBy(0, 1500));
      await page.waitForTimeout(250);
    }
    const d = await page.evaluate(() => {
      const clean = (s) => (s || '').replace(/\s+/g, ' ').trim();
      return {
        title: document.title,
        text: document.body ? document.body.innerText : '',
        tables: [...document.querySelectorAll('table')].map((t) => {
          let heading = '';
          // The nearest heading or caption before the table names it (a team's box score).
          for (let el = t; el && !heading; el = el.previousElementSibling || el.parentElement) {
            if (el !== t && /^(H\d|CAPTION)$/.test(el.tagName)) heading = clean(el.innerText);
            if (el.parentElement === document.body) break;
          }
          return {
            caption: clean(t.caption?.innerText || t.getAttribute('aria-label') || heading),
            rows: [...t.querySelectorAll('tr')].map((tr) => [...tr.querySelectorAll('th,td')].map((c) => clean(c.innerText))),
          };
        }),
        links: [...document.querySelectorAll('a[href]')].map((a) => [clean(a.innerText), a.href]),
      };
    });
    return { status: resp?.status() ?? 0, url: page.url(), ...d, page };
  } catch (e) {
    await page.close();
    throw e;
  }
}

// --- Normalising --------------------------------------------------------------

/** "Stanley, Taylor" or "Taylor Stanley" -> "taylor stanley". */
function personKey(name) {
  let n = String(name ?? '').replace(/^\s*#?\d+\s+/, '').trim();
  if (n.includes(',')) {
    const [last, first] = n.split(',', 2);
    n = `${first} ${last}`;
  }
  return n.toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/[^a-z ]/g, '').replace(/\s+/g, ' ').trim();
}

/** Team names as the NCAA, Kansas Athletics and the app write them, made comparable. */
function teamKey(name) {
  return String(name ?? '')
    .replace(/\(\d+\)/g, '')
    .replace(/^#\d+\s+/, '')
    .toLowerCase()
    .replace(/\bst\.?$/, 'state')
    .replace(/\bst\.?\s/, 'state ')
    .replace(/&/g, 'and')
    .replace(/[^a-z ]/g, '')
    .replace(/\s+/g, ' ')
    .trim();
}

const num = (s) => {
  const v = Number(String(s ?? '').replace(/,/g, '').trim());
  return Number.isFinite(v) ? v : null;
};

/** "8/28/2026" -> "2026-08-28". */
function isoDate(s) {
  const m = String(s).match(/(\d{1,2})\/(\d{1,2})\/(\d{4})/);
  return m ? `${m[3]}-${m[1].padStart(2, '0')}-${m[2].padStart(2, '0')}` : null;
}

/** The index of the header row of [table] that has every one of [cols], and its column map. */
function headerOf(table, cols) {
  for (let i = 0; i < Math.min(table.rows.length, 4); i++) {
    const h = table.rows[i].map((c) => c.toUpperCase());
    if (cols.every((c) => h.includes(c))) return { at: i, col: (name) => h.indexOf(name) };
  }
  return null;
}

// --- The report ---------------------------------------------------------------

const sections = [];
let section = null;
function begin(title, source) {
  section = { title, source, checked: 0, diffs: [], notes: [] };
  sections.push(section);
  console.log(`\n=== ${title}\n    source: ${source}`);
}
function same(what, app, official) {
  section.checked++;
  const eq = typeof app === 'number' && typeof official === 'number' ? Math.abs(app - official) < 1e-9 : String(app) === String(official);
  if (!eq) {
    section.diffs.push(`${what}: app ${app ?? '(none)'} · official ${official ?? '(none)'}`);
    console.log(`  DIFF ${what}: app ${app ?? '(none)'} · official ${official ?? '(none)'}`);
  }
  return eq;
}
function note(text) {
  section.notes.push(text);
  console.log(`  note: ${text}`);
}

// --- The app's data -------------------------------------------------------------

async function appData() {
  for (const url of APP_DATA) {
    try {
      const r = await fetch(url, { cache: 'no-store' });
      if (r.ok) return { url, data: await r.json() };
    } catch { /* next */ }
  }
  throw new Error('could not load the app data');
}

const FIELDS = ['sp', 'k', 'e', 'ta', 'a', 'sa', 'se', 're', 'd', 'bs', 'ba', 'bhe'];

/** Sum of the given lines, with matches played (MP: lines with a set played). */
function totals(lines) {
  const t = Object.fromEntries(FIELDS.map((f) => [f, 0]));
  t.mp = 0;
  for (const l of lines) {
    for (const f of FIELDS) t[f] += Number(l[f] ?? 0) || 0;
    if ((Number(l.sp) || 0) > 0) t.mp++;
  }
  t.pts = t.k + t.sa + t.bs + t.ba / 2;
  return t;
}

// --- The checks -------------------------------------------------------------------

async function check(season) {
  const { url: dataUrl, data } = await appData();
  console.log(`App data: ${dataUrl} (generated ${data.generatedAt})`);
  const matches = (data.matches ?? []).filter((m) => m.season === season);
  const played = matches.filter((m) => m.teamSets != null && m.opponentSets != null);
  const byDate = new Map(played.map((m) => [m.date, m]));

  const browser = await launch();
  const context = await newContext(browser);

  // 1. Kansas Athletics' cumulative statistics page.
  const cume = await read(context, `${KU}/stats/${season}`);
  const playerTable = cume.tables.find((t) => headerOf(t, ['PLAYER', 'SP', 'MP', 'K', 'TA', 'SA']));
  const mbmTable = cume.tables.find((t) => headerOf(t, ['DATE', 'OPPONENT', 'W/L', 'SP', 'K']));
  const kuNames = new Set();

  begin('Results and team totals, match by match', `${KU}/stats/${season} (Match-By-Match)`);
  if (!mbmTable) note('match-by-match table not found on the page');
  else {
    const h = headerOf(mbmTable, ['DATE', 'OPPONENT', 'W/L', 'SP', 'K']);
    const c = (r, name) => r[h.col(name)];
    const officialDates = new Set();
    for (const r of mbmTable.rows.slice(h.at + 1)) {
      const date = isoDate(c(r, 'DATE'));
      if (!date) continue;
      officialDates.add(date);
      const opp = c(r, 'OPPONENT').replace(/^(at|vs\.?)\s+/i, '');
      const m = byDate.get(date);
      if (!same(`${date} ${opp}: in the app`, m ? 'yes' : 'no', 'yes')) continue;
      same(`${date} ${opp}: opponent`, teamKey(m.opponent), teamKey(opp));
      same(`${date} ${opp}: result`, m.teamSets > m.opponentSets ? 'W' : 'L', c(r, 'W/L').toUpperCase());
      const sets = String(m.setScores ?? '').split(',').filter((s) => s.trim()).length;
      same(`${date} ${opp}: sets played`, sets, num(c(r, 'SP')));
      const lines = totals(m.lines ?? []);
      const pairs = [['K', 'k'], ['E', 'e'], ['TA', 'ta'], ['AST', 'a'], ['SA', 'sa'], ['SE', 'se'], ['RE', 're'], ['DIG', 'd'], ['BS', 'bs'], ['BA', 'ba'], ['BHE', 'bhe']];
      for (const [col, f] of pairs) {
        if (h.col(col) < 0) continue;
        same(`${date} ${opp}: team ${col} (sum of player lines)`, lines[f], num(c(r, col)));
        if (m.teamStats && m.teamStats[f] != null) same(`${date} ${opp}: team ${col} (team line)`, m.teamStats[f], num(c(r, col)));
      }
    }
    for (const m of played) {
      if (!officialDates.has(m.date)) same(`${m.date} ${m.opponent}: on Kansas Athletics' list`, 'yes', 'no');
    }
  }

  begin('Player season totals', `${KU}/stats/${season} (Player Stats)`);
  if (!playerTable) note('player table not found on the page');
  else {
    const h = headerOf(playerTable, ['PLAYER', 'SP', 'MP', 'K', 'TA', 'SA']);
    const c = (r, name) => r[h.col(name)];
    const linesByPlayer = new Map();
    for (const m of played) for (const l of m.lines ?? []) {
      const k = personKey(l.player);
      if (!linesByPlayer.has(k)) linesByPlayer.set(k, []);
      linesByPlayer.get(k).push(l);
    }
    for (const r of playerTable.rows.slice(h.at + 1)) {
      const name = c(r, 'PLAYER');
      if (!name || /^(total|opponents)$/i.test(name)) continue;
      const key = personKey(name);
      kuNames.add(key);
      const mine = linesByPlayer.get(key);
      if (!same(`${name}: in the app's box scores`, mine ? 'yes' : 'no', 'yes')) continue;
      const t = totals(mine);
      for (const [col, f] of [['SP', 'sp'], ['MP', 'mp'], ['K', 'k'], ['E', 'e'], ['TA', 'ta'], ['A', 'a'], ['SA', 'sa'], ['SE', 'se'], ['PTS', 'pts']]) {
        if (h.col(col) >= 0) same(`${name}: ${col}`, t[f], num(c(r, col)));
      }
    }
    for (const [key, lines] of linesByPlayer) {
      if (!kuNames.has(key) && totals(lines).sp > 0) same(`${lines[0].player}: on Kansas Athletics' list`, 'yes', 'no');
    }
  }

  // 2. Kansas Athletics' box score for every match.
  begin('Box scores: set scores and every player line', `${KU}/stats/${season}/<opponent>/boxscore/<id>`);
  const boxLinks = [...new Map(cume.links.filter(([, href]) => /\/stats\/\d{4}\/[^/]+\/boxscore\/\d+/.test(href)).map(([t, href]) => [href, t])).entries()];
  note(`${boxLinks.length} box scores listed; the app has ${played.length} played matches`);
  await cume.page.close();
  const mbmDates = mbmTable ? mbmTable.rows.map((r) => isoDate(r[0])).filter(Boolean) : [];
  for (const [i, [href, opp]] of boxLinks.entries()) {
    let box;
    try {
      box = await read(context, href, { settleMs: 4_000 });
    } catch (e) {
      note(`${opp}: box score did not load (${e.message})`);
      continue;
    }
    // The page's own "Date:" line; else the match-by-match row in the same place.
    const dated = box.text.match(/Date:?\s*(\d{1,2}\/\d{1,2}\/\d{4})/i);
    const date = dated ? isoDate(dated[1]) : mbmDates[i];
    const m = byDate.get(date);
    if (!m) {
      note(`${opp} (${date ?? 'no date'}): no played match on that date in the app`);
      await box.page.close();
      continue;
    }
    // The line score: a table whose header is set numbers.
    const ls = box.tables.find((t) => t.rows[0] && t.rows[0].slice(1).some((x) => x === '1') && t.rows[0].slice(1).some((x) => x === '2'));
    if (ls) {
      const head = ls.rows[0];
      const setCols = head.map((x, j) => (/^[1-5]$/.test(x) ? j : -1)).filter((j) => j >= 0);
      const kuRow = ls.rows.find((r) => /kansas/i.test(r[0]) && !/state|st\./i.test(r[0]));
      const oppRow = ls.rows.slice(1).find((r) => r !== kuRow);
      if (kuRow && oppRow) {
        const scores = setCols.map((j) => [num(kuRow[j]), num(oppRow[j])]).filter(([a, b]) => a != null && b != null && a + b > 0);
        same(`${date} ${m.opponent}: set scores`, m.setScores, scores.map(([a, b]) => `${a}-${b}`).join(', '));
      } else note(`${date} ${m.opponent}: line score found but no Kansas row (${ls.rows.map((r) => r[0]).join(' / ')})`);
    } else note(`${date} ${m.opponent}: no line score table`);
    // Kansas's player table: the stats table whose names are Kansas players.
    const statTables = box.tables.map((t) => ({ t, h: headerOf(t, ['SP', 'K', 'E', 'TA']) })).filter((x) => x.h);
    let best = null;
    let bestHits = 0;
    for (const x of statTables) {
      const nameCol = x.t.rows[x.h.at].findIndex((c) => /player|name/i.test(c));
      const col = nameCol >= 0 ? nameCol : 1;
      const hits = x.t.rows.slice(x.h.at + 1).filter((r) => kuNames.has(personKey(r[col]))).length;
      if (hits > bestHits) { best = { ...x, col }; bestHits = hits; }
    }
    if (!best) {
      note(`${date} ${m.opponent}: Kansas's stats table not found (tables: ${box.tables.map((t) => (t.rows[0] || []).slice(0, 6).join(' ')).join(' / ')})`);
      await box.page.close();
      continue;
    }
    const H = best.t.rows[best.h.at].map((c) => c.toUpperCase());
    const at = (names) => names.map((n) => H.indexOf(n)).find((j) => j >= 0) ?? -1;
    const colOf = { sp: at(['SP']), k: at(['K']), e: at(['E']), ta: at(['TA']), a: at(['A', 'AST']), sa: at(['SA']), se: at(['SE']), re: at(['RE']), d: at(['DIG', 'DIGS']), bs: at(['BS']), ba: at(['BA']), bhe: at(['BHE']) };
    const appLines = new Map((m.lines ?? []).map((l) => [personKey(l.player), l]));
    const officialNames = new Set(best.t.rows.slice(best.h.at + 1).map((r) => personKey(r[best.col])));
    for (const l of m.lines ?? []) {
      if ((Number(l.sp) || 0) > 0 && !officialNames.has(personKey(l.player))) {
        same(`${date} ${m.opponent} · ${l.player}: SP (not in the official box score)`, Number(l.sp), 0);
      }
    }
    for (const r of best.t.rows.slice(best.h.at + 1)) {
      const who = r[best.col];
      const key = personKey(who);
      if (!key || /^(totals?|team)$/.test(key)) continue;
      const l = appLines.get(key);
      const sp = num(r[colOf.sp]) ?? 0;
      if (!l) {
        if (sp > 0) same(`${date} ${m.opponent} · ${who}: in the app's box score`, 'no', 'yes');
        continue;
      }
      for (const [f, j] of Object.entries(colOf)) {
        if (j >= 0) same(`${date} ${m.opponent} · ${who}: ${f.toUpperCase()}`, Number(l[f] ?? 0), num(r[j]) ?? 0);
      }
    }
    await box.page.close();
  }

  // 3. Records on Kansas Athletics' schedule page.
  begin('Season record', `${KU}/schedule`);
  try {
    const sched = await read(context, `${KU}/schedule`);
    const grab = (label) => {
      const m = sched.text.match(new RegExp(`${label}\\s*\\n\\s*Wins\\s*\\n\\s*(\\d+)\\s*\\n\\s*Losses\\s*\\n\\s*(\\d+)`));
      return m ? `${m[1]}-${m[2]}` : null;
    };
    const w = played.filter((m) => m.teamSets > m.opponentSets).length;
    same('overall record (from the app\'s results)', `${w}-${played.length - w}`, grab('Overall'));
    const kansas = (data.standings ?? []).find((s) => s.season === season && teamKey(s.team) === 'kansas');
    if (kansas) {
      same('overall record (Big 12 table row)', `${kansas.overallW}-${kansas.overallL}`, grab('Overall'));
      same('conference record (Big 12 table row)', `${kansas.confW}-${kansas.confL}`, grab('Conf'));
    } else note('no Kansas row in the app\'s Big 12 table');
    await sched.page.close();
  } catch (e) {
    note(`schedule page did not load (${e.message})`);
  }

  // 4. AVCA poll on ncaa.com.
  begin('AVCA poll', `${NCAA}/rankings/volleyball-women/d1/avca-rankings`);
  try {
    const p = await read(context, `${NCAA}/rankings/volleyball-women/d1/avca-rankings`);
    const appPoll = (data.polls ?? []).find((x) => x.season === season && /avca/i.test(x.name));
    const through = (p.text.match(/Through Games[^\n]*/i) || [''])[0].trim();
    same('poll week', String(appPoll?.updated ?? appPoll?.name ?? '').match(/Through Games.*/i)?.[0]?.toUpperCase(), through.toUpperCase());
    const t = p.tables.find((x) => headerOf(x, ['RANK', 'SCHOOL', 'RECORD']));
    const h = t && headerOf(t, ['RANK', 'SCHOOL', 'RECORD']);
    const rows = t ? t.rows.slice(h.at + 1).filter((r) => /^\d+$/.test(r[0])) : [];
    for (const r of rows) {
      const school = r[h.col('SCHOOL')];
      const votes = Number((school.match(/\((\d+)\)/) || [0, 0])[1]);
      const a = appPoll?.rows.find((x) => teamKey(x.team) === teamKey(school));
      if (!same(`#${r[0]} ${school}: in the app's poll`, a ? 'yes' : 'no', 'yes')) continue;
      same(`${a.team}: rank`, a.rank, num(r[0]));
      same(`${a.team}: record`, a.record, r[h.col('RECORD')]);
      if (h.col('TOTAL POINTS') >= 0) same(`${a.team}: points`, num(a.points), num(r[h.col('TOTAL POINTS')]));
      same(`${a.team}: first-place votes`, a.firstPlaceVotes ?? 0, votes);
    }
    same('teams in the poll', appPoll?.rows.length, rows.length);
    await p.page.close();
  } catch (e) {
    note(`poll page did not load (${e.message})`);
  }

  // 5. RPI on ncaa.com: each Big 12 team's rank and overall record.
  // Only ranks: the page's records are as of its own week, not today.
  begin('RPI ranks of the Big 12', `${NCAA}/rankings/volleyball-women/d1/ncaa-womens-volleyball-rpi`);
  try {
    const p = await read(context, `${NCAA}/rankings/volleyball-women/d1/ncaa-womens-volleyball-rpi`);
    const through = (p.text.match(/Through Games[^\n]*/i) || [''])[0].trim();
    same('RPI week', String(data.rpi?.updated ?? '').toUpperCase(), through.toUpperCase());
    const t = p.tables.find((x) => headerOf(x, ['RANK', 'SCHOOL', 'RECORD']));
    const h = t && headerOf(t, ['RANK', 'SCHOOL', 'RECORD']);
    const byTeam = new Map(t ? t.rows.slice(h.at + 1).map((r) => [teamKey(r[h.col('SCHOOL')]), r]) : []);
    for (const s of (data.standings ?? []).filter((x) => x.season === season)) {
      const r = byTeam.get(teamKey(s.team));
      if (!same(`${s.team}: on the RPI list`, r ? 'yes' : 'no', 'yes')) continue;
      same(`${s.team}: RPI rank`, s.rpiRank, num(r[h.col('RANK')]));
    }
    await p.page.close();
  } catch (e) {
    note(`RPI page did not load (${e.message})`);
  }

  // 6. Big 12 conference records, from whichever standings page loads.
  begin('Big 12 records', STANDINGS_PAGES.join(' or '));
  let standingsDone = false;
  for (const url of STANDINGS_PAGES) {
    if (standingsDone) break;
    try {
      const p = await read(context, url);
      const i = p.text.search(/Big 12/i);
      if (p.status >= 400 || i < 0) {
        note(`${url}: status ${p.status}, ${i < 0 ? 'no Big 12 section' : 'error'}`);
        await p.page.close();
        continue;
      }
      // Each row: a team, then its conference W-L, then its overall W-L,
      // written "7-2" or as separate numbers; the section is shown in the log
      // either way, so a changed layout can be read.
      const block = p.text.slice(i, i + 4000);
      console.log(`  ${url} Big 12 section: ${block.slice(0, 1500).replace(/\n/g, ' | ')}`);
      let found = 0;
      for (const s of (data.standings ?? []).filter((x) => x.season === season)) {
        const names = [s.team, s.team.replace(/St\.$/, 'State')];
        const at = names.map((n) => block.search(new RegExp(`(^|\\n)\\s*${n.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*(\\n|$)`, 'i'))).find((j) => j >= 0);
        if (at == null || at < 0) continue;
        const recs = block.slice(at).match(/\b\d{1,2}-\d{1,2}\b/g) || [];
        if (recs.length < 2) continue;
        found++;
        same(`${s.team}: conference record`, `${s.confW}-${s.confL}`, recs[0]);
        same(`${s.team}: overall record`, `${s.overallW}-${s.overallL}`, recs[1]);
      }
      if (found >= 10) standingsDone = true;
      else note(`${url}: read ${found} of 15 teams`);
      await p.page.close();
    } catch (e) {
      note(`${url} did not load (${e.message})`);
    }
  }

  // 7. National leaders on ncaa.com.
  begin('National leaders', `${NCAA}/stats/volleyball-women/d1/current/individual/<id>`);
  const leaders = data.nationalLeaders;
  if (leaders?.season === season) {
    for (const cat of leaders.categories ?? []) {
      try {
        const p = await read(context, `${NCAA}/stats/volleyball-women/d1/current/individual/${cat.id}`, { settleMs: 4_000 });
        const updated = (p.text.match(/Last updated[^\n]*/i) || [''])[0].trim();
        const t = p.tables.find((x) => headerOf(x, ['RANK', 'NAME', 'TEAM']));
        if (!t) { note(`${cat.name}: no table`); await p.page.close(); continue; }
        const h = headerOf(t, ['RANK', 'NAME', 'TEAM']);
        const H = t.rows[h.at].map((c) => c.toUpperCase());
        const valueCol = H.length - 1;
        // The table repeats its header in a second row.
        const official = t.rows.slice(h.at + 1).filter((r) => r[h.col('NAME')] && !/^name$/i.test(r[h.col('NAME')]));
        const appTop = cat.rows.slice(0, official.length);
        let diffs = 0;
        for (const [j, r] of official.entries()) {
          const a = appTop[j];
          const ok = a && personKey(a.player) === personKey(r[h.col('NAME')]) && teamKey(a.team) === teamKey(r[h.col('TEAM')]) &&
            String(a.value).replace(/^0(?=\.)/, '') === String(r[valueCol]).replace(/^0(?=\.)/, '');
          section.checked++;
          if (!ok) {
            diffs++;
            if (diffs <= 5) {
              const msg = `${cat.name} row ${j + 1}: app ${a ? `${a.player} (${a.team}) ${a.value}` : '(none)'} · official ${r[h.col('NAME')]} (${r[h.col('TEAM')]}) ${r[valueCol]}`;
              section.diffs.push(msg);
              console.log(`  DIFF ${msg}`);
            }
          }
        }
        if (diffs > 5) section.diffs.push(`${cat.name}: ${diffs - 5} more rows differ`);
        if (diffs) note(`${cat.name}: official page ${updated || '(no date)'}; app "${leaders.updated}"`);
        // Every Kansas player the app lists, wherever she ranks.
        for (const a of cat.rows.filter((x) => teamKey(x.team) === 'kansas')) {
          const r = official.find((x) => personKey(x[h.col('NAME')]) === personKey(a.player));
          if (r) same(`${cat.name} · ${a.player}`, `${a.rank} ${String(a.value).replace(/^0(?=\.)/, '')}`, `${num(r[h.col('RANK')]) ?? r[h.col('RANK')]} ${String(r[valueCol]).replace(/^0(?=\.)/, '')}`);
        }
        await p.page.close();
      } catch (e) {
        note(`${cat.name}: did not load (${e.message})`);
      }
    }
  } else note(`the app has no ${season} national leaders`);

  await browser.close();
  summarize(dataUrl, data);
}

function summarize(dataUrl, data) {
  const lines = [
    `## Data cross-check`,
    ``,
    `App data: ${dataUrl}, generated ${data.generatedAt}.`,
    ``,
    `| Area | Values compared | Differences |`,
    `|---|---|---|`,
    ...sections.map((s) => `| ${s.title} | ${s.checked} | ${s.diffs.length} |`),
  ];
  for (const s of sections) {
    if (!s.diffs.length && !s.notes.length) continue;
    lines.push('', `### ${s.title}`, `Source: ${s.source}`, '');
    for (const d of s.diffs) lines.push(`- ${d}`);
    for (const n of s.notes) lines.push(`- _note:_ ${n}`);
  }
  const md = lines.join('\n');
  console.log(`\n\n${md}`);
  if (process.env.GITHUB_STEP_SUMMARY) fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, md + '\n');
}

async function probe(urls) {
  const browser = await launch();
  const context = await newContext(browser);
  for (const url of urls.length ? urls : PROBE_PAGES) {
    console.log(`\n######## ${url}`);
    try {
      const d = await read(context, url);
      console.log(`status ${d.status} · final ${d.url} · title ${d.title}`);
      console.log(`---- text (first 4000 of ${d.text.length} chars)\n${d.text.slice(0, 4000)}`);
      d.tables.forEach((t, i) => {
        console.log(`---- table ${i + 1}/${d.tables.length} "${t.caption}" ${t.rows.length} rows`);
        for (const r of t.rows.slice(0, 8)) console.log('  | ' + r.join(' | '));
      });
      const links = d.links.filter(([text, href]) => /box|stat|pdf|recap|game/i.test(text + ' ' + href));
      console.log(`---- ${links.length} links about box scores / stats`);
      for (const [text, href] of links.slice(0, 40)) console.log(`  ${text} -> ${href}`);
      await d.page.close();
    } catch (e) {
      console.log(`FAILED: ${e.message}`);
    }
  }
  await browser.close();
}

const [mode, ...rest] = process.argv.slice(2);
if (mode === 'check') await check(rest[0] || String(new Date().getUTCFullYear()));
else if (mode === 'probe') await probe(rest);
else {
  console.error('usage: data-cross-check.mjs check [season] | probe [url...]');
  process.exit(2);
}
