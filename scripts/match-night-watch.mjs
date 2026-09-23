// Same-night results: wait for KU's match to go final, then run the scrape.
//
// The scrape runs every four hours, and the overnight entries are the ones
// GitHub's scheduler delivers late - hours late, some nights - so a 7 p.m.
// match used to land the next morning. More cron slots would not fix that;
// they would be late too. This watches the one match instead.
//
//   node scripts/match-night-watch.mjs plan
//       Run by each daytime scrape. Writes watch=true and date=YYYY-MM-DD to
//       $GITHUB_OUTPUT when KU's next match starts within the window, so the
//       scrape can dispatch match-night.yml.
//   node scripts/match-night-watch.mjs watch YYYY-MM-DD
//       Run by match-night.yml. Sleeps to first serve, polls the NCAA
//       scoreboard until KU's game is final, then dispatches scrape-data.yml.

import { readFileSync, appendFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';

const API = 'https://ncaa-api.henrygd.me';
const TEAM_SEO = 'kansas';
const ZONE = 'America/Chicago'; // the athletics schedule lists every time in Central

const MIN = 60_000;
const HOUR = 60 * MIN;
// A scrape dispatches the watcher when first serve is at most this far off.
// A little over the four-hour cron spacing, so one daytime scrape always
// falls inside it; a second one inside it is harmless (see skip rules below).
export const PLAN_WINDOW = 4.5 * HOUR;
// A best-of-five rarely finishes inside 75 minutes, so polling starts then.
const FIRST_POLL_AFTER = 75 * MIN;
const POLL_EVERY = 5 * MIN;
// Give up this long after first serve: a postponed match, or a scoreboard that
// never flips. The normal scrape still catches it later.
const GIVE_UP_AFTER = 4.5 * HOUR;
// The box score can trail the final whistle by a few minutes.
const SETTLE = 8 * MIN;
// GitHub stops a job at six hours. A watcher dispatched early in the window
// and a long five-setter can together need more, so a watcher that reaches
// this budget hands over to a fresh run of itself rather than being killed.
const RUN_BUDGET = 340 * MIN;
// With no published time, assume an evening start for the plan and the watch.
const DEFAULT_TIME = '18:00';

/** The UTC instant of a Central wall-clock time, DST included. */
export function centralToUtc(date, time) {
  const [y, mo, d] = date.split('-').map(Number);
  const [h, mi] = time.split(':').map(Number);
  const wall = Date.UTC(y, mo - 1, d, h, mi);
  // Read the zone's offset at that moment and correct for it; a second pass
  // settles the rare case where the first guess lands across a clock change.
  let t = wall + 6 * HOUR;
  for (let i = 0; i < 2; i++) t = wall - offsetMs(t);
  return t;
}

function offsetMs(t) {
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat('en-US', {
      timeZone: ZONE, hourCycle: 'h23',
      year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
    }).formatToParts(new Date(t)).map((p) => [p.type, p.value]),
  );
  const asUtc = Date.UTC(+parts.year, +parts.month - 1, +parts.day, +parts.hour, +parts.minute);
  return asUtc - Math.floor(t / MIN) * MIN;
}

/** Today's date in Central, as the schedule dates are. */
export function centralDate(t) {
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat('en-US', { timeZone: ZONE, year: 'numeric', month: '2-digit', day: '2-digit' })
      .formatToParts(new Date(t)).map((p) => [p.type, p.value]),
  );
  return `${parts.year}-${parts.month}-${parts.day}`;
}

const played = (m) => m.teamSets != null && m.opponentSets != null;

/**
 * Whether a scrape at `now` should start a watcher: KU plays today (Central),
 * the match has no result yet, and first serve is within the window. A match
 * already under way still counts - a late cron delivery should not lose it.
 */
export function plan(matches, now) {
  const today = centralDate(now);
  const match = matches.find((m) => m.date === today && !played(m));
  if (!match) return { watch: false, reason: `no unplayed KU match on ${today}` };
  const start = centralToUtc(today, match.time || DEFAULT_TIME);
  if (start - now > PLAN_WINDOW) {
    return { watch: false, reason: `first serve ${new Date(start).toISOString()} is more than ${PLAN_WINDOW / HOUR}h away` };
  }
  if (now - start > GIVE_UP_AFTER) return { watch: false, reason: 'match started too long ago to watch' };
  return { watch: true, date: today, match, start };
}

