---
title: 'Story 1.7: Store alarms in app.db'
type: 'feature'
created: '2026-09-27'
status: 'done'
baseline_commit: '22e9c13b9c0625412b26cb901576706f75ca60eb'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-1-6-time-ports-deadlines-and-alarm-occurrence-math-in-core.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Alarms exist only as a rule type for occurrence math; nothing stores them, validates them, or keeps them readable before first unlock, and nothing controls what Android backs up.

**Approach:** Implement Story 1.7 in `epics.md`: the `Alarm` domain, `AlarmRepository` port and alarm use cases in `:core` (with a fake in `:testing`), `RoomAlarmRepository` over `app.db` in device-protected storage in `:data` (Room 3.0.3, or 2.8.5 with a decision doc if OQ-2 blocks it), and Auto Backup rules that include only `app.db`.

## Boundaries & Constraints

**Always:**
- `Alarm` fields and defaults exactly as the story lists (`id` UUID v4 string, `time`, `repeatDays` empty = one-time, `label` ≤ 40 chars optional, `enabled`, `soundRef` default built-in, `volumePercent` 80, `gradualVolume` true, `rampStartPercent` 20, `vibration` true, `snoozeLengthMinutes` ∈ {5, 9, 10, 15} default 9, `graceSeconds` 15–30 default 20, `requestCode`, `createdAt`, `updatedAt`). `Alarm` exposes its `AlarmRule` for `nextOccurrence` (Story 1.6).
- Errors as values (AD-12): add `Outcome<T, E>` (sealed Success/Failure) and sealed `DomainError` with `InvalidAlarm(field)`, `NotFound(id)` and `StorageFailure(cause description)`; adapters map exceptions to these, core never throws for expected failures.
- `AlarmRepository` port: `observeAll(): Flow<List<Alarm>>` (ordered by time of day, then `createdAt`), `get(id)`, `upsert(alarm)`, `delete(id)`, returning `Outcome`. `FakeAlarmRepository` in `:testing` with the same ordering and uniqueness rules.
- Use cases `SaveAlarm`, `SetAlarmEnabled`, `DeleteAlarm`, `DuplicateAlarm` validate label length, snooze length, grace range and `volumePercent`/`rampStartPercent` in 0–100, returning `DomainError.InvalidAlarm(field)`. New IDs come from an `IdGenerator` port (UUID v4 via `kotlin.uuid`; fake in `:testing`); timestamps from the Story 1.6 `Clock` port. `DuplicateAlarm` gets a new id, new request code and fresh timestamps.
- Request codes: reserved constants in `:core` (1 = session slot, 2 = test alarm, per AD-4); alarm codes are assigned from 1000 upward as (highest existing code + 1), never reused while the alarm exists, and stay stable across edits.
- `app.db`: Room KMP, schema version 1, table `alarm` only, file path from `createDeviceProtectedStorageContext()` (never the credential-protected context), exported schema committed under `data/schemas/`, unique index on `requestCode`. Only `:data` touches the database; Koin binding in `dataModule`.
- Room 3.0.3 (`androidx.room3`) first; if it cannot work with KMP + AGP 9, use Room 2.8.5 and write `docs/decisions/oq-2-room.md` either way (record what was tried and the outcome).
- Backup: `dataExtractionRules` (API 31+) and `fullBackupContent` (API ≤ 30) referenced from the manifest, include `app.db` in the device-protected domain (`device_database` / equivalent), exclude everything else created so far; Robolectric test parses both XML files and asserts the entries.
- New runtime artifacts (Room, SQLite driver) are added to `config/dependency-allowlist.txt` with a review comment; no network-capable library.

**Never:**
- No check config, pending changes, motivation, session history or `runtime.db` (later stories).
- No scheduling (Story 1.10) and no UI (Story 1.8/1.9).
- No destructive migration fallback.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Save new | valid fields | stored with new UUID, code ≥ 1000, createdAt = updatedAt = now | N/A |
| Edit | existing alarm, changed time | same id and request code, updatedAt = now | N/A |
| Label too long | 41 chars | not stored | `InvalidAlarm(label)` |
| Bad snooze | 7 min | not stored | `InvalidAlarm(snoozeLengthMinutes)` |
| Bad grace | 14 s / 31 s | not stored | `InvalidAlarm(graceSeconds)` |
| Enable/disable | existing id | `enabled` flips, updatedAt = now | `NotFound(id)` if missing |
| Delete | existing / missing id | removed / nothing changes | `NotFound(id)` for missing |
| Duplicate | existing alarm | copy with new id and new code | `NotFound(id)` if missing |
| Ordering | alarms at 22:00, 06:30, 07:15 | observeAll emits 06:30, 07:15, 22:00 | N/A |
| Duplicate request code | two rows with same code in Room | insert rejected | `StorageFailure` |
| Backup rules | both XML files | include `app.db` (device-protected), nothing else | N/A |

