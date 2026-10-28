---
title: 'Story 7.11: Skip the next alarm from a pre-alarm notification'
type: 'feature'
created: '2026-10-28'
status: 'review'
baseline_revision: 'bb13190a3b006d30ae75fdebcde73f6819075abe'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-7-context.md'
  - '{project-root}/.claude/skills/pps-design/SKILL.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-6-8-weekly-summary.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** A user whose plans changed must switch an alarm off and remember to switch it back on (FR-ALM-10). The epic promises an optional notification 2 h before an alarm that skips that one morning, logged as Skipped and free.

**Approach:**
- **Storage:** `Alarm` gains `offerSkip` (default off) and `skippedDate` (a local date).
- **Next ring:** a core rule skips the occurrence whose local date is `skippedDate`. Scheduling, the Home countdown and the editor header all use it.
- **Notification:** a `BackgroundWork` one-time job `pre-alarm-{alarmId}` posts "{time} alarm" / "Rings in {h} h {min} min" with "Skip this one".
- **Skipping:** the action opens the app to a confirm dialog. Confirming runs a guarded `SkipNextAlarm` use case: it stores the skipped date (or disables a one-time alarm), re-syncs scheduling, and has `SessionRecorder` write one Skipped history row with no ring.
- **Undo:** Home's card shows "Next ring skipped" with a "Don't skip" menu item, which undoes all of that.
- **Lock:** turning the switch on is a weakening change under the commitment lock.

## Boundaries & Constraints

**Always:**
- **Copy:** all strings are verbatim from EXPERIENCE.md Key strings:
  - "Offer to skip 2 h before"
  - channel "Upcoming alarms"
  - action "Skip this one"
  - "Skip your {time} alarm? This is logged." with "Skip" / "Keep it" ("Keep it" is the default dismiss)
  - "Skipped. No charge."
  - "Next ring skipped"
  - "Don't skip"
  - notification text "Rings in {hours} h {minutes} min", using the Home countdown wording
  - "{time} alarm" as the title is the epic's text, marked (EXPERIENCE.md Key strings) and listed for the owner.
- **`app.db` v10 to v11, one migration:**
  - `alarm.offer_skip INTEGER NOT NULL DEFAULT 0`;
  - `alarm.skipped_date TEXT` (ISO local date, nullable);
  - `session_history.first_ring_at` made nullable through a table rebuild that keeps every row and the `scheduled_at` index.
  - The schema `11.json` is exported. Migration tests keep existing alarms and history.
  - If another Epic 7 schema story merges first, this migration takes the next free version.
- **Skipped date is a local date** (`TimeZoneProvider` zone at skip time). After a time-zone change, the occurrence on that local date is still skipped. The rule must handle DST days.
- **Turning `offerSkip` on** within 8 h of that alarm is stored as a weakening `PendingChange` effective after the next occurrence. The editor shows the existing note "Saved. Takes effect after tomorrow's {time} alarm.". Turning it off applies at once. It is a new `LockedField` / `SettingValue`, promoted like the others.
- **Pre-alarm job:**
  - Enqueued only when the alarm is enabled, `offerSkip` is effective and a next ring exists.
  - Timing: delay = (next ring − 2 h − now), coerced to ≥ 0, with REPLACE.
  - Re-synced on every alarm save, enable or disable, `rescheduleAll()`, after a ring (re-arm), and after skip or undo.
  - Cancelled (job and any posted notification) when the alarm is disabled, deleted or edited with the switch off, and when that occurrence starts ringing.
  - When the worker runs at or after the occurrence, it posts nothing.
  - It is never scheduled through `AlarmScheduler`, and nothing runs on the wake path except the cancel.
- **Notification:**
  - Channel "Upcoming alarms", default importance, the monochrome `ic_stat_alarm`.
  - Posted only with `POST_NOTIFICATIONS` granted and notifications enabled. It never asks for the permission.
  - The action `PendingIntent` opens `MainActivity` with the alarm id. The app then shows the dialog over Home.
- **Skip:**
  - Allowed inside the lock window, confirmed and logged by the history row.
  - Guarded by `SessionLockGuard.whenIdle`: during a session, only the session panel shows, and nothing is skipped or dropped silently. The notification stays.
  - Idempotent: an occurrence already skipped is not skipped again, and no second row is written.
  - The Skipped row has a new session id, `scheduled_at` = the skipped occurrence, `first_ring_at` null, outcome Skipped, and no snooze or check data. It never counts toward rates or streaks (the existing `isCounted`).
