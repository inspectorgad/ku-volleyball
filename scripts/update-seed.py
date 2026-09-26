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
from collections import defaultdict
import json
import os
import re
from datetime import datetime, timezone

TEAM_SEO = "kansas"
SEED_PATH = "app/src/main/assets/seed.json"


# The NCAA API sends a few names double-encoded ("InÃ©s" for "Inés"), so every
# box score read below goes through fix_tree (scripts/text_fix.py).
from text_fix import fix_tree  # noqa: E402


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
matches = {}  # match_key(date, opponent) -> match dict


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


def strip_rank(name):
    """Drops the national rank kuathletics prefixes onto a ranked opponent.

    "#4 Pittsburgh" is not a team name and changes week to week, so stored
    verbatim it forks one fixture into a fresh row on every poll - which is
    exactly what happened to Pittsburgh and Stanford the morning the 2026
    preseason poll landed.
    """
    return re.sub(r"^#\d+\s+", "", (name or "").strip())


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
        # Serves taken. Published per player in every box score - in all 90
        # team blocks captured so far the player rows sum exactly to the team
        # total - which makes a true (aces - errors) / attempts possible.
        "sat": to_int(src.get("serveAttempts")),
    }


# The roster, read early because the box-score loop needs it to tell KU's own
# players from the other side's. It is read again below, where it is the
# preferred source for number and position.
ROSTER_NAMES = {
    entry.get("name", "").strip().lower()
    for entry in load_json("scraped/roster.json", [])
    if entry.get("name", "").strip()
}


# Schools the sources call different things where no rule gets from one to the
# other. Mirrors TEAM_ALIASES in scrape-ku-volleyball.mjs, which explains why
# Southern Cal needs one.
TEAM_ALIASES = {
    "southern california": "southern cal",
    "usc": "southern cal",
}


def norm_team(name):
    """Canonical key for cross-source name matching ('Iowa State'/'Iowa St.')."""
    # Strips the NCAA's poll-vote count and kuathletics' "(Exh.)" suffix.
    n = re.sub(r"\s*\((?:\d+|[Ee]xh\.?|[Ee]xhibition)\)\s*$", "", strip_rank(name)).lower()
    n = n.replace(".", "")
    n = re.sub(r"\bstate\b", "st", n)
    n = re.sub(r"\s+", " ", n).strip()
    return TEAM_ALIASES.get(n, n)


def match_key(date, opponent):
    """How a match is identified across sources.

    The two sources spell schools differently - the NCAA box score says
    "Florida St." where kuathletics' schedule says "Florida State" - so keying
    on the raw name filed the same match twice. That is exactly what happened on
    2026-09-04: the box score arrived while the fixture was still dated today,
    and the seed published both the played match and an unplayed stub beside it.
    The feed healed itself the next day when the fixture fell out of the
    schedule, but an app that had already synced kept both rows for good,
    because matches are never deleted.
    """
    return (date, norm_team(opponent))


def div(a, b):
    """a/b, or None when there is no denominator to divide by."""
    return a / b if b else None


# --- The coaching staff's game-by-game performance goals --------------------
# The numbers live in scripts/goal-targets.json, which is the only place a
# target appears and the only file the staff need to touch to change one. What
# stays here is how each number is worked out: a goal names a metric, and these
# are the metrics.
#
# Splitting it this way because the two change for different reasons and by
# different people. A target moves when the staff decide the bar is wrong -
# which they should, since Digs/set at 15.5 has not been met once in ten
# matches. A formula changes when the sport's arithmetic does, which is
# essentially never.
TEAM_METRICS = {
    "points_per_set": lambda k, o, s: div(k["k"] + k["sa"] + k["bs"] + 0.5 * k["ba"], s),
    "errors_per_set": lambda k, o, s: div(k["e"] + k["se"] + k["bhe"], s),
    "kills_per_set": lambda k, o, s: div(k["k"], s),
    "aces_per_set": lambda k, o, s: div(k["sa"], s),
    "blocks_per_set": lambda k, o, s: div(k["bs"] + 0.5 * k["ba"], s),
    "hit_pct": lambda k, o, s: div(k["k"] - k["e"], k["ta"]),
    "opp_hit_pct": lambda k, o, s: div(o["k"] - o["e"], o["ta"]),
    "ace_to_error": lambda k, o, s: div(k["sa"], k["se"]),
    "digs_per_set": lambda k, o, s: div(k["d"], s),
    "digs_per_opp_attack": lambda k, o, s: div(k["d"], o["ta"]),
    "kill_pct": lambda k, o, s: div(k["k"], k["ta"]),
}

# A role's goals are about that player's own swings, so these take the player.
ROLE_METRICS = {
    "kill_pct": lambda p: div(p["k"], p["ta"]),
    "hit_pct": lambda p: div(p["k"] - p["e"], p["ta"]),
}


def _load_goals():
    """The goals as configured, or nothing at all if the file cannot be used.

    A goal naming a metric that does not exist is a stop, not a skip. Dropping
    it quietly would publish a tracker that is quietly missing a row, and the
    tally underneath would still look like a complete answer.
    """
    cfg = load_json("scripts/goal-targets.json", None)
    if not cfg:
        print("  WARNING: scripts/goal-targets.json missing or unreadable; "
              "no performance goals will be published")
        return [], []
    team, role = [], []
    for g in cfg.get("teamGoals", []):
        metric = g.get("metric")
        if metric not in TEAM_METRICS:
            raise SystemExit(
                f"goal-targets.json: team goal {g.get('name')!r} names unknown "
                f"metric {metric!r}; known: {', '.join(sorted(TEAM_METRICS))}"
            )
        team.append(g)
    for g in cfg.get("roleGoals", []):
        metric = g.get("metric")
        if metric not in ROLE_METRICS:
            raise SystemExit(
                f"goal-targets.json: role goal {g.get('name')!r} names unknown "
                f"metric {metric!r}; known: {', '.join(sorted(ROLE_METRICS))}"
            )
        role.append(g)
    return team, role


TEAM_GOALS, ROLE_GOALS = _load_goals()

# The goals themselves, published once at the top of the seed instead of
# repeated on all 45 matches - the names and targets are the same every night,
# and only the numbers underneath them change. A match carries a bare list of
# values in this order, so the two must not be sorted or filtered apart.
GOAL_DEFINITIONS = (
    [{"name": g["name"], "group": "team", "target": g["target"],
      "decimals": g.get("decimals", 3), "ceiling": bool(g.get("ceiling"))}
     for g in TEAM_GOALS]
    + [{"name": g["name"], "group": "role", "role": g["role"], "target": g["target"],
        "decimals": g.get("decimals", 3), "ceiling": False}
       for g in ROLE_GOALS]
)