</frozen-after-approval>

## Code Map

- `core/src/commonMain/kotlin/com/yawnandpawn/app/core/alarm/` -- `AlarmRule.kt`, `AlarmOccurrence.kt` (Story 1.6); add `Alarm`, `AlarmRepository`, use cases, `RequestCodes` here; `Outcome`/`DomainError` in a shared `core` package (e.g. `core.error`).
- `core/src/commonMain/kotlin/com/yawnandpawn/app/core/time/TimePorts.kt` -- `Clock` typealias for timestamps.
- `testing/src/commonMain/kotlin/com/yawnandpawn/app/testing/` -- `TimeFakes.kt`; add `FakeAlarmRepository`, `FakeIdGenerator`, alarm builder.
- `data/build.gradle.kts`, `data/src/commonMain/.../DataModule.kt`, `data/schemas/.gitkeep` -- Room + KSP setup, schema export dir, Koin binding.
- `gradle/libs.versions.toml` -- `room3`, `ksp` versions and aliases already present; SQLite bundled driver needs an entry (verify published version).
- `androidApp/src/main/AndroidManifest.xml` (`allowBackup="true"`, no rules yet), `androidApp/src/main/res/xml/` -- add both rule files.
- `config/dependency-allowlist.txt` -- add reviewed Room/SQLite artifacts; `checkDependencyAllowlist` fails otherwise.
- `build-logic/.../CoreDependencyRules.kt` -- `:core` may not import Room; keep entities/DAO in `:data`.

## Tasks & Acceptance

**Execution:**
- [x] `core/.../` -- `Outcome`, `DomainError`, `Alarm` (+ defaults, `toRule()`), `RequestCodes`, `IdGenerator`, `AlarmRepository`, four use cases + table-driven tests for every matrix row that is core logic.
- [x] `testing/.../` -- `FakeAlarmRepository`, `FakeIdGenerator`, `anAlarm()` builder + tests.
- [x] `data/` -- Room database, entity, DAO, mappers, `RoomAlarmRepository` (exception → `StorageFailure`), device-protected path, schema export; host tests for insert, update, delete, ordering, unique request code.
- [x] `docs/decisions/oq-2-room.md` -- Room version decision.
- [x] `androidApp/src/main/res/xml/data_extraction_rules.xml`, `backup_rules.xml`, manifest attributes, Robolectric parsing test.
- [x] `config/dependency-allowlist.txt`, `gradle/libs.versions.toml` -- new artifacts, reviewed.

**Acceptance Criteria:**
- Given the branch, when `./gradlew qualityGate` runs, then it passes with the schema committed under `data/schemas/`, `:core` ≥ 90% line coverage and the allowlists updated.

## Implementation Notes

- `./gradlew clean qualityGate koverLog --rerun-tasks` passes locally (BUILD SUCCESSFUL); `:core` line coverage 100%. Schema exported to `data/schemas/com.yawnandpawn.app.data.db.AppDatabase/1.json`.
- **Core:** `core.error` has `Outcome` (+ `map`, `flatMap`, `valueOrNull`, `errorOrNull`) and `DomainError`; `InvalidAlarm(field)` takes an `AlarmField` enum (`Label`, `VolumePercent`, `RampStartPercent`, `SnoozeLengthMinutes`, `GraceSeconds`). `core.id` has `IdGenerator` + `UuidV4IdGenerator` (`kotlin.uuid`). `core.alarm` has `Alarm` (defaults as companion constants, `toRule()`), `AlarmListOrder` (time, `createdAt`, then id as tie-break), `validate(alarm)`, `RequestCodes`, `AlarmRepository`, `AlarmDraft` and the four use cases. Label length counts a surrogate pair as one character. Use cases truncate `clock.now()` to whole milliseconds (the stored precision), so a returned alarm equals the stored one. `SetAlarmEnabled` and `DuplicateAlarm` also validate (a restored row could be out of range). The next request code is read via `observeAll().first()` (the port has no other listing call). `kotlinx-coroutines-core` became `api` in `:core` because the port exposes `Flow`.
- Core tests cannot use `:testing` (AD-1 graph), so `AlarmUseCasesTest` has small local doubles; the shared fakes are in `:testing` (`FakeAlarmRepository` with a `failure` switch, `FakeIdGenerator` with predictable UUID v4 strings, `anAlarm()`).
- **Data:** Room 3.0.3 worked first time (see `docs/decisions/oq-2-room.md`). Columns: `time_nano_of_day` (lossless, sortable), `repeat_days` bitmask, epoch-millis timestamps, unique index `index_alarm_request_code`. `AlarmDao.upsert` is `@Update`-then-`@Insert` in a transaction, not `@Upsert` (which swallows a request-code clash and silently drops the new row). Driver `AndroidSQLiteDriver` (framework SQLite), journal mode TRUNCATE so that backing up only `app.db` never misses WAL content. `dataModule` moved to `androidMain` (it needs `Context`), and its test moved to `androidHostTest` (Robolectric).
- `:data` build: lint tasks got an explicit `dependsOn` on the `kspAndroid*` tasks; without it Gradle fails qualityGate with an implicit-dependency error on the KSP-generated source folders.
- **Android:** `appModule` binds `IdGenerator` and the four use cases (`AlarmWiringTest`). Backup rules include only `device_database/app.db` (cloud backup, device transfer and API ≤ 30); there are no excludes, since an include already excludes everything else. `BackupRulesTest` parses both XML files and checks the manifest attributes in the manifest source (the `ApplicationInfo` fields are hidden).

