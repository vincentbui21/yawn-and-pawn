---
title: 'Story 2.12: Back up alarms and history, never the session or media'
type: 'feature'
created: '2026-10-05'
status: 'in-progress'
baseline_revision: '4bd25ee4d0cfbcb25e77c90cfcce0094d66ef317'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
  - '{project-root}/docs/decisions/db-downgrade.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** Auto Backup is live (a reinstall on the owner's phone brought old alarms back), but the rules only name `app.db` and `runtime.db`. They leave out the settings DataStore, say nothing about journals, shared preferences or credential-protected storage (where media will live), and nothing re-arms restored alarms until the user opens the app. A restored `app.db` from a newer schema would also make every alarm read fail (`docs/decisions/db-downgrade.md`).

**Approach:**
- Finalise both rule files.
- Add a `PpsBackupAgent` (`fullBackupOnly`, Auto Backup file handling unchanged). It guards the incoming `app.db` against a newer schema and runs `rescheduleAll()` when a restore finishes.
- Add a `BackupRulesCoverageTest` that fails on any app file the rules neither include nor exclude.

## Boundaries & Constraints

**Always:**
- **Device-protected includes**, in every section (`cloud-backup`, `device-transfer`, and `full-backup-content` for API 30 and lower):
  - `device_database/app.db`;
  - `device_file/datastore/settings.preferences_pb` (the settings, the missed-note dismissals and the pending test ring all live in this one DataStore file).
- **Explicit excludes**, in every section:
  - `device_database`: `runtime.db` with its `-journal`, `-wal` and `-shm` files, and `app.db-journal`;
  - `device_sharedpref`: `wake_runtime.xml` (the saved user volume during a ring) and `backup_restore.xml` (the skipped-restore notice flag);
  - the whole credential-protected storage: `root`, `file`, `database` and `sharedpref`, each with path `.`.
- `BackupRulesTest` asserts every include and exclude entry in both files, in order.
- **Manifest:** `allowBackup="true"`, `fullBackupOnly="true"`, and `backupAgent=".android.backup.PpsBackupAgent"`. It also sets the application property `android.app.backup.PROPERTY_USE_RESTRICTED_BACKUP_MODE=true`, so the custom `YawnAndPawnApp` (Room, engine restore) never runs while a restore replaces `app.db`. `restoreAnyVersion` is never set.
- **`PpsBackupAgent.onRestoreFile`:** an incoming `app.db` (the device-protected `app.db` path) is first written to a temporary file next to it. The agent then reads its SQLite header (magic string and `user_version` at offset 60):
  - at or below `AppDatabase.SCHEMA_VERSION`: it replaces `app.db` (deleting any `app.db-journal`), and Room migrates it on the next open;
  - above it: the file is dropped, the current `app.db` is kept, `OperationFailed("restore app.db", "schema <restored> is newer than <installed>")` is logged once, and the notice flag is set;
  - not a SQLite file: dropped and logged, and no notice is set.
  - Every other file goes to the default handling.
- **`onRestoreFinished`:** it runs `AlarmScheduling.rescheduleAll()` to completion. It uses the running Koin graph if there is one. Otherwise (restricted mode) it starts `appModule` and `dataModule` itself and stops them afterwards. The agent never touches `runtime.db`, history or merges.
- **Schema version:** `AppDatabase.SCHEMA_VERSION` is the single source of truth for the `@Database` version.

**Never:**
- No visible notice UI. No owner-approved copy exists yet, so the flag is stored and the UI is deferred.
- No change to EXPERIENCE.md, no screen change, and no destructive migration.
- No down-migration, and no `restoreAnyVersion`.
- Never include `runtime.db`, the AlarmVolume prefs or any credential-protected path.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Same schema | Incoming v3 `app.db` with an alarm | Replaces the current file; no notice | No error expected |
| Older schema | Incoming v2 `app.db` with an alarm | Replaces the current file; the next open migrates it to v3 with the alarm kept; no notice | No error expected |
| Newer schema | Incoming v4 file, current v3 file holds an alarm | Current `app.db` unchanged, temp file gone, one `OperationFailed` log, notice flag = 4 | Logged, never thrown |
| Not a database | Incoming bytes without the SQLite magic | Current `app.db` unchanged, logged, no notice | Logged, never thrown |
| Restore finished, Koin running | Restored enabled alarm, `FakeAlarmScheduler` | The alarm is armed; `runtime.db` absent; the engine restores Idle; history and merge rows unchanged | No error expected |
| Restore finished, restricted mode | No Koin running, restored enabled alarm | The agent starts Koin, arms the alarm through `AlarmManager`, and stops Koin | No error expected |
| Coverage | Full simulated morning on real Room and DataStore | Every app file is included or explicitly excluded in every section | The test names each uncovered file |

</intent-contract>

## Code Map

- `androidApp/src/main/AndroidManifest.xml` -- the application element (backup attributes); add the agent and the property.
- `androidApp/src/main/res/xml/data_extraction_rules.xml`, `backup_rules.xml` -- today `app.db` include and `runtime.db` exclude only.
- `androidApp/src/test/kotlin/com/yawnandpawn/app/BackupRulesTest.kt` -- the XML parser and rule assertions (Stories 1.7 and 1.12).
- `data/src/commonMain/.../db/AppDatabase.kt` -- `version = 3`; add `SCHEMA_VERSION`. `data/src/androidMain/.../db/AppDatabaseFactory.kt` -- `appDatabaseFile()`, TRUNCATE journal (so `app.db-journal` exists, never `-wal`).
- `data/src/androidMain/.../settings/SettingsDataStore.kt` -- `datastore/settings.preferences_pb` in device-protected files.
- `androidApp/src/main/kotlin/com/yawnandpawn/app/android/wake/AlarmVolume.kt` -- device-protected prefs `wake_runtime`.
- `androidApp/src/main/kotlin/com/yawnandpawn/app/YawnAndPawnApp.kt` -- `appModule`; `core/.../alarm/AlarmScheduling.kt` -- `rescheduleAll()`.
- `androidApp/src/test/.../TestApp.kt` (`restartKoin`, `stopApp`) and `android/wake/WakeApp.kt` -- the Robolectric app harness for the simulated morning.
- `docs/decisions/db-downgrade.md`, `_bmad-output/implementation-artifacts/deferred-work.md` -- the policy and its Story 2.12 entry.

