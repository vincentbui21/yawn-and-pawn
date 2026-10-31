---
title: '0.1.8: one pop-up pattern, Test alarm in the editor overflow menu'
type: 'feature'
created: '2026-10-31'
status: 'done'
baseline_commit: '26a821abdbd05d13db494c5e733f11e4167dbfc4'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/.claude/skills/pps-design/SKILL.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Owner feedback on 0.1.7: several pop-ups still put everything in one title line (skip, QR replace, recording delete), unlike the 0.1.7 turn-off dialog; and the editor's "Test alarm" text button under the cards looks out of place.

**Approach:** One PR (`feat/0-1-8-popups-test-alarm`), owner-approved 2026-10-31, no further sign-off:

1. Every `ConfirmDialog` follows one pattern: a short question as the title, an optional one-line grey body for the consequence, the action button and a "Keep ..." button.
   - Skip (Home): title "Skip the {time} alarm?", body when it next rings after the skipped one ("It rings again tomorrow." / "It rings again on {weekday}." / "It rings again on {long date}." when 7+ days away; review fix 2026-10-31, "next {weekday}" was ambiguous); a one-time alarm (switched off by the skip) has no body. Buttons "Skip" / "Keep it". "This is logged." is dropped from the visible text; the skip is still logged.
   - QR replace: "Replace your QR code?" / "The old one stops working." / "Replace" / "Keep it".
   - Recording delete: "Delete this message?" / "Alarms using it will play no message." / "Delete" / "Keep it".
   - Delete all data, Discard changes, Delete alarm, Turn off: already in the pattern; unchanged.
2. The alarm editor's "Test alarm" leaves the editor body and becomes the first item of the ⋮ "More options" menu in the editor header, for new and stored alarms (stored: Test alarm, Duplicate, Delete; new or duplicate: Test alarm only). Same behaviour (rings as configured, never charges, snackbar). The onboarding test alarm and the "Make sure alarms ring" test ring are unchanged.

## Boundaries & Constraints

**Always:** tokens only; strings in resources, no em dashes; TalkBack labels kept ("More options", menu items readable); 48 dp targets; both docs copies (`docs/` and `_bmad-output/planning-artifacts/ux-designs/...`) updated; Roborazzi baselines of changed screens re-recorded; version name/code untouched.

**Never:** no change to money, price, charge or refund wording; no change to the snooze confirm sheet (a payment wake sheet with its own spec, not a simple confirm); no change to skip logic or logging; no per-alarm "Offer to skip" removal (that is PR B).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Skip daily, today's ring | daily 07:00, now Mon 05:00 | "Skip the 7:00 AM alarm?" / "It rings again tomorrow." | N/A |
| Skip weekdays, Friday's ring | Mon-Fri 07:30, now Thu 22:00 | body "It rings again on Monday." | N/A |
| Skip weekly | Mon only, now Mon 05:00 | body "It rings again on Monday, {date}." | N/A |
| Skip one-time | once 07:00 | title only | N/A |
| Editor menu, new alarm | new alarm | ⋮ shows only "Test alarm" | N/A |
| Editor menu, stored alarm | stored alarm | "Test alarm", "Duplicate", "Delete" | N/A |

</frozen-after-approval>

## Code Map

UI paths under `composeApp/src/commonMain/kotlin/com/yawnandpawn/app/ui/`; strings in `composeApp/src/commonMain/composeResources/values/strings.xml`.

