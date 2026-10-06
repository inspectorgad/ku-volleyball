// node --test docs/sim.test.mjs
// The fixture and the expected numbers are repeated in the app's
// SeasonSimulatorTest.kt: the two simulators must agree to the last run.
import test from 'node:test';
import assert from 'node:assert/strict';
import { simulate, chance } from './sim.js';

export const FIXTURE = {
  confTotal: 6,
  kansas: { confW: 1, confL: 1, overallW: 5, overallL: 2, rating: 74 },
  remaining: [
    { date: '2026-10-11', opponent: 'UCF', venue: 'A', conference: true, key: 'ucf', p: 0.6 },
    { date: '2026-10-18', opponent: 'TCU', venue: 'H', conference: true, key: 'tcu', p: 0.3 },
    { date: '2026-10-25', opponent: 'Baylor', venue: 'A', conference: true, key: 'baylor', p: 0.5 },
    { date: '2026-11-01', opponent: 'Utah', venue: 'H', conference: true, key: 'utah', p: 0.7 },
  ],
  others: [
    { team: 'TCU', key: 'tcu', confW: 2, confL: 0, remaining: 3, pVsAverage: 0.75 },
    { team: 'UCF', key: 'ucf', confW: 1, confL: 1, remaining: 3, pVsAverage: 0.45 },
    { team: 'Baylor', key: 'baylor', confW: 1, confL: 1, remaining: 3, pVsAverage: 0.5 },
    { team: 'Utah', key: 'utah', confW: 0, confL: 2, remaining: 3, pVsAverage: 0.4 },
  ],
  rpi: { wins: 5, played: 7, owp: 0.6, oowp: 0.55, field: [0.72, 0.7, 0.66, 0.64, 0.6, 0.58], atLargeCutoff: 3 },
};

test('the same inputs give the same numbers every time', () => {
  const a = simulate(FIXTURE, {}, 5000);
  const b = simulate(FIXTURE, {}, 5000);
  assert.deepEqual(a, b);
});

test('pinned results, shared with SeasonSimulatorTest.kt', () => {
  const r = simulate(FIXTURE, {}, 5000);
  assert.equal(r.winsMid, 7);
  assert.equal(r.games, 11);
  assert.equal(r.title, 0.0956);
  assert.equal(r.top4, 0.8908);
  assert.equal(r.shareOfTitle, 0.1586);
  assert.equal(r.expectedWins, 7.0946);
  assert.equal(r.rpiRankMid, 6);
  assert.equal(simulate(FIXTURE, { 1: 'W' }, 5000).title, 0.2848);
});

test('a forced win raises the odds and a forced loss lowers them', () => {
  const base = simulate(FIXTURE, {}, 5000);
  const win = simulate(FIXTURE, { 1: 'W' }, 5000);
  const loss = simulate(FIXTURE, { 1: 'L' }, 5000);
  assert.ok(win.title > base.title && base.title > loss.title);
  assert.ok(win.expectedWins > loss.expectedWins);
  // Winning everything left: every run has KU on 9 overall wins.
  const all = simulate(FIXTURE, { 0: 'W', 1: 'W', 2: 'W', 3: 'W' }, 2000);
  assert.equal(all.winsLow, 9);
  assert.equal(all.winsHigh, 9);
});

test('chances read in words at the ends', () => {
  assert.equal(chance(0.001), 'under 1%');
  assert.equal(chance(0.999), 'over 99%');
  assert.equal(chance(0.374), '37%');
});
