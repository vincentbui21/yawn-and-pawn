---
title: 'Story 5.4: Reliability checklist screen and revoked-setting warnings'
type: 'feature'
created: '2026-10-19'
status: 'ready-for-dev'
baseline_revision: '862bc748dcdbe7781585e62eeadfd8f7717210d0'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-5-context.md'
  - '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-pay-per-snooze-2026-09-26/EXPERIENCE.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-5-3-full-reliability-probe-do-not-disturb-battery-and-the-locked-screen-test.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-5-5-manufacturer-background-guidance.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Story 5.3 evaluates the nine checklist rows (`EvaluateReliability`, `ReliabilityChecklist.alarmBlocking`),
but nothing shows them. Home's banner still uses the three Story 1.19 items (`ReliabilityStatus.allOk`), and its
"Fix" opens one system screen. The Settings "Reliability checklist" row is hidden, and the approved `ReliabilityScreen`
and `ChecklistCard` (design preview rounds 2–3) are not wired. Story 5.5's maker guidance has no caller.

**Approach:**
- **Checklist screen:** a pushed `Route.Reliability` with a `ReliabilityViewModel`.
  - It calls `EvaluateReliability` on every `ON_START`/`ON_RESUME` and maps each non-Hidden `ChecklistEntry` to the
    approved UI rows: Ok, Missing (from NeedsFix) or Revoked.
  - "Fix" goes through the row's own target: a widened `ReliabilitySettings.open(row)` port, the `CameraPermission`
    for Camera, and `openManufacturerGuidance()` for Manufacturer.
  - "Ring a test alarm" schedules a Story 1.18 test through `ScheduleTestAlarm`, using the soonest enabled alarm's
    settings (else the defaults).
- **Home and Settings banner:** on both, the `banner-warning` follows `alarmBlocking`, re-evaluated on every start, and
  its "Fix" opens the checklist. The Settings "Reliability checklist" row opens it too.
- **Deep links:** every target is checked with `resolveActivity`, falling back to app details, then
  `ACTION_SETTINGS`.

## Boundaries & Constraints

**Always:**
- Use the approved `ReliabilityScreen`, `ChecklistCard` and `checklist-row` layout (64 dp, icon, title, reason, then
  "OK" or "Fix"), tokens and existing strings only. Key strings are verbatim, and `CopyRulesTest` passes.
- Intents start only on a user tap, from the screen in front (AD-5). An intent that would not resolve is never
  started.
- Statuses are re-read on every return to the screen, so a fixed row shows "OK" without a tap. The banner cannot be
  dismissed and clears itself.
- Granting exact alarms still triggers `rescheduleAll()` (Story 1.10). The existing receiver stays, and a test
  re-asserts it.
- The session lock replaces every route, as today.

**Never:**
- No new permission. Specifically, no `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` and no `ACCESS_NOTIFICATION_POLICY`.
- No new string, token or colour pair.
- No changes to the checklist rules of Story 5.3 (`evaluate`, `lastOkAfter`, `BLOCKING_ROWS`) or to Story 5.5's guidance
  screen.
- No onboarding use (Stories 5.11 and 5.12).

## DEFAULTS TAKEN (owner can change)

1. **The Test alarm row has no "Fix".** This follows the approved design (`ChecklistRowView`): a hollow ring until a
   locked test passes. The "Ring a test alarm" button at the bottom rings it. The AC's "Fix → the Story 1.18 test
   alarm" is met by that button.
2. **Camera "Fix":** the first tap asks the runtime permission. If that answer is "no" (also "Don't ask again", which
   the app cannot tell apart), the next tap opens the app details page.
3. **Camera reason:** it keeps the EXPERIENCE.md / approved copy "Needed for your QR/Barcode and House Hunt checks."
   over the epic's shorter "Needed for your QR/Barcode check." The spines win (pps-design). This is an owner question
   recorded in `epic-5-context.md`.
4. **"Ring a test alarm":**
   - It uses the soonest enabled alarm by next occurrence: its sound, volume, vibration, quiet time and checks, as
     the editor's draft would be.
   - With no enabled alarm, it uses the built-in alarm defaults and the Settings defaults (Story 5.1).
   - It then shows the existing snackbar "Lock your phone. We'll ring in 10 seconds.", or the editor's existing test
     failure snackbar when scheduling fails.
