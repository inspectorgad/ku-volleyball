// One-off probe: can we show an opponent's roster BEFORE the match is played?
//
// Two candidate sources, and this answers both before any app code is written:
//
//  A. NCAA-derived — the nightly sweep already pulls every D1 game on every
//     date, so an upcoming opponent's already-played matches are visible to us
//     for free. Their box scores would give us who has actually played (name,
//     number, position) plus real stats. Unknown: whether the API also exposes
//     a roster endpoint outright, which would be simpler and would cover teams
//     that have not played yet.
//
//  B. The school's own athletics site — the true preseason roster including
//     height and class year. Unknown: whether each site is the same Sidearm
//     layout kuathletics.com uses, so the roster block parser in
//     scrape-ku-volleyball.mjs can be reused rather than written per school.
//
// Writes raw evidence under probe/ and a readable summary to probe/SUMMARY.md.
// Delete this script and its workflow once the questions above are answered.
import { chromium } from 'playwright';
import fs from 'fs';

const API = 'https://ncaa-api.henrygd.me';
fs.rmSync('probe', { recursive: true, force: true });
fs.mkdirSync('probe', { recursive: true });

const summary = [];
const note = (line) => { summary.push(line); console.log(line); };
const save = (name, data) => fs.writeFileSync(`probe/${name}`, data);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function getJson(url) {
  const resp = await fetch(url, { headers: { accept: 'application/json' } });
  const body = await resp.text();
  await sleep(400); // public instance is limited to 5 req/s
  return { status: resp.status, body };
}

// --- A1. Is there a roster endpoint at all? --------------------------------
// Candidate shapes, guessed from the endpoints we already use. A 200 with
// player names on any of these makes approach A work even for a team that
// has not played yet, which is the one gap in the box-score approach.
note('## A1. NCAA roster endpoint candidates\n');
const candidates = [
  `${API}/rosters/volleyball-women/d1/pittsburgh`,
  `${API}/roster/volleyball-women/d1/pittsburgh`,
  `${API}/team/volleyball-women/d1/pittsburgh`,
  `${API}/teams/volleyball-women/d1/pittsburgh`,
  `${API}/schools/pittsburgh`,
  `${API}/school/pittsburgh/volleyball-women`,
  `${API}/stats/volleyball-women/d1/current/individual/1116`,
];
for (const url of candidates) {
  try {
    const { status, body } = await getJson(url);
    const looksLikePlayers = /"(firstName|lastName|playerName|player)"/i.test(body);
    note(`- \`${url.replace(API, '')}\` -> **${status}**` +
      (status === 200 ? ` (${body.length} chars, player-shaped: ${looksLikePlayers})` : ''));
    if (status === 200) save(`ncaa-${url.split('/').slice(3).join('-')}.json`, body.slice(0, 200_000));
  } catch (e) {
    note(`- \`${url.replace(API, '')}\` -> ERROR ${e.message}`);
  }
}

// --- A2. Can we enumerate an opponent's season from the sweep? -------------
// 2026 has not started, so 2025 stands in as the model: find Pittsburgh's
// games from the same scoreboard payload the nightly sweep already fetches,
// then pull one box score and count the players it names.
note('\n## A2. Opponent season from the scoreboard sweep (2025 as the model)\n');
const TARGET = 'pittsburgh';
const found = [];
for (const [m, d] of [['09', '05'], ['09', '06'], ['09', '12'], ['09', '19'], ['10', '03'], ['10', '17']]) {
  try {
    const { status, body } = await getJson(`${API}/scoreboard/volleyball-women/d1/2025/${m}/${d}`);
    if (status !== 200) { note(`- 2025-${m}-${d} -> ${status}`); continue; }
    const data = JSON.parse(body);
    const games = (data.games || []).map((w) => w.game || w);
    const hit = games.filter((g) =>
      [g.home, g.away].some((s) => s?.names?.seo === TARGET));
    note(`- 2025-${m}-${d}: ${games.length} D1 games that day, ${hit.length} involving ${TARGET}`);
    for (const g of hit) found.push({ id: g.gameID, date: `2025-${m}-${d}`, state: g.gameState });
  } catch (e) {
    note(`- 2025-${m}-${d} -> ERROR ${e.message}`);
  }
}
save('ncaa-opponent-games.json', JSON.stringify(found, null, 1));
note(`\nFound ${found.length} ${TARGET} games across the sampled dates.`);

