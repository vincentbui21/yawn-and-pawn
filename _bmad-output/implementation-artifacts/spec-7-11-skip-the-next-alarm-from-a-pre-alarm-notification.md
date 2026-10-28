---
title: 'Story 7.11: Skip the next alarm from a pre-alarm notification'
type: 'feature'
created: '2026-10-28'
status: 'done'
baseline_revision: 'c3fcc6180672c126ba87344695b880588545d949'
review_loop_iteration: 1
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-7-context.md'
  - '{project-root}/.claude/skills/pps-design/SKILL.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-6-8-weekly-summary.md'
warnings: ['oversized']
deferred:
  - summary: >-
      The pre-alarm notification text is fixed at post time and can go stale.
    evidence: |-
      AndroidPreAlarmNotifier posts "Rings in 1 h 59 min" with setShowWhen(false) and never updates it; a chronometer or the absolute time would be a copy or design change for the owner.
    location: >-
      androidApp/src/main/kotlin/com/yawnandpawn/app/android/prealarm/AndroidPreAlarmNotifier.kt
    severity: low
  - summary: >-
      A blocked "Upcoming alarms" channel drops the post silently.
    evidence: |-
      canPost() checks app notifications and POST_NOTIFICATIONS, not the channel importance; same pattern as the weekly summary notifier.
    location: >-
      androidApp/src/main/kotlin/com/yawnandpawn/app/android/prealarm/AndroidPreAlarmNotifier.kt
    severity: low
  - summary: >-
      "Delete all data" does not cancel pre-alarm jobs or remove a posted pre-alarm notification.
    evidence: |-
      DeleteAllData cancels system alarms on AlarmScheduler only; the notification times out at the ring and the job is a NotFound no-op.
    location: >-
      core/src/commonMain/kotlin/com/yawnandpawn/app/core/reset/DeleteAllData.kt
    severity: low
  - summary: >-
      A re-sync inside the 2 h window (app start, a save) re-posts a notification the user dismissed.
    evidence: |-
      PreAlarmScheduler.sync enqueues with delay 0 and REPLACE; nothing remembers that the occurrence was already posted.
    location: >-
      core/src/commonMain/kotlin/com/yawnandpawn/app/core/alarm/PreAlarm.kt
    severity: low
  - summary: >-
      The commitment lock window and its note still use nextOccurrence, so a locked change waits for a skipped ring.
    evidence: |-
      LockWindow.of uses nextOccurrence; the intent routes only scheduling, the Home countdown and the editor header through nextRing. The change is promoted at the next rescheduleAll (app start, boot, time change).
    location: >-
      core/src/commonMain/kotlin/com/yawnandpawn/app/core/config/LockWindow.kt
    severity: medium
  - summary: >-
      No test pins the wake fire handler's pre-alarm cancel or the app wiring of preAlarms into PromotePendingChanges.
    evidence: |-
      WakeAlarmFiredHandlerTest builds the handler without preAlarms; OfferSkipLockTest builds PromotePendingChanges itself. The re-arm sync and setTimeoutAfter cover the visible effect.
    location: >-
      androidApp/src/main/kotlin/com/yawnandpawn/app/android/wake/WakeAlarmFiredHandler.kt
    severity: low
  - summary: >-
      UndoSkip's put-back of already removed rows on a later failure has no test.
    evidence: |-
      Both history fakes fail every delete, so the failure happens at the first row; a fake failing on the Nth delete is needed.
    location: >-
      core/src/commonMain/kotlin/com/yawnandpawn/app/core/alarm/SkipNextAlarm.kt
    severity: low
  - summary: >-
      The staleSkips argument of SaveAlarm and SetAlarmEnabled is optional, so losing its Koin wiring would pass every test.
    evidence: |-
      AlarmWiringTest only resolves the use cases; behaviour tests build them through AlarmUseCasesFixture.
    location: >-
      androidApp/src/main/kotlin/com/yawnandpawn/app/YawnAndPawnApp.kt
    severity: low
  - summary: >-
      The weekly summary tap's request code 1015 can equal an alarm's request code (pre-existing, Story 6.8).
    evidence: |-
      AndroidAlarmScheduler's show intent uses the alarm request code on the same activity; filterEquals ignores extras, so the Progress extra can be replaced.
    location: >-
      androidApp/src/main/kotlin/com/yawnandpawn/app/android/summary/AndroidWeeklySummaryNotifier.kt
    severity: low
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
  - Entity fields, mapping, DAO delete, `MIGRATION_12_13` (with the history table rebuild), `SCHEMA_VERSION = 13`, schema `13.json` (renumbered: 7.1 took v11, 7.2 took v12).
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

