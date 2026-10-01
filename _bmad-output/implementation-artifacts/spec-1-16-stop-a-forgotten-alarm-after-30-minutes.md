---
title: 'Story 1.16: Stop a forgotten alarm after 30 minutes'
type: 'feature'
created: '2026-10-01'
status: 'done'
baseline_revision: '91c77ef2d14bc458d4472d34e15075e07aa3b8f9'
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

**Problem:** The core reducer already turns a ring with no interaction for 30 minutes into Missed, and history records it. But `WakeService` polls `engine.tick()` once a second (a 1.14 stopgap), and nothing tells the user afterwards that an alarm stopped by itself. The approved Home note ("Your {time} alarm stopped after 30 minutes. Logged as missed.") is drawn only by the design preview.

**Approach:**
- **Deadline-based ticks:** `WakeService` ticks the engine when the nearest session deadline is due, computed by a pure core function from `Deadline.remaining` on the time ports. It also ticks on every `SlotFired`.
- **Home missed note:** Home reads the latest Missed history row, through a new read-only flow on `SessionHistoryRepository`. Unless that session's note was dismissed, it shows the existing `missedAlarmAt` note.
- **Dismissals:** stored per session id in a new device-protected Preferences DataStore.

## Boundaries & Constraints

**Always:**
- **Next tick (core, pure).** `nextTickIn(state, now): Duration?` uses the same deadline `dueEvents` reads:
  - the grace end in Grace;
  - the interaction deadline in Ringing and Loud;
  - none while paused by a call, while Snoozed (the session slot ends a snooze), or when no session rings.

  `dueEvents` is refactored onto the same helper, so the two can't disagree. The result is `Deadline.remaining(now)`: monotonic on the same boot, wall time after a reboot. A wall-clock change never moves it.
- **WakeService ticks.** While a session is ongoing, one loop on the service scope does the following:
  - It reads the state and asks `nextTickIn`.
  - When there is no deadline, it waits for the next state change, so there is no busy loop.
  - Otherwise it waits until the deadline or the next state change, whichever comes first. On the deadline it calls `engine.tick()`.
  - If a tick leaves the state unchanged (for example, a failed commit), the next wait is at least 1 s.
  - The loop is cancelled on shutdown.
  - `onSlot` calls `engine.tick()` after dispatching `SlotFired`. The 60 s heartbeat slot is what wakes the CPU in deep sleep, when the main-thread timer may lag.
- **Missed.** `NoInteractionTimeout` moves the session to Missed, and the entry effects run. `SessionRecorder` writes the row with outcome Missed, then `Recorded` → Idle. The 1.14 shutdown stops sound and vibration, restores the volume, removes the notification, cancels the slot and stops the service. `WakeActivity` finishes. No new code is expected here; tests prove it.
- **History read.** `SessionHistoryRepository.observeLatestMissed(): Flow<SessionHistoryRow?>` returns the Missed row with the latest `ended_at`, as a DAO `@Query` Flow. It is a read only: `SessionRecorder` stays the only writer.
- **Dismissals port (core).** `MissedNoteDismissals` has `dismissed(): Flow<Set<String>>` and `dismiss(sessionId): Outcome<Unit, DomainError>`.
  - The core class `MissedNotes(history, dismissals)` exposes `current(): Flow<SessionHistoryRow?>` (the latest Missed row unless dismissed) and `dismiss(sessionId)`.
- **DataStore adapter (`:data`).**
  - `DataStoreMissedNoteDismissals` uses a string-set key, `missed_note_dismissed_sessions`.
  - The DataStore is created with `PreferenceDataStoreFactory.createWithPath`, in device-protected storage (`createDeviceProtectedStorageContext().filesDir/datastore/settings.preferences_pb`).
  - It is a Koin single with its own `CoroutineScope`, cancelled when Koin closes. A restarted app or test never opens a second active DataStore on the file.
  - The dependency `androidx.datastore:datastore-preferences` (already in the version catalog) is added to `:data`. Its runtime artifacts (androidx.datastore\*, okio) are added to `config/dependency-allowlist.txt` with a reviewed comment: local file IO only, no network.
  - IO errors become `StorageFailure`, and a read error emits an empty set.
