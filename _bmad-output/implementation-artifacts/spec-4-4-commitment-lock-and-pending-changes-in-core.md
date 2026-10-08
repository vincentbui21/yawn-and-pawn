---
title: 'Story 4.4: Commitment lock and pending changes in core'
type: 'feature'
created: '2026-10-08'
status: 'review'
baseline_revision: '64d9e3c'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-4-context.md'
  - '{project-root}/_bmad-output/planning-artifacts/epics.md'
  - '{project-root}/docs/architecture.md'
  - '{project-root}/docs/prd.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** PRD §6.2 says that within 8 h of an enabled alarm, a "weakening" change (lower base fee, more snoozes, a
longer grace window, easier checks) is saved but only takes effect after that alarm, while strengthening changes apply
at once (AD-16, FR-SET-1, FR-ALM-2). Nothing implements that yet. Also, nothing stores the global settings:
`WakeService` resolves every session with `GlobalSettings()`, so a user's base fee could never reach a session
(Epic 4 context finding).

**Approach:**
- **New package `core.config`** (pure rules + use cases, behind ports):
  - `LockWindow`: an enabled alarm is in the window when 0 < (`nextOccurrence` − now) ≤ 8 h, on instants. For an alarm
    setting only that alarm counts. For a global setting, any enabled alarm counts, and `effectiveAfter` is the latest
    in-window occurrence (`Occurrence(alarmId, scheduledAt)`; ties broken by alarm id).
  - `classify(old, new)`: `Weakening` / `Strengthening` / `NoChange` per field (`LockedField`: BaseFee, MaxSnoozes,
    GraceSeconds, Checks). Check plans: `NoChange` when the mode and the (type, difficulty, count) entries match as a
    set (order and registered codes ignored). `Strengthening` when the new mode is All and every old type is present
    with difficulty and count ≥. Anything else is `Weakening`.
  - `PendingChange(alarmId?, value, effectiveAfter)`: `alarmId == null` is global. The `value` is a sealed
    `SettingValue` (BaseFeeTier, MaxSnoozes, GraceSeconds, Checks(CheckPlan)) that carries its `field`.
  - Ports: `PendingChangeRepository`, `GlobalSettingsRepository` and `CommitmentEventRepository`. Fakes for all three
    go in `:testing`.
  - Use cases:
    - `SetBaseFee` (tiers 1–10) and `SetMaxSnoozes` (1–5) return `Saved(pendingUntil)` or
      `DomainError.InvalidSetting(field)`.
    - `RecordCommitmentEvent(alarm, action)` writes only inside the alarm's window and returns the event or null.
    - `PromotePendingChanges` (an `AlarmScheduling` hook).
- **The lock decision (`CommitmentRules.decide`):** the change is classified against the *effective* value. That is the
  live value, or a pending value whose occurrence has already passed (due but not yet promoted).
  - `NoChange`, `Strengthening`, or anything outside the window: apply now, and drop the field's pending change.
  - `Weakening` inside the window: keep the effective value live, and store or replace the pending change with this
    window's `effectiveAfter`. The exception is a pending change that already holds the same value: it is kept as it
    is, so re-saving never pushes the date later.
  - The use case always writes the effective value as the live value, so a due pending change is promoted on the way.
- **`SaveAlarm`:** for an edit, the alarm's locked fields (grace window, check plan) go through the decision.
  - **The alarm's occurrences:** the stored next occurrence (if the stored alarm is enabled) and the new one (if the
    draft is enabled). The latest in-window one is `effectiveAfter`.
  - **Writes:** the other fields are stored at once. Weakened fields keep their effective values in the row and are
    stored as `PendingChange`s.
  - A new alarm has no commitment, so nothing is pending.
  - `SaveAlarm.save(draft)` returns `AlarmSaved(alarm, pendingUntil)`. `invoke(draft)` keeps returning the stored
    alarm, so the editor and the tests are unchanged.
- **`ConfigResolver.resolve(..., pendingChanges)`:** a pending change applies only to an occurrence strictly after its
  `effectiveAfter.scheduledAt`. The resolved config is frozen at `AlarmFired` as before.
