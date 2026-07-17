// One-off data-source probe for the KU Women's Volleyball app feasibility
// check. Runs in GitHub Actions (open outbound network). Hits the two
// candidate sources and dumps raw evidence under probe/ so we can confirm
// correct game + player stats are retrievable before building anything.
import { chromium } from 'playwright';
import fs from 'fs';

fs.rmSync('probe', { recursive: true, force: true });
fs.mkdirSync('probe', { recursive: true });

const summary = [];
function note(line) {
  summary.push(line);
  console.log(line);
}

function save(name, data) {
  fs.writeFileSync(`probe/${name}`, data);
  note(`saved probe/${name} (${Buffer.byteLength(data)} bytes)`);
}

// --- 1. NCAA API (ncaa-api.henrygd.me, JSON wrapper around ncaa.com) -------
// Known 2025 match: Kansas at Wisconsin, Aug 29 2025, ncaa.com game 6480027.
const ncaaTargets = [
  // scoreboard: try the documented date formats to learn which one works
  ['ncaa-scoreboard-slash.json', 'https://ncaa-api.henrygd.me/scoreboard/volleyball-women/d1/2025/08/29'],
  ['ncaa-scoreboard-dash.json', 'https://ncaa-api.henrygd.me/scoreboard/volleyball-women/d1/2025-08-29'],
  ['ncaa-game-6480027.json', 'https://ncaa-api.henrygd.me/game/6480027'],
  ['ncaa-game-6480027-boxscore.json', 'https://ncaa-api.henrygd.me/game/6480027/boxscore'],
  ['ncaa-game-6480027-pbp.json', 'https://ncaa-api.henrygd.me/game/6480027/play-by-play'],
];

for (const [name, url] of ncaaTargets) {
  try {
    const resp = await fetch(url, { headers: { accept: 'application/json' } });
    const body = await resp.text();
    note(`GET ${url} -> ${resp.status} (${body.length} chars)`);
    save(name, body.slice(0, 500_000));
  } catch (e) {
    note(`GET ${url} -> ERROR ${e.message}`);
  }
  // hosted instance is rate-limited to 5 req/s; be polite anyway
  await new Promise((r) => setTimeout(r, 1500));
}

// --- 2. kuathletics.com (Sidearm Sports) via real headless browser ---------
const browser = await chromium.launch();
const context = await browser.newContext({
  userAgent:
    'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36',
  viewport: { width: 1400, height: 2400 },
});

// Record every JSON response the site makes so we discover Sidearm's
// internal API endpoints (roster/schedule/stats feeds).
const jsonHits = [];
context.on('response', async (resp) => {
  try {
    const ct = resp.headers()['content-type'] || '';
    if (ct.includes('json') && resp.status() === 200) {
      const url = resp.url();
      jsonHits.push(`${resp.status()} ${url}`);
      if (jsonHits.length <= 15) {
        const body = await resp.text();
        const safe = String(jsonHits.length).padStart(2, '0');
        fs.writeFileSync(`probe/sidearm-json-${safe}.json`, `// ${url}\n` + body.slice(0, 200_000));
      }
    }
  } catch {
    /* response bodies can be gone by the time we read them; fine */
  }
});

const kuPages = [
  ['ku-stats-2025', 'https://kuathletics.com/sports/womens-volleyball/stats/2025'],
  ['ku-roster-2025', 'https://kuathletics.com/sports/womens-volleyball/roster/2025'],
  ['ku-schedule-2025', 'https://kuathletics.com/sports/womens-volleyball/schedule/2025'],
];

for (const [name, url] of kuPages) {
  const page = await context.newPage();
  try {
    const resp = await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 60_000 });
    note(`GOTO ${url} -> ${resp ? resp.status() : 'no response'}`);
    await page.waitForTimeout(8_000);
    for (let i = 0; i < 10; i++) {
      await page.evaluate(() => window.scrollBy(0, 1200));
      await page.waitForTimeout(500);
    }
    const text = await page.evaluate(() => (document.body ? document.body.innerText : ''));
    save(`${name}.txt`, text);
    await page.screenshot({ path: `probe/${name}.png`, fullPage: false });
    note(`saved probe/${name}.png`);
  } catch (e) {
    note(`GOTO ${url} -> ERROR ${e.message}`);
  }
  await page.close();
}

await browser.close();

save('sidearm-json-endpoints.txt', jsonHits.join('\n') || '(no JSON responses observed)');
save('summary.txt', summary.join('\n'));