## Review Triage Log

### 2026-10-28 — Review pass
- verdicts: 41 findings — high 0, medium 9, low 26, false 6, maybe-false 0
- findings:
  - `[medium]` `[patch]` (blind 1) Skip recomputes the occurrence at confirm, so a stale dialog skips the following ring — `SkipDialog.occurrence` + `SkipNextAlarm(alarmId, expected)`; Home test "Skip confirmed after the asked-about ring has passed skips nothing" and a core test.
  - `[medium]` `[patch]` (blind 2) Skip ignores "Offer to skip", bypassing the lock via the exported activity — `!stored.offerSkip -> NothingToSkip`, and `askSkip` checks it; core and Home tests added.
  - `[medium]` `[bad_spec]` (blind 3) Skipped rows orphaned on re-enabling a one-time alarm or an edit to one-time — Spec amended (change log, review pass 1): `DiscardStaleSkips` after `SaveAlarm`/`SetAlarmEnabled`; two Home tests. A repeat-day change keeps a row on the skipped date (harmless); a delete keeps the row as log.
  - `[low]` `[reject]` (blind 4) A failed skip shows no feedback — The intent's matrix says "error logged, no snackbar".
  - `[low]` `[patch]` (blind 5) UndoSkip multi-row partial failure — `discardAll` puts back the rows already removed.
  - `[low]` `[defer]` (blind 6) Notification text goes stale — The intent fixes this copy; a chronometer is a design change for the owner.
  - `[low]` `[patch]` (blind 7) KDoc says schema v11 — Now v13 in `AlarmEntity` and `SessionHistoryEntity`.
  - `[low]` `[defer]` (blind 8) No test for the fire handler's pre-alarm cancel — The re-arm sync and `setTimeoutAfter` cover the visible effect.
  - `[low]` `[patch]` (blind 9) `textOf` DaysHours branch drops minutes — Whole hours and minutes now come from the duration.
  - `[low]` `[patch]` (blind 10) Light and Dark previews show different states — Dark now shows the snackbar too.
  - `[low]` `[reject]` (blind 11) The editor never says the next ring is skipped — The intent only routes the header through `nextRing`; Home carries the caption and the undo.
  - `[low]` `[defer]` (blind 12) A blocked "Upcoming alarms" channel drops posts silently — Same pattern as the weekly summary notifier.
  - `[low]` `[defer]` (edge 1) "Delete all data" leaves pre-alarm jobs and notifications — The notification times out at the ring; the job is a NotFound no-op.
  - `[low]` `[defer]` (edge 2) A re-sync inside the 2 h window re-posts a dismissed notification — Needs a remembered posted occurrence.
  - `[medium]` `[bad_spec]` (edge 3) A one-time skip then re-enable keeps the row — Same root cause as blind 3, fixed there.
  - `[medium]` `[bad_spec]` (edge 4) An edit no longer reaching the skipped date leaves an orphan row — Same root cause as blind 3 (to one-time fixed; a repeat-day change keeps a row on the skipped date).
  - `[medium]` `[patch]` (edge 5) A skip without live offerSkip bypasses the lock — Same as blind 2.
  - `[low]` `[reject]` (edge 6) A failed re-arm after a skip still rings — The never-silent direction; scheduler failures are logged and rare.
  - `[medium]` `[defer]` (edge 7) `LockWindow` uses `nextOccurrence`, so a lock waits for a skipped ring — The intent lists only scheduling, the countdown and the header for `nextRing`; the stricter path, promoted at `rescheduleAll`.
  - `[low]` `[reject]` (edge 8) Home left open past the skipped ring keeps a stale caption — Refreshed on the next tick or resume; rare.
  - `[low]` `[patch]` (edge 9) `REQUEST_OPEN_APP` 1018 can equal an alarm request code — Now -1018.
  - `[low]` `[patch]` (edge 10) UndoSkip half-applied on a later failure — Same as blind 5.
  - `[medium]` `[bad_spec]` (edge 11) A one-time skip cannot be undone — Same root cause as blind 3: switching it back on now removes the row.
  - `[false]` `[reject]` (edge 12) `PreAlarmNotifier` signature differs from the spec — The Spec Change Log records `post(alarm, ringsIn)` / `cancel(requestCode)`.
  - `[false]` `[reject]` (edge 13) `recordSkip` signature differs from the spec — Recorded in the Spec Change Log.
  - `[low]` `[patch]` (verification 1) DeleteAlarm's pre-alarm cancel untested — Home test "deleting an alarm cancels its pre-alarm job and notification".
  - `[low]` `[defer]` (verification 2) "Delete all data" pre-alarm cleanup — Same as edge 1.
  - `[low]` `[patch]` (verification 3) `openRequested` Boolean never asserted — `AppOpenRequestsTest` asserts true and false.
  - `[low]` `[defer]` (verification 4) App wiring of `preAlarms` into `PromotePendingChanges` untested — One-argument wiring; the core logic is tested.
  - `[low]` `[defer]` (verification 5) Fire handler cancel untested — Same as blind 8.
  - `[low]` `[patch]` (verification 6) A failed "Don't skip" showing "Couldn't save" untested — Home test added.
  - `[low]` `[patch]` (verification other) KDoc v11 — Same as blind 7.
  - `[false]` `[reject]` (intent A) Which occurrence a skip takes — Resolved by the blind 1 patch (the dialog's occurrence).
  - `[false]` `[reject]` (intent B) One-time undo — Resolved with blind 3.
  - `[low]` `[reject]` (intent C) Notification removed at the fire when the session is that alarm's — The occurrence is ringing, so nothing is left to skip.
  - `[low]` `[reject]` (intent D) Request dropped with the editor or the charge form open — Documented in the Spec Change Log; the notification stays.
  - `[false]` `[reject]` (intent E) Under-an-hour wording — The intent says "using the Home countdown wording".
  - `[false]` `[reject]` (intent F) Late worker reading — The matrix rows pass.
  - `[medium]` `[defer]` (intent G) Lock window over a skipped occurrence — Same as edge 7.
  - `[low]` `[reject]` (intent H) Undo deletes a wider row set — Only this alarm's Skipped rows still to come or on the skipped date; with stale rows now removed, that is the skip's row.
  - `[low]` `[reject]` (intent 7) After a zone change the row keeps the old zone's instant — The logged date is still the skipped morning; rare.

### 2026-10-28 — Review pass (after the bad_spec loopback)
- verdicts: 22 findings — high 0, medium 1, low 19, false 2, maybe-false 0 (duplicates across layers grouped per row)
- findings:
  - `[medium]` `[patch]` (blind 1, edge 1, verification 1) An identical new alarm switching on a skipped one-time alarm (`SaveAlarm.switchOn`) skipped `DiscardStaleSkips` — `switchOn` now runs it; Home test "a new alarm identical to a skipped one-time alarm switches it on, and its Skipped row goes".
  - `[low]` `[patch]` (blind 2, edge 3) An edit that removes the skipped weekday keeps the skip and its row — `storeEdit` keeps `skippedDate` only while its weekday is still in the repeat days, so the stale-row cleanup runs; Home test added.
  - `[low]` `[reject]` (blind 3) No feedback when a confirmed Skip turns out to be NothingToSkip — The dialog closes and the card shows the true state; the intent asks for no message there.
  - `[low]` `[reject]` (blind 4) Nullable `expected` defaults keep an unguarded path — Only tests and the preview omit it; the app path always passes it.
  - `[low]` `[patch]` (blind 5, verification 2) `DiscardStaleSkips` keep rules unpinned — Home test "a skipped daily alarm switched off and on keeps its skip and its row".
  - `[low]` `[defer]` (blind 6, verification 3) `UndoSkip` multi-row rollback untested — Needs a fake that fails on the Nth delete; only reachable after an earlier cleanup failure.
  - `[low]` `[patch]` (blind 7, intent b) Test name claims the pending-lock case it does not drive — Renamed: the lock keeps the stored value off, which is what the test stores.
  - `[low]` `[reject]` (blind 8) Notifier `DaysHours` text and the negative request code have no test — The branch is unreachable within the 2 h lead; the code is a constant.
  - `[low]` `[reject]` (blind 9) `AppDatabaseSkipTest` copies the v12 helpers — Test-only duplication, split for detekt LargeClass; acceptable.
  - `[low]` `[patch]` (blind 10) Home KDoc still says only "when it is on" — Now names "Offer to skip".
  - `[low]` `[patch]` (blind 11) `askSkip` uses `nextRing(rule, null, ...)` instead of `nextOccurrence` — Uses `nextOccurrence`, the value `SkipNextAlarm` compares with.
  - `[low]` `[patch]` (blind 12) `DiscardStaleSkips.after` parameter `after` shadows the function — Renamed to `stored`; `history.all()` cost rejected (one read per re-enable or skip-changing edit).
  - `[low]` `[reject]` (blind 13) No preview of the resting skipped card — The Roborazzi baselines `home_ring_skipped_*` show it.
  - `[low]` `[reject]` (blind 14) Delete test lives in HomeSkipTest — Cosmetic.
  - `[low]` `[patch]` (edge 2) Expected occurrence passed while the following one is skipped returns AlreadySkipped ("Skipped. No charge.") — The expected check now comes first: NothingToSkip.
  - `[low]` `[reject]` (edge 4) A zone change then off/on can drop a row whose date shifted — Only when the shift crosses midnight; rare.
  - `[low]` `[patch]` (edge 5) A cancelled save could stop the stale-row loop midway — `withContext(NonCancellable)` around the cleanup in `storeEdit` and `switchOn`.
  - `[low]` `[defer]` (verification 4) `staleSkips` is optional, so dropping its Koin wiring passes every test — Making it required touches every test construction; an `AlarmWiringTest` round trip can pin it later.
  - `[low]` `[defer]` (verification other) Weekly summary tap request code 1015 can equal an alarm request code — Pre-existing (Story 6.8), not this story's.
  - `[false]` `[reject]` (intent a) The occurrence is captured at dialog open, not from the notification — The notification times out at the ring (`setTimeoutAfter`), so a tap after the ring is not reachable in practice.
  - `[false]` `[reject]` (intent c) An edit keeping the date but changing the time leaves the row — The row's date still matches the skipped date, so that morning is still skipped and logged correctly.
  - `[low]` `[defer]` (intent f, g) Delete and open-to-dialog are checked on fakes and the navigation helper, not on WorkManager or a skip intent — Covered by `PreAlarmWorkTest` and `SkipRequestOpenTest` for the main paths; the rest is phone check 7.12.

## Verification

**Commands:**
- `./gradlew :core:jvmTest` (targeted to alarm, config, session, work, history, stats) -- expected: pass
- `./gradlew :data:testAndroidHostTest --tests "*AppDatabaseFactory*" --tests "*SessionHistory*" --tests "*Alarm*"` -- expected: pass
- `./gradlew :composeApp:testAndroidHostTest` -- expected: pass
- `./gradlew :androidApp:testDebugUnitTest` (targeted: work, notification, home, editor, screenshots) -- expected: pass
- `./gradlew qualityGate` (CI) -- expected: pass

## Spec Change Log

**As built (Story 7.11, base bb13190a).** `app.db` took v11 (no other Epic 7 schema story had merged).

**Rebased onto c3fcc618 (7.1 #101, 7.2 #102).** Story 7.1 took v11 (`recording`) and Story 7.2 took v12 (`alarm.motivation`), so this story's migration is `MIGRATION_12_13`, `SCHEMA_VERSION = 13`, exported `13.json`; the migration test starts from a v12 file and checks `motivation` is kept. `Alarm`, `AlarmDraft`, the entity, mapping, `hasSameSettingsAs` and the editor form carry both `motivation` and `offerSkip`; the production editor shows the Motivation row and the "Offer to skip" switch. The spec's baseline moved to c3fcc618.

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


**Review pass 1 amendments (2026-10-28).** Triggered by the review findings on orphan Skipped rows and on a skip that bypassed the lock. Amended, outside the intent contract:
- `SkipNextAlarm` skips only an alarm whose live "Offer to skip" is on (a pending turn-on under the lock skips nothing), and takes the occurrence the dialog asked about (`SkipDialog.occurrence`): confirmed after it passed, nothing is skipped. Known-bad state avoided: a skip of tomorrow's ring from a stale dialog, and a free skip of an alarm whose "Offer to skip" still waits under the commitment lock (any app can start the exported `MainActivity` with the skip extra).
- New `DiscardStaleSkips` (through `SessionRecorder`, the only history writer) runs after `SaveAlarm` and `SetAlarmEnabled`: once the alarm is on and was off, or its skipped date changed, its Skipped rows still to come whose local date is not the skipped date are removed. Design Notes' "its Skipped row stays as the log" now holds only while the one-time alarm stays off. Known-bad state avoided: a morning that rings logged as Skipped in Progress.
- `UndoSkip` puts back rows it already removed when a later removal fails (all-or-nothing).
- `REQUEST_OPEN_APP` is negative, so it never equals an alarm request code (the system alarm's show intent opens the same activity).
- KEEP: everything else as built (storage, `nextRing`, the pre-alarm job and notifier, the skip and undo flows, copy, screens and baselines, the v13 migration).


**Review pass 2 (2026-10-28).** Patches only: `SaveAlarm.switchOn` also runs `DiscardStaleSkips`; an edit drops the skip when its weekday leaves the repeat days; the stale-row cleanup runs non-cancellable; the expected-occurrence check comes before AlreadySkipped.

**Owner review list (not previewed):** the editor switch, the skip dialog, the card caption with "Don't skip", and the notification ("{time} alarm" title is the epic's text; the body "Rings in {h} h {min} min" / "Rings in {min} min" reuses the Home countdown strings).

**Verified locally:** `:core:jvmTest` (alarm, config, session, stats, history, summary, billing), `:data:testAndroidHostTest` (AppDatabaseFactory, SessionHistory, Alarm, scan tests), `:composeApp:testAndroidHostTest` (all), `:androidApp:testDebugUnitTest` (targeted: work, pre-alarm, skip, Home, editor, wiring, backup, wake, MainActivity; Home and editor baselines verify unchanged), `spotlessApply`, `detekt`. `qualityGate` is left to CI.

## Auto Run Result

Status: done

**Summary.** Story 7.11 resumed after the overnight stop: the WIP was committed, rebased onto 7.1 (#101) and 7.2 (#102), and the `app.db` migration renumbered to v12 to v13 (7.2 took v12). The feature as specced: an "Offer to skip 2 h before" switch (locked when turned on), a WorkManager pre-alarm job posting "{time} alarm / Rings in ..." with "Skip this one", a confirm dialog over Home, a guarded `SkipNextAlarm` that stores the skipped local date (or turns a one-time alarm off) and logs one Skipped row, "Next ring skipped" with "Don't skip" on Home's card, and the notification removed when the alarm rings.

**Files changed (beyond the WIP):**
- `data/.../db/AppDatabase.kt`, `AppDatabaseMigrations.kt`, `schemas/.../13.json` — `MIGRATION_12_13`, `SCHEMA_VERSION = 13`.
- `data/.../db/AppDatabaseSkipTest.kt` — the 7.11 schema and migration tests, split from `AppDatabaseFactoryTest`.
- `core/.../alarm/SkipNextAlarm.kt` — live "Offer to skip" and asked-about-occurrence guards, all-or-nothing undo, `DiscardStaleSkips`.
- `core/.../alarm/AlarmUseCases.kt` — stale-row cleanup after `SaveAlarm` (edit and switch-on) and `SetAlarmEnabled`; an edit drops a skip whose weekday left the repeat days.
- `composeApp/.../home/*` — `SkipDialog.occurrence`, `askSkip` requires "Offer to skip".
- `composeApp/.../editor/AlarmEditorViewModel.kt` — merged with 7.2's Motivation row; switch intents split out (detekt).
- `androidApp/.../prealarm/AndroidPreAlarmNotifier.kt` — negative tap request code, exact long-duration text.
- `androidApp/.../YawnAndPawnApp.kt` — `DiscardStaleSkips` wiring.
- Tests: `HomeSkipTest` (+9), `SkipNextAlarmTest` (+2), `AppOpenRequestsTest` (return values), `AlarmEditorLockTest`, `SkipRequestOpenTest`.

**Review.** Two passes. Pass 1: 41 findings; 3 medium patched (skip guards), 1 bad_spec root cause (stale Skipped rows) fixed by a spec amendment, 13 low patched, 6 deferred, the rest rejected with reasons in the triage log. Pass 2 (on the fixes): 22 findings; 1 medium and 8 low patched, 4 deferred, 9 rejected.

**Follow-up review recommended: false.** Pass 1 patched two medium entries, but pass 2 reviewed exactly those fixes and found only the sibling switch-on path (patched, tested). No unverified risk remains that a further pass would target.

**Verification.** Local, one Gradle build at a time: `:core:jvmTest` (all, then alarm package after fixes), `:data:testAndroidHostTest` (AppDatabase*, SessionHistory*, Alarm*), `:composeApp:testAndroidHostTest` (all), `:androidApp:testDebugUnitTest` (full run: 2490 tests, 1 failure in `SessionKillRecoveryTest`, which passed on a targeted re-run: a load flake; then targeted pre-alarm, skip, screenshot, wiring, kill-recovery), `spotlessApply`, `detekt` on every module. `qualityGate` runs in CI.

**Residual risks.** Device behaviour (WorkManager timing, the notification, the action opening the dialog, Doze) is for the 7.12 phone checks. The deferred items above.