- **`PromotePendingChanges`:** once now > `effectiveAfter.scheduledAt`, and unless the session in progress rings that
  very occurrence, it writes the pending value as the live value and then deletes the pending change. It runs:
  - on app start and every time the engine returns to Idle (so after `Recorded`), from `YawnAndPawnApp`;
  - in `rescheduleAll()`, through an optional `PendingChangePromotion` hook on `AlarmScheduling` (before its lock).
  - A pending change whose alarm was turned off still takes effect after that time.
- **Storage:**
  - **app.db v8 → v9:**
    - `pending_change` (`alarm_id` FK → alarm, cascade; `field`; `value_json`; `effective_after_alarm_id`;
      `effective_after_scheduled_at`; PK `alarm_id, field`).
    - `commitment_event` (`id` PK, `alarm_id`, `occurrence_at`, `action`, `at`; no FK, because the event must outlive a
      deleted alarm).
    - Exported `9.json`, plus a migration test that keeps v8 rows.
  - **Settings DataStore:** `base_fee_tier` and `max_snoozes` (the persisted `GlobalSettings`), and
    `pending_global_changes` (a JSON list). AD-6: no settings copy in app.db.
  - `CompositePendingChangeRepository` routes alarm changes to Room and global ones to DataStore.
- **The fire path (`WakeService`):** it reads the pending changes first and the live settings second. The reverse order
  of promotion's writes makes the pair consistent. It then resolves with both. Every read is bounded by a 1 s timeout,
  and a failure falls back to the defaults or to no pending change, with a log entry. It never fails or delays the ring.

## Boundaries & Constraints

**Always:**
- **Alarm safety:**
  - A pending change never stops an alarm from ringing and never changes a running session.
  - A failed or slow settings read rings with the defaults and the live alarm.
  - A pending check plan was validated when it was saved.
  - On promote or resolve, a pending QR/Barcode entry takes the live entry's registered code when the live plan has
    one. A re-registered sticker therefore always wins.
- Every new use case that writes runs inside `SessionLockGuard.whenIdle`. `PromotePendingChanges` is the one allowed
  writer: it only promotes occurrences that have passed, and session configs are frozen.
- The write order is the live value first, then the pending change. A crash in between leaves the stronger or the
  intended state, never a lost commitment beyond one field.
- Global settings and global pending changes live only in the settings DataStore, which is backed up. The new app.db
  tables are backed up with app.db.
- Snooze length, sound, volume, label and repeat days are never locked.

**Never:**
- No UI, strings or screens: that is 4.5 and 4.6.
- No change to `resolveTest` (PR #41 touches it): a test ring uses the live settings.
- No guard against deleting and recreating an alarm (owner decision 4).

## AC deviations and decisions (fast mode: default taken, owner can change)

1. **Schema number:** app.db **v8 → v9**, not the epic's "7 → 8" (the Epic 4 context's schema order). If 4.10 merges
   first, this becomes v10 (see the rebase notes).
2. **Moving an alarm's time while weakening:** the lock uses the stored and the new occurrence, whichever are in the
   window, and waits for the latest of them. Moving the alarm out of the window and weakening it in the same save
   applies at once. This is a nudge, like delete and recreate (owner decision 4).
3. **`NoChange` drops a pending change:** saving the effective value means "keep what I have". Until 4.6 loads pending
   values into the editor, re-saving an alarm with untouched grace or checks cancels its pending weakening. That is the
   safe direction.
4. **Check plan equality** ignores the order of the entries and their registered codes. A new sticker or a reorder is
   never "weakening"; a reorder in All mode is Strengthening, which also applies at once.
5. **`SaveAlarm` API:** a new `save(draft): Outcome<AlarmSaved>`. `invoke` keeps its signature (it returns
   `save(...).alarm`).
6. **`RecordCommitmentEvent`** takes the `Alarm` (captured before a delete) and writes only inside the window. 4.6
   calls it.
7. **Global settings repository** stores only the base fee tier and max snoozes. The other `GlobalSettings` fields
   (defaults for new alarms) stay at their defaults until Epic 5.
8. **Promotion hook:** `AlarmScheduling(..., promotion: PendingChangePromotion? = null)`. The backup agent's fallback
   construction runs without it; the app start covers that.
9. **`InvalidSetting(field: SettingField)`** is added to `DomainError`. `SettingField` is `BaseFee` or `MaxSnoozes`.

