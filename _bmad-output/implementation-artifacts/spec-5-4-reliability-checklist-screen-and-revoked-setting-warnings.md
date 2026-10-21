---
title: 'Story 5.4: Reliability checklist screen and revoked-setting warnings'
type: 'feature'
created: '2026-10-19'
status: 'done'
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
- [x] Port and Android opener with resolver fallbacks, plus tests.
- [x] Checklist ViewModel, route, navigation and wiring, plus tests.
- [x] Home and Settings banner and row rewiring, plus tests.
- [x] Screenshots, semantics and real-app route tests. Keep the preview catalogue and the existing Home and Settings
  tests green; re-record only the baselines that change on purpose.

**Acceptance Criteria:**
- Given Settings, when the user taps "Reliability checklist", then the pushed screen shows each non-Hidden row with its
  title, reason (or the Revoked reason) and "OK" or "Fix".
- Given a "Fix", when it is tapped, then its deep link starts (resolver-checked, with fallbacks), and on return the row
  re-evaluates.
- Given `alarmBlocking`, then Home and Settings show the non-dismissible banner, whose Fix opens the checklist. It
  clears itself when the rows are fixed.

## pps-design Done checklist

- [x] Only tokens from `DESIGN.md` used. The approved `ChecklistCard` / `checklist-row` layout is unchanged; the
  snackbar is the shared `AppSnackbar`. No raw colour, radius or size.
- [x] Light, Dark (and Sunrise where relevant) checked with previews / screenshots. `ReliabilityScreenshotTest` (Light,
  Dark, Light 200 %), inspected. Sunrise does not apply (not a wake screen).
- [x] Every colour pair used is in the `DESIGN.md` contrast table. Only the approved row pairs (`success`, `error`,
  `text`, `text-secondary` on glass) and the existing snackbar and button pairs; no new pair.
- [x] Touch targets ≥ 48 dp; wake actions ≥ 64 dp. `ReliabilitySemanticsTest`: rows ≥ 64 dp, "Fix", Back and "Ring a
  test alarm" ≥ 48 dp; the Settings row ≥ 48 dp (`DefaultsSettingsSemanticsTest`). No wake action here.
- [x] Works at 200% font scale and with TalkBack; outcome glyphs present. 200 % screenshots for each state; each row is
  one TalkBack stop reading title, reason (or the revoked line) and "OK", with "Fix" its own button read right after;
  OK / error / hollow-ring glyphs carry the status besides colour.
- [x] Reduced-motion path works. No animation of its own; the push uses the shared screen transition.
- [x] Copy matches `EXPERIENCE.md > Voice and Tone` (no em dashes, no filler, strings in resources). No new string; the
  existing verbatim keys only (`CopyRulesTest` passes).
- [x] Every state row in `EXPERIENCE.md > State Patterns` for this surface is handled. "Permission or setting missing"
  (Home and Settings banner), "All OK" (every row OK, "Ring a test alarm" stays), "Session active" (the lock replaces
  the checklist, `TabNavigationTest`).
- [x] "I'm up" is the most prominent wake action; snooze is visible, plain and priced. Does not apply: not a wake
  screen.
- [x] Compose `@Preview`s for each state (light/dark/Sunrise, empty/error) exist; screenshot tests updated. The design
  preview panes (round 2) keep rendering the same composable; `ReliabilityScreenshotTest` adds 16 goldens; the 8
  Settings main-screen goldens are re-recorded because the "Reliability checklist" row now shows.

## Spec Change Log

