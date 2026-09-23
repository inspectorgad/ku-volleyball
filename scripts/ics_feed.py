"""The season as an iCalendar feed, for phones to subscribe to.

An iPhone cannot install the APK, but it can subscribe to a calendar, and a
subscribed calendar refreshes itself. So the schedule is published as one: every
match of the current season, with first serve, TV and venue, rewritten on every
scrape so a time the conference moves or a broadcast added late reaches the
phone without anybody doing anything.

Times on the athletics schedule are Central, so events are written in
America/Chicago with the zone spelled out in a VTIMEZONE, not converted to UTC
here. Six of this season's timed matches fall after clocks go back on Nov 1; a
feed written in fixed offsets would put every one of those an hour out.
"""

import re
from datetime import date, datetime, timedelta

# US Central since 2007: CDT from the second Sunday of March, CST from the first
# Sunday of November, both at 02:00 local.
VTIMEZONE = [
    "BEGIN:VTIMEZONE",
    "TZID:America/Chicago",
    "BEGIN:DAYLIGHT",
    "TZOFFSETFROM:-0600",
    "TZOFFSETTO:-0500",
    "TZNAME:CDT",
    "DTSTART:19700308T020000",
    "RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=2SU",
    "END:DAYLIGHT",
    "BEGIN:STANDARD",
    "TZOFFSETFROM:-0500",
    "TZOFFSETTO:-0600",
    "TZNAME:CST",
    "DTSTART:19701101T020000",
    "RRULE:FREQ=YEARLY;BYMONTH=11;BYDAY=1SU",
    "END:STANDARD",
    "END:VTIMEZONE",
]

# A volleyball match is two hours give or take; the calendar only needs a block.
MATCH_LENGTH = timedelta(hours=2)


def _escape(text):
    return (text.replace("\\", "\\\\").replace(";", "\\;")
            .replace(",", "\\,").replace("\n", "\\n"))


def _fold(line):
    """RFC 5545 folding: at most 75 octets a line, continuations start with a space."""
    out, cur = [], b""
    for ch in line:
        enc = ch.encode("utf-8")
        if len(cur) + len(enc) > (75 if not out else 74):
            out.append(cur.decode("utf-8"))
            cur = b""
        cur += enc
    out.append(cur.decode("utf-8"))
    return "\r\n ".join(out)


def _uid(m):
    key = re.sub(r"[^a-z0-9]+", "-", m["opponent"].lower()).strip("-")
    return f"{m['date']}-{key}@ku-volleyball"


def _summary(m):
    site = "at" if m.get("home") is False and not m.get("neutral") else "vs"
    us, them = m.get("teamSets"), m.get("opponentSets")
    if us is not None and them is not None:
        return f"KU {'W' if us > them else 'L'} {us}-{them} {site} {m['opponent']}"
    return f"KU volleyball {site} {m['opponent']}"


def _description(m):
    parts = []
    if m.get("tv"):
        parts.append(f"TV: {m['tv']}")
    if m.get("setScores"):
        parts.append(f"Sets: {m['setScores']}")
    if not m.get("time"):
        parts.append("Time to be announced")
    return "\n".join(parts)


def build_ics(matches, stamp):
    """The feed as a string. `stamp` is a UTC datetime, for DTSTAMP only."""
    dtstamp = stamp.strftime("%Y%m%dT%H%M%SZ")
    lines = [
        "BEGIN:VCALENDAR",
        "VERSION:2.0",
        "PRODID:-//ku-volleyball//schedule//EN",
        "CALSCALE:GREGORIAN",
        "METHOD:PUBLISH",
        "X-WR-CALNAME:KU Volleyball",
        "X-WR-TIMEZONE:America/Chicago",
        # A hint to refresh twice a day; iOS sets its own interval regardless.
        "REFRESH-INTERVAL;VALUE=DURATION:PT12H",
        "X-PUBLISHED-TTL:PT12H",
        *VTIMEZONE,
    ]
    for m in sorted(matches, key=lambda m: m["date"]):
        day = date.fromisoformat(m["date"])
        lines += ["BEGIN:VEVENT", f"UID:{_uid(m)}", f"DTSTAMP:{dtstamp}"]
        time = m.get("time") or ""
        if re.fullmatch(r"\d{1,2}:\d{2}", time):
            h, mi = (int(x) for x in time.split(":"))
            start = datetime(day.year, day.month, day.day, h, mi)
            end = start + MATCH_LENGTH
            lines += [
                f"DTSTART;TZID=America/Chicago:{start:%Y%m%dT%H%M%S}",
                f"DTEND;TZID=America/Chicago:{end:%Y%m%dT%H%M%S}",
            ]
        else:
            # No time yet: an all-day event, so it holds the date without
            # claiming an hour nobody has announced.
            lines += [
                f"DTSTART;VALUE=DATE:{day:%Y%m%d}",
                f"DTEND;VALUE=DATE:{day + timedelta(days=1):%Y%m%d}",
                "TRANSP:TRANSPARENT",
            ]
        lines.append(f"SUMMARY:{_escape(_summary(m))}")
        where = ", ".join(x for x in (m.get("venue"), m.get("city")) if x)
        if where:
            lines.append(f"LOCATION:{_escape(where)}")
        desc = _description(m)
        if desc:
            lines.append(f"DESCRIPTION:{_escape(desc)}")
        lines.append("END:VEVENT")
    lines.append("END:VCALENDAR")
    return "\r\n".join(_fold(l) for l in lines) + "\r\n"


def same_apart_from_stamp(a, b):
    """True when two feeds differ in DTSTAMP at most, so a rewrite would be noise."""
    strip = lambda s: re.sub(r"^DTSTAMP:.*$", "", s or "", flags=re.M)
    return strip(a) == strip(b)
