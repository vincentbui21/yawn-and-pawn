---
title: 'Story 1.13: Record every session in history'
type: 'feature'
created: '2026-10-01'
status: 'done'
baseline_revision: '53d47d4f666044fcd7f15a5eea33026045bc95b1'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
  - '{project-root}/_bmad-output/planning-artifacts/architecture/architecture-pay-per-snooze-2026-09-26/ARCHITECTURE-SPINE.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Sessions end, but nothing records them, so Progress (Epic 6) would have nothing to show. Two effects also overlap today. A one-shot `RecordOutcome` and the entry effect `HistoryWriteRequested` both mean "write the outcome", with no owner, and nothing ever dispatches `Recorded`. Completed and Missed sessions therefore never return to Idle.

**Approach:**
- Add a `session_history` table to `app.db` as the v2 → v3 migration.
- Add a `SessionHistoryRepository` port with a Room adapter and a fake.
- Add a core `SessionRecorder`, the only writer, which the `SessionEngine` drives itself:
  - "record session start" upserts the start row;
  - the idempotent `HistoryWriteRequested` entry effect is the single place where the outcome is written;
  - once it succeeds, the engine applies `Recorded` inside the same lock, which moves to Idle and clears `runtime.db`.
- Drop the duplicate `RecordOutcome` one-shot.
- Record the downgrade policy in `docs/decisions/db-downgrade.md`.

## Boundaries & Constraints

**Always:**
- **The table:** `app.db` version 3 adds `session_history`, the v2 → v3 migration.
  - Columns: `session_id` (primary key), `alarm_id`, `scheduled_at`, `first_ring_at`, `ended_at` (nullable), `snooze_count`, `check_types` (a list, stored as one text column), `time_to_complete_ms` (nullable), `fallback_used`, `direct_boot`, `outcome` (nullable; OnTime / Snoozed / Missed / Skipped / Test, stored as a stable string).
  - All instants are epoch millis.
  - Add `MIGRATION_2_3`, export `data/schemas/.../AppDatabase/3.json`, and keep `1.json` and `2.json`.
  - Migration tests: v2 → v3 keeps the alarms and the request-code mark, and a v1 file migrates through 1 → 2 → 3.
  - No paid amounts are stored here. They come later from purchase records keyed by `session_id` (AD-7, AD-8).
- **The port:** `SessionHistoryRepository` in `core.history` (or `core.session`).
  - It upserts by `session_id`, returning `Outcome`.
  - It has a read for one row by id (tests, and Epic 6 later).
  - `RoomSessionHistoryRepository` is in `:data` and `FakeSessionHistoryRepository` is in `:testing`.
- **The recorder:** `SessionRecorder` in core is the only writer.
  - On `RecordSessionStart` it upserts a row with `alarm_id`, `scheduled_at`, `first_ring_at` = the session's first ring time, `direct_boot` = `beforeFirstUnlock`, and a null outcome.
  - On `HistoryWriteRequested` (Completed or Missed) it upserts the full row:
    - `ended_at` = now (wall);
    - `snooze_count` = `snoozesGranted`;
    - `check_types` = the plan's step types;
    - `time_to_complete_ms` = `ended_at` − `first_ring_at` for Completed, and null for Missed;
    - `fallback_used`, plus the same start fields.
  - The outcome mapping:
    - Test when `config.testMode`, whatever the ending;
    - otherwise OnTime when completed with 0 snoozes;
    - Snoozed when completed with 1 or more;
    - Missed after the timeout.
  - The same write repeated (for example, the process died between the write and `Recorded`) leaves exactly one row with identical values. To make that hold, `ended_at` for a re-run write is the value already stored, if one exists.
