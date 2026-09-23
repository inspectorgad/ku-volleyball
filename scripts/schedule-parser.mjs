// Reads kuathletics.com's schedule page (as rendered text, one line per line)
// into fixtures. Lives apart from the scraper so it can be tested against a
// saved page - scripts/schedule-parser.test.mjs runs it on
// scraped/schedule-page.txt - because the only other way to find out a change
// broke it is a scrape that quietly finds fewer matches.
//
// stripRank and postalCity are passed in rather than imported: they belong to
// the scraper, which uses them on every source, not only this one.

const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July',
  'August', 'September', 'October', 'November', 'December'];

// "6 p.m. CT", "12:30 p.m. CT". A row with no time yet (late-season fixtures
// before the conference fixes them) has none of these, and gets null rather
// than a guessed hour.
const TIME = /^(\d{1,2})(?::(\d{2}))?\s*([ap])\.m\.\s*(CT|ET|MT|PT)?$/i;

/** "6 p.m." -> "18:00", "12:30 p.m." -> "12:30"; null for anything else. */
export function to24h(text) {
  const m = TIME.exec((text || '').trim());
  if (!m) return null;
  let h = Number(m[1]) % 12;
  if (m[3].toLowerCase() === 'p') h += 12;
  return `${String(h).padStart(2, '0')}:${m[2] ?? '00'}`;
}

export function parseSchedule(schedText, { stripRank = (s) => s, postalCity = (s) => s } = {}) {
  const upcoming = [];

  // Source 1 - the site-wide scoreboard rotator's accessibility labels:
  // "Upcoming Event: Women's Volleyball versus X on August 22, 2026 at 1 p.m. CT"
  // Authoritative (it states the year outright) but it only labels the next few
  // weeks, which is why this alone showed three weeks of a four-month season.
  const re = /Upcoming Event: Women's Volleyball (versus|at) (.+?) on ([A-Z][a-z]+) (\d{1,2}), (\d{4})(?: at ([^\n]+?))?\s*$/gm;
  for (const m of schedText.matchAll(re)) {
    const month = MONTHS.indexOf(m[3]) + 1;
    if (month === 0) continue;
    const date = `${m[5]}-${String(month).padStart(2, '0')}-${String(m[4]).padStart(2, '0')}`;
    upcoming.push({ date, opponent: stripRank(m[2]), home: m[1] === 'versus',
      time: to24h(m[6]) });
  }

  // Source 2 - the schedule table's own rows, which cover the whole season.
  // Each row puts the side and opponent BEFORE its date, and the time after:
  //     at / Texas Tech / United Supermarkets Arena / Lubbock, Texas /
  //     TV: ESPN+ / Sep 27 / (Sun) / 1 p.m. CT / Live Stats
  // "vs" or "at" gives home or away directly. The date carries no year, so it
  // comes from the page title ("2026 Women's Volleyball Schedule"): a season
  // runs August to December, so a January-to-July month belongs to the year
  // after the one named.
  const seasonYear = Number(/\b(20\d{2})\b/.exec(schedText)?.[1]) || new Date().getUTCFullYear();
  const SHORT = /^(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) (\d{1,2})$/;
  // "Lawrence, Kan." / "Lubbock, Texas" - a place, then a state, and nothing else.
  const CITY = /^[A-Z][A-Za-z.'\- ]*, ?[A-Z][A-Za-z.]*\.?$/;
  const lines = schedText.split('\n').map((l) => l.trim());
  for (let i = 0; i < lines.length; i++) {
    if (lines[i] !== 'vs' && lines[i] !== 'at') continue;
    let j = i + 1;
    while (j < lines.length && !lines[j]) j++;
    const opponent = stripRank(lines[j]);
    if (!opponent) continue;
    // The date follows within the venue/TV block; a bounded look-ahead keeps a
    // row without one from adopting the next row's date.
    let found = null;
    let dateAt = -1;
    for (let k = j + 1; k < Math.min(j + 14, lines.length); k++) {
      const m = SHORT.exec(lines[k]);
      if (m) { found = m; dateAt = k; break; }
    }
    if (!found) continue;
    const month = MONTHS.findIndex((name) => name.startsWith(found[1])) + 1;
    if (month === 0) continue;
    const year = month >= 8 ? seasonYear : seasonYear + 1;
    // Between the opponent and the date sits the location: an optional venue
    // name, then the city, then an optional broadcast note. Conference road
    // games name the arena; the early-season events often give only the city.
    // The broadcast note used to be skipped here and thrown away; it is kept.
    const between = [];
    let tv = null;
    for (let k = j + 1; k < dateAt; k++) {
      const line = lines[k];
      if (!line) continue;
      if (line.startsWith('TV:')) tv = line.slice(3).trim() || null;
      else between.push(line);
    }
    // The time sits just after the date and its weekday: "Sep 25 / (Fri) / 6 p.m. CT".
    // Looked for in the next few lines only, so a row with no time yet cannot
    // borrow one from the row below.
    let time = null;
    for (let k = dateAt + 1; k < Math.min(dateAt + 5, lines.length); k++) {
      if (SHORT.test(lines[k]) || lines[k] === 'vs' || lines[k] === 'at') break;
      const t = to24h(lines[k]);
      if (t) { time = t; break; }
    }
    const cityAt = between.findIndex((line) => CITY.test(line));
    upcoming.push({
      date: `${year}-${String(month).padStart(2, '0')}-${String(found[2]).padStart(2, '0')}`,
      opponent,
      home: lines[i] === 'vs',
      // Postal state codes, to match the city form the NCAA box scores use for
      // played matches - the seed decides home/neutral by comparing the two.
      city: cityAt >= 0 ? postalCity(between[cityAt]) : null,
      venue: cityAt > 0 ? between[cityAt - 1] : null,
      time,
      tv,
    });
  }
  // De-dup (the rotator repeats on every page view). The two sources describe
  // the same fixture differently: the rotator states the year outright but names
  // no location, the table gives a location but has to infer the year. So the
  // first sighting sets the date and the side, and either may fill in the rest.
  const byKey = new Map();
  for (const u of upcoming) {
    const k = `${u.date}|${u.opponent}`;
    const prev = byKey.get(k);
    if (!prev) { byKey.set(k, u); continue; }
    for (const f of ['city', 'venue', 'time', 'tv']) if (!prev[f] && u[f]) prev[f] = u[f];
  }
  return [...byKey.values()];
}
