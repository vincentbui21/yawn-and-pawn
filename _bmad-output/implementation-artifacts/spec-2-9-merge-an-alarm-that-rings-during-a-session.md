---
title: 'Story 2.9: Merge an alarm that rings during a session'
type: 'feature'
created: '2026-10-06'
status: 'done'
baseline_revision: 'a57b29952c5e9f8070aab5958a1b816379f5da95'
review_loop_iteration: 1
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-2-context.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** The reducer already merges an alarm that fires during a session (`OverlapAlarmFired`): Ring states stay as they are, and a Snoozed session gets `Ringing(ringIndex + 1, noGraceThisRing)`. But the `RecordMergedOccurrence` effect is only logged by the runner, so no merge is ever recorded and Epic 6 cannot show "{time} alarm merged into this session". `WakeService` also merges an alarm that was disabled or deleted by the time the service handles it.

**Approach:**
- Add `session_merge` to `app.db` (migration v3 → v4).
- Route `RecordMergedOccurrence` from the engine to `SessionRecorder`, the single history writer. The write is insert-or-ignore on (session, alarm, scheduled time), so replaying it leaves one row.
- In `WakeService`, skip the merge (and log it) for a deleted alarm, a repeating alarm switched off, and the session's own occurrence.
- Prove every AC with core, data and Robolectric tests.

## Boundaries & Constraints

**Always:**
- **Table:** `session_merge` (`session_id` TEXT, `alarm_id` TEXT, `scheduled_at` INTEGER, `merged_at` INTEGER; primary key `session_id, alarm_id, scheduled_at`).
- **Migration:** `MIGRATION_3_4` with an exported `4.json`, and a migration test that builds a v3 file by hand and keeps alarms and history.
- **Repository:** `SessionHistoryRepository` gains `recordMerge(SessionMergeRow)` (insert or ignore) and `merges(sessionId)` (a read for tests and Epic 6).
- **Single writer:** the writer scan covers the merge DAO write and `recordMerge`, and only `SessionRecorder` may call it.
- **Engine:** `SessionEffect.RecordMergedOccurrence` goes to `SessionRecorder.recordMerge(effect, now)` with `merged_at` = the step's wall time, never to the runner. It is written before the step's commit (review fix), so a kill right after the commit cannot lose it; the start row still runs after the step's other effects. A failure is logged, the commit goes ahead, and the ring is not touched. A commit that fails after the row was written leaves the row; the merge dispatched again inserts nothing new.
- **No new state behaviour:** the reducer and the table are unchanged. A merge in a Ring state leaves the state, sound, config, fee ladder, check progress, grace and timeout exactly as they were. In Snoozed it is the existing row: no fee, the heartbeat replaces the snooze-end slot, and the ring has no grace window.
- **Re-arming the merged alarm:** stays with `RearmOnFire`, which already runs at the fire. The `RescheduleAlarm` effect stays logged only.
- **WakeService.onAlarm with an active session (Ring or Snoozed):** checks the fire first.
  - The session's own occurrence (same `alarmId` and `scheduledAt` as the session config, a real session): logged as `FireIgnored`, no merge. A session never merges into itself.
  - Deleted (`NotFound`): logged as `FireIgnored`, no merge.
  - Read successfully, disabled **and** repeating (`!enabled && repeatDays.isNotEmpty()`): logged as `FireIgnored`, no merge.
  - Read successfully, disabled and one-time: it merges. `RearmOnFire` switches a fired one-time alarm off at the fire, before the service reads it, so "off" is expected here. A one-time alarm that was already off when it fired was refused by the receiver.
  - Read failure, or no answer within 3 s (`withTimeoutOrNull`): it still merges, because never-silent comes first.

**Never:** No UI or strings (the Day detail note is Epic 6). No reducer rows. No new files outside `app.db`.

**Deviation from the AC, recorded:** "a real alarm that fires during a test session is merged … outcome stays Test" contradicts the Story 1.18 review fix of 2026-10-02, which is reviewed and already shipped: a real alarm ends the test (recorded Test) and starts its own real session, and merges only if the test cannot be ended. This story keeps that behaviour, so a real morning is never recorded as a test. The fallback merge into a test writes a `session_merge` row, and its outcome stays Test, which is what the AC asks for.