/** KU's game on a scoreboard response, or null. */
export function kuGame(scoreboard) {
  for (const wrap of scoreboard?.games ?? []) {
    const g = wrap.game || wrap;
    if ([g.home, g.away].some((s) => s?.names?.seo === TEAM_SEO)) return g;
  }
  return null;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, Math.max(0, ms)));
const stamp = () => new Date().toISOString().slice(11, 16) + 'Z';

function loadMatches() {
  return JSON.parse(readFileSync('app/src/main/assets/seed.json', 'utf8')).matches ?? [];
}

async function watch(date) {
  const budgetEnd = Date.now() + RUN_BUDGET;
  const match = loadMatches().find((m) => m.date === date);
  if (!match) return console.log(`no KU match on ${date}; nothing to watch`);
  if (played(match)) return console.log(`${date} vs ${match.opponent} already has a result; nothing to do`);
  const start = centralToUtc(date, match.time || DEFAULT_TIME);
  const firstPoll = start + FIRST_POLL_AFTER;
  const deadline = start + GIVE_UP_AFTER;
  console.log(`watching ${match.opponent} on ${date}: first serve ${new Date(start).toISOString()}` +
    (match.time ? '' : ' (no time published; assumed 6 p.m. CT)'));
  await sleep(Math.min(firstPoll, budgetEnd) - Date.now());

  const [y, mo, d] = date.split('-');
  while (Date.now() < deadline) {
    if (Date.now() >= budgetEnd) return handOver(date);
    try {
      const resp = await fetch(`${API}/scoreboard/volleyball-women/d1/${y}/${mo}/${d}`,
        { headers: { accept: 'application/json' } });
      if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
      const g = kuGame(await resp.json());
      const state = g?.gameState ?? 'not on the scoreboard';
      console.log(`${stamp()} ${state}` + (g ? ` · ${g.away?.names?.short} ${g.away?.score ?? ''} at ${g.home?.names?.short} ${g.home?.score ?? ''}` : ''));
      if (g?.gameState === 'final') {
        await sleep(SETTLE);
        dispatchScrape();
        return;
      }
    } catch (e) {
      console.log(`${stamp()} scoreboard: ${e.message}`);
    }
    await sleep(POLL_EVERY);
  }
  console.log('gave up waiting; the scheduled scrape will pick the result up');
}

function handOver(date) {
  console.log('run budget spent before the final; handing over to a fresh watcher');
  // Queues behind this run in the per-date concurrency group, then starts.
  execFileSync('gh', ['workflow', 'run', 'match-night.yml', '--repo', process.env.GITHUB_REPOSITORY,
    '--ref', 'main', '-f', `date=${date}`], { stdio: 'inherit' });
}

function dispatchScrape() {
  const repo = process.env.GITHUB_REPOSITORY;
  console.log(`final; dispatching scrape-data.yml on ${repo}`);
  execFileSync('gh', ['workflow', 'run', 'scrape-data.yml', '--repo', repo, '--ref', 'main'], { stdio: 'inherit' });
}

async function main() {
  const [mode, arg] = process.argv.slice(2);
  if (mode === 'plan') {
    const p = plan(loadMatches(), Date.now());
    console.log(p.watch ? `match night: ${p.match.opponent} at ${new Date(p.start).toISOString()}` : `no watch: ${p.reason}`);
    if (process.env.GITHUB_OUTPUT) {
      appendFileSync(process.env.GITHUB_OUTPUT, `watch=${p.watch}\n` + (p.watch ? `date=${p.date}\n` : ''));
    }
  } else if (mode === 'watch' && /^\d{4}-\d{2}-\d{2}$/.test(arg ?? '')) {
    await watch(arg);
  } else {
    console.error('usage: match-night-watch.mjs plan | watch YYYY-MM-DD');
    process.exit(2);
  }
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) await main();