def match_roles(players):
    """Who filled each tracker role in this match, read from that match's box score.

    Read per match rather than assumed for the season, because line-ups change.
    The box score files every pin hitter as "OH", so serve-receive load is what
    separates them: the opposite is the pin who does not pass. The outsides are
    then ranked by attack attempts, L1 carrying the primary load.
    """
    setters = [p for p in players if p["pos"].startswith("S")] or players
    roles = {"Setter": max(setters, key=lambda p: p["a"]) if setters else None}
    mbs = sorted([p for p in players if p["pos"] == "MB"], key=lambda p: -p["ta"])
    roles["M1"], roles["M2"] = (mbs + [None, None])[:2]
    pins = [p for p in players if p["pos"] == "OH"]
    non_passers = [p for p in pins if p["sp"] and p["rcp"] / p["sp"] <= 1.0]
    roles["OPP"] = max(non_passers, key=lambda p: p["ta"]) if non_passers else None
    outsides = sorted([p for p in pins if p is not roles["OPP"]], key=lambda p: -p["ta"])
    roles["L1"], roles["L2"] = (outsides + [None, None])[:2]
    return roles


def evaluate_goals(ku, opp, sets, players):
    """Every goal of the match, with the number behind it, plus the counts.

    Each goal is published rather than only the tally, because the tally alone
    says a match went 16 out of 22 without saying which two thirds. The app
    cannot work the rest out for itself: assigning the roles needs each player's
    position and serve-receive load, and neither survives into the stat lines.

    'decimals' travels with the value so a rate reads 21.33 and a percentage
    .362, the way the staff's own sheet writes them. 'ceiling' marks the two
    goals where lower is better, so a screen can say "at or below" rather than
    implying the target was a floor that got missed.
    """
    if not ku or not opp or not sets:
        return None
    roles = match_roles(players)
    values = []
    met = evaluated = team_met = team_evaluated = 0
    # Judged on the number as published, rounded the way the card writes it,
    # so the tally always agrees with the rows under it. Judging the raw value
    # counted a .2897 hitting night as a miss against .290 while the card
    # showed .290 beside a .290 target.
    for g in TEAM_GOALS:
        value = TEAM_METRICS[g["metric"]](ku, opp, sets)
        value = None if value is None else round(value, g.get("decimals", 3))
        values.append(value)
        if value is None:
            continue
        target = g["target"]
        ok = int(value <= target if g.get("ceiling") else value >= target)
        evaluated, met = evaluated + 1, met + ok
        team_evaluated, team_met = team_evaluated + 1, team_met + ok
    for g in ROLE_GOALS:
        player = roles.get(g["role"])
        value = None if not player else ROLE_METRICS[g["metric"]](player)
        value = None if value is None else round(value, g.get("decimals", 3))
        values.append(value)
        if value is None:
            continue
        evaluated, met = evaluated + 1, met + int(value >= g["target"])
    if not evaluated:
        return None
    return {
        "met": met,
        "evaluated": evaluated,
        "teamMet": team_met,
        "teamEvaluated": team_evaluated,
        # One number per goal, in GOAL_DEFINITIONS order, null where the match
        # gave the goal nothing to measure. Whether a goal was met is not stored
        # beside it: that is the value against the target, and a stored copy is
        # a second answer to a question that already has one.
        "values": values,
        # Who filled each role in this match, so a screen can say whose .158 it
        # was. Only the roles somebody actually filled.
        "roles": {r: p["name"] for r, p in roles.items() if p and p.get("name")},
    }


def team_points(mine, theirs):
    """Points a side scored, from the two box-score team blocks.

    A point comes from a kill, an ace, or an opponent's error. Blocks are not
    added: a blocked attack is already charged to the other side as an attack
    error, so counting both would double up. Net violations and the like are not
    in a box score, so this runs a point or two under the true total - close
    enough to tell the two linescore columns apart, which is all it is for.
    """
    return (
        mine.get("k", 0) + mine.get("sa", 0)
        + theirs.get("e", 0) + theirs.get("se", 0) + theirs.get("bhe", 0)
    )


_transposed_warned = set()


def ku_side(blocks, ku_team_id):
    """Which box-score block is KU's, when the teamId cannot be trusted.

    The NCAA labels each block with a teamId, and normally that is the end of
    it. On 2026-09-04 against Florida State it was not: contest 6625725 came
    back with the two player lists transposed, so the block stamped Kansas held
    Florida State's eighteen players and vice versa. Everything downstream
    believed it - the app credited KU's box score to eighteen Seminoles, added
    them to the roster, and showed the team totals the wrong way round.

    The roster settles it. A block whose names are on KU's roster is KU's,
    whatever it is stamped with. This only overrides the label when the evidence
    is one-sided: at least three roster names in the other block and a clear
    margin over the labelled one. A thin or ambiguous match changes nothing,
    which keeps older seasons - whose players have since left the roster - on
    the teamId they were always read with.
    """
    labelled = [b for b in blocks if to_int(b.get("teamId")) == ku_team_id]
    other = [b for b in blocks if to_int(b.get("teamId")) != ku_team_id]
    if len(labelled) != 1 or len(other) != 1:
        return labelled[0] if len(labelled) == 1 else None

    def roster_hits(block):
        names = {
            f"{p.get('firstName', '').strip()} {p.get('lastName', '').strip()}".strip().lower()
            for p in block.get("playerStats") or []
        }
        return len(names & ROSTER_NAMES)

    ours, theirs = roster_hits(labelled[0]), roster_hits(other[0])
    if theirs >= 3 and theirs - ours >= 2:
        # Said once per contest: the box scores are read twice, once for the
        # match record and again for cumulative team serving.
        stamp = str(other[0].get("teamId"))
        if stamp not in _transposed_warned:
            _transposed_warned.add(stamp)
            print(
                f"  WARNING: box score teams are transposed upstream; taking the "
                f"block labelled {stamp} as KU's "
                f"({theirs} roster names against {ours})"
            )
        return other[0]
    return labelled[0]


