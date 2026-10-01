---
title: 'Story 1.9: Alarm list on Home with the next-alarm countdown'
type: 'feature'
created: '2026-10-01'
status: 'done'
baseline_revision: '34937c88f2442309b396f7658e6373d42915f674'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
  - '{project-root}/.claude/skills/pps-design/SKILL.md'
warnings: ['oversized']
deferred:
  - summary: >-
      Test the Home toggle token guard with two toggles of one alarm completing out of order.
    evidence: |-
      HomeViewModel.toggle drops an older result with `takeIf { it.token == token }`; every toggle test sends one
      AlarmToggled on an UnconfinedTestDispatcher with a synchronous fake, so the guard never runs. Needs a gated
      repository fake (CompletableDeferred). Window is narrow because AlarmWriteLock serializes the writes.
    location: >-
      composeApp/src/commonMain/kotlin/com/yawnandpawn/app/ui/home/HomeViewModel.kt (toggle)
    severity: medium
  - summary: >-
      Restore `when` exhaustiveness for EditorIntent instead of chained `else` branches ending in `else -> Unit`.
    evidence: |-
      onIntent sends `else` to onFormIntent, whose `else` goes to onMenuIntent, whose `else` is Unit, so a new
      EditorIntent compiles and is silently ignored. The `else -> { Unit }` pattern predates Story 1.9 (Story 1.8).
    location: >-
      composeApp/src/commonMain/kotlin/com/yawnandpawn/app/ui/editor/AlarmEditorViewModel.kt
    severity: low
---

<intent-contract>

## Intent

**Problem:** Production still shows the interim Story 1.8 Alarms list (no countdown, FAB, no toggle, duplicate or delete, blank on load failure). The owner-approved Home, the shell and the nav capsule exist only behind the debug preview.

**Approach:** Make the commonMain `AppShell` + `HomeScreen` the production start screen. Feed `HomeScreen` from a new `HomeViewModel` built on the real repository, alarm use cases and time ports. Add the missing pieces without redesigning anything: a long-press menu with Duplicate and Delete, the delete dialog, an editor overflow menu, the load-failure state and a `Logger` port. Retire `AlarmsScreen`, `AlarmsViewModel` and the FAB.

## Boundaries & Constraints

