---
title: 'Story 2.10: Session conflict rules end to end'
type: 'feature'
created: '2026-10-06'
status: 'done'
baseline_revision: '887726d'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-2-context.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** Epic 2 built the conflict pieces in separate stories: kill recovery, deadlines, Direct Boot, unlock, merges, calls, lock and volume. Each story tested its own piece. Nothing yet proves that the PRD §6.4 conflict rules hold when those pieces meet in the real wake runtime.

**Approach:** A Robolectric `SessionConflictScenariosTest` runs each conflict as one named scenario. It uses:
- the real `WakeService`, `SessionEngine` and wake runtime;
- the call adapter, on Robolectric's audio manager;
- Room `runtime.db` and `app.db` on the device-protected paths;
- `FakeBilling`, a fake policy that offers a snooze (unavailable before the first unlock, like Epic 4's), `FakeUserLockState`, and fake wall and monotonic clocks.

Billing is not wired until Epic 4, so its results are dispatched the way the adapter will dispatch them. Code is changed only where a scenario fails.

## Boundaries & Constraints

**Always:**
- Every scenario ends by asserting the `session_history` row (outcome, `snooze_count`, `direct_boot`), the `session_merge` rows, and an empty `runtime.db` after `Recorded`.
- A kill or reboot inside a scenario is a new process over the same storage and clocks. A reboot adds a new boot count, a restarted elapsed clock and `BOOT_COMPLETED`. The session slot is delivered through its receiver.
- The real alarm during a test session follows the kept Story 1.18 rule: the test is recorded as Test, and the real alarm starts its own session.

**Never:** No new states, rows, strings or UI. No androidTest; the CI ATD image is not needed.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 1 Payment when the grace window ends | `PayConfirmed` in Grace, grace elapses, then `PurchaseCancelled` | Loud at full set volume while `paying`; Loud keeps ringing | — |
| 2 Grant during a check | Loud at step 2, `PurchaseGranted` | Snoozed, sound off, step 0 with new seeds; at the snooze end Ringing(2); I'm up → Grace | — |
| 3 Overlap | During Loud; during Snoozed | Merged with nothing changed; re-rings now, no fee, I'm up → Loud | — |
| 4 Call in Grace | Call for 2 min | Grace frozen; resumes with 15 s left | — |
| 5 Reboot or death | Phone off through the snooze end; then death while paying | Rings at once; restored with a fresh timer, `paying` cleared, no billing relaunch | — |
| 6 Before the first unlock | System sound while locked | Default sound, BeforeFirstUnlock; the unlock makes snooze Available in place; plan and sound stay for the ring | — |
| Call during payment | Grace + paying + call | Grace and timeout shifted by the call; `paying` kept until the result | — |
| Overlap during a call | Paused ring + alarm B | Merged; still paused until the call ends | — |
| Reboot while paused | Call over by the reboot | Restored ringing, not paused | — |
| Clock +2 h in a snooze | `TIME_SET` at minute 3 | Slot at new wall + 6 min; an early slot is ignored; re-rings 6 monotonic min later | — |
| Test vs real alarm | Real alarm during a test | Test recorded Test, no merge; the real session runs | — |
| Locked ring + overlap | Alarm B while locked | Merged; Direct Boot sound kept; `direct_boot` true | — |

</intent-contract>

## Code Map

- `androidApp/src/test/.../wake/SessionConflictScenariosTest.kt` (new): the 12 scenarios.
- `androidApp/src/test/.../wake/WakeApp.kt`: a `policy` override for `SnoozeAvailabilityPolicy`.
- Already covered elsewhere, not duplicated here:
  - volume keys (`WakeVolumeKeysTest`, `VolumeKeyGateTest`);
  - the session lock (`SessionInProgress` tests of Story 2.6);
  - no-hostage (`NoHostageBackgroundTest`);
  - kill recovery per state (`SessionKillRecoveryTest`).

## Tasks & Acceptance

**Execution:** write the scenarios; fix any code a scenario proves wrong.

**Acceptance Criteria:**
- Given `./gradlew qualityGate`, then it passes, the Kover gates are green, and the preview baselines are unchanged.

## Verification

**Commands:**
- `./gradlew qualityGate`: expected BUILD SUCCESSFUL.

## Spec Change Log

## Review Triage Log

## Auto Run Result

Status: done. Fast mode, one agent, no separate review pass. Branch `story/2-10-session-conflict-rules-end-to-end`, based on `origin/story/2-4-2-7-final-stack` (887726d).

**Summary:** 12 scenarios: the 6 PRD §6.4 rows, the 4 combined conflicts from the AC, and 2 that cross stories (the test alarm vs a real alarm under the Story 1.18 rule, and an overlap during a locked ring). All 12 passed on the first run. No production code needed a fix: the pieces agree.

**Files changed:** `SessionConflictScenariosTest.kt` (new), `WakeApp.kt` (policy override), this spec, `sprint-status.yaml` (2.10 done).

**Verification:** `./gradlew qualityGate` passes (BUILD SUCCESSFUL, 5m 42s). `androidApp:testDebugUnitTest` ran in full: 76 result files, no failures. The Kover gates are green. `git status --porcelain androidApp/src/test/screenshots/preview` is empty.

**Residual risks:** the scenarios dispatch billing results directly, because no billing adapter exists until Epic 4. Real devices are covered by Story 2.13's checklist.