# --- Finished matches from NCAA box scores ---------------------------------
for path in sorted(glob.glob("scraped/ncaa-game-*.json")):
    data = fix_tree(load_json(path, None))
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

    # Which block is KU's is settled from the roster before anything is read off
    # the contest labels, because when those labels are crossed they are crossed
    # throughout: the sets, the winner and the home flag are as wrong as the
    # player lists were. Reading the score first and the roster second is what
    # left the Florida State match recorded as a Kansas win.
    ku_team_id = to_int(ku.get("teamId"))
    blocks = (data.get("box") or {}).get("teamBoxscore") or []
    ku_block = ku_side(blocks, ku_team_id)
    crossed = ku_block is not None and to_int(ku_block.get("teamId")) != ku_team_id
    ours_team, theirs_team = (opp, ku) if crossed else (ku, opp)

    ku_home = bool(ours_team.get("isHome"))
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
        "teamSets": to_int(ours_team.get("score")),
        "opponentSets": to_int(theirs_team.get("score")),
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
    # Each side's national rank at first serve, as the NCAA printed it on the
    # contest. Read from the team found BY NAME (ku/opp), not from ours_team/
    # theirs_team: when a contest comes back crossed, its scores and home flag
    # are crossed but its ranks are not. Florida State's is the proof - the
    # "Kansas" entry carries score 3 (KU lost 2-3) and rank 16, which was KU's
    # rank that week (#16 at Lipscomb the day before). Taking the un-crossed
    # side filed KU's own #16 as Florida State's and scored the match a loss to
    # a ranked team. NCAA tournament games print a bracket seed instead of a
    # rank; the seed is kept for the card, and the rank is filled in below from
    # that season's poll so the ranked/unranked record still counts them.
    for side, team in (("ku", ku), ("opponent", opp)):
        rank, seed = to_int(team.get("teamRank")), to_int(team.get("seed"))
        if rank:
            match[f"{side}Rank"] = rank
        if seed:
            match[f"{side}Seed"] = seed

    # Position and serve-receive load are what the goal roles are read from, and
    # neither survives into the stat line, so they are kept aside here.
    ku_players = []
    for tb in blocks:
        is_ku = tb is ku_block
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
                ku_players.append({
                    "name": name,
                    "pos": (p.get("position") or "").strip(),
                    "rcp": to_int(p.get("receptionAttempts")),
                    **line,
                })
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

    # Does the recorded result actually belong to the side whose box score we
    # just filed? Each team's points are recoverable from the two team blocks,
    # and the two linescore columns are far enough apart to say which column is
    # ours. This is the check that would have caught the Florida State match on
    # the day rather than three days later, so it runs on every match, not only
    # on one the roster flagged.
    ku_stats, opp_stats = match.get("teamStats"), match.get("opponentStats")
    lines = contest.get("linescores") or []
    if ku_stats and opp_stats and lines:
        ours_pts = team_points(ku_stats, opp_stats)
        home_total = sum(to_int(ls.get("home")) for ls in lines)
        visit_total = sum(to_int(ls.get("visit")) for ls in lines)
        chosen, other = (home_total, visit_total) if ku_home else (visit_total, home_total)
        # Only speak up when the other column is clearly the better fit: a
        # couple of points of slack is normal, being several points closer to
        # the wrong column is not.
        if abs(ours_pts - other) + 3 <= abs(ours_pts - chosen):
            print(
                f"  WARNING: {match['date']} {match['opponent']}: KU's box score "
                f"scores {ours_pts} points but the column recorded as KU's totals "
                f"{chosen} and the other totals {other}. Recorded "
                f"{match['teamSets']}-{match['opponentSets']}; the sides may be "
                f"crossed upstream."
            )

    goals = evaluate_goals(
        ku_stats, opp_stats,
        (match["teamSets"] or 0) + (match["opponentSets"] or 0),
        ku_players,
    )
    if goals:
        match["goals"] = goals

    matches[match_key(match["date"], match["opponent"])] = match

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
    opponent = strip_rank(entry.get("opponent"))
    if not date or not opponent or date < today:
        continue
    key = match_key(date, opponent)
    if key in matches:
        continue
    fixture = {
        "date": date,
        "opponent": opponent,
        "season": date[:4],
        # kuathletics writes "versus" for home and "at" for road games.
        "home": bool(entry.get("home")),
    }
    # Start time (24-hour, Central, as kuathletics lists it) and broadcast, when
    # the schedule gives them. Late-season fixtures often have no time yet, and
    # they get none here rather than a guess.
    for field in ("venue", "city", "time", "tv"):
        if entry.get(field):
            fixture[field] = entry[field]
    # "versus" only means KU is the designated home team, which at an early-season
    # event is a neutral floor: KU is listed "vs Stanford" for a match played in
    # Pittsburgh. Where the schedule gave a city, hand the flag to the
    # home/away/neutral pass below so the venue decides. Without a city there is
    # nothing to decide from, so "versus" is left to stand for home on its own.
    if fixture.get("city"):
        fixture["_kuDesignatedHome"] = fixture["home"]
    matches[key] = fixture

# A fixture we already knew about is kept even when this scrape did not see it.
# kuathletics renders its schedule rows progressively, and a capture taken a beat
# early returns a short list: one run silently dropped Wichita State on Sept 15
# and Ole Miss on Sept 18, both still weeks away. Rebuilding the seed from that
# run deleted them outright, so the dashboard and every fresh install lost two
# real fixtures.
#
# Only future, result-less matches are carried, and only for a season the scrape
# actually reported, so this cannot resurrect anything from a season that has
# been dropped deliberately. A cancelled match lingering until someone notices is
# a far smaller error than a real one vanishing without trace.
seasons_seen = {m["season"] for m in matches.values()}
carried = []
for match in previous_seed.get("matches", []):
    date = match.get("date", "")
    # Stripped here too, so a rank that a previous run stored into the seed
    # collapses onto the clean fixture instead of being carried forward beside
    # it for the rest of the season.
    opponent = strip_rank(match.get("opponent"))
    if not date or not opponent or date < today:
        continue
    # A played match comes from the NCAA sweep, not the schedule page, so it is
    # never a candidate. It carries a set score and box-score lines; today's
    # match is dated today and so survives the date filter above, which is why
    # this is checked on the data rather than on the date alone.
    if match.get("teamSets") is not None or match.get("lines"):
        continue
    if match.get("season") not in seasons_seen:
        continue
    key = match_key(date, opponent)
    if key in matches:
        continue
    matches[key] = {**match, "opponent": opponent}
    carried.append(f"{date} {opponent}")
if carried:
    print(f"  carried forward {len(carried)} fixture(s) this scrape did not "
          f"report: {', '.join(carried)}")

# --- Where a played match was played, when the box score does not say --------
# The NCAA box score is authoritative on what happened. It is not always
# authoritative on where: both Opening Spike Classic contests came back with an
# empty location object. A played match is rebuilt from the box score alone, so
# the venue and city the schedule table had already recorded were overwritten
# with blanks - and with them the only evidence that Stanford, on a Pittsburgh
# floor, was a neutral game rather than a road trip to Palo Alto. The match read
# as "at Stanford" in the app.
#
# kuathletics drops a row from its schedule table once the match is played, so
# the previous seed is the only place that location still exists. Facts fill in
# and are never overwritten, which is the rule everywhere else in this pipeline;
# a blank is not a correction.
previous_by_key = {
    match_key(m.get("date", ""), m.get("opponent") or ""): m
    for m in previous_seed.get("matches", [])
}
relocated = []
for key, m in matches.items():
    if m.get("venue") or m.get("city"):
        continue
    was = previous_by_key.get(key)
    if not was or not (was.get("venue") or was.get("city")):
        continue
    for field in ("venue", "city"):
        if was.get(field):
            m[field] = was[field]
    # "vs" in the schedule table means KU was the designated home team, and that
    # is what separates a neutral floor from a road game. The flag itself is
    # consumed when the seed is written, but its conclusion survives: a row that
    # came out home or neutral was designated home, an away row was not. Taking
    # it from here rather than from the box score is deliberate - the NCAA still
    # names one side the home team at a neutral event, which is exactly why this
    # pipeline does not decide the site from that flag.
    m["_kuDesignatedHome"] = bool(was.get("home") or was.get("neutral"))
    relocated.append(f"{m['date']} {m['opponent']}")