- `components/ConfirmDialog.kt` -- already has optional `body` (15 sp `dialogBody`, `textSecondary`) and 20 sp title; no change needed beyond KDoc.
- `home/HomeContract.kt` `SkipDialog(alarmId, time, occurrence)` -- add `ringsAgain: RingsAt?` (reuse `editor/RingsAt(daysAhead, dayOfWeek, time)`).
- `home/HomeViewModel.kt` `askSkip()` (~line 339) -- for a repeating rule compute `nextOccurrence(rule, occurrence, zone)` and days ahead from today's local date; null for one-time.
- `home/HomeScreen.kt` (~line 142) -- skip `ConfirmDialog` gets `body`; strings `skip_title` "Skip the %1$s alarm?", new `skip_rings_again_tomorrow`, `skip_rings_again_on_day`, `skip_rings_again_next_day`.
- `qr/QrRegistration.kt` (~line 199) -- `qr_replace_dialog` -> title "Replace your QR code?" + new `qr_replace_body`.
- `recordings/Recordings.kt` (~line 205) -- `recordings_delete_dialog` -> "Delete this message?" + new `recordings_delete_body`.
- `editor/EditorHeader.kt` -- header always shows the ⋮ button; `OverflowMenu(stored = state.hasOverflowMenu)` lists "Test alarm" (`editor_test_alarm`, `EditorIntent.TestAlarmClicked`) then Duplicate and Delete for stored alarms.
- `editor/AlarmEditorScreen.kt` `EditorMain` (~line 302) -- remove the `PpsTextButton` "Test alarm" and fix the KDoc.
- `editor/AlarmEditorContract.kt` `hasOverflowMenu` KDoc; `AlarmEditorViewModel.kt` unchanged (`TestAlarmClicked` already handled outside `onMenuIntent`).
- Debug preview `androidApp/src/debug/.../preview/TapThrough.kt` reuses the real editor screen: check only.
- KDocs quoting the old skip title: `AndroidPreAlarmNotifier.kt:29`, `MainActivity.kt:58`, `nav/AppNavHost.kt:73`, `nav/SkipRequests.kt:19`, `home/HomeRoute.kt:19`, `HomeContract.kt` `SkipDialog`.
- Tests: `composeApp/src/androidHostTest/.../CopyRulesTest.kt:76`, `commonTest/.../home/HomeSkipTest.kt` (dialog body), `androidApp/src/test/.../SkipRequestOpenTest.kt`, `ui/SkipNextAlarmScreenshotTest.kt`, `ui/QrScreenshotTest.kt`, `android/qr/PrintableQrAppTest.kt`, `PrintableQrScreenTest.kt`, `ui/EditorLockSemanticsTest.kt`, `ui/AlarmScreensSemanticsTest.kt` (Test alarm ~line 401, More options ~658-672), `ui/AlarmScreensScreenshotTest.kt:193-205`, `androidApp/src/testDebug/.../TapThroughTest.kt:107,129`, `Round3TapThroughTest.kt`, recordings tests with the old title.
- Docs: EXPERIENCE.md (both copies) lines ~47 (editor IA), 111 (editor rows), 251/255, 264/268, 268/272 (dialog strings), 303/309 (`button-text` example), 314/320 (`card-alarm` overflow), 349/355 (`dialog-confirm`); DESIGN.md `button-text` row (both copies).

## Tasks & Acceptance

**Execution:**
- [x] `home/*` -- skip dialog body (contract, view model, screen, strings) -- item 1.
- [x] `qr/QrRegistration.kt`, `recordings/Recordings.kt`, strings -- title + body -- item 1.
- [x] `editor/EditorHeader.kt`, `editor/AlarmEditorScreen.kt` -- Test alarm in the menu -- item 2.
- [x] KDocs, docs (both copies), CopyRulesTest.
- [x] Tests: view-model tests for the skip body (tomorrow / on day / next day / one-time); semantics test that a new alarm's ⋮ has Test alarm only and a stored alarm's has the three items, and Test alarm from the menu arms the test; fix tests using old copy.
- [x] Re-record Roborazzi baselines for changed screens (editor, home skip dialog, QR, recordings) and review the images.

**Acceptance Criteria:**
- Given any confirm pop-up, then its title is one short question and any consequence is the grey body line under it.
- Given the editor (new or stored), when "More options" > "Test alarm" is tapped, then a test ring is armed with the form's values and "Lock your phone. We'll ring in 10 seconds." shows; the body has no "Test alarm" button.
- `./gradlew qualityGate` passes in CI.

## Implementation Notes

