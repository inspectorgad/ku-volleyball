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
import re
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
# Height only ever comes from the roster page, so box scores pass it as "".
def add_player(name, jersey, position, height="", prefer=False):
    if not name:
        return
    existing = players.get(name.lower())
    if existing is None:
        players[name.lower()] = {
            "name": name, "jerseyNumber": jersey, "position": position, "height": height
        }
    elif prefer:
        existing["name"] = name
        if jersey:
            existing["jerseyNumber"] = jersey
        if position:
            existing["position"] = position
        if height:
            existing["height"] = height


def canonical_name(name):
    return players[name.lower()]["name"]


# The twelve counting stats the app stores, in both per-player and per-team form.
def stat_line(src):
    return {
        "sp": to_int(src.get("gamesPlayed")),
        "k": to_int(src.get("kills")),
        "e": to_int(src.get("attackErrors")),
        "ta": to_int(src.get("attackAttempts")),
        "a": to_int(src.get("assists")),
        "sa": to_int(src.get("serviceAces")),
        "se": to_int(src.get("serviceErrors")),
        "d": to_int(src.get("digs")),
        "bs": to_int(src.get("blockSolos")),
        "ba": to_int(src.get("blockAssists")),
        "re": to_int(src.get("receptionErrors")),
        "bhe": to_int(src.get("ballHandlingErrors")),
    }


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
    location = contest.get("location") or {}
    city = ", ".join(
        p for p in [location.get("city"), location.get("stateUsps")] if p
    )
    match = {
        "date": data["date"],
        "opponent": opp.get("nameShort") or opp.get("nameFull") or "Unknown",
        "season": season,
        "teamSets": to_int(ku.get("score")),
        "opponentSets": to_int(opp.get("score")),
        "setScores": ", ".join(set_scores),
        "venue": (location.get("venue") or "").strip(),
        "city": city,
        # isHome is not enough on its own: at neutral tournaments the NCAA still
        # designates one side as home, so KU shows "home" in Sioux Falls. The
        # venue decides instead; _kuDesignatedHome only helps spot neutrals.
        "_kuDesignatedHome": ku_home,
        "lines": [],
        "opponentLines": [],
    }

    ku_team_id = to_int(ku.get("teamId"))
    for tb in (data.get("box") or {}).get("teamBoxscore") or []:
        is_ku = to_int(tb.get("teamId")) == ku_team_id
        # Team totals are recorded, not summed from the player lines: every stat
        # does add up except reception errors, which the NCAA may charge to the
        # team rather than a player (38 such rows across the 2025 season).
        side_totals = stat_line(tb.get("teamStats") or {})
        if is_ku:
            match["teamStats"] = side_totals
        else:
            match["opponentStats"] = side_totals
        for p in tb.get("playerStats") or []:
            if not p.get("participated"):
                continue
            name = f"{p.get('firstName', '').strip()} {p.get('lastName', '').strip()}".strip()
            line = stat_line(p)
            if is_ku:
                add_player(name, str(p.get("number") or ""), p.get("position") or "")
                match["lines"].append({"player": name, **line})
            else:
                # Opponent players are stored inline on the match rather than in
                # the players list: names collide across teams, and we only ever
                # see their games against KU, so there is no season to aggregate
                # them into. Number/position are denormalized for the same reason.
                match["opponentLines"].append({
                    "player": name,
                    "jerseyNumber": str(p.get("number") or ""),
                    "position": p.get("position") or "",
                    **line,
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
        (entry.get("height") or "").strip(),
        prefer=True,
    )

# active = on the current scraped roster. A failed/empty roster scrape must
# not mass-retire the team, so with an implausibly small roster the previous
# seed's flags are carried forward instead.
previous_seed = load_json(SEED_PATH, {})
previous_players = {
    (p.get("name") or "").lower(): p for p in previous_seed.get("players", [])
}
roster_keys = {n.lower() for n in roster_names}
roster_valid = len(roster_keys) >= 8
for key, player in players.items():
    previous = previous_players.get(key, {})
    if roster_valid:
        player["active"] = key in roster_keys
    else:
        player["active"] = previous.get("active", True)
    # Only the roster page carries height, and it lists current players only, so
    # a player who leaves the roster keeps the height we already knew.
    if not player.get("height"):
        player["height"] = previous.get("height", "")

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
        # kuathletics writes "versus" for home and "at" for road games.
        "home": bool(entry.get("home")),
    }

