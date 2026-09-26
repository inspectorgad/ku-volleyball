import unittest

from ask_pack import build_pack

SEED = {
    "generatedAt": "2026-09-26T00:00:00Z",
    "players": [{"name": "Taylor Stanley", "jerseyNumber": "23", "position": "OH", "height": "6-3", "active": True}],
    "standings": [{"season": "2026", "team": "Houston", "confW": 0, "confL": 1}],
    "goalDefinitions": [
        {"name": "Hit %", "group": "team", "target": 0.29, "decimals": 3, "ceiling": False},
        {"name": "Errors/set", "group": "team", "target": 7.5, "decimals": 2, "ceiling": True},
    ],
    "matches": [
        {"date": "2026-09-25", "opponent": "Houston", "season": "2026", "home": True,
         "teamSets": 3, "opponentSets": 0, "setScores": "31-29, 25-20, 25-15", "kuRank": 19,
         "opponentRpi": 140, "forecast": 0.82,
         "lines": [{"player": "Taylor Stanley", "sp": 3, "k": 17, "e": 4, "ta": 43}],
         "teamStats": {"sp": 3, "k": 45, "e": 13, "ta": 140},
         "opponentStats": {"sp": 3, "k": 30, "e": 20, "ta": 120},
         "goals": {"values": [0.229, 7.0], "roles": {}}},
        {"date": "2026-09-27", "opponent": "Texas Tech", "season": "2026", "home": False,
         "time": "13:00", "tv": "ESPN+", "winProbability": 0.72, "ratingSource": "results"},
    ],
}


class AskPackTest(unittest.TestCase):
    def setUp(self):
        self.p = build_pack(SEED)

    def test_played_and_upcoming_are_separate_flat_tables(self):
        self.assertEqual(len(self.p["matches"]), 1)
        m = self.p["matches"][0]
        self.assertEqual((m["site"], m["conference"], m["result"], m["ku_sets"]), ("H", True, "W", 3))
        self.assertEqual(self.p["upcoming"][0]["first_serve_ct"], "13:00")
        self.assertEqual(self.p["upcoming"][0]["site"], "A")

    def test_lines_carry_roster_details_and_every_stat_column(self):
        l = self.p["ku_lines"][0]
        self.assertEqual((l["jersey"], l["position"], l["k"], l["bs"]), ("23", "OH", 17, 0))
        self.assertEqual([t["side"] for t in self.p["team_totals"]], ["KU", "OPP"])

    def test_goal_verdicts_respect_ceilings(self):
        g = {x["goal"]: x["met"] for x in self.p["goals"]}
        self.assertEqual(g, {"Hit %": False, "Errors/set": True})


if __name__ == "__main__":
    unittest.main()
