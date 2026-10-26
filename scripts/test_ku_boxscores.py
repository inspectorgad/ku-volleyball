"""Sets played, and who played at all, from Kansas Athletics' box scores.

The NCAA feed credits every player it lists with every set of a match,
including players who never came on; update-seed.py corrects both sides' lines
in a Kansas match from Kansas Athletics' own box score, and marks the match
linesChecked so phones that hold its old lines correct them. These run the
correction on a small made-up NCAA game and a box score in the shape
scripts/ku-boxscores.mjs writes.
"""
import importlib.util
import json
import os
import re
import subprocess
import sys
import tempfile
import unicodedata
import unittest
from collections import defaultdict

_spec = importlib.util.spec_from_file_location(
    "update_seed_ku_boxscores", os.path.join(os.path.dirname(__file__), "update-seed.py"))


def _load_helpers():
    # update-seed.py runs the whole pipeline when imported, so the pieces under
    # test are read out of the file and run on their own, as test_sets.py does.
    src = open(_spec.origin).read()

    def cut(start, end):
        i = src.index(start)
        return src[i:src.index(end, i + len(start))]

    ns = {"re": re, "unicodedata": unicodedata, "defaultdict": defaultdict}
    for piece in (
        cut("def to_int(", "\n\n\n"),
        cut("def stat_line(", "\n\n\n"),
        cut("def strip_rank(", "\n\n\n"),
        cut("TEAM_ALIASES = {", "\n\n\n"),
        cut("def norm_team(", "\n\n\n"),
        cut("ON_THE_FLOOR = (", "\nku_boxscores_raw = "),
    ):
        exec(piece, ns)
    return ns


H = _load_helpers()
stat_line = H["stat_line"]
person_key = H["person_key"]
ku_boxscore_lookup = H["ku_boxscore_lookup"]
apply_ku_boxscore = H["apply_ku_boxscore"]
norm_team = H["norm_team"]


def ncaa_player(first, last, number, sets, **stats):
    """One playerStats row as the NCAA feed sends it: every figure a string."""
    row = {"firstName": first, "lastName": last, "number": number, "position": "OH",
           "participated": True, "gamesPlayed": str(sets)}
    for key in ("kills", "attackErrors", "attackAttempts", "assists", "serviceAces",
                "serviceErrors", "serveAttempts", "digs", "receptionAttempts",
                "receptionErrors", "blockSolos", "blockAssists", "ballHandlingErrors"):
        row[key] = str(stats.get(key, 0))
    return row


# Kansas at Baylor, five sets. The feed has all of these down for five sets.
NCAA_KU = [
    ncaa_player("Reese", "Ptacek", 10, 5, kills=13, attackErrors=2, attackAttempts=27,
                assists=2, serviceAces=3, serviceErrors=3, serveAttempts=18, digs=4,
                blockSolos=1, blockAssists=3, receptionAttempts=5),
    # On for one set, did nothing anybody counts.
    ncaa_player("Heidi", "Devers", 12, 5),
    # Never came on.
    ncaa_player("Cydnee", "Bryant", 4, 5),
    # Not in Kansas Athletics' box score, yet the feed has her with a kill.
    ncaa_player("Ryan", "Sadler", 5, 5, kills=1, attackAttempts=2),
    # The feed spells her name differently; her shirt number is the same.
    ncaa_player("Grace", "Nelsen", 1, 5, kills=13, attackAttempts=32, digs=17),
    # Not in Kansas Athletics' box score either, and nothing counted - but she
    # took serves, so she was on the floor.
    ncaa_player("Logan", "Bell", 25, 5, serveAttempts=4),
    # The same for a player who only passed.
    ncaa_player("Olivia", "Hasbrook", 6, 5, receptionAttempts=3),
]

# Baylor's side of the same NCAA box score.
NCAA_BAYLOR = [
    ncaa_player("Ksenia", "Rakhmanchik", 6, 5, kills=25, attackErrors=7, attackAttempts=65, digs=10),
    ncaa_player("Nadiya", "Johnson", 10, 5, kills=5, attackErrors=8, attackAttempts=16),
    # Dressed, never on.
    ncaa_player("Ava", "Bench", 30, 5),
]