if relocated:
    print(f"  location carried forward for {len(relocated)} played match(es) the "
          f"box score left blank: {', '.join(relocated)}")

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
        venue_opponents.setdefault((m["season"], venue), set()).add(norm_team(m["opponent"]))

for m in matches.values():
    if "_kuDesignatedHome" not in m:
        continue  # nothing to decide from: no venue was recorded for this match
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


# The column names are not stable. The in-season poll labels the team column
# TEAM and carries RECORD and PREVIOUS; the preseason poll labels it SCHOOL and
# ships neither, since nobody has a record yet. The RPI endpoint uses School.
# Matching on the name ignoring case and spacing survives all three, and a
# missing column reads as absent rather than as an empty team name.
def col(row, *names):
    flat = {re.sub(r"[^a-z]", "", k.lower()): v for k, v in row.items()}
    for name in names:
        value = flat.get(re.sub(r"[^a-z]", "", name.lower()))
        if value not in (None, ""):
            return str(value).strip()
    return ""


avca = load_json("scraped/rankings-avca.json", {})
rpi = load_json("scraped/rankings-rpi.json", {})

rpi_season = snapshot_season(rpi)
rpi_by_team = {}
for row in rpi.get("data", []):
    if col(row, "Conf", "Conference") == "Big 12":
        rpi_by_team[norm_team(col(row, "School", "Team"))] = row

# RPI carries each team's official overall record — fold in the RPI rank and
# cross-check our computed record against it (a warning, never a failure: the
# snapshot and our sweep can legitimately sit a game apart mid-season).
for (season, key), rec in records.items():
    row = rpi_by_team.get(key)
    if not row or season != rpi_season:
        continue
    rpi_rank = col(row, "Rank")
    rec["rpiRank"] = int(rpi_rank) if rpi_rank.isdigit() else None
    official = col(row, "Record")
    ours = f"{rec['overallW']}-{rec['overallL']}"
    if official and official != ours:
        print(f"  cross-check: {rec['team']} computed {ours} vs RPI {official}")

# --- RPI for every season we can rate -----------------------------------------
# The NCAA's table where it covers the season, and until it does - it serves
# last season's final RPI for weeks into the new one - a provisional one worked
# out from every Division I result the sweep has seen (scripts/resume.py).
# Either way each table is keyed by normalised name, with where it came from.
from resume import team_games, compute_rpi, rank_rpi, common_opponents, fit_line, blend  # noqa: E402

rpi_tables = {}  # season -> {"source", "updated", "ranks": {key: (rank, record)}}
if rpi.get("data") and rpi_season:
    rpi_tables[rpi_season] = {
        "source": "ncaa",
        "updated": rpi.get("updated") or "",
        "ranks": {
            norm_team(col(r, "School", "Team")): (
                int(col(r, "Rank")) if col(r, "Rank").isdigit() else None, col(r, "Record"))
            for r in rpi["data"]
        },
    }
# Division I is whoever the NCAA's table lists - any season's, since the
# membership barely moves - so a non-D1 opponent's game stays out of the rating.
d1_names = {norm_team(col(r, "School", "Team")) for r in rpi.get("data", [])}
d1_raw = load_json("scraped/d1-results.json", {})
# The scoreboard sometimes lists one match under two game IDs (Jacksonville St.
# 0-3 Southern Miss. on Aug 29 appears as 6625557 and 6640466), which would
# count it twice. Same day, same two teams, same score is taken as one match;
# a genuine double-header with an identical score is rare enough to accept.
d1_games, _seen = [], set()
for g in sorted((d1_raw.get("games") or {}).items()):
    row = [g[1][0], norm_team(g[1][1]), g[1][2], norm_team(g[1][3]), g[1][4]]
    key = (row[0],) + tuple(sorted([(row[1], row[2]), (row[3], row[4])]))
    if key in _seen:
        continue
    _seen.add(key)
    d1_games.append(row)
current_season = max((m["season"] for m in matches.values()), default=None)
# Computed whether or not the NCAA has published: the win model below rates
# unranked teams from these values, and the NCAA's table carries ranks only.
computed_rpi, d1_games_played = {}, {}
if d1_games and d1_raw.get("season") == current_season:
    is_d1 = (lambda t: t in d1_names) if d1_names else (lambda t: True)
    by_team_games = team_games(d1_games, is_d1)
    computed_rpi = compute_rpi(by_team_games)
    d1_games_played = {t: len(g) for t, g in by_team_games.items()}
if computed_rpi and current_season not in rpi_tables:
    ranked = rank_rpi(computed_rpi)
    through = max(g[0] for g in d1_games)
    rpi_tables[current_season] = {
        "source": "provisional",
        "updated": f"Provisional, through games {through}",
        "ranks": {t: (r, f"{w}-{l}") for t, r, _v, w, l in ranked},
    }
    ku_row = next((row for row in ranked if row[0] == "kansas"), None)
    print(f"provisional RPI {current_season}: {len(ranked)} D1 teams from {len(d1_games)} results"
          + (f"; Kansas #{ku_row[1]} ({ku_row[3]}-{ku_row[4]})" if ku_row else ""))

for (season, key), rec in records.items():
    table = rpi_tables.get(season)
    if table and key in table["ranks"]:
        rec["rpiRank"] = table["ranks"][key][0]
        rec["rpiSource"] = table["source"]

# Polls accumulate: each snapshot covers one season, and the endpoint only ever
# serves the current one, so last season's final poll has to be carried forward
# or it is lost the day the new preseason poll appears. Losing it is not cosmetic
# - the poll is what keeps a season's national ranks honest (see below).
polls_by_season = {p["season"]: p for p in previous_seed.get("polls", [])}

