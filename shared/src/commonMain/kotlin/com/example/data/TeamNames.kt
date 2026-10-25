package com.example.data

/**
 * Canonical key for matching one school's name across sources.
 *
 * No two sources spell a school the same way. The NCAA box score says
 * "Florida St." where kuathletics' schedule says "Florida State"; a roster
 * scraped from the school's own site may say either, and a poll line arrives as
 * "#12 Arizona State". Comparing those strings directly is what filed the
 * Florida State match twice, and what left six opponents' rosters - Arizona
 * State, Iowa State, Kansas State, Wichita St., South Dakota St. and Florida
 * St. - collected but unreachable, because the screens looked them up by a name
 * that did not match the one they were stored under.
 *
 * So nothing compares team names directly. Everything compares this.
 */
fun normTeam(name: String): String =
    name.trim()
        .replace(Regex("^#\\d+\\s+"), "")
        .replace(Regex("\\s*\\((?:\\d+|[Ee]xh\\.?|[Ee]xhibition)\\)\\s*$"), "")
        .lowercase()
        .replace(".", "")
        .replace(Regex("\\bstate\\b"), "st")
        .replace(Regex("\\s+"), " ")
        .trim()

/** True when two spellings name the same school. */
fun sameTeam(a: String, b: String): Boolean = normTeam(a) == normTeam(b)