- 2026-10-20 (implementation, resumed after the first implementer was cut off):
  - **Port and opener.** `ReliabilitySettings.open(row: ChecklistRow): Boolean`; `ReliabilityItem`, `firstFailing`
    and `allOk` are gone (and `ReliabilityStatusTest` with them). `AndroidReliabilitySettings` moved to its own file:
    each target (row screen, app details, `ACTION_SETTINGS`, no repeats) is started only when `resolveActivity`
    resolves it; a throwing start or lookup falls through; nothing resolving is logged and answers false. The manifest
    `<queries>` names each settings action (package visibility, not a permission). The old `ReliabilitySettingsTest` is
    split into `AndroidReliabilitySettingsTest` (the opener) and `AndroidNotificationPermissionTest` (unchanged tests).
  - **Test alarm failure (DEFAULT 4):** the editor has no test-failure snackbar (it logs and shows nothing), so the
    checklist does the same: logged, no snackbar, no new string.
  - **Banner timing:** Home and Settings no longer probe when the ViewModel is created; the banner is false until the
    first evaluation, which runs (without waiting) on `ON_START` / `ON_RESUME` (`AlarmBlockingCheck`, one at a time).
  - **TalkBack:** the row's `mergeDescendants` moved from the inner title block to the whole 64 dp row, so one stop
    covers icon, title, reason and "OK", and its height is the row's; "Fix" stays its own button (no visual change).
  - **Navigation:** the pushed entries (Purchase history, "Problem with a charge?", the checklist and the maker
    guidance) moved to `ui/nav/PushedEntries.kt` because of detekt's `TooManyFunctions` and `LongMethod` on
    `AppNavHost.kt`; `SettingsViewModel.onIntent` hands the three reliability intents to one helper
    (`CyclomaticComplexMethod`).
  - **Tests updated for the new behaviour:** `MainActivityTest` and `DefaultsSettingsSemanticsTest` now expect the
    "Reliability checklist" row; Home tests build the real `EvaluateReliability` over fakes (`anEvaluateReliability`).

## Review Triage Log

### 2026-10-21 — Review pass (fast mode: one combined self-review; the coordinator runs the independent review)
- verdicts: 4 findings — high 0, medium 0, low 4, false 0, maybe-false 0
- findings:
  - `[low]` `[patch]` `ReliabilityRouteTest` checked the rows as soon as the screen showed, before the first evaluation (which reads the records DataStore on the main looper) had filled them. It failed on the merged tree. It now waits for the row (`waitForRow`) after the banner's Fix in both tests.
  - `[low]` `[patch]` After the rebase onto Story 5.6, `SettingsViewModel` broke detekt's `TooManyFunctions` and `CyclomaticComplexMethod`. The usage-stats and reliability intents now share one handler (`onGeneralIntent`), and the read-failure log moved to a top-level `Logger.logFailure`. No behaviour change.
  - `[low]` `[reject]` Home, Settings and the checklist each evaluate on resume, so two writes of `lastOk` can overlap. Each write is the full set computed from the same probe and records, so the last writer is the right value. Locking across screens would only add machinery.
  - `[low]` `[reject]` Each foreground re-reads the records DataStore once per visible screen. It is cheap (one small preferences file), and the AC asks for re-evaluation on every start.

## Verification

**Commands (targeted, `--no-daemon --max-workers=2`, run once at the end):**
- `spotlessCheck detekt`
- `:core:jvmTest --tests 'com.yawnandpawn.app.core.reliability.*'`
- `:composeApp:testAndroidHostTest --tests 'com.yawnandpawn.app.ui.reliability.*' --tests 'com.yawnandpawn.app.ui.home.*' --tests 'com.yawnandpawn.app.ui.settings.*' --tests 'com.yawnandpawn.app.ui.nav.*' --tests 'com.yawnandpawn.app.ui.UiModuleTest' --tests 'com.yawnandpawn.app.ui.CopyRulesTest'`
- `:androidApp:testDebugUnitTest` for the new reliability tests (Roborazzi record, then verify), plus the Home and
  Settings screenshot, semantics and route tests, `MainActivityTest`, `ReliabilityProbeTest`, the exact-alarm
  reschedule test, and the preview tests that cover reliability, Home and Settings.

**Results (2026-10-20, JDK 17, `--no-daemon --max-workers=2`):**
- `spotlessCheck detekt`: pass (after moving the pushed entries and the Settings intent helper, see the change log).
- `:core:jvmTest` (`core.reliability.*`): 22 tests, pass. `:testing:jvmTest` (`ReliabilityFakesTest`): 4, pass.
- `:composeApp:testAndroidHostTest` (`ui.reliability.*`, `ui.home.*`, `ui.settings.*`, `ui.nav.*`, `UiModuleTest`,
  `CopyRulesTest`): 170 tests, pass; re-run after the detekt refactors: pass (see the rebase results below).
- `:androidApp:recordRoborazziDebug` for `ReliabilityScreenshotTest` (16 new goldens) and the Settings main-screen
  classes (8 goldens changed on purpose: the "Reliability checklist" row now shows; the sub-screen goldens are
  unchanged). Inspected.
