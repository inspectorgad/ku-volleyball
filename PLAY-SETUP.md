# Publishing KU Volleyball to Play Store internal testing

Internal testing distributes the app through the Play Store itself to up to
100 named testers — no sideloading, so Advanced Protection and Play Protect
never interfere, and updates install automatically.

CI already produces the signed Play bundle. Every build attaches
`app-release.aab` to the rolling release:

    https://github.com/inspectorgad/ku-volleyball/releases/latest/download/app-release.aab

## One-time setup (about 30 minutes, $25)

1. **Create a Google Play developer account** at
   https://play.google.com/console/signup with your personal Google account
   (one-time $25 fee). Choose a "personal" account type. Google requires an
   identity-verification step (usually a driver's license photo) that can
   take a day or two to clear.

2. **Create the app**: Play Console → All apps → **Create app**.
   - App name: `KU Volleyball` (internal testing isn't publicly listed)
   - Default language: English (US) · App or game: App · Free
   - Declarations: accept.

3. **Accept Play App Signing** when prompted (the default). Google holds the
   real signing key; the repo's committed `upload.keystore` is only the
   upload key and can be reset from the Console if ever needed
   (Setup → App signing → Request upload key reset).

4. **Set up internal testing**: Testing → **Internal testing** →
   **Create new release**.
   - Upload `app-release.aab` (downloaded from the link above).
   - Release name/notes: anything (e.g. "2025 season baked in").
   - Click **Next**, resolve any warnings, then **Save and publish** —
     internal testing releases go live in minutes, no review.

5. **Add testers**: still under Internal testing → **Testers** tab →
   create an email list containing your personal Gmail (and any friends'
   or family's) → save → copy the **opt-in link**.

6. **On the phone**: open the opt-in link, tap **Accept invite**, then
   **Download it on Google Play**. Installs like any Play Store app.

7. A few Console sections must be filled in before the release can publish
   (the Dashboard shows a checklist): Privacy policy (a GitHub Pages or
   even a repo README URL describing "no data collected" is fine), App
   access (all functionality available without login), Ads (no), Content
   rating questionnaire (utility, no objectionable content), Target
   audience (18+ keeps it simplest), Data safety (no data collected or
   shared — the app only downloads public stats).

## Updating the app later

Season *data* updates never need a new upload — the app syncs stats itself.
Only app *code* changes need one: download the newest `app-release.aab`
from the release link and upload it as a new internal-testing release
(two minutes). CI stamps each build with an increasing `versionCode`
(the workflow run number), so a newer AAB always uploads cleanly.

## Notes

- The upload keystore (`upload.keystore.base64`, password
  `kuvb-upload-2026`) is committed to this public repo for CI
  self-containment, mirroring the debug keystore. This is tolerable only
  because Play App Signing holds the real key and upload keys are
  resettable — but moving both to GitHub Actions secrets is better
  hygiene if the app ever goes beyond internal testing.
- "KU"/Jayhawks trademarks: fine for a private internal-testing app;
  a public Play listing would need a rename (e.g. "Rock Chalk Volleyball
  Stats") to survive review.
