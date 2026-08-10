## A1. NCAA roster endpoint candidates

- `/rosters/volleyball-women/d1/pittsburgh` -> **422**
- `/roster/volleyball-women/d1/pittsburgh` -> **422**
- `/team/volleyball-women/d1/pittsburgh` -> **422**
- `/teams/volleyball-women/d1/pittsburgh` -> **422**
- `/schools/pittsburgh` -> **404**
- `/school/pittsburgh/volleyball-women` -> **422**
- `/stats/volleyball-women/d1/current/individual/1116` -> **404**

## A2. Opponent season from the scoreboard sweep (2025 as the model)

- 2025-09-05: 207 D1 games that day, 1 involving pittsburgh
- 2025-09-06: 167 D1 games that day, 0 involving pittsburgh
- 2025-09-12: 195 D1 games that day, 1 involving pittsburgh
- 2025-09-19: 170 D1 games that day, 0 involving pittsburgh
- 2025-10-03: 120 D1 games that day, 1 involving pittsburgh
- 2025-10-17: 119 D1 games that day, 1 involving pittsburgh

Found 4 pittsburgh games across the sampled dates.

**Pittsburgh** — 14 players in the box score:
  - #25 Haiti Tautua'a (S)
  - #5 Olivia Babcock (OH)
  - #20 Abbey Emch (MB)
  - #6 Sophia Gregoire (OH)
  - #3 Emery Dupes (L)
  - #21 Bre Kelley (MB)
  - #13 Mallorie Meyer (L/DS)
  - #14 Kiana Dinn (S)
  - #17 Brooke Mosher (S)
  - #19 Dagmar Mourits (OH)
  - #10 Marina Pezelj (OH)
  - #9 Ryla Jones (MB)
  - #7 Izzy Masten (L/DS)
  - #8 Blaire Bayless (OH)

**Pepperdine** — 12 players in the box score:
  - #3 Chloe Pravednikov (OH)
  - #1 Irelynd Lorenzen (MB)
  - #21 Laine Briggs (L/DS)
  - #5 Emma McMahon (DS)
  - #6 Brynne McGhie (S)
  - #9 Tristen Raymond (S)
  - #20 Vanessa Polk (MB)
  - #12 Maggie Beauer (OH)
  - #11 Ella Irwin (L/DS)
  - #10 Grace Jackson (MB)
  - #14 Ryan Gilhooly (OH)
  - #18 Ella Piskorz (MB)

## B. Opponent roster pages (Sidearm?)

- **Pittsburgh** `pittsburghpanthers.com` -> 200, parser found **15 players** (15 with height) — e.g. Maaike Heilig #2 MB 6-4
- **Stanford** `gostanford.com` -> 200, parser found **0 players** (0 with height)
- **Wichita State** `goshockers.com` -> 200, parser found **0 players** (0 with height)
- **Creighton** `gocreighton.com` -> 200, parser found **17 players** (17 with height) — e.g. Trinity Shadd-Ceres #0 OH 5-11
- **Lipscomb** `lipscombsports.com` -> 200, parser found **14 players** (10 with height) — e.g. Luca Bredenberg #2 DS/L 5-7
- **Florida State** `seminoles.com` -> 200, parser found **18 players** (18 with height) — e.g. Payton Whalen #0 Outside Hitter 6-1
- **South Dakota State** `gojacks.com` -> 200, parser found **16 players** (16 with height) — e.g. Kennedey Whitford #2 DS 5-4
- **Tulsa** `tulsahurricane.com` -> 200, parser found **16 players** (0 with height) — e.g. Lexi Dahl #1  (no height)
- **Iowa State** `cyclones.com` -> 200, parser found **19 players** (19 with height) — e.g. Pam McCune #1 MB 6-0

**7/9 school sites parsed with the existing KU parser, unchanged.**
