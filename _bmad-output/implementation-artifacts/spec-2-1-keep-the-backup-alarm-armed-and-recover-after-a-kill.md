---
title: 'Story 2.1: Keep the backup alarm armed and recover after a kill'
type: 'feature'
created: '2026-10-05'
status: 'done'
baseline_revision: '4bd25ee4d0cfbcb25e77c90cfcce0094d66ef317'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-2-context.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Epic 1 arms a 60 s session slot, but nothing proves that a killed process comes back on the same step. The slot also fires into the general alarm receiver. `YawnAndPawnApp.onCreate` restores the session for every process start, so a boot or clock broadcast runs ringing entry effects and starts a media foreground service from a boot receiver (not allowed on Android 15+). Three cases are never recovered: an orphan slot, a refused service start for a fresh alarm, and a process death during the emergency default-sound ring (deferred to 2.1).

**Approach:**
- A dedicated `SessionSlotReceiver` gets the slot.
- `restore()` runs only from `WakeService`, `MainActivity` and `WakeActivity`.
- A pure-core `SessionSlotRearm` re-arms the slot from `runtime.db` without the engine, for non-alarm broadcasts and for refused service starts.
- The slot can carry the alarm it stands for when no session holds that alarm (a refused start, or an emergency ring), so a fresh process still rings it.
- Robolectric kill tests prove recovery in every ringing and snoozed state.

## Boundaries & Constraints

**Always:**
- **Reducer table unchanged.** No new states, rows or effects. The heartbeat stays the AD-2 `SlotFired` row (+60 s). Snoozed arms at the snooze end. The FGS type stays `mediaPlayback`, and the heartbeat stays as it is until Spike S2.
- **One slot.** The slot uses one request code, `RequestCodes.SESSION_SLOT`, through `setAlarmClock` only. Re-arming replaces it (`FLAG_UPDATE_CURRENT`, which also replaces the extras). The operation is an immutable broadcast to `SessionSlotReceiver` (`directBootAware`, `exported=false`, no intent filter). The show intent opens `MainActivity`.
- **Slot payload.** `AlarmScheduler.armSessionSlot(deadline, alarm: AlarmFired? = null)`. A non-null `alarm` rides as extras, and the fire hands it to `AlarmFiredHandler.onSessionSlotFired(alarm)`, then to `WakeService` (`ACTION_SLOT` with the same extras).
- **WakeService order.** `startForeground` first, then `engine.restore()` before any broadcast-derived event (`AlarmFired`, `TestAlarmFired`, `OverlapAlarmFired`, `SlotFired`). The slot command does the following after the restore:
  - **Payload alarm present:** it is handled exactly like an alarm start (`onAlarm`). It merges into an active session (`OverlapAlarmFired`) or starts one; it never makes a second session.
  - **Session active:** `SlotFired`, then `tick`.
  - **Emergency ring playing, no session:** the backup slot is re-armed.
  - **Otherwise (orphan):** it logs `FireIgnored(SessionSlot, "no session stored")` and cancels the slot, then the service shuts down (notification removed, stopped).
- **Heartbeat entry effect.** `HeartbeatSlotArmed` arms `now + 60 s` when this process has no armed slot, or when its armed slot is already due (a fire this process never handled). `WakeRuntime.endSession()` always cancels the slot; a fresh alarm of another refused start that the slot carried is armed again one heartbeat later (review).
- **Emergency ring backstop.** `startEmergency` with no active session arms the slot `now + 60 s` carrying the alarm it rings for (when known). A live slot fire during it re-arms the slot. A session taking over drops the payload, because its own heartbeat re-arms. After a kill, the payload alarm rings again through `onAlarm`. That gives a real session, or the emergency ring again if storage is still broken. A restored emergency gets a fresh 30-minute limit.
- **Refused starts.** `SessionSlotRearm.afterRefusedStart(alarm)` re-arms the slot one heartbeat from now in these cases:
  - `WakeServiceStarter` refuses an alarm or slot start (in `WakeAlarmFiredHandler`);
  - `startForeground` is refused in `WakeService` for an alarm, slot or restore start.

  The payload is kept only while the alarm is less than 30 min past its scheduled time. With no payload, it re-arms for a stored Ring state, and for a Snoozed state at its snooze end (or a heartbeat if that is due). Otherwise it arms nothing. Test starts are never retried. Review: a payload-less re-arm keeps the alarm the armed slot still carries, and the retries stop (logged) 30 min after the first refusal (`retrySince` rides on the slot).