- **"Don't skip":**
  - It strengthens, so it is always allowed when no session runs.
  - It clears `skippedDate`, re-arms the system alarm and the pre-alarm job, and has `SessionRecorder` delete that Skipped row.
  - `SessionRecorder` stays the only history writer. The writer-scan test is updated to allow the new delete.

**Never:**
- No change to the Day detail or calendar designs: Skipped rendering already exists.
- No new permission.
- No price, billing or session-engine change.
- No direct clock or zone reads (`NoDirectTimeAccess`).
- Skipping is never charged and never needs unlock.
- No House Hunt or audio work.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Notification time | Repeating alarm 07:00, switch on, now 01:00 | job delay 4 h (05:00) | — |
| Window started | now 06:00, occurrence 07:00 | job delay 0: posts at once | — |
| Late worker | worker runs 07:01 | posts nothing | — |
| Skip repeating | confirm Skip for Tue 07:00 | skippedDate = Tue; system alarm armed for Wed 07:00; one Skipped row, scheduledAt Tue 07:00, firstRingAt null; snackbar "Skipped. No charge." | storage failure: nothing half-applied, error logged, no snackbar |
| Skip one-time | one-time alarm | alarm disabled, system alarm cancelled, Skipped row | — |
| Skip twice | same occurrence skipped again | no change, no second row | — |
| Time-zone change | skipped Tue (Helsinki), zone becomes London | the Tue occurrence in the new zone is still skipped | — |
| DST day | occurrence on a DST change date | the skip matches the local date; the following occurrence is correct | — |
| Don't skip | card menu on a skipped alarm | skippedDate cleared, Tue 07:00 re-armed, Skipped row deleted, pre-alarm job re-synced | — |
| During a session | action tapped while a session runs | only the session panel; nothing skipped | — |
| Switch on in lock window | alarm within 8 h, switch turned on | PendingChange; editor note "Saved. Takes effect after tomorrow's {time} alarm."; no job until promoted | — |
| Switch off | switch turned off | applied now; job and notification cancelled | — |

</intent-contract>

## Code Map

