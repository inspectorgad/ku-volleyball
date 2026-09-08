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
    for field in ("venue", "city"):
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
    contest the NCAA sent back the wrong way round is credited correctly. For
    another team's game there is no roster to check against, so the teamId label
    stands - the alternative is guessing, and a wrong guess here would silently
    swap two teams' seasons.
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
    return [(names.get(str(b.get("teamId")), ""), b) for b in blocks]


serving = {}  # norm team -> totals
for path in sorted(glob.glob("scraped/ncaa-opp-*.json") + glob.glob("scraped/ncaa-game-*.json")):
    data = load_json(path, None)
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
if team_serving:
    seed["teamServing"] = team_serving

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
