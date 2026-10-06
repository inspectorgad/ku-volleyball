// The season simulator: plays the rest of the season out many times from the
// inputs the pipeline ships (seed.simulation, built by scripts/sim_inputs.py)
// and counts what happened.
//
// SeasonSimulator.kt in the app is a line-for-line copy. Both use the same
// small random-number generator (mulberry32) from the same starting seed and
// draw in the same order, so the dashboard and the app show identical numbers,
// and a what-if toggled in one gives what it gives in the other. Keep them in
// step: sim.test.mjs and SeasonSimulatorTest.kt check the same fixture.

export const RUNS = 20000;
const SEED = 20261;

function mulberry32(seed) {
  let a = seed | 0;
  return () => {
    a = (a + 0x6D2B79F5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/**
 * inputs: seed.simulation. forced: {index: "W" | "L"} for KU's remaining
 * matches the reader has decided. Returns the summary the screens show.
 */
export function simulate(inputs, forced = {}, runs = RUNS) {
  const rnd = mulberry32(SEED);
  const ku = inputs.kansas;
  const rem = inputs.remaining;
  const others = inputs.others;
  const rpi = inputs.rpi;
  const index = new Map(others.map((o, i) => [o.key, i]));

  const overall = new Array(rem.length + 1).fill(0); // by wins over the rest
  let titles = 0, shares = 0, top4 = 0, atLarge = 0, inField = 0, placeSum = 0;
  const ranks = [];
  const confWins = new Int32Array(others.length);

  for (let run = 0; run < runs; run++) {
    for (let i = 0; i < others.length; i++) confWins[i] = others[i].confW;
    let wins = 0, kuConf = ku.confW;
    for (let m = 0; m < rem.length; m++) {
      const draw = rnd(); // always drawn, so a forced result does not shift the rest
      const f = forced[m];
      const won = f === "W" ? true : f === "L" ? false : draw < rem[m].p;
      if (won) {
        wins++;
        if (rem[m].conference) kuConf++;
      } else if (rem[m].conference) {
        const o = index.get(rem[m].key);
        if (o !== undefined) confWins[o]++;
      }
    }
    for (let i = 0; i < others.length; i++) {
      const o = others[i];
      for (let g = 0; g < o.remaining; g++) if (rnd() < o.pVsAverage) confWins[i]++;
    }
    // Ties in the table are settled by a coin toss: the Big 12's real
    // tiebreakers need results this simulation does not keep.
    const kuTie = rnd();
    let ahead = 0, best = true;
    for (let i = 0; i < others.length; i++) {
      const tie = rnd();
      if (confWins[i] > kuConf || (confWins[i] === kuConf && tie > kuTie)) ahead++;
      if (confWins[i] > kuConf) best = false;
    }
    const place = ahead + 1;

    const w = rpi.wins + wins, p = rpi.played + rem.length;
    const value = 0.25 * (w / p) + 0.5 * rpi.owp + 0.25 * rpi.oowp;
    let rank = 1;
    for (const v of rpi.field) if (v > value) rank++;

    overall[wins]++;
    placeSum += place;
    if (place === 1) titles++;
    if (best) shares++;
    if (place <= 4) top4++;
    if (rank <= rpi.atLargeCutoff) atLarge++;
    if (rank <= rpi.atLargeCutoff || place === 1) inField++;
    ranks.push(rank);
  }

  ranks.sort((a, b) => a - b);
  const pct = (q) => ranks[Math.min(ranks.length - 1, Math.floor(q * ranks.length))];
  const winsAt = (q) => {
    let seen = 0;
    for (let k = 0; k < overall.length; k++) {
      seen += overall[k];
      if (seen > q * runs) return k;
    }
    return overall.length - 1;
  };
  const exp = overall.reduce((s, n, k) => s + n * k, 0) / runs;
  return {
    runs,
    expectedWins: ku.overallW + exp,
    expectedLosses: ku.overallL + rem.length - exp,
    winsLow: ku.overallW + winsAt(0.1),
    winsMid: ku.overallW + winsAt(0.5),
    winsHigh: ku.overallW + winsAt(0.9),
    games: ku.overallW + ku.overallL + rem.length,
    title: titles / runs,
    shareOfTitle: shares / runs,
    top4: top4 / runs,
    averagePlace: placeSum / runs,
    rpiRankMid: pct(0.5),
    rpiRankLow: pct(0.1),
    rpiRankHigh: pct(0.9),
    atLarge: atLarge / runs,
    tournament: inField / runs,
  };
}

/** "37%", with the ends said in words rather than as a false 0% or 100%. */
export function chance(p) {
  if (p < 0.005) return "under 1%";
  if (p > 0.995) return "over 99%";
  return Math.round(p * 100) + "%";
}