## Tasks & Acceptance

**Execution:**
- `data/.../db/AppDatabase.kt` -- `const val SCHEMA_VERSION = 3`, used by `@Database`.
- `androidApp/.../android/backup/AppDatabaseRestoreGuard.kt` -- reads the header, then replaces or drops the file and logs. `SkippedRestoreNotice.kt` -- the device-protected prefs flag.
- `androidApp/.../android/backup/PpsBackupAgent.kt` -- the `onRestoreFile` routing (copies exactly `size` bytes from the pipe and drains on a write error) and `onRestoreFinished` rescheduling.
- `AndroidManifest.xml` and both rule XML files -- as in Boundaries.
- `androidApp/src/test/.../BackupRulesTest.kt` -- the full entry lists.
- `androidApp/src/test/.../backup/BackupRulesCoverageTest.kt`, `PpsBackupAgentTest.kt` -- the matrix rows.
- `docs/decisions/db-downgrade.md`, `deferred-work.md` -- mark the guard implemented, and defer the notice UI (owner copy).

**Acceptance Criteria:**
- Given the rule files, when `BackupRulesTest` runs, then every include and exclude in both files matches the lists above.
- Given a later story adds a file under app storage without a rule, when `BackupRulesCoverageTest` runs, then it fails naming that file.
- Given `./gradlew qualityGate`, when it runs, then it passes, and `git status --porcelain androidApp/src/test/screenshots/preview` is empty.

## Spec Change Log

- 2026-10-05 (implementation): the first run of `BackupRulesCoverageTest` found Room 3's lock files, `app.db.lck` and `runtime.db.lck`, which no rule named. Both are added as explicit `device_database` excludes in every section, and `BackupRulesTest` lists them. This is exactly the failure the coverage test exists to catch. KEEP: the lock files are excluded, never included.

## Review Triage Log

## Design Notes

- **No `app.db-wal` include (deviation from the AC):** both databases use the TRUNCATE rollback journal (Story 1.7), so no `-wal` file is ever written. Including one would also need the downgrade guard to drop it, or a newer WAL could pair with a kept older `app.db`. A journal-mode change would create an unlisted `app.db-wal`, and the coverage test then fails, which forces the decision.
- **Platform exclusions:** `cache`, `code_cache` and `no_backup` (in both storages) are never backed up by Auto Backup, so the coverage test treats them as excluded.
- **The pending test ring rides along in the settings DataStore.** It is inert after a restore: no test alarm is armed, so nothing reads it, and the next "Test alarm" replaces it.

## Verification

**Commands:**
- `./gradlew :androidApp:testDebugUnitTest --tests "*Backup*"` -- expected: all pass.
- `./gradlew qualityGate` -- expected: BUILD SUCCESSFUL, with no screenshot changes.

## Auto Run Result

**Summary:** backup now covers alarms, settings and history, and nothing else. A restore re-arms alarms without opening the app.
- **Rule files:** both include only device-protected `app.db` and `datastore/settings.preferences_pb`. They explicitly exclude:
  - `app.db-journal` and `app.db.lck`;
  - `runtime.db` with its `-journal`, `-wal`, `-shm` and `.lck` files;
  - `wake_runtime.xml` and `backup_restore.xml`;
  - all credential-protected storage (`root`, `file`, `database`, `sharedpref`).
- **`PpsBackupAgent`** (`fullBackupOnly`, restricted mode forced through the manifest property) does two things:
  - It sends an incoming `app.db` through `AppDatabaseRestoreGuard`. Newer schema: skipped, logged once, flag set. Not a database: skipped and logged. Same or older: replaces the file.
  - When the restore finishes, it runs `rescheduleAll()`, on the running Koin graph or on its own short-lived one.
- **`AppDatabase.SCHEMA_VERSION`** is the single source of the schema version. `3.json` is unchanged.

**Files changed:**
- `AndroidManifest.xml`, `res/xml/data_extraction_rules.xml`, `res/xml/backup_rules.xml`.
- `android/backup/PpsBackupAgent.kt`, `AppDatabaseRestoreGuard.kt`, `SkippedRestoreNotice.kt`.
- `data/.../db/AppDatabase.kt`.
- Tests: `BackupRulesTest` (every entry), `BackupRule.kt` (parser), `android/backup/PpsBackupAgentTest` (6 tests), `BackupRulesCoverageTest` (2 tests).
- `docs/decisions/db-downgrade.md`, `deferred-work.md`.

**Verification:** `./gradlew qualityGate` BUILD SUCCESSFUL. `git status --porcelain androidApp/src/test/screenshots/preview` is empty. Every matrix row is covered by a passing test.

**Deferred:** the visible skipped-restore notice, which needs owner-approved copy (`deferred-work.md`).

**Not run:** the step-04 review (owner-approved fast mode, planning and implementation only). A real restore on a device is Story 2.13 (human-verify).
