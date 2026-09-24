"""Repairs names the NCAA API sends double-encoded.

A handful of box scores arrive with accented names mangled: the UTF-8 bytes of
"Inés" read back as Latin-1, giving "InÃ©s" - and in some files lowercased on
top of that, giving "Inã©s". The lowercasing moves the lead byte (Ã, 0xC3,
becomes ã, 0xE3), so it has to be undone before the bytes can be decoded again.

Only runs of two or more characters from U+0080-U+00FF are touched, and only
when they decode as valid UTF-8, so a correctly spelled "Inés" or "Désirée" -
whose accented letters stand alone between plain ones - is left as it is.
"""

import re

_RUN = re.compile(r"[\u0080-ÿ]{2,}")


def _decode(run):
    raw = bytes(ord(c) for c in run)
    try:
        return raw.decode("utf-8")
    except UnicodeDecodeError:
        pass
    # Undo a lowercasing: a lead byte from 0xE0 up that is followed by a
    # continuation byte (0x80-0xBF) was an uppercase 0xC0-0xDF before.
    fixed = bytearray(raw)
    for i in range(len(fixed) - 1):
        if 0xE2 <= fixed[i] <= 0xFE and 0x80 <= fixed[i + 1] <= 0xBF:
            fixed[i] -= 0x20
    try:
        return bytes(fixed).decode("utf-8")
    except UnicodeDecodeError:
        return None


def fix_text(s):
    """The string with any double-encoded runs decoded back."""
    if not isinstance(s, str) or not _RUN.search(s):
        return s
    return _RUN.sub(lambda m: _decode(m.group(0)) or m.group(0), s)


def fix_tree(obj):
    """fix_text applied to every string in a loaded JSON document."""
    if isinstance(obj, dict):
        return {fix_text(k): fix_tree(v) for k, v in obj.items()}
    if isinstance(obj, list):
        return [fix_tree(v) for v in obj]
    return fix_text(obj)