- `data/src/commonMain/kotlin/com/yawnandpawn/app/data/db/AppDatabase.kt` -- `SCHEMA_VERSION = 10` (l.77, KDoc history l.24-35). Migrations are in `data/.../db/AppDatabaseMigrations.kt` (`MIGRATION_4_5` l.69 adds an alarm column; list `APP_DATABASE_MIGRATIONS` l.175). Schemas are in `data/schemas/com.yawnandpawn.app.data.db.AppDatabase/`. Tests are in `data/src/androidHostTest/.../db/AppDatabaseFactoryTest.kt` (`schema(N)` l.57, migration pattern l.297-313, versions pin l.487-493, `createDatabase` l.581, `toVersion3Insert` l.629). `PpsBackupAgent.kt:71` guards restore by `SCHEMA_VERSION`.
- `data/.../alarm/AlarmEntity.kt`, `AlarmMapping.kt` -- the alarm table and its mapping. `data/.../history/SessionHistoryEntity.kt` has `first_ring_at` NOT NULL (l.24). `SessionHistoryDao.kt` and `SessionHistoryMapping.kt` (`"Skipped"` l.56). `data/src/androidHostTest/.../history/SessionHistoryWriterScanTest.kt:33-46` pins the writers.
- `core/.../alarm/Alarm.kt` -- `Alarm` (l.19-38), `hasSameSettingsAs` (l.103). `AlarmUseCases.kt`: `AlarmDraft`/`toAlarm` (l.43-60, 320-342), `SaveAlarm.storeEdit` (l.189-237), `SetAlarmEnabled` (l.355-383), `DeleteAlarm` (l.393-418). `AlarmOccurrence.kt`: `nextOccurrence`, `durationUntil` (l.24-43). `AlarmScheduling.kt`: `sync` l.44, `cancel` l.49, `rescheduleAll` l.68-91, `syncAfter` l.110-119 (the skip rule goes here), `disablePassed` l.130. `AlarmFiredHandler.kt`: `RearmOnFire.switchOff` l.79-94. `androidApp/.../wake/WakeAlarmFiredHandler.kt:50` is the hook where the occurrence starts.
- `core/.../config/PendingChange.kt` (`LockedField`, `SettingValue`), `ChangeKind.kt` (`classify`), `CommitmentRules.kt:54-73` (`decide`), `PromotePendingChanges.kt:72-99`, `ConfigPorts.kt:94-109` (`CommitmentAction`) -- lock machinery for the new locked switch.
- `core/.../work/BackgroundWork.kt` -- port (`BackgroundTaskKind`, `BackgroundJob`, `SchedulePolicy`, `scheduleWeeklySummary`/`cancelWeeklySummary`). Adapter `androidApp/.../android/work/AndroidBackgroundWork.kt` (`scheduleWeeklySummary` l.54-75 pattern, `guarded` l.92-105), `BackgroundTaskWorker.kt` (`KEY_TASK`), `WorkModule.kt` (tasks l.35-43). The pattern to follow: `core/.../summary/WeeklySummaryScheduler.kt` and `androidApp/.../summary/AndroidWeeklySummaryNotifier.kt` (`canPost` l.41-46, `ensureChannel` l.110-114, `ic_stat_alarm`, PendingIntent to `MainActivity`). Channel names are in `androidApp/src/main/res/values/strings.xml:5-12`.
- `core/.../history/SessionHistory.kt` -- `SessionOutcome.Skipped` exists (l.9-24, "nothing produces it"); `SessionHistoryRow.firstRingAt: Instant` (l.45); the repository port (l.60-94) has no delete-one. `core/.../session/SessionRecorder.kt` is the only writer. `firstRingAt` is read as non-null in `core/.../stats/ReRegisterSuggestion.kt:76-80`, `composeApp/.../daydetail/DayDetailMapping.kt:106` and `SessionRecorder.kt:33,76`. Stats `isCounted` (`core/.../stats/WakeStats.kt:45-49`) already excludes Skipped.
- `composeApp/.../ui/home/HomeContract.kt` (`AlarmCard` l.21-28) and `HomeScreen.kt` (`AlarmCardView` l.330-384, menu l.373-382, `cardActions` l.524, `CardTexts` l.503-521, `DisableDialog` l.465-498 as the dialog pattern). `HomeViewModel.kt` has `nextAlarm` (l.494-500) and the cards (l.619). Snackbar: `HomeSnackbar.kt`. The session gate is in `AppNavHost.kt` (l.50-55, 75-84, 151-162). Open requests: `composeApp/.../ui/nav/AppOpenRequests.kt`, consumed after `shownStack` (`AppNavHost.kt:84`). `MainActivity.kt` handles intents (`onCreate` l.113, `onNewIntent` l.121, `openRequestedTab` l.127-131). `ConfirmDialog.kt:15`.
- `composeApp/.../ui/editor/AlarmEditorContract.kt` (`EditorForm` l.26, `VibrateInGraceToggled` l.326), `AlarmEditorViewModel.kt` (toggles l.331, `save` l.704-733, `toDraft` l.926, header l.997, weakening note l.718 via `weakeningNoteOf`), and `AlarmEditorScreen.kt` (`NameSoundCard` l.337-369, which holds the Vibration `SwitchRow` l.361).
- Fakes in `testing/src/commonMain/.../testing/`: `TimeFakes.kt` (`FakeClock`, `FakeTimeZoneProvider`), `SchedulerFakes.kt` (`FakeAlarmScheduler` l.63, `AlarmUseCasesFixture` l.164), `PriceFakes.kt` (`FakeBackgroundWork` l.149), `AlarmFakes.kt`, `FakeSessionHistoryRepository.kt`, `ConfigFakes.kt`, `WeeklySummaryFakes.kt`.

## Tasks & Acceptance

**Execution:**
- **Core alarm:**
  - `Alarm` gets `offerSkip` and `skippedDate: LocalDate?`.
  - A pure `nextRing(alarm, now, zone)` returns the next occurrence whose local date ≠ `skippedDate`.
  - `syncAfter` uses it, and so do the Home countdown, the card caption and the editor header.
  - `RearmOnFire` and `disablePassed` are unchanged except that they go through `nextRing`.
- **Core lock:** `LockedField.OfferSkip` and `SettingValue.OfferSkip(on)` (serial name stable). Add branches in `classify` (off→on weakens, on→off strengthens), in `promoteOne`, in `storeEdit`, and in the editor's `PendingFields`.
- **Core pre-alarm:**
  - A `PreAlarmScheduler` (sync per alarm, cancel per alarm id, and a sync-all from `rescheduleAll`) on new `BackgroundWork` methods: `schedulePreAlarm(alarmId, delay)` with REPLACE, and `cancelPreAlarm(alarmId)`. Add the `BackgroundTaskKind.PreAlarm` task.
  - A `PreAlarmNotifier` port with `canPost`, `post(alarmId, time, ringsIn)` and `cancel(alarmId)`.
  - `PreAlarmTask` reads the alarm and posts only when now < occurrence.