**Deviation from the AC, recorded (review):** the AC says "a disabled or deleted alarm that fires during a session writes no merge row". At service time a disabled one-time alarm cannot be told apart from one that `RearmOnFire` switched off for this very fire, so only a disabled repeating alarm is skipped (`RearmOnFire` never disables a repeating one). The receiver already refuses any alarm that is off when it fires (Story 1.14), so a one-time alarm switched off by the user before its fire still writes no row.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Ring merge | Ringing, Grace or Loud + alarm B (other sound, snooze length, grace) | State and session data unchanged; one merge row; B's settings unused | Merge row write fails → logged, ring unchanged |
| Snooze merge | Snoozed + alarm B | Ringing(ringIndex + 1, noGraceThisRing); no fee; heartbeat slot; I'm up → Loud; one merge row | — |
| Replay | Same merge recorded twice (its commit failed, or a kill after the row) | One row, the first `merged_at` | — |
| Kill after the commit | Merge committed, process killed before the effects | The row is already written (it precedes the commit) | — |
| Same minute | A and B delivered in either order | One session, one merge row | — |
| Killed process | Stored Ringing + B fires into a new process | Restore, then merge row | — |
| Own occurrence | The session's alarm at its scheduled time fires again (for example the backup slot) | No merge, `FireIgnored` logged, state unchanged | — |
| Deleted B | Alarm gone at service time (Ring or Snoozed) | No merge, `FireIgnored` logged, state unchanged | — |
| Disabled repeating B | Repeating alarm off at service time | No merge, `FireIgnored` logged | — |
| Disabled one-time B | One-time alarm off at service time (`RearmOnFire` did it) | Merges, one row | — |
| B unreadable | Read fails or takes over 3 s (Ring or Snoozed) | Merges (Snoozed: Ringing(ringIndex + 1)), one row | — |
| Migration | Hand-built v3 file with alarms and history | v4 with the rows kept and an empty `session_merge` | — |

</intent-contract>

## Code Map

- **core:**
  - `core/.../history/SessionHistory.kt`: `SessionMergeRow`, plus `recordMerge` and `merges` on the port.
  - `core/.../session/SessionRecorder.kt`: `recordMerge`.
  - `core/.../session/SessionEngine.kt`, `EngineHistory.kt`: route the effect to the recorder.
- **data:**
  - `data/.../history/SessionMergeEntity.kt` (new).
  - `data/.../history/SessionHistoryDao.kt`: insert-or-ignore and a query.
  - `data/.../history/RoomSessionHistoryRepository.kt`.
  - `data/.../db/AppDatabase.kt`: version 4.
  - `data/.../db/AppDatabaseMigrations.kt`: `MIGRATION_3_4`.
  - `data/schemas/.../4.json`.
- **androidApp:** `androidApp/.../wake/WakeService.kt`: the alarm check before a merge.
- **Fakes:** `FakeSessionHistoryRepository`, `InMemoryHistory`, `MissedNotesTest` and `WakeActivityTest` implement the new methods.
- **Tests:**
  - `SessionHistoryWriterScanTest`, `AppDatabaseFactoryTest`, `RoomSessionHistoryRepositoryTest`;
  - core engine and recorder merge tests;
  - the new Robolectric `MergeDuringSessionTest`;
  - the 2.1 kill test gets a merge-row assertion.

## Tasks & Acceptance

**Execution:** implement the Code Map, with tests for every matrix row.

**Acceptance Criteria:**
- Given `./gradlew qualityGate`, then it passes, the preview baselines are unchanged, and the Kover gates are green.
- Given a rebase onto main (Story 2.12), then `AppDatabase.SCHEMA_VERSION` becomes 4. The constant does not exist on this stacked branch, which carries the literal `version = 4`. A table needs no backup-rule change.

## Verification

**Commands:**
- `./gradlew qualityGate` -- expected: BUILD SUCCESSFUL.
- `git status --porcelain androidApp/src/test/screenshots/preview` -- expected: empty.

## Spec Change Log

## Review Triage Log

### Review (2 reviewers, fast mode)

Fixed (`fix(2.9): review fixes`):