# What parseKuBoxScore makes of Kansas Athletics' page for the same match.
KU_BOX = {
    "date": "2026-10-25",
    "opponent": "Baylor",
    "sets": [[23, 25], [22, 25], [25, 21], [25, 21], [15, 13]],
    "players": [
        {"name": "Reese Ptacek", "number": "10", "sp": 5, "k": 13, "e": 2, "ta": 27, "a": 2,
         "sa": 3, "se": 3, "bs": 1, "ba": 3, "d": 4, "re": 0},
        {"name": "Heidi Devers", "number": "12", "sp": 1, "k": 0, "e": 0, "ta": 0, "a": 0,
         "sa": 0, "se": 0, "bs": 0, "ba": 0, "d": 0, "re": 0},
        {"name": "Grace Nelson", "number": "1", "sp": 4, "k": 13, "e": 3, "ta": 32, "a": 1,
         "sa": 0, "se": 1, "bs": 0, "ba": 2, "d": 17, "re": 2},
    ],
    "opponentPlayers": [
        {"name": "Ksenia Rakhmanchik", "number": "6", "sp": 5, "k": 25, "e": 7, "ta": 65, "a": 0,
         "sa": 2, "se": 4, "bs": 0, "ba": 0, "d": 10, "re": 1},
        {"name": "Nadiya Johnson", "number": "10", "sp": 4, "k": 5, "e": 8, "ta": 16, "a": 0,
         "sa": 0, "se": 0, "bs": 0, "ba": 1, "d": 0, "re": 0},
    ],
}


def ku_lines(rows):
    """KU's lines as the box-score loop builds them before the correction."""
    out = []
    for p in rows:
        name = f"{p['firstName']} {p['lastName']}"
        out.append({"name": name, "number": str(p["number"]), "pos": p["position"],
                    "rcp": int(p["receptionAttempts"]), **stat_line(p)})
    return out


class PersonKeyTest(unittest.TestCase):
    def test_either_order_and_any_spelling_of_case_or_accent(self):
        self.assertEqual(person_key("Stanley, Taylor"), person_key("Taylor Stanley"))
        self.assertEqual(person_key("van Lannen, Emerson"), person_key("Emerson Van lannen"))
        self.assertEqual(person_key("Inés Losada"), "ines losada")
        self.assertEqual(person_key("Ndam-Simpson, Ama"), person_key("Ama Ndam-simpson"))


class LookupTest(unittest.TestCase):
    def raw(self, *boxes):
        return {"formatVersion": 1, "seasons": {"2026": {str(i): b for i, b in enumerate(boxes)}}}

    def test_found_by_date_and_opponent_however_the_school_is_written(self):
        box = {**KU_BOX, "date": "2026-10-15", "opponent": "Kansas State"}
        find = ku_boxscore_lookup(self.raw(box), norm_team)
        self.assertIs(find("2026-10-15", "Kansas St."), box)
        self.assertIsNone(find("2026-10-16", "Kansas St."))

    def test_the_date_alone_settles_it_only_when_there_is_one_match_that_day(self):
        one = ku_boxscore_lookup(self.raw(KU_BOX), norm_team)
        self.assertIs(one("2026-10-25", "Baylor University"), KU_BOX)
        other = {**KU_BOX, "opponent": "Stanford"}
        two = ku_boxscore_lookup(self.raw(KU_BOX, other), norm_team)
        self.assertIs(two("2026-10-25", "Stanford"), other)
        self.assertIsNone(two("2026-10-25", "Pittsburgh"))

    def test_two_on_one_date_with_neither_name_matching_are_told_apart_by_set_scores(self):
        # 2025-09-21: Creighton and South Florida, which the NCAA writes
        # "South Fla." - no spelling of "South Florida" that norm_team knows.
        usf = {**KU_BOX, "date": "2025-09-21", "opponent": "South Florida",
               "sets": [[26, 28], [25, 22], [25, 23], [25, 20]]}
        creighton = {**KU_BOX, "date": "2025-09-21", "opponent": "Creighton University",
                     "sets": [[24, 26], [18, 25], [22, 25]]}
        find = ku_boxscore_lookup(self.raw(creighton, usf), norm_team)
        self.assertIs(find("2025-09-21", "South Fla.", "26-28, 25-22, 25-23, 25-20"), usf)
        self.assertIs(find("2025-09-21", "Creighton", "24-26, 18-25, 22-25"), creighton)
        # Set scores neither box score has, or none at all: not guessed at.
        self.assertIsNone(find("2025-09-21", "South Fla.", "25-20, 25-20, 25-20"))
        self.assertIsNone(find("2025-09-21", "South Fla."))

    def test_the_name_comes_before_the_set_scores(self):
        stanford = {**KU_BOX, "opponent": "Stanford", "sets": [[25, 20], [25, 20], [25, 20]]}
        same_sets = {**KU_BOX, "opponent": "Pittsburgh", "sets": [[25, 20], [25, 20], [25, 20]]}
        find = ku_boxscore_lookup(self.raw(stanford, same_sets), norm_team)
        self.assertIs(find("2026-10-25", "Pittsburgh", "25-20, 25-20, 25-20"), same_sets)
        # Two that day with the same set scores and neither name: left alone.
        self.assertIsNone(find("2026-10-25", "Pitt", "25-20, 25-20, 25-20"))

    def test_nothing_on_disk_finds_nothing(self):
        self.assertIsNone(ku_boxscore_lookup({}, norm_team)("2026-10-25", "Baylor"))

    def test_a_page_that_failed_to_read_is_not_a_box_score(self):
        failed = {"failures": 3, "failedAt": "2026-10-26T05:37:00.000Z", "source": "x"}
        find = ku_boxscore_lookup(self.raw(failed, KU_BOX), norm_team)
        self.assertIs(find("2026-10-25", "Baylor"), KU_BOX)