# --- Home / away / neutral for played matches -------------------------------
# KU's home floor is in Lawrence, so the venue city is the one dependable
# signal. Everything else is a road or neutral game.
HOME_CITY = "Lawrence, KS"

# A non-Lawrence venue that hosted KU against two or more different opponents in
# one season is a multi-team event, so those games are neutral-site rather than
# true road games.
venue_opponents = {}
for m in matches.values():
    venue = m.get("venue")
    if venue and m.get("city") != HOME_CITY:
        venue_opponents.setdefault((m["season"], venue), set()).add(m["opponent"].lower())

for m in matches.values():
    if "_kuDesignatedHome" not in m:
        continue  # upcoming match: home already came from kuathletics
    designated = m.pop("_kuDesignatedHome")
    at_home = m.get("city") == HOME_CITY
    tournament = len(venue_opponents.get((m["season"], m.get("venue")), ())) > 1
    m["home"] = at_home
    # Designated home away from Lawrence can only be a neutral site.
    if not at_home and (designated or tournament):
        m["neutral"] = True

played = [m for m in matches.values() if "teamSets" in m]
if played:
    h = sum(1 for m in played if m.get("home"))
    n = sum(1 for m in played if m.get("neutral"))
    print(f"home/away: {h} home, {n} neutral, {len(played) - h - n} away")

# --- Big 12 standings, computed from the scoreboard sweep -------------------
# /standings/volleyball-women/d1 returns HTTP 500 for this sport, so conference
# records are derived from the Big 12 games the sweep already collects. This
# also means any season can be rebuilt retroactively, which a live standings
# endpoint could not do.
def norm_team(name):
    """Canonical key for cross-source name matching ('Iowa State'/'Iowa St.')."""
    n = re.sub(r"\s*\(\d+\)\s*$", "", name or "").lower()  # strip poll votes
    n = n.replace(".", "")
    n = re.sub(r"\bstate\b", "st", n)
    return re.sub(r"\s+", " ", n).strip()


index = load_json("scraped/ku-index.json", {})
records = {}  # (season, key) -> record dict
for game in index.get("big12Games", {}).values():
    season = game.get("season") or game.get("date", "")[:4]
    conf_game = bool(game.get("conferenceGame"))
    for side in ("home", "away"):
        s = game.get(side) or {}
        if not s.get("inConference") or not s.get("name"):
            continue  # non-conference opponents get no standings row
        rec = records.setdefault(
            (season, norm_team(s["name"])),
            {
                "season": season,
                "team": s["name"],
                "seo": s.get("seo", ""),
                "confW": 0, "confL": 0, "overallW": 0, "overallL": 0,
                "_rankDate": "", "nationalRank": None,
            },
        )
        won = bool(s.get("winner"))
        rec["overallW" if won else "overallL"] += 1
        if conf_game:
            rec["confW" if won else "confL"] += 1
        # Keep the most recent rank the scoreboard reported that season.
        if s.get("rank") and game.get("date", "") >= rec["_rankDate"]:
            rec["_rankDate"] = game["date"]
            rec["nationalRank"] = s["rank"]

# --- Rankings snapshots (AVCA poll + RPI) -----------------------------------
# Both endpoints serve only the current poll, so each is keyed by the season in
# its "Through Games ..." label.
def snapshot_season(payload):
    m = re.search(r"(20\d{2})", payload.get("updated", "") or "")
    return m.group(1) if m else None


polls = []
avca = load_json("scraped/rankings-avca.json", {})
rpi = load_json("scraped/rankings-rpi.json", {})

rpi_season = snapshot_season(rpi)
rpi_by_team = {}
for row in rpi.get("data", []):
    if (row.get("Conf") or "") == "Big 12":
        rpi_by_team[norm_team(row.get("School", ""))] = row

