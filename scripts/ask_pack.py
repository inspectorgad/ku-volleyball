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
    "classYear / hometown / highSchool / previousSchool": "from kuathletics.com's roster: academic year "
                                                          "(Fr., So., Jr., Sr., Gr.), home town, last high school, and "
                                                          "for a transfer the college she came from; class is "
                                                          "only current for active players",
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
    "opponent_rosters": "every player on the rosters of the schools KU plays, from each school's athletics site: "
                        "team, player, jersey, position, height, class_year, hometown, high_school, previous_school",
    "opponent_form": "season totals so far for every player of each team still to play KU (team, matches = the "
                     "team's matches, mp = matches the player played, then the usual stat columns)",
    "team_serving": "season serving totals for every Big 12 and ranked team: matches, sets, service_aces, "
                    "service_errors, serve_attempts; big12 marks conference members",
    "national_leaders": "the NCAA's Division I individual leaders, one row per player per category; value is "
                        "as the NCAA prints it (a rate like .74 or a total), sets = sets played; season is the "
                        "season the list covers, and the date it runs through is given with the summary below",
    "team_key / opponent_key / home_key / away_key": "one normalized spelling per school ('Arizona State', "
                                                     "'Arizona St.' and '#8 Arizona State' all become 'arizona st'); "
                                                     "the tables spell schools as their sources do, so match teams "
                                                     "across tables on these keys, never on the display names",
    "d1_results": "every final Division I result of the current season: date, home team, home sets won, away "
                  "team, away sets won (team names as the NCAA scoreboard spells them)",
}


# The instructions Claude is given, shared by the dashboard (docs/ask.js) and
# the app (AskEngine.kt) so both ask the same way. The tables are fetched by
# Claude's own code through the get_table tool; this only says how.
SYSTEM_RULES = """You are the analyst behind the Kansas Jayhawks women's volleyball app and dashboard. You answer questions from coaches and fans about the team and the teams it plays, using the season data you are given - and the web, when the reader turns web search on for a question.

The complete data is available to your Python code through the get_table tool: tables matches, sets (each set's score and both teams' kills, attack errors and attempts in it), ku_lines (KU player lines per match), team_totals, opponent_lines, goals (the staff's per-match goals), upcoming, standings, poll, roster, and definitions. Call it from inside code execution, for example: import json, pandas as pd; lines = pd.DataFrame(json.loads(await get_table({'table': 'ku_lines'}))). Join tables on (season, date, opponent). There are also tables about other teams: opponent_rosters, opponent_form, team_serving, national_leaders and d1_results. A summary of the smaller tables is below for orientation.

How to answer:
- Compute every number with code from the tables. Do not estimate, recall, or do arithmetic in your head, even for a simple total.
- Lead with the answer in a sentence or two. Add a small markdown table when it helps. End with one short line saying what the figures cover (which season, which matches, any filter).
- Use volleyball conventions: hitting percentage as .300, per-set rates to two decimals, team blocks as solos plus half of assists.
- Name small samples plainly (for example "only 3 matches").
- If the data cannot answer the question - injuries, practice, line-ups, serve-receive ratings, anything not in the box scores - say so in a sentence instead of guessing; with web search on, look for it on the web instead.
- Season 2026 is the current season. "This season" means 2026 unless the reader says otherwise.

Other teams: opponent_rosters, opponent_form, team_serving, national_leaders and d1_results (every Division I result this season) let you analyse other teams too - an opponent's players and form, records against common opponents, results against ranked teams, serving, and where players rank nationally. Use them for any question about another team. The tables spell schools differently (the poll says "Arizona State", d1_results "Arizona St."), so match teams across tables on the team_key, opponent_key, home_key and away_key columns.

Web search: the reader can turn on web search for a question, and each question says whether it is on. When it is off, answer from the data only, and say plainly when the data cannot answer. When it is on, you may use the web_search tool for what the tables cannot tell you - previews, recaps, injuries and line-up news, coaches' comments, transfers, rankings commentary - preferring official athletics sites, the Big 12, the NCAA, the AVCA and established news outlets. Even then, take every statistic from the tables when they have it, and keep web material for context. Make clear which statements come from the web, and give each article's date when it matters, since news goes out of date. Search only as much as the question needs."""


