---
title: 'Story 2.4: Unlocking during a ring makes snooze available in place'
type: 'feature'
created: '2026-10-05'
status: 'done'
baseline_revision: '36a60b238c3a8e95e09fc7fb265a784e2ef6e9ec'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-2-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-2-3-ring-before-the-first-unlock-after-a-reboot.md'
  - '{project-root}/.claude/skills/pps-design/SKILL.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** A ring that starts before the first unlock after a reboot shows snooze disabled with "Unlock your phone to snooze". When the user unlocks during the ring, nothing notices it:
- the `UserUnlocked` row never runs;
- billing and Firebase stay uninitialised;
- the wake screen keeps the locked label. A Story 2.3 reviewer deferred this last point to 2.4.

There is also a 2.3 behaviour to fix. The ring's Direct Boot sound follows the session's `beforeFirstUnlock` flag, so the `UserUnlocked` row would switch the sound in the middle of a ring. The AC says the substituted sound and check stay for the current ring.

**Approach:**
- **Signals.** Every unlock signal goes to one `UnlockSignals` (androidApp):
  - `ACTION_USER_UNLOCKED`, observed by `WakeService` through the Story 2.3 `UserLockState.observe()` while a `beforeFirstUnlock` session runs;
  - `BOOT_COMPLETED` while the user is unlocked;
  - `WakeActivity.onResume` while the user is unlocked.
- **What a signal does.** It initialises billing and Firebase once, outside the table. While the in-memory session is still marked locked, it also dispatches `UserUnlocked`.
- **In-place snooze.** `WakeActivity` collects the live lock state, so the snooze availability (from the 2.3 `NoBillingSnoozeAvailability` with its live lock input) is recomputed in place.
- **Sound for the ring.** A new `SessionData.directBootRing` decides the sound per ring. It is set when a ring starts or is restored while locked, it is kept on unlock, and it is decided again at the next ring.

## Boundaries & Constraints

**Always:**
- **AD-2 table is unchanged.** The only `UserUnlocked` row is Ringing (before first unlock). It clears `beforeFirstUnlock`, keeps `startedBeforeUnlock`, and runs `LiftDirectBootSubstitutions` and `InitBilling`. Grace and Loud ignore and log the event.
- **`Billing.init()`** is a new default no-op on the port. `FakeBilling` counts it.
- **Initialising after the unlock.** `UnlockSignals.initialiseAfterUnlock()` calls `Billing.init()` and `FirebaseStartup.start()` once per process. The `InitBilling` effect (through `WakeRuntime`) and every signal use it, so an unlock in Grace or Loud still initialises them.
- **Signals never throw.**
  - A signal after the unlock changes nothing.
  - A signal never restores a session the engine has not loaded (receivers stay free of restore, Story 2.1).
  - The dispatch is launched on `ApplicationScope`, never awaited inside an engine effect.
- **The `WakeService` receiver.** The `ACTION_USER_UNLOCKED` receiver (registered by the `observe()` collection) lives only while a locked session runs. It is cancelled, and so unregistered, when the session ends or the service stops. Once the unlock is seen it is not re-armed, so a Grace or Loud ignore happens once.
- **Sound per ring.**
  - `directBootRing` is set at session start (`= beforeFirstUnlock`) and by `lockedIf` (restore, or a new ring while locked).
  - The next ring after a snooze or a merge takes the live lock state.
  - `entryEffects` substitutes the sound when `directBootRing` is true.
- **`WakeActivity`.**
  - It renders the availability again when the lock state flips, and is never finished or recreated. This also covers an unlock through `requestDismissKeyguard`, because the screen stays resumed.
  - Its `onResume` with the user unlocked is a signal.
  - The change is kept small, because Lane 2 reworks this file.
- **Copy and screenshots.** Strings are the existing resources, verbatim:
  - "Unlock your phone to snooze";
  - "Prices not loaded yet" (TalkBack "Snooze unavailable, prices not loaded yet", owner decision 2026-10-02);
  - "Snooze · {price}".

  Roborazzi in Sunrise at 100 % and 200 %: after the unlock, "Prices not loaded yet", and "Snooze · {price}" with a fake `Available` policy. Before the unlock is `wake_ringing_locked_*` from 2.3.

**Never:** No payment flow or Play sheet (Epic 4). No new strings, screens or table rows. No preview baseline changes.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Unlock while Ringing | `beforeFirstUnlock` session; `ACTION_USER_UNLOCKED` | `UserUnlocked` applied; `directBootRing` and `startedBeforeUnlock` kept; `Billing.init()` once; receiver gone at the session's end | — |
| Unlock in Grace or Loud | Same, state Grace | Ignored and logged; billing still initialised | Never throws |
| Screen in front | `FakeUserLockState` flips while `WakeActivity` is resumed | "Unlock your phone to snooze" → "Prices not loaded yet" in place; same instance, RESUMED | — |
| `onResume` unlocked | Screen resumed after the unlock | `UserUnlocked` applied | — |
| `BOOT_COMPLETED` | Unlocked; the ring missed the broadcast | `UserUnlocked` applied | Not loaded → nothing |
| Repeats | `onUnlocked`, then `BOOT_COMPLETED`, after the unlock | Nothing changes; billing initialised once | Never throws |
| Next ring | Snooze ends after the unlock | Chosen sound (`directBootRing` false) | — |
| Fake `Available` | Policy offers a price | "Snooze · {price}" (screenshot) | — |