# RPI carries each team's official overall record — fold in the RPI rank and
# cross-check our computed record against it (a warning, never a failure: the
# snapshot and our sweep can legitimately sit a game apart mid-season).
for (season, key), rec in records.items():
    row = rpi_by_team.get(key)
    if not row or season != rpi_season:
        continue
    rec["rpiRank"] = int(row["Rank"]) if str(row.get("Rank", "")).isdigit() else None
    official = (row.get("Record") or "").strip()
    ours = f"{rec['overallW']}-{rec['overallL']}"
    if official and official != ours:
        print(f"  cross-check: {rec['team']} computed {ours} vs RPI {official}")

avca_season = snapshot_season(avca)
if avca.get("data") and avca_season:
    b12_keys = {k for (s, k) in records if s == avca_season}
    rows = []
    for row in avca["data"]:
        label = str(row.get("RANK", "")).strip()  # can be a tie, e.g. "T-22."
        digits = re.search(r"\d+", label)
        team = re.sub(r"\s*\(\d+\)\s*$", "", row.get("TEAM", "")).strip()
        votes = re.search(r"\((\d+)\)\s*$", row.get("TEAM", "") or "")
        rows.append({
            "rank": int(digits.group()) if digits else 0,
            "rankLabel": label.rstrip("."),
            "team": team,
            "record": (row.get("RECORD") or "").strip(),
            "points": (row.get("TOTAL POINTS") or "").strip(),
            "previous": (row.get("PREVIOUS") or "").strip(),
            "firstPlaceVotes": int(votes.group(1)) if votes else 0,
            "big12": norm_team(team) in b12_keys,
        })
    polls.append({
        "season": avca_season,
        "name": "AVCA Coaches Poll",
        "updated": (avca.get("updated") or "").strip(),
        "rows": rows,
    })

    # The poll is the authoritative ranking. The scoreboard's per-game rank is
    # only "the rank this team carried in that game", so a team that fell out
    # of the top 25 would otherwise keep a stale number forever (Utah looked
    # like a final #23 while actually finishing unranked). Where we hold a poll
    # for the season, it decides: absent from the poll means unranked.
    poll_rank = {norm_team(r["team"]): r["rank"] for r in rows}
    restated = 0
    for (season, key), rec in records.items():
        if season != avca_season:
            continue
        fresh = poll_rank.get(key)
        if fresh != rec["nationalRank"]:
            restated += 1
        rec["nationalRank"] = fresh
    if restated:
        print(f"  national ranks restated from the poll for {restated} teams")
    print(
        f"  AVCA poll {avca_season}: {len(rows)} teams, "
        f"{sum(1 for r in rows if r['big12'])} from the Big 12"
    )

# Sorted by conference win %, then conference wins, then overall win % — NOT
# official Big 12 tiebreakers (those use head-to-head); the UI says as much.
def standing_sort(rec):
    conf_games = rec["confW"] + rec["confL"]
    overall = rec["overallW"] + rec["overallL"]
    return (
        -(rec["confW"] / conf_games if conf_games else 0),
        -rec["confW"],
        -(rec["overallW"] / overall if overall else 0),
        rec["team"],
    )


standings = []
for rec in sorted(records.values(), key=standing_sort):
    standings.append({k: v for k, v in rec.items() if not k.startswith("_")})
if standings:
    seasons = sorted({r["season"] for r in standings})
    print(f"standings computed for seasons {', '.join(seasons)}: {len(standings)} team rows")

opp_lines = sum(len(m.get("opponentLines") or []) for m in matches.values())
opp_matches = sum(1 for m in matches.values() if m.get("opponentLines"))
heights = sum(1 for p in players.values() if p.get("height"))
print(f"opponent box scores: {opp_matches} matches, {opp_lines} player lines")
print(f"heights: {heights}/{len(players)} players")

