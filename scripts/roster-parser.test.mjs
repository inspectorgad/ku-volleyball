// Fixtures for every roster shape the parser knows, so a change made for one
// school cannot quietly break another. Run with:  node --test scripts/
//
// Each fixture is the page text as the scraper sees it — innerText, one line
// per rendered line — trimmed down to a couple of players. The real pages that
// prompted the last two shapes are kept in scraped/roster-miss-*.txt.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseRoster, normalizeHeight } from './roster-parser.mjs';

const LABELLED = `
Jersey Number
12
Jane Doe
Position
MB
Height
6' 3''
Jersey Number
7
Mary Smith
Position
Outside Hitter
Height
5'10"
`;

const CARD = `
1
OPP
SARAH HICKMAN
6′5″Senior
Houston, Texas
2
S
ANNA JONES
5′9″Junior
Dallas, Texas
`;

const HEADER = `
Middle Blocker 6'0"
1
Jane Doe
Sophomore Columbia, Mo.
Setter 5'8"
2
Mary Smith
Junior Wichita, Kan.
`;

const TABLE = `
5
Jane Doe
MB\t6-3\tJr.\tAustin, Texas
9
Mary Smith
S\t5-9\tSo.\tDenver, Colorado
`;

// Texas Tech: the whole player on one tab-separated line.
const ROW = `
0\tFaith Jordan\tMB\t5' 11''\tFr.\tJoliet, Illinois / West Joliet HS
1\tTaylor Bahnub\tOH/RS\t6' 2''\tJr.\tDublin, Ohio / Dublin Jerome HS
21\tEmily Contreras\tL\t5' 5''\tSr.\tAustin, Texas / Lake Travis HS
`;

// Arizona St.: name above the number, repeated below as image alt text, and a
// hometown that reads like a name.
const CARD_NAME_ABOVE = `
Una Vajagić
#1
6′0″Junior
OH
Novi Sad, Serbia
Una Vajagić
Instagram
Opens in a new window
Jillian Neal
#14
6′2″Senior
OH
San Diego
Jillian Neal
Instagram
Opens in a new window
`;

test('labelled blocks', () => {
  const r = parseRoster(LABELLED);
  assert.equal(r.length, 2);
  assert.deepEqual(r[0], { name: 'Jane Doe', jerseyNumber: '12', position: 'MB', height: '6-3' });
  assert.equal(r[1].height, '5-10');
  assert.equal(r[1].position, 'Outside Hitter');
});

test('unlabelled cards, name below the number', () => {
  const r = parseRoster(CARD);
  assert.equal(r.length, 2);
  // Caps are folded back to title case.
  assert.deepEqual(r[0], { name: 'Sarah Hickman', jerseyNumber: '1', position: 'OPP', height: '6-5' });
});

test('position and height above the number', () => {
  const r = parseRoster(HEADER);
  assert.equal(r.length, 2);
  assert.deepEqual(r[0], { name: 'Jane Doe', jerseyNumber: '1', position: 'Middle Blocker', height: '6-0' });
});

test('table with the number and name on their own lines', () => {
  const r = parseRoster(TABLE);
  assert.equal(r.length, 2);
  assert.deepEqual(r[0], { name: 'Jane Doe', jerseyNumber: '5', position: 'MB', height: '6-3' });
});

test('one player per tab-separated line', () => {
  const r = parseRoster(ROW);
  assert.equal(r.length, 3);
  assert.deepEqual(r[0], { name: 'Faith Jordan', jerseyNumber: '0', position: 'MB', height: '5-11' });
  assert.equal(r[1].position, 'OH/RS');
  assert.equal(r[2].height, '5-5');
});

test('name above the number wins over a hometown that reads like one', () => {
  const r = parseRoster(CARD_NAME_ABOVE);
  assert.equal(r.length, 2);
  assert.equal(r[0].name, 'Una Vajagić');
  // The bug this guards: "San Diego" is where she is from, not who she is.
  assert.equal(r[1].name, 'Jillian Neal');
  assert.equal(r[1].jerseyNumber, '14');
});

test('accented names are names', () => {
  const r = parseRoster(CARD_NAME_ABOVE);
  assert.ok(r.some((p) => p.name === 'Una Vajagić'), 'Vajagić should parse');
});

test('heights in every notation a school writes', () => {
  assert.equal(normalizeHeight("6' 3''"), '6-3');
  assert.equal(normalizeHeight('6′5″Senior'), '6-5');
  assert.equal(normalizeHeight('5\'10"'), '5-10');
  assert.equal(normalizeHeight('6-4'), '6-4');
  // A season label is not a height.
  assert.equal(normalizeHeight('2025-26'), '');
});

test('a page with no roster yields nothing rather than guesses', () => {
  assert.deepEqual(parseRoster('Home\nTickets\nShop\nNews\n'), []);
  assert.deepEqual(parseRoster(''), []);
});
