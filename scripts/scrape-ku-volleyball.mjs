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
import { parseRoster, playersFromJson } from './roster-parser.mjs';

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
index.opponentGames ??= {};

// Teams Kansas is scheduled to face but has not played yet. Their matches are
// captured so the app can show how an upcoming opponent has been playing —
// only for the current season, since that is the only one anybody asks about,
// and it keeps this off the already-swept historical dates.
const CURRENT_SEASON = SEASONS.map(Number).sort().at(-1);
// Mirrors norm_team() in update-seed.py. Strips the poll-vote count the NCAA
// appends and the "(Exh.)" kuathletics adds to exhibitions — the latter is why
// the season opener against Creighton matched nothing on the first run.
const normTeamName = (name) =>
  (name || '')
    .replace(/\s*\((?:\d+|exh\.?|exhibition)\)\s*$/i, '')
    .toLowerCase()
    .replace(/\./g, '')
    .replace(/\bstate\b/g, 'st')
    .replace(/\s+/g, ' ')
    .trim();
const upcomingOpponents = new Set(
  (() => {
    try {
      return JSON.parse(fs.readFileSync('scraped/upcoming.json', 'utf8'))
        .map((u) => normTeamName(u.opponent));
    } catch {
      return [];
    }
  })()
);
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

      // Scheduled-opponent capture: the sweep already has every D1 game for the
      // date, so their season costs nothing extra to spot.
      if (Number(date.slice(0, 4)) === CURRENT_SEASON && g.gameState === 'final') {
        const facing = sides.filter((s) => upcomingOpponents.has(normTeamName(s?.names?.short)));
        if (facing.length) {
          index.opponentGames[g.gameID] ??= {
            date,
            season: date.slice(0, 4),
            teams: facing.map((s) => s?.names?.short),
            boxscored: false,
          };
        }
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

// --- 2b. Box scores for scheduled opponents' own matches -------------------
// Only the boxscore call here: the contest wrapper adds nothing we use for a
// team we are not playing in that game, and it halves the request count.
let oppCaptured = 0;
for (const [gameId, meta] of Object.entries(index.opponentGames)) {
  if (meta.boxscored) continue;
  try {
    const box = await getJson(`${API}/game/${gameId}/boxscore`);
    fs.writeFileSync(
      `scraped/ncaa-opp-${gameId}.json`,
      JSON.stringify({ gameId, date: meta.date, season: meta.season, box }, null, 1)
    );
    meta.boxscored = true;
    oppCaptured++;
  } catch (e) {
    console.log(`opponent boxscore ${gameId}: ${e.message}`);
  }
}
console.log(
  `scheduled-opponent games: ${Object.keys(index.opponentGames).length} known, ` +
  `${oppCaptured} box scores captured this run`
);

fs.writeFileSync(INDEX_PATH, JSON.stringify(index, null, 1));

// --- 3. kuathletics.com: current roster + upcoming schedule ----------------
// Best-effort: NCAA data alone keeps results flowing if Sidearm blocks us.
try {
  const browser = await chromium.launch();
  // Setting userAgent alone leaves the Client Hints headers untouched, so we
  // were announcing two different identities: a UA string claiming Chrome 126
  // while sec-ch-ua said HeadlessChrome 131. Both are now derived from the
  // browser actually running, so they agree and neither goes stale when
  // Playwright updates Chromium.
  const major = /^(\d+)/.exec(browser.version())?.[1] ?? '131';
  const context = await browser.newContext({
    userAgent:
      'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 ' +
      `(KHTML, like Gecko) Chrome/${major}.0.0.0 Safari/537.36`,
    viewport: { width: 1400, height: 2400 },
    extraHTTPHeaders: {
      'sec-ch-ua': `"Chromium";v="${major}", "Google Chrome";v="${major}", "Not_A Brand";v="24"`,
      'sec-ch-ua-mobile': '?0',
      'sec-ch-ua-platform': '"Windows"',
    },
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

  // Roster parsing lives in roster-parser.mjs because opponents' sites use the
  // same handful of layouts; Kansas is just the first of them.
  const rosterText = await pageText('https://kuathletics.com/sports/womens-volleyball/roster');
  fs.writeFileSync('scraped/roster-page.txt', rosterText);
  const roster = parseRoster(rosterText);
  fs.writeFileSync('scraped/roster.json', JSON.stringify(roster, null, 1));
  const withHeight = roster.filter((p) => p.height).length;
  console.log(`roster: ${roster.length} players (${withHeight} with height)`);

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

  // --- 3b. Opponent rosters -----------------------------------------------
  // The NCAA has no roster endpoint (probed: every candidate 404s or 422s), so
  // an opponent's line-up before they have played anyone comes from their own
  // athletics site. Scraped for the teams on Kansas' schedule plus the Big 12.
  //
  // Rosters barely move once a season starts, so this refreshes weekly rather
  // than nightly — except for a team we have never fetched, which is pulled on
  // the first run that needs it.
  // Sidearm offers the same roster as a table, a list and cards. Some schools
  // ship the page with every view empty until one is chosen, so each toggle is
  // clicked in turn and the caller re-parses after. Any click that fails is
  // ignored: this is an attempt to coax a stubborn page, not a required step.
  // Returns the toggles it managed to click, so a miss can report whether this
  // even had anything to work with.
  async function clickViewToggles(page) {
    const clicked = [];
    for (const label of ['Table View', 'List View', 'Card View']) {
      try {
        const button = page.getByText(label, { exact: false }).first();
        if (await button.count()) {
          await button.click({ timeout: 3_000 });
          clicked.push(label);
        }
      } catch {
        /* toggle absent or not clickable — nothing to coax */
      }
    }
    return clicked;
  }

  async function rosterFromPage(url) {
    const page = await context.newPage();
    // The JSON a page fetches, kept for the schools whose roster never becomes
    // text. Only the school's own requests: an athletics page fires dozens of
    // advertising bid requests, all of them JSON, and on Texas Tech they filled
    // the whole budget before the roster was ever requested. A path naming a
    // roster is kept whatever the host, since some schools serve their data from
    // a separate domain.
    const siteDomain = new URL(url).hostname.split('.').slice(-2).join('.');
    const worthKeeping = (u) => {
      try {
        const { hostname, pathname } = new URL(u);
        return hostname.endsWith(siteDomain) || /roster|athlete|player/i.test(pathname);
      } catch {
        return false;
      }
    };
    const payloads = [];
    const jsonUrls = [];
    page.on('response', async (res) => {
      try {
        if (payloads.length >= 60) return;
        if (!/json/i.test(res.headers()['content-type'] || '')) return;
        if (!worthKeeping(res.url())) return;
        const body = await res.json();
        payloads.push(body);
        jsonUrls.push(`${res.url()} (${JSON.stringify(body).length} bytes)`);
      } catch {
        /* redirected, aborted, or not really JSON — nothing to keep */
      }
    });
    try {
      await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 60_000 });
      let text = '';
      let players = [];
      let toggles = [];
      for (let attempt = 0; attempt < 12; attempt++) {
        await page.waitForTimeout(1_500);
        await page.evaluate(() => window.scrollBy(0, 1400));
        text = await page.evaluate(() => (document.body ? document.body.innerText : ''));
        players = parseRoster(text);
        if (players.length >= 8) break;
        // Texas Tech renders the roster shell — the view toggles and the column
        // headers — with no rows in it, and the rows arrive only once a view is
        // selected. Clicking the toggles is harmless on the sites that need no
        // help, since a page without them simply has nothing to click.
        if (attempt === 3) toggles = await clickViewToggles(page);
      }
      if (players.length < 8) {
        // No roster in the text. Fall back to the data the page was handed:
        // what it fetched over the wire, plus the state blob a Nuxt or Next
        // site hydrates from, which is inline rather than fetched.
        const inline = await page.evaluate(() => {
          try {
            const state = window.__NUXT__ ?? window.__NEXT_DATA__ ?? null;
            return state ? JSON.parse(JSON.stringify(state)) : null;
          } catch {
            return null; // circular or function-valued state
          }
        });
        const fromJson = playersFromJson([...payloads, inline].filter(Boolean));
        if (fromJson.length > players.length) players = fromJson;
      }
      // The rendered text is what the parser normally sees, but a page that
      // renders no roster leaves nothing in it to diagnose from, so the markup
      // is kept alongside - for the misses only, since that is the only case
      // that uses it, and it is the one call here with no timeout of its own.
      // page.content() takes no timeout option, so it is raced against one: a
      // wedged renderer on a page we already failed to parse must not be able
      // to stall the whole nightly run.
      let html = '';
      if (players.length < 8) {
        html = await Promise.race([
          page.content().catch(() => ''),
          new Promise((resolve) => setTimeout(() => resolve(''), 20_000)),
        ]);
      }
      return { text, players, html, jsonUrls, toggles };
    } finally {
      await page.close();
    }
  }

  const sites = JSON.parse(fs.readFileSync('scripts/opponent-sites.json', 'utf8'));
  const siteMap = { ...sites.verified, ...sites.unverified };

  const wanted = new Map();
  for (const u of uniqueUpcoming) wanted.set(normTeamName(u.opponent), u.opponent);
  for (const g of Object.values(index.big12Games)) {
    for (const side of [g.home, g.away]) {
      if (side.inConference) wanted.set(normTeamName(side.name), side.name);
    }
  }
  wanted.delete(normTeamName('Kansas')); // already scraped above

  const ROSTERS_PATH = 'scraped/opponent-rosters.json';
  const previous = fs.existsSync(ROSTERS_PATH)
    ? JSON.parse(fs.readFileSync(ROSTERS_PATH, 'utf8'))
    : {};
  const weekAgo = new Date(Date.now() - 7 * 86_400_000).toISOString();

  const rosters = { ...previous };
  const unmapped = [];
  const failed = [];
  let fetched = 0;
  for (const [key, displayName] of [...wanted].sort()) {
    const url = siteMap[key];
    if (!url) { unmapped.push(displayName); continue; }
    if (previous[key]?.fetchedAt > weekAgo && previous[key]?.players?.length) continue;
    try {
      // Some roster tables render well after domcontentloaded, so keep
      // scrolling and re-reading until the parser finds players. Using the
      // parse as the readiness signal avoids guessing at a per-site selector.
      const { text, players, html, jsonUrls, toggles } = await rosterFromPage(url);
      const miss = `scraped/roster-miss-${key.replace(/\s+/g, '-')}`;
      // A volleyball roster is ~13-20 players. Anything under this floor means
      // we matched stray page furniture rather than the roster - Arizona St.'s
      // scoreboard yields a single phantom "San Diego" - and a partial roster
      // is worse than none, so it is refused and the page kept for diagnosis.
      const MIN_ROSTER = 8;
      if (players.length < MIN_ROSTER) {
        fs.writeFileSync(`${miss}.txt`, text);
        // Capped because a school's markup can run to megabytes, and the part
        // that identifies where the data really comes from is near the top.
        fs.writeFileSync(`${miss}.html`, html.slice(0, 500_000));
        failed.push(`${displayName} (parsed ${players.length}, below the ${MIN_ROSTER} floor; page saved)`);
        // The JSON the page fetched is the shortest route to where the roster
        // actually lives, so the URLs go in the log rather than into a file.
        console.log(`  ${displayName} view toggles clicked: ${toggles?.join(', ') || 'none found'}`);
        console.log(`  ${displayName} fetched its own JSON from:\n    ${jsonUrls?.join('\n    ') || '(none)'}`);
        continue;
      }
      rosters[key] = {
        team: displayName,
        url,
        fetchedAt: new Date().toISOString(),
        players,
      };
      fetched++;
      fs.rmSync(`${miss}.txt`, { force: true });
      fs.rmSync(`${miss}.html`, { force: true });
      console.log(`  roster ${displayName}: ${players.length} players ` +
        `(${players.filter((p) => p.height).length} with height)`);
    } catch (e) {
      failed.push(`${displayName} (${e.message.split('\n')[0]})`);
    }
  }
  // A team that failed once and parses now keeps its stale miss page around,
  // because the weekly refresh skips it and so never reaches the cleanup above.
  // Sweeping every stored team means the saved pages are only the open cases.
  for (const [key, stored] of Object.entries(rosters)) {
    if (!stored?.players?.length) continue;
    const miss = `scraped/roster-miss-${key.replace(/\s+/g, '-')}`;
    fs.rmSync(`${miss}.txt`, { force: true });
    fs.rmSync(`${miss}.html`, { force: true });
  }
  fs.writeFileSync(ROSTERS_PATH, JSON.stringify(rosters, null, 1));
  console.log(`opponent rosters: ${Object.keys(rosters).length} teams stored, ${fetched} refreshed this run`);
  if (unmapped.length) console.log(`  no roster URL mapped for: ${unmapped.join(', ')}`);
  if (failed.length) console.log(`  failed: ${failed.join(', ')}`);

  await browser.close();
} catch (e) {
  console.log(`kuathletics scrape failed (non-fatal): ${e.message}`);
}

console.log('scrape complete');