- **Non-alarm broadcasts.** `SystemEventsReceiver` runs `rescheduleAll()`, then `SessionSlotRearm.afterSystemEvent()`. It arms `now + 1 s` for Ringing, Grace or Loud, and the snooze end for Snoozed. It arms nothing for Idle or ended states, and logs a store failure. It never touches the engine and never starts a service.
- **Restore entry points.** `MainActivity.onCreate` and `WakeActivity.onCreate` launch `engine.restore()` on `ApplicationScope`. `YawnAndPawnApp.onCreate` no longer restores. It puts back a saved user alarm volume only when `runtime.db` holds no session (`StoredSession.Empty`, an unreadable row or a stored Idle; review).
- **Swipe from Recents.** `WakeService.onTaskRemoved` keeps the session, player and notification. `stopWithTask` is never set.
- **No ramp on a restored ring.** A sound entry effect with nothing playing and no new-ring one-shot in the same step (`StartWakeRuntime` or `ArmSlot`) is a restore. It plays at the set volume with no ramp, and later steps of that ring keep the same request.
- Core stays pure, with no Android types. The Kover gates (core ≥ 90 %, core.session ≥ 90 %) stay green. The never-silent rules from 1.14 and 1.18 stay as they are.

**Never:** No UI or string changes. No "restored" message. No `LOCKED_BOOT_COMPLETED` or user-unlocked receiver (Story 2.3). No change to the deadline semantics (Story 2.2). No `session_merge` writes (Story 2.9). No inexact alarms. Never await `engine.dispatch` inside an effect. Never call `restore()` from `Application.onCreate` or a receiver.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Kill in Ringing / Grace / Loud | Room `runtime.db` holds the state; new engine; slot delivered via `SessionSlotReceiver` | Same state and session id. The sound plays again (Grace stays muted), the slot is re-armed, and the interaction deadline is fresh | — |
| Kill in Ringing with `paying` | `paying` set | Ringing, `paying` null, no billing launched | — |
| Kill in Snoozed | Snooze end 9 min ahead | An early slot leaves it silent, Snoozed, slot at the snooze end. After the snooze end, Ringing(ringIndex + 1), same snoozesGranted. Then I'm up gives one history row | — |
| Restored Grace past its deadline | graceEnd passed | Loud in the same restore | — |
| Orphan slot | Nothing stored | Logged, slot cancelled, notification removed, service stopped | — |
| Slot with payload, no session | `AlarmFired` extras | A real session starts for that alarm | Store broken → emergency ring + backup slot |
| Slot with payload, active session | Ringing stored | `OverlapAlarmFired`, one session | — |
| Non-alarm broadcast | BOOT_COMPLETED with a Ringing / Snoozed / Idle / Completed session | Slot at now + 1 s / at snooze end / none / none; no service start | Load failure logged |
| Refused alarm start | `startForegroundService` throws | Slot at +60 s with the alarm payload | Arm failure logged |
| Refused slot start, nothing stored, payload stale | — | Nothing armed | — |
| Emergency ring | Store broken | Slot armed +60 s with the alarm; cancelled when "I'm up" ends it | — |
| Swipe from Recents | `onTaskRemoved` | The player still plays | — |

</intent-contract>

## Code Map

