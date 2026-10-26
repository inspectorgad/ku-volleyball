// Runs the Kansas Athletics box score reader on two saved pages: at Baylor on
// 10/25/2026 (five sets, Heidi Devers on for one of them) and at Kansas St. on
// 10/15/2026 (three sets, three subs on for two). The fixtures are what the
// scrape's page reading returns - the title and every table's rows of cell
// text - so a change to the site's layout that this misreads fails here, not
// as quietly wrong sets played in the app.
// Run with:  node --test scripts/*.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { parseKuBoxScore, readKuBoxScores } from './ku-boxscores.mjs';

const fixture = (id) => JSON.parse(fs.readFileSync(new URL(`./fixtures/ku-boxscore-${id}.json`, import.meta.url), 'utf8'));

// The 2026 roster, as scrape-ku-volleyball.mjs reads it from the roster page.
const KU_NAMES = new Set([
  'grace nelson', 'kenzie dean', 'reese messer', 'cydnee bryant', 'ryan sadler', 'olivia hasbrook',
  'jaeli rutledge', 'reese ptacek', 'cristin cline', 'heidi devers', 'tessa dodd', 'avery poulton',
  'taylor stanley', 'aisha aiono', 'logan bell',
]);

const byName = (box) => new Map(box.players.map((p) => [p.name, p]));

test('at Baylor: the date, the opponent and all five sets', () => {
  const box = parseKuBoxScore(fixture(20599), KU_NAMES);
  assert.equal(box.date, '2026-10-25');
  assert.equal(box.opponent, 'Baylor');
  assert.deepEqual(box.sets, [[23, 25], [22, 25], [25, 21], [25, 21], [15, 13]]);
});

test('at Baylor: Kansas\'s nine players, Devers on for one set', () => {
  const box = parseKuBoxScore(fixture(20599), KU_NAMES);
  assert.equal(box.players.length, 9);
  const p = byName(box);
  assert.equal(p.get('Heidi Devers').sp, 1);
  assert.equal(p.get('Heidi Devers').number, '12');
  // A starter's whole line. The first "E" is attack errors; the second, after
  // assists, is set errors and is not one of these.
  assert.deepEqual(p.get('Reese Ptacek'), {
    name: 'Reese Ptacek', number: '10', sp: 5, k: 13, e: 2, ta: 27, a: 2, sa: 3, se: 3, bs: 1, ba: 3, d: 4, re: 0,
  });
  assert.deepEqual(p.get('Reese Messer'), {
    name: 'Reese Messer', number: '3', sp: 5, k: 4, e: 1, ta: 12, a: 53, sa: 0, se: 0, bs: 0, ba: 0, d: 8, re: 0,
  });
  // Bench players who never came on are not in Kansas Athletics' box score.
  for (const absent of ['Cydnee Bryant', 'Ryan Sadler', 'Tessa Dodd']) assert.equal(p.has(absent), false, absent);
});

test('the opponent\'s table is not taken for Kansas\'s, and Team and Totals rows are not players', () => {
  for (const id of [20599, 20596]) {
    const box = parseKuBoxScore(fixture(id), KU_NAMES);
    const names = box.players.map((p) => p.name);
    assert.ok(names.every((n) => KU_NAMES.has(n.toLowerCase())), `${id}: ${names.join(', ')}`);
    assert.ok(!names.some((n) => /^(team|totals?)$/i.test(n) || n === ''), `${id}: ${names.join(', ')}`);
    assert.ok(!box.players.some((p) => p.number === 'TM'));
  }
  // Baylor's players, and Kansas St.'s, appear nowhere.
  assert.equal(byName(parseKuBoxScore(fixture(20599), KU_NAMES)).has('Ksenia Rakhmanchik'), false);
  assert.equal(byName(parseKuBoxScore(fixture(20596), KU_NAMES)).has('Emerson van Lannen'), false);
});

test('at Kansas St.: three sets, ten players, the subs on for two', () => {
  const box = parseKuBoxScore(fixture(20596), KU_NAMES);
  assert.equal(box.date, '2026-10-15');
  assert.equal(box.opponent, 'Kansas St.');
  assert.deepEqual(box.sets, [[25, 20], [25, 15], [25, 23]]);
  assert.equal(box.players.length, 10);
  const p = byName(box);
  assert.deepEqual(['Cristin Cline', 'Tessa Dodd', 'Avery Poulton'].map((n) => p.get(n).sp), [2, 2, 2]);
  assert.equal(p.get('Cristin Cline').a, 2);
  assert.deepEqual(p.get('Taylor Stanley'), {
    name: 'Taylor Stanley', number: '23', sp: 3, k: 16, e: 7, ta: 29, a: 0, sa: 2, se: 5, bs: 0, ba: 1, d: 7, re: 0,
  });
  for (const absent of ['Heidi Devers', 'Kenzie Dean', 'Cydnee Bryant', 'Ryan Sadler']) assert.equal(p.has(absent), false, absent);
});

