---
title: '0.1.7 settings rules: one global quiet time, snooze lengths 5/10/15/30'
type: 'feature'
created: '2026-10-31'
status: 'done'
route: 'dispatch'
review_loop_iteration: 0
baseline_commit: 'd100b56158e277182402d18fff50be7883b851e2'
context:
  - '{project-root}/.claude/skills/pps-design/SKILL.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Quiet time (the grace window after "I'm up") is set per alarm in a sub-screen few people find, and Settings only holds a "Default quiet time" for new alarms, so changing it never reaches existing alarms. The snooze lengths 5 / 9 / 10 / 15 min (default 9) are odd; the owner wants 5 / 10 / 15 / 30 with 10 as the default.

**Approach:** Owner decisions 2026-10-30 (approved, no further sign-off):
1. Quiet time is one global setting. The editor loses its "Quiet time" row and sub-screen (with its "Vibrate during quiet time"). Settings > Wake: "Default quiet time" becomes "Quiet time", its value shown as "{seconds} seconds · all alarms"; "Vibrate during quiet time" stays in Settings. Both apply to every alarm, existing ones included, and to test rings.
2. The commitment rule stays: a longer quiet time saved within 8 h of an enabled alarm waits until that alarm has rung (as base fee and max snoozes do), with the existing "Saved. Takes effect after ..." note under the setting. "Vibrate during quiet time" stays unlocked, as today (it was never a locked field).
3. Snooze length choices are 5, 10, 15, 30 min; the default for new alarms and the Settings default is 10. Stored alarms with 9 (or any value no longer allowed) become 10 through a Room migration to the next schema version; a stored Settings default of 9 (or any invalid value) reads as 10. Snooze length stays unlocked (it never was a locked field); 30 min fits the session rules (the 30-minute no-interaction timeout restarts when the snooze ends).

## Boundaries & Constraints

