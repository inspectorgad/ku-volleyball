import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { finalMessage, formatHit, kuBlock, kuGameId, live, setMessage } from './match-alerts.mjs';

// The same cases MatchAlertsTest.kt pins for the Android alerts, on the same
// real feed (Kansas-Utah, 2026-10-01: FINAL, three sets Kansas all won, and a
// sets-won field of 2-0), so the iPhone's alerts read exactly as Android's.
const game = JSON.parse(readFileSync(new URL('../app/src/test/resources/ncaa-game-utah-2026-10-01.json', import.meta.url), 'utf8'));
const { info, box } = game;
const kuNames = new Set(['taylor stanley', 'grace nelson', 'reese messer', 'aisha aiono', 'reese ptacek']);

test('sets are counted from the set scores, so the 2-0 glitch reads 3-0', () => {
  const l = live(info, box, kuNames);
  assert.equal(l.opponent, 'Utah');
  assert.deepEqual(l.sets, [[32, 30], [25, 20], [26, 24]]);
  assert.equal(l.kuSets, 3);
  assert.equal(l.oppSets, 0);
  assert.equal(l.final, true);
});

test('the final alert names the score, the sets and the top performers', () => {
  const l = live(info, box, kuNames);
  assert.deepEqual(finalMessage(l, box, kuNames), [
    'Final: Kansas def. Utah 3-0',
    '32-30, 25-20, 26-24\nStanley 13 K (.132) · Nelson 12 D · Messer 36 A · Aiono 3.5 BLK',
  ]);
});

test('a set alert says who took it and where the match stands', () => {
  const partial = structuredClone(info);
  const c = partial.contests[0];
  c.gameState = 'I';
  c.currentPeriod = '3RD';
  c.linescores = [c.linescores[0], c.linescores[1], { period: 3, home: '4', visit: '6' }];
  const l = live(partial, box, kuNames);
  assert.equal(l.final, false);
  assert.equal(l.finished, 2);
  assert.deepEqual(setMessage(l), ['Kansas wins set 2, 25-20', 'Kansas leads 2-0 vs Utah']);
});

test('a lost set, a trailing count and a tie read from Kansas side', () => {
  const partial = structuredClone(info);
  const c = partial.contests[0];
  c.gameState = 'I';
  c.currentPeriod = '2ND';
  // Kansas is home: home 20, visit 25 is a set Utah took.
  c.linescores = [{ period: 1, home: '20', visit: '25' }, { period: 2, home: '3', visit: '1' }];
  assert.deepEqual(setMessage(live(partial, box, kuNames)), ['Utah takes set 1, 25-20', 'Kansas trails 0-1 vs Utah']);
  c.linescores = [{ period: 1, home: '20', visit: '25' }, { period: 2, home: '27', visit: '25' }, { period: 3, home: '0', visit: '1' }];
  assert.deepEqual(setMessage(live(partial, box, kuNames)), ['Kansas wins set 2, 27-25', 'Tied 1-1 vs Utah']);
});

test('crossed box-score blocks swap the sides back', () => {
  // Kansas's players filed under Utah's team id, as happened at Florida State.
  const crossed = structuredClone(box);
  const [kuTeam, utahTeam] = crossed.teamBoxscore;
  const utahId = utahTeam.teamId;
  [kuTeam.teamId, utahTeam.teamId] = [utahTeam.teamId, kuTeam.teamId];
  const swapped = structuredClone(info);
  for (const s of swapped.contests[0].linescores) [s.home, s.visit] = [s.visit, s.home];
  const l = live(swapped, crossed, kuNames);
  assert.deepEqual(l.sets, [[32, 30], [25, 20], [26, 24]]);
  assert.equal(kuBlock(crossed, kuNames).teamId, utahId);
});

test('finds Kansas on a scoreboard', () => {
  const sb = {
    games: [
      { game: { gameID: '1', home: { names: { seo: 'utah' } }, away: { names: { seo: 'byu' } } } },
      { game: { gameID: '6625743', home: { names: { seo: 'kansas' } }, away: { names: { seo: 'byu' } } } },
    ],
  };
  assert.equal(kuGameId(sb), '6625743');
  assert.equal(kuGameId({ games: [] }), null);
});

test('hitting percentages round as Kotlin fixed() does', () => {
  assert.equal(formatHit(13 - 8, 38), '.132'); // Stanley's line in the fixture
  assert.equal(formatHit(1, 16), '.063'); // exactly half a thousandth: up
  assert.equal(formatHit(1, 80), '.013');
  assert.equal(formatHit(-1, 16), '-.063');
  assert.equal(formatHit(0, 9), '.000');
  assert.equal(formatHit(5, 5), '1.000');
  assert.equal(formatHit(-2, 40), '-.050');
});
