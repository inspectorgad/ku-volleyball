import unittest

from resume import team_games, compute_rpi, rank_rpi, common_opponents


class RpiTest(unittest.TestCase):
    GAMES = [
        ["2026-09-01", "a", 3, "b", 1],
        ["2026-09-02", "b", 3, "c", 0],
        ["2026-09-03", "c", 0, "a", 3],
        ["2026-09-04", "a", 3, "d2 school", 0],  # not Division I
        ["2026-09-05", "b", 0, "c", 0],          # no winner: a glitch, skipped
    ]

    def setUp(self):
        self.by_team = team_games(self.GAMES, lambda t: t in {"a", "b", "c"})

    def test_only_division_one_games_with_a_winner_count(self):
        self.assertEqual(len(self.by_team["a"]), 2)
        self.assertEqual(len(self.by_team["b"]), 2)
        self.assertNotIn("d2 school", self.by_team)

    def test_standard_weights_and_opponents_exclude_the_rated_team(self):
        rpi = compute_rpi(self.by_team)
        # A: WP 1. OWP: B without A is 1-0, C without A is 0-1 -> 0.5.
        # OOWP: mean of OWP(B)=0.5 and OWP(C)=0.5 -> 0.5 (worked by hand).
        self.assertAlmostEqual(rpi["a"][0], 0.25 * 1 + 0.5 * 0.5 + 0.25 * 0.5)
        self.assertEqual(rpi["a"][1:], (2, 0))
        ranked = rank_rpi(rpi)
        self.assertEqual([t for t, *_ in ranked], ["a", "b", "c"])
        self.assertEqual([r for _t, r, *_ in ranked], [1, 2, 3])

    def test_ties_share_a_rank(self):
        ranked = rank_rpi({"x": (0.5, 1, 1), "y": (0.5, 1, 1), "z": (0.4, 0, 2)})
        self.assertEqual([r for _t, r, *_ in ranked], [1, 1, 3])

    def test_common_opponents_leave_out_both_teams(self):
        ku = {"pittsburgh": ["L 1-3"], "tulsa": ["W 3-0"], "utah": ["L 0-3"]}
        them = {"pittsburgh": ["L 1-3"], "kansas": ["W 3-0"], "byu": ["W 3-1"]}
        self.assertEqual(common_opponents(ku, them, exclude=("kansas", "utah")),
                         [("pittsburgh", ["L 1-3"], ["L 1-3"])])


if __name__ == "__main__":
    unittest.main()