- **First ring time:** `SessionData` gains `firstRingAt: Instant?` (default null, so stored v1 JSON still decodes). The session-start rules (`AlarmFired`, `TestAlarmFired`) set it from `now`'s wall time. If it is missing, the recorder falls back to the stored row's value, then to `config.scheduledAt`.
- **The engine:** `SessionEngine` takes the `SessionRecorder` and handles the two history effects itself. It never passes them to the platform `EffectRunner`.
  - After `HistoryWriteRequested` succeeds for Completed or Missed, the engine reduces `Recorded(sessionId)` as a follow-up event in the same lock (like due events). The state becomes Idle, and the commit clears the `active_session` row.
  - If the write fails, the failure is logged, the state stays Completed or Missed, and the next `tick()` or restore retries the write.
  - Restoring a persisted Completed or Missed session writes the row (once), then reaches Idle.
- **Effect cleanup:** remove `SessionEffect.RecordOutcome` and its emission in the Completed and Missed transitions. The AD-2 "record outcome" is the `HistoryWriteRequested` entry effect. Update the Story 1.11 row tests and fixtures. `RecordSessionStart` stays a one-shot effect.
- **Writer scan:** a unit test scans the `:core` and `:data` main sources. It fails if anything other than `RoomSessionHistoryRepository` references the session history DAO's write methods, or if anything other than `SessionRecorder` calls the repository's upsert.
- **Koin and coverage:** `:androidApp` wires the repository and the recorder into the engine. `koverVerify` and `koverVerifySession` stay green.
- **Downgrade note:** `docs/decisions/db-downgrade.md` states the policy for restoring a newer-schema `app.db` onto an older install. Proposed: the restore of an `app.db` whose version is above the installed schema is skipped and logged. It also explains why: with no destructive fallback, a newer file cannot be opened. Implementation is carried to Story 2.12 (backup and restore), which is also recorded in `deferred-work.md`.

**Never:**
- No UI, no stats (Epic 6), no purchase amounts, and no `Skipped` producer (Epic 7 skip).
- No destructive migration. No other writer of `session_history`. Never dispatch to the engine from inside an effect.
- No `println` and no `Clock.System` in core.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Start | Idle + AlarmFired at 07:00:05 for scheduled 07:00 | Row with first_ring_at 07:00:05, scheduled_at 07:00, alarm_id, direct_boot, null outcome | No error expected |
| On time | Completed, 0 snoozes, 07:03 | Outcome OnTime, ended_at 07:03, time_to_complete 175 s; Recorded → Idle, active_session cleared | No error expected |
| Snoozed | Completed after 2 grants | Outcome Snoozed, snooze_count 2 | No error expected |
| Missed | NoInteractionTimeout → Missed | Outcome Missed, time_to_complete null | No error expected |
| Test | Test session completed | Outcome Test | No error expected |
| Replay | Write succeeds, "crash" before Recorded, restore | Exactly one row with identical values; ends Idle | No error expected |
| Write fails | Repository failing at Completed | State stays Completed, logged; the next tick retries and reaches Idle | Logged, never thrown |
| Migration | v2 DB with alarms and mark 1005 | v3 has the alarms and the mark, and an empty session_history | No destructive fallback |

</intent-contract>

## Code Map

