---
title: 'Story 2.6: Lock the app to "Alarm in progress" during a session'
type: 'feature'
created: '2026-10-05'
status: 'in-progress'
baseline_revision: 'ca9681651ca0912baf85d520f4b3f938969a966f'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/spec-2-5-leave-the-alarm-and-come-back-through-the-notification.md'
  - '{project-root}/.claude/skills/pps-design/SKILL.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** During a session the whole app stays usable, so the user can open the editor, switch an alarm off or delete it, and escape the morning for free (FR-SES-3). The approved session-lock look (`home-session`, `settings-session`) exists only in the design preview.

**Approach:**
- While `SessionEngine.state` is not Idle, the Navigation 3 back stack is replaced by one `Route.SessionInProgress`. It shows the approved `home-session` screen: the Home header plus `SessionInProgressPanel`, with no nav capsule. "Back to alarm" opens `WakeActivity`.
- The stack returns to `[Alarms]` once the session is Idle.
- Behind the UI, one core `SessionLockGuard` makes the alarm use cases return `DomainError.SessionActive` without writing.

## Boundaries & Constraints

**Always:**
- **The route:** `Route.SessionInProgress` is a new serializable route with no tab, so the nav capsule (and its "+") hides.
- **Applying the lock:** `MutableList<NavKey>.applySessionLock(active)` is pure.
  - Active: anything other than exactly `[SessionInProgress]` becomes `[SessionInProgress]`.
  - Inactive: a stack holding `SessionInProgress` becomes `[Alarms]`.
  - Otherwise the stack is unchanged.
  - `AppNavHost` applies it after every composition where the stack and the lock disagree (`SideEffect`), so any route pushed while locked is replaced. That includes tab selection, the editor and future routes.
  - Moving to or from `SessionInProgress` is instant: no slide.
- **The screen:** `SessionInProgress` renders `HomeScreen(HomeUiState(sessionInProgress = true))`, unchanged from the approved preview: the glass card, "Alarm in progress" as a `headline` heading, and the `button-filled` "Back to alarm" (≥ 48 dp, role button). Strings come from the existing resources, verbatim from EXPERIENCE.md.
- **Back to alarm:** it calls a `WakeScreenOpener` (`fun interface` in `:composeApp`, bound in `:androidApp`) that starts `WakeActivity.intent()`. In Snoozed this also opens the wake screen on the current state.
- **The editor and Sound picker:** when the lock applies, they leave the back stack. Their ViewModel is cleared (no dialog, draft discarded, and the existing `addCloseable` stops any preview). When Idle, the app shows Home.
- **The guard:** `SessionLockGuard(state: StateFlow<SessionState>)` in `core.session`.
  - `suspend fun <T> whenIdle(block)` returns `Outcome.Failure(DomainError.SessionActive)` when the state is not Idle, and runs `block` otherwise.
  - `SaveAlarm`, `SetAlarmEnabled`, `DeleteAlarm` and `DuplicateAlarm` take it as a required constructor parameter and call it inside their write lock, before any read or write.
  - Its KDoc states that every later mutating use case (settings, base fee, "Delete all data") must use it.
- **The fire path stays unguarded:** `RearmOnFire` stops using `SetAlarmEnabled`. It switches a fired one-time alarm off itself, inside the same lock (`repository.upsert`, then `scheduling.sync`), so it still disables one-time alarms and re-arms repeating ones during a session.
- **The scan:** `SessionLockGuardScanTest` scans `core/src/commonMain` packages `core/alarm` and `core/config`. It fails for any class with `operator fun invoke` whose body writes a repository (or allocates a request code) without calling `whenIdle`. It also self-tests with rogue snippets.
- **Wiring:** `:androidApp` Koin binds `SessionLockGuard(get<SessionEngine>().state)` and `WakeScreenOpener`.

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
| Back to alarm | Panel shown (Snoozed) | `WakeActivity` started | No error expected |
| Guarded use cases | Save, enable, delete or duplicate while not Idle | `Failure(SessionActive)`; repository, sequence and scheduler untouched | Returned, not thrown |
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

## Review Triage Log

## Design Notes

- **Not a new screen:** the session route reuses `HomeScreen` in its approved `home-session` state. The header plus panel is exactly what the owner approved; the preview hides the nav bar in that state too.
- **`SideEffect` over a derived list:** NavDisplay needs the real stack for Back, and a derived list would keep editor ViewModels alive under the lock. Mutating the stack clears them, which is what discards the draft and stops the preview.

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