class ApplyTest(unittest.TestCase):
    def setUp(self):
        self.before = ku_lines(NCAA_KU)
        self.kept, self.dropped, self.notes = apply_ku_boxscore(
            KU_BOX["players"], self.before, "2026-10-25 Baylor")
        self.by_name = {p["name"]: p for p in self.kept}

    def test_a_listed_player_gets_kansas_athletics_sets_played_and_keeps_the_rest(self):
        self.assertEqual(self.by_name["Heidi Devers"]["sp"], 1)
        ptacek = self.by_name["Reese Ptacek"]
        self.assertEqual(ptacek, {**self.before[0], "sp": 5})
        # The NCAA's figures stand for everything else, serves taken included.
        self.assertEqual((ptacek["k"], ptacek["sat"], ptacek["rcp"]), (13, 18, 5))

    def test_a_player_who_never_came_on_is_dropped(self):
        self.assertEqual(self.dropped, ["Cydnee Bryant"])
        self.assertNotIn("Cydnee Bryant", self.by_name)

    def test_one_missing_from_kansas_athletics_but_with_stats_is_kept_and_reported(self):
        self.assertEqual(self.by_name["Ryan Sadler"], self.before[3])
        self.assertTrue(any("WARNING" in n and "Ryan Sadler" in n for n in self.notes), self.notes)

    def test_a_name_spelled_differently_is_matched_by_shirt_number(self):
        self.assertEqual(self.by_name["Grace Nelsen"]["sp"], 4)
        self.assertTrue(any("shirt number" in n for n in self.notes), self.notes)

    def test_one_missing_who_took_serves_or_passed_is_kept_and_reported(self):
        for name, i in (("Logan Bell", 5), ("Olivia Hasbrook", 6)):
            self.assertEqual(self.by_name[name], self.before[i])
            self.assertTrue(any("WARNING" in n and name in n for n in self.notes), self.notes)

    def test_the_order_is_the_feeds(self):
        self.assertEqual([p["name"] for p in self.kept],
                         ["Reese Ptacek", "Heidi Devers", "Ryan Sadler", "Grace Nelsen",
                          "Logan Bell", "Olivia Hasbrook"])

    def test_a_shirt_number_never_takes_a_name_the_feed_has_elsewhere(self):
        # Devers's row must not go to somebody else wearing 12 in the feed.
        rows = ku_lines([ncaa_player("Avery", "Poulton", 12, 5),
                         ncaa_player("Heidi", "Devers", 21, 5)])
        kept, dropped, _ = apply_ku_boxscore(KU_BOX["players"], rows)
        self.assertEqual(dropped, ["Avery Poulton"])
        self.assertEqual([(p["name"], p["sp"]) for p in kept], [("Heidi Devers", 1)])

    def test_a_player_kansas_athletics_has_and_the_feed_does_not_is_reported(self):
        _, _, notes = apply_ku_boxscore(KU_BOX["players"], ku_lines(NCAA_KU[:1]), "x")
        self.assertTrue(any("WARNING" in n and "Heidi Devers" in n for n in notes), notes)