- `core/.../core/alarm/AlarmScheduler.kt` -- the port: `armSessionSlot` gains `alarm: AlarmFired? = null`.
- `core/.../core/alarm/AlarmFiredHandler.kt` -- `onSessionSlotFired(alarm: AlarmFired?)`; `RearmOnFire` logs and ignores it.
- `core/.../core/session/SessionSlotRearm.kt` (new) -- `afterSystemEvent()` and `afterRefusedStart(alarm)` over `ActiveSessionStore`, `AlarmScheduler` and the time ports.
- `core/src/commonTest/.../alarm/AlarmTestDoubles.kt`, `testing/.../SchedulerFakes.kt` -- the fakes get the new signature (`SchedulerCall.ArmSessionSlot(deadline, alarm = null)`).
- `androidApp/.../android/SessionSlotReceiver.kt` (new) and `AlarmFiredReceiver.kt` -- the slot moves to its own receiver. They share the bounded `goAsync` helper (`ReceiverWork.kt`).
- `androidApp/.../android/AndroidAlarmScheduler.kt` -- the slot operation goes to `SessionSlotReceiver` with payload extras, and the cancel matches it.
- `androidApp/.../android/SystemEventsReceiver.kt` -- calls `afterSystemEvent()` after `rescheduleAll()`.
- `androidApp/.../wake/WakeAlarmFiredHandler.kt`, `WakeServiceStarter.kt` -- the slot payload, and refused starts go to `afterRefusedStart`.
- `androidApp/.../wake/WakeService.kt` -- the slot command logic, restore before every event, the refused-`startForeground` retry, and `onTaskRemoved`.
- `androidApp/.../wake/WakeRuntime.kt` -- the stale heartbeat re-arm, `endSession` cancels always, the emergency backup slot.
- `androidApp/.../YawnAndPawnApp.kt`, `MainActivity.kt`, `wake/WakeActivity.kt` -- the restore entry points. The Koin single for `SessionSlotRearm` is added.
- `androidApp/src/main/AndroidManifest.xml` -- the `SessionSlotReceiver` entry.

## Tasks & Acceptance

**Execution:**
- Core: write `SessionSlotRearm` and `SessionSlotRearmTest` (every state × both entry points, stale payload, store failure, arm failure). Make the port changes and update the fakes.
- Android: make the receiver, scheduler, handler, starter, service, runtime and app wiring changes listed in the Code Map.
- Tests:
  - New `SessionKillRecoveryTest`, covering the matrix kill rows through `SessionSlotReceiver` with Room `runtime.db` and history.
  - New `RestoreEntryPointsTest`: a source scan that allows `restore()` only in `WakeService`, `MainActivity` and `WakeActivity`. It also checks that app start runs no restore and starts no service, and that `MainActivity` creation restores.
  - Extend the scheduler, receiver, manifest, handler, service, runtime and system-events tests.
  - Rewrite `AppStartRestoreTest` to match the new entry points.

**Acceptance Criteria:**
- Given any matrix row, then its test passes in `./gradlew qualityGate`.
- Given the Story 1.11 table-coverage test, then it passes unchanged.
- Given `git status --porcelain androidApp/src/test/screenshots/preview`, then it is empty.

## Design Notes

Why the payload rides on the slot: the emergency ring and a refused start both mean that no committed session holds the alarm. The `PendingIntent` extras survive the process, the slot is already the single kill-recovery path, and a later session heartbeat replaces the extras (`FLAG_UPDATE_CURRENT`). That rules out a second slot and any extra storage.

What stays for Spike S2 and Story 2.13: whether `stopSelf` after a refused `startForeground` crashes (`RemoteServiceException`), whether a retried start from a slot fire is allowed on the device matrix, and the real retry cadence (heartbeat 60 s here).

## Verification

**Commands:**
- `./gradlew qualityGate` -- expected: BUILD SUCCESSFUL. This covers detekt, ktlint, lint, the core and session Kover gates, and every host test.
- `git status --porcelain androidApp/src/test/screenshots/preview` -- expected: empty.

