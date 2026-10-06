import unittest

from sim_inputs import build, ku_rpi_parts, win_chance


def norm(name):
    return name.lower().replace(".", "")


STANDINGS = [
    {"team": "Kansas", "confW": 3, "confL": 1, "overallW": 10, "overallL": 4},
    {"team": "TCU", "confW": 4, "confL": 0, "overallW": 13, "overallL": 2},
    {"team": "Utah", "confW": 1, "confL": 3, "overallW": 8, "overallL": 7},
]
RATINGS = {"Kansas": 74.0, "TCU": 80.0, "Utah": 68.0}


class SimInputsTest(unittest.TestCase):
    def setUp(self):
        self.by_team = {
            "kansas": [("tcu", False), ("utah", True)],
            "tcu": [("kansas", True), ("utah", True)],
            "utah": [("kansas", False), ("tcu", False), ("byu", True)],
            "byu": [("utah", False)],
        }
        self.inputs = build(
            ku_key="kansas", ku_rating=74.0,
            remaining=[
                {"date": "2026-10-23", "opponent": "TCU", "venue": "A", "conference": True, "p": 0.26},
                {"date": "2026-11-01", "opponent": "Utah", "venue": "H", "conference": True, "p": None},
            ],
            standings=STANDINGS, rating_of=RATINGS.get, scale=25, conf_total=18,
            by_team=self.by_team,
            computed_rpi={"kansas": (0.6, 1, 1), "tcu": (0.7, 2, 0), "utah": (0.4, 1, 2)},
            norm=norm,
        )

    def test_other_teams_remaining_leaves_out_their_games_with_kansas(self):
        tcu = next(o for o in self.inputs["others"] if o["key"] == "tcu")
        # 18 in all, 4 played, 1 still to come against Kansas.
        self.assertEqual(tcu["remaining"], 13)
        # Above the conference mean, so better than even against an average side.
        self.assertGreater(tcu["pVsAverage"], 0.5)

    def test_a_match_with_no_forecast_counts_as_even(self):
        self.assertEqual(self.inputs["remaining"][1]["p"], 0.5)

    def test_rpi_parts_exclude_games_against_kansas(self):
        owp, _oowp = ku_rpi_parts(self.by_team, "kansas", [])
        # TCU without Kansas: 1-0; Utah without Kansas: 1-1.
        self.assertAlmostEqual(owp, (1.0 + 0.5) / 2)
        self.assertEqual(self.inputs["rpi"]["field"], [0.7, 0.4])

    def test_nothing_to_simulate_once_the_season_is_over(self):
        self.assertIsNone(build(
            ku_key="kansas", ku_rating=74.0, remaining=[], standings=STANDINGS,
            rating_of=RATINGS.get, scale=25, conf_total=18, by_team={}, computed_rpi={}, norm=norm))

    def test_formula_matches_the_win_model(self):
        self.assertAlmostEqual(win_chance(0, 25), 0.5)
        self.assertAlmostEqual(win_chance(25, 25), 10 / 11)


if __name__ == "__main__":
    unittest.main()