def ncaa_game():
    """A made-up NCAA capture, in the shape scraped/ncaa-game-*.json has."""
    totals = {"gamesPlayed": "5", "kills": "60", "attackErrors": "20", "attackAttempts": "150",
              "assists": "55", "serviceAces": "6", "serviceErrors": "12", "digs": "57"}
    return {
        "gameId": "1", "date": "2026-10-25",
        "info": {"contests": [{
            "gameState": "F", "seasonYear": 2026,
            "teams": [
                {"seoname": "kansas", "teamId": "1", "nameShort": "Kansas", "isHome": False, "score": 3},
                {"seoname": "baylor", "teamId": "2", "nameShort": "Baylor", "isHome": True, "score": 2},
            ],
            "linescores": [{"home": 25, "visit": 23}, {"home": 25, "visit": 22}, {"home": 21, "visit": 25},
                           {"home": 21, "visit": 25}, {"home": 13, "visit": 15}],
            "location": {"venue": "Foster Pavilion", "city": "Waco", "stateUsps": "TX"},
        }]},
        "box": {
            "teams": [{"teamId": "1", "nameShort": "Kansas"}, {"teamId": "2", "nameShort": "Baylor"}],
            "teamBoxscore": [
                {"teamId": "1", "teamStats": totals, "playerStats": NCAA_KU},
                {"teamId": "2", "teamStats": totals, "playerStats": NCAA_BAYLOR},
            ],
        },
    }