## Spec Change Log

## Review Triage Log

### Review (2 reviewers, fast mode), 2026-10-05

Patched (14):
1. `WakeService.onSlot`: the emergency ring is checked before a failed restore, so a slot fire while it plays always re-arms its backup slot (also with an unreadable store).
2. Payload-less re-arms keep the alarm the armed slot still carries: new port `AlarmScheduler.sessionSlotAlarm()` (the Android adapter knows the slot this process armed, until its trigger time). `SessionSlotRearm.afterSystemEvent` and `afterRefusedStart(null)` (which also covers the refused-restore retry in `WakeService`) arm with it.
3. Refused-start retries are capped: the slot carries `retrySince` (extra `retrySince`, through `SessionSlotReceiver`, `onSessionSlotFired`, `startSlot` and the `ACTION_SLOT` intent). `afterRefusedStart` gives up, logged, 30 minutes after the first refusal (or the alarm's scheduled time).
4. `WakeRuntime.endSession` re-arms a foreign carried alarm (not the emergency ring's own, less than 30 min past) one heartbeat later after cancelling.
5. `WakeService.onSlot` ignores, logged, a payload alarm 30 minutes or more past.
6. `WakeService`: when the restore failed but the load inside `dispatch(AlarmFired)` finds a ringing or snoozed session, the alarm is routed again (merged, or a test ended first).
7. `WakeAlarmFiredHandler`: the refused-start re-arm runs in the start's `finally`, under `NonCancellable`, so a read that throws or overruns still re-arms.
8. `WakeRuntime`: a session sound that replaces the emergency default sound counts as a sound start (restored: no ramp; new: ramp), so no stale no-ramp flag.
9. Legacy slot: `AlarmFiredReceiver` forwards the pre-2.1 slot action to `onSessionSlotFired(null)`, and `MY_PACKAGE_REPLACED` cancels that PendingIntent (`AndroidAlarmScheduler.cancelLegacySessionSlot`).
10. `SystemEventsReceiver`: `rescheduleAll()` runs first, then the slot re-arm.
11. `YawnAndPawnApp`: the saved alarm volume is also put back for an unreadable row or a stored Idle (still only a read).
12. `WakeAlarmFiredHandler.rearm` is required; a `WakeApp` test proves the production wiring (refused start through the receiver arms the slot carrying the alarm).
13. `SessionSlotRearmTest`: refused start with stored Ringing, Missed and Unreadable rows, arm failure, carried payload kept or dropped when stale, retry cap.
14. Matrix row "slot with payload, store broken": `WakeServiceTest` delivers a payload slot into a broken store (emergency ring plus a backup slot carrying the alarm).

Verification: `./gradlew qualityGate` BUILD SUCCESSFUL (16m 4s, 2026-10-05); `git status --porcelain androidApp/src/test/screenshots/preview` empty.

Deferred (in `deferred-work.md`, Story 2.1 entry):
- A host test for a refused `startForeground` retry: Robolectric cannot make `startForeground` throw (Spike S2 / Story 2.13).
- Settling a stored Completed or Missed session without a foreground service until the UI opens (low; Story 2.3 / 2.13).
- The carried alarm is known only in the process that armed the slot; a payload-less re-arm in a new process can still replace it (rare; Story 2.13).

## Auto Run Result

Status: done (fast mode: planned and implemented by one agent, no separate review pass)

**Summary.** The session slot now has its own `SessionSlotReceiver`. It can carry the alarm it stands for. Restore runs only from `WakeService`, `MainActivity` and `WakeActivity`. System events and refused starts re-arm the slot through the new pure-core `SessionSlotRearm`, never through the engine. The emergency ring keeps a backup slot. A restored ring plays with no ramp. The reducer table is unchanged.

**Files changed**

Core and test fakes:
- `core/.../alarm/AlarmScheduler.kt`, `AlarmFiredHandler.kt`: the slot can carry an `AlarmFired`, and `onSessionSlotFired(alarm)`.
- `core/.../session/SessionSlotRearm.kt` (new): `afterSystemEvent()` and `afterRefusedStart(alarm)`.
- `core/.../log/Logger.kt`: new `LogEvent.SessionSlotRearmed`.
- Core and `:testing` fakes: updated for the new signatures.

Android:
- `androidApp/.../android/SessionSlotReceiver.kt` (new) and `ReceiverWork.kt` (new): the shared bounded `goAsync` helper and the alarm extras helpers.
- `AlarmFiredReceiver.kt`: no longer handles the slot.
- `AndroidAlarmScheduler.kt`: the slot goes to `SessionSlotReceiver`, with its payload extras.
- `SystemEventsReceiver.kt`: re-arms the slot, then runs `rescheduleAll()`.
- `AndroidLogger.kt`: logs the new event.
- `wake/WakeService.kt`, in five parts:
  - the slot command: restore, payload alarm, `SlotFired`, emergency backup or orphan cleanup;
  - restore before a test fire;
  - a refused `startForeground` re-arms the slot;
  - `onTaskRemoved`;
  - `slotIntent`.
- `wake/WakeRuntime.kt`, in four parts:
  - a heartbeat that is stale (already due) is re-armed;
  - `endSession` always cancels the slot;
  - the emergency backup slot (`keepEmergencySlot`);
  - no ramp on a restored ring.
- `wake/WakeAlarmFiredHandler.kt`, `WakeServiceStarter.kt`: refused starts go to `SessionSlotRearm`, and the slot payload is passed on.
- `YawnAndPawnApp.kt`: no restore at app start. The volume is put back only when `runtime.db` is empty. Koin wiring added.
- `MainActivity.kt`, `wake/WakeActivity.kt`: restore on creation.
- `AndroidManifest.xml`: the `SessionSlotReceiver` entry.

Tests:
- New: `SessionSlotRearmTest` (core), `SessionKillRecoveryTest`, and `RestoreEntryPointsTest` (replaces `AppStartRestoreTest`).
- Extended: `WakeServiceTest`, `WakeRuntimeTest`, `WakeAlarmFiredHandlerTest`, `AndroidAlarmSchedulerTest`, `ReceiversManifestTest`, `AlarmFiredReceiverBudgetTest`.

**Review findings.** No separate review pass ran (owner-approved fast mode). Three issues were found and fixed during verification:
1. A heartbeat `ArmSlot` while a restored ring played restarted the ring with a ramp. The ramp choice is now made only when a sound starts.
2. Kill tests were flaky: the first process's history start row could still be in flight when the closed Room instance held the `app.db` lock. The test now waits for the start row before killing.
3. Detekt complexity findings in `WakeService.onStartCommand`, `WakeRuntime.applyEffect`, `AndroidAlarmScheduler` and `AndroidLogger`.

**Follow-up review recommended:** false.

**Residual risks.** All of the following are in `deferred-work.md`, Story 2.1 entry:
- The refused-`startForeground` branch has no host test.
- The `stopSelf` crash and whether the platform allows a retried start both need device evidence (Spike S2 / Story 2.13).
- Real process kills are human-verify in Story 2.13.

**Verification.** `./gradlew qualityGate`: BUILD SUCCESSFUL (11m 52s, 2026-10-05). It covers detekt, spotless, lint, Kover (core and core.session), every host test, the allowlists and `checkReleaseContent`. New or extended test classes, all passing:

| Test class | Tests |
|---|---|
| `SessionKillRecoveryTest` | 8 |
| `RestoreEntryPointsTest` | 8 |
| `WakeServiceTest` | 24 |
| `WakeRuntimeTest` | 29 |
| `SessionSlotRearmTest` | 11 |

`git status --porcelain androidApp/src/test/screenshots/preview` is empty.
