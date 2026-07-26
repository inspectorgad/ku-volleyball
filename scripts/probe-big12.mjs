// TEMPORARY Step 0 probe: verifies the data sources behind the Big 12 tab before
// any app code is written. Runs in GitHub Actions (open outbound network) and
// commits raw output under probe-big12/ for inspection. Delete after reading.
import fs from 'fs';

const API = 'https://ncaa-api.henrygd.me';
const OUT = 'probe-big12';
fs.rmSync(OUT, { recursive: true, force: true });
fs.mkdirSync(OUT, { recursive: true });

const summary = [];
const note = (s) => { summary.push(s); console.log(s); };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function grab(name, url) {
  try {
    const resp = await fetch(url, { headers: { accept: 'application/json' } });
    const body = await resp.text();
    note(`GET ${url} -> ${resp.status} (${body.length} chars)`);
    fs.writeFileSync(`${OUT}/${name}`, `// ${url}\n${body.slice(0, 400_000)}`);
    await sleep(1200); // public instance: 5 req/s cap, stay well under
    return resp.ok ? body : null;
  } catch (e) {
    note(`GET ${url} -> ERROR ${e.message}`);
    await sleep(1200);
    return null;
  }
}

// --- 1. Standings endpoint: does it exist for volleyball, and carry Big 12? ---
const standings = await grab('standings-d1.json', `${API}/standings/volleyball-women/d1`);
if (standings) {
  const hasBig12 = /big[\s-]?12/i.test(standings);
  note(`  standings mentions Big 12: ${hasBig12}`);
  try {
    const j = JSON.parse(standings);
    note(`  standings top-level keys: ${Object.keys(j).join(', ')}`);
  } catch { note('  standings did not parse as JSON'); }
}

// --- 2. Rankings endpoints (ncaa.com slugs) ---------------------------------
for (const type of ['avca-rankings', 'ncaa-womens-volleyball-rpi']) {
  const body = await grab(`rankings-${type}.json`, `${API}/rankings/volleyball-women/d1/${type}`);
  if (!body) continue;
  try {
    const j = JSON.parse(body);
    note(`  ${type} keys: ${Object.keys(j).join(', ')}`);
    const rows = j.data ?? j.rankings ?? [];
    note(`  ${type} row count: ${Array.isArray(rows) ? rows.length : 'n/a'}`);
    if (Array.isArray(rows) && rows.length) {
      note(`  ${type} first row: ${JSON.stringify(rows[0]).slice(0, 240)}`);
    }
    // Which season/week does it describe? Decides whether 2025 is reproducible.
    for (const k of ['title', 'updated', 'page', 'sport', 'division', 'week', 'season']) {
      if (j[k] !== undefined) note(`  ${type} ${k}: ${JSON.stringify(j[k]).slice(0, 120)}`);
    }
  } catch { note(`  ${type} did not parse as JSON`); }
}

// --- 3. Conference tags on a CONFERENCE-PLAY date (the key question) --------
// 2025-10-16 was KU at Houston, mid Big 12 play.
for (const date of ['2025-10-16', '2025-11-15']) {
  const [y, m, d] = date.split('-');
  const body = await grab(`scoreboard-${date}.json`, `${API}/scoreboard/volleyball-women/d1/${y}/${m}/${d}`);
  if (!body) continue;
  try {
    const games = (JSON.parse(body).games ?? []).map((w) => w.game ?? w);
    const confOf = (side) => (side?.conferences ?? []).map((c) => c.conferenceSeo);
    let b12Teams = new Set(), b12VsB12 = 0, ranked = 0;
    for (const g of games) {
      const hc = confOf(g.home), ac = confOf(g.away);
      if (hc.includes('big-12')) b12Teams.add(g.home.names.short);
      if (ac.includes('big-12')) b12Teams.add(g.away.names.short);
      if (hc.includes('big-12') && ac.includes('big-12')) b12VsB12++;
      for (const s of [g.home, g.away]) if (s?.rank) ranked++;
    }
    note(`  ${date}: ${games.length} matches | big-12 teams tagged: ${b12Teams.size} | big12-vs-big12: ${b12VsB12} | ranked entries: ${ranked}`);
    note(`  ${date} big-12 teams: ${[...b12Teams].sort().join(', ')}`);
  } catch (e) { note(`  ${date} parse error: ${e.message}`); }
}

fs.writeFileSync(`${OUT}/summary.txt`, summary.join('\n') + '\n');
console.log('probe complete');
