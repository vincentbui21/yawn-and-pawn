---
title: 'Story 2.2: Session deadlines survive clock changes and reboots'
type: 'feature'
created: '2026-10-05'
status: 'done'
baseline_revision: 'a73466fcfb323bf68d4a2d972dcbef86e0e1410d'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-2-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-2-1-keep-the-backup-alarm-armed-and-recover-after-a-kill.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** Session timers already count monotonic time, but nothing proves a clock or zone change leaves a session intact, and three gaps remain:
- **No BOOT_COUNT:** on such a device the boot counter is derived from the wall clock. A clock change then looks like a reboot, so a session deadline falls back to wall time and can be shortened.
- **Locked boot:** the receiver does not take `LOCKED_BOOT_COMPLETED`, so after a reboot a session waits for the first unlock.
- **Snooze ended during the phone being off:** the slot is armed at a past wall time instead of "now + 1 s".

**Approach:** Tighten "same boot" in `Deadline`: the boot count must match and elapsed time must not have gone back below the deadline's creation. The boot counter becomes a constant on devices without `BOOT_COUNT`. `SystemEventsReceiver` also listens to `LOCKED_BOOT_COMPLETED`, and `SessionSlotRearm` arms a passed snooze end at once. Prove clock jumps, zone changes, reboots and app updates with core and Robolectric tests.

## Boundaries & Constraints

**Always:**
- **Deadline:** `Deadline` gains `createdElapsedMillis` (default 0; `after()` sets it). `sameBoot(now)` is `bootCount == now.bootCount && now.elapsedMillis >= createdElapsedMillis`. `remaining`, `nextTickIn`, the Snoozed `SlotFired` row, `durationBetween` (via `Deadline.sameBoot(from, to)`) and `AndroidAlarmScheduler.wallMillisOf` all use it.
- **Old rows:** a v1 row without the field decodes as 0, which keeps the Story 1.6 rule unchanged for stored sessions and for normal devices.
- **Boot counter:** `AndroidBootCounter` returns `MISSING_BOOT_COUNT` (-1) when `BOOT_COUNT` is missing, never a wall-derived identity.
- **SessionSlotRearm.afterSystemEvent:** Ringing, Grace and Loud get now + 1 s. Snoozed gets its stored snooze end, or now + 1 s once that has passed. The adapter converts the deadline (same boot: new wall time + monotonic left; another boot: its wall time).
- **SystemEventsReceiver:** handles `LOCKED_BOOT_COMPLETED` too (manifest filter and `ACTIONS`).
- **Unchanged:** the reducer table, the frozen `SessionConfig`, setAlarmClock-only scheduling, device-protected storage, and the rule never to start a service from a receiver.

**Never:** No reducer row changes. No clock-trust heuristics (the decision is recorded in `docs/decisions/reboot-clock.md`). No UI change. No new storage files.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Ring + wall ±2 h | Ringing or Loud | Still ringing; Missed exactly 30 monotonic min after the last interaction | — |
| Grace + wall ±2 h | Grace | Loud after the same 20 monotonic s | — |
| Snooze + ±1 h at min 3 | 9-min snooze | An early slot is ignored; re-rings 6 monotonic min later; slot = new wall + 6 min | — |
| Merge after jump | Ringing + OverlapAlarmFired | Same session, merge effects | — |
| TIMEZONE_CHANGED | Ringing stored | Slot now + 1 s; row unchanged | — |
| Reboot, snooze ahead | Other boot | Slot at snooze-end wall time | — |
| Reboot, snooze passed | Off 3 h | Slot now + 1 s; restore gives Ringing(ringIndex + 1) | — |
| Reboot 10 h, ringing | LOCKED_BOOT_COMPLETED | Slot now + 1 s; restored ring with a fresh 30 min deadline, playing | — |
| No BOOT_COUNT | Same count; elapsed lower than at creation | Treated as a reboot (wall time) | — |
| App update | MY_PACKAGE_REPLACED, Ringing | Slot now + 1 s; restored on the same step with the same counts | — |

</intent-contract>

## Code Map