- `core/src/commonMain/kotlin/com/yawnandpawn/app/core/session/SessionEffect.kt` -- `RecordSessionStart` (line 45), `RecordOutcome` (line 51, to remove), `RecordMergedOccurrence` (line 57, kept for Epic 6/Day detail and handled as a log-only effect for now), `EntryEffect.HistoryWriteRequested` (line 214).
- `core/.../session/CheckRules.kt:72`, `RingRules.kt:132` -- emit `RecordOutcome` today; remove it. `IdleRules.kt:58` emits `RecordSessionStart`; also set `firstRingAt`. `SessionRuntime.kt:33` emits `HistoryWriteRequested` for Completed and Missed.
- `core/.../session/SessionState.kt` -- `SessionData`: add `firstRingAt`. `SessionEvent.kt:93` has `Recorded(sessionId)`. `SessionJson.kt` and the v1 fixtures in `SessionJsonTest` must still decode (default null).
- `core/.../session/SessionEngine.kt` -- internal constructor (line 37) and the step, due-loop and restore paths. Add the recorder and the `Recorded` follow-up, and route the two history effects to the recorder.
- `core/src/commonTest/.../session/SessionTransitionTable.kt`, `SessionEngineTest.kt`, `SessionEngineTestDoubles.kt` -- update the expected effects (no `RecordOutcome`), add the recorder doubles and the engine history tests.
- `data/src/commonMain/kotlin/com/yawnandpawn/app/data/db/AppDatabase.kt` (version 2: entities `AlarmEntity`, `RequestCodeSequenceEntity`) and `AppDatabaseMigrations.kt` (`MIGRATION_1_2`) -- add the entity, DAO and `MIGRATION_2_3`. The factory in `data/src/androidMain/.../db/AppDatabaseFactory.kt` registers the migrations. `DataModule.kt` adds the binding.
- `data/src/androidHostTest/.../db/AppDatabaseFactoryTest.kt` -- currently asserts schema v2. Update it, and add the migration tests (follow the v1 hand-built-file pattern from Story 1.10).
- `testing/src/commonMain/kotlin/com/yawnandpawn/app/testing/SessionEngineFakes.kt` -- the fakes, plus `aSessionConfig` and `aSession`. Add `FakeSessionHistoryRepository`, and update the engine full-morning tests to expect the history row.
- `androidApp/src/main/kotlin/com/yawnandpawn/app/YawnAndPawnApp.kt` -- engine wiring. `androidApp/src/test/.../SessionWiringTest.kt` asserts bindings.

## Tasks & Acceptance

**Execution:**
- `core/.../history/SessionHistory.kt` (row model, `SessionOutcome`, port) and `core/.../session/SessionRecorder.kt`.
- Engine routing and the `Recorded` follow-up, plus `firstRingAt`, and the removal of `RecordOutcome`.
- `data/...` -- `SessionHistoryEntity`, the DAO, `RoomSessionHistoryRepository`, `MIGRATION_2_3`, the binding, and `3.json`.
- `testing/...` -- `FakeSessionHistoryRepository` with tests.
- `androidApp/...` -- the wiring.
- `docs/decisions/db-downgrade.md`, plus a `deferred-work.md` entry for Story 2.12.
- Tests:
  - every matrix row (recorder and engine);
  - outcome mapping;
  - idempotent replay;
  - the migration tests;
  - the writer-scan test;
  - Room repository round-trip;
  - the updated Story 1.11 and 1.12 tests.

**Acceptance Criteria:**
- Given a full morning through the engine with fakes (AlarmFired … check completed), when it ends, then exactly one history row exists with the right outcome and fields, the engine is Idle, and the active-session store is empty.
- Given `./gradlew qualityGate`, when it runs, then it passes with `3.json` committed and both Kover rules green.

## Spec Change Log

## Review Triage Log