5. **Manufacturer "Fix"** opens Story 5.5's `Route.ManufacturerGuidance` pushed over the checklist.
   `openManufacturerGuidance()` now pushes only over `Route.Reliability`, its one caller.
6. **Battery "Fix":** it opens `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` while the app is not exempt. When the
   app is exempt but background-restricted (or in the restricted bucket), it opens the app details page, where those
   settings live.
7. **The old API:** `ReliabilityItem`, `firstFailing` and `allOk` go once nothing uses them. `ReliabilitySettings`
   now takes a `ChecklistRow`.

## I/O & Edge-Case Matrix

| Scenario | State | Expected |
|---|---|---|
| All OK (API 34) | probe all good, test passed | 5 rows "OK" (Notifications, Full-screen, DND, Battery, Test; Manufacturer only if covered); no banner; "Ring a test alarm" shown |
| Several NeedsFix | notifications off, DND blocks | those rows "Fix" with the normal reason; banner on Home and Settings |
| Revoked battery | Battery in `lastOk`, now optimised | reason "Alarm may not ring: battery optimization turned back on" in `error`, "Fix" |
| Camera visible | enabled alarm with QR/Barcode, camera denied | Camera row "Fix" |
| API 31 | exact alarms denied | Exact alarms row shown, "Fix" opens `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` |
| Fix, target resolves | e.g. DND | `ACTION_ZEN_MODE_PRIORITY_SETTINGS` started |
| Fix, no resolver | target missing | app details; if that is missing too, `ACTION_SETTINGS`; none → logged, no crash |
| Return from settings | user fixed it | row "OK" on resume, banner gone on Home's next start |
| Only non-blocking rows fail | Camera / Test / Manufacturer not OK | no banner (`alarmBlocking` false) |
| Records unreadable | DataStore read fails | rows still shown (5.3 rules); nothing crashes |
| Test alarm | soonest enabled alarm exists / none | test scheduled with its settings / defaults; snackbar |
| Session starts | checklist open | "Alarm in progress" only |

</intent-contract>

## Code Map

- **Core:**
  - `core/.../reliability/Reliability.kt`: `ReliabilitySettings.open(row: ChecklistRow): Boolean` (false when nothing
    could open). Drop `ReliabilityItem` / `firstFailing` / `allOk` if they are unused afterwards.
  - Keep `ReliabilityChecklist.kt` as is (rules), and reuse `EvaluateReliability`.
- **Android:**
  - `androidApp/.../reliability/AndroidReliability.kt` (`AndroidReliabilitySettings`): the per-row intents. Check
    `resolveActivity` before each, then app details, then `ACTION_SETTINGS`. `ReliabilityModule.kt` handles the
    wiring, plus an `EvaluateReliability` factory if not already bound (Story 5.3 bound one: check).
- **composeApp:**
  - `composeApp/.../ui/reliability/ReliabilityViewModel.kt` (new), `ReliabilityRoute.kt` (new; `LifecycleStartEffect`
    / resume → `Refresh`).
  - `ReliabilityScreen.kt`: the approved UI. Keep `ChecklistItem`/`ItemStatus`, and add a mapping from core rows.
    `ReliabilityUiState` keeps working for the preview catalogue, plus an optional snackbar.
- **Navigation:**
  - `composeApp/.../ui/nav/Route.kt` (`Reliability`), `TabNavigation.kt` (`tab` → null),
    `ReliabilityNavigation.kt` (`openReliability(from)`: over Settings or Alarms; `openManufacturerGuidance` over
    Reliability).
  - `AppNavHost.kt` (the entry; Home and Settings callbacks), `TabNavigationTest.kt`.
- **Home:** `composeApp/.../ui/home/HomeViewModel.kt`, `HomeRoute.kt`, `HomeContract.kt`.
  - The banner comes from `alarmBlocking`: evaluate on `Started`, asynchronously.
  - `FixSettings` → an effect or callback that opens the checklist.
  - Remove the `ReliabilitySettings` dependency.
- **Settings:** `composeApp/.../ui/settings/SettingsViewModel.kt`, `SettingsRoute.kt`, `SettingsContract.kt`.
  - Add `SettingsRow.Reliability` to `BuiltSettingsRows`, and set `reliabilityProblem` from `alarmBlocking` on start.
  - `FixSettings` and `ReliabilityClicked` open the checklist.