- `:androidApp:testDebugUnitTest` in verify mode, all pass: `ReliabilityScreenshotTest` 16, `ReliabilitySemanticsTest`
  5, `ReliabilityRouteTest` 3, `AndroidReliabilitySettingsTest` 11, `AndroidNotificationPermissionTest` 4,
  `ManufacturerSettingsTest` 8, `ReliabilityProbeTest` 23, `SnoozeSettingsScreenshotTest` 18,
  `DefaultsSettingsScreenshotTest` 11, `SnoozeSettingsSemanticsTest` 6, `DefaultsSettingsSemanticsTest` 6,
  `SnoozeSettingsRouteTest` 3, `HomeScreenshotTest` 35, `HomeRouteLifecycleTest` 1, `HomeInsetsTest` 3,
  `AlarmScreensScreenshotTest` 34, `AlarmScreensSemanticsTest` 32, `SessionLockScreenTest` 7, `MainActivityTest` 20,
  `SystemEventsReceiverTest` 12 and `ReceiversManifestTest` 7 (exact-alarm grant → `rescheduleAll`), and the preview
  tests `PreviewEditorScreenshotTest` 152, `PreviewEditorSemanticsTest` 342, `Round2TapThroughTest` 6,
  `Round3TapThroughTest` 8, `TapThroughTest` 5, `PreviewMenuTest` 8, `NavBarScreenshotTest` 4.
- Not run here (run constraints): the full `qualityGate`, Kover, and the other test classes (CI runs them).

**Rebase onto main 2d35e706 (Stories 5.6 and 5.8), 2026-10-21, JDK 17, `--no-daemon --max-workers=2`:**
- Conflicts were resolved by keeping both sides:
  - Koin modules, the nav comments, the You/Payments entries now inside `pushedEntries`.
  - Settings: the usage stats row and the reliability row, the consent ports and the checklist.
  - `UiModuleTest` and `DefaultsSettingsSemanticsTest`.
- Five Settings main-screen baselines were re-recorded on the merged tree, because they now show usage stats and the
  checklist row: `settings_defaults_light/dark/changed_light` and `settings_snooze_light/dark`.
- Passed:
  - `spotlessCheck`, `detekt`, `:core:jvmTest` (`core.reliability.*`) and `:testing:jvmTest` (`ReliabilityFakesTest`).
  - `:composeApp:testAndroidHostTest`, filtered to the reliability, home, settings, nav and you packages, plus
    `UiModuleTest` and `CopyRulesTest`: 190 tests.
  - `:androidApp:testDebugUnitTest`: the reliability classes, the Settings screenshot, semantics and route classes, the
    Home classes, `SessionLockScreenTest`, `AlarmScreensSemanticsTest`, `MainActivityTest`, `YouRouteTest`,
    `ThemeOverrideTest`, and the preview tap-throughs, `PreviewMenuTest` and `NavBarScreenshotTest`.

## Review (fast mode)

2026-10-21, one combined reviewer (PR #74, head 3cad248d): 8 findings, 1 medium and 7 low, no high. All 8 are fixed,
each with its test. No new string, token or colour pair. Story 5.3's rules (`evaluate`, `lastOkAfter`, `BLOCKING_ROWS`)
are unchanged.

1. **[medium] Camera "Fix" did nothing visible once the permission was blocked.**
   - Fix: an additive port change.
     - `CameraPermission` gains `enum class CameraAnswer { Granted, Denied, Blocked }` and `requestAnswer()`. Its
       default bridges `request()`, so Story 3.10's callers and fakes are unchanged.
     - `AndroidCameraPermission.onResult(granted, blocked)` reports Blocked when `MainActivity` sees
       `shouldShowRequestPermissionRationale` false after a "no".
     - The checklist's Camera Fix: Granted refreshes. Denied changes nothing visible on this tap (the next tap opens
       the app details, as before). Blocked opens the app details (`settings.open(Camera)`) in the same tap.
   - DEFAULT TAKEN (owner can change): a first dialog dismissed without an answer also reads "rationale false" on API
     30+, so it counts as Blocked and opens the app details. The app cannot tell these two apart without storing
     whether it asked before.
   - Tests:
     - `ReliabilityViewModelTest`: "Camera blocked (no dialog showed) opens the app details in the same tap".
     - `AndroidCameraPermissionTest` (Robolectric): Denied, Blocked, `request()` still false, and Granted.
