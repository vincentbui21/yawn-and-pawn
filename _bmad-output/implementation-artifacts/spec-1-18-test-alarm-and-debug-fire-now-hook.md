---
title: 'Story 1.18: Test alarm and debug fire-now hook'
type: 'feature'
created: '2026-10-02'
status: 'in-review'
baseline_revision: '3ccde81cb2fee126ec315ea6c6f82138edf3eb16'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
  - '{project-root}/.claude/skills/pps-design/SKILL.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** The editor's "Test alarm" exists only in the design preview's full editor, and its tap does nothing. The test-alarm system alarm fires into a receiver that logs it and ignores it. Developers have no way to fire an alarm on demand, and nothing proves that release builds ship without the debug-only code.

**Approach:** "Test alarm" works in the real editor:
- It saves the editor's current (even unsaved) values as a pending test config in device-protected storage, arms `AlarmScheduler.scheduleTest` 10 s ahead, and shows the snackbar.
- When the alarm fires, the wake service takes the pending config and dispatches `TestAlarmFired`, which runs the whole Epic 1 flow in test mode.
- A debug-only broadcast hook schedules real or test fires.
- A build check fails if release output contains any debug-only content.

## Boundaries & Constraints

**Always:**
- **Editor.** "Test alarm" (`PpsTextButton`, the existing `editor_test_alarm` string) shows under the editor's cards in every editor state. Before, only the preview's full editor showed it. The `SaveCancelPill` is unchanged.
  - The tap calls the core `ScheduleTestAlarm(draft)` with the form's draft, built as Save builds it, so unsaved values are used. On success the ViewModel sends `EditorEffect.ShowTestScheduled`, and the route shows the snackbar "Lock your phone. We'll ring in 10 seconds." (new resource `editor_test_scheduled`, verbatim from EXPERIENCE.md).
  - A failure is logged through `AlarmActions` and shows nothing (no invented copy).
  - It works while saving is idle. A tap during a save is ignored, like every intent.
- **Core.**
  - `ConfigResolver.resolveTest(draft, globalSettings, scheduledAt)` builds a `SessionConfig` with `testMode = true`:
    - `alarmId` is the draft id or `TEST_ALARM_ID`;
    - the label is trimmed, and blank means none;
    - the ramp start is fixed at 20;
    - the check plan is the placeholder.
  - Port `TestAlarmStore`: `put(config)` and `take()`, where `take` reads and clears.
  - `ScheduleTestAlarm(scheduler, store, clock)` fires at now + 10 s. It puts the config, then calls `scheduleTest`. If arming fails, it clears the stored config and returns the failure.
  - `SessionJson.encodeConfig` / `decodeConfig` (the pinned Json).
- **Data.** `DataStoreTestAlarmStore` keeps the encoded config under one key in the 1.16 settings DataStore (device-protected).
  - `take` removes the key in the same edit it reads in.
  - IO errors become `StorageFailure`. An undecodable value is logged, cleared and returned as none.
- **Fire.**
  - `WakeAlarmFiredHandler.onTestAlarmFired` calls `WakeServiceStarter.startTest()` (`ACTION_TEST`).
  - `WakeService.onTest()` calls `testStore.take()`.
    - Nothing pending: logged (`FireIgnored TestAlarm`); the service stops.
    - Otherwise it dispatches `TestAlarmFired(newId, config, seeds, beforeFirstUnlock)`. The config's `scheduledAt` is the armed fire time.
  - While a session is active, the reducer ignores the event and logs it (AD-2), and the session is unchanged.
  - A failed commit is logged. A test never starts the emergency ring.