def _cell(v):
    if v is None:
        return ""
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, list):
        v = "; ".join(f"{c.get('team')}: KU {c.get('ku')} / them {c.get('them')}" for c in v)
    s = str(v)
    return '"' + s.replace('"', '""') + '"' if any(ch in s for ch in '",\n') else s


def _csv(rows, cols):
    return "\n".join([",".join(cols)] + [",".join(_cell(r.get(c)) for c in cols) for r in rows])


def system_prompt(p):
    """The rules plus a compact summary of the smaller tables, in fixed column
    order so the text - and the prompt cache - changes only with the data."""
    current = (p["upcoming"][0]["season"] if p["upcoming"] else
               (p["matches"][-1]["season"] if p["matches"] else None))
    parts = [
        f"Data generated {p['generated_at']}.",
        "Definitions:\n" + "\n".join(f"- {k}: {v}" for k, v in p["definitions"].items()),
        f"RPI: {p['rpi_note']}." if p.get("rpi_note") else "",
        "Played matches:\n" + _csv(p["matches"], ["season", "date", "opponent", "site", "conference", "result",
                                                  "ku_sets", "opp_sets", "set_scores", "ku_rank", "opp_rank",
                                                  "opp_seed", "opp_rpi", "forecast"]),
        "Upcoming matches:\n" + _csv(p["upcoming"], ["date", "opponent", "site", "conference", "first_serve_ct",
                                                     "tv", "win_probability", "rating_source", "common_opponents"]),
        f"Big 12 standings {current}:\n" + _csv([s for s in p["standings"] if s["season"] == current],
                                                ["team", "confW", "confL", "overallW", "overallL",
                                                 "nationalRank", "rpiRank", "rpiSource"]),
        (f"AVCA poll ({p['poll']['updated']}):\n" + _csv(p["poll"]["rows"], ["rank", "team", "record", "points", "previous"]))
        if p.get("poll") else "",
        (f"National leaders ({p['national_leaders'][0]['season']} season): {p['national_leaders_as_of']}. "
         "This list can lag the other tables, so give its date when you use it.")
        if p.get("national_leaders") and p.get("national_leaders_as_of") else "",
        "Roster:\n" + _csv(p["roster"], ["name", "jerseyNumber", "position", "height", "active",
                                         "classYear", "hometown", "highSchool", "previousSchool"]),
    ]
    return SYSTEM_RULES + "\n\n" + "\n\n".join(x for x in parts if x)


def _site(m):
    if m.get("neutral"):
        return "N"
    if m.get("home") is True:
        return "H"
    if m.get("home") is False:
        return "A"
    return None


