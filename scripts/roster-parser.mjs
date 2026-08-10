// Parses a volleyball roster out of the rendered text of a college athletics
// roster page. Shared by the Kansas roster scrape and the opponent scrape, so
// one parser covers both.
//
// Nine schools were probed and they render three distinct shapes. Rather than
// sniff the site, every shape is tried and the one that yields the most players
// wins — a school that redesigns into a different shape keeps working.
//
//   labelled  Sidearm's default (Kansas, Pittsburgh, Creighton, Lipscomb,
//             Florida St., South Dakota St., Iowa St., Tulsa):
//               Jersey Number / 12 / Jane Doe / Position / MB / Height / 6' 3''
//             Tulsa writes the labels in caps, hence the case-insensitive match.
//
//   card      unlabelled cards, name in caps (Stanford):
//               1 / OPP / SARAH HICKMAN / 6′5″Senior / Houston, Texas...
//
//   header    position and height share the line above the number (Wichita St.):
//               Middle Blocker 6'0" / 1 / Jane Doe / Sophomore Columbia, Mo.

// Heights appear as 6' 3'', 6'3", or 6′3″ — straight quotes, doubled
// apostrophes, and Unicode primes all mean the same thing.
const HEIGHT = /(\d)\s*['‘’′]\s*(\d{1,2})?\s*(?:''|"|”|″)?/;

export function normalizeHeight(raw) {
  const m = HEIGHT.exec(raw || '');
  return m ? `${m[1]}-${m[2] ?? 0}` : '';
}

const NAME = /^[A-Za-z'.-]+(?: [A-Za-z'.’-]+)+$/;
const isName = (s) => NAME.test((s || '').trim());
const isNumber = (s) => /^\d{1,2}$/.test((s || '').trim());

// Spelled-out positions ("Middle Blocker") and the abbreviations ("MB", "L/DS").
const POSITION = /^(?:[A-Z]{1,3}(?:\/[A-Z]{1,3})?|(?:Outside|Middle|Defensive|Right|Left)\s+\w+|Setter|Libero|Opposite|Pin)$/i;

function titleCase(s) {
  return s.replace(/\S+/g, (w) =>
    w.charAt(0).toUpperCase() + w.slice(1).toLowerCase());
}

/** Sidearm's labelled blocks: scan each player's block for the labels we want. */
function parseLabelled(lines) {
  const roster = [];
  for (let i = 0; i < lines.length; i++) {
    if (lines[i].toLowerCase() !== 'jersey number') continue;
    const number = lines[i + 1] || '';
    const name = lines[i + 2] || '';
    if (!isNumber(number) || !isName(name)) continue;
    const fields = {};
    for (let j = i + 3; j < lines.length && lines[j].toLowerCase() !== 'jersey number'; j++) {
      const label = lines[j].toLowerCase();
      if (label === 'position' || label === 'height') {
        fields[label] = (lines[j + 1] || '').trim();
      }
    }
    roster.push({
      name: name.trim(),
      jerseyNumber: number.trim(),
      position: (fields.position || '').trim(),
      height: normalizeHeight(fields.height),
    });
  }
  return roster;
}

/** Unlabelled cards: number, position, NAME IN CAPS, then height. */
function parseCards(lines) {
  const roster = [];
  for (let i = 0; i + 3 < lines.length; i++) {
    if (!isNumber(lines[i])) continue;
    const position = lines[i + 1];
    const name = lines[i + 2];
    const heightLine = lines[i + 3];
    // The name line is upper-case here, which is what separates a real card
    // from three unrelated lines that happen to sit together.
    if (!POSITION.test(position) || !isName(name) || name !== name.toUpperCase()) continue;
    const height = normalizeHeight(heightLine);
    if (!height) continue;
    roster.push({
      name: titleCase(name),
      jerseyNumber: lines[i].trim(),
      position: position.trim(),
      height,
    });
  }
  return roster;
}

/** Position and height on the line above the number, then the name. */
function parseHeader(lines) {
  const roster = [];
  for (let i = 0; i + 2 < lines.length; i++) {
    const header = lines[i];
    const height = normalizeHeight(header);
    if (!height) continue;
    const position = header.slice(0, HEIGHT.exec(header).index).trim();
    if (!position || !POSITION.test(position)) continue;
    if (!isNumber(lines[i + 1])) continue;
    // A blank line or two can sit between the number and the name.
    const name = [lines[i + 2], lines[i + 3], lines[i + 4]].find(isName);
    if (!name) continue;
    roster.push({
      name: name.trim(),
      jerseyNumber: lines[i + 1].trim(),
      position,
      height,
    });
  }
  return roster;
}

/**
 * Best-effort roster from a page's innerText. Returns [] when nothing parses,
 * which callers treat as "no roster available" rather than an error.
 */
export function parseRoster(pageText) {
  const lines = (pageText || '').split('\n').map((l) => l.trim());
  const shapes = [parseLabelled, parseCards, parseHeader];
  let best = [];
  for (const shape of shapes) {
    let players = [];
    try {
      players = shape(lines);
    } catch {
      players = [];
    }
    // Same player can appear twice when a page repeats the list in two views.
    const seen = new Set();
    players = players.filter((p) => {
      const key = `${p.jerseyNumber}|${p.name.toLowerCase()}`;
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    });
    if (players.length > best.length) best = players;
  }
  return best;
}