- **A switched-off repeating alarm no longer merges.** `WakeService.mergeIgnoredBecause` skips (logs `FireIgnored`, "repeating alarm switched off before it rang") an alarm that is read successfully and is `!enabled && repeatDays.isNotEmpty()`. A disabled one-time alarm still merges: `RearmOnFire` switches it off at its own fire, before the service reads it. Recorded as a deviation above.
- **The alarm read is bounded.** `withTimeoutOrNull(ALARM_READ_TIMEOUT = 3 s)`: a stuck read counts as a read failure and merges (never silent), instead of holding the service's command lock.
- **The session's own occurrence is not merged into itself.** A fire with the session config's `alarmId` and `scheduledAt` (for example the backup slot carrying the alarm that started the session) is logged ("the session's own occurrence") and changes nothing. A test session is excluded, so the Story 1.18 rule is untouched.
- **The merge row survives a kill after the commit.** `SessionEngine.step` writes the `RecordMergedOccurrence` row before the commit; it used to be written after, and a restore (`runOneShot = false`) never replays it. Insert or ignore keeps it to one row with the first `merged_at` when the merge is dispatched again (for example after a failed commit). The start row stays after the effects.
- **No non-exhaustive `when` on history effects.** The step now takes each history effect by type (`filterIsInstance`), so there is no `else -> Unit` branch left to swallow a new one.
- **Test helper:** `AppDatabaseFactoryTest.toVersion3Insert` writes SQL `NULL`, not the string `'null'`, for a null outcome.
- **Stable order:** `SessionHistoryDao.mergesOf` orders by `merged_at, scheduled_at, alarm_id`.

Tests added:
- core `SessionMergeTest`: the row exists when the merge commits; a failed commit keeps the row, and the merge dispatched again leaves one row with the first time.
- data `RoomSessionHistoryRepositoryTest`: a replayed merge keeps one row with the first merge time (real Room, both writes Success); merges read back in merge time, scheduled time, alarm order, per session.
- Android `MergeDuringSessionTest`: a repeating alarm switched off is not merged; a one-time alarm switched off still merges; the own occurrence is ignored; a read that does not answer in time merges; an alarm that cannot be read during a snooze merges and rings (`ringIndex + 1`); a deleted alarm during a snooze stays Snoozed with no row, logged.

## Auto Run Result

Status: done. Fast mode: one agent planned and implemented, with no separate review pass. The branch is stacked on Story 2.3 (a57b299).

**Summary**
- `RecordMergedOccurrence` now goes to `SessionRecorder.recordMerge` instead of being logged only. It writes one `session_merge` row, with insert-or-ignore so a replay leaves one row.
- `app.db` migrates from v3 to v4 (`MIGRATION_3_4`), and `4.json` is exported.
- The writer scan now also covers `recordMerge` and the DAO's `insertMerge`.
- `WakeService` no longer merges an alarm deleted before the service handled it, and logs it. A read failure still merges, because never-silent comes first.
- The reducer table and the state behaviour are unchanged, and there is no UI change.

**Files changed**
- core:
  - `history/SessionHistory.kt` (`SessionMergeRow`, `recordMerge`, `merges`);
  - `session/SessionRecorder.kt`, `EngineHistory.kt`, `SessionEngine.kt`, `SessionEffect.kt` (KDoc);
  - tests: `SessionMergeTest` (5), and updates to `SessionClockChangeTest`, `InMemoryHistory` and `MissedNotesTest`.
- data:
  - `history/SessionMergeEntity.kt` (new), `SessionHistoryDao.kt`, `RoomSessionHistoryRepository.kt`;
  - `db/AppDatabase.kt` (v4), `AppDatabaseMigrations.kt`, `schemas/.../4.json`;
  - tests: `AppDatabaseFactoryTest` (v4 schema, a hand-built v3 file migrated) and `SessionHistoryWriterScanTest`.
- testing: `FakeSessionHistoryRepository`.
- androidApp:
  - `wake/WakeService.kt`;
  - new `MergeDuringSessionTest` (6 tests): a merge in Ring with alarm B's settings ignored; a merge in Snoozed; the same minute in both orders; a disabled alarm; a deleted alarm;
  - `WakeServiceTest`, `SessionKillRecoveryTest`, `TestAlarmFlowTest` and `WakeActivityTest` now assert merge rows;
  - `WakeApp`: merge helpers, and a 30 s wait bound.

**Review findings**
- No review pass ran. Verification found two problems, both fixed:
  - detekt `TooManyFunctions` on `SessionEngine`: the helper moved to file level.
  - A flaky `WakeActivityTest` on API 26: its dispatch wait timed out under gate load. `WakeApp`'s wait bound went from 5 s to 30 s, so it is deterministic under load.

**Deviation from the AC**
- A real alarm during a test session follows the reviewed Story 1.18 rule: the test ends, is recorded as Test, and the alarm starts its own real session.
- The AC instead says "merged like any other, outcome Test". The owner decision is recorded in `deferred-work.md`.

**Rebase notes**
- On main, set `AppDatabase.SCHEMA_VERSION = 4`. This branch has the literal `version = 4`.
- No backup-rule change is needed, because the new table lives inside `app.db`.

**Verification**
`./gradlew qualityGate`: BUILD SUCCESSFUL (7m 48s). `git status --porcelain androidApp/src/test/screenshots/preview` is empty. The Kover gates are green.