2. **[low] Notifications Fix opened the app page when only the Alarms channel was blocked.**
   - Fix: `AndroidReliabilitySettings` opens `ACTION_CHANNEL_NOTIFICATION_SETTINGS` when app notifications are on and
     the Alarms channel is at `IMPORTANCE_NONE`. It passes `EXTRA_APP_PACKAGE` and `EXTRA_CHANNEL_ID` =
     `WakeNotifier.CHANNEL_ID`.
     - Otherwise it opens the app's notification settings, as before.
     - It keeps the same resolver fallbacks (app details, then `ACTION_SETTINGS`).
     - A channel read that throws is logged and counts as not blocked.
     - The action is added to the manifest `<queries>`.
   - Test: `AndroidReliabilitySettingsTest` uses a real Robolectric channel at `IMPORTANCE_NONE` and checks the extras,
     the start, and the app page once notifications are off.
3. **[low] A revoked Battery row blamed battery optimisation for every cause.**
   - Fix (additive in `:core`): `EvaluateReliability.evaluation()` returns `ReliabilityEvaluation(rows, status)` from
     one probe read. `invoke()` returns its rows, as before. `toChecklistRows(status)` shows "Alarm may not ring:
     battery optimization turned back on" only when `!batteryOptimizationIgnored`.
   - DEFAULT TAKEN (owner can change): a revoked Battery row caused by background restriction or the restricted bucket
     maps to `ItemStatus.Missing`. It shows the normal Battery reason, the error icon and "Fix". It stays Revoked in
     `lastOk` and still raises the banner. No UI field was added, because Missing renders exactly that row.
   - Tests:
     - `ReliabilityViewModelTest`: "a revoked battery row names battery optimisation only when that is the cause".
     - `ReliabilityChecklistTest`: the evaluation hands out the rows with its one probe status.
4. **[low] Evaluation ran twice per start (`ON_START` and `ON_RESUME`) on Main.**
   - Fix: the checklist route and Home now refresh on `ON_RESUME` only, which ends every start. `HomeIntent.Started` is
     gone. Settings already had a single `ON_START` trigger and keeps it.
     - The evaluation runs in an injected `evaluationContext` (default `Dispatchers.Default`). It is used by
       `ReliabilityViewModel`, `AlarmBlockingCheck`, `HomeViewModel` and `SettingsViewModel`.
     - Tests pass their test dispatcher, or `EmptyCoroutineContext` in Robolectric route tests for exact counts.
   - Tests:
     - `ReliabilityRouteLifecycleTest` (new): one probe call per stop/start and one per pause/resume.
     - `HomeRouteLifecycleTest`: exactly +1 per cycle.
     - `HomeViewModelTest`: one probe call per resume.
5. **[low] "Ring a test alarm" failed silently on API 31-32 with exact alarms denied, and ignored the Settings.**
   - Fix: after the test, success or failure, the checklist calls `refresh()`, so the Exact alarms row shows.
     `ChecklistTestAlarm` now reads the Settings on every test and passes them as `ScheduleTestAlarm(globalSettings =
     ...)`, so the base fee tier and max snoozes are the user's. The alarms are read first, then the Settings, so the
     log order is unchanged.
   - Tests (`ReliabilityViewModelTest`):
     - The failure test (API 31, exact alarms denied) now expects the Exact alarms row after the failed test.
     - The success test checks one re-read.
     - New: "the test rings with the Settings' base fee tier and max snoozes".
6. **[low] Fix buttons read only "Fix" to TalkBack.**
   - Fix: each Fix button's `contentDescription` is "<row title>, <Fix>", built from the row title and `home_fix`.
   - DEFAULT TAKEN (owner can change): the Test alarm row, while not OK (no Fix, hollow ring), gets the
     `stateDescription` "Ring a test alarm" (`reliability_ring_test`). Its reason is already read as text, and that
     label names the button below that turns the row OK. No new string.
   - Tests (`ReliabilitySemanticsTest`):
     - New: "each Fix names its row, and the Test alarm row not yet rung reads its state". The OK row has no state.
     - The reading-order test now expects "Notifications, Fix" and "Do Not Disturb, Fix".
7. **[low] The manifest `<queries>` test compared action names only.**
   - Fix: it now parses each `<intent>` into (action, data scheme). It compares that set with the targets the opener
     builds for every row on API 28, 31 and 34, with the battery exempt or not and the Alarms channel blocked or not.
   - Test: `AndroidReliabilitySettingsTest` "the manifest queries every settings target the checklist opens, by action
     and data scheme".
