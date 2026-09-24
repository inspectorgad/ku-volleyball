"""Season resume pieces built from every Division I result.

Two things the app wants that the NCAA does not hand over in time:

* An RPI for the current season. The NCAA's RPI endpoint serves last season's
  final table until the first in-season release, weeks into the schedule, and
  the RPI is what decides at-large bids and seeds. So until that release a
  provisional one is computed here, with the standard weights - 25% winning
  percentage, 50% opponents' winning percentage, 25% opponents' opponents' -
  over Division I games only. It is labelled provisional wherever it is shown,
  and the NCAA's own table replaces it the moment the endpoint's season is the
  current one.

* Common opponents: before KU plays a team, the teams both have already played
  and how each side got on against them.
"""

from collections import defaultdict


def team_games(games, is_d1):
    """{team: [(opponent, won)]} over games where both sides are Division I.

    `games` is rows of [date, home, home_sets, away, away_sets] with names
    already normalised. A game with no winner (0-0, a scoreboard glitch) is
    skipped rather than counted as a loss for somebody.
    """
    by_team = defaultdict(list)
    for _date, home, hs, away, as_ in games:
        if not (is_d1(home) and is_d1(away)) or hs == as_:
            continue
        by_team[home].append((away, hs > as_))
        by_team[away].append((home, as_ > hs))
    return by_team


def compute_rpi(by_team):
    """{team: (rpi, wins, losses)} using the standard 25/50/25 weights.

    Opponents' winning percentage leaves out the games against the team being
    rated, as the NCAA's does; each game counts once, so an opponent met twice
    weighs twice.
    """
    wins = {t: sum(1 for _o, w in g if w) for t, g in by_team.items()}
    played = {t: len(g) for t, g in by_team.items()}

    def wp_excluding(team, excluded):
        games = [(o, w) for o, w in by_team.get(team, ()) if o != excluded]
        return sum(1 for _o, w in games if w) / len(games) if games else None

    owp = {}
    for team, games in by_team.items():
        vals = [v for v in (wp_excluding(o, team) for o, _w in games) if v is not None]
        owp[team] = sum(vals) / len(vals) if vals else 0.0

    out = {}
    for team, games in by_team.items():
        wp = wins[team] / played[team]
        oowp_vals = [owp[o] for o, _w in games if o in owp]
        oowp = sum(oowp_vals) / len(oowp_vals) if oowp_vals else 0.0
        out[team] = (0.25 * wp + 0.5 * owp[team] + 0.25 * oowp, wins[team], played[team] - wins[team])
    return out


def rank_rpi(rpi):
    """[(team, rank, rpi, wins, losses)], best first; ties share a rank."""
    ordered = sorted(rpi.items(), key=lambda kv: (-round(kv[1][0], 6), kv[0]))
    out, last_value, last_rank = [], None, 0
    for i, (team, (value, w, l)) in enumerate(ordered, start=1):
        rank = last_rank if last_value is not None and round(value, 6) == last_value else i
        out.append((team, rank, value, w, l))
        last_value, last_rank = round(value, 6), rank
    return out


def common_opponents(ku_results, their_results, exclude=()):
    """The teams both sides have played, with each side's results against them.

    Both arguments are {opponent_key: [result label, ...]} in date order. The
    two teams themselves (and anything in `exclude`) are left out.
    """
    shared = sorted(set(ku_results) & set(their_results) - set(exclude))
    return [(t, ku_results[t], their_results[t]) for t in shared]


def fit_line(pairs):
    """Least-squares (intercept, slope) for [(x, y)], or None if x never varies."""
    if not pairs:
        return None
    mx = sum(x for x, _ in pairs) / len(pairs)
    my = sum(y for _, y in pairs) / len(pairs)
    sxx = sum((x - mx) ** 2 for x, _ in pairs)
    if sxx == 0:
        return None
    slope = sum((x - mx) * (y - my) for x, y in pairs) / sxx
    return my - slope * mx, slope


def blend(from_results, games, preseason, prior_games):
    """Results and preseason ratings weighted games : prior_games.

    Half each at prior_games games; results alone when there is no preseason
    number to blend with.
    """
    if preseason is None:
        return from_results
    weight = games / (games + prior_games)
    return weight * from_results + (1 - weight) * preseason