### 2026-10-01 — Review pass
- verdicts: 33 findings — high 1, medium 4, low 21, false 7, maybe-false 0
- findings:
  - (verification-gap, other) `[medium]` `[patch]` `ended_at` and `time_to_complete_ms` record when the write succeeded, not when the session ended (a retry or a restore after a crash inflates them) — the reducer now stores the end snapshot on Completed and Missed, and the recorder uses it; tests added.
  - (verification-gap, other) `[low]` `[patch]` The writer scan covers only `:core` and `:data` — it now also scans `:androidApp` and `:composeApp`, with path-based exemptions.
  - (blind) `[medium]` `[patch]` `ended_at` is the write time — grouped with the end-snapshot patch.
  - (blind) `[low]` `[patch]` `direct_boot` falls back to `beforeFirstUnlock`, which UserUnlocked clears — `startedBeforeUnlock` is set at the start and used instead; test added.
  - (blind) `[medium]` `[patch]` `time_to_complete_ms` is wall minus wall (a clock change distorts it) — computed from monotonic elapsed on the same boot (first-ring and end snapshots), wall otherwise; test added.
  - (blind) `[low]` `[patch]` The tick retry re-applies every entry effect of the ended state — it now calls the end write directly.
  - (blind) `[low]` `[patch]` `recordStart` ignores the effect's payload and drops silently — sessionId checked, the drop logged.
  - (blind) `[low]` `[patch]` `recordStart` blindly upserts a null outcome — it merges with an existing row and keeps a stored outcome and `ended_at`; test added.
  - (blind) `[low]` `[patch]` The writer scan is weak in scope and method — grouped with the scan patch (more modules, path-based exemptions, receiver-specific upsert match).
  - (blind) `[low]` `[patch]` The no-comma rule for `CheckStep.typeName` is only a comment — `require` in the mapping plus a test over every subtype.
  - (blind) `[low]` `[defer]` RecordMergedOccurrence never reaches history — recorded in deferred-work.md for Story 2.9 (Epic 6 Day detail consumes it).
  - (blind) `[low]` `[patch]` The downgrade note says "proposed" while deferred-work says resolved, and ignores a newly set-up phone — the note is stated as accepted (Story 2.12 implements it) and names the trade-off, with a required user notice.
  - (blind) `[low]` `[patch]` `session_history` has no index for Progress queries — index on `scheduled_at` added in the unreleased `MIGRATION_2_3`; `3.json` regenerated.
  - (blind) `[low]` `[reject]` Two in-memory history repositories exist — the established pattern here: `:core` tests cannot depend on `:testing` (AD-1).
  - (blind) `[low]` `[reject]` `SessionEvent.Recorded` is still public — an outside Recorded is guarded and only retries the write; making it internal would split the sealed event set across modules.
  - (blind) `[low]` `[reject]` `SessionHistoryRow` doesn't enforce its own invariants — only `SessionRecorder` builds rows (enforced by the scan), and it always pairs outcome with `ended_at`.
  - (blind) `[low]` `[reject]` A Test row can't tell a completed test from a missed one — Test rows are excluded from every stat (FR-PRG-1); a null `time_to_complete` marks a missed test.
  - (edge-case) `[medium]` `[patch]` End write applied late inflates the duration — grouped with the end-snapshot patch.
  - (edge-case) `[high]` `[patch]` A persistently failing history write keeps the engine in Completed or Missed, so the next AlarmFired is ignored and the next alarm never rings — on a new alarm the write is retried once, then abandoned with a log, Recorded is reduced and the new alarm rings; tests added.
  - (edge-case) `[low]` `[patch]` Start write failed, then unlock → wrong `direct_boot` — grouped with the startedBeforeUnlock patch.
  - (edge-case) `[low]` `[patch]` The scan misses `:androidApp` writers — grouped with the scan patch.
  - (edge-case) `[low]` `[patch]` Bare file-name exemptions in the scan — grouped with the scan patch.
  - (edge-case) `[low]` `[patch]` A comma or empty check type corrupts `check_types` — grouped with the require patch.
  - (edge-case, claim) `[low]` `[patch]` The single-writer claim isn't enforced for the app module — grouped with the scan patch.
  - (intent) `[false]` `[reject]` "SessionRecorder dispatches Recorded" vs the engine reducing it — matches the epics AC ("the engine then dispatches Recorded"); a recorder dispatching from inside an effect would deadlock the engine (Story 1.12 contract).
  - (intent) `[false]` `[reject]` Resolving the duplicate effect changes the Story 1.11 and 1.12 contracts — that change is what the deferred item asked for; the reasoning is recorded in the spec.
  - (intent) `[false]` `[reject]` Production can't reach the start and end writes yet — the AlarmFired dispatch into the engine is Story 1.14; the restore path is covered at app level.
  - (intent) `[false]` `[reject]` The core matrix runs on a core double, not `FakeSessionHistoryRepository` — AD-1 constraint; the fake drives the `:testing` full morning.
  - (intent) `[low]` `[reject]` The replay is a simulated failure, not real process death over Room — the engine logic is store-agnostic; Room's replace-on-upsert is tested in the repository test.
  - (intent) `[low]` `[patch]` Start-row fields asserted only for AlarmFired — TestAlarmFired assertions added.
  - (intent) `[false]` `[reject]` The writer exclusivity scan is narrower than "the whole app" — now widened (scan patch); raw SQL writers don't exist (Room-only rule, AD-6).
  - (intent) `[false]` `[reject]` Only the downgrade document is delivered — the AC asks the doc to state the policy; implementation is carried to Story 2.12.
  - (intent) `[false]` `[reject]` Delivery steps not in the diff — they run after review.

