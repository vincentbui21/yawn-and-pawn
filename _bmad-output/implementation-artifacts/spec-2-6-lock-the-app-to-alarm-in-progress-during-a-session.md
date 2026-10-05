---
title: 'Story 2.6: Lock the app to "Alarm in progress" during a session'
type: 'feature'
created: '2026-10-05'
status: 'done'
baseline_revision: 'ca9681651ca0912baf85d520f4b3f938969a966f'
review_loop_iteration: 1
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/spec-2-5-leave-the-alarm-and-come-back-through-the-notification.md'
  - '{project-root}/.claude/skills/pps-design/SKILL.md'
warnings: ['oversized']
deferred:
  - 'A frame-level test that moving into and out of the lock is instant (needs mainClock control; visual only).'
---

<intent-contract>

## Intent

**Problem:** During a session the whole app stays usable, so the user can open the editor, switch an alarm off or delete it, and escape the morning for free (FR-SES-3). The approved session-lock look (`home-session`, `settings-session`) exists only in the design preview.

**Approach:**
- While the app is **session-locked**, the Navigation 3 back stack is replaced by one `Route.SessionInProgress`. It shows the approved `home-session` screen: the Home header plus `SessionInProgressPanel`, with no nav capsule. "Back to alarm" opens `WakeActivity`.
- Session-locked (review fix): the stored session is not restored yet (or its load failed), or a ring (Ringing, Grace, Loud), a snooze or the emergency ring is in progress. Completed and Missed only wait for their history row, which may keep failing, so they do not lock.
- Not restored yet (owner polish, 2026-10-06): while the stored session is not restored and no emergency ring plays, the app shows a neutral empty screen (the app background, no text, no nav capsule) instead of the "Alarm in progress" panel, so a cold start never flashes it. The stack is left as it is. Once restored, the app shows the lock only if a session is in progress, else Home. The guard still refuses writes until restored.
- The stack returns to `[Alarms]` once unlocked.
- Behind the UI, one core `SessionLockGuard` makes the alarm use cases return `DomainError.SessionActive` without writing, on the same rule.

## Boundaries & Constraints

**Always:**
- **The route:** `Route.SessionInProgress` is a new serializable route with no tab, so the nav capsule (and its "+") hides.
- **Applying the lock:** `MutableList<NavKey>.applySessionLock(active)` is pure.
  - Active: anything other than exactly `[SessionInProgress]` becomes `[SessionInProgress]`.
  - Inactive: a stack holding `SessionInProgress` becomes `[Alarms]`.
  - Otherwise the stack is unchanged.
  - `AppNavHost` reads the lock synchronously for the first frame (`SessionLockGuard.locked`, initial `isLocked`). While locked it hands `NavDisplay` `[SessionInProgress]` from the same composition, so the route the stack held (Home, the editor) never composes. It also applies `applySessionLock` after every composition (`SideEffect`), so any route pushed while locked is replaced. That includes tab selection, the editor and future routes.
  - Moving to or from `SessionInProgress` is instant: no slide.
- **The screen:** `SessionInProgress` renders `HomeScreen(HomeUiState(sessionInProgress = true))`, unchanged from the approved preview: the glass card, "Alarm in progress" as a `headline` heading, and the `button-filled` "Back to alarm" (≥ 48 dp, role button). Strings come from the existing resources, verbatim from EXPERIENCE.md.
- **Back to alarm:** it calls a `WakeScreenOpener` (`fun interface` in `:composeApp`, bound in `:androidApp`) that starts `WakeActivity.intent()`. In Snoozed this also opens the wake screen on the current state. It opens only during a ring, a snooze or the emergency ring: in Idle, Completed or Missed the wake screen would wait for a ring that never comes and never close, so the tap does nothing (review fix).
- **The editor and Sound picker:** when the lock applies, they leave the back stack. Their ViewModel is cleared (no dialog, draft discarded, and the existing `addCloseable` stops any preview). When unlocked, the app shows Home.
- **The guard:** `SessionLockGuard(state, restored, emergency)` in `core.session`. `state` and `restored` are `SessionEngine`'s, `emergency` is `WakeRuntime.emergencyRinging`.
  - `isLocked` is `!restored || emergency || state.inProgress` (`inProgress`: a `Ring` or `Snoozed`). `SessionEngine.restored` turns true only after the restored state is published, and stays false while the load fails.
  - `suspend fun <T> whenIdle(block)` returns `Outcome.Failure(DomainError.SessionActive)` when locked, and runs `block` otherwise.
  - `whenIdle` and `startingSession(block)` share one mutex. `WakeService` runs the `AlarmFired` and `TestAlarmFired` dispatches in `startingSession`, so a session never starts in the middle of a guarded write; a write waiting behind a start is refused.
  - `SaveAlarm`, `SetAlarmEnabled`, `DeleteAlarm` and `DuplicateAlarm` take it as a required constructor parameter and call it inside their write lock, before any read or write.
  - Its KDoc states that every later mutating use case (settings, base fee, "Delete all data") must use it.
  - A `SessionActive` refusal (a session started between the tap and the write) is not logged as `OperationFailed` and shows no "Couldn't save" message: the lock screen replaces the screen.