avca_season = snapshot_season(avca)
if avca.get("data") and avca_season:
    # Membership comes from every season we hold, not just the poll's own. A
    # preseason poll arrives before a single conference game has been played, so
    # scoping this to the poll's season flagged the whole Big 12 as non-members.
    # The cost is that a departing school keeps its flag until its last season
    # ages out of the data, which is the lesser error of the two.
    b12_keys = {k for (_s, k) in records}
    rows = []
    for row in avca["data"]:
        label = col(row, "RANK")  # can be a tie, e.g. "T-22."
        digits = re.search(r"\d+", label)
        school = col(row, "SCHOOL", "TEAM")
        team = re.sub(r"\s*\(\d+\)\s*$", "", school).strip()
        votes = re.search(r"\((\d+)\)\s*$", school)
        rows.append({
            "rank": int(digits.group()) if digits else 0,
            "rankLabel": label.rstrip("."),
            "team": team,
            "record": col(row, "RECORD"),
            "points": col(row, "TOTAL POINTS"),
            "previous": col(row, "PREVIOUS", "PREVIOUS RANK"),
            "firstPlaceVotes": int(votes.group(1)) if votes else 0,
            "big12": norm_team(team) in b12_keys,
        })
    named = sum(1 for r in rows if r["team"])
    if named < len(rows):
        # Refuse a poll we clearly failed to read rather than storing blank rows
        # for the app to display.
        print(f"  WARNING: AVCA poll {avca_season}: only {named}/{len(rows)} "
              f"rows carried a team name; keeping the previous poll instead")
    else:
        polls_by_season[avca_season] = {
            "season": avca_season,
            "name": "AVCA Coaches Poll",
            "updated": (avca.get("updated") or "").strip(),
            "rows": rows,
        }
        print(
            f"  AVCA poll {avca_season}: {len(rows)} teams, "
            f"{sum(1 for r in rows if r['big12'])} from the Big 12"
        )

polls = [polls_by_season[s] for s in sorted(polls_by_season)]

# Tournament games print a seed and no rank, so their ranks come from that
# season's poll as captured - for a finished season, its final poll. That poll
# postdates the tournament, so it is the nearest thing on disk rather than the
# rank at first serve; it is used only to count the ranked/unranked record, and
# the card still shows the seed the NCAA printed.
#
# Kansas's own rank is better than that: it is carried forward from its last
# box score before the tournament, which is the rank it took into it. The
# final poll had KU 14th after a tournament it entered 13th.
last_ku_rank = {}
for match in sorted(matches.values(), key=lambda m: m["date"]):
    season = match.get("season")
    if match.get("kuSeed") and not match.get("kuRank") and last_ku_rank.get(season):
        match["kuRank"] = last_ku_rank[season]
    elif match.get("kuRank"):
        last_ku_rank[season] = match["kuRank"]
for match in matches.values():
    season_poll = polls_by_season.get(match.get("season"))
    if not season_poll:
        continue
    ranks = {norm_team(r.get("team", "")): to_int(r.get("rank")) for r in season_poll.get("rows", [])}
    for side, name in (("ku", "Kansas"), ("opponent", match.get("opponent", ""))):
        if match.get(f"{side}Seed") and not match.get(f"{side}Rank"):
            rank = ranks.get(norm_team(name))
            if rank:
                match[f"{side}Rank"] = rank

# The poll is the authoritative ranking. The scoreboard's per-game rank is only
# "the rank this team carried in that game", so a team that fell out of the top
# 25 would otherwise keep a stale number forever (Utah looked like a final #23
# while actually finishing unranked). Each season is restated from its own poll:
# absent from that poll means unranked.
restated = 0
for poll in polls:
    poll_rank = {norm_team(r["team"]): r["rank"] for r in poll["rows"]}
    for (season, key), rec in records.items():
        if season != poll["season"]:
            continue
        fresh = poll_rank.get(key)
        if fresh != rec["nationalRank"]:
            restated += 1
        rec["nationalRank"] = fresh
if restated:
    print(f"  national ranks restated from the poll for {restated} teams")

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
STAT_KEYS = ["sp", "k", "e", "ta", "a", "sa", "se", "d", "bs", "ba", "re", "bhe", "sat"]
# Roster names per team, for telling which side of another team's box score is
# which. The NCAA sometimes sends a contest back with the two teams' player
# blocks under each other's labels - Florida State against Kansas was the first
# seen, and five captures have it, putting Denver's players under Utah, Weber
# St.'s under Kansas St. and Utah St.'s under Iowa St. Filed by label, each of
# those scheduled opponents picked up a second team's worth of players (Utah 37
# against an 18-player roster) and a second team's serving.
_OPP_ROSTER_NAMES = {
    key: {p.get("name", "").strip().lower() for p in (rec.get("players") or [])}
    for key, rec in (load_json("scraped/opponent-rosters.json", {}) or {}).items()
}
_OPP_ROSTER_NAMES.setdefault(norm_team("Kansas"), set()).update(ROSTER_NAMES)
_crossed_warned = set()


def labelled_blocks(path, data):
    """(team name, block) for each side of a capture, un-crossing a swapped one.

    Swapped only on strong evidence, the same bar the KU transposition guard
    uses: at least three roster names on the crossed reading, and at least two
    more than on the labelled one. Where neither team's roster is known the
    labels stand, because guessing would be worse than trusting them.
    """
    box = data.get("box") or {}
    blocks = box.get("teamBoxscore") or []
    by_id = {str(t.get("teamId")): t for t in box.get("teams") or []}
    def name_of(b):
        t = by_id.get(str(b.get("teamId"))) or {}
        return t.get("nameShort") or t.get("nameFull") or ""
    pairs = [(name_of(b), b) for b in blocks]
    if len(pairs) != 2:
        return pairs

    def hits(block, team):
        roster = _OPP_ROSTER_NAMES.get(norm_team(team), set())
        return len(roster & {
            f"{p.get('firstName', '').strip()} {p.get('lastName', '').strip()}".strip().lower()
            for p in block.get("playerStats") or []
        })
    (na, a), (nb, b) = pairs
    straight = hits(a, na) + hits(b, nb)
    crossed = hits(a, nb) + hits(b, na)
    if crossed >= 3 and crossed - straight >= 2:
        if path not in _crossed_warned:
            _crossed_warned.add(path)
            print(f"  WARNING: {os.path.basename(path)}: {na} and {nb} blocks are crossed "
                  f"upstream ({crossed} roster names crossed against {straight}); swapping")
        return [(nb, a), (na, b)]
    return pairs