## Design Notes

- **check_types storage:** a comma-separated list of stable step-type names (for example `Placeholder`) through a Room `TypeConverter`, or a plain mapping in the adapter. An empty list is an empty string.
- **Recorded follow-up:** treat it like a due event, inside the bounded follow-up loop, so one mutex hold covers write → Recorded → Idle commit.
- **Environment (company PC):** export `JAVA_HOME=C:/Users/BuiTua/AppData/Local/Programs/jdk17/jdk-17.0.20.1+1`. Never use the owner's phone.

## Verification

**Commands:**
- `./gradlew qualityGate` -- expected: BUILD SUCCESSFUL, with both Kover rules green and the schemas exported.
- `git status --porcelain androidApp/src/test/screenshots` -- expected: empty.

## Auto Run Result

**Summary:** history is now recorded exactly once per session.
- **Table:** `app.db` v3 adds `session_history` with an index on `scheduled_at` (the v2→v3 migration; a v1 file migrates through 1→2→3).
- **Writer:** `SessionRecorder` in core is the only writer, through the `SessionHistoryRepository` port, with a Room adapter and a fake. A writer scan across `:core`, `:data`, `:androidApp` and `:composeApp` enforces this.
- **Engine:** it drives history itself. `RecordSessionStart` writes the start row, merging with any existing row. The idempotent `HistoryWriteRequested` entry effect writes the end row. On success the engine reduces `Recorded` in the same lock, so the session goes Idle and the runtime row is cleared.
- **Removed:** the duplicate `RecordOutcome` one-shot effect.
- **Accuracy:** `SessionData` keeps the first-ring and end snapshots plus `startedBeforeUnlock`, so `ended_at`, `time_to_complete` (monotonic on the same boot) and `direct_boot` stay correct after a retry or a crash.
- **Never blocks an alarm:** a persistently failing history write never blocks a new alarm. It is retried once, then abandoned with a log.
- **Downgrade:** the policy is accepted in `docs/decisions/db-downgrade.md`, with implementation in Story 2.12.

**Files changed:**
- `core/.../history/SessionHistory.kt`, `core/.../session/SessionRecorder.kt`, `EngineHistory.kt`, plus the engine, rules, `SessionData` and effects (`RecordOutcome` removed).
- `data/.../history/*`, `AppDatabase` v3, `MIGRATION_2_3`, `3.json` and the `DataModule` binding.
- `testing/.../FakeSessionHistoryRepository.kt`.
- The `YawnAndPawnApp` wiring.
- `docs/decisions/db-downgrade.md`.
- `deferred-work.md`: resolved items, plus new ones for Stories 2.12, 2.9 and 1.14.
- Tests in core, data and androidApp.

**Review findings breakdown:** 33 findings: 1 high, 4 medium, 21 low, 7 false.
- **Patched:**
  - High: a failing write blocks the next alarm.
  - Medium: the real end time, monotonic duration, and the writer-scan scope.
  - Low: `direct_boot`, the tick retry calling only the write, start-write merge and logging, the check-type name rule, the index, the downgrade note, and the TestAlarmFired start-row test.
- **Deferred:** merged occurrences to Story 2.9, and the storage-broken "never silent" case to Story 1.14.
- **Rejected:** the rest, with reasons in the triage log.

**Follow-up review recommendation:** `true`. A high and several medium entries were patched on the first pass. The unverified risk is the abandon-on-new-alarm path and the new snapshot fields; they have had no second review. The engine is also at detekt's 11-function limit.

**Verification:**
- `./gradlew qualityGate --rerun-tasks`: BUILD SUCCESSFUL (5 min 42 s).
- No screenshots changed.

**Residual risks:**
- In production nothing dispatches AlarmFired into the engine until Story 1.14. Only the restore path is live.
- If `runtime.db` cannot be written, a new session cannot start (deferred to Story 1.14).
