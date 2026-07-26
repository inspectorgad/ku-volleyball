// Scrapes KU Women's Volleyball data from three sources:
//  1. NCAA API (ncaa-api.henrygd.me, JSON wrapper around ncaa.com) — match
//     discovery via the daily scoreboard, then per-match box scores. The same
//     sweep also yields every Big 12 game (each team carries a conference
//     tag), which is what the Big 12 standings are computed from.
//  2. NCAA API rankings — AVCA coaches top 25 and NCAA RPI. Both are
//     current-snapshot endpoints (no per-season history), so each nightly run
//     captures the latest and update-seed.py keys it by season.
//     Note: /standings/volleyball-women/d1 returns HTTP 500 for this sport,
//     which is why conference records are computed rather than fetched.
//  3. kuathletics.com (Sidearm) via headless Chromium — current roster and
//     upcoming schedule (which the NCAA scoreboard only shows day-of).
// Runs in GitHub Actions where outbound network is open. Incremental: an
// index in scraped/ku-index.json records scanned dates and finished games so
// nightly runs only touch new dates.
import { chromium } from 'playwright';
import fs from 'fs';

const API = 'https://ncaa-api.henrygd.me';
const SEASONS = (process.env.SEASONS || '2026').trim().split(/\s+/);
const TEAM_SEO = 'kansas';
const CONFERENCE_SEO = 'big-12';
// Bump to force a one-time full re-sweep when the sweep starts capturing
// something new (the scannedDates cache would otherwise skip old dates).
const INDEX_VERSION = 2;

fs.mkdirSync('scraped', { recursive: true });

const INDEX_PATH = 'scraped/ku-index.json';
const index = fs.existsSync(INDEX_PATH)
  ? JSON.parse(fs.readFileSync(INDEX_PATH, 'utf8'))
  : { indexVersion: INDEX_VERSION, scannedDates: {}, games: {}, big12Games: {} };

