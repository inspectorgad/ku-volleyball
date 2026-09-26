"""The season as flat tables, for the dashboard's "Ask about the team" box.

The seed is shaped for the app: nested, merged, full of display details. A
question put to Claude is better answered from plain tables it can load into
pandas and compute on, so the numbers come from code rather than from reading
a long document. This writes docs/ask-data.json: one list of flat records per
table, a data dictionary saying what every column means, and the few facts a
reader needs to interpret them (what "site" means, how hitting percentage is
worked out, that the RPI is provisional).

Everything here is derived from the seed that was just validated against the
NCAA box scores; nothing is recomputed or estimated.
"""

import json
import re

STAT_COLS = ["sp", "k", "e", "ta", "a", "sa", "se", "sat", "d", "bs", "ba", "re", "bhe"]

DEFINITIONS = {
    "sp": "sets played",
    "k": "kills", "e": "attack errors", "ta": "total attack attempts",
    "a": "assists", "sa": "service aces", "se": "service errors", "sat": "serve attempts",
    "d": "digs", "bs": "block solos", "ba": "block assists",
    "re": "reception errors", "bhe": "ball-handling errors",
    "hitting_pct": "(k - e) / ta, written like .300",
    "points": "k + sa + bs + 0.5 * ba (NCAA individual points)",
    "team_blocks": "bs + 0.5 * ba (NCAA team block total)",
    "per_set": "a rate per set uses the player's own sp; a team rate uses the match's total sets",
    "site": "H home, A away, N neutral site (a tournament or event venue that is neither team's home)",
    "conference": "true when the opponent is in that season's Big 12 standings",
    "ku_rank / opp_rank": "AVCA poll rank each team held on the day of the match (null = unranked); for NCAA "
                          "tournament matches the opponent's seed is in opp_seed and is the better guide",
    "opp_rpi": "the opponent's RPI rank for that season (current table, not on the day); 2026 is a provisional "
               "RPI computed from every Division I result until the NCAA publishes its own",
    "forecast": "the win model's last pre-match chance that KU wins (0-1), only for matches since Sep 19 2026",
    "sets": "one row per set: the score (ku_points, opp_points) and each side's kills, attack errors and "
            "attempts in that set (ku_k, ku_e, ku_ta, opp_k, opp_e, opp_ta) - the only stats the NCAA splits by set",
    "goals": "the coaching staff's per-match targets; met is judged on the value as published; "
             "ceiling=true means lower is better",
}


def _site(m):
    if m.get("neutral"):
        return "N"
    if m.get("home") is True:
        return "H"
    if m.get("home") is False:
        return "A"
    return None