- **Test session.** The existing flow runs: sound with ramp, vibration, notification, the Ringing screen, "I'm up", the placeholder answer and Completed. History records outcome Test (SessionRecorder). Snooze reads "Test · no charge" and is the disabled, non-tappable variant. No billing call happens: a test asserts `FakeBilling.launched` stays empty.
- **Debug hook** (`androidApp/src/debug` only):
  - `DebugFireProvider`, a non-exported provider, registers `DebugFireReceiver` at runtime for `com.yawnandpawn.app.debug.FIRE`.
    - It is runtime-registered because implicit broadcasts don't reach manifest receivers on API 26+.
    - It is protected by `android.permission.DUMP`, which the adb shell holds.
  - Extras: `seconds` (default 10, clamped 1..3600), `alarmId` (optional), `test` (default false).
    - Test: it schedules a test from the stored alarm's values, or from a synthetic default draft, through `ScheduleTestAlarm` with N s.
    - Real with `alarmId`: `AlarmScheduler.schedule(id, requestCode, now + N)`.
    - Real without one: it saves a synthetic one-time alarm "Debug fire" through `SaveAlarm`, then arms it the same way.
  - Every result is logged. The process must be running (open the app first).
- **Release check.** The build-logic task `checkReleaseContent` is added to `qualityGate`. It reads the release merged manifest, the release project classes and the release resource directories. It fails on any of:
  - `DebugFireReceiver`, `DebugFireProvider` or `com.yawnandpawn.app.debug.FIRE`;
  - any `com/yawnandpawn/app/debug/` class (`debug.preview`, `ThemeShowcase`);
  - a `ThemeShowcase` or `.debug.preview.` component;
  - `preview_launcher_label` or "Yawn & Pawn Preview".

  It also fails if it gathered no classes, so broken wiring can't pass. The pure rules have unit tests.

**Never:**
- No billing, no new screen, no change to the preview composables' looks (preview baselines unchanged).
- No changes to the sound picker (Story 1.17 is in parallel).
- No new permissions or dependencies.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Tap | Editor with unsaved changes, tap "Test alarm" | Pending config holds the unsaved values (testMode); test alarm armed at now + 10 s; snackbar shown | No error expected |
| Arm fails | `scheduleTest` fails | No snackbar; logged; pending cleared | Logged |
| Fire | Test fire, Idle | Ringing with `testMode`; snooze "Test · no charge" (disabled); "I'm up" → history Test; no billing call | No error expected |
| Busy | Test fire while a session rings | Ignored and logged; session unchanged | Logged |
| Nothing pending | Test fire with no pending config | Logged; service stops | Logged |
| Debug test | `FIRE --ei seconds 5 --ez test true` | Test alarm armed at +5 s with defaults | Logged on failure |
| Debug real | `FIRE --ei seconds 5 --es alarmId X` | Alarm X armed at +5 s (real session) | Unknown id logged |
| Release | Release build | No debug content; `checkReleaseContent` passes | Any match fails the build |

</intent-contract>

## Code Map