class SeedTest(unittest.TestCase):
    """The whole of update-seed.py, run on the made-up game in a scratch folder."""

    def run_seed(self, boxscores, runs=1):
        with tempfile.TemporaryDirectory() as tmp:
            os.makedirs(os.path.join(tmp, "scraped"))
            with open(os.path.join(tmp, "scraped", "ncaa-game-1.json"), "w") as f:
                json.dump(ncaa_game(), f)
            if boxscores is not None:
                with open(os.path.join(tmp, "scraped", "ku-boxscores.json"), "w") as f:
                    json.dump(boxscores, f)
            for _ in range(runs):
                run = subprocess.run([sys.executable, _spec.origin], cwd=tmp,
                                     capture_output=True, text=True, timeout=120)
                self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
            with open(os.path.join(tmp, "app", "src", "main", "assets", "seed.json")) as f:
                return json.load(f), run.stdout

    def test_the_seed_carries_the_corrected_lines_and_marks_the_match(self):
        seed, out = self.run_seed({"formatVersion": 1, "seasons": {"2026": {"20599": KU_BOX}}})
        self.assertNotIn("linesRevision", seed)
        match = seed["matches"][0]
        self.assertIs(match["linesChecked"], True)
        self.assertEqual(match["setScores"], "23-25, 22-25, 25-21, 25-21, 15-13")
        sets = {line["player"]: line["sp"] for line in match["lines"]}
        self.assertEqual(sets, {"Reese Ptacek": 5, "Heidi Devers": 1, "Ryan Sadler": 5, "Grace Nelsen": 4,
                                "Logan Bell": 5, "Olivia Hasbrook": 5})
        ptacek = next(line for line in match["lines"] if line["player"] == "Reese Ptacek")
        self.assertEqual(list(ptacek), ["player", "sp", "k", "e", "ta", "a", "sa", "se", "d",
                                        "bs", "ba", "re", "bhe", "sat"])
        self.assertIn("1 lines dropped", out)
        self.assertIn("WARNING: 2026-10-25 Baylor: Ryan Sadler", out)

    def test_the_other_teams_lines_are_corrected_the_same_way(self):
        seed, out = self.run_seed({"formatVersion": 1, "seasons": {"2026": {"20599": KU_BOX}}})
        lines = seed["matches"][0]["opponentLines"]
        self.assertEqual({line["player"]: line["sp"] for line in lines},
                         {"Ksenia Rakhmanchik": 5, "Nadiya Johnson": 4})
        # Shaped as opponent lines always have been.
        self.assertEqual(list(lines[0]), ["player", "jerseyNumber", "position", "sp", "k", "e", "ta",
                                          "a", "sa", "se", "d", "bs", "ba", "re", "bhe", "sat"])
        self.assertIn("(opponents: 1 corrected, 1 dropped)", out)

    def test_a_box_score_without_the_other_teams_table_leaves_their_lines(self):
        box = {k: v for k, v in KU_BOX.items() if k != "opponentPlayers"}
        seed, _ = self.run_seed({"formatVersion": 1, "seasons": {"2026": {"20599": box}}})
        match = seed["matches"][0]
        self.assertEqual({line["player"]: line["sp"] for line in match["opponentLines"]},
                         {"Ksenia Rakhmanchik": 5, "Nadiya Johnson": 5, "Ava Bench": 5})
        self.assertIs(match["linesChecked"], True)

    FEEDS_LINES = {"Reese Ptacek": 5, "Heidi Devers": 5, "Cydnee Bryant": 5,
                   "Ryan Sadler": 5, "Grace Nelsen": 5, "Logan Bell": 5, "Olivia Hasbrook": 5}

    def test_without_kansas_athletics_box_scores_the_feeds_lines_stand_unmarked(self):
        seed, _ = self.run_seed(None)
        match = seed["matches"][0]
        self.assertNotIn("linesChecked", match)
        self.assertEqual({line["player"]: line["sp"] for line in match["lines"]}, self.FEEDS_LINES)
        self.assertEqual(len(match["opponentLines"]), 3)

    def test_a_box_score_whose_sets_disagree_is_not_used_and_the_match_not_marked(self):
        # Read with the match still on: three sets in, two to come.
        partial = {**KU_BOX, "sets": KU_BOX["sets"][:3]}
        seed, out = self.run_seed({"formatVersion": 1, "seasons": {"2026": {"20599": partial}}})
        match = seed["matches"][0]
        self.assertNotIn("linesChecked", match)
        self.assertEqual({line["player"]: line["sp"] for line in match["lines"]}, self.FEEDS_LINES)
        self.assertEqual(len(match["opponentLines"]), 3)
        self.assertIn("keeping the NCAA's sets played", out)

    def test_a_box_score_with_sets_played_that_cannot_be_right_is_refused(self):
        # A phone corrects a marked match once and for good, so a misread
        # figure must refuse the box score rather than be applied.
        for sp in (0, 6):
            players = [dict(p) for p in KU_BOX["players"]]
            players[0]["sp"] = sp
            box = {**KU_BOX, "players": players}
            seed, out = self.run_seed({"formatVersion": 1, "seasons": {"2026": {"20599": box}}})
            match = seed["matches"][0]
            self.assertNotIn("linesChecked", match, sp)
            self.assertEqual({line["player"]: line["sp"] for line in match["lines"]}, self.FEEDS_LINES)
            self.assertIn("looks misread", out)

    def test_a_box_score_with_no_sets_is_refused(self):
        seed, out = self.run_seed({"formatVersion": 1, "seasons": {"2026": {"20599": {**KU_BOX, "sets": []}}}})
        self.assertNotIn("linesChecked", seed["matches"][0])
        self.assertIn("keeping the NCAA's sets played", out)

    def test_a_second_run_on_the_same_data_leaves_the_seed_as_it_is(self):
        _, out = self.run_seed({"formatVersion": 1, "seasons": {"2026": {"20599": KU_BOX}}}, runs=2)
        self.assertIn("seed.json unchanged", out)


class BoxSetScoresTest(unittest.TestCase):
    def test_written_as_a_match_writes_them(self):
        self.assertEqual(H["box_set_scores"](KU_BOX), "23-25, 22-25, 25-21, 25-21, 15-13")
        self.assertEqual(H["box_set_scores"]({}), "")


if __name__ == "__main__":
    unittest.main()