- **The fire path stays unguarded:** `RearmOnFire` stops using `SetAlarmEnabled`. It switches a fired one-time alarm off itself, inside the same lock (`repository.upsert`, then `scheduling.sync`), so it still disables one-time alarms and re-arms repeating ones during a session.
- **The scan:** `SessionLockGuardScanTest` scans all of `core/src/commonMain`, comments and string contents blanked out (review fix).
  - In `core.alarm` and `core.history` every class and object is checked; elsewhere only use cases (`operator fun invoke`), so a settings or "Delete all data" use case in any later package is held to the rule. There is no `core.config` yet, so it is not listed; a missing listed package fails the test.
  - Every write (repository, store, DAO, DataStore and dismissal writes, request-code allocation, scheduler and `AlarmScheduling` calls) must sit inside a `whenIdle { }` block. A private function that writes is checked at its call sites.
  - Allowed with a reason: `RearmOnFire` (fire path), `AlarmScheduling` (system events; the use cases call it inside `whenIdle`), `MissedNotes` (dismissing the note changes nothing the user owns), `ScheduleTestAlarm` (the editor offering it is closed by the lock, and a test fire during a session is ignored by the engine, 1.18).
  - It self-tests with rogue snippets: `object`, `value`, `final`, `inner` and indented classes, a write after a `whenIdle` block, a guard in a comment, scheduler, DataStore, DAO and dismissal writes, and private helpers.
- **Wiring:** `:androidApp` Koin binds `SessionLockGuard(engine.state, engine.restored, runtime.emergencyRinging)` and `WakeScreenOpener`.