- `SkipDialog.ringsAgain` (a home-owned `RingsAgain(daysAhead, date)`) is computed in `HomeViewModel.askSkip` as `nextOccurrence(rule, occurrence, zone)`, days counted from today's local date; one-time alarms get `null`. `HomeScreen.skipRingsAgain` maps 1 -> tomorrow, 2..6 -> on {weekday}, 7+ -> on {long date} (see Spec Change Log).
- `EditorHeader` always renders the title row with the ⋮ button; `OverflowMenu(stored)` lists Test alarm, then Duplicate/Delete when stored.
- Roborazzi baselines re-recorded: editor screens (alarm_editor_*, editor_checks_main_*, editor_offer_skip_*, editor_pending_main_*), home_skip_dialog_*, qr_printable_replace_*, preview/editor_*, preview/qr_replace_dialog_*, preview/recordings_delete_dialog_*.

## Spec Change Log

- Review fix: "It rings again next {weekday}." was ambiguous at 8+ days (a Monday-only alarm asked Sunday night, or a weekly alarm asked after today's ring: 14 days). 7+ days now says "It rings again on {date}." ("on Monday, November 9."), reusing `skip_rings_again_on_day`; `skip_rings_again_next_day` is removed.

## Review Triage Log

| # | Layer | Finding | Verdict | Evidence / route |
|---|---|---|---|---|
| 1 | edge+blind | "next {weekday}" wrong at 8 or 14 days (Sunday-night ask, weekly alarm after today's ring) | medium | Reproduced from askSkip math. Patched: 7+ days says the long date. |
| 2 | edge+blind | Body goes stale if the dialog stays open past midnight | low | Same as the editor header (0.1.7 triage #19); fix needs a clock subscription. Rejected. |
| 3 | blind | Day wording duplicated with the editor; 0 days reads "tomorrow" | low | 0 days cannot occur (the following ring is after the skipped one). Rejected. |
| 4 | blind | Home borrows editor `RingsAt` | low | Patched with item 1 (home-owned `RingsAgain`). |
| 5 | blind | `hasOverflowMenu` now means "stored" | low | KDoc states it; rename would touch many tests. Rejected. |
| 6 | blind+verif | KDocs quote old one-line dialogs / Test alarm as a body row | low | Direct correction. Patched. |
| 7 | blind | closed-test.md and the Play app-access text point to a gone button | medium | Play reviewers follow it. Patched. |
| 8 | blind | 0.1.8 doc rows lack the owner decision date | low | Patched: "owner decision 2026-10-31". |
| 9 | blind | New alarm gets a one-item menu | false | Owner decision 2026-10-31: Test alarm goes in the overflow menu. |
| 10 | blind | No copy test for QR/recordings bodies | low | Rejected (screenshot and tap-through tests check them). |
| 11 | blind | Thin screenshot coverage of long bodies at 200% | low | Rejected. |
| 12 | blind | No AlreadySkipped / DST body tests | low | Rejected; same math as nextOccurrence, covered in core. |
| 13 | blind | Inconsistent assertion style in SkipRequestOpenTest | low | Direct. Patched. |
| 14 | verif | No verification gaps | false | n/a |

## Design Notes

The ⋮ button now shows on new alarms too; the title row keeps the same layout as the stored editor. The snooze confirm sheet keeps its `headline` title: it is a wake-screen payment sheet (pps-design rule 8), not a simple confirm, and its wording is money wording.

## Verification

**Environment (company PC, no admin):** worktree `C:/Users/BuiTua/Personal/yp-018`; `JAVA_HOME=C:/Users/BuiTua/AppData/Local/Programs/jdk17/jdk-17.0.20.1+1`; ONE Gradle build at a time, targeted tasks, `./gradlew --stop` when done; no adb; never edit `C:/Users/BuiTua/Personal/yawn-and-pawn`.

**Commands:**
- `./gradlew :composeApp:testAndroidHostTest :androidApp:testDebugUnitTest --tests <changed classes>` -- pass
- `./gradlew :androidApp:recordRoborazziDebug -Proborazzi.test.verify=false --tests <screenshot classes>` -- baselines updated
- `./gradlew qualityGate` -- pass (CI)
