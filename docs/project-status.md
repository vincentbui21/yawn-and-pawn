# Yawn & Pawn — project status

_Last updated: 2026-10-31_

## ▶ Where we are

**The app is in Google Play's closed test.** Every planned feature (Epics 1 to 7) is built. Google requires 12 or more testers to stay opted in for 14 days in a row before the app can go public.

- **Closed test** ("Closed testing - Alpha") started **2026-10-30** with Testers Community's tester group. Tester guide: [`closed-test.md`](closed-test.md). Opt-in link: https://play.google.com/apps/testing/com.yawnandpawn.app
- **Latest version: 0.1.8 (versionCode 108)**, 2026-10-31. It is live on the internal track (the owner's phone) and was sent to Google's review for the closed track.
- **Website and support email:** https://yawnandpawn.com (GitHub Pages from `docs/site`, domain bought through Cloudflare) and support@yawnandpawn.com (Cloudflare Email Routing).
- **Play Console:** store listing, App content, Data safety, content rating, EU trader status and the foreground-service demo video are all done. The listing text is in [`closed-test/store-listing-draft.md`](closed-test/store-listing-draft.md).
- **The repo stays public** until the app is published.

## Versions

| Version | Date | Tracks | What changed |
|---|---|---|---|
| 0.1.5 (105) | 2026-10-30 | Internal | Epic 7 features (recordings, own alarm sound, House Hunt, printable QR, skip next alarm), Sunrise clock icon |
| 0.1.6 (106) | 2026-10-30 | Internal, Closed | Links moved to yawnandpawn.com; the version the closed test started with |
| 0.1.7 (107) | 2026-10-31 | Internal, Closed | Alarms page entrance animation, "Alarm name" field, steady "Rings … at" line, "Share usage stats", "Make sure alarms ring", About page, simpler turn-off/delete pop-ups with smaller text, 14 cm printed QR, battery Fix opens App info, one global quiet time, snooze lengths 5/10/15/30 (default 10; app.db v16) (#112, #113) |
| 0.1.8 (108) | 2026-10-31 | Internal; Closed in review | One pop-up style everywhere, Test alarm in the editor's ⋮ menu, global "Skip reminder" notification 2 h before the first alarm of the day replaces the per-alarm "Offer to skip" (#114, #115) |

## How a release is made

GitHub has no signing or Play secrets, so releases are built on the owner's PC (see [`ci-release.md`](ci-release.md), "How releases are made now"):

1. Changes go through a pull request; it is squash-merged once CI is green.
2. The owner runs the release build with the upload keystore and `-Pyawnandpawn.versionName=0.1.X` (versionCode = 100 + X for 0.1.X).
3. The bundle (`androidApp/build/outputs/bundle/release/androidApp-release.aab`) is uploaded in Play Console: to **Internal testing** (published at once, reaches the owner's phone within about an hour) and to **Closed testing - Alpha** (needs "Send changes for review"; Google's review took a few hours so far).

## Next

1. **0.1.9:** tester feedback and polish. Check every few days: Play Console's pre-launch report and tester feedback, Firebase Crashlytics, support@ emails, and Testers Community's app page.
2. **Keep 12 or more testers opted in** until the 14 days are complete (about 2026-11-15).
3. **Apply for production access** (Play Console asks about the test; answers should be specific to what testers found and what was fixed), then publish.
4. Before the public launch: a promo video; the Epic 5 and 6 retrospectives.
5. After launch: move the site to Cloudflare Pages, then optionally make the repo private.

## Test phone

The owner's **Oppo A96** (Android 13, ColorOS), USB debugging on.
- **Use it only when the owner says the phone is free.** Keep rings at the lowest volume with vibration off, and leave test alarms off afterwards.
- Avoid `settings put` and `svc power`: on ColorOS they fail with a WRITE_SETTINGS error.
- The app on the phone comes from the Play internal track, so a locally built APK cannot be installed over it without uninstalling (which deletes the app's data).

## Open items

- `GOOGLE_SERVICES_JSON_RELEASE` and the signing secrets are not in GitHub; the release workflow skips the Play upload. Releases are built locally (above).
- Testers Community testers are not licence testers: a snooze payment would be a real charge, so the tester guide asks them not to pay.
- Play warns that the bundle has native code without debug symbols. Harmless; uploading symbols would make native crash reports easier to read.
