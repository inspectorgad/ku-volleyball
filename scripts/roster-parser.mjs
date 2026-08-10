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
// Some sites drop the quote marks entirely and write 6-4. Anchored, so a year
// range like 2025-26 is not mistaken for a height.
const PLAIN_HEIGHT = /^([4-7])-(\d{1,2})$/;

export function normalizeHeight(raw) {
  const plain = PLAIN_HEIGHT.exec((raw || '').trim());
  if (plain && Number(plain[2]) <= 11) return `${plain[1]}-${plain[2]}`;
  const m = HEIGHT.exec(raw || '');
  return m ? `${m[1]}-${m[2] ?? 0}` : '';
}

const NAME = /^[A-Za-z'.-]+(?: [A-Za-z'.’-]+)+$/;
const isName = (s) => NAME.test((s || '').trim());
// UCF prints "#1" rather than "1"; the hash is decoration, not part of the number.
const isNumber = (s) => /^#?\d{1,2}$/.test((s || '').trim());
const numberOf = (s) => (s || '').trim().replace(/^#/, '');

// Spelled-out positions ("Middle Blocker") and the abbreviations ("MB", "L/DS").
const POSITION = /^(?:[A-Z]{1,3}(?:\/[A-Z]{1,3})?|(?:Outside|Middle|Defensive|Right|Left)\s+\w+|Setter|Libero|Opposite|Pin)$/i;

// Cards often print names in caps. Fold those to title case, but leave a name
// that already carries its own capitalisation alone - McMillan, DeMaria.
function tidyName(s) {
  if (s !== s.toUpperCase()) return s;
  return s.replace(/\S+/g, (w) => w.charAt(0).toUpperCase() + w.slice(1).toLowerCase());
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
      jerseyNumber: numberOf(number),
      position: (fields.position || '').trim(),
      height: normalizeHeight(fields.height),
    });
  }
  return roster;
}

/**
 * Unlabelled cards: a number, then the name, position and height in some order
 * — Stanford lists position first, Cincinnati the name — so the three lines
 * after the number are identified by what they look like rather than by place.
 */
function parseCards(lines) {
  const roster = [];
  for (let i = 0; i + 3 < lines.length; i++) {
    if (!isNumber(lines[i])) continue;
    const window = lines.slice(i + 1, i + 4);
    // Requiring all three - a person's name, a volleyball position and a
    // height, on three distinct lines - is what separates a real card from
    // unrelated lines that happen to sit together. Case is not a reliable
    // signal: Stanford shouts the name, UCF does not.
    const name = window.find(isName);
    const position = window.find((l) => l !== name && POSITION.test(l));
    const height = window.filter((l) => l !== name && l !== position)
      .map(normalizeHeight).find(Boolean);
    if (!name || !position || !height) continue;
    roster.push({
      name: tidyName(name),
      jerseyNumber: numberOf(lines[i]),
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
    // This shape needs the position and the height on one line, so it wants the
    // quoted form specifically — a line holding nothing but a bare "6-4" has no
    // position in front of it to split off.
    const quoted = HEIGHT.exec(header);
    if (!quoted) continue;
    const height = normalizeHeight(header);
    if (!height) continue;
    const position = header.slice(0, quoted.index).trim();
    if (!position || !POSITION.test(position)) continue;
    if (!isNumber(lines[i + 1])) continue;
    // A blank line or two can sit between the number and the name.
    const name = [lines[i + 2], lines[i + 3], lines[i + 4]].find(isName);
    if (!name) continue;
    roster.push({
      name: name.trim(),
      jerseyNumber: numberOf(lines[i + 1]),
      position,
      height,
    });
  }
  return roster;
}

/**
 * A tab-separated table: the number and name on their own lines, then one line
 * carrying position, height, class and hometown. Heights here are already
 * written "6-4", with no quote marks, so they need their own match.
 */
function parseTable(lines) {
  const roster = [];
  for (let i = 0; i + 2 < lines.length; i++) {
    if (!isNumber(lines[i].replace(/\t+$/, ''))) continue;
    const name = lines[i + 1];
    if (!isName(name)) continue;
    const cells = lines[i + 2].split('\t').map((c) => c.trim()).filter(Boolean);
    const heightCell = cells.find((c) => {
      const m = PLAIN_HEIGHT.exec(c);
      return m && Number(m[2]) <= 11;
    });
    if (!heightCell) continue;
    const position = cells[cells.indexOf(heightCell) - 1] ?? '';
    if (!position) continue;
    roster.push({
      name: name.trim(),
      jerseyNumber: numberOf(lines[i].replace(/\t+$/, '')),
      position,
      height: heightCell,
    });
  }
  return roster;
}

// --- Rosters that never reach the page as text -------------------------------
// Texas Tech and Arizona St. serve a page shell and fetch the roster afterwards
// — Texas Tech from an API, Arizona St. into a Nuxt state blob — so there is no
// rendered text to parse and no markup either. Guessing each school's endpoint
// would be a per-site hack that rots; instead the caller hands over whatever
// JSON the page fetched, and a player is recognised by its fields.
//
// Field names vary by platform, so each is matched by a set of aliases with
// punctuation and case ignored (jersey_number, jerseyNumber and Jersey all
// read the same).
const FIELDS = {
  name: ['name', 'fullname', 'playername', 'title'],
  first: ['firstname'],
  last: ['lastname'],
  number: ['jerseynumber', 'jersey', 'uniformnumber', 'uniform', 'number'],
  position: ['positionshort', 'position', 'pos'],
  height: ['height', 'heightformatted'],
};

function pick(obj, aliases) {
  for (const key of Object.keys(obj)) {
    const flat = key.toLowerCase().replace(/[^a-z]/g, '');
    if (aliases.includes(flat) && obj[key] != null && obj[key] !== '') {
      return String(obj[key]);
    }
  }
  return '';
}

/**
 * A player, if this object looks like one. A height is required: it is the
 * field that separates a roster entry from the many other objects an athletics
 * site ships (staff, news, opponents), which carry names but no measurements.
 */
function playerFromObject(o) {
  if (!o || typeof o !== 'object' || Array.isArray(o)) return null;
  const height = normalizeHeight(pick(o, FIELDS.height));
  if (!height) return null;
  const name = pick(o, FIELDS.name)
    || `${pick(o, FIELDS.first)} ${pick(o, FIELDS.last)}`.trim();
  if (!isName(name)) return null;
  return {
    name: tidyName(name.trim()),
    jerseyNumber: numberOf(pick(o, FIELDS.number)),
    position: pick(o, FIELDS.position).trim(),
    height,
  };
}

/**
 * Best roster found in any of the JSON payloads a page fetched. Each array in
 * each payload is a candidate; the one yielding the most players wins, on the
 * same reasoning as the text shapes above.
 */
export function playersFromJson(payloads) {
  let best = [];
  const visit = (node, depth) => {
    if (depth > 12 || !node || typeof node !== 'object') return;
    if (Array.isArray(node)) {
      const players = node.map(playerFromObject).filter(Boolean);
      if (players.length > best.length) best = players;
      for (const item of node) visit(item, depth + 1);
      return;
    }
    for (const value of Object.values(node)) visit(value, depth + 1);
  };
  for (const payload of payloads || []) visit(payload, 0);
  return best;
}

/**
 * Best-effort roster from a page's innerText. Returns [] when nothing parses,
 * which callers treat as "no roster available" rather than an error.
 */
export function parseRoster(pageText) {
  const lines = (pageText || '').split('\n').map((l) => l.trim());
  const shapes = [parseLabelled, parseCards, parseHeader, parseTable];
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