def build_pack(seed, results=None):
    """results: this season's Division I results as [date, home, home sets,
    away, away sets] rows, de-duplicated by update-seed.py."""
    players = {p["name"].lower(): p for p in seed.get("players", [])}
    big12 = {}
    for st in seed.get("standings", []):
        big12.setdefault(st["season"], set()).add(_norm(st["team"]))
    defs = seed.get("goalDefinitions") or []

    matches, ku_lines, team_totals, opp_lines, goals, upcoming, set_attack = [], [], [], [], [], [], []
    for m in sorted(seed.get("matches", []), key=lambda x: x["date"]):
        base = {"season": m["season"], "date": m["date"], "opponent": m["opponent"],
                "opponent_key": team_key(m["opponent"])}
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

    leaders = seed.get("nationalLeaders") or {}
    polls = seed.get("polls") or []
    current_poll = polls[-1] if polls else None
    rpi = seed.get("rpi") or {}
    pack = {
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
        "standings": [{**{k: st.get(k) for k in ("season", "team", "confW", "confL", "overallW",
                                                "overallL", "nationalRank", "rpiRank", "rpiSource")},
                       "team_key": team_key(st.get("team"))}
                      for st in seed.get("standings", [])],
        "poll": None if not current_poll else {
            "season": current_poll["season"], "updated": current_poll.get("updated"),
            "rows": [{**{k: r.get(k) for k in ("rank", "team", "record", "points", "previous", "firstPlaceVotes")},
                      "team_key": team_key(r.get("team"))}
                     for r in current_poll.get("rows", [])]},
        "roster": [{k: p.get(k) for k in ("name", "jerseyNumber", "position", "height", "active",
                                          "classYear", "hometown", "highSchool", "previousSchool")}
                   for p in seed.get("players", [])],
        "opponent_rosters": [
            {"team": r.get("team"), "team_key": team_key(r.get("team")),
             "player": pl.get("player"), "jersey": pl.get("jerseyNumber"),
             "position": pl.get("position"), "height": pl.get("height"), "class_year": pl.get("classYear"),
             "hometown": pl.get("hometown"), "high_school": pl.get("highSchool"),
             "previous_school": pl.get("previousSchool")}
            for r in seed.get("opponentRosters") or [] for pl in r.get("players") or []],
        "opponent_form": [
            {"team": f.get("team"), "team_key": team_key(f.get("team")),
             "matches": f.get("matches"), "player": pl.get("player"),
             "jersey": pl.get("jerseyNumber"), "position": pl.get("position"), "mp": pl.get("mp", 0),
             **{c: pl.get(c, 0) for c in STAT_COLS}}
            for f in seed.get("opponentForm") or [] for pl in f.get("players") or []],
        "team_serving": [
            {"team": t.get("team"), "team_key": team_key(t.get("team")),
             "season": t.get("season"), "matches": t.get("matches"),
             "sets": t.get("sets"), "service_aces": t.get("serviceAces"),
             "service_errors": t.get("serviceErrors"), "serve_attempts": t.get("serveAttempts"),
             "big12": bool(t.get("big12"))}
            for t in seed.get("teamServing") or []],
        "national_leaders": [
            {"season": leaders.get("season"), "category": c.get("name"), "rank": r.get("rank"),
             "player": r.get("player"), "team": r.get("team"), "team_key": team_key(r.get("team")),
             "class_year": r.get("cls"), "position": r.get("position"),
             "sets": r.get("sets"), "value": r.get("value")}
            for c in leaders.get("categories") or [] for r in c.get("rows") or []],
        "national_leaders_as_of": leaders.get("updated") or None,
        "d1_results": [
            {"date": g[0], "home": g[1], "home_key": team_key(g[1]), "home_sets": g[2],
             "away": g[3], "away_key": team_key(g[3]), "away_sets": g[4]}
            for g in results or []],
    }
    pack["system_prompt"] = system_prompt(pack)
    return pack


# Kept in step with update-seed.py's norm_team(), which the seed's own joins use.
_ALIASES = {"southern california": "southern cal", "usc": "southern cal"}


def team_key(name):
    """One spelling per school: 'Arizona State', 'Arizona St.' and '#8 Arizona
    State' all become 'arizona st'. The tables spell schools as their sources
    do, so Claude matches teams across them on this key."""
    n = re.sub(r"^#\d+\s+", "", (name or "").strip())
    n = re.sub(r"\s*\((?:\d+|[Ee]xh\.?|[Ee]xhibition)\)\s*$", "", n).lower().replace(".", "")
    n = re.sub(r"\s+", " ", re.sub(r"\bstate\b", "st", n)).strip()
    return _ALIASES.get(n, n)


_norm = team_key


def write_pack(seed, path, results=None):
    """Writes the pack; returns False when only the timestamp would change."""
    pack = build_pack(seed, results)
    try:
        with open(path) as f:
            old = json.load(f)
    except (OSError, ValueError):
        old = None
    # The timestamp is also inside system_prompt ("Data generated ..."), so the
    # comparison gives the new pack the old timestamp and rebuilds its prompt;
    # otherwise every run counts as a change, rewrites the pack and rebuilds
    # the app for nothing.
    if old is not None:
        same = dict(pack, generated_at=old.get("generated_at"))
        same["system_prompt"] = system_prompt(same)
        if same == old:
            return False
    with open(path, "w") as f:
        json.dump(pack, f, separators=(",", ":"))
        f.write("\n")
    return True
