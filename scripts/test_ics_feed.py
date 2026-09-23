"""python3 -m unittest discover -s scripts -p 'test_*.py'"""
import unittest
from datetime import datetime, timezone

from ics_feed import build_ics, same_apart_from_stamp

MATCHES = [
    {"date": "2026-10-30", "opponent": "Kansas State", "season": "2026", "home": True,
     "time": "19:00", "tv": "FS1", "venue": "Horejsi Family Volleyball Arena", "city": "Lawrence, KS"},
    {"date": "2026-11-01", "opponent": "Iowa State", "season": "2026", "home": False, "time": "14:00"},
    {"date": "2026-11-25", "opponent": "Arizona", "season": "2026", "home": False},
    {"date": "2026-09-20", "opponent": "Grand Canyon", "season": "2026", "home": True,
     "teamSets": 3, "opponentSets": 0, "setScores": "25-21, 25-21, 25-15"},
]
STAMP = datetime(2026, 9, 23, 12, 0, tzinfo=timezone.utc)


class IcsFeedTest(unittest.TestCase):
    def setUp(self):
        self.ics = build_ics(MATCHES, STAMP)

    def test_times_are_local_central_either_side_of_the_clock_change(self):
        # Written as wall-clock time in the named zone; the VTIMEZONE rules put
        # Oct 30 in CDT and Nov 1 in CST, so neither is an hour out.
        self.assertIn("DTSTART;TZID=America/Chicago:20261030T190000", self.ics)
        self.assertIn("DTSTART;TZID=America/Chicago:20261101T140000", self.ics)
        self.assertIn("RRULE:FREQ=YEARLY;BYMONTH=11;BYDAY=1SU", self.ics)

    def test_no_time_is_an_all_day_event(self):
        self.assertIn("DTSTART;VALUE=DATE:20261125", self.ics)
        self.assertIn("DTEND;VALUE=DATE:20261126", self.ics)

    def test_escaping_results_and_line_endings(self):
        self.assertIn("LOCATION:Horejsi Family Volleyball Arena\\, Lawrence\\, KS", self.ics)
        self.assertIn("SUMMARY:KU W 3-0 vs Grand Canyon", self.ics)
        self.assertIn("SUMMARY:KU volleyball at Iowa State", self.ics)
        self.assertIn("UID:2026-10-30-kansas-state@ku-volleyball", self.ics)
        body = self.ics.encode()
        self.assertEqual(body.count(b"\n"), body.count(b"\r\n"))
        self.assertLessEqual(max(len(l) for l in body.split(b"\r\n")), 75)

    def test_a_new_stamp_alone_is_not_a_change(self):
        later = build_ics(MATCHES, datetime(2026, 9, 24, tzinfo=timezone.utc))
        self.assertTrue(same_apart_from_stamp(self.ics, later))
        moved = build_ics([{**MATCHES[0], "time": "18:00"}] + MATCHES[1:], STAMP)
        self.assertFalse(same_apart_from_stamp(self.ics, moved))


if __name__ == "__main__":
    unittest.main()