- **Wiring:** `composeApp/.../ui/UiModule.kt`, `androidApp/.../YawnAndPawnApp.kt`.
- **Preview catalogue:** `androidApp/src/debug/.../preview/*` must keep compiling.
- **Tests:**
  - `composeApp/src/commonTest/.../reliability/ReliabilityViewModelTest.kt`: mapping, refresh, each Fix target,
    camera ask then details, test alarm with soonest alarm or defaults.
  - Home and Settings ViewModel tests: the banner from `alarmBlocking`, and Fix opening the checklist.
  - `androidApp/src/test/.../AndroidReliabilitySettingsTest.kt`: each row with and without a resolver, through
    Robolectric `ShadowPackageManager`.
  - In `androidApp/src/test/.../ui/`:
    - `ReliabilityScreenshotTest.kt`: all OK, several NeedsFix, Revoked battery, camera visible, API 31 exact row; in
      Light, Dark and Light 200 %.
    - `ReliabilitySemanticsTest.kt`: 64 dp rows, 48 dp Fix and Back, one merged node per row reading title, reason
      and status.
    - `ReliabilityRouteTest.kt` (real app): Settings → checklist; Home banner Fix → checklist; a fixed row turns OK on
      resume; Manufacturer Fix → guidance.
  - The `rescheduleAll` on exact-alarm grant test stays green.

## Tasks & Acceptance

**Execution:**
- [ ] Port and Android opener with resolver fallbacks, plus tests.
- [ ] Checklist ViewModel, route, navigation and wiring, plus tests.
- [ ] Home and Settings banner and row rewiring, plus tests.
- [ ] Screenshots, semantics and real-app route tests. Keep the preview catalogue and the existing Home and Settings
  tests green; re-record only the baselines that change on purpose.

**Acceptance Criteria:**
- Given Settings, when the user taps "Reliability checklist", then the pushed screen shows each non-Hidden row with its
  title, reason (or the Revoked reason) and "OK" or "Fix".
- Given a "Fix", when it is tapped, then its deep link starts (resolver-checked, with fallbacks), and on return the row
  re-evaluates.
- Given `alarmBlocking`, then Home and Settings show the non-dismissible banner, whose Fix opens the checklist. It
  clears itself when the rows are fixed.

## pps-design Done checklist

- [ ] Only tokens from `DESIGN.md` used.
- [ ] Light, Dark (and Sunrise where relevant) checked with previews / screenshots.
- [ ] Every colour pair used is in the `DESIGN.md` contrast table.
- [ ] Touch targets ≥ 48 dp; wake actions ≥ 64 dp.
- [ ] Works at 200% font scale and with TalkBack; outcome glyphs present.
- [ ] Reduced-motion path works.
- [ ] Copy matches `EXPERIENCE.md > Voice and Tone` (no em dashes, no filler, strings in resources).
- [ ] Every state row in `EXPERIENCE.md > State Patterns` for this surface is handled.
- [ ] "I'm up" is the most prominent wake action; snooze is visible, plain and priced.
- [ ] Compose `@Preview`s for each state (light/dark/Sunrise, empty/error) exist; screenshot tests updated.

## Spec Change Log

## Review Triage Log

## Verification

**Commands (targeted, `--no-daemon --max-workers=2`, run once at the end):**
- `spotlessCheck detekt`
- `:core:jvmTest --tests 'com.yawnandpawn.app.core.reliability.*'`
- `:composeApp:testAndroidHostTest --tests 'com.yawnandpawn.app.ui.reliability.*' --tests 'com.yawnandpawn.app.ui.home.*' --tests 'com.yawnandpawn.app.ui.settings.*' --tests 'com.yawnandpawn.app.ui.nav.*' --tests 'com.yawnandpawn.app.ui.UiModuleTest' --tests 'com.yawnandpawn.app.ui.CopyRulesTest'`
- `:androidApp:testDebugUnitTest` for the new reliability tests (Roborazzi record, then verify), plus the Home and
  Settings screenshot, semantics and route tests, `MainActivityTest`, `ReliabilityProbeTest`, the exact-alarm
  reschedule test, and the preview tests that cover reliability, Home and Settings.