8. **[low] Missing real-wiring tests.**
   - Fix: three new `ReliabilityRouteTest` cases in the real app, with the real Koin graph, navigation, DataStore,
     `ScheduleTestAlarm` and `AlarmManager`:
     - "Ring a test alarm" with no alarms arms the test alarm (`RequestCodes.TEST_ALARM` in `ShadowAlarmManager`) and
       shows "Lock your phone. We'll ring in 10 seconds.".
     - An enabled QR alarm (saved through `SaveAlarm`) with the camera denied: the banner clears once only the camera
       fails, and the checklist shows the Camera row with "Camera, Fix".
     - On API 31 with exact alarms denied, the Home banner shows. Its Fix opens the checklist at the Exact alarms row,
       whose Fix opens `ChecklistRow.ExactAlarms`.
   - The probe stays a fake there (as in the existing route tests), because Robolectric's power manager reports the
     app as optimised, which would raise the banner for Battery.
   - API 31 comes from the `DeviceInfo` the checklist reads (`sdkInt = 31`), and Robolectric stays on SDK 34. Under
     `@Config(sdk = [31])` the evaluation started (one probe call), but Home's alarm list and the banner never loaded
     within 30 s. The Exact alarms screen opener on API 31 is covered by `AndroidReliabilitySettingsTest`
     (`@Config(sdk = [31])`).

**Verification (2026-10-21, JDK 17, `--no-daemon --max-workers=2`, targeted):**
- `spotlessApply`, then `spotlessCheck` and `detekt`: pass.
- `:core:jvmTest` (`core.reliability.*`): pass.
- `:composeApp:testAndroidHostTest`: 211 tests across `ui.reliability.*`, `ui.home.*`, `ui.settings.*`, `ui.qr.*`,
  `AlarmEditorQrTest`, `ui.nav.*`, `UiModuleTest` and `CopyRulesTest`.
  - `UiModuleTest`'s checklist case first failed, because the app's default context now evaluates off the main
    thread. It now waits for the rows in real time, and passes.
- `:androidApp:testDebugUnitTest`: 421 tests across these classes:
  - the reliability classes (route, route lifecycle, semantics, screenshot in verify mode);
  - `AndroidReliabilitySettingsTest`, `AndroidNotificationPermissionTest`, `ReliabilityProbeTest`;
  - `AndroidCameraPermissionTest`, `QrCheckScreenTest`, `QrScreenshotTest`;
  - the Home classes (screenshot, insets, route lifecycle) and `AlarmScreensSemanticsTest`;
  - `SessionLockScreenTest`, `DefaultsSettingsSemanticsTest`, `SnoozeSettingsRouteTest`, `MainActivityTest`,
    `ReceiversManifestTest`;
  - the preview tap-throughs (`TapThroughTest`, `Round2TapThroughTest`, `Round3TapThroughTest`) and
    `PreviewSemanticsTest`.

  The only failure was the API 31 route case under `@Config(sdk = [31])`. It was changed as described in item 8, and
  `ReliabilityRouteTest` was re-run: 6 tests pass.
- No Roborazzi baseline changed: the fixes change semantics only, not pixels.

## Auto Run Result

Status: done (fast mode). The coordinator runs the independent review and CI.

- **Built:**
  - `Route.Reliability` shows the approved checklist. It is re-evaluated on every start and resume, and each Fix opens
    its row's own target, resolver-checked with fallbacks.
    - Camera asks the permission first.
    - Manufacturer opens Story 5.5's guidance over the checklist.
    - "Ring a test alarm" uses the soonest enabled alarm's settings.
  - Home and Settings show the banner from `alarmBlocking`. Its Fix, and the new Settings row, open the checklist.
  - `ReliabilitySettings` takes a `ChecklistRow`, and the Story 1.19 item API is gone.
- **Review:** 4 findings: 2 patched (low), 2 rejected (reasons in the triage log).
- **Follow-up review:** false. Nothing medium or high was patched.
- **Defaults taken:** see `## DEFAULTS TAKEN`. These are:
  - no Fix on the Test alarm row;
  - Camera asks first, then app details;
  - the camera reason keeps EXPERIENCE.md's House Hunt wording;
  - the test-alarm settings;
  - the Manufacturer route;
  - the Battery target;
  - test-alarm failure is logged with no snackbar.
- **Residual risks:**
  - The deep links, DND and battery have not been checked on a phone (Story 5.13).
  - The manifest `<queries>` list must name every settings action a Fix uses. A new target needs a new entry, or
    API 30+ falls back to app details.

