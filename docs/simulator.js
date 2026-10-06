// The dashboard's season simulator section. The simulation is sim.js; this
// draws the numbers and the what-if toggles, with the same wording as the
// app's SimulatorScreen.kt.
import { simulate, chance } from "./sim.js";

const EXPLAIN =
  "The rest of the season played out 20,000 times. Each remaining match is won or lost at " +
  "random with the win model's chance for it (the Est. win on the schedule), and the " +
  "numbers here count what happened. Set a match to Win or Loss to see what that result " +
  "would change. Big 12: the other teams' remaining opponents are not published in advance, " +
  "so their games other than against Kansas are played against an average Big 12 side; ties " +
  "in the table are settled by a coin toss rather than the Big 12's tiebreakers. NCAA " +
  "tournament: Kansas is in if the conference title falls to Kansas (the automatic bid) or " +
  "Kansas's final RPI ranks inside the top 45, about where at-large bids usually stop, with " +
  "everyone else's RPI held where it is today. Estimates, not a forecast of what the " +
  "selection committee will do.";

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
const shortDate = (iso) => `${MONTHS[Number(iso.slice(5, 7)) - 1]} ${Number(iso.slice(8, 10))}`;

const el = (tag, cls, text) => {
  const n = document.createElement(tag);
  if (cls) n.className = cls;
  if (text !== undefined) n.textContent = text;
  return n;
};

export function lines(r) {
  return [
    `Projected record ${r.winsMid}-${r.games - r.winsMid} (likely range ${r.winsLow}-${r.games - r.winsLow} to ${r.winsHigh}-${r.games - r.winsHigh})`,
    `Big 12: top-four finish ${chance(r.top4)} · title ${chance(r.title)} (a share ${chance(r.shareOfTitle)}) · average place ${r.averagePlace.toFixed(1)}`,
    `NCAA tournament ${chance(r.tournament)} · final RPI around #${r.rpiRankMid} (#${r.rpiRankLow}-#${r.rpiRankHigh})`,
  ];
}

async function init() {
  const wrap = document.getElementById("sim-wrap");
  if (!wrap) return;
  let inputs;
  try {
    const r = await fetch("season-data.json", { cache: "no-cache" });
    inputs = (await r.json()).simulation;
  } catch { return; }
  if (!inputs || !inputs.remaining?.length) return;

  const forced = {};
  const head = el("h3", "sim-head");
  const out = el("div", "sim-lines");
  const reset = el("button", "chip", "Reset to the model");
  reset.hidden = true;
  const list = el("div", "sim-list");

  const render = () => {
    const n = Object.keys(forced).length;
    head.textContent = n ? `With ${n} result${n === 1 ? "" : "s"} set by you`
      : "If the rest of the season goes as the model expects";
    out.replaceChildren(...lines(simulate(inputs, forced)).map((t) => el("p", null, t)));
    reset.hidden = !n;
    for (const row of list.children) {
      const i = Number(row.dataset.i);
      for (const b of row.querySelectorAll("button")) {
        b.setAttribute("aria-pressed", String((forced[i] ?? "M") === b.dataset.v));
      }
    }
  };

  inputs.remaining.forEach((m, i) => {
    const row = el("div", "sim-row");
    row.dataset.i = String(i);
    row.appendChild(el("span", "sim-match", `${shortDate(m.date)}  ${m.venue === "A" ? "at" : "vs"} ${m.opponent}`));
    row.appendChild(el("span", "sim-p", `model ${chance(m.p)}`));
    const chips = el("span", "sim-chips");
    for (const [v, label] of [["M", "Model"], ["W", "Win"], ["L", "Loss"]]) {
      const b = el("button", "chip", label);
      b.dataset.v = v;
      b.onclick = () => {
        if (v === "M") delete forced[i]; else forced[i] = v;
        render();
      };
      chips.appendChild(b);
    }
    row.appendChild(chips);
    list.appendChild(row);
  });
  reset.onclick = () => {
    for (const k of Object.keys(forced)) delete forced[k];
    render();
  };

  wrap.replaceChildren(
    el("h2", null, "Season simulator"),
    head, out, reset,
    el("p", "fineprint", EXPLAIN),
    el("h3", "sim-head", "Remaining matches"),
    list,
  );
  wrap.hidden = false;
  render();
}

init();