test('the other team\'s players come too, with their own sets played', () => {
  const baylor = byName({ players: parseKuBoxScore(fixture(20599), KU_NAMES).opponentPlayers });
  assert.equal(baylor.size, 12);
  assert.deepEqual(['Nadiya Johnson', 'Lacy Tinnell', 'Grace Carroll', 'Mya Hemmer'].map((n) => baylor.get(n).sp), [4, 3, 2, 2]);
  assert.deepEqual(baylor.get('Ksenia Rakhmanchik'), {
    name: 'Ksenia Rakhmanchik', number: '6', sp: 5, k: 25, e: 7, ta: 65, a: 0, sa: 2, se: 4, bs: 0, ba: 0, d: 10, re: 1,
  });
  assert.ok(![...baylor.keys()].some((n) => KU_NAMES.has(n.toLowerCase()) || /^(team|totals?)$/i.test(n)));
  const kstate = parseKuBoxScore(fixture(20596), KU_NAMES).opponentPlayers;
  assert.equal(kstate.length, 12);
  assert.equal(byName({ players: kstate }).get('Emerson van Lannen').sp, 3);
});

test('a set column is a set only when both cells are whole numbers, and never 0-0', () => {
  const page = fixture(20596);
  const [head, ku, opp] = page.tables[0];
  const lineScore = [[...head, '4', '5'], [...ku, '-', '0'], [...opp, '-', '0']];
  const box = parseKuBoxScore({ ...page, tables: [lineScore, ...page.tables.slice(1)] }, KU_NAMES);
  assert.deepEqual(box.sets, [[25, 20], [25, 15], [25, 23]]);
  const odd = [head, [ku[0], '25', 'x', '25'], [opp[0], '20', '15', '23']];
  assert.deepEqual(parseKuBoxScore({ ...page, tables: [odd, ...page.tables.slice(1)] }, KU_NAMES).sets, [[25, 20], [25, 23]]);
});

test('Kansas\'s table is found by names wherever it sits on the page', () => {
  const page = fixture(20596);
  const swapped = { ...page, tables: [...page.tables.slice(0, 4), page.tables[5], page.tables[4]] };
  assert.deepEqual(parseKuBoxScore(swapped, KU_NAMES), parseKuBoxScore(page, KU_NAMES));
});

test('a page that is not a box score, or whose sides cannot be told apart, gives nothing', () => {
  assert.equal(parseKuBoxScore({ title: 'Women\'s Volleyball Statistics - University of Kansas', tables: [] }, KU_NAMES), null);
  assert.equal(parseKuBoxScore({ ...fixture(20599), tables: [] }, KU_NAMES), null);
  // No Kansas names to go on: no guessing which table is whose.
  assert.equal(parseKuBoxScore(fixture(20599), new Set()), null);
});

// A stand-in for Playwright's browser context that serves saved pages, so the
// reading loop - which pages it asks for, and when it asks again - can be run
// without a network. A page that is missing fails to load, and one given as
// HANG never finishes loading.
const HANG = Symbol('hang');
function fakeContext(pages) {
  const asked = [];
  const ctx = {
    asked,
    closed: 0,
    newPage: async () => {
      let url = null;
      return {
        goto: async (u) => { url = u; asked.push(u); },
        waitForTimeout: async () => {},
        evaluate: async () => {
          if (pages[url] === HANG) return new Promise(() => {});
          if (!pages[url]) throw new Error(`no page for ${url}`);
          return { links: [], ...pages[url] };
        },
        close: async () => { ctx.closed++; },
      };
    },
  };
  return ctx;
}

const STATS = 'https://kuathletics.com/sports/womens-volleyball/stats/2026';
const BAYLOR = `${STATS}/baylor/boxscore/20599`;
const KSTATE = `${STATS}/kansas-st-/boxscore/20596`;
const PAGES = {
  // The cumulative page: the season's player table (where the Kansas names
  // come from) and a link to every match's box score, some of them twice.
  [STATS]: {
    title: 'Women\'s Volleyball Statistics - University of Kansas',
    tables: [[
      ['#', 'Player', 'SP', 'MP', 'K', 'TA', 'SA'],
      ['24', 'Aiono, Aisha', '60', '18', '150', '400', '10'],
      ['1', 'Nelson, Grace', '60', '18', '200', '500', '12'],
      ['23', 'Stanley, Taylor', '60', '18', '210', '520', '15'],
      ['', 'Total', '60', '18', '900', '2400', '90'],
    ]],
    links: [BAYLOR, `${BAYLOR}#top`, KSTATE, `${STATS}/baylor/recap/1`, 'https://kuathletics.com/sports/womens-volleyball/schedule'],
  },
  [BAYLOR]: fixture(20599),
  [KSTATE]: fixture(20596),
};

