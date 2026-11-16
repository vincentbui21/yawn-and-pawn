---
title: '0.1.9: "Rate Yawn & Pawn" and "Share Yawn & Pawn" rows in Settings'
type: 'feature'
created: '2026-11-16'
status: 'done'
route: 'oneshot'
review_loop_iteration: 0
context:
  - '{project-root}/.claude/skills/pps-design/SKILL.md'
  - '{project-root}/docs/EXPERIENCE.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** After the 14-day closed test (0.1.8 live), the app gives users no way to rate it on Google Play or tell a friend about it, so ratings and word of mouth only come from people who find the listing themselves.

**Approach (owner-approved 2026-11-16):** Two new rows in Settings directly after "About", in the same card as "Make sure alarms ring" and "About", each with the trailing "open in new" icon About's external rows use. "Rate Yawn & Pawn" opens the app's Google Play listing (`market://details?id=com.yawnandpawn.app` in the Play Store app; with no Play Store, the https listing in a browser; with neither, the existing "No browser found." snackbar). "Share Yawn & Pawn" opens Android's share sheet (ACTION_SEND text/plain in a chooser) with "Yawn & Pawn: an alarm clock where snoozing costs money and waking up is free. https://play.google.com/store/apps/details?id=com.yawnandpawn.app". The listing URL lives once, in `config/app-links.properties`. The Google Play In-App Review prompt (spec on branch `feat/in-app-review`) is optional for this PR: ship it only if it fits safely in ~45 extra minutes, else defer it. Version name/code unchanged.

</frozen-after-approval>

## Implementation Notes

- In-app review prompt: **deferred** (not in this PR). The draft spec needs a core policy, a DataStore-backed prompt store, a Play facade, new install-time and per-version tracking, and a touch point in `WakeActivity` (the alarm path) with Robolectric coverage; on this PC each targeted androidApp test run takes many minutes, so it would not fit in ~45 extra minutes with the care the alarm path needs. Recorded in `deferred-work.md` with the owner's conservative rules so the next PR starts from them.
- The market link is derived from the https listing (`market://details?id=` + its `id` parameter), so the URL is written once. The market intent is pinned to the Play Store package (`com.android.vending`): phones with their own store (Oppo App Market, Samsung Galaxy Store) would otherwise be offered, where the app is not listed.
- Share failing (no chooser, practically impossible) is only logged; the "No browser found." snackbar is wrong for it.
- Files: `config/app-links.properties` (`play.listing`; not `*.url`, which `checkSitePages` would match to a site page), `androidApp/build.gradle.kts` (`PLAY_LISTING_URL`), `YawnAndPawnApp.kt`, `ExternalLinks.kt` (`AppLinks.playListingUrl`/`playStoreAppUrl`, `openInPlayStore`, `shareText`), `AndroidExternalLinks.kt`, Settings contract/route/screen/ViewModel (`RateAndShare`, `noBrowser` snackbar in the TabScreen overlay), `UiModule.kt`, strings, test fakes. Tests: `SettingsRateShareViewModelTest`, `AndroidExternalLinksTest` (+4), `RateShareSettingsTest` (real app + 4 Roborazzi baselines). Existing Settings baselines unchanged.
- The real-app test scrolls Settings to its end before tapping: `performScrollTo` stops with the last row under the floating nav bar, where a tap lands on the bar.

## Review Triage Log

- Share links to a listing non-testers cannot see before production: real (low until launch) but the text and URL are the owner's; deferred to the owner (phone check / launch timing), not changed.
- No UTM `referrer` on the shared URL: real (low); changes the owner-approved text, left for the owner; noted in the PR.
- No EXTRA_SUBJECT / chooser title: low, Android 10+ ignores the chooser title; rejected (adds a string for little gain).
- Message joined in code instead of a `%1$s` resource: low, app is English only and EXPERIENCE.md states the format; rejected.
- `market://` derived without checking `id=`: low; patched (keeps only the `id` parameter, test added).
- project-status out of date: low; patched the date and the "Next" items.
- Spec missing sections: false; the oneshot route removes them by design.
- Adjacency test only checks order: low; rejected (the screenshot baseline pins the layout).
- Untested repeat-timer and share-failure log: low; rejected (same pattern as About, which is tested).
- Snackbar timer duplicated: low; rejected (matches the existing per-ViewModel pattern).
- TalkBack hint for leaving the app: low; consistent with About's external rows; rejected.
- Double tap opens twice: low; harmless (Play Store and chooser come to front); rejected.
- In-app review rules only in deferred-work.md: low; the entry names the draft branch; rejected.