const finalGame = found.find((g) => g.state === 'final');
if (finalGame) {
  const { status, body } = await getJson(`${API}/game/${finalGame.id}/boxscore`);
  save('ncaa-opponent-boxscore.json', body.slice(0, 400_000));
  if (status === 200) {
    const box = JSON.parse(body);
    for (const tb of box.teamBoxscore || []) {
      const team = (box.teams || []).find((t) => String(t.teamId) === String(tb.teamId));
      const players = (tb.playerStats || []).map((p) =>
        `#${p.number} ${p.firstName} ${p.lastName} (${p.position || '?'})`);
      note(`\n**${team?.nameShort ?? tb.teamId}** — ${players.length} players in the box score:`);
      note(players.map((p) => `  - ${p}`).join('\n'));
    }
  } else {
    note(`boxscore ${finalGame.id} -> ${status}`);
  }
} else {
  note('No final game found in the sampled dates.');
}

// --- B. School athletics sites --------------------------------------------
// Every opponent on the 2026 schedule, plus a Big 12 team. Domains are
// guesses; the probe reports which resolve so the real map can be written
// from evidence rather than assumption.
note('\n## B. Opponent roster pages (Sidearm?)\n');
const SITES = [
  ['Pittsburgh', 'https://pittsburghpanthers.com/sports/womens-volleyball/roster'],
  ['Stanford', 'https://gostanford.com/sports/womens-volleyball/roster'],
  ['Wichita State', 'https://goshockers.com/sports/womens-volleyball/roster'],
  ['Creighton', 'https://gocreighton.com/sports/womens-volleyball/roster'],
  ['Lipscomb', 'https://lipscombsports.com/sports/womens-volleyball/roster'],
  ['Florida State', 'https://seminoles.com/sports/womens-volleyball/roster'],
  ['South Dakota State', 'https://gojacks.com/sports/womens-volleyball/roster'],
  ['Tulsa', 'https://tulsahurricane.com/sports/womens-volleyball/roster'],
  ['Iowa State', 'https://cyclones.com/sports/womens-volleyball/roster'],
];

// The exact parser scrape-ku-volleyball.mjs uses for kuathletics.com, so a
// pass here means the existing code works unchanged on that school.
function parseRoster(rosterText) {
  const lines = rosterText.split('\n').map((l) => l.trim());
  const normalizeHeight = (raw) => {
    const m = (raw || '').match(/(\d)\s*'\s*(\d{1,2})?/);
    return m ? `${m[1]}-${m[2] ?? 0}` : '';
  };
  const roster = [];
  for (let i = 0; i < lines.length; i++) {
    if (lines[i] !== 'Jersey Number') continue;
    const number = lines[i + 1] || '';
    const name = lines[i + 2] || '';
    if (!/^\d{1,2}$/.test(number) || !/^[A-Za-z'.-]+( [A-Za-z'.-]+)+$/.test(name)) continue;
    const fields = {};
    for (let j = i + 3; j < lines.length && lines[j] !== 'Jersey Number'; j++) {
      if (['Position', 'Height'].includes(lines[j])) fields[lines[j]] = (lines[j + 1] || '').trim();
    }
    roster.push({
      name, jerseyNumber: number,
      position: (fields.Position || '').trim(),
      height: normalizeHeight(fields.Height),
    });
  }
  return roster;
}

const browser = await chromium.launch();
const context = await browser.newContext({
  userAgent:
    'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36',
  viewport: { width: 1400, height: 2400 },
});

const results = [];
for (const [team, url] of SITES) {
  const page = await context.newPage();
  let status = 'error';
  let roster = [];
  let text = '';
  try {
    const resp = await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 60_000 });
    status = resp ? resp.status() : 'no-response';
    await page.waitForTimeout(6_000);
    for (let i = 0; i < 10; i++) {
      await page.evaluate(() => window.scrollBy(0, 1200));
      await page.waitForTimeout(300);
    }
    text = await page.evaluate(() => (document.body ? document.body.innerText : ''));
    roster = parseRoster(text);
  } catch (e) {
    status = `ERROR ${e.message.split('\n')[0]}`;
  }
  const slug = team.toLowerCase().replace(/[^a-z]+/g, '-');
  if (text) save(`roster-${slug}.txt`, text);
  const withHeight = roster.filter((p) => p.height).length;
  note(`- **${team}** \`${url.replace('https://', '').split('/')[0]}\` -> ${status}, ` +
    `parser found **${roster.length} players** (${withHeight} with height)` +
    (roster.length ? ` — e.g. ${roster[0].name} #${roster[0].jerseyNumber} ` +
      `${roster[0].position} ${roster[0].height || '(no height)'}` : ''));
  results.push({ team, url, status, players: roster.length, withHeight, sample: roster.slice(0, 3) });
  await page.close();
}
save('school-rosters.json', JSON.stringify(results, null, 1));
await browser.close();

const ok = results.filter((r) => r.players >= 8).length;
note(`\n**${ok}/${SITES.length} school sites parsed with the existing KU parser, unchanged.**`);

fs.writeFileSync('probe/SUMMARY.md', summary.join('\n') + '\n');
console.log('\nprobe complete');
