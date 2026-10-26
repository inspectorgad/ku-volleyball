# iPhone app

The iPhone version of KU Volleyball, built by GitHub Actions and delivered
through TestFlight.

It is a thin shell around the shared Kotlin code in `shared/`: the same
screens, database, season sync and Ask as the Android app, drawn with
Compose Multiplatform. The Swift here only puts those screens on the phone.

- `project.yml` describes the Xcode project for XcodeGen; CI generates the
  `.xcodeproj` from it. Its build step runs Gradle to compile `shared/` into
  `Shared.framework` for the device or simulator being built.
- `App/` holds the Swift entry point and the icon. The season data the app
  starts from (`app/src/main/assets/seed.json`) is bundled too.
- `check_apple_keys.py` (run by the "Apple keys check" workflow) confirms the
  Apple secrets work without printing them.

The "iOS TestFlight build" workflow (`.github/workflows/ios-testflight.yml`)
builds the app on a GitHub Mac and uploads it to TestFlight. It runs when
anything here changes on a branch, or by hand from the Actions tab. Each
upload takes the workflow's run number as its build number, and Apple then
spends 5-30 minutes processing it before it appears in the TestFlight app.

Not on iPhone yet: match alerts (to come as push notifications from the
match-night watcher), the home-screen widget, and the result picture.

## Secrets

Set in the repository's Settings > Secrets and variables > Actions:
`APPLE_TEAM_ID`, `ASC_ISSUER_ID`, `ASC_KEY_ID`, `ASC_KEY_P8` (the App Store
Connect key that uploads builds) and `APNS_KEY_ID`, `APNS_KEY_P8` (the push
key for match alerts).
