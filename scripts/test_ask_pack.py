import unittest

from ask_pack import build_pack

SEED = {
    "generatedAt": "2026-09-26T00:00:00Z",
    "players": [{"name": "Taylor Stanley", "jerseyNumber": "23", "position": "OH", "height": "6-3", "active": True}],
    "standings": [{"season": "2026", "team": "Houston", "confW": 0, "confL": 1}],
    "opponentRosters": [{"team": "Houston", "fetchedAt": "x", "players": [
        {"player": "Ana Lee", "jerseyNumber": "7", "position": "OH", "height": "6-1", "classYear": "Jr.",
         "hometown": "Austin, Texas", "previousSchool": "Rice"}]}],
    "opponentForm": [{"team": "Texas Tech", "matches": 22, "players": [
        {"player": "Kim Ross", "jerseyNumber": "9", "position": "OH", "mp": 22, "sp": 80, "k": 300}]}],
    "teamServing": [{"team": "Houston", "season": "2026", "matches": 20, "sets": 70, "serviceAces": 90,
                     "serviceErrors": 120, "serveAttempts": 1500, "big12": True}],
    "nationalLeaders": {"season": "2026", "categories": [
        {"name": "Kills Per Set", "rows": [{"rank": 1, "player": "Kim Ross", "team": "Texas Tech", "cls": "Sr.",
                                            "position": "OH", "sets": 80, "value": "5.10"}]}]},
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


RESULTS = [["2026-09-20", "Houston", 3, "Texas Tech", 1]]


class AskPackTest(unittest.TestCase):
    def setUp(self):
        self.p = build_pack(SEED, RESULTS)

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

    def test_system_prompt_is_shared_and_summarizes_the_small_tables(self):
        t = self.p["system_prompt"]
        self.assertTrue(t.startswith("You are the analyst behind"))
        self.assertIn("get_table", t)
        self.assertIn("Played matches:\nseason,date,opponent,site", t)
        self.assertIn("Roster:\nname,jerseyNumber,position,height,active", t)
        # Same input, same text: the prompt cache depends on it.
        self.assertEqual(t, build_pack(SEED)["system_prompt"])


    def test_other_teams_come_as_flat_tables(self):
        r = self.p["opponent_rosters"][0]
        self.assertEqual((r["team"], r["player"], r["class_year"], r["previous_school"], r["high_school"]),
                         ("Houston", "Ana Lee", "Jr.", "Rice", None))
        self.assertNotIn("fetchedAt", r)
        f = self.p["opponent_form"][0]
        self.assertEqual((f["team"], f["matches"], f["mp"], f["k"], f["e"]), ("Texas Tech", 22, 22, 300, 0))
        self.assertEqual(self.p["team_serving"][0]["service_aces"], 90)
        self.assertEqual(self.p["national_leaders"][0]["category"], "Kills Per Set")
        self.assertEqual(self.p["d1_results"], [
            {"date": "2026-09-20", "home": "Houston", "home_sets": 3, "away": "Texas Tech", "away_sets": 1}])

    def test_new_tables_are_defined_and_named_in_the_prompt(self):
        for t in ("opponent_rosters", "opponent_form", "team_serving", "national_leaders", "d1_results"):
            self.assertIn(t, self.p["definitions"])
            self.assertIn(t, self.p["system_prompt"])
        self.assertIn("web_search", self.p["system_prompt"])

    def test_without_results_the_table_is_empty(self):
        self.assertEqual(build_pack(SEED)["d1_results"], [])


if __name__ == "__main__":
    unittest.main()
