"""What the season simulator needs, worked out once a night.

The simulation itself runs in the app (SeasonSimulator.kt) and on the
dashboard (docs/sim.js), not here: running it on the reader's device is what
lets a "what if Kansas wins this one?" answer at once. This builds its inputs
from what the pipeline already has - the win model's chance for every KU match
still to play, the Big 12 table, and the Division I results behind the RPI.

Two simplifications, both said on screen:

- The NCAA scoreboard lists a game only on the day it is played, so the other
  Big 12 teams' remaining opponents are not known. Their games other than
  against Kansas are played against an average Big 12 opponent: each one is
  won with the chance the win model gives that team on a neutral floor
  against the conference's mean rating.
- Kansas's RPI at the end of the season is estimated with everyone else's RPI
  frozen where it is today. Kansas's winning percentage moves with the
  simulated results; its opponents' and opponents' opponents' figures are
  fixed by the schedule, since those are about the other teams' results.
"""


def win_chance(gap, scale):
    """The win model's formula, as in update-seed.py."""
    return 1 / (1 + 10 ** (-gap / scale))


def ku_rpi_parts(by_team, ku, remaining_opponents):
    """(owp, oowp) for Kansas once every remaining opponent has been played.

    Uses the NCAA's rule that an opponent's winning percentage leaves out its
    games against the team being rated, and counts an opponent met twice
    twice. Opponents with no Division I games yet are skipped.
    """
    def wp_excluding(team, excluded):
        games = [w for o, w in by_team.get(team, ()) if o != excluded]
        return sum(games) / len(games) if games else None

    def owp_of(team):
        vals = [v for v in (wp_excluding(o, team) for o, _w in by_team.get(team, ())) if v is not None]
        return sum(vals) / len(vals) if vals else None

    opponents = [o for o, _w in by_team.get(ku, ())] + list(remaining_opponents)
    owps = [v for v in (wp_excluding(o, ku) for o in opponents) if v is not None]
    oowps = [v for v in (owp_of(o) for o in opponents) if v is not None]
    owp = sum(owps) / len(owps) if owps else 0.0
    oowp = sum(oowps) / len(oowps) if oowps else 0.0
    return owp, oowp


def build(*, ku_key, ku_rating, remaining, standings, rating_of, scale, conf_total,
          by_team, computed_rpi, norm, at_large_cutoff=45):
    """The simulator's inputs, or None when there is nothing left to simulate.

    remaining: KU's unplayed matches, each {date, opponent, venue, conference,
    p} with p the win model's chance (None if it has none).
    standings: this season's Big 12 rows {team, confW, confL, overallW, overallL}.
    rating_of(team): the win model's rating for a team, or None.
    """
    if not remaining:
        return None
    rows = {norm(r["team"]): r for r in standings}
    ku_row = rows.get(ku_key)
    if ku_row is None:
        return None

    ratings = {k: rating_of(r["team"]) for k, r in rows.items()}
    rated = [v for v in ratings.values() if v is not None]
    mean = sum(rated) / len(rated) if rated else None

    ku_conf_left = {}
    for m in remaining:
        if m["conference"]:
            k = norm(m["opponent"])
            ku_conf_left[k] = ku_conf_left.get(k, 0) + 1

    others = []
    for k, r in rows.items():
        if k == ku_key:
            continue
        played = r["confW"] + r["confL"]
        vs_ku = ku_conf_left.get(k, 0)
        rating = ratings.get(k)
        p_avg = win_chance(rating - mean, scale) if rating is not None and mean is not None else 0.5
        others.append({
            "team": r["team"],
            "key": k,
            "confW": r["confW"],
            "confL": r["confL"],
            # Their conference matches still to play, less those against Kansas,
            # which the simulation plays out as Kansas's own.
            "remaining": max(0, conf_total - played - vs_ku),
            "pVsAverage": round(p_avg, 4),
        })

    played_ku = len(by_team.get(ku_key, ()))
    wins_ku = sum(1 for _o, w in by_team.get(ku_key, ()) if w)
    owp, oowp = ku_rpi_parts(by_team, ku_key, [norm(m["opponent"]) for m in remaining])
    field = sorted((v[0] for t, v in computed_rpi.items() if t != ku_key), reverse=True)

    return {
        "confTotal": conf_total,
        "kansas": {
            "confW": ku_row["confW"], "confL": ku_row["confL"],
            "overallW": ku_row["overallW"], "overallL": ku_row["overallL"],
            "rating": ku_rating,
        },
        "remaining": [
            {"date": m["date"], "opponent": m["opponent"], "venue": m["venue"],
             "conference": m["conference"], "key": norm(m["opponent"]),
             "p": round(m["p"], 4) if m["p"] is not None else 0.5}
            for m in remaining
        ],
        "others": others,
        "rpi": {
            # Division I games only, which is what the RPI counts.
            "wins": wins_ku,
            "played": played_ku,
            "owp": round(owp, 5),
            "oowp": round(oowp, 5),
            # Everyone else's RPI today, best first, for ranking Kansas's
            # simulated final figure. Kept to the part of the table that matters.
            "field": [round(v, 5) for v in field[:150]],
            "atLargeCutoff": at_large_cutoff,
        },
    }