## I/O & Edge-Case Matrix

| Case | Expected |
|---|---|
| Occurrence − now = 7:59:59 / 8:00:00 / 8:00:01 | inside / inside / outside |
| Helsinki spring-forward night, 22:00 → 06:30 (local 8 h 30, instant 7 h 30) | inside (the instant wins) |
| Disabled alarm at +1 h | never locks |
| Global, alarms at +2 h and +7 h | `effectiveAfter` is the +7 h one |
| Effective $3, pending $1, new $2 | Weakening; pending replaced by $2 |
| Effective $3, pending $1, new $5 | Strengthening; live $5, pending cleared |
| Weakening equal to the stored pending value | pending kept (same `effectiveAfter`) |
| Due pending (occurrence passed), new weakening in window | live = due value, new pending |
| Fee tier 0 / 11, max snoozes 0 / 6 | `InvalidSetting`, nothing written |
| Resolve at the waited-for occurrence / earlier / later | ignored / ignored / applied |
| Promote while the session for that occurrence is in progress | skipped; promoted after `Recorded` |
| Settings read fails or times out at fire | rings with the defaults (logged) |
| Alarm deleted | its `pending_change` rows cascade away; its `commitment_event` rows stay |

</intent-contract>

## Code Map

- `core/.../core/config/`:
  - `LockWindow.kt`, `ChangeClassifier.kt`, `PendingChange.kt` (+ JSON), `CommitmentRules.kt`;
  - `ConfigPorts.kt` (repositories, `CommitmentEvent`);
  - `SettingsUseCases.kt` (`SetBaseFee`, `SetMaxSnoozes`, `Saved`), `RecordCommitmentEvent.kt`,
    `PromotePendingChanges.kt`.
- `core/.../session/SessionConfig.kt`: `resolve(..., pendingChanges)`.
- `core/.../alarm/AlarmUseCases.kt`: the `SaveAlarm` lock path and `AlarmSaved`.
- `core/.../alarm/AlarmScheduling.kt`: the promotion hook.
- `core/.../error/DomainError.kt`, `log/Logger.kt`.
- `testing/.../ConfigFakes.kt`; `AlarmUseCasesFixture` wires the pending fake.
- `data/.../config/` (Room entities and DAOs, `RoomPendingChangeRepository`, `RoomCommitmentEventRepository`,
  `DataStoreGlobalSettings`, `CompositePendingChangeRepository`).
- `data/.../db/AppDatabase.kt` and `AppDatabaseMigrations.kt` (`MIGRATION_8_9`); `data/schemas/.../9.json`.
- `androidApp`: Koin wiring, the `WakeService` config read, the promotion on Idle, the backup XML comments, and the
  `BackupRulesCoverageTest` flow.
- `data/.../SessionLockGuardScanTest.kt`: `config` becomes a fully scanned package, and `PromotePendingChanges` is
  allowed.

## Rebase notes

- **PR #41 (`fix/epic-3-device-check-bugs`):**
  - It touches `ConfigResolver.resolveTest` and the defaults. This story only adds a defaulted `pendingChanges` parameter
    to `resolve` and a `applyPending` helper, so take both sides.
  - `WakeService.dispatchAlarmFired` now passes `settings` and `pending` instead of `GlobalSettings()`. Keep that if #41
    edits nearby lines.
- **4.2 (`FeeLadder`/Money):** this story does not touch it. The base fee here is a tier `Int` (1–10).
- **4.10 (app.db v10):**
  - If 4.10 merges first with v9, renumber `MIGRATION_8_9` to the next free version.
  - Re-export the schema, and update `AppDatabaseFactoryTest`'s step list and `SCHEMA_VERSION`.
- **4.5 and 4.6:** use `PendingChangeRepository.observe()`, `SetBaseFee`/`SetMaxSnoozes`, `SaveAlarm.save` (with
  `pendingUntil`) and `RecordCommitmentEvent`.

## Verification

- `./gradlew qualityGate :androidApp:assembleDebugAndroidTest --no-daemon`: Kover `core.config` ≥ 90 % (a new
  "config" variant), and the scan, migration and backup coverage tests.

## Auto Run Result

See the commit `feat(4.4)` and the coordinator's report.