- **Home.** `HomeViewModel` gets `MissedNotes`.
  - `missedAlarmAt` is the row's `scheduledAt` in the current zone.
  - `MissedNoteDismissed` dismisses that session id; the note hides once the store confirms it.
  - A read failure is logged and shows no note: never a crash, never a wrong note.
  - The layout and copy are unchanged: the existing `Notices` composable and the `home_missed_note` / `home_dismiss` strings, verbatim from EXPERIENCE.md.

**Never:**
- No change to the reducer rules or the 30-minute value.
- No new screen or design change.
- No second history writer.
- No backup-rule change: Story 2.12 / 8.x adds the DataStore file to backup.
- No `Clock.System` in core.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Forgotten | Ringing, no user event for 30 min | Missed → row Missed → Idle; service stopped, no sound, vibration or notification | No error expected |
| Interaction | `UserInteracted` at minute 20 | Still ringing at 49:59; Missed at 50 min | No error expected |
| Wall jump | Wall clock −1 h during the ring | Stop still at 30 min (monotonic) | No error expected |
| Grace | Grace, 20 s | Tick at the grace end → Loud | No error expected |
| No deadline | Snoozed, paused by a call, or Idle | `nextTickIn` is null; no tick loop spin | No error expected |
| Tick failed | Tick leaves the state unchanged | Next tick waits at least 1 s | Logged by the engine |
| Slot | `SlotFired` | A tick follows the dispatch | No error expected |
| Note shown | Latest Missed row, not dismissed | Home shows "Your 6:00 AM alarm stopped after 30 minutes. Logged as missed." | No error expected |
| Dismissed | Dismiss tapped | Id stored; note hidden, and stays hidden after a restart | Store failure logged; note stays |
| Newer missed | A later Missed session | Its note shows even if an older one was dismissed | No error expected |
| Read failure | History or DataStore flow throws | No note; logged | Logged |

</intent-contract>

## Code Map

- `core/src/commonMain/kotlin/com/yawnandpawn/app/core/session/SessionRuntime.kt:43` -- `dueEvents`. Add the shared deadline helper and `nextTickIn` here.
- `core/.../time/Deadline.kt` -- `remaining(now)` and `isDue`. `TimeSnapshot.of(clock, monotonic, boot)` (in `core/.../time/`).
- `androidApp/src/main/kotlin/com/yawnandpawn/app/android/wake/WakeService.kt` -- `startTicking()` holds the 1 s loop to replace. Also `onSlot()`, `evaluate`, `shutdown`, and `scope` on Main.immediate. Inject `MonotonicClock` and `BootCounter`.
- `core/.../history/SessionHistory.kt` -- the `SessionHistoryRepository` port: add `observeLatestMissed`. `data/.../history/SessionHistoryDao.kt` and `RoomSessionHistoryRepository.kt` (`storage {}`, `toRow()`), and the stored name "Missed" in `SessionHistoryMapping.kt:53`.
- `testing/.../FakeSessionHistoryRepository.kt` -- needs `observeLatestMissed`. Other implementers: `WakeActivityTest.CountingHistory`, and any `SessionHistoryRepository` object in tests (grep for them).
- `data/src/androidMain/kotlin/com/yawnandpawn/app/data/DataModule.kt` -- Koin; databases close in `onClose`. `data/build.gradle.kts`, `gradle/libs.versions.toml:83` (`datastore-preferences`) and `config/dependency-allowlist.txt`.
- `composeApp/.../ui/home/HomeViewModel.kt` -- `render`, `LocalState` and `onIntent`, where the `else` branch swallows `MissedNoteDismissed` today. `HomeContract.kt:48` holds `missedAlarmAt`, and `HomeScreen.kt:244` renders the note. `UiModule.kt:18` is the wiring. `AlarmActions.logFailure` handles logging.
- Tests:
  - `androidApp/src/test/.../wake/WakeServiceTest.kt` (`upsert`, `app.ring`, `idleFor`, `isStoppedBySelf`) and `WakeApp.kt` (add a `clock` override);
  - `composeApp/src/commonTest/.../home/HomeViewModelTest.kt` (lines 91, 402) and `androidApp/src/test/.../ui/AlarmScreensSemanticsTest.kt:473` (constructor sites);
  - `androidApp/src/test/.../ui/HomeScreenshotTest.kt` + `HomeSamples.kt` for the new screenshots;
  - `data/src/androidHostTest/.../history/RoomSessionHistoryRepositoryTest.kt`.

## Tasks & Acceptance