- **Core skip:**
  - `SkipNextAlarm(alarmId)` and `UndoSkip(alarmId)` use cases (guarded, idempotent).
  - `SessionRecorder.recordSkip(alarm, occurrence)` and `discardSkip(alarmId, scheduledAt)`.
  - The repository gains `deleteSkipped(alarmId, scheduledAt)`.
  - `SessionHistoryRow.firstRingAt` becomes nullable; update its readers.
- **Data:**
  - Entity fields, mapping, DAO delete, `MIGRATION_10_11` (with the history table rebuild), `SCHEMA_VERSION = 11`, schema `11.json`.
  - Migration and schema tests; the writer-scan update.
- **androidApp:**
  - The adapter methods with unique work `pre-alarm-{alarmId}`, and a worker input for the alarm id.
  - `AndroidPreAlarmNotifier` with channel "Upcoming alarms", id per alarm, and the action PendingIntent to `MainActivity` with the alarm id.
  - `MainActivity` turns that intent into a skip request.
  - Cancel the notification in `WakeAlarmFiredHandler`.
  - Koin wiring.
- **composeApp:**
  - A `SkipRequests` holder like `AppOpenRequests`. `AppNavHost` shows the `ConfirmDialog` over Home only when no session runs and onboarding is done; otherwise it drops the request.
  - Confirm runs `SkipNextAlarm`, then the snackbar "Skipped. No charge.".
  - Home `AlarmCard.skipped`: the caption "Next ring skipped" replaces the card's summary line, and the menu and TalkBack action "Don't skip" run `UndoSkip`.
  - The editor's "Offer to skip 2 h before" `SwitchRow` goes in card 1 after "Vibration", with its lock note through the existing weakening path.
- **Strings:** new compose resources and Android channel/action strings, plus `CopyRulesTest`.
- **Tests:**
  - Core: `FakeClock`, `FakeTimeZoneProvider`, `FakeAlarmScheduler`, `FakeBackgroundWork` covering every matrix row.
  - Data: migration tests.
  - androidApp: the worker/notifier tests modelled on `WeeklySummaryWorkTest`.
  - composeApp: ViewModel tests for Home, the editor and the dialog.
  - Roborazzi: the editor switch, the confirm dialog and a "Next ring skipped" card, in Light, Dark and 200%.

**Acceptance Criteria:**
- Given an enabled alarm with the switch on, when time reaches occurrence − 2 h, then the notification shows; and "Skip this one" → "Skip" prevents that ring, logs one Skipped row and shows "Skipped. No charge.".
- Given a skipped occurrence, when Home renders, then the card says "Next ring skipped", the countdown targets the following occurrence, and "Don't skip" restores the ring and removes the row.
- Given the pps-design Done checklist, when the story is done, then every item is ticked below, `CopyRulesTest` passes and `./gradlew qualityGate` passes in CI.

## Design Notes

- **Not previewed (owner review list):** the editor switch, the skip dialog, the card caption with its "Don't skip" menu, and the notification. They are built in the existing styles:
  - the switch: `SwitchRow` like "Vibration", in card 1;
  - the dialog: `ConfirmDialog` like the disable dialog;
  - the caption: the card's secondary line;
  - the menu: the card's existing `PpsMenu`.
- `skippedDate` is not cleared after the skipped day passes. A past date never matches a future occurrence, so it is harmless, and "Don't skip" is only offered while it matches the next raw occurrence.
- A skipped one-time alarm is simply disabled. Its card shows it off, with no "Next ring skipped" and no "Don't skip". Switching it back on is the normal enable, and its Skipped row stays as the log.
- **Notification id:** use a stable per-alarm id derived from the alarm's `requestCode` (offset range distinct from the weekly summary's `1_015` and the wake notification ids).

## pps-design Done checklist