test('every listed box score is read once, with Kansas told apart by the season\'s own names', async () => {
  const context = fakeContext(PAGES);
  const cache = await readKuBoxScores(context, '2026', {}, { log: () => {} });
  assert.deepEqual(Object.keys(cache).sort(), ['20596', '20599']);
  assert.equal(cache['20599'].source, BAYLOR);
  assert.equal(cache['20599'].players.find((p) => p.name === 'Heidi Devers').sp, 1);
  assert.ok(cache['20596'].fetchedAt);
  assert.deepEqual(context.asked, [STATS, BAYLOR, KSTATE]);
});

test('a box score is read again until a copy from three days after the match is held', async () => {
  const context = fakeContext(PAGES);
  const held = {
    // Read four days after the match: final.
    20599: { ...parseKuBoxScore(fixture(20599), KU_NAMES), fetchedAt: '2026-10-29T09:37:00.000Z' },
    // Read the morning after: a correction could still come.
    20596: { ...parseKuBoxScore(fixture(20596), KU_NAMES), fetchedAt: '2026-10-16T05:37:00.000Z' },
  };
  const cache = await readKuBoxScores(context, '2026', held, { log: () => {} });
  assert.deepEqual(context.asked, [STATS, KSTATE]);
  assert.equal(cache['20599'].fetchedAt, '2026-10-29T09:37:00.000Z');
  assert.notEqual(cache['20596'].fetchedAt, '2026-10-16T05:37:00.000Z');
});

test('the reads per run are capped, and what is left waits for the next run', async () => {
  const context = fakeContext(PAGES);
  const budget = { left: 1 };
  const lines = [];
  const cache = await readKuBoxScores(context, '2026', {}, { budget, log: (s) => lines.push(s) });
  assert.equal(Object.keys(cache).length, 1);
  assert.equal(budget.left, 0);
  assert.match(lines[0], /1 left for the next run/);
});

test('a box score that fails to load keeps the copy already held', async () => {
  const context = fakeContext({ ...PAGES, [KSTATE]: undefined });
  const held = { 20596: { ...parseKuBoxScore(fixture(20596), KU_NAMES), fetchedAt: '2026-10-16T05:37:00.000Z' } };
  const lines = [];
  const cache = await readKuBoxScores(context, '2026', held, { log: (s) => lines.push(s) });
  assert.equal(cache['20596'].fetchedAt, '2026-10-16T05:37:00.000Z');
  assert.ok(cache['20599']);
  assert.match(lines.join('\n'), /not read: 20596/);
});

// Three more box scores the cumulative page links to, for the tests that need a
// run of pages: the Kansas St. page under other ids, read only for the asking.
const EXTRA = [1, 2, 3].map((n) => `${STATS}/extra-${n}/boxscore/3000${n}`);
const withLinks = (links, more = {}) => ({ ...PAGES, [STATS]: { ...PAGES[STATS], links }, ...more });

test('reading stops when the step runs out of time, checked before every page', async () => {
  // Out of time before the season starts: not even the stats page is opened.
  const context = fakeContext(PAGES);
  const held = { 20596: { ...parseKuBoxScore(fixture(20596), KU_NAMES), fetchedAt: '2026-10-16T05:37:00.000Z' } };
  const lines = [];
  const late = await readKuBoxScores(context, '2026', held, {
    budget: { left: 60, until: 1000 }, now: () => 2000, log: (s) => lines.push(s),
  });
  assert.deepEqual(context.asked, []);
  assert.deepEqual(late, held);
  assert.match(lines[0], /out of time/);

  // Time runs out after the first box score: the rest wait for the next run.
  const later = fakeContext(PAGES);
  let clock = 0;
  const budget = { left: 60, until: 1000 };
  const log = [];
  const cache = await readKuBoxScores(later, '2026', {}, {
    budget, now: () => (later.asked.length >= 2 ? 1001 : clock++), log: (s) => log.push(s),
  });
  assert.deepEqual(later.asked, [STATS, BAYLOR]);
  assert.deepEqual(Object.keys(cache), ['20599']);
  assert.equal(budget.left, 59);
  assert.match(log[0], /1 left for the next run \(out of time\)/);
});