**Execution:**
- `core/.../session/SessionRuntime.kt` -- `nextTickIn` and the shared deadline helper. Tests in core `commonTest`: each state, paused, a wall jump, a reboot fallback, and due → zero.
- `core/.../history/MissedNotes.kt` -- the `MissedNoteDismissals` port and the `MissedNotes` class, with tests.
- `core/.../history/SessionHistory.kt`, `data/.../history/*`, `testing/...` -- `observeLatestMissed`: the DAO query, the repository, the fake, and a Room test (latest by `ended_at`, ignores other outcomes, emits on upsert).
- `testing/.../FakeMissedNoteDismissals.kt` -- an in-memory fake with failure switches.
- `data/.../settings/DataStoreMissedNoteDismissals.kt` + `DataModule.kt` + `data/build.gradle.kts` + allowlist -- the adapter, a Robolectric test (persists across a new instance, read error → empty set), and Koin.
- `androidApp/.../wake/WakeService.kt` -- the deadline loop and the slot tick. KDoc updated.
- `composeApp/.../ui/home/HomeViewModel.kt` + `UiModule.kt` -- the note wiring. ViewModel tests: shown, dismissed, a newer missed one, a read failure, the zone.
- `androidApp/src/test/.../wake/WakeServiceTest.kt` -- 30 min → stopped + Missed row; interaction at 20 → stop at 50; wall −1 h unchanged; a slot fire ticks.
- `androidApp/src/test/.../ui/HomeScreenshotTest.kt` -- `home_missed_note_light`, `_dark` and `_light_font200`.

**Acceptance Criteria:**
- Given a ringing session nobody touches, when 30 minutes pass on the monotonic clock, then the service stops, nothing rings or shows, and history has a Missed row.
- Given that Missed row, when Home opens, then the note shows until it is dismissed, and the dismissal survives a new app instance.
- Given `./gradlew qualityGate`, when it runs, then it passes with the allowlist updated and no preview baseline change.

## Spec Change Log

## Review Triage Log

## Design Notes

- **Wait-or-change:** `withTimeoutOrNull(wait) { engine.state.first { it != seen } }` waits for either the deadline or a state change. Unlike `collectLatest`, it never cancels a running `engine.tick()`.
- **Environment:** `JAVA_HOME=C:/Users/BuiTua/AppData/Local/Programs/jdk17/jdk-17.0.20.1+1`. No phone. No admin.

## Verification

**Commands:**
- `./gradlew qualityGate` -- expected: BUILD SUCCESSFUL.
- `git status --porcelain androidApp/src/test/screenshots/preview` -- expected: empty.

## pps-design Done checklist