def build_pack(seed):
    players = {p["name"].lower(): p for p in seed.get("players", [])}
    big12 = {}
    for st in seed.get("standings", []):
        big12.setdefault(st["season"], set()).add(_norm(st["team"]))
    defs = seed.get("goalDefinitions") or []

    matches, ku_lines, team_totals, opp_lines, goals, upcoming, set_attack = [], [], [], [], [], [], []
    for m in sorted(seed.get("matches", []), key=lambda x: x["date"]):
        base = {"season": m["season"], "date": m["date"], "opponent": m["opponent"]}
        conf = _norm(m["opponent"]) in big12.get(m["season"], set())
        if m.get("teamSets") is None:
            upcoming.append({**base, "site": _site(m), "conference": conf,
                             "venue": m.get("venue") or None, "city": m.get("city") or None,
                             "first_serve_ct": m.get("time") or None, "tv": m.get("tv") or None,
                             "win_probability": m.get("winProbability"),
                             "rating_source": m.get("ratingSource"),
                             "common_opponents": m.get("commonOpponents") or []})
            continue
        us, them = m["teamSets"], m["opponentSets"]
        matches.append({**base, "site": _site(m), "conference": conf,
                        "result": "W" if us > them else "L", "ku_sets": us, "opp_sets": them,
                        "set_scores": m.get("setScores"),
                        "ku_rank": m.get("kuRank"), "opp_rank": m.get("opponentRank"),
                        "opp_seed": m.get("opponentSeed"), "opp_rpi": m.get("opponentRpi"),
                        "forecast": m.get("forecast")})
        for l in m.get("lines", []):
            p = players.get(l["player"].lower(), {})
            ku_lines.append({**base, "player": l["player"], "jersey": p.get("jerseyNumber"),
                             "position": p.get("position"), **{c: l.get(c, 0) for c in STAT_COLS}})
        scores = [tuple(int(x) for x in s.strip().split("-")) for s in (m.get("setScores") or "").split(",") if "-" in s]
        sa = m.get("setAttack") or {}
        for i, (ku_pts, opp_pts) in enumerate(scores):
            row = {**base, "set": i + 1, "ku_points": ku_pts, "opp_points": opp_pts}
            for side in ("ku", "opp"):
                v = (sa.get(side) or [])[i] if i < len(sa.get(side) or []) else None
                row.update({f"{side}_k": v and v[0], f"{side}_e": v and v[1], f"{side}_ta": v and v[2]})
            set_attack.append(row)
        for side, key in (("KU", "teamStats"), ("OPP", "opponentStats")):
            if m.get(key):
                team_totals.append({**base, "side": side, **{c: m[key].get(c, 0) for c in STAT_COLS}})
        for l in m.get("opponentLines", []):
            opp_lines.append({**base, "player": l["player"], "position": l.get("position"),
                              **{c: l.get(c, 0) for c in STAT_COLS}})
        g = m.get("goals")
        if g and len(g.get("values", [])) == len(defs):
            for d, v in zip(defs, g["values"]):
                met = None if v is None else (v <= d["target"] if d.get("ceiling") else v >= d["target"])
                goals.append({**base, "goal": d["name"], "group": d["group"], "role": d.get("role"),
                              "player": (g.get("roles") or {}).get(d.get("role")) if d.get("role") else None,
                              "target": d["target"], "ceiling": bool(d.get("ceiling")),
                              "value": v, "met": met})

    polls = seed.get("polls") or []
    current_poll = polls[-1] if polls else None
    rpi = seed.get("rpi") or {}
    return {
        "about": "Kansas Jayhawks women's volleyball, from the official NCAA box scores and "
                 "kuathletics.com. One record per row; join tables on (season, date, opponent).",
        "generated_at": seed.get("generatedAt"),
        "definitions": DEFINITIONS,
        "rpi_note": rpi.get("updated"),
        "matches": matches,
        "ku_lines": ku_lines,
        "team_totals": team_totals,
        "opponent_lines": opp_lines,
        "goals": goals,
        "sets": set_attack,
        "upcoming": upcoming,
        "standings": [{k: st.get(k) for k in ("season", "team", "confW", "confL", "overallW",
                                             "overallL", "nationalRank", "rpiRank", "rpiSource")}
                      for st in seed.get("standings", [])],
        "poll": None if not current_poll else {
            "season": current_poll["season"], "updated": current_poll.get("updated"),
            "rows": [{k: r.get(k) for k in ("rank", "team", "record", "points", "previous", "firstPlaceVotes")}
                     for r in current_poll.get("rows", [])]},
        "roster": [{k: p.get(k) for k in ("name", "jerseyNumber", "position", "height", "active")}
                   for p in seed.get("players", [])],
    }


def _norm(name):
    n = re.sub(r"^#\d+\s+", "", (name or "").strip()).lower().replace(".", "")
    n = re.sub(r"\bstate\b", "st", n)
    return re.sub(r"\s+", " ", n).strip()


def write_pack(seed, path):
    """Writes the pack; returns False when only the timestamp would change."""
    pack = build_pack(seed)
    try:
        with open(path) as f:
            old = json.load(f)
    except (OSError, ValueError):
        old = None
    strip = lambda p: {k: v for k, v in (p or {}).items() if k != "generated_at"}
    if old is not None and strip(old) == strip(pack):
        return False
    with open(path, "w") as f:
        json.dump(pack, f, separators=(",", ":"))
        f.write("\n")
    return True
