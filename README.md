# KU Volleyball

An Android app for following the Kansas Jayhawks Women's Volleyball team:
full match results with set scores, per-player box scores, season aggregates,
and team leaderboards — automatically updated throughout the season.

## Features

- **Roster** — the current Jayhawks roster (name, jersey number, position)
  with each player's career line at a glance. Tap a player for per-season
  aggregates, career totals, and a full match log.
- **Matches** — every match with result and set-by-set scores. Inside a
  match, every player's full stat line (SP, K, E, TA, hitting %, A, SA, SE,
  D, BS, BA, RE, BHE). Matches and lines can also be added or edited by hand.
- **Leaders** — filter by season (or all-time) to see the team's record,
  sets for/against, team hitting %, and leaderboards for hitting percentage,
  kills, assists, aces, digs, blocks, and points.

Derived stats (hitting %, kills/set, digs/set, points with block assists
counted half) are computed automatically from the raw counting stats.

All data is stored locally on the device in a Room (SQLite) database.

## Data pipeline

1. `scripts/scrape-ku-volleyball.mjs` (GitHub Actions, nightly) discovers KU
   matches via the [NCAA API](https://github.com/henrygd/ncaa-api) daily
   scoreboard, captures each finished match's official box score, and pulls
   the current roster + upcoming schedule from kuathletics.com.
2. `scripts/update-seed.py` regenerates `app/src/main/assets/seed.json`
   from the scraped data.
3. A seed change triggers the APK build workflow, which publishes the APK
   and `season-data.json` to the rolling `latest-apk` release.
4. On launch (and via pull-to-refresh) the app downloads `season-data.json`
   and merges it — gap-filling only, never overwriting user-entered data.

Install the latest build directly on a phone:
`https://github.com/inspectorgad/ku-volleyball/releases/latest/download/app-debug.apk`

## Tech

- Kotlin + Jetpack Compose (Material 3), KU crimson & blue theme
- Room for persistence
- Pure-Kotlin volleyball stats engine in `app/src/main/java/com/example/stats/`,
  covered by unit tests in `app/src/test/`

## Run Locally

**Prerequisites:** [Android Studio](https://developer.android.com/studio)

1. Open Android Studio
2. Select **Open** and choose the directory containing this project
3. Allow Android Studio to fix any incompatibilities as it imports the project
4. Run the app on an emulator or physical device