index.games ??= {};
index.big12Games ??= {};
if ((index.indexVersion ?? 1) < INDEX_VERSION) {
  console.log(
    `index v${index.indexVersion ?? 1} < v${INDEX_VERSION}: clearing ${Object.keys(index.scannedDates ?? {}).length} scanned dates for a one-time full re-sweep`
  );
  index.scannedDates = {};
  index.indexVersion = INDEX_VERSION;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function getJson(url) {
  const resp = await fetch(url, { headers: { accept: 'application/json' } });
  await sleep(400); // public instance is limited to 5 req/s
  if (!resp.ok) throw new Error(`${resp.status} for ${url}`);
  return resp.json();
}

function* seasonDates(year) {
  // D1 women's volleyball: mid-August exhibitions through the December final.
  const start = new Date(Date.UTC(year, 7, 15));
  const end = new Date(Date.UTC(year, 11, 31));
  for (let d = start; d <= end; d = new Date(d.getTime() + 86_400_000)) {
    yield d.toISOString().slice(0, 10);
  }
}

const today = new Date().toISOString().slice(0, 10);
const recentCutoff = new Date(Date.now() - 4 * 86_400_000).toISOString().slice(0, 10);

// --- 1. Scoreboard scan: find KU games ------------------------------------
for (const season of SEASONS) {
  for (const date of seasonDates(Number(season))) {
    if (date > today) break;
    // Rescan recent dates (results may have just gone final); skip older
    // dates we've already scanned.
    if (index.scannedDates[date] && date < recentCutoff) continue;

    const [y, m, d] = date.split('-');
    let data;
    try {
      data = await getJson(`${API}/scoreboard/volleyball-women/d1/${y}/${m}/${d}`);
    } catch (e) {
      console.log(`scoreboard ${date}: ${e.message}`);
      continue; // leave unscanned so a transient failure retries tomorrow
    }
    for (const wrap of data.games || []) {
      const g = wrap.game || wrap;
      const sides = [g.home, g.away];

      // Big 12 capture: any game with at least one conference team. Conference
      // games (both sides tagged) drive conference records; the rest still
      // count toward each team's overall record. Membership comes from the
      // tags themselves, never a hardcoded list, so realignment can't stale it.
      const confTags = (s) => (s?.conferences ?? []).map((c) => c.conferenceSeo);
      const homeInConf = confTags(g.home).includes(CONFERENCE_SEO);
      const awayInConf = confTags(g.away).includes(CONFERENCE_SEO);
      if ((homeInConf || awayInConf) && g.gameState === 'final') {
        const side = (s, inConf) => ({
          name: s?.names?.short ?? '',
          seo: s?.names?.seo ?? '',
          score: Number(s?.score ?? 0),
          winner: Boolean(s?.winner),
          rank: s?.rank ? Number(s.rank) : null,
          inConference: inConf,
        });
        index.big12Games[g.gameID] = {
          date,
          season: date.slice(0, 4),
          home: side(g.home, homeInConf),
          away: side(g.away, awayInConf),
          conferenceGame: homeInConf && awayInConf,
        };
      }

      if (!sides.some((s) => s?.names?.seo === TEAM_SEO)) continue;
      const existing = index.games[g.gameID];
      if (existing?.final && existing?.boxscored) continue;
      index.games[g.gameID] = {
        date,
        final: g.gameState === 'final',
        home: g.home?.names?.short,
        away: g.away?.names?.short,
        boxscored: existing?.boxscored || false,
      };
      console.log(`found KU game ${g.gameID} on ${date}: ${g.away?.names?.short} at ${g.home?.names?.short} (${g.gameState})`);
    }
    index.scannedDates[date] = true;
  }
}
console.log(`big12 games captured: ${Object.keys(index.big12Games).length}`);

// --- 1b. Rankings snapshots (best effort; never fail the run) ---------------
// Both endpoints serve only the CURRENT poll/RPI — no per-season history — so
// each run overwrites its snapshot and update-seed.py keys it by the season in
// the "Through Games ..." label.
for (const [name, path] of [
  ['avca', 'rankings/volleyball-women/d1/avca-rankings'],
  ['rpi', 'rankings/volleyball-women/d1/ncaa-womens-volleyball-rpi'],
]) {
  try {
    const data = await getJson(`${API}/${path}`);
    fs.writeFileSync(`scraped/rankings-${name}.json`, JSON.stringify(data, null, 1));
    console.log(`rankings ${name}: ${data.data?.length ?? 0} rows (${data.updated ?? 'no date'})`);
  } catch (e) {
    console.log(`rankings ${name} failed (non-fatal): ${e.message}`);
  }
}

// --- 2. Box scores for final games not yet captured ------------------------
for (const [gameId, meta] of Object.entries(index.games)) {
  if (!meta.final || meta.boxscored) continue;
  try {
    const info = await getJson(`${API}/game/${gameId}`);
    const box = await getJson(`${API}/game/${gameId}/boxscore`);
    fs.writeFileSync(
      `scraped/ncaa-game-${gameId}.json`,
      JSON.stringify({ gameId, date: meta.date, info, box }, null, 1)
    );
    meta.boxscored = true;
    console.log(`captured box score for ${gameId} (${meta.date})`);
  } catch (e) {
    console.log(`boxscore ${gameId}: ${e.message}`);
  }
}

fs.writeFileSync(INDEX_PATH, JSON.stringify(index, null, 1));

// --- 3. kuathletics.com: current roster + upcoming schedule ----------------
// Best-effort: NCAA data alone keeps results flowing if Sidearm blocks us.
try {
  const browser = await chromium.launch();
  const context = await browser.newContext({
    userAgent:
      'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36',
    viewport: { width: 1400, height: 2400 },
  });

  async function pageText(url) {
    const page = await context.newPage();
    await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 60_000 });
    await page.waitForTimeout(8_000);
    for (let i = 0; i < 10; i++) {
      await page.evaluate(() => window.scrollBy(0, 1200));
      await page.waitForTimeout(400);
    }
    const text = await page.evaluate(() => (document.body ? document.body.innerText : ''));
    await page.close();
    return text;
  }

  // Roster: lines run "Jersey Number\n<num>\n<name>\nPosition\n<pos>\n..."
  const rosterText = await pageText('https://kuathletics.com/sports/womens-volleyball/roster');
  fs.writeFileSync('scraped/roster-page.txt', rosterText);
  const rosterLines = rosterText.split('\n').map((l) => l.trim());
  const roster = [];
  for (let i = 0; i < rosterLines.length; i++) {
    if (rosterLines[i] !== 'Jersey Number') continue;
    const number = rosterLines[i + 1] || '';
    const name = rosterLines[i + 2] || '';
    let position = '';
    if (rosterLines[i + 3] === 'Position') position = (rosterLines[i + 4] || '').trim();
    if (/^\d{1,2}$/.test(number) && /^[A-Za-z'.-]+( [A-Za-z'.-]+)+$/.test(name)) {
      roster.push({ name, jerseyNumber: number, position: position.replace(/\s+$/, '') });
    }
  }
  fs.writeFileSync('scraped/roster.json', JSON.stringify(roster, null, 1));
  console.log(`roster: ${roster.length} players`);

  // Upcoming matches from the site-wide scoreboard rotator:
  // "Upcoming Event: Women's Volleyball versus X on August 22, 2026 at 1 p.m. CT"
  const schedText = await pageText('https://kuathletics.com/sports/womens-volleyball/schedule');
  fs.writeFileSync('scraped/schedule-page.txt', schedText);
  const months = ['January', 'February', 'March', 'April', 'May', 'June', 'July',
    'August', 'September', 'October', 'November', 'December'];
  const upcoming = [];
  const re = /Upcoming Event: Women's Volleyball (versus|at) (.+?) on ([A-Z][a-z]+) (\d{1,2}), (\d{4})/g;
  for (const m of schedText.matchAll(re)) {
    const month = months.indexOf(m[3]) + 1;
    if (month === 0) continue;
    const date = `${m[5]}-${String(month).padStart(2, '0')}-${String(m[4]).padStart(2, '0')}`;
    upcoming.push({ date, opponent: m[2].trim(), home: m[1] === 'versus' });
  }
  // De-dup (the rotator repeats on every page view)
  const seen = new Set();
  const uniqueUpcoming = upcoming.filter((u) => {
    const k = `${u.date}|${u.opponent}`;
    if (seen.has(k)) return false;
    seen.add(k);
    return true;
  });
  fs.writeFileSync('scraped/upcoming.json', JSON.stringify(uniqueUpcoming, null, 1));
  console.log(`upcoming: ${uniqueUpcoming.length} matches`);

  await browser.close();
} catch (e) {
  console.log(`kuathletics scrape failed (non-fatal): ${e.message}`);
}

console.log('scrape complete');