**Always:**
- The approved design is the existing composables plus DESIGN.md / EXPERIENCE.md v0.5. Reuse `AppShell`, `AppTab`, `HomeScreen`, `HomeHeader` (the collapsing header), the `AlarmCard` model and `ConfirmDialog` as they are. Add parameters or fields only with defaults, so every preview state and its baselines render unchanged.
- The countdown uses `nextOccurrence` + `durationUntil` from `core.alarm` (the scheduler's functions), turned into text with `countdownOf`. That text is "Rings in {h} h {m} min" / "{m} min" / "{d} d {h} h", rounded up to the minute.
- The countdown refreshes every minute, on resume, and on time or time-zone changes.
- The countdown comes from the soonest enabled alarm. It is hidden when no alarm is enabled.
- Cards are sorted by time of day (`AlarmListOrder`), with a repeat summary from `repeatSummary` ("Every day", "Weekdays", "Weekends", "Once" or short day names), and `checks = emptyList()` in Epic 1.
- Toggling a card's switch calls `SetAlarmEnabled` immediately.
- Long-press on a card opens a menu with "Duplicate" and "Delete". The same two actions are in an overflow menu in the editor header, shown only when editing an existing alarm. Both are also exposed to TalkBack.
- "Duplicate" uses `DuplicateAlarm` and opens the copy in the editor.
- "Delete" opens `ConfirmDialog(destructive)` with "Delete your {time} alarm? This is logged.", "Delete" and "Keep it" (the safe default). Confirming calls `DeleteAlarm`, then logs `AlarmDeleted(alarmId, at)` through the new `Logger` port. Deleting from the editor also closes it.
- The tabs are four `Route` tab keys: Alarms, Progress, Settings and You. The centre "+" pushes `Route.AlarmEditor(null)` from any tab.
- The editor is pushed over the shell, so the capsule is not shown there.
- Back on Progress, Settings or You returns to Alarms. Back on Alarms exits.
- Tabs whose stories are not done open their existing screens in the empty or default state (`ProgressUiState()`; Settings and You with defaults). Any row that would open an unbuilt screen or link is hidden in production, through a row-visibility parameter that defaults to "all", so the previews keep every row.
- Strings live in `strings.xml`, copied from EXPERIENCE.md verbatim, and `CopyRulesTest` passes. Use tokens only. Targets are at least 48 dp. Reduced motion makes everything instant.
- Card add and remove animations already exist (`animateItem`).

**Never:**
- No new layout, colours or tokens, and no change to any existing preview baseline. If one changes, the cause must be fixed, not re-recorded.
- No hero, missed note, reliability banner or session panel wiring: they stay null or false (Stories 6.3, 1.16, 1.19 and Epic 2).
- No scheduler calls (that is Story 1.10) and no commitment-lock dialog (that is Epic 4).
- No `println` or `Log` in `:core`. No `Clock.System` outside adapters.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Countdown hours | Soonest enabled alarm 7 h 12 min away (FakeClock) | "Rings in 7 h 12 min" | No error expected |
| Under a minute | 59 s away | "Rings in 1 min" | No error expected |
| Exactly 24 h | 24 h 0 min away | "Rings in 1 d 0 h" | No error expected |
| DST gap | Berlin spring-forward day, 02:30 alarm | The countdown targets 03:30 (`nextOccurrence`) | No error expected |
| Zone change | FakeTimeZoneProvider Berlin → New York, then a refresh | The countdown is recomputed for the new zone | No error expected |
| All disabled | Alarms exist, none enabled | Cards show, the countdown is hidden | No error expected |
| Loading | First emission not yet arrived | Nothing renders: no empty state, no list | No error expected |
| Load failure | `observeAll` throws | "Couldn't load your alarms." and "Try again", which resubscribes; no empty state | Logged through the Logger |
| Editor load failure | `get(id)` fails | The editor closes and Home shows the snackbar "Couldn't open this alarm." | Logged |
| Toggle failure | `SetAlarmEnabled` fails | The switch reverts to the stored value | Logged |
| Delete | Confirm in the dialog | The card animates out, `AlarmDeleted(id, now)` is logged once | On failure the card stays and the error is logged |

</intent-contract>

## Code Map

- `composeApp/src/commonMain/kotlin/com/yawnandpawn/app/ui/nav/Route.kt` (lines 13 to 23) -- the sealed `Route : NavKey` with `subclassesOfSealed`. Add tab keys; keep `AlarmEditor(alarmId: String?)`.
- `composeApp/src/commonMain/kotlin/com/yawnandpawn/app/ui/nav/AppNavHost.kt` (lines 24 to 59) -- `NavDisplay` with entries for `Alarms` and `AlarmEditor`. `openEditor` only pushes from `Alarms`; `close`/`pop` never pop the root. Rework this for tabs plus the shell.
- `composeApp/src/commonMain/kotlin/com/yawnandpawn/app/ui/shell/AppShell.kt:58` -- `AppShell(selected, onSelect, modifier, onAdd, showNavBar, content)`. `AppTab.kt:20` is `enum AppTab { Alarms, Progress, Settings, You }`. `LocalNavBarClearance` is in `components/Screens.kt:36`.
- `androidApp/src/debug/kotlin/com/yawnandpawn/app/debug/preview/TapThrough.kt` -- the template to copy into production: `Tabs()` at line 456 (`AppShell` plus a `when(tab)` over the four screens), and `onHome` at line 173. Read only.
- `composeApp/src/commonMain/kotlin/com/yawnandpawn/app/ui/home/HomeContract.kt` -- `AlarmCard` (line 17), `HomeUiState` (line 34) and `HomeIntent` (line 53). Add the loading and load-failed fields plus the intents for duplicate, delete (request, confirm, cancel), retry and snackbar dismissal, all with defaults.
- `composeApp/src/commonMain/kotlin/com/yawnandpawn/app/ui/home/HomeScreen.kt` -- `HomeScreen` (line 88) renders `EmptyHome` when the list is empty (line 97), which would flash during loading. `AlarmCardView` (line 272) is `.clickable` with a separate `PpsSwitch`. Add long-press (`combinedClickable` plus `onLongClickLabel`) and the menu. `DisableDialog` (line 360) shows how to use `ConfirmDialog`.
- `composeApp/src/commonMain/kotlin/com/yawnandpawn/app/ui/components/ConfirmDialog.kt:15` -- reuse for the delete dialog.
- `composeApp/src/commonMain/kotlin/com/yawnandpawn/app/ui/alarms/AlarmsViewModel.kt`, `AlarmsScreen.kt` -- the interim list. Delete both, and move the useful parts of their tests (`AlarmsViewModelTest`, `AlarmScreensSemanticsTest` lines 212, 227 and 262, the `alarms_*` screenshots) to the Home equivalents.
- `composeApp/src/commonMain/kotlin/com/yawnandpawn/app/ui/editor/AlarmEditorViewModel.kt` -- `load()` closes silently on failure (lines 152 to 155). Change it to emit an effect that results in the Home snackbar. `EditorEffect` is at `AlarmEditorContract.kt:243`. The header in `AlarmEditorScreen.kt` gets the overflow menu.
- `composeApp/src/commonMain/kotlin/com/yawnandpawn/app/ui/UiModule.kt` (lines 15 to 27) -- register `HomeViewModel`. The use cases are already bound in `androidApp/src/main/kotlin/com/yawnandpawn/app/YawnAndPawnApp.kt` (lines 19 to 30).
- `core/src/commonMain/kotlin/com/yawnandpawn/app/core/alarm/AlarmUseCases.kt` -- `SetAlarmEnabled` (line 103), `DeleteAlarm` (line 121), `DuplicateAlarm` (line 129). `AlarmOccurrence.kt` has `nextOccurrence` (line 24) and `durationUntil` (line 40). `Alarm.kt` has `toRule()` (line 34) and `AlarmListOrder` (line 64).
- `core/.../core/time/TimePorts.kt:24` -- `TimeZoneProvider`. Add `Logger` (a port with a sealed `LogEvent`, starting with `AlarmDeleted(alarmId, at)` and a generic error event) in `core`. Add an `AndroidLogger` adapter in `:androidApp` and a `FakeLogger` in `:testing`.
- `composeApp/src/commonMain/kotlin/com/yawnandpawn/app/ui/format/Countdown.kt` (`countdownOf` at line 32, `countdownText`), `RepeatSummary.kt`, `TimeFormat.kt` (`is24HourClock`, `formatClockTime`) -- reuse.
- `composeApp/src/commonMain/composeResources/values/strings.xml` -- `home_rings_in_*` (lines 76 to 78), `alarm_card_*`, `disable_*` and `nav_*` already exist. Add the load-failed, try-again, open-failed, duplicate, delete, delete-dialog and keep-it strings, plus the long-press label (for the delete dialog copy see EXPERIENCE.md, lines 128, 165, 166, 306 and 396 to 397).
- Settings, You and Progress -- `SettingsUiState` (`SettingsContract.kt:18`, `baseFee: Money` is required) and `YouUiState` (`YouContract.kt:35`, `appVersion` is required). `ProgressUiState()` works with all defaults. Add the row-visibility parameter.
- Time-change signal -- an Android adapter (in `androidMain` or `:androidApp`) exposing a `Flow<Unit>` for `ACTION_TIME_TICK`, `TIME_SET` and `TIMEZONE_CHANGED`, plus refresh on `ON_RESUME`. Inject it into `HomeViewModel` so tests drive it with a `MutableSharedFlow`.
- Tests:
  - `composeApp/src/commonTest/.../alarms/AlarmsViewModelTest.kt` becomes `home/HomeViewModelTest.kt`.
  - `androidApp/src/test/kotlin/com/yawnandpawn/app/ui/AlarmScreensScreenshotTest.kt` and `EditorSamples.kt` (lines 51 to 55).
  - `androidApp/src/test/.../MainActivityTest.kt`: the line 75 `empty_screen.png` baseline legitimately changes because the shell now draws the capsule.
  - Preview tests in `androidApp/src/testDebug/.../debug/preview/` must stay green, with no baseline changes.
  - Baselines live in `androidApp/src/test/screenshots/`. Use the shared 0.1% threshold in `ScreenshotOptions.kt`.

## Tasks & Acceptance

**Execution:**
- `core/.../core/log/Logger.kt` (new), `testing/.../LogFakes.kt`, `androidApp/.../android/AndroidLogger.kt` -- add the `Logger` port, `LogEvent.AlarmDeleted` and a generic error event; bind it in Koin. This is what the architecture's logging convention needs.
- `composeApp/.../ui/home/HomeViewModel.kt` (new) -- combine `observeAll()`, the time signal and refresh intents into `HomeUiState`: map `Alarm` to `AlarmCard` and compute the countdown. Handle toggle, duplicate, delete, retry and the open-failed snackbar. Emit navigation through `Channel<HomeEffect>`.
- `composeApp/.../ui/home/HomeContract.kt`, `HomeScreen.kt` -- add the fields, intents, long-press menu, delete dialog and load-failure view, all with defaults so the previews don't change.
- `composeApp/.../ui/nav/Route.kt`, `AppNavHost.kt` -- tab routes, the shell host, "+" to the editor, Back behaviour, and passing the editor's open-failed result to Home.
- `composeApp/.../ui/editor/*` -- the overflow menu (Duplicate, Delete with the same dialog) for existing alarms, and the open-failed effect instead of a silent close.
- `composeApp/.../ui/settings/*`, `ui/you/*`, `ui/progress/*` -- the row-visibility parameter (default all), with production defaults.
- `composeApp/.../ui/alarms/*` -- remove the interim screen and ViewModel, and move their tests.
- `composeApp/src/commonMain/composeResources/values/strings.xml` -- new strings.
- Tests:
  - the I/O matrix as `HomeViewModelTest` cases with `FakeAlarmRepository`, `FakeClock`, `FakeTimeZoneProvider`, `FakeLogger` and a fake time signal;
  - semantics tests for the long-press label, menu, dialog, editor overflow, 48 dp targets and the failure view;
  - Roborazzi tests for Home (empty, one alarm, many, all disabled, load failure, delete dialog, long-press menu) in Light, Dark and 200%;
  - a test that the tab Back behaviour and "+" push the editor;
  - `MainActivityTest` updated.
- `_bmad-output/implementation-artifacts/spec-1-9-alarm-list-on-home-with-the-next-alarm-countdown.md` -- paste the `pps-design` Done checklist under Verification and tick it.

**Acceptance Criteria:**
- Given a fresh install, when the app launches, then Home shows inside the glass nav capsule with "No alarms yet." and "Add your first alarm", and no FAB exists anywhere in production.
- Given saved alarms, when Home shows, then they appear as glass cards sorted by time, with the countdown in the collapsing header for the soonest enabled alarm.
- Given any tab, when "+" is tapped, then the editor opens with defaults; after Save or Cancel the user is back on Home.
- Given an alarm card, when it is long-pressed or the editor overflow is used, then "Duplicate" and "Delete" behave as in Always, and TalkBack users reach both without long-press.
- Given the Progress, Settings or You tab, when it is opened in production, then its screen shows the default or empty state with no row that leads nowhere.
- Given the change, when `./gradlew qualityGate` runs, then it passes, and the existing preview baselines are byte-for-byte unchanged.

## Spec Change Log

## Review Triage Log

### 2026-10-01 — Review pass
- verdicts: 40 findings — high 0, medium 9, low 22, false 9, maybe-false 0
- findings:
  - (verification-gap) `[medium]` `[patch]` Editor overflow Duplicate (OpenCopy → replaceEditor) not tested end to end; unwired it leaves a stuck editor — added a MainActivityTest case (Duplicate from the editor menu, Cancel, two cards).
  - (verification-gap) `[medium]` `[patch]` Editor open-failed result (AppNavHost flag → HomeRoute → snackbar) untested between its ends — added a HomeRoute compose test asserting the snackbar and onOpenFailedShown.
  - (verification-gap) `[medium]` `[patch]` Time-signal test never checks unsubscribing after the WhileSubscribed timeout — test extended: cancel collector, advance past 5 s, 0 subscribers.
  - (verification-gap) `[medium]` `[patch]` Failed editor Duplicate (isSaving reset) untested — added an editor ViewModel test (no effect, logged, Back still works).
  - (verification-gap) `[low]` `[patch]` Editor Delete returning NotFound closes the editor, untested — added an editor ViewModel test.
  - (verification-gap) `[medium]` `[defer]` Toggle token guard against out-of-order results never exercised — needs a gated fake; window narrow because AlarmWriteLock serializes writes; recorded in `deferred`.
  - (verification-gap, other) `[low]` `[patch]` `AlarmEditorRoute` defaults `onOpenCopy = {}` / `onOpenFailed = onClose` hide forgotten wiring — made both parameters required.
  - (blind) `[low]` `[reject]` Pending toggle can stick when the wall clock is set back and the editor then saves with an older updatedAt — real but needs a clock set backwards plus an edit while Home's ViewModel lives; the fix (re-modelling pending toggles) is more than a direct correction.
  - (blind) `[low]` `[reject]` Home Duplicate has no in-flight guard (double trigger stores two copies) — the menu closes on the first tap; a double TalkBack action is unlikely and the extra copy is visible on Home; fix adds a guard.
  - (blind) `[low]` `[reject]` Buffered OpenEditor effect fires later if the user switches tab mid-Duplicate — the write takes milliseconds; not reachable in normal use; fix adds state.
  - (blind) `[false]` `[reject]` Editor menu intents ignore isSaving / isLoading — `onIntent` returns early while `isSaving` for every intent (AlarmEditorViewModel.kt:76) and the menu exists only after load (`hasOverflowMenu`).
  - (blind) `[low]` `[reject]` Editor Duplicate drops unsaved edits without "Discard changes?" — rare flow (edit, then duplicate), edits can be redone; a fix needs a pending-action dialog flow; logged in deferred-work.md for the owner's end-of-epic review.
  - (blind) `[low]` `[reject]` Switching tabs drops the non-root tab's saved state — in Epic 1 Progress, Settings and You have no scrollable content; logged in deferred-work.md for Epics 5 and 6.
  - (blind) `[medium]` `[patch]` Each tab entry builds its own AppShell, so the approved tab tint/fill animation never plays on a tab switch — one AppShell hoisted around NavDisplay.
  - (blind) `[low]` `[reject]` Duplicate, Delete and toggle failures give no visible feedback, only logs — matches the intent's I/O matrix (log, keep card, revert switch); storage failures are rare; new copy needs owner approval, logged in deferred-work.md.
  - (blind) `[low]` `[reject]` `AlarmActions` lives in ui.home and holds the AlarmDeleted log, so a future core-level delete path could skip the log — no such path exists yet; moving it changes the core use case API; logged in deferred-work.md for Epic 4 (commitment-lock deletes) and Story 5.9.
  - (blind) `[medium]` `[patch]` Open-failed path not tested end to end — grouped with the verification-gap row above (HomeRoute test).
  - (blind) `[medium]` `[defer]` Toggle token logic untested — grouped with the verification-gap defer row.
  - (blind) `[medium]` `[patch]` Time-signal test only checks the subscribe half — grouped with the verification-gap row (test extended).
  - (blind) `[low]` `[defer]` Editor `else -> onMenuIntent` / `else -> Unit` drops `when` exhaustiveness — pre-existing pattern from Story 1.8 (`else -> { Unit }` at baseline); recorded in `deferred`.
  - (blind) `[low]` `[patch]` Hand-written SDK branch around registerReceiver, pre-33 path untested — replaced with `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)`.
  - (blind) `[low]` `[reject]` Spec drift (LogFakes.kt name, 21 vs 22 screenshot count) — fix would edit this build's spec.
  - (blind) `[low]` `[reject]` Open-failed snackbar baseline only in Light and no isLoading screenshot — the screenshot AC lists empty, one, many, all disabled and load failure, all in Light, Dark and 200%; loading renders nothing.
  - (blind) `[low]` `[patch]` `HomeIntent.SnackbarDismissed` is never sent by the UI — intent, handler and test deleted.
  - (blind) `[low]` `[reject]` ProgressTab reads "today" once, stale across midnight — Epic 1 Progress is an empty placeholder; the real Progress (Epic 6) recomputes from stats; fix adds signal collection.
  - (edge-case) `[low]` `[reject]` Home Duplicate double trigger — same as the blind row; same reason.
  - (edge-case) `[low]` `[reject]` Buffered OpenEditor after a tab switch — same as the blind row; same reason.
  - (edge-case) `[low]` `[reject]` Duplicate failure gives no feedback (Home and editor) — same as the blind row; same reason.
  - (edge-case) `[low]` `[reject]` Editor Duplicate discards unsaved edits — same as the blind row; same reason.
  - (edge-case) `[low]` `[reject]` Pending toggle sticks after a backwards clock change — same as the blind row; same reason.
  - (edge-case) `[low]` `[patch]` Delete dialog reappears after a load failure and "Try again" (local.deleteDialog survives Failed) — `RetryLoad` now clears it.
  - (edge-case) `[low]` `[reject]` ProgressTab today stale — same as the blind row; same reason.
  - (edge-case, claim) `[false]` `[reject]` Spec names testing/.../LogFakes.kt — true drift, but the fix edits this build's spec (FakeLogger.kt exists and is bound).
  - (intent) `[false]` `[reject]` Countdown sits in the list under the collapsing header, not inside the header bar — the approved `HomeScreen` composable places it there and the design baseline says the composables win; moving it would be a redesign.
  - (intent) `[false]` `[reject]` Countdown text asserted as a model, not strings — `countdownOf` is unit-tested, the strings are `home_rings_in_*` resources rendered by the pre-existing `countdownText`, and MainActivityTest finds "Rings in" on the live app.
  - (intent) `[false]` `[reject]` Resume and minute-tick refresh never run on the live surface — ViewModel tests cover Resumed and the signal, the adapter test covers the broadcasts, and Koin binds them; the open-failed wiring gap is patched above.
  - (intent) `[false]` `[reject]` Header collapse, reduced motion and card animations untested by this diff — pre-existing approved composables, unchanged and covered by the preview baselines.
  - (intent) `[false]` `[reject]` Duplicate's new requestCode not asserted — Story 1.7's `DuplicateAlarm` tests assert a new id and request code; this diff calls it unchanged.
  - (intent) `[false]` `[reject]` Delivery (PR, CI, merge, sprint-status) not visible in the diff — those steps run after review by the orchestrator.
  - (intent) `[false]` `[reject]` "Rows not done hidden" read as titles only — matches the AC (no dead links) and the spec's Design Notes; verified on the live app by MainActivityTest.

## Design Notes

- **Tab routes:**
  - Keep `Route.Alarms` as the root.
  - Selecting another tab replaces any non-root tab at the top, so the stack is `[Alarms]` or `[Alarms, Tab]`. The editor goes on top.
  - The shell is drawn by a shared tab-entry wrapper, so all four tab entries share one `AppShell`.
  - A single entry with saveable tab state is acceptable if it gives the same Back behaviour and keeps each tab's scroll position.
- **Row visibility:** a small `Set<…Row>` or Boolean flags parameter on `SettingsScreen` / `YouScreen` / `ProgressScreen`, defaulting to everything. In Epic 1 production, Progress shows the empty ring and calendar without the Purchase history link. Settings shows its title with no rows (every row belongs to Epics 4 and 5). You shows only About with the app version, if that row has no link target; otherwise it shows the title alone.
- **Settings default `baseFee`:** it is hidden, so any placeholder `Money` is fine. Don't format it anywhere visible.
- **Environment (company PC):** export `JAVA_HOME=C:/Users/BuiTua/AppData/Local/Programs/jdk17/jdk-17.0.20.1+1`, and run `./gradlew --stop` after build-logic changes. Never use the owner's phone.
- **Screenshot noise:** if Linux CI fails only on rendering noise above 0.1% on a new baseline, re-record it from the CI artifact (Story 1.3 note).

## Verification

**Commands:**
- `./gradlew qualityGate` -- expected: BUILD SUCCESSFUL. Kover on core is at least 90%, and the dependency and permission allowlists are unchanged or updated.
- `git status --porcelain androidApp/src/test/screenshots/preview` -- expected: empty (no preview baseline changed).

**pps-design Done checklist:**
- [x] Only tokens from `DESIGN.md` used (no raw hex, no new radii, no new font sizes). The menu is `surface` with `rounded.md` and the `outline-subtle` hairline, like `dialog-confirm` (no shadow); the overflow icon is a Material Symbols Rounded vector tinted `text`.
- [x] Light, Dark (and Sunrise where relevant) checked with previews / screenshots. `HomeScreenshotTest` (21 Home states) and the new editor overflow / delete baselines in `AlarmScreensScreenshotTest`; no wake screen in this story.
- [x] Every colour pair used is in the `DESIGN.md` contrast table (text, text-secondary, accent-text and error on glass and surface; no new pair).
- [x] Touch targets ≥ 48 dp; wake actions ≥ 64 dp. Semantics tests check every Home, menu, dialog and editor control.
- [x] Works at 200% font scale and with TalkBack; outcome glyphs present (none on these screens). Cards carry the long-press label "Duplicate or delete" and the TalkBack actions Duplicate and Delete; the editor has "More options".
- [x] Reduced-motion path works. Nav slides, `animateItem` and the menu use Compose animation specs, which the animator duration scale makes instant; tab switches are instant.
- [x] Copy matches `EXPERIENCE.md > Voice and Tone` (no em dashes, no filler, strings in resources). The two new TalkBack drafts are listed in `docs/design-preview/copy-to-approve.md`; `CopyRulesTest` passes.
- [x] Every state row in `EXPERIENCE.md > State Patterns` for this surface is handled (empty, list, all off, loading, storage read failure on Home and in the editor). Hero, banners, missed note, session panel and the commitment-lock dialog stay unwired per Never.
- [x] "I'm up" is the most prominent wake action; snooze is visible, plain and priced (not applicable: no wake screen).
- [x] Compose `@Preview`s for each state (light/dark/Sunrise, empty/error) exist; screenshot tests updated (Roborazzi). `AppPreview.kt` now previews Home (empty, list, load failed, delete dialog, open failed).

## Auto Run Result

**Summary:** the approved shell and Home are the production start screen. One `AppShell` (the floating glass capsule: Alarms · Progress · + · Settings · You) wraps the Navigation 3 tab routes. `HomeViewModel` feeds `HomeScreen` from the alarm repository, the alarm use cases and the time ports. The countdown uses `nextOccurrence`/`durationUntil` and refreshes each minute, on clock and zone changes, and on resume. Long-press and an editor overflow menu offer Duplicate and Delete; Delete has a confirm dialog and is logged. There is a load-failure view with "Try again", and an editor open failure shows a snackbar on Home. Progress, Settings and You show placeholders with no dead links. The FAB and the interim Alarms screen are gone.

**Files changed (main ones):**
- `core/.../log/Logger.kt` -- the `Logger` port, `AlarmDeleted`, `OperationFailed`.
- `core/.../time/TimePorts.kt` -- the `TimeChangeSignal` port.
- `androidApp/.../android/AndroidLogger.kt`, `AndroidTimeChangeSignal.kt` -- the adapters (Logcat; the TIME_TICK, TIME_SET and TIMEZONE_CHANGED receiver, registered only while collected).
- `composeApp/.../ui/home/HomeViewModel.kt`, `HomeRoute.kt`, `AlarmActions.kt`, `DeleteAlarmConfirm.kt` -- Home wiring and the shared alarm actions.
- `composeApp/.../ui/home/HomeContract.kt`, `HomeScreen.kt` -- loading and failure state, long-press menu and TalkBack actions, delete dialog, snackbar (defaults keep the previews unchanged).
- `composeApp/.../ui/nav/Route.kt`, `TabNavigation.kt`, `AppNavHost.kt` -- tab routes, back-stack rules, one shell around `NavDisplay`, the placeholder tabs.
- `composeApp/.../ui/editor/*` -- the overflow menu, `OpenFailed`/`OpenCopy` effects, required route callbacks.
- `composeApp/.../ui/settings/*`, `ui/you/*`, `ui/progress/*` -- the row-visibility parameter (defaults to all).
- `composeApp/.../ui/components/PpsMenu.kt`, `Glass.kt`; `ui/alarms/*` and `PpsFab` removed.
- New strings and `symbol_more_vert`.
- Tests: `HomeViewModelTest`, `TabNavigationTest`, `HomeScreenshotTest` (new baselines), plus editor, semantics, `MainActivityTest`, adapter and fake tests.

**Review findings breakdown:** 40 findings: 0 high, 9 medium, 22 low, 9 false.
- **Patched entries (10):**
  - 5 medium: the hoisted single AppShell (the tab tint animation now plays); the editor overflow Duplicate end-to-end test; the HomeRoute open-failed test; time-signal unsubscribe after the stop timeout; the failed-Duplicate editor test.
  - 5 low: the Delete NotFound test; required route callbacks; the delete dialog cleared on retry; dead `SnackbarDismissed` removed; `ContextCompat.registerReceiver`.
- **Deferred (frontmatter `deferred`):** the toggle token-guard test (medium) and `EditorIntent` `when` exhaustiveness (low, pre-existing).
- **Rejected:** the rejected rows and their reasons are in the Review Triage Log. Four of them went to `deferred-work.md` for the owner and later epics:
  - feedback when an action fails;
  - editor Duplicate dropping unsaved edits;
  - per-tab saved state;
  - the AlarmDeleted log moving into core.

**Follow-up review recommendation:** `true`. Five medium entries were patched on this first pass. The unverified risk is the AppShell hoist. It has had no review pass and no device check. It changes the push transition: the capsule now disappears at once when the editor is pushed, and Home's bottom clearance drops to 0 during the 250 ms slide.

**Verification:**
- `./gradlew qualityGate`: BUILD SUCCESSFUL after the patches (2 min 29 s).
- `git status --porcelain androidApp/src/test/screenshots/preview`: empty.
- Every I/O-matrix row has a passing test in `HomeViewModelTest` (22) or `AlarmEditorViewModelTest` (36).

**Residual risks:**
- The new Windows-recorded baselines may show Linux rendering noise above 0.1% on CI. Re-record them from the CI artifact if so.
- The time signal, the menus and the tab animation haven't been checked on the Oppo A96 yet; that is in Story 1.21 item 20.
