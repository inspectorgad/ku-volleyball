// What a match-night alert says, worked out from the NCAA's feeds: the
// JavaScript twin of MatchAlerts.kt in the shared app code, which words the
// Android app's own alerts. The match-night watcher uses this to send the
// iPhone's alerts, so both phones say the same thing; match-alerts.test.mjs
// pins the same cases as MatchAlertsTest.kt.

const KU_SEO = 'kansas';

// The feed's fields as MatchAlerts.kt reads them through optString: missing is
// "", a whole number only if the text is one (toIntOrNull), blank falls back.
const str = (v) => (v == null ? '' : String(v));
const num = (v) => (/^[+-]?\d+$/.test(str(v)) ? Number(str(v)) : 0);
const ifBlank = (v, fallback) => (str(v).trim() ? str(v) : fallback);

/** KU's game id on a scoreboard response, or null. */
export function kuGameId(scoreboard) {
  for (const wrap of scoreboard?.games ?? []) {
    const g = wrap?.game ?? wrap;
    if ([g?.home, g?.away].some((t) => t?.names?.seo === KU_SEO)) return str(g.gameID).trim() ? str(g.gameID) : null;
  }
  return null;
}

/**
 * The box-score block whose players are Kansas's, by roster names
 * (lowercase "first last"); null if neither matches.
 */
export function kuBlock(box, kuNames) {
  let best = null;
  let bestHits = 0;
  for (const b of box?.teamBoxscore ?? []) {
    const hits = (b?.playerStats ?? [])
      .filter((p) => kuNames.has(`${str(p?.firstName)} ${str(p?.lastName)}`.trim().toLowerCase())).length;
    if (hits > bestHits) {
      best = b;
      bestHits = hits;
    }
  }
  return best;
}

/**
 * A match in progress or finished, from Kansas's side: { opponent, sets
 * (Kansas first), finished, kuSets, oppSets, final }; null if the game cannot
 * be read. Sets are counted from the set scores, and crossed box-score blocks
 * swap the sides back, as in MatchAlerts.live.
 */
export function live(info, box, kuNames) {
  const contest = info?.contests?.[0];
  const teams = contest?.teams;
  if (!teams) return null;
  const ku = teams.find((t) => t?.seoname === KU_SEO);
  const opp = teams.find((t) => t && t !== ku);
  if (!ku || !opp) return null;
  let kuHome = str(ku.isHome).toLowerCase() === 'true';
  const blockId = str(box ? kuBlock(box, kuNames)?.teamId : null);
  if (blockId.trim() && blockId !== str(ku.teamId)) kuHome = !kuHome;

  const sets = (contest.linescores ?? [])
    .map((s) => {
      const home = num(s?.home);
      const visit = num(s?.visit);
      return kuHome ? [home, visit] : [visit, home];
    })
    .filter(([a, b]) => a + b > 0);
  const isFinal = str(contest.gameState).toUpperCase() === 'F' ||
    str(contest.currentPeriod).toUpperCase() === 'FINAL';
  let finished = 0;
  let us = 0;
  let them = 0;
  for (const [i, [a, b]] of sets.entries()) {
    const target = i === 4 ? 15 : 25;
    const done = (Math.max(a, b) >= target && Math.abs(a - b) >= 2) || (isFinal && i === sets.length - 1);
    if (!done || us === 3 || them === 3) break;
    finished++;
    if (a > b) us++;
    else them++;
  }
  return {
    opponent: ifBlank(opp.nameShort, 'the opponent'),
    sets, finished, kuSets: us, oppSets: them,
    final: isFinal || us === 3 || them === 3,
  };
}

/** ["Kansas wins set 2, 25-21", "Kansas leads 2-0 vs Utah"]. */
export function setMessage(l) {
  const n = l.finished;
  const [a, b] = l.sets[n - 1];
  const title = a > b ? `Kansas wins set ${n}, ${a}-${b}` : `${l.opponent} takes set ${n}, ${b}-${a}`;
  const state = l.kuSets > l.oppSets ? `Kansas leads ${l.kuSets}-${l.oppSets}`
    : l.kuSets < l.oppSets ? `Kansas trails ${l.kuSets}-${l.oppSets}`
      : `Tied ${l.kuSets}-${l.oppSets}`;
  return [title, `${state} vs ${l.opponent}`];
}

/** ["Final: Kansas def. Utah 3-0", "32-30, 25-20, 26-24\nStanley 13 K (.132) · ..."]. */
export function finalMessage(l, box, kuNames) {
  const won = l.kuSets > l.oppSets;
  const title = won ? `Final: Kansas def. ${l.opponent} ${l.kuSets}-${l.oppSets}`
    : `Final: ${l.opponent} def. Kansas ${l.oppSets}-${l.kuSets}`;
  const scores = l.sets.slice(0, l.finished).map(([a, b]) => `${a}-${b}`).join(', ');
  const block = box ? kuBlock(box, kuNames) : null;
  const leaders = block ? topPerformers(block) : null;
  return [title, [scores, leaders].filter((x) => x != null).join('\n')];
}

/** "Ptacek 14 K (.684) · Hasbrook 13 D · Messer 27 A". */
export function topPerformers(block) {
  if (!Array.isArray(block?.playerStats)) return null;
  const rows = block.playerStats.filter((p) => p && typeof p === 'object');
  const n = (p, k) => num(p?.[k]);
  const last = (p) => ifBlank(p.lastName, str(p.firstName));
  // Kotlin's maxByOrNull keeps the first of equal maxima; so does this.
  const maxBy = (f) => rows.reduce((best, p) => (best === null || f(p) > f(best) ? p : best), null);
  const parts = [];
  const k = maxBy((p) => n(p, 'kills'));
  if (k && n(k, 'kills') > 0) {
    const ta = n(k, 'attackAttempts');
    const pct = ta > 0 ? ` (${formatHit(n(k, 'kills') - n(k, 'attackErrors'), ta)})` : '';
    parts.push(`${last(k)} ${n(k, 'kills')} K${pct}`);
  }
  const d = maxBy((p) => n(p, 'digs'));
  if (d && n(d, 'digs') > 0) parts.push(`${last(d)} ${n(d, 'digs')} D`);
  const a = maxBy((p) => n(p, 'assists'));
  if (a && n(a, 'assists') >= 10) parts.push(`${last(a)} ${n(a, 'assists')} A`);
  const bl = maxBy((p) => n(p, 'blockSolos') * 2 + n(p, 'blockAssists'));
  if (bl && n(bl, 'blockSolos') + n(bl, 'blockAssists') >= 3) {
    // Kotlin writes 3.5 as "3.5" and 4.0 as "4.0", then drops the ".0".
    parts.push(`${last(bl)} ${n(bl, 'blockSolos') + n(bl, 'blockAssists') / 2} BLK`);
  }
  return parts.length ? parts.join(' · ') : null;
}

/**
 * A hitting percentage, (kills - errors) / attempts, as ".684": three
 * places, halves rounded away from zero as Kotlin's fixed() rounds them,
 * and the leading zero dropped. Worked in whole numbers, so the rounding is
 * exact: the ratio of two counts is never a hair off a half the way its
 * floating-point value can be.
 */
export function formatHit(net, attempts) {
  const sign = net < 0 ? '-' : '';
  const thousandths = Math.floor((2000 * Math.abs(net) + attempts) / (2 * attempts));
  const whole = Math.floor(thousandths / 1000);
  const frac = String(thousandths % 1000).padStart(3, '0');
  return whole === 0 ? `${sign}.${frac}` : `${sign}${whole}.${frac}`;
}
