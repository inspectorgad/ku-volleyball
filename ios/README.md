# iPhone app

The iPhone version of KU Volleyball, built by GitHub Actions and delivered
through TestFlight.

- `project.yml` describes the Xcode project for XcodeGen; CI generates the
  `.xcodeproj` from it.
- `App/` holds the app's code and its icon.
- `check_apple_keys.py` (run by the "Apple keys check" workflow) confirms the
  Apple secrets work without printing them.

The "iOS TestFlight build" workflow (`.github/workflows/ios-testflight.yml`)
builds the app on a GitHub Mac and uploads it to TestFlight. It runs when
anything here changes on a branch, or by hand from the Actions tab. Each
upload takes the workflow's run number as its build number, and Apple then
spends 5-30 minutes processing it before it appears in the TestFlight app.

For now the app is a test build that shows the season dashboard; the real
app, sharing its code with the Android one, replaces it.

## Secrets

Set in the repository's Settings > Secrets and variables > Actions:
`APPLE_TEAM_ID`, `ASC_ISSUER_ID`, `ASC_KEY_ID`, `ASC_KEY_P8` (the App Store
Connect key that uploads builds) and `APNS_KEY_ID`, `APNS_KEY_P8` (the push
key for match alerts).
