import { test } from 'node:test';
import assert from 'node:assert/strict';
import { centralToUtc, centralDate, plan, kuGame, PLAN_WINDOW } from './match-night-watch.mjs';

const iso = (t) => new Date(t).toISOString();

test('Central wall time converts either side of the clock change', () => {
  assert.equal(iso(centralToUtc('2026-09-25', '18:00')), '2026-09-25T23:00:00.000Z'); // CDT
  assert.equal(iso(centralToUtc('2026-10-30', '19:00')), '2026-10-31T00:00:00.000Z'); // CDT
  assert.equal(iso(centralToUtc('2026-11-01', '14:00')), '2026-11-01T20:00:00.000Z'); // CST
  assert.equal(iso(centralToUtc('2026-11-20', '18:00')), '2026-11-21T00:00:00.000Z'); // CST
});

test('the date is Central, not UTC', () => {
  // 01:30 UTC on the 26th is still the evening of the 25th in Lawrence.
  assert.equal(centralDate(Date.parse('2026-09-26T01:30:00Z')), '2026-09-25');
});

const matches = [
  { date: '2026-09-20', opponent: 'Grand Canyon', teamSets: 3, opponentSets: 0 },
  { date: '2026-09-25', opponent: 'Houston', time: '18:00' },
  { date: '2026-11-25', opponent: 'Arizona' },
];

test('plans a watch only within the window on match day', () => {
  // The 16:37 CDT scrape: first serve 83 minutes out.
  const p = plan(matches, Date.parse('2026-09-25T21:37:00Z'));
  assert.equal(p.watch, true);
  assert.equal(p.date, '2026-09-25');
  // The 08:37 CDT scrape: over nine hours out, so a later scrape starts it.
  assert.equal(plan(matches, Date.parse('2026-09-25T13:37:00Z')).watch, false);
  // A late-delivered cron after first serve still starts one.
  assert.equal(plan(matches, Date.parse('2026-09-26T00:10:00Z')).watch, true);
  // No match that day.
  assert.equal(plan(matches, Date.parse('2026-09-24T21:37:00Z')).watch, false);
  // Already played.
  assert.equal(plan(matches, Date.parse('2026-09-20T20:00:00Z')).watch, false);
  assert.ok(PLAN_WINDOW > 4 * 3600_000, 'window must cover the four-hour cron spacing');
});

test('a match with no time is assumed to start at 6 p.m.', () => {
  assert.equal(plan(matches, Date.parse('2026-11-25T21:00:00Z')).watch, true);
  assert.equal(plan(matches, Date.parse('2026-11-25T15:00:00Z')).watch, false);
});

test('finds the KU game on a scoreboard', () => {
  const board = { games: [
    { game: { gameState: 'final', home: { names: { seo: 'utah' } }, away: { names: { seo: 'byu' } } } },
    { game: { gameState: 'live', home: { names: { seo: 'kansas' } }, away: { names: { seo: 'houston' } } } },
  ] };
  assert.equal(kuGame(board).gameState, 'live');
  assert.equal(kuGame({ games: [] }), null);
});
