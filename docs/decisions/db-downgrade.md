# Restoring a newer app.db onto an older install

- **Date:** 2026-10-01 (Story 1.13)
- **Question:** `app.db` is backed up (AD-6) and its schema only moves forward (version 3 since Story 1.13, with `session_history`; version 4 since Story 2.9, with `session_merge`). What happens when a backup made by a newer app version, with a higher schema version, is restored onto an install of an older app version?
- **Decision (accepted, Story 1.13):** the restore of an `app.db` whose schema version (`PRAGMA user_version`) is above the schema the installed app knows (`AppDatabase` version) is **skipped and logged**. The `app.db` already on the phone stays as it is, the rest of the restore (DataStore settings) goes ahead, and the log line names only the two version numbers.
- **Implementation:** Story 2.12 (back up alarms and history) added `PpsBackupAgent`.
  - Its `onRestoreFile` writes an incoming `app.db` to a temporary file in device-protected `no_backup` first.
  - `AppDatabaseRestoreGuard` then reads `user_version` from the SQLite header and compares it with `AppDatabase.SCHEMA_VERSION`. A newer file is dropped, logged once, and recorded in `SkippedRestoreNotice` (device-protected preferences, excluded from backup). A file that is not a database is dropped and logged.
  - The manifest keeps restores in restricted mode, so the app's own Room instance never has `app.db` open while it is replaced.
  - **Still open:** the visible notice. It needs owner-approved copy, so the flag is stored but not yet shown (`_bmad-output/implementation-artifacts/deferred-work.md`).

## Why

- **There is no destructive fallback.** `buildAppDatabase` registers only forward migrations (`APP_DATABASE_MIGRATIONS`: 1 to 2, 2 to 3, 3 to 4) and never calls `fallbackToDestructiveMigration`, so user history is never wiped by a schema change. The flip side is that Room refuses to open a file whose version is above its own: there is no migration down. If such a file replaced `app.db`, every read of alarms and history would fail with a `StorageFailure`, and alarms could no longer be armed. Keeping the older, readable `app.db` is better than a newer one the app cannot open.
- **Skipping keeps the app working, at a cost.** The phone keeps the `app.db` it had, so alarms still ring and history still reads. The trade-off is a newly set-up phone: its `app.db` is empty, so a skipped restore leaves it with no alarms and no history from the old phone. Done silently, the user would expect their alarms back and oversleep. The backup itself is untouched, so the data is not lost: an updated app can restore it again (Auto Backup restores at install, so in practice by reinstalling the updated app). So the skip must never be silent (see below).
- **Downgrading the file is not an option.** Rewriting a newer schema into an older one would need down-migrations for every version, tested in both directions, for a rare case. Dropping the unknown tables would silently lose history.
- **Android usually prevents it already, but the app does not rely on that.** `android:restoreAnyVersion` is not set (it defaults to false), so the Backup Manager normally declines a backup made by a newer `versionCode`. The guard covers the paths where that check may not apply (device-to-device transfer, OEM migration tools) and any case where the schema and `versionCode` disagree.

## What Story 2.12 must do

- Compare the restored `app.db`'s `user_version` with the installed `AppDatabase` version before the file replaces the current one (for example in `PpsBackupAgent.onRestoreFile`, reading the SQLite header of the incoming file). Above it: skip the file and log `OperationFailed("restore app.db", "schema <restored> is newer than <installed>")`. Equal or below: restore it; Room's migrations bring an older file up to date on the next open.
- Tell the user when a restore was skipped: a notice the next time the app opens, saying their alarms and history were not restored because the backup comes from a newer version of the app, and that installing the latest version brings them back. Its copy needs the owner's approval like any other string (EXPERIENCE.md, `CopyRulesTest`).
- Never set `android:restoreAnyVersion="true"`.
- Add a Robolectric test: a v4 file (hand-built, `PRAGMA user_version = 4`) offered to the agent leaves the current v3 `app.db` unchanged, logs once and sets the notice; a v2 file is restored and migrates to v3 with no notice.