</intent-contract>

## Code Map

- `core/.../session/SessionPorts.kt` (`Billing.init`), `SessionState.kt` (`directBootRing`).
- `IdleRules.kt`, `SessionTimers.kt` (`lockedIf`), `SnoozedRules.kt` (`nextRing`), `SessionRuntime.kt` (`soundOf`).
- `testing/.../SessionEngineFakes.kt`: `FakeBilling.initCalls`.
- `androidApp/.../wake/UnlockSignals.kt` (new), `WakeModule.kt` (wiring, `onInitBilling`), `WakeRuntime.kt` (`InitBilling`).
- `WakeService.kt`: `watchUnlock`.
- `WakeActivity.kt`: the live lock state and `onResume`.
- `android/SystemEventsReceiver.kt`: `BOOT_COMPLETED`.
- Tests:
  - `DirectBootTest` (+3) and `SessionTransitionTable` (R01 expected field);
  - new `UnlockDuringRingTest` (6);
  - `WakeApp` (`userLock` override);
  - `RingingSamples` and `RingingScreenshotTest` (+4 baselines).

## Tasks & Acceptance

**Execution:** implement the Code Map, with a test for every matrix row.

**Acceptance Criteria:**
- Given `./gradlew qualityGate`, then it passes. The Kover gates are green, the preview baselines are unchanged, and only the four new 2.4 baselines are added.
- Given the pps-design Done checklist:
  - [x] tokens only;
  - [x] strings are resources, verbatim from EXPERIENCE.md;
  - [x] Sunrise 100 %/200 %;
  - [x] targets ≥ 64 dp, unchanged;
  - [x] TalkBack order is clock, then I'm up, then snooze, unchanged;
  - [x] `CopyRulesTest` passes.

## Verification

**Commands:**
- `./gradlew qualityGate` -- expected: BUILD SUCCESSFUL.
- `git status --porcelain androidApp/src/test/screenshots/preview` -- expected: empty.

## Spec Change Log

- 2026-10-06: rebased from Lane 2's blocked draft (674aed8) onto Lane 1's 2.3 (port, adapter, fake, live policy). Added `directBootRing`, so the current ring keeps its substitutions after an unlock (AD-2 "stay for the current ring").

## Review Triage Log

## Auto Run Result

Status: done (fast mode: one agent planned and implemented, no separate review pass). Stacked on Story 2.9 (36a60b2).

**Summary**
- Every unlock signal now goes to `UnlockSignals`:
  - the `ACTION_USER_UNLOCKED` receiver that `WakeService` keeps (through `UserLockState.observe()`) while a session before the first unlock runs;
  - `BOOT_COMPLETED` once the phone is unlocked;
  - `WakeActivity.onResume` once the phone is unlocked.
- On a signal, billing and Firebase start once, outside the table, and `UserUnlocked` is dispatched while the in-memory session is still marked locked.
- `WakeActivity` collects the lock state, so the snooze control changes in place. It is not finished or recreated, which also covers an unlock through `requestDismissKeyguard`.
- `SessionData.directBootRing` keeps the Direct Boot sound for the current ring after the unlock; the next ring takes the live lock state.
- `Billing.init()` is new: a default no-op that `FakeBilling` counts.
- The AD-2 table is unchanged. Only R01's expected session in the table test gained the new field.

**Files changed**
- **core:**
  - `SessionPorts.kt`, `SessionState.kt`, `IdleRules.kt`, `SessionTimers.kt`, `SnoozedRules.kt`, `SessionRuntime.kt`;
  - tests: `DirectBootTest` (+3), `SessionTransitionTable` (R01).
- **testing:** `FakeBilling.initCalls`.
- **androidApp:**
  - `wake/UnlockSignals.kt` (new), `WakeModule.kt`, `WakeRuntime.kt` (`InitBilling`), `WakeService.kt` (`watchUnlock`), `WakeActivity.kt` (live lock state, `onResume`), `SystemEventsReceiver.kt`;
  - tests: `UnlockDuringRingTest` (6), `WakeApp` (`userLock` override), `RingingSamples` / `RingingScreenshotTest` (4 new baselines: `wake_ringing_unlocked_prices_sunrise(_font200)`, `wake_ringing_unlocked_snooze_sunrise(_font200)`).
- **data (test only):** `RoomAlarmRepositoryTest` gets a 5-minute `runTest` limit. It timed out at the 1-minute default under a loaded gate run, so this makes it deterministic.

**Verification**
- `./gradlew qualityGate`: BUILD SUCCESSFUL. Its tests ran in the earlier runs with these inputs and passed. That includes `UnlockDuringRingTest` 6/6, `RingingScreenshotTest` 18/18 and `RoomAlarmRepositoryTest` 13/13. The Kover gates are green.
- The preview baselines are unchanged.

**Residual risks**
- A real unlock on the lock screen and through `requestDismissKeyguard` is human-verify in Story 2.13.
- `WakeActivity` will conflict with Lane 2's 2.6/2.8/2.11 rework on rebase. My change there is small: two injections, an `onResume`, and one collected state feeding the availability.