- `core/.../alarm/AlarmScheduler.kt:34` -- `scheduleTest(triggerAtWallMillis)`. `AndroidAlarmScheduler.kt:55` arms `ACTION_TEST_ALARM` (code 2).
- `core/.../alarm/AlarmFiredHandler.kt:27,85` -- `onTestAlarmFired`. `RearmOnFire` logs `NOT_BOUND`.
- `androidApp/.../wake/WakeAlarmFiredHandler.kt` -- routes test fires to the scheduler log today. `WakeServiceStarter.kt` (`start*`). `WakeService.kt`: `onStartCommand` routes by action; `onAlarm` / `dispatchAlarmFired` show the event-building pattern.
- `core/.../session/IdleRules.kt:18` -- `TestAlarmFired` forces `testMode`. `RingRules.kt:33` ignores it while ringing. `SessionRecorder.kt:113` records Test. `NoBillingSnoozeAvailability` gives `TestMode`.
- `core/.../session/SessionConfig.kt` -- `ConfigResolver.resolve`. `SessionJson.kt` holds the pinned Json.
- `core/.../alarm/AlarmUseCases.kt:19` -- `AlarmDraft`. `composeApp/.../ui/editor/AlarmEditorViewModel.kt` -- `toDraft(alarmId, stored)`, `onIntent`, `_effects`. `AlarmEditorContract.kt:244` (`TestAlarmClicked`), `EditorEffect`. `AlarmEditorScreen.kt:239` (`if (state.full != null)` around the button), the route's effect collector, `PpsSnackbarHost`.
- `data/.../settings/SettingsDataStore.kt`, `DataStoreMissedNoteDismissals.kt` (pattern), `DataModule.kt`.
- `androidApp/src/debug/AndroidManifest.xml` -- the debug activities and the preview label.
- `build-logic/.../AllowlistsPlugin.kt`, `AgpAllowlistWiring.kt` (variant API), `PermissionAllowlist.kt` (XML helpers). The root `build.gradle.kts` lists the `qualityGate` tasks.
- Tests:
  - `androidApp/src/test/.../wake/WakeApp.kt` (overrides), `ForgottenAlarmTest` / `WakeServiceTest` (service flow patterns);
  - `ui/AlarmScreensScreenshotTest.kt` + `EditorSamples.kt`;
  - `composeApp/src/commonTest/.../editor/AlarmEditorViewModelTest.kt`;
  - `androidApp/src/testDebug` (debug-only tests).

## Tasks & Acceptance

**Execution:**
- core: `resolveTest`, `TestAlarmStore`, `ScheduleTestAlarm` and the JSON helpers, with tests.
- testing: `FakeTestAlarmStore`.
- data: `DataStoreTestAlarmStore` with a test, plus Koin.
- composeApp: the editor ViewModel intent, effect and button visibility, the route's snackbar, the string, and ViewModel tests.
- androidApp: the handler, starter and service `ACTION_TEST`, plus Koin and the KDocs.
- debug: `DebugFireProvider`, `DebugFireReceiver` and `DebugFire` (logic), the debug manifest, and a `testDebug` test.
- build-logic: `ReleaseContent` (pure), `CheckReleaseContentTask`, the wiring, unit tests, and the `qualityGate` entry.
- Tests:
  - `TestAlarmFlowTest` (Robolectric): the receiver test action → service → `TestAlarmFired` → Ringing in test mode, snooze "Test · no charge", "I'm up" → history Test, `FakeBilling` never launched; busy → ignored and logged; nothing pending → logged and stopped;
  - Roborazzi: the editor with "Test alarm" and the test snackbar in Light, Dark and 200% (the test-mode ringing screen is `wake_ringing_test_sunrise` from 1.15).

**Acceptance Criteria:**
- Given the editor with unsaved values, when "Test alarm" is tapped, then a test ring with those values is armed 10 s ahead and the snackbar shows.
- Given that ring, when it fires, then a test session runs end to end, and history records Test with no billing.
- Given `./gradlew qualityGate`, when it runs, then `checkReleaseContent` passes on the clean release build, and the preview baselines are unchanged.

## Spec Change Log

## Review Triage Log

## Design Notes

- **Pending config, not a stored alarm:** the test uses unsaved values, so it can't be read from `app.db`. A test alarm is one at a time (request code 2), so one key is enough. A second tap replaces the pending config and re-arms.
- **scheduledAt = fire time:** `ScheduleTestAlarm` sets `scheduledAt` to the armed time (now + 10 s). The notification and the ringing screen then show when the test actually rings, not the minute on the editor's wheel.
- **Environment:** `JAVA_HOME=C:/Users/BuiTua/AppData/Local/Programs/jdk17/jdk-17.0.20.1+1`. No phone. Logs go to the scratchpad with a worktree-unique name.

## Verification

**Commands:**
- `./gradlew qualityGate` -- expected: BUILD SUCCESSFUL, including `checkReleaseContent`.
- `git status --porcelain androidApp/src/test/screenshots/preview` -- expected: empty.

## pps-design Done checklist

