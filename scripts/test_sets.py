import importlib.util
import os
import unittest

_spec = importlib.util.spec_from_file_location(
    "update_seed_sets", os.path.join(os.path.dirname(__file__), "update-seed.py"))


def _load_helper():
    # update-seed.py runs the whole pipeline when imported, so the helper is
    # read out of the file and run on its own.
    src = open(_spec.origin).read()
    start = src.index("def sets_from_scores(")
    end = src.index("\ndef ", start + 1)
    ns = {}
    exec(src[start:end], ns)
    return ns["sets_from_scores"]


sets_from_scores = _load_helper()


class SetsFromScoresTest(unittest.TestCase):
    def test_counts_a_finished_sweep_that_the_ncaa_called_two_nil(self):
        self.assertEqual(sets_from_scores(["32-30", "25-20", "26-24"]), (3, 0))

    def test_five_setter_uses_fifteen_for_the_fifth(self):
        self.assertEqual(sets_from_scores(["24-26", "25-21", "25-19", "26-28", "13-15"]), (2, 3))

    def test_an_unfinished_match_counts_as_nothing(self):
        self.assertIsNone(sets_from_scores(["25-20", "25-22", "14-11"]))
        self.assertIsNone(sets_from_scores(["25-20", "25-22"]))
        self.assertIsNone(sets_from_scores([]))


if __name__ == "__main__":
    unittest.main()