# --- Opponent rosters -------------------------------------------------------
# From each school's own athletics site, so a scheduled opponent's line-up is
# available before they have played anyone. Also the only source of opposing
# players' heights: the NCAA box score carries name, number and position only.
opponent_rosters = []
for key, entry in sorted(load_json("scraped/opponent-rosters.json", {}).items()):
    roster = [
        {
            "player": p.get("name", "").strip(),
            "jerseyNumber": str(p.get("jerseyNumber") or ""),
            "position": (p.get("position") or "").strip(),
            "height": (p.get("height") or "").strip(),
        }
        for p in entry.get("players", [])
        if p.get("name")
    ]
    if roster:
        opponent_rosters.append({
            "team": entry.get("team", key),
            "fetchedAt": entry.get("fetchedAt", ""),
            "players": roster,
        })

# Heights recorded on the roster carry over onto the box-score lines we already
# store, matched by name within the same team.
height_by_team = {
    norm_team(r["team"]): {p["player"].lower(): p["height"] for p in r["players"] if p["height"]}
    for r in opponent_rosters
}
backfilled = 0
for m in matches.values():
    heights_for_opponent = height_by_team.get(norm_team(m.get("opponent", "")), {})
    for line in m.get("opponentLines") or []:
        height = heights_for_opponent.get(line["player"].lower())
        if height:
            line["height"] = height
            backfilled += 1

roster_players = sum(len(r["players"]) for r in opponent_rosters)
print(f"opponent rosters: {len(opponent_rosters)} teams, {roster_players} players")
print(f"  heights applied to {backfilled} opposing box-score lines")

# --- Scheduled opponents' season form ---------------------------------------
# Box scores from a scheduled opponent's *other* matches, so the app can show
# how they have been playing before Kansas faces them. Empty before their first
# match of the season, which is exactly the gap the roster scrape above covers.
STAT_KEYS = ["sp", "k", "e", "ta", "a", "sa", "se", "d", "bs", "ba", "re", "bhe"]
form = {}  # norm team -> {"team":..., "matches": set, "players": {name: totals}}
for path in sorted(glob.glob("scraped/ncaa-opp-*.json")):
    data = load_json(path, None)
    if not data:
        continue
    box = data.get("box") or {}
    teams = {str(t.get("teamId")): t for t in box.get("teams") or []}
    for tb in box.get("teamBoxscore") or []:
        team = teams.get(str(tb.get("teamId"))) or {}
        name = team.get("nameShort") or team.get("nameFull") or ""
        key = norm_team(name)
        if not key:
            continue
        rec = form.setdefault(key, {"team": name, "matches": set(), "players": {}})
        rec["matches"].add(data.get("gameId"))
        for p in tb.get("playerStats") or []:
            if not p.get("participated"):
                continue
            player = f"{p.get('firstName','').strip()} {p.get('lastName','').strip()}".strip()
            if not player:
                continue
            totals = rec["players"].setdefault(
                player,
                {"player": player, "jerseyNumber": str(p.get("number") or ""),
                 "position": p.get("position") or "", "mp": 0,
                 **{k: 0 for k in STAT_KEYS}},
            )
            totals["mp"] += 1
            for k, v in stat_line(p).items():
                totals[k] += v

# Only teams Kansas is actually scheduled to face - the sweep can pick up others.
scheduled = {norm_team(m["opponent"]) for m in matches.values() if "teamSets" not in m}
opponent_form = [
    {
        "team": rec["team"],
        "matches": len(rec["matches"]),
        "players": sorted(rec["players"].values(), key=lambda p: -p["k"]),
    }
    for key, rec in sorted(form.items())
    if key in scheduled
]
if opponent_form:
    print(f"opponent form: {len(opponent_form)} scheduled teams with season stats")
else:
    print("opponent form: none yet (no scheduled opponent has played this season)")

seed = {
    "formatVersion": 1,
    "generatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "team": "Kansas Jayhawks Women's Volleyball",
    "players": sorted(players.values(), key=lambda p: p["name"]),
    "matches": [matches[k] for k in sorted(matches)],
}
# Additive only: formatVersion stays 1 so already-installed APKs (which reject
# anything newer) keep syncing, and older seeds without these keys stay valid.
if standings:
    seed["standings"] = standings
if polls:
    seed["polls"] = polls
if opponent_rosters:
    seed["opponentRosters"] = opponent_rosters
if opponent_form:
    seed["opponentForm"] = opponent_form

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