**Never:**
- No new strings and no visual redesign. Use the approved composables only (the AC's "surface card" is the approved glass card).
- No lock-task, launcher, overlay or other-app effect.
- No change to the session state machine.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Lock on | Stack `[Alarms, Settings]`, session active | `[SessionInProgress]`, nav capsule hidden | No error expected |
| Navigate while locked | Locked; any `Route` subclass pushed, tab selected or editor opened | Top stays `SessionInProgress` (every subclass, enumerated from the serializer) | No error expected |
| Editor open | `[Alarms, AlarmEditor]` with a preview playing; session starts | Editor gone, no dialog, preview stopped | No error expected |
| Session ends | Locked, then Idle | `[Alarms]` (Home) | No error expected |
| Not restored yet | Stored Ringing, engine still Idle | Neutral empty screen (no "Alarm in progress", no Home, no nav capsule); the lock once restored | No error expected |
| Not restored yet, empty store | Nothing stored, engine still Idle | Neutral empty screen, then Home; "Alarm in progress" never shown | No error expected |
| History write failing | Engine stuck in Completed or Missed | Not locked: Home, alarms editable | Engine retries the write |
| Cold start in a session | Session restored before the first frame | First frame is the lock; `HomeViewModel` never created | No error expected |
| Back to alarm | Panel shown (Snoozed) | `WakeActivity` started | No error expected |
| Back to alarm, nothing to show | Idle, Completed or Missed | Nothing started | No error expected |
| Guarded use cases | Save, enable, delete or duplicate while locked | `Failure(SessionActive)`; repository, sequence and scheduler untouched | Returned, not thrown |
| Start races a write | `AlarmFired` while a guarded write runs | The start waits for the write; the next write is refused | Refusal not logged, no message |
| Fire during a session | One-time alarm fires; session active | Alarm disabled and code cancelled; repeating alarm re-armed | No error expected |
| Scan | A use case writing without `whenIdle` | Test names the class | No error expected |

</intent-contract>

## Code Map

- `core/.../error/DomainError.kt` -- add `SessionActive`. The `diagnostic()` mapping lives in `core/.../log` (check exhaustiveness).
- `core/.../alarm/AlarmUseCases.kt` -- the four use cases, inside `lock.withLock`.
- `core/.../alarm/AlarmFiredHandler.kt` -- `RearmOnFire` uses `SetAlarmEnabled` (line 76); switch to a direct write.
- `core/src/commonTest/.../alarm/AlarmUseCasesTest.kt`, `AlarmSchedulingTest.kt`, `testing/.../SchedulerFakes.kt` (`AlarmUseCasesFixture`) -- constructor call sites.
- `androidApp/.../YawnAndPawnApp.kt` -- Koin bindings of the use cases and `RearmOnFire`.
- `composeApp/.../ui/nav/Route.kt`, `TabNavigation.kt`, `AppNavHost.kt` -- routes, stack rules and the host.
- `composeApp/.../ui/home/HomeScreen.kt`, `ui/components/Screens.kt` (`SessionInProgressPanel`) -- the approved look, reused as is.
- `androidApp/src/debug/.../preview/TapThrough.kt` -- preview reference: the lock hides the nav bar.

## Tasks & Acceptance

**Execution:**
- Core: `DomainError.SessionActive`, `core/session/SessionLockGuard.kt`, the guarded use cases and `RearmOnFire`, plus tests: `SessionLockGuardTest`, use-case rows, fire during a session, and `SessionLockGuardScanTest`.
- `testing`: the fixture takes a guard (default: an Idle state flow).
- ComposeApp:
  - `Route.SessionInProgress`, `applySessionLock`, `WakeScreenOpener`;
  - `AppNavHost` (lock state from `SessionLockGuard.state`, the `SideEffect`, the entry and instant transitions);
  - commonTest `SessionLockTest` (every `Route` subclass via the serializer descriptor), which needs `kotlinx-serialization-json` as a test-only dependency.
- AndroidApp:
  - Koin bindings;
  - `SessionLockScreenTest` (Robolectric with the real app: Snoozed restored → panel, no nav bar, "Back to alarm" starts `WakeActivity`; editor with a preview closes on session start; Idle → Home);
  - `SessionInProgressScreenshotTest` (Light, Dark, Light 200%; new baselines in `src/test/screenshots`, not preview).

**Acceptance Criteria:**
- Given a session active in the real app, when Roborazzi and semantic tests run, then "Alarm in progress" is a heading, "Back to alarm" is ≥ 48 dp with role button, and Light, Dark and 200% screenshots exist.
- Given `./gradlew qualityGate`, when it runs, then it passes, and `git status --porcelain androidApp/src/test/screenshots/preview` is empty.

## Spec Change Log

- 2026-10-06 (owner polish, from review, `fix(2.6): no "Alarm in progress" flash while restoring`): on a cold start the "Alarm in progress" panel flashed while the stored session was restored. Until it is restored (and no emergency ring plays) `AppNavHost` now shows a neutral empty screen (`RESTORING_TAG`); then the lock for a session in progress, else Home. `SessionLockGuard` is unchanged and still refuses writes until restored. `SessionLockScreenTest` keeps the load failing until it lets it finish, since `MainActivity` restores on its own (Story 2.1).

## Review Triage Log

### Review (2 reviewers, fast mode)

All patched in `fix(2.6): review fixes`, with tests:
1. **Not restored yet looked Idle** (guard and nav host): with Story 2.1 the restore runs from `WakeService`, `MainActivity` and `WakeActivity`, so a stored ring could show editable alarms. `SessionEngine.restored` (set after the restored state is published, false while the load fails) now locks both. Tests: `SessionLockGuardTest`, `SessionEngineTest` (restored flag), `AlarmUseCasesTest` (not restored), `SessionLockScreenTest` (stored Ringing not restored: lock, not Home; empty store: lock, then Home).
2. **`AlarmFired` racing a guarded write:** `whenIdle` and `SessionLockGuard.startingSession` share one mutex, and `WakeService` starts sessions (`AlarmFired`, `TestAlarmFired`) inside it. A guarded write takes milliseconds, so the ring is never noticeably late. Test: `SessionLockGuardTest` (the start waits, the next write is refused).
3. **"Back to alarm" with nothing ringing** opened a wake screen that never closed: `AndroidWakeScreenOpener` now opens only during a ring, a snooze or the emergency ring. Test: `SessionLockScreenTest`.
4. **Stuck Completed or Missed locked the app forever:** the lock (UI and guard alike) is now a ring, a snooze or the emergency ring, plus not restored. Completed and Missed only wait for their history row. Tests: `SessionLockGuardTest`, `AlarmUseCasesTest`, `SessionLockScreenTest` (history write failing: Home).
5. **The first locked frame composed the old route:** `AppNavHost` hands `NavDisplay` `[SessionInProgress]` from the same composition, with the lock read synchronously for the first frame. The `SideEffect` still clears the real stack. Test: `SessionLockScreenTest` (cold start into a ring never creates `HomeViewModel`, no nav capsule).
6. **A `SessionActive` refusal looked like a failure:** `AlarmActions` no longer logs it as `OperationFailed`; Home and the editor show no "Couldn't save" for it. Test: `HomeViewModelTest`.
7. **The scan was too narrow:** it now strips comments and strings, requires each write to sit inside a `whenIdle` block, matches `object`, `value`, `inner`, `final` and indented declarations, checks scheduler, DataStore, DAO and dismissal writes and private helpers, covers every use case in `:core` plus every class in `core.alarm` and `core.history`, and fails when a listed package is missing (`core.config` is not listed: it does not exist yet). `MissedNotes` and `ScheduleTestAlarm` are allowed with their reasons. Self-test extended with rogue samples for each new shape.

`OneTimeAlarmFlowTest` now restores the engine before switching the alarm on, as the app does before its alarms are editable. `./gradlew qualityGate`: BUILD SUCCESSFUL, Kover green, no change under `screenshots/preview`.

Deferred: a frame-level test that moving into and out of the lock is instant (needs `mainClock` control; visual only).

## Design Notes

- **Not a new screen:** the session route reuses `HomeScreen` in its approved `home-session` state. The header plus panel is exactly what the owner approved; the preview hides the nav bar in that state too.
- **A derived list and a `SideEffect`:** the shown list follows the lock in the same composition, so the old route never composes for a frame (review fix). The `SideEffect` still mutates the real stack: that clears the editor's ViewModel, which discards the draft and stops the preview, and Back works on the real stack once unlocked.
- **Locked before the restore:** with Story 2.1 the restore runs from the activities, so "Idle" can mean "not loaded yet". The guard stays locked until `restored`, which is preferred over editable alarms while a ring may be stored. The UI shows a neutral empty screen for that moment rather than "Alarm in progress" (owner polish), and the lock only once a restored session is in progress.

## Verification

**Commands:**
- `./gradlew :core:allTests :composeApp:allTests` -- expected: pass, and Kover green.
- `./gradlew :androidApp:recordRoborazziDebug -Proborazzi.test.verify=false --tests <the new screenshot test>` -- records only the new baselines.
- `./gradlew qualityGate` -- expected: BUILD SUCCESSFUL, with no change under `screenshots/preview`.

## Auto Run Result

**Summary:** while a session is active (any state but Idle), the app shows only "Alarm in progress".
- **The lock screen:**
  - `AppNavHost` replaces the back stack with `Route.SessionInProgress` (`applySessionLock`, applied after each composition). That route renders the approved `home-session` screen: the Home header and `SessionInProgressPanel`, with no nav capsule.
  - "Back to alarm" opens `WakeActivity` through `WakeScreenOpener`, Snoozed included.
  - Any route pushed while locked is replaced again. An open editor leaves without a dialog, which discards its draft and stops its preview (the existing `addCloseable`).
  - Home returns once the session is Idle. Transitions into and out of the lock are instant.
- **The core guard:** `SessionLockGuard` makes `SaveAlarm`, `SetAlarmEnabled`, `DeleteAlarm` and `DuplicateAlarm` return `DomainError.SessionActive` before any read or write.
  - `RearmOnFire` no longer goes through `SetAlarmEnabled`. It switches fired one-time alarms off itself under the write lock, so the fire path still works during a session (tested).
  - `SessionLockGuardScanTest` (`:data` host test) fails for any `core.alarm` or `core.config` use case that writes without `whenIdle`.

**Files changed:**
- Core: `error/DomainError.kt`, `log/Logger.kt`, `session/SessionLockGuard.kt`, `alarm/AlarmUseCases.kt`, `alarm/AlarmFiredHandler.kt`.
- `testing/SchedulerFakes.kt` (fixture guard).
- ComposeApp: `ui/nav/Route.kt`, `TabNavigation.kt`, `AppNavHost.kt`, `WakeScreenOpener.kt`; `composeApp/build.gradle.kts` adds serialization-json for commonTest only.
- AndroidApp: `YawnAndPawnApp.kt` (bindings), `android/wake/AndroidWakeScreenOpener.kt`, and the `SchedulingApp.kt` test helper.
- Tests: `SessionLockGuardTest`, use-case and fire-during-session rows, `SessionLockTest` (every `Route` subclass from the serializer), `SessionLockScreenTest`, `SessionInProgressScreenshotTest`.
- 3 new baselines: `src/test/screenshots/session_in_progress_{light,dark,light_font200}.png`. The preview baselines are untouched.

**Verification:**
- `./gradlew qualityGate`: BUILD SUCCESSFUL.
- `git status --porcelain androidApp/src/test/screenshots/preview` is empty.

**Deviation:** the AC's "`surface` card" is drawn as the approved glass card (`SessionInProgressPanel`), as the design preview shows. No redesign.

**Not run:** the step-04 review (fast mode).