- `core/.../time/Deadline.kt`: `createdElapsedMillis`, `sameBoot`, `Deadline.sameBoot(from, to)`.
- `core/.../session/SessionRuntime.kt`, `SessionTimers.kt`, `SnoozedRules.kt`: use `sameBoot`.
- `core/.../session/SessionSlotRearm.kt`: a passed snooze end gives now + 1 s.
- `androidApp/.../android/AndroidTimeAdapters.kt`: constant boot count when missing.
- `androidApp/.../android/AndroidAlarmScheduler.kt`: `sameBoot`.
- `androidApp/.../android/SystemEventsReceiver.kt`, `AndroidManifest.xml`: `LOCKED_BOOT_COMPLETED`.
- `docs/decisions/reboot-clock.md`: the Story 2.2 decision on a wrong clock after a reboot.

## Tasks & Acceptance

**Execution:**
- Core:
  - the Deadline change and its callers;
  - `DeadlineTest` (creation elapsed, same count with elapsed going back, old deadlines);
  - `SessionJsonTest` (v1 deadlines decode with 0);
  - the new `SessionClockChangeTest` (matrix rows 1 to 4, 6, 7 and 9 at engine level);
  - `EngineTime.jumpWall` and `reboot`.
- Android:
  - the boot counter, the scheduler, the receiver and the manifest;
  - the new `SessionClockAndRebootTest` (TIME_SET ±1 h, TIMEZONE_CHANGED, BOOT_COMPLETED ahead and passed, LOCKED_BOOT_COMPLETED 10 h, MY_PACKAGE_REPLACED);
  - the `ReceiversManifestTest` and `AndroidTimeAdaptersTest` updates.

**Acceptance Criteria:**
- Given any matrix row, then its test passes in `./gradlew qualityGate`, the Kover gates stay green, and the preview baselines do not change.

## Verification

**Commands:**
- `./gradlew qualityGate`: expected BUILD SUCCESSFUL.
- `git status --porcelain androidApp/src/test/screenshots/preview`: expected empty.

## Spec Change Log

## Review Triage Log

## Auto Run Result

Status: done. This was fast mode: one agent planned and implemented, with no separate review pass. The branch is stacked on Story 2.1 (a73466f).

**Summary**
- `Deadline` records the elapsed time it was made at. "Same boot" now also requires that the elapsed clock has not gone back.
- On a device without `BOOT_COUNT`, the boot counter is a constant. A wall-clock change can no longer make a deadline fall back to wall time.
- Session slot re-arming:
  - `LOCKED_BOOT_COMPLETED` re-arms the slot.
  - A snooze end that passed while the phone was off arms the slot at now + 1 s.
  - A clock or zone change re-arms a snooze's slot from its stored deadline: new wall time plus the monotonic time left.
- The reducer table is unchanged. Old stored rows decode as before (`createdElapsedMillis` = 0).

**Files changed**
- Core:
  - `time/Deadline.kt`;
  - `session/SessionRuntime.kt`, `SessionTimers.kt`, `SnoozedRules.kt`, `SessionSlotRearm.kt`;
  - tests: `DeadlineTest` and `SessionJsonTest` (updated), `EngineTime` (`jumpWall`, `reboot`), and the new `SessionClockChangeTest` (10 tests).
- Android:
  - `AndroidTimeAdapters.kt` (boot counter), `AndroidAlarmScheduler.kt`, `SystemEventsReceiver.kt`, `AndroidManifest.xml`;
  - tests: the new `SessionClockAndRebootTest` (6 tests), plus updates to `ReceiversManifestTest` and `AndroidTimeAdaptersTest`.
- Docs:
  - `docs/decisions/reboot-clock.md`: the Story 2.2 decision, which adds no clock-trust check;
  - `deferred-work.md`: the 2.2 item marked resolved, and a new residual for devices without `BOOT_COUNT`.

**Review findings**
- No review pass ran.
- During verification, the detekt rule `NoInexactAlarm` flagged the `FakeClock.set` and `FakeMonotonicClock.set` calls in the new test. They were replaced by `advanceBy` and `reboot`.

**Verification**
- `./gradlew qualityGate`: BUILD SUCCESSFUL (14m 33s).
- `git status --porcelain androidApp/src/test/screenshots/preview`: empty.

**Residual risks**
- On a device without `BOOT_COUNT`, a restore at a higher uptime than the snooze was granted at reads the snooze end on the new boot's clock. That snooze can end up to one snooze length late. This is recorded in `deferred-work.md`.
- After a reboot, a wrong wall clock moves only a snooze end. A restored ring rings at once whatever the clock says. See `docs/decisions/reboot-clock.md`.