## Spec Change Log

## Review Triage Log

Pass 1 (blind-hunter, edge-case-hunter, verification-gap):

| # | Finding | Verdict | Evidence | Route |
|---|---|---|---|---|
| 1 | List read in `nextRequestCode` (and `get`'s row mapping) outside `storage { }`: DB failure throws out of `SaveAlarm`/`DuplicateAlarm`; fakes' `failure` ignores `observeAll` | medium | `observeAll().first()` unwrapped; closed-DB test covers only get/upsert/delete | patch |
| 2 | Concurrent saves race on request-code allocation; edit racing delete re-inserts the alarm | medium | Read-then-write with no lock; `upsert` inserts when the row is gone | patch |
| 3 | `SetAlarmEnabled` re-validates, so an out-of-range stored alarm can't be disabled | medium | `validate()` on the toggle path | patch |
| 4 | `time` not truncated to minutes | medium | Seconds/nanos round-trip; would shift ring time | patch |
| 5 | Blank label stored as `""` (two "no label" states); `characterCount` KDoc overclaims | low | Test asserts `""` valid; code counts code points | patch |
| 6 | Blank `soundRef`; ramp start above target volume accepted | low | No `AlarmField` for either | patch |
| 7 | `StorageFailure.cause` could leak to UI | low | Free-form `e.message` | patch (KDoc) |
| 8 | `DataModuleTest` `finally` re-gets the database | low | Masks original failure | patch |
| 9 | Deleting the highest alarm lets its request code be reused | maybe-false (medium once scheduling exists) | Spec allows reuse after deletion; harmful only if a stale PendingIntent isn't cancelled — Story 1.10 cancels on delete | defer |
| 10 | Restoring a newer-schema backup onto an older install (downgrade) | maybe-false (medium if true) | Arises from Story 1.13 schema v2; needs a written downgrade policy | defer |
| 11 | No test that `app.db` opens before first unlock | medium (unverified) | Only path comparison; `directBootAware` components arrive in 1.10/1.14 | defer |
| 12 | `BackupRulesTest` reads source manifest, not merged | low | No library overrides backup attributes today; merged-manifest parsing is extra tooling | reject |
| 13 | TRUNCATE journal: backup during a write may miss a hot journal | low | Backups run while idle; accepted risk vs WAL losing recent writes | reject |
| 14 | `Int.MAX_VALUE` request-code overflow | low | Unreachable in practice | reject |
| 15 | Lint→KSP workaround tied to task names | low | Works today; comment explains it | reject |
| 16 | Code Map still mentions the bundled driver | n/a | Fix is a spec edit; decision doc records the framework driver | reject |

## Design Notes

- Device-protected path: build the database file from `context.createDeviceProtectedStorageContext().getDatabasePath("app.db")` and hand it to the Room builder; do not call `.applicationContext` on the device-protected context (it returns the credential-protected one).
- Environment (company PC): JDK 17 at `C:/Users/BuiTua/AppData/Local/Programs/jdk17/jdk-17.0.20.1+1` (export `JAVA_HOME` before `./gradlew`; use `./gradlew --stop` after changing build logic or detekt rules).

## Verification

**Commands:**
- `./gradlew qualityGate` -- expected: BUILD SUCCESSFUL.
- `ls data/schemas/` -- expected: the exported version-1 schema JSON for the app database.