test('a page that hangs is given up on, closed, and the next one read', async () => {
  const context = fakeContext({ ...PAGES, [BAYLOR]: HANG });
  const lines = [];
  const cache = await readKuBoxScores(context, '2026', {}, { pageTimeoutMs: 50, log: (s) => lines.push(s) });
  assert.deepEqual(context.asked, [STATS, BAYLOR, KSTATE]);
  assert.deepEqual(Object.keys(cache), ['20596']);
  assert.equal(context.closed, 3);
  assert.match(lines.join('\n'), /not read: 20599 \(page timed out/);
});

test('a season stops after three failed reads in a row, and only in a row', async () => {
  // Three that fail to load, then two that would have been read.
  const context = fakeContext(withLinks([...EXTRA, BAYLOR, KSTATE]));
  const lines = [];
  const cache = await readKuBoxScores(context, '2026', {}, { log: (s) => lines.push(s) });
  assert.deepEqual(context.asked, [STATS, ...EXTRA]);
  assert.deepEqual(cache, {});
  assert.match(lines[0], /2 left for the next run \(3 failed reads in a row\)/);

  // A good read in between starts the count again.
  const mixed = fakeContext(withLinks([EXTRA[0], EXTRA[1], BAYLOR, EXTRA[2], KSTATE]));
  const all = await readKuBoxScores(mixed, '2026', {}, { log: () => {} });
  assert.deepEqual(mixed.asked, [STATS, EXTRA[0], EXTRA[1], BAYLOR, EXTRA[2], KSTATE]);
  assert.deepEqual(Object.keys(all).sort(), ['20596', '20599']);
});

test('with no Kansas names to go on, nothing is read and the cache is kept', async () => {
  const bare = { title: 'Women\'s Volleyball Statistics - University of Kansas', tables: [], links: [BAYLOR, KSTATE] };
  const context = fakeContext({ ...PAGES, [STATS]: bare });
  const held = { 20599: { ...parseKuBoxScore(fixture(20599), KU_NAMES), fetchedAt: '2026-10-26T05:37:00.000Z' } };
  const budget = { left: 60 };
  const lines = [];
  const cache = await readKuBoxScores(context, '2026', held, { budget, log: (s) => lines.push(s) });
  assert.deepEqual(context.asked, [STATS]);
  assert.deepEqual(cache, held);
  assert.equal(budget.left, 60);
  assert.match(lines[0], /no Kansas names/);
});

test('a page that never reads as a box score is tried once a day after three goes', async () => {
  const notABox = { title: 'Women\'s Volleyball Recap - University of Kansas', tables: [] };
  const pages = withLinks([EXTRA[0], BAYLOR], { [EXTRA[0]]: notABox });
  const day = Date.parse('2026-10-26T12:00:00Z');
  let cache = {};
  for (let run = 1; run <= 3; run++) {
    cache = await readKuBoxScores(fakeContext(pages), '2026', cache, { now: () => day + run * 60_000, log: () => {} });
    assert.equal(cache['30001'].failures, run);
    assert.equal(cache['30001'].source, EXTRA[0]);
  }
  assert.equal(cache['30001'].failedAt, new Date(day + 3 * 60_000).toISOString());
  // Not asked for again the same day...
  const sameDay = fakeContext(pages);
  cache = await readKuBoxScores(sameDay, '2026', cache, { now: () => day + 4 * 3_600_000, log: () => {} });
  assert.ok(!sameDay.asked.includes(EXTRA[0]));
  // ...but a day on it is, in case it has been put right.
  const nextDay = fakeContext({ ...pages, [EXTRA[0]]: fixture(20596) });
  cache = await readKuBoxScores(nextDay, '2026', cache, { now: () => day + 25 * 3_600_000, log: () => {} });
  assert.ok(nextDay.asked.includes(EXTRA[0]));
  assert.equal(cache['30001'].date, '2026-10-15');
  assert.equal(cache['30001'].failures, undefined);
  // The update-seed lookup skips a record with no date or players, so a
  // failure on its own never reaches the seed.
  assert.equal(cache['20599'].failures, undefined);
});

test('a held copy that stops reading as a box score is kept', async () => {
  const notABox = { title: 'Women\'s Volleyball - University of Kansas', tables: [] };
  const held = { 20596: { ...parseKuBoxScore(fixture(20596), KU_NAMES), fetchedAt: '2026-10-16T05:37:00.000Z' } };
  const cache = await readKuBoxScores(fakeContext({ ...PAGES, [KSTATE]: notABox }), '2026', held, { log: () => {} });
  assert.deepEqual(cache['20596'].players, held['20596'].players);
  assert.equal(cache['20596'].fetchedAt, held['20596'].fetchedAt);
  assert.equal(cache['20596'].failures, 1);
});
