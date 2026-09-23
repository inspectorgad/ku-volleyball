// Runs the schedule parser on a real saved page (scraped/schedule-page.txt,
// the copy the last scrape wrote) and on small hand-written rows, so a change
// that loses fixtures, times or channels shows up here instead of as a quieter
// schedule in the app. Run with:  node --test scripts/
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { parseSchedule, to24h } from './schedule-parser.mjs';

const stripRank = (s) => (s || '').replace(/^#\d+\s+/, '').trim();

test('times read in 24-hour form, and nothing else counts as one', () => {
  assert.equal(to24h('6 p.m. CT'), '18:00');
  assert.equal(to24h('12 p.m. CT'), '12:00');
  assert.equal(to24h('12:30 p.m. CT'), '12:30');
  assert.equal(to24h('11 a.m. CT'), '11:00');
  assert.equal(to24h('12 a.m.'), '00:00');
  assert.equal(to24h('Live Stats'), null);
  assert.equal(to24h('(Fri)'), null);
});

test('a row carries its time and channel', () => {
  const row = ['vs', 'Houston', '', 'Horejsi Family Volleyball Arena', 'Lawrence, Kan.', '',
    'TV: ESPN+', '', 'Sep 25', '(Fri)', '', '6 p.m. CT', '', 'Live Stats'].join('\n');
  const [u] = parseSchedule(`2026 Women's Volleyball Schedule\n${row}`, { stripRank });
  assert.equal(u.date, '2026-09-25');
  assert.equal(u.time, '18:00');
  assert.equal(u.tv, 'ESPN+');
  assert.equal(u.venue, 'Horejsi Family Volleyball Arena');
});

test('a row with no time yet gets none, not the next row\'s', () => {
  const rows = [
    'at', 'Arizona State', '', 'Tempe, Ariz.', '', 'TV: ESPN+', '', 'Nov 27', '(Fri)', '', 'Live Stats',
    'vs', 'Kansas State', '', 'Lawrence, Kan.', '', 'Dec 1', '(Tue)', '', '7 p.m. CT',
  ].join('\n');
  const got = parseSchedule(`2026 Women's Volleyball Schedule\n${rows}`, { stripRank });
  assert.equal(got.find((u) => u.opponent === 'Arizona State').time, null);
  assert.equal(got.find((u) => u.opponent === 'Kansas State').time, '19:00');
  assert.equal(got.find((u) => u.opponent === 'Kansas State').tv, null);
});

test('the saved schedule page yields every fixture, with times and channels', () => {
  const page = fs.readFileSync(new URL('../scraped/schedule-page.txt', import.meta.url), 'utf8');
  const got = parseSchedule(page, { stripRank });
  // As of the 23 Sep capture: eighteen fixtures left, sixteen with a set time,
  // all eighteen with a broadcast. If the page's layout moves, these drop.
  assert.equal(got.length, 18);
  assert.equal(got.filter((u) => u.time).length, 16);
  assert.equal(got.filter((u) => u.tv).length, 18);
  const houston = got.find((u) => u.opponent === 'Houston');
  assert.deepEqual([houston.date, houston.time, houston.tv, houston.home], ['2026-09-25', '18:00', 'ESPN+', true]);
  assert.equal(got.find((u) => u.date === '2026-10-30').tv, 'FS1');
});