**Always:** keep the per-alarm `grace_seconds` / `vibrate_in_grace` columns and `Alarm` fields (no risky column drop), but they no longer drive any behaviour: sessions and test rings read the global values. Global quiet-time pending changes go through `SaveGlobalSetting` / `CommitmentRules` like base fee and max snoozes. Room migration with an exported schema JSON and a migration test. Strings in resources, tokens only, Roborazzi baselines updated (removed screens' baselines deleted).

**Never:** no change to version name/code; no UI beyond the listed rows; no new lock on snooze length or vibrate; do not drop or rename stored DataStore keys (read compatibility).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Global quiet time applies | alarm stored with grace 30, Settings quiet time 20 | the session's grace window is 20 s | N/A |
| Vibrate global | alarm `vibrateInGrace` false, Settings vibrate on, alarm vibration on | vibrates during quiet time | N/A |
| Vibration off wins | Settings vibrate on, alarm "Vibration" off | no quiet-time vibration | N/A |
| Longer quiet time inside lock | 20 -> 30 s, an enabled alarm rings in 7 h | live stays 20, pending 30 after that ring; note "Saved. Takes effect after ..." | N/A |
| Shorter quiet time inside lock | 30 -> 20 s | applies now | N/A |
| Longer quiet time outside lock | no alarm within 8 h | applies now | N/A |
| Pending applies after the ring | ring at the protected occurrence, then the next one | 20 s at the protected one, 30 s after | N/A |
| Migration | v15 alarms with snooze 9, 5, 15, 7 | 10, 5, 15, 10 | N/A |
| Default read | DataStore default snooze 9 | 10 | N/A |
| Test ring | editor test alarm | uses Settings quiet time and vibrate | N/A |

</frozen-after-approval>

## Code Map

- `core/.../alarm/Alarm.kt` -- `DEFAULT_SNOOZE_LENGTH_MINUTES` 9 -> 10, `SNOOZE_LENGTHS_MINUTES` -> {5, 10, 15, 30}; KDoc of `graceSeconds`/`vibrateInGrace`: kept, no longer used by sessions (0.1.7). `AlarmValidation.kt` still validates grace (stored values stay valid).
- `core/.../config/PendingChange.kt` -- add `LockedField.QuietTime(global = true)` and `SettingValue.QuietTimeSeconds(seconds)` with `@SerialName("QuietTime")` (stored names never change; leave `GraceSeconds` per-alarm entries as they are for old rows). `ChangeKind.kt` `classify`: QuietTime lower is stronger. `CommitmentRules.kt` `withPending`: apply QuietTime to `GlobalSettings`. `SettingsUseCases.kt` `SaveGlobalSetting`: `writeLive`, `invalidField` (15..30), `valueOf`; new use case `SetQuietTime`. Check every exhaustive `when` over `SettingValue` / `LockedField` (also `PromotePendingChanges.kt`, which promotes global ones into the settings store, data `PendingChange` storage/DataStore global list, `RecordCommitmentEvent.kt` / day-detail notes if they name fields).
- `core/.../session/SessionConfig.kt` -- `GlobalSettings.defaultGraceSeconds` / `defaultVibrateInGrace` become the global quiet time and vibrate (rename the Kotlin fields to `graceSeconds` / `vibrateInGrace`, keep the DataStore keys); `ConfigResolver.resolve`: grace = settings-with-pending `graceSeconds` (ignore per-alarm `SettingValue.GraceSeconds` pending rows), `vibrateInGrace = alarm.vibration && settings.vibrateInGrace`; `resolveTest` the same from `globalSettings`. `defaultSnoozeLengthMinutes` default 10.
- `core/.../config/DefaultSettingsUseCases.kt` -- `DefaultSetting.GraceSeconds` / `SetDefaultGrace` removed or redirected (quiet time now goes through `SetQuietTime`); `VibrateInGrace` stays a plain (unlocked) setting; snooze length set {5,10,15,30}.
- `core/.../alarm/AlarmUseCases.kt` ~220 -- `SaveAlarm` per-alarm grace lock: the editor no longer changes grace, so keep it harmless (it compares stored vs edited, which stay equal); `AlarmDraft` defaults follow `Alarm`.
- `data/.../config/DataStoreGlobalSettings.kt` ~155 -- read keeps keys; invalid snooze (9) falls back to the new default 10; `setDefaultGraceSeconds` writes the global value. `data/.../db/AppDatabase.kt` `SCHEMA_VERSION` 15 -> 16, `AppDatabaseMigrations.kt` `MIGRATION_15_16`: `UPDATE alarm SET snooze_length_minutes = 10 WHERE snooze_length_minutes NOT IN (5, 10, 15, 30)`, add to `APP_DATABASE_MIGRATIONS`; export `data/schemas/com.yawnandpawn.app.data.db.AppDatabase/16.json` (KSP writes it on build); test like `data/src/androidHostTest/.../db/AppDatabaseMotivationModeTest.kt` (create v15, insert 9/5/15/7, open, assert). Check `AlarmEntity` column defaults and backup/restore paths (`PpsBackupAgent`) that might carry 9.
- `composeApp/.../ui/editor/` -- remove `EditorPane.QuietTime` (contract ~77, `rows` ~266), its row in `AlarmEditorScreen.kt` `RowsCard` (~429), its sub-screen in `EditorSubScreens.kt` (~294), intents `GraceChanged`/`VibrateInGraceToggled` in `AlarmEditorViewModel.kt` (~359, ~380), grace parts of `EditorLock.kt` (~125, ~154). The form keeps the stored values and passes them through unchanged. `EditorForm.SNOOZE_OPTIONS` follows `Alarm`. New alarms default snooze from Settings (already).
- `composeApp/.../ui/settings/` -- `SettingsScreen.kt` `WakeCard`: row "Quiet time" (`settings_quiet_time`), value "%1$d seconds · all alarms" (`settings_quiet_time_value`); `QuietTimePane` title "Quiet time", slider, note (reword `editor_quiet_time_note` to cover all alarms, e.g. "After I'm up, every alarm stays quiet this long while you do your check."), plus `WeakeningNoteInline` for a pending quiet time (like `maxSnoozesNote` in `SnoozePanes.kt`). `SettingsViewModel.kt`: QuietTimeChanged goes through `SetQuietTime` the way `MaxSnoozesChanged` goes through `setMaxSnoozes` (~254, ~390, ~457 `weakeningNoteOf(saved.pending(LockedField.QuietTime))`), not through the defaults path (~586). `SettingsContract.kt`: `quietSeconds`, `quietTimeNote`. `UiModule.kt` wiring. Snooze length pane options follow `Alarm`.
- Fixtures/previews: `androidApp/src/debug/.../preview/*`, test samples (`EditorSamples`, `SettingsSamples` etc.), `composeApp/src/androidMain` previews; delete orphaned Roborazzi baselines of the removed quiet-time editor screens (`alarm_editor_quiet_time_*`).
- Docs: `docs/prd.md` (~112, 166-168: snooze lengths; quiet time per alarm), `_bmad-output/planning-artifacts/epics.md` (brief "owner decision 2026-10-30" notes where it states 5/9/10/15, default 9, per-alarm grace), both EXPERIENCE.md copies (editor IA row, Settings row, Key strings, glossary "Quiet time"), `docs/site/*` and `docs/closed-test/store-listing-draft.md` if they name snooze lengths or per-alarm quiet time.

## Tasks & Acceptance

**Execution:**
- [x] Core: snooze constants; QuietTime locked global field + use case; resolver reads globals; tests (ConfigResolverTest, CommitmentRules/ChangeKind, SaveGlobalSetting, PromotePendingChanges) covering the matrix rows.
- [x] Data: migration 15 -> 16 + schema 16 + migration test; DataStore read 9 -> 10 test; global quiet time stored.
- [x] UI: editor quiet-time row/pane removed; Settings "Quiet time" row value, pane with lock note; view-model tests (lock note shows for a longer value inside the window, none for shorter).
- [x] Fixtures, previews, debug design preview compile; Roborazzi baselines re-recorded / orphans removed.
- [x] Docs.

**Acceptance Criteria:**
- Given an existing alarm, when the user changes Settings > Quiet time (outside the lock), then that alarm's next session uses the new value.
- `./gradlew qualityGate` passes.

## Implementation Notes

- Renames (Kotlin only, stored names unchanged): `GlobalSettings.graceSeconds` / `vibrateInGrace`; port `setGraceSeconds` / `setVibrateInGrace` (DataStore keys `default_grace_seconds` / `default_vibrate_in_grace` and the snapshot JSON names `defaultGraceSeconds` / `defaultVibrateInGrace` kept); `SetDefaultGrace` and `DefaultSetting.GraceSeconds` removed, `SetDefaultVibrateInGrace` -> `SetVibrateInGrace`; `SettingField.DefaultGrace` -> `SettingField.QuietTime`; `SaveDefaultSetting.GRACE_SECONDS` -> `SaveGlobalSetting.GRACE_SECONDS`; `SettingsRow.DefaultQuietTime` -> `QuietTime`; `SettingsUiState.defaultQuietSeconds` -> `quietSeconds` (+ `quietTimeNote`). Strings: `editor_quiet_time`, `editor_quiet_time_note`, `settings_default_quiet_time` removed; `settings_quiet_time`, `settings_quiet_time_value`, `settings_quiet_time_note` added.
- `hasSameSettingsAs` no longer compares the alarm's own grace / vibrate-in-grace (not shown or chosen any more), so a new alarm that differs only there switches the stored one on.
- The editor's "Test alarm" now passes the read Settings to `ScheduleTestAlarm` (it passed none before, so it rang the built-in base fee / max snoozes too); a store read failure falls back to the last-known settings, logged.
- Settings: quiet-time slider saves share the locked queue; a step superseded by a newer one is skipped (a drag coalesces), and like base fee / max snoozes a queued quiet-time save is dropped (logged) when Settings closes.
- Leftover per-alarm `GraceSeconds` pending rows: ignored by the resolver and the editor; an editor save of that alarm clears it (no change against the stored value), promotion still writes it into the unused column.

## Spec Change Log

## Review Triage Log

| # | Layer | Finding | Verdict | Evidence / route |
|---|---|---|---|---|
| 1 | all three | Quiet-time slider drag then leaving Settings drops the final value (locked saves are dropped in `onCleared`; the guarding test was moved to snooze length) | medium | `onCleared` finishes only `Save.Default`. Patch: finish the latest queued quiet-time save in `outlivingScope`; restore a slider-drag close test. |
| 2 | blind+edge | Upgrade replaces each alarm's own quiet time/vibrate with the global value without the lock | low | Intended by the owner decision (applies to existing alarms); one-time at upgrade, closed test only. Rejected; listed for the owner's phone check. |
| 3 | edge | 25,26,25 with the first save failing skips the last 25 | low | Needs a failing store plus an exact repeat; the UI then rolls back to the stored value. Rejected. |
| 4 | edge | Damaged pending JSON could carry an out-of-range quiet time | low | Only our own encoder writes it. Rejected. |
| 5 | verif | Failed quiet-time save rollback untested | medium (gap) | Patch: view-model test (25 shown, back to 20, logged). |
| 6 | verif+blind | Editor `testSettings()` read-failure path untested | low (gap) | Deferred. |
| 7 | blind | PRD UJ1 still says 6:39 after the snooze became 10 min; other docs still show 9 min or per-alarm quiet time (EXPERIENCE flows, copy-to-approve, planning PRD copy, epics criteria, FR-SET-1) | low | Direct doc corrections. Patch. |
| 8 | blind | 30-min snooze vs the 30-min timers | false | `snoozed()` clears the interaction deadline (`withoutTimers`), `SnoozedRules` restarts it at the snooze end, and promotion skips a session still in progress after SETTLE. |
| 9 | blind | Snooze change bundled without a spec | false | Owner decision 2026-10-30, this spec's intent. |
| 10 | blind | `GlobalSettings.valueOf` has a catch-all `else` | low | A future global field would read as max snoozes. Direct correction: exhaustive `when`. Patch. |
| 11 | blind | Old per-alarm GraceSeconds pending rows never cleaned | low | Harmless dead path. Rejected. |
| 12 | blind | "Vibrate during quiet time" unlocked without a decision | false | Existing rule (never a locked field) kept, recorded in the intent. |
| 13 | blind | "No Quiet time row" test renders the simplified editor only | low | Full-editor rows covered by the view-model test. Rejected. |
| 14 | blind | `editor_` prefixed strings used by Settings; plain string instead of plural | low | Rejected. |
| 15 | blind | 9 -> 10 lengthens existing snoozes without the lock | false | Owner decision; snooze length was never locked. |

## Design Notes

Chosen approach (document in the PR): the session reads the global values; the per-alarm columns stay (no column drop, no data rewrite) and are ignored. Leftover per-alarm `GraceSeconds` pending rows are ignored by the resolver and may still be promoted into the unused column, which is harmless.

## Verification

**Environment (company PC, no admin):** worktree `C:/Users/BuiTua/Personal/yp-017b`, branch `feat/0-1-7-settings-rules` (based on the 0.1.7 polish branch; the caller rebases on main later); `JAVA_HOME=C:/Users/BuiTua/AppData/Local/Programs/jdk17/jdk-17.0.20.1+1`; RAM is tight: run ONE Gradle build at a time, targeted tasks, `./gradlew --stop` when done; no adb, no phone; never edit `C:/Users/BuiTua/Personal/yawn-and-pawn` or `C:/Users/BuiTua/Personal/yp-017`; do not commit, push or open a PR (the caller does).

**Commands:**
- `./gradlew :core:allTests :data:testAndroidHostTest :composeApp:testAndroidHostTest` (or the module's host test task names) -- pass
- `./gradlew :androidApp:testDebugUnitTest --tests <changed classes>` and `recordRoborazziDebug -Proborazzi.test.verify=false` for changed screens -- pass
- `./gradlew qualityGate` -- pass