- [x] Only DESIGN.md tokens (the existing `SwitchRow`, `ConfirmDialog`, card caption style and `PpsMenu`; no new token)
- [x] Light and Dark checked with screenshots (`SkipNextAlarmScreenshotTest`)
- [x] Every colour pair is in the contrast table (no new pair: caption in `text-secondary` on glass, the existing dialog and switch)
- [x] Touch targets ≥ 48 dp (existing `SwitchRow`, dialog buttons and menu items)
- [x] Works at 200% font scale and with TalkBack (card action "Don't skip" as a custom action; 200% baselines recorded)
- [x] Reduced-motion path works (no animation added)
- [x] Copy matches EXPERIENCE.md, strings in resources (`CopyRulesTest` pins every new string)
- [x] State rows handled (skipped / not skipped card, dialog, snackbar, failure logged with no snackbar, session lock)
- [x] "I'm up" prominence (not applicable)
- [x] Previews and screenshot tests updated (`HomeSkipPreview.kt`, `AlarmEditorPreview.kt`; 9 new baselines)

## Verification

**Commands:**
- `./gradlew :core:jvmTest` (targeted to alarm, config, session, work, history, stats) -- expected: pass
- `./gradlew :data:testAndroidHostTest --tests "*AppDatabaseFactory*" --tests "*SessionHistory*" --tests "*Alarm*"` -- expected: pass
- `./gradlew :composeApp:testAndroidHostTest` -- expected: pass
- `./gradlew :androidApp:testDebugUnitTest` (targeted: work, notification, home, editor, screenshots) -- expected: pass
- `./gradlew qualityGate` (CI) -- expected: pass

## Spec Change Log

**As built (Story 7.11, base bb13190a).** `app.db` took v11 (no other Epic 7 schema story had merged).

| Spec | As built | Why |
|---|---|---|
| `PreAlarmNotifier.post(alarmId, time, ringsIn)`, `cancel(alarmId)` | `post(alarm, ringsIn)`, `cancel(requestCode)` | The notification id and the action's PendingIntent request code are `100_000 + requestCode` (Design Notes); the alarm carries id, time and code. |
| A `PreAlarmScheduler` sync-all from `rescheduleAll()` | `AlarmScheduling` calls `PreAlarmScheduler.sync` inside `syncAfter` (and `cancel(requestCode, alarmId)`) | Every system-alarm change (save, switch, delete, `rescheduleAll`, the re-arm after a ring, skip and undo) re-syncs the job with no second code path. `PromotePendingChanges` also syncs a promoted "Offer to skip" (the wake service promotes without a `rescheduleAll`). |
| Worker input for the alarm id | `BackgroundTaskWorker.KEY_ALARM_ID` and a `KeyedBackgroundTask` port (`BackgroundTasks.keyed`) | `BackgroundTask.run()` takes no input. |
| `recordSkip(alarm, occurrence)` | `recordSkip(sessionId, alarmId, scheduledAt, skippedAt)` | `SkipNextAlarm` owns the id generator; `endedAt` = the time of the skip. |
| `SkipNextAlarm` order | Skipped row first, then the alarm (a failed alarm write deletes the row again) | Nothing half-applied. `UndoSkip` deletes the row(s) first, then clears the date (a failed alarm write puts the rows back). |
| `UndoSkip` row lookup | The alarm's Skipped rows still to come, or on the skipped date in the current zone | So an edited time or a zone change still finds the row (`deleteSkipped` is exact). |
| Skip dialog in `AppNavHost` | `AppNavHost` takes the request (`SkipRequested`, after `shownStack`) and selects Home only past the session lock, onboarding and the editor; Home's ViewModel shows the dialog for an enabled alarm and runs the use case | Home owns the snackbar and the ViewModel tests. |
| — | The editor switch shows only where `FullEditorSections.showOfferSkip` (the app's editor) | The round 2 and 3 design-preview baselines stay unchanged. |
| — | An edit keeps the skip of a repeating alarm (dropped when it becomes one-time); Duplicate never copies it; `hasSameSettingsAs` includes "Offer to skip" | Defaults taken. |
| — | The tap on the notification body opens the app (no skip); `setTimeoutAfter` removes it at the ring | Defaults taken; only the action skips. |

**Owner review list (not previewed):** the editor switch, the skip dialog, the card caption with "Don't skip", and the notification ("{time} alarm" title is the epic's text; the body "Rings in {h} h {min} min" / "Rings in {min} min" reuses the Home countdown strings).

**Verified locally:** `:core:jvmTest` (alarm, config, session, stats, history, summary, billing), `:data:testAndroidHostTest` (AppDatabaseFactory, SessionHistory, Alarm, scan tests), `:composeApp:testAndroidHostTest` (all), `:androidApp:testDebugUnitTest` (targeted: work, pre-alarm, skip, Home, editor, wiring, backup, wake, MainActivity; Home and editor baselines verify unchanged), `spotlessApply`, `detekt`. `qualityGate` is left to CI.