form = {}  # norm team -> {"team":..., "matches": set, "players": {name: totals}}
for path in sorted(glob.glob("scraped/ncaa-opp-*.json")):
    data = fix_tree(load_json(path, None))
    if not data:
        continue
    for name, tb in labelled_blocks(path, data):
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
            # Keyed without case: the NCAA spells one player "Ndam-Simpson" in
            # some box scores and "Ndam-simpson" in others, which split her
            # season in two and showed 74 of her 110 kills.
            totals = rec["players"].setdefault(
                player.lower(),
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

# --- Cumulative team serving for the Big 12 and the poll ---------------------
# Serve faults on their own flatter whoever has played least, so each row also
# carries what it took to earn them: aces, attempts and sets. Attempts are the
# honest denominator and the team block is the only place they exist - a player
# row publishes aces and errors but never serves taken, which is why the app's
# per-player SRV divides by sets instead. Here the real figure is available, so
# the app can show both a serving percentage and errors per set.
#
# Read from teamStats rather than summed from the player rows: the totals are
# recorded, and reception errors are sometimes charged to the team rather than
# to anybody in particular.
SERVE_TRACKED_SEASON = str(max(int(m["season"]) for m in matches.values()))


def serving_blocks(path, data):
    """Each team block in a capture, paired with the team it belongs to.

    For a KU game the transposition guard decides which block is KU's, so a
    contest the NCAA sent back the wrong way round is credited correctly. Another
    team's game gets the same treatment from the opponent rosters, through
    labelled_blocks; where no roster is known for either side the label stands.
    """
    box = data.get("box") or {}
    blocks = box.get("teamBoxscore") or []
    by_id = {str(t.get("teamId")): t for t in box.get("teams") or []}
    names = {
        str(b.get("teamId")): (by_id.get(str(b.get("teamId"))) or {}).get("nameShort")
        or (by_id.get(str(b.get("teamId"))) or {}).get("nameFull")
        or ""
        for b in blocks
    }
    if "ncaa-game-" in path and len(blocks) == 2:
        contests = (data.get("info") or {}).get("contests") or []
        teams = (contests[0].get("teams") or []) if contests else []
        ku = next((t for t in teams if t.get("seoname") == TEAM_SEO), None)
        opp = next((t for t in teams if t.get("seoname") != TEAM_SEO), None)
        if ku and opp:
            ku_block = ku_side(blocks, to_int(ku.get("teamId")))
            ku_name = ku.get("nameShort") or ku.get("nameFull") or ""
            opp_name = opp.get("nameShort") or opp.get("nameFull") or ""
            return [
                (ku_name if b is ku_block else opp_name, b) for b in blocks
            ]
    return labelled_blocks(path, data)


serving = {}  # norm team -> totals
for path in sorted(glob.glob("scraped/ncaa-opp-*.json") + glob.glob("scraped/ncaa-game-*.json")):
    data = fix_tree(load_json(path, None))
    if not data:
        continue
    season = str(data.get("season") or (data.get("date") or "")[:4])
    if season != SERVE_TRACKED_SEASON:
        continue
    game_id = str(data.get("gameId") or path)
    for name, block in serving_blocks(path, data):
        key = norm_team(name)
        if not key:
            continue
        rec = serving.setdefault(
            key,
            {"team": name, "_games": set(), "sets": 0, "sa": 0, "se": 0, "att": 0},
        )
        # A scheduled opponent's game can be captured as both ncaa-opp and
        # ncaa-game (KU's own matches are in both queues), so count each contest
        # once per team rather than once per file.
        if game_id in rec["_games"]:
            continue
        rec["_games"].add(game_id)
        stats = block.get("teamStats") or {}
        rec["sets"] += to_int(stats.get("gamesPlayed"))
        rec["sa"] += to_int(stats.get("serviceAces"))
        rec["se"] += to_int(stats.get("serviceErrors"))
        rec["att"] += to_int(stats.get("serveAttempts"))

# Published for the Big 12 and for anybody who has been in this season's poll.
# A team that has since dropped out keeps its row: it is still a team the app
# has a full season for, and losing the row the week it falls to 26th would be
# odd. The poll accumulates by season, so "has been ranked" is what we can
# answer honestly.
b12_serve = {norm_team(r["team"]) for r in standings if r["season"] == SERVE_TRACKED_SEASON}
polled_serve = {
    norm_team(row["team"])
    for poll in polls
    if poll["season"] == SERVE_TRACKED_SEASON
    for row in poll["rows"]
}
poll_rank = {
    norm_team(row["team"]): row["rank"]
    for poll in polls
    if poll["season"] == SERVE_TRACKED_SEASON
    for row in poll["rows"]
}
team_serving = []
for key in sorted(b12_serve | polled_serve):
    rec = serving.get(key)
    if not rec or not rec["_games"]:
        continue
    row = {
        "team": rec["team"],
        "season": SERVE_TRACKED_SEASON,
        "matches": len(rec["_games"]),
        "sets": rec["sets"],
        "serviceAces": rec["sa"],
        "serviceErrors": rec["se"],
        "serveAttempts": rec["att"],
        "big12": key in b12_serve,
    }
    if key in poll_rank:
        row["pollRank"] = poll_rank[key]
    team_serving.append(row)

if team_serving:
    wanted = b12_serve | polled_serve
    missing = sorted(k for k in wanted if k not in serving or not serving[k]["_games"])
    print(
        f"team serving: {len(team_serving)} of {len(wanted)} tracked teams "
        f"({sum(1 for r in team_serving if r['big12'])} Big 12, "
        f"{sum(1 for r in team_serving if 'pollRank' in r)} ranked)"
    )
    if missing:
        # Loud, because the usual cause is a name the poll and the NCAA spell
        # differently rather than a team that has genuinely not played.
        print(f"  WARNING: no serving data captured for: {', '.join(missing)}")
    thin = [r["team"] for r in team_serving if r["matches"] < 3]
    if thin:
        print(f"  only one or two matches captured so far for: {', '.join(thin)}")

# --- Estimated win probability for the matches still to play ----------------
# The win model, moved out of the workbook it was built in so the number is on
# the schedule rather than in a file someone has to open.
#
#   Win % = 1 / (1 + 10 ^ (-gap / scale)),  gap = kansas + venue - opponent
#
# A forecast, so it is attached only to matches with no result: once a match is
# played the question it answers has been answered on the floor. The ratings are
# subjective and static, which scripts/power-ratings.json says at length; what
# the model is worth is set there, not here.
ratings = load_json("scripts/power-ratings.json", {})
teams_rated = {norm_team(k): v for k, v in (ratings.get("teams") or {}).items()}

# A ranked team is rated by the poll rather than by hand, so its number moves
# the Monday a new poll lands instead of whenever someone remembers to edit it.
# Points are read as a share of the most any team could have scored - every
# ballot carries exactly one first-place vote, so the first-place votes count
# the ballots - which keeps the map steady if the panel of coaches changes size.
poll_rating = {}
poll_floor = None
poll_map = ratings.get("pollMap") or {}
# polls are ordered by season, so the last one with rows is the current one.
current_poll = next((p for p in reversed(polls) if p.get("rows")), None)
if current_poll and poll_map:
    ballots = sum(to_int(r.get("firstPlaceVotes")) for r in current_poll["rows"])
    most = ballots * 25
    if most:
        for row in current_poll["rows"]:
            share = to_int(row.get("points")) / most
            value = poll_map.get("base", 0) + poll_map.get("perShare", 0) * share
            poll_rating[norm_team(row.get("team") or "")] = round(value, 2)
        poll_floor = min(poll_rating.values())


# A team the poll does not rate is rated from its results as well as from the
# preseason number, once it has played enough to say anything. The RPI is put
# on the model's scale by a straight-line fit to the poll-rated teams, the one
# set of ratings the model already trusts, and then blended with the preseason
# rating by games played: at `priorGames` games it is half each, and it leans
# further on results as the season goes. The fit needs enough ranked teams
# with an RPI to mean anything, or the preseason ratings stand alone.
blend_cfg = ratings.get("rpiBlend") or {}
prior_games = blend_cfg.get("priorGames", 10)
min_games = blend_cfg.get("minGames", 5)
rpi_fit = None
pairs = [(computed_rpi[k][0], v) for k, v in poll_rating.items() if k in computed_rpi]
if len(pairs) >= 10:
    rpi_fit = fit_line(pairs)
    if rpi_fit:
        print(f"win model: RPI mapped to ratings as {rpi_fit[0]:.2f} + {rpi_fit[1]:.2f} x RPI "
              f"(fit to {len(pairs)} poll-rated teams)")


def results_rating(key):
    """(rating from results, games played) or (None, 0) when there is too little to go on."""
    games = d1_games_played.get(key, 0)
    if rpi_fit is None or key not in computed_rpi or games < min_games:
        return None, games
    return rpi_fit[0] + rpi_fit[1] * computed_rpi[key][0], games


def rating_for(team):
    """This team's power rating, and where it came from."""
    key = norm_team(team)
    if key in poll_rating:
        return poll_rating[key], "poll"
    manual = teams_rated.get(key)
    from_results, games = results_rating(key)
    if from_results is not None:
        value = blend(from_results, games, manual["rating"] if manual else None, prior_games)
        if poll_floor is not None:
            value = min(value, poll_floor)
        return round(value, 2), "results"
    if not manual:
        return None, None
    # Capped at the poll's last-placed team: a rating set before the season
    # should not leave a team that the coaches have dropped - or never ranked -
    # sitting above one they still rank.
    if poll_floor is not None:
        return min(manual["rating"], poll_floor), "file (capped at the poll floor)"
    return manual["rating"], "file"


if teams_rated or poll_rating:
    scale = ratings.get("scale") or 25
    ku_rating, ku_source = rating_for("Kansas")
    if ku_rating is None:
        ku_rating, ku_source = ratings.get("kansas") or 0, "file"
    forecast = unrated = 0
    for match in matches.values():
        if match.get("teamSets") is not None or match.get("opponentSets") is not None:
            continue
        opponent_rating, opponent_source = rating_for(match["opponent"])
        if opponent_rating is None:
            unrated += 1
            continue
        if match.get("neutral"):
            venue = ratings.get("neutralAdjustment", 0)
        elif match.get("home"):
            venue = ratings.get("homeAdjustment", 0)
        else:
            venue = ratings.get("roadAdjustment", 0)
        gap = ku_rating + venue - opponent_rating
        match["winProbability"] = round(1 / (1 + 10 ** (-gap / scale)), 4)
        # Said per match so a screen can own up to how many of its forecasts
        # still rest on a rating set by hand before the season.
        match["ratingSource"] = (
            "poll" if opponent_source == "poll"
            else "results" if opponent_source == "results" else "preseason")
        forecast += 1
    by_source = {}
    for m in matches.values():
        if m.get("winProbability") is not None:
            by_source[m["ratingSource"]] = by_source.get(m["ratingSource"], 0) + 1
    print(f"win model: {forecast} upcoming match(es) rated "
          f"({', '.join(f'{n} from {src}' for src, n in sorted(by_source.items()))}); "
          f"Kansas {ku_rating} from the {ku_source}"
          + (f"; {unrated} with no rating for the opponent" if unrated else ""))

# --- Forecast log --------------------------------------------------------------
# The forecast is dropped from a match once it is played, which is right for the
# schedule but leaves nothing to grade the model by. So the last forecast made
# before first serve is kept here, and a played match carries it as "forecast".
from zoneinfo import ZoneInfo  # noqa: E402

FORECAST_LOG = "scraped/forecasts.json"
forecast_log = load_json(FORECAST_LOG, {})
now_utc = datetime.now(timezone.utc)
central = ZoneInfo("America/Chicago")
for key, match in matches.items():
    if match.get("teamSets") is not None or match.get("winProbability") is None:
        continue
    # Only while the match is still ahead. With no published time, noon.
    hh, mm = (match.get("time") or "12:00").split(":")
    y, mo, d = (int(x) for x in match["date"].split("-"))
    first_serve = datetime(y, mo, d, int(hh), int(mm), tzinfo=central)
    if first_serve <= now_utc:
        continue
    forecast_log[f"{match['date']}|{norm_team(match['opponent'])}"] = {
        "date": match["date"], "opponent": match["opponent"],
        "p": match["winProbability"], "source": match.get("ratingSource"),
        "recordedAt": now_utc.strftime("%Y-%m-%dT%H:%M:%SZ"),
    }
graded = 0
for match in matches.values():
    entry = forecast_log.get(f"{match['date']}|{norm_team(match['opponent'])}")
    if entry and match.get("teamSets") is not None:
        match["forecast"] = entry["p"]
        graded += 1
# Rewritten only when a forecast moved, so recordedAt alone is no commit.
old_log = load_json(FORECAST_LOG, {})
strip_at = lambda log: {k: {kk: vv for kk, vv in v.items() if kk != "recordedAt"} for k, v in log.items()}
if strip_at(old_log) != strip_at(forecast_log):
    with open(FORECAST_LOG, "w") as f:
        json.dump(dict(sorted(forecast_log.items())), f, indent=1)
        f.write("\n")
print(f"forecast log: {len(forecast_log)} forecasts kept, {graded} played match(es) graded")

# --- Resume and common opponents -------------------------------------------------
# Each played match carries its opponent's RPI rank for that season (the current
# table, not the rank on the night: quality wins are judged on where teams end up).
for match in matches.values():
    table = rpi_tables.get(match["season"])
    if table and match.get("teamSets") is not None:
        rank = table["ranks"].get(norm_team(match["opponent"]), (None,))[0]
        if rank:
            match["opponentRpi"] = rank

if current_season and d1_games:
    def label(us, them):
        return f"{'W' if us > them else 'L'} {us}-{them}"
    ku_vs, ku_names = defaultdict(list), {}
    for m in sorted(matches.values(), key=lambda m: m["date"]):
        if m["season"] == current_season and m.get("teamSets") is not None:
            k = norm_team(m["opponent"])
            ku_vs[k].append(label(m["teamSets"], m["opponentSets"]))
            ku_names[k] = m["opponent"]
    their_vs = defaultdict(lambda: defaultdict(list))
    for date, home, hs, away, as_ in sorted(d1_games):
        if hs == as_:
            continue
        their_vs[home][away].append(label(hs, as_))
        their_vs[away][home].append(label(as_, hs))
    with_common = 0
    for m in matches.values():
        if m["season"] != current_season or m.get("teamSets") is not None:
            continue
        opp = norm_team(m["opponent"])
        shared = common_opponents(ku_vs, their_vs.get(opp, {}), exclude=("kansas", opp))
        if shared:
            m["commonOpponents"] = [
                {"team": ku_names[t], "ku": ", ".join(k), "them": ", ".join(th)}
                for t, k, th in shared
            ]
            with_common += 1
    print(f"common opponents: {with_common} upcoming match(es) share an opponent with KU")

if rpi_tables.get(current_season):
    rpi_note = {"season": current_season, "source": rpi_tables[current_season]["source"],
                "updated": rpi_tables[current_season]["updated"]}
else:
    rpi_note = None

seed = {
    "formatVersion": 1,
    "generatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "team": "Kansas Jayhawks Women's Volleyball",
    "players": sorted(players.values(), key=lambda p: p["name"]),
    "matches": [matches[k] for k in sorted(matches)],
}
# Only worth publishing if some match actually carries values against them.
if any(m.get("goals") for m in matches.values()):
    seed["goalDefinitions"] = GOAL_DEFINITIONS
# --- National individual leaders -------------------------------------------
# The NCAA's own top 50 per category, across all of Division I. Worth carrying
# because our box scores cannot produce it: we capture 34 teams in full, so a
# leader at an unranked school never appears in them at all - the current kills
# leader plays for LSU, the aces leader for Harvard.
#
# Rows are normalised to one shape because the columns are not: every category
# ends with its headline number, but that column is "Per Set" for a rate, "Pct."
# for hitting percentage and the stat's own name for a season total. The last
# column is the headline in all sixteen, so that is what is read, and its label
# is carried alongside the value so a screen can title the column correctly.
#
# A snapshot with no history, like the polls: each run overwrites it, and the
# season comes from the "Through games ..." label rather than from today's date,
# so a capture taken in January still files under the season it describes.
leaders_raw = load_json("scraped/ncaa-leaders.json", {})
national_leaders = None
FIXED = ("Rank", "Name", "Team", "Cl", "Height", "Position", "S")
categories = []
for cat in leaders_raw.get("categories", []):
    rows = cat.get("rows") or []
    if not rows:
        continue
    columns = list(rows[0].keys())
    value_label = columns[-1] if columns else ""
    out_rows = []
    # The NCAA writes the rank once per group of ties and "-" on the rows that
    # share it: on Total Kills, Mallory Reck's 162 ties Victoria Marthaler's, so
    # Reck's rank column reads "-" and she is also fourth. Read literally that
    # is a rank of zero, which is how 209 of these 763 rows first came through.
    # The rank therefore carries forward, and since it then repeats, the row's
    # place in the published list is what identifies it.
    last_rank = 0
    for idx, row in enumerate(rows):
        rank = to_int(col(row, "Rank")) or last_rank
        last_rank = rank
        out_rows.append({
            "idx": idx,
            "rank": rank,
            "player": col(row, "Name"),
            "team": col(row, "Team"),
            "cls": col(row, "Cl"),
            "height": col(row, "Height"),
            "position": col(row, "Position"),
            "sets": to_int(col(row, "S")),
            "value": (row.get(value_label) or "").strip(),
        })
    categories.append({
        "id": str(cat.get("id") or ""),
        "name": cat.get("name") or cat.get("title") or "",
        "valueLabel": value_label,
        "rows": out_rows,
    })
if categories:
    season = snapshot_season({"updated": leaders_raw["categories"][0].get("updated", "")})
    national_leaders = {
        "season": season or str(datetime.now(timezone.utc).year),
        "updated": leaders_raw["categories"][0].get("updated") or "",
        "categories": categories,
    }
    total = sum(len(c["rows"]) for c in categories)
    print(f"national leaders: {len(categories)} categories, {total} rows "
          f"(season {national_leaders['season']})")
elif leaders_raw:
    print("  WARNING: ncaa-leaders.json present but no category had rows")

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
if team_serving:
    seed["teamServing"] = team_serving
if national_leaders:
    seed["nationalLeaders"] = national_leaders
if rpi_note:
    seed["rpi"] = rpi_note

os.makedirs(os.path.dirname(SEED_PATH), exist_ok=True)

# Skip the write when nothing but a timestamp would change, so the nightly job
# doesn't commit (and rebuild and republish the APK) on quiet days.
#
# Two timestamps have to be ignored, not one. Each roster carries the moment it
# was fetched, and the weekly refresh rewrites that even when the school's page
# is unchanged - which republished the whole app for two altered timestamps and
# nothing else. Nothing reads the seed's copy of fetchedAt (the scraper decides
# refresh timing from scraped/opponent-rosters.json), so it is informational
# only and cannot on its own justify a build.
def without_timestamps(seed_obj):
    trimmed = {k: v for k, v in seed_obj.items() if k != "generatedAt"}
    rosters = trimmed.get("opponentRosters")
    if isinstance(rosters, list):
        trimmed["opponentRosters"] = [
            {k: v for k, v in r.items() if k != "fetchedAt"} if isinstance(r, dict) else r
            for r in rosters
        ]
    return trimmed


previous = load_json(SEED_PATH, {})
current_cmp = without_timestamps(seed)
previous_cmp = without_timestamps(previous)
if current_cmp == previous_cmp:
    print("seed.json unchanged (ignoring timestamps); not rewriting")
else:
    with open(SEED_PATH, "w") as f:
        json.dump(seed, f, indent=1)
        f.write("\n")
    print(
        f"seed.json written: {len(seed['players'])} players, "
        f"{len(seed['matches'])} matches "
        f"({sum(1 for m in seed['matches'] if 'teamSets' in m)} with results)"
    )

# --- Calendar feed for iPhones -------------------------------------------------
# Written beside the dashboard so GitHub Pages serves it, and rewritten only when
# a match changed: the DTSTAMP alone moving is not worth a commit.
from ics_feed import build_ics, same_apart_from_stamp  # noqa: E402

ICS_PATH = "docs/ku-volleyball.ics"
latest_season = max((m["season"] for m in seed["matches"]), default=None)
if latest_season:
    ics = build_ics([m for m in seed["matches"] if m["season"] == latest_season],
                    datetime.now(timezone.utc))
    try:
        with open(ICS_PATH, newline="") as f:
            old_ics = f.read()
    except FileNotFoundError:
        old_ics = None
    if same_apart_from_stamp(ics, old_ics):
        print("calendar unchanged; not rewriting")
    else:
        os.makedirs(os.path.dirname(ICS_PATH), exist_ok=True)
        with open(ICS_PATH, "w", newline="") as f:
            f.write(ics)
        n = sum(1 for m in seed["matches"] if m["season"] == latest_season)
        print(f"calendar written: {n} {latest_season} matches to {ICS_PATH}")

# --- Data for the dashboard's "Ask about the team" box --------------------------
# Flat tables Claude can load and compute on (scripts/ask_pack.py).
from ask_pack import write_pack  # noqa: E402

if write_pack(seed, "docs/ask-data.json"):
    print("ask data written: docs/ask-data.json")
else:
    print("ask data unchanged; not rewriting")