- [x] Only tokens from `DESIGN.md` are used. No UI code changed: the existing `Notices` / `NoteInline` / `DismissButton` render the note.
- [x] Light and Dark checked with screenshots: `home_missed_note_light` and `home_missed_note_dark` (Sunrise doesn't apply: Home is an app screen).
- [x] Every colour pair used is in the `DESIGN.md` contrast table (the existing `note-inline` pairs).
- [x] Touch targets are at least 48 dp: Dismiss is the existing 48 dp icon button. There are no wake actions here.
- [x] Works at 200% font scale (`home_missed_note_light_font200`) and with TalkBack (Dismiss has its content description). Outcome glyphs: not applicable on Home.
- [x] Reduced-motion path works: no new animation.
- [x] Copy matches `EXPERIENCE.md > Voice and Tone`: "Your {time} alarm stopped after 30 minutes. Logged as missed." and "Dismiss" are existing resources, asserted verbatim in `HomeScreenshotTest`, and `CopyRulesTest` passes.
- [x] Every state row in `EXPERIENCE.md > State Patterns` for this surface is handled. "Missed session | Home | note-inline … until dismissed": shown, dismissed, a newer one, and a read failure (no note).
- [x] "I'm up" is the most prominent wake action: not applicable (no wake screen change).
- [x] Previews and screenshots: the preview catalogue is unchanged, and Roborazzi has Light, Dark and 200%.

## Auto Run Result

**Summary:** a forgotten alarm now stops after 30 minutes and Home says so.

- **Deadline-based ticks:**
  - `WakeService`'s 1 s poll is replaced by a loop that waits for the next session deadline or a state change, whichever comes first. The deadline comes from core `nextTickIn` (shared with `dueEvents`, monotonic on the same boot).
  - With no deadline it doesn't spin, and a tick that changed nothing is retried after 1 s at the earliest.
  - The tick runs on `ApplicationScope`, so the service stopping on Missed can't cut off the history write.
  - `SlotFired` also ticks.
- **Missed:** sound, vibration and the notification stop, the service stops, `WakeActivity` closes, and `SessionRecorder` records Missed. This was existing behaviour, now proven by tests.
- **Home missed note:**
  - `SessionHistoryRepository.observeLatestMissed()` is a Room `@Query` Flow (a read only).
  - The core `MissedNotes` class combines it with the new `MissedNoteDismissals` port.
  - `HomeViewModel` fills the existing `missedAlarmAt` note, and "Dismiss" stores the session id.
- **DataStore:**
  - `SettingsDataStore` in `:data` uses `PreferenceDataStoreFactory.createWithPath` on `deviceProtected filesDir/datastore/settings.preferences_pb`.
  - It is a Koin single whose scope is cancelled and joined on close.
  - The adapter is `DataStoreMissedNoteDismissals`.
  - New runtime artifacts (androidx.datastore\*, okio) are on the dependency allowlist, with a review note.

**Verification:**
- `./gradlew qualityGate` gives BUILD SUCCESSFUL.
- New suites:
  - `NextTickTest` (5) and `MissedNotesTest` (3) in core;
  - `DataStoreMissedNoteDismissalsTest` (3), `DataModuleTest` (+1) and `RoomSessionHistoryRepositoryTest` (+2) in `:data`;
  - `HomeViewModelTest` (+6);
  - `ForgottenAlarmTest` (6): 30 min → Missed and service stopped; an interaction at 20 → stop at 50; wall −1 h unchanged; a wall jump forward doesn't stop it early; failed-commit retry about once a second with no busy loop; a slot fire after a missed timer;
  - `WakeActivityTest` (+1): Missed closes the screen;
  - `HomeScreenshotTest` (+3).
- Preview baselines unchanged.

### 2026-10-02 — Review fixes

- **Slot test:** the slot-fire test now advances only a fake `MonotonicClock` (a new `WakeApp` option), so the service's own timer is still in the future and only the slot path can stop the alarm.
  - Finding: the `SlotFired` dispatch already runs the due timer events, so the extra `engine.tick()` in `onSlot` is a second safety net. Removing it doesn't fail the test.
- **Dismissals read retry:** a dismissals read error (`IOException`) is logged ("read missed note dismissals"), reads as nothing dismissed, and the read is retried (1 s, doubling, at most 1 min), so the flow stays alive. Other errors are rethrown. The adapter takes a `Logger`.
- **History read retry:** a failing history read on Home is logged on every failure and retried with the same backoff. Before, one failure ended the flow.
- **Dismiss names its session:** `HomeUiState.missedSessionId` carries the session, and `HomeIntent.MissedNoteDismissed(sessionId)` dismisses exactly that one. A newer Missed row that arrives before the tap stays (new test).
- **Reboot cap:** across a reboot (`bootCount` differs), `nextTickIn` waits at most one 60 s heartbeat, because wall time can still move.
- **Tests:**
  - `DataModuleTest`: the second app's dismiss returns Success and reads back the first app's id.
  - Room and DataStore tests: one open collection receives the new value.

**Residual risks:**
- On a device, deep sleep can delay the main-thread timer. The 60 s heartbeat slot bounds the lag, but only device testing (Story 1.20/1.21) shows the real stop time.
- The DataStore file isn't in the backup rules yet (Story 2.12 / 8.x adds it).
- The dismissed-id set grows by one id per dismissed missed session (tiny).

## Review Triage Log (fast mode)

### 2026-10-02 — Review pass
Two layers (edge case and verification gap), patching only high and medium findings.
- **Patched (medium):**
  - The deep-sleep slot test now advances only a fake monotonic clock.
  - The DataStore dismissal read survives an IO error (retry with backoff, logged).
  - The missed-note history read retries instead of completing.
  - Dismiss names the session it hides.
  - The wait is capped at the 60 s heartbeat when deadlines use wall time after a reboot.
- **New tests:** DataStore close and reopen, and live re-emission of history rows and dismissals.
- **Rejected (low):** ordering by `ended_at` when the wall clock is set back between two Missed sessions.
- **Noted:** the extra `engine.tick()` in `onSlot` is redundant with `SlotFired`'s due loop, and is kept as a safety net.