- [x] Only tokens from `DESIGN.md` are used. No new UI: the existing `PpsTextButton` and `PpsSnackbarHost`.
- [x] Light, Dark and Sunrise checked with screenshots:
  - `alarm_editor_test_snackbar_light` / `_dark` / `_light_font200`;
  - the editor baselines re-recorded with "Test alarm" under the cards;
  - the test-mode ringing screen is `wake_ringing_test_sunrise` (Story 1.15).
- [x] Every colour pair used is in the `DESIGN.md` contrast table (the existing text-button and snackbar pairs).
- [x] Touch targets are at least 48 dp (the text button). The disabled snooze is the 64 dp wake variant.
- [x] Works at 200% font scale (screenshot) and with TalkBack ("Test alarm" is a button; the snooze reads "Snooze unavailable, Test · no charge").
- [x] Reduced-motion path works: no new animation.
- [x] Copy matches `EXPERIENCE.md > Voice and Tone`: "Lock your phone. We'll ring in 10 seconds." (`editor_test_scheduled`, verbatim) and "Test · no charge". `CopyRulesTest` passes.
- [x] Every state row for this surface is handled. Test alarm on all wake screens: disabled "Test · no charge", no payment, logged Test only.
- [x] "I'm up" is the most prominent wake action (unchanged), and snooze is visible, plain and disabled with its reason.
- [x] Previews and screenshots: the preview catalogue is unchanged, and Roborazzi is updated.

## Auto Run Result

**Summary:** "Test alarm" now rings a real test of the alarm.
- **Editor:** the button is under the cards in every editor. A tap stores the form's current values (unsaved changes included) as a `testMode` config in the device-protected settings DataStore, arms `scheduleTest` 10 s ahead, and shows "Lock your phone. We'll ring in 10 seconds.".
- **Fire path:** the fire goes through `AlarmFiredReceiver` → `WakeAlarmFiredHandler` → `WakeService` `ACTION_TEST`, which takes the pending config and dispatches `TestAlarmFired`.
- **Test session:** the full flow runs with snooze disabled as "Test · no charge" and no billing, and history records Test.
- **Ignored fires:** a fire during a session is ignored and logged by the reducer. A fire with nothing pending is logged and the service stops.
- **Debug hook:** `adb shell am broadcast -a com.yawnandpawn.app.debug.FIRE --ei seconds N [--es alarmId ID] [--ez test true|false]` reaches a runtime-registered `DebugFireReceiver`. It is registered by a debug-only `DebugFireProvider` and protected by the shell's DUMP permission. It arms a real or test ring N s ahead; a real ring with no id uses a saved one-time "Debug fire" alarm.
- **Release check:** `checkReleaseContent` keeps all of this out of release, and is now part of `qualityGate`.

**Verification:**
- `./gradlew qualityGate` gives BUILD SUCCESSFUL.
- New suites:
  - `ScheduleTestAlarmTest` (5);
  - `DataStoreTestAlarmStoreTest` (5);
  - `AlarmEditorTestAlarmTest` (3, the editor ViewModel);
  - `TestAlarmFlowTest` (3): end to end with `FakeBilling.launched` empty, busy ignored, nothing pending;
  - `DebugFireTest` (6);
  - `ReleaseContentTest` (5);
  - `AlarmScreensSemanticsTest` (+1): the route shows the snackbar;
  - `AlarmScreensScreenshotTest` (+3).
- `AlarmFiredReceiverTest` is updated: a test fire now starts the service.
- Preview baselines unchanged.

**Residual risks:**
- The debug hook only works while the app process runs, because the receiver is registered at runtime. Open the app first.
- With the exact-alarm permission off (API 31–32), the test can't be armed. The tap is logged with no message until Story 1.19's checklist explains it.
- Merge conflicts with the parallel Stories 1.17 and 1.19 are likely in `AlarmEditorViewModel` (a new constructor parameter), the editor screenshot baselines, the debug manifest and `config/`.
