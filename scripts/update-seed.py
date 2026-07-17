#!/usr/bin/env python3
"""Regenerates app/src/main/assets/seed.json from scraped/ KU volleyball data.

Inputs (all optional, produced by scrape-ku-volleyball.mjs):
  scraped/ncaa-game-*.json  one per finished match: {gameId, date, info, box}
  scraped/roster.json       current roster from kuathletics.com
  scraped/upcoming.json     upcoming matches from kuathletics.com

The seed is regenerated in full on every run — all data is scraper-owned, and
the app's Seeder merge is what protects user edits on-device.
"""
import glob
import json
import os
from datetime import datetime, timezone

TEAM_SEO = "kansas"
SEED_PATH = "app/src/main/assets/seed.json"


def load_json(path, default):
    try:
        with open(path) as f:
            return json.load(f)
    except (OSError, json.JSONDecodeError):
        return default


def to_int(value):
    try:
        return int(str(value).strip() or 0)
    except ValueError:
        return 0


players = {}  # name -> {name, jerseyNumber, position}
matches = {}  # (date, opponent.lower()) -> match dict


# Sources capitalize names inconsistently (e.g. "McCarthy" vs "Mccarthy"),
# so players are keyed case-insensitively; the roster's spelling wins.
def add_player(name, jersey, position, prefer=False):
    if not name:
        return
    existing = players.get(name.lower())
    if existing is None:
        players[name.lower()] = {"name": name, "jerseyNumber": jersey, "position": position}
    elif prefer:
        existing["name"] = name
        if jersey:
            existing["jerseyNumber"] = jersey
        if position:
            existing["position"] = position


def canonical_name(name):
    return players[name.lower()]["name"]


# --- Finished matches from NCAA box scores ---------------------------------
for path in sorted(glob.glob("scraped/ncaa-game-*.json")):
    data = load_json(path, None)
    if not data:
        continue
    contests = (data.get("info") or {}).get("contests") or []
    if not contests:
        continue
    contest = contests[0]
    teams = contest.get("teams") or []
    ku = next((t for t in teams if t.get("seoname") == TEAM_SEO), None)
    opp = next((t for t in teams if t.get("seoname") != TEAM_SEO), None)
    if ku is None or opp is None:
        continue
    if contest.get("gameState") != "F":
        continue

    ku_home = bool(ku.get("isHome"))
    set_scores = []
    for ls in contest.get("linescores") or []:
        home, visit = to_int(ls.get("home")), to_int(ls.get("visit"))
        ours, theirs = (home, visit) if ku_home else (visit, home)
        set_scores.append(f"{ours}-{theirs}")

    season = str(contest.get("seasonYear") or data["date"][:4])
    match = {
        "date": data["date"],
        "opponent": opp.get("nameShort") or opp.get("nameFull") or "Unknown",
        "season": season,
        "teamSets": to_int(ku.get("score")),
        "opponentSets": to_int(opp.get("score")),
        "setScores": ", ".join(set_scores),
        "lines": [],
    }

    ku_team_id = to_int(ku.get("teamId"))
    for tb in (data.get("box") or {}).get("teamBoxscore") or []:
        if to_int(tb.get("teamId")) != ku_team_id:
            continue
        for p in tb.get("playerStats") or []:
            if not p.get("participated"):
                continue
            name = f"{p.get('firstName', '').strip()} {p.get('lastName', '').strip()}".strip()
            add_player(name, str(p.get("number") or ""), p.get("position") or "")
            match["lines"].append({
                "player": name,
                "sp": to_int(p.get("gamesPlayed")),
                "k": to_int(p.get("kills")),
                "e": to_int(p.get("attackErrors")),
                "ta": to_int(p.get("attackAttempts")),
                "a": to_int(p.get("assists")),
                "sa": to_int(p.get("serviceAces")),
                "se": to_int(p.get("serviceErrors")),
                "d": to_int(p.get("digs")),
                "bs": to_int(p.get("blockSolos")),
                "ba": to_int(p.get("blockAssists")),
                "re": to_int(p.get("receptionErrors")),
                "bhe": to_int(p.get("ballHandlingErrors")),
            })

    matches[(match["date"], match["opponent"].lower())] = match

# --- Current roster (preferred source for number/position) ------------------
roster_names = set()
for entry in load_json("scraped/roster.json", []):
    name = entry.get("name", "").strip()
    roster_names.add(name)
    add_player(
        name,
        str(entry.get("jerseyNumber") or ""),
        (entry.get("position") or "").strip(),
        prefer=True,
    )

# active = on the current scraped roster. A failed/empty roster scrape must
# not mass-retire the team, so with an implausibly small roster the previous
# seed's flags are carried forward instead.
previous_seed = load_json(SEED_PATH, {})
previous_active = {
    (p.get("name") or "").lower(): p.get("active", True)
    for p in previous_seed.get("players", [])
}
roster_keys = {n.lower() for n in roster_names}
roster_valid = len(roster_keys) >= 8
for key, player in players.items():
    if roster_valid:
        player["active"] = key in roster_keys
    else:
        player["active"] = previous_active.get(key, True)

# Stat lines were recorded with whatever casing the box score used; align
# them with the canonical player names so the app can match them up.
for match in matches.values():
    for line in match.get("lines", []):
        line["player"] = canonical_name(line["player"])

# --- Upcoming matches (no results yet) --------------------------------------
played_dates = {key[0] for key in matches}
today = datetime.now(timezone.utc).strftime("%Y-%m-%d")
for entry in load_json("scraped/upcoming.json", []):
    date = entry.get("date", "")
    opponent = (entry.get("opponent") or "").strip()
    if not date or not opponent or date < today:
        continue
    key = (date, opponent.lower())
    if key in matches:
        continue
    matches[key] = {
        "date": date,
        "opponent": opponent,
        "season": date[:4],
    }

seed = {
    "formatVersion": 1,
    "generatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "team": "Kansas Jayhawks Women's Volleyball",
    "players": sorted(players.values(), key=lambda p: p["name"]),
    "matches": [matches[k] for k in sorted(matches)],
}

os.makedirs(os.path.dirname(SEED_PATH), exist_ok=True)

# Skip the write when nothing but the timestamp would change, so the nightly
# job doesn't commit (and rebuild the APK) on quiet days.
previous = load_json(SEED_PATH, {})
current_cmp = {k: v for k, v in seed.items() if k != "generatedAt"}
previous_cmp = {k: v for k, v in previous.items() if k != "generatedAt"}
if current_cmp == previous_cmp:
    print("seed.json unchanged (ignoring timestamp); not rewriting")
else:
    with open(SEED_PATH, "w") as f:
        json.dump(seed, f, indent=1)
        f.write("\n")
    print(
        f"seed.json written: {len(seed['players'])} players, "
        f"{len(seed['matches'])} matches "
        f"({sum(1 for m in seed['matches'] if 'teamSets' in m)} with results)"
    )
