---
title: '0.1.8: one global Skip reminder replaces the per-alarm "Offer to skip"'
type: 'feature'
created: '2026-10-31'
status: 'done'
baseline_commit: '747d736bfc4be88f468c43271abca136f32ca7ed'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/.claude/skills/pps-design/SKILL.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The per-alarm "Offer to skip 2 h before" switch (Story 7.11) is hidden in each alarm, off by default and locked, so few people find it; and its notification only opens the app to ask again.

**Approach:** One PR (`feat/0-1-8-skip-reminder`), owner-approved 2026-10-31 (mockups for the Settings row and the notification), no further sign-off:

1. Remove the per-alarm option: editor row, form/draft/model state, its lock handling and its per-alarm pre-alarm job and notification. The `offer_skip` DB column stays, unused (like 0.1.7's grace columns). On update, every per-alarm pre-alarm job (`pre-alarm-{id}`) is cancelled and a stray old job does nothing.
2. A global "Skip reminder" switch in Settings, in the card with "Weekly summary" and "Share usage stats" (after Weekly summary), grey subtitle "If you're up early, ask to skip today's alarms.", ON by default, stored in the settings DataStore.
3. When on: one notification per local date D, 2 h before the first enabled, not-skipped alarm occurrence on D (the earliest ring on D, even if it already rang; once D's first ring has passed there is no reminder for D). Title "Up already?", text "Your {time} alarm rings in {h} h {m} min. Skip today's alarms?" ("rings in {m} min" under an hour), remaining time computed when posted; actions "Skip today's alarms" and "Keep"; tapping the body opens the app on Alarms (Home). If D's reminder time has already passed when it is scheduled or run (new alarm, late job), it posts at once only if more than 15 min remain, else D gets none. Never posted while a session is active; never twice for the same D.
4. "Skip today's alarms" (no app open) skips, through the existing `SkipNextAlarm` (free, Skipped history row, re-arm), every enabled alarm whose next occurrence is on D and still to come; then the notification becomes "Today's alarms are skipped. No charge." (auto-dismissed when D's first ring time passes). "Keep" dismisses. Home shows the skipped alarms with the existing "Next ring skipped" / "Don't skip".
5. Rescheduled on alarm add/edit/delete/enable/disable, skip and undo, the setting change, app start, boot and time/time-zone change (the existing `AlarmScheduling` / `SystemEventsReceiver` paths, WorkManager like the old pre-alarm job). Uses the existing "Upcoming alarms" notification channel (`upcoming_alarms`). Turning the switch off cancels the job and any posted reminder.
6. Commitment rule: the switch is not a locked or weakening change; it can be turned on or off at any time. Reason: skipping is free and allowed inside the lock window (FR-ALM-10, `SkipNextAlarm`), and switching an alarm off under the lock is already allowed with a confirm; the old per-alarm switch was locked only because it gated the skip. `SkipNextAlarm` no longer requires `offerSkip`.
7. The Home skip dialog was reachable only from the old notification's "Skip this one"; with that gone, its plumbing (`SkipRequests`, `MainActivity.EXTRA_SKIP_ALARM`, `HomeIntent.SkipRequested/SkipConfirmed/SkipCancelled`, `SkipDialog`) is removed. "Don't skip" on the card stays.
8. Docs: PRD FR-ALM-10 and epics (Story 7.11 note), EXPERIENCE.md (both copies) key strings and Settings rows, `docs/closed-test/store-listing-draft.md` ("Up early? A reminder 2 hours before your first alarm lets you skip the day's alarms, for free."), `docs/site/index.md` "Skip one morning" card.

## Boundaries & Constraints

**Always:** tokens only; strings in resources, no em dashes; every new mutating use case runs inside `SessionLockGuard.whenIdle` (SessionLockGuardScanTest); the action receiver is not exported and uses `goAsync`; PendingIntents immutable; notification time format follows the phone's 12/24 h setting; the job is only scheduled while the user is unlocked (as `AndroidBackgroundWork.guarded`); Roborazzi baselines of changed screens re-recorded; version name/code untouched.

**Never:** no DB column drop and no schema version bump; no new permission; no change to money wording, sessions, the ringing flow or the commitment rules of other fields; no app-opening activity start from the receiver.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Normal day | alarms 07:00 and 07:30 daily, now 22:00 | job at 05:00; text "Your 7:00 AM alarm rings in 2 h 0 min. ..." (real remaining) | N/A |
| New alarm 1 h away | create 08:00 alarm at 07:00, no earlier alarm that day | posts at once, "rings in 1 h 0 min" | N/A |
| New alarm 10 min away | create alarm at now + 10 min | no reminder for that date; next date scheduled | N/A |
| First ring already passed | 07:00 rang, 09:00 alarm same day, now 07:05 | no reminder today; next date's | N/A |
| Skip pressed | 07:00 and 07:30 on D, now 05:10 | both skipped (rows Skipped), confirmation shown, Home "Next ring skipped" on both | session active: nothing skipped, notification stays |
| Setting off | switch off | job cancelled, posted reminder removed | N/A |
| Session active at run | ringing at 05:00 | no post; next date scheduled | N/A |
| All alarms off / skipped on D | none enabled for D | D has no reminder; next date with an alarm | N/A |
| Old per-alarm job runs after update | `pre-alarm-{id}` fires | nothing posted | N/A |

</frozen-after-approval>

## Code Map

- `core/.../alarm/Alarm.kt` (`offerSkip` :54, `hasSameSettingsAs` :138), `AlarmUseCases.kt` (`AlarmDraft.offerSkip` :67, SaveAlarm OfferSkip lock :231-258, mapping :373) -- drop the field and its lock decision.
- `core/.../config/PendingChange.kt` (`LockedField.OfferSkip` :31, `SettingValue.OfferSkip` :88-94), `ChangeKind.kt` :42, `CommitmentRules.kt` :89, `SettingsUseCases.kt` :102/:110/:155, `PromotePendingChanges.kt` :50/:107-111, `composeApp/.../settings/SettingsViewModel.kt` :581 -- keep `OfferSkip` as a legacy entry (like `GraceSeconds`) so stored rows still decode; promoting one is a no-op that removes it.
- `core/.../alarm/SkipNextAlarm.kt` :78 -- drop the `offerSkip` gate; KDoc.
- `core/.../alarm/PreAlarm.kt` (`PreAlarm.LEAD`, `PreAlarmNotifier`, `PreAlarmScheduler`, `PreAlarmTask`) -- replace with `SkipReminder` (lead 2 h, min 15 min), `SkipReminderNotifier { canPost; post(date, firstRing: Alarm-or-time, ringsIn); showSkipped(...); cancel() }`, `SkipReminderScheduler.sync()` (computes D and delay from all alarms, cancels when off, posts at once inside the window), `SkipReminderTask` (recheck, `SessionLockGuard.isLocked`, post, mark D), `SkipTodaysAlarms(date)` use case (loops `SkipNextAlarm(id, expected)` for alarms whose next occurrence is on D).
- `core/.../work/BackgroundWork.kt` -- `BackgroundTaskKind.PreAlarm` stays decodable (no-op); add `scheduleSkipReminder(delay)` / `cancelSkipReminder()` and a unique name `skip-reminder`; keep `cancelPreAlarm(id)` for the update cleanup.
- `core/.../alarm/AlarmScheduling.kt` (:47 `preAlarms`, :58-64 cancel, :81 `rescheduleAll`, :123-135 `syncAfter`) -- call `skipReminder.sync()` once after a sync/cancel/rescheduleAll; `rescheduleAll` also cancels every stored alarm's old `pre-alarm-{id}` job.
- Store: new `core/.../reminder` (or `summary`-style) `SkipReminderStore { observe; read; setEnabled; setLastRemindedDate }`, data impl next to `data/src/commonMain/.../settings/DataStoreWeeklySummaryStore.kt` (keys `skip_reminder_enabled` absent = on, `skip_reminder_last_date`), wired in `data/src/androidMain/.../DataModule.kt` :126; `SetSkipReminder` like `core/.../summary/SetWeeklySummary.kt`.
- Android: replace `androidApp/.../android/prealarm/AndroidPreAlarmNotifier.kt` with `AndroidSkipReminderNotifier` (channel `upcoming_alarms` kept; one notification id, e.g. 1_018; body -> MainActivity; two broadcast actions) and a new non-exported `SkipReminderActionReceiver` (manifest); `android/work/AndroidBackgroundWork.kt` :85-104, `BackgroundTaskWorker.kt`, `WorkModule.kt` :47-57; `wake/WakeAlarmFiredHandler.kt` :55/:78/:130 (cancel the reminder when an alarm fires); `YawnAndPawnApp.kt` :166-240, :485; `SystemEventsReceiver.kt`; strings `androidApp/src/main/res/values/strings.xml` :18-25 (remove `pre_alarm_*`, add `skip_reminder_title` "Up already?", `skip_reminder_text_hours` "Your %1$s alarm rings in %2$d h %3$d min. Skip today\'s alarms?", `skip_reminder_text_minutes`, `skip_reminder_skip` "Skip today\'s alarms", `skip_reminder_keep` "Keep", `skip_reminder_done` "Today\'s alarms are skipped. No charge.").
- Settings UI: `composeApp/.../settings/SettingsContract.kt` (row `SkipReminder`, state, `SkipReminderToggled`), `SettingsScreen.kt` `GeneralCards` :203-245, `SettingsViewModel.kt` (:92 switch pattern, :220 fold the third switch into `switches`), `SettingsRoute.kt` rows, `UiModule.kt` :79; strings `settings_skip_reminder`, `settings_skip_reminder_body`; debug preview `androidApp/src/debug/.../preview/PreviewRound2Reducer.kt` :70 and settings samples.
- Editor: `editor/AlarmEditorContract.kt` (:57, :279, :364), `AlarmEditorViewModel.kt` (:376, :1079, :1086-1097, :1166), `AlarmEditorScreen.kt` :384-390, `EditorLock.kt` (:75, :95-99, :122, :149-159), `composeApp/src/androidMain/.../editor/AlarmEditorPreview.kt` :73-85, string `editor_offer_skip`.
- Home: `home/HomeContract.kt`, `HomeViewModel.kt` (:283-370), `HomeScreen.kt` (:147-185), `HomeRoute.kt`, `nav/AppNavHost.kt`, `nav/SkipRequests.kt` (delete), `UiModule.kt` :51, `MainActivity.kt` (:57-59, :83, :171, :197), `AlarmActions.skipNext` (remove if unused); keep `skipped` card state and "Don't skip".
- Data: `data/.../alarm/AlarmEntity.kt` :63, `AlarmMapping.kt` :34/:62 -- write `false`, ignore on read.
- Tests to update/remove: core `OfferSkipLockTest`, `PreAlarmTest`, `PreAlarmTestDoubles`, `SkipNextAlarmTest`, `ChangeKindTest`, `PendingChangeJsonTest`; `testing/.../FakePreAlarmNotifier.kt`, `SchedulerFakes.kt` and other `BackgroundWork` fakes; composeApp `HomeSkipTest`, `AlarmEditorLockTest` :257-361, `CopyRulesTest` :75-96, `SettingsWeeklySummaryViewModelTest` pattern; androidApp `PreAlarmWorkTest`, `BackgroundWorkWiringTest`, `SkipRequestOpenTest` (delete), `SkipNextAlarmScreenshotTest` (`editor_offer_skip_*`, `home_skip_dialog_*` baselines removed), `SystemEventsReceiverTest`, `UsageStatsSettingsTest`; data raw-INSERT tests keep `offer_skip`.
- Docs: `docs/prd.md` + `_bmad-output/planning-artifacts/prds/.../prd.md` :215/:227/:474-477; `_bmad-output/planning-artifacts/epics.md` (Story 7.11 :3934-3997, add a 0.1.8 note); EXPERIENCE.md both copies (:266-271 key strings, Settings rows, skip dialog row removed); `docs/closed-test/store-listing-draft.md` :62/:94; `docs/site/index.md` :144.

## Tasks & Acceptance

**Execution:**
- [ ] Core: remove `offerSkip` (model, draft, lock legacy no-op, SkipNextAlarm gate); `SkipReminder*` scheduler/task/notifier port/store port, `SkipTodaysAlarms`, `SetSkipReminder`; `AlarmScheduling` wiring; unit tests for every matrix row (scheduler delay and D choice, 15 min rule, once per D, session active, setting off, skip-all with rows, old job no-op).
- [ ] Data: DataStore store + mapping (`offer_skip` written false) + tests.
- [ ] Android: notifier, receiver (manifest), work, DI, fired-handler cancel, start-up cleanup of `pre-alarm-{id}` jobs; Robolectric tests for posting text, actions and the receiver.
- [ ] UI: Settings row (+ semantics test, screenshots), editor row removal, Home skip-dialog plumbing removal, debug preview.
- [ ] Docs (list above).
- [ ] Re-record Roborazzi baselines for Settings and editor; delete baselines of removed screens.

**Acceptance Criteria:**
- Given Settings, then the card with Weekly summary shows "Skip reminder" with "If you're up early, ask to skip today's alarms." and the switch is on for a fresh install; toggling it never shows a lock note.
- Given the editor, then there is no "Offer to skip" row.
- `./gradlew qualityGate` passes in CI.

## Implementation Notes

- Core lives in `core/.../reminder` (`SkipReminder`, `skipReminderTarget`, `SkipReminderScheduler`, `SkipReminderTask`, `SetSkipReminder`, `SkipTodaysAlarms`), outside the fully scanned packages; `SetSkipReminder` is in the scan's ALLOWED list like `SetWeeklySummary`.
- `AlarmScheduling.sync/cancel/syncAfter` are now `suspend` (the reminder reads the alarms and the store); `rescheduleAll` arms every alarm, cancels every stored alarm's `pre-alarm-{id}` job, then syncs the reminder once. `cancel` no longer takes the alarm id.
- The scheduler posts directly when the reminder time has come (one code path for the job's run, a new alarm and app start); the date is stored as reminded before the post (also when a session runs or notifications are off), so a date never posts twice. The job reschedules itself with REPLACE from inside its run, which cancels and removes the finished run (harmless: the post is done first).
- "Session active" for the reminder: the restored engine's `isLocked`, else (cold start by the job) the emergency flag or a ring/snooze stored in runtime.db.
- A one-time alarm counts only for its next occurrence; a repeating alarm's past occurrence on D counts as "already rang" only when it came after the alarm's `updatedAt`.
- The action receiver restores the session engine first when runtime.db holds no session (cold start by the broadcast, like `ResumePendingDeletion`); otherwise the guard would refuse every skip.
- The posted reminder is not removed by alarm changes; it times out at the first ring and any alarm fire removes it.

## Spec Change Log

- 2026-10-31, review fixes (frozen block unchanged): the reminded date is monotonic ("handled through": dates on or before it never come back, and a date with 15 min or less left, or whose first ring passed, is stored as handled with no post), and a sync always ends by scheduling or cancelling the job; the job's own run chains its successor (APPEND_OR_REPLACE, like the weekly summary) instead of replacing itself; nothing is posted or stored before the first unlock; "Skip today's alarms" returns the failure when any due alarm could not be skipped, so the reminder stays and a second press skips the rest; a reminder posted the evening before a ring between 00:00 and 01:59 says "tomorrow's" (text, action and confirmation, EXPERIENCE.md Key strings); the confirmation has its own notification tag and "Don't skip" removes it; the update cleanup also removes Story 7.11's pre-alarm notifications (id 100 000 + request code); the receiver uses the app's one empty-session restore (`RestoreIfIdle`); EXPERIENCE.md gains a `notification-skip-reminder` row; the nextRing DST and past-skipped-date tests are back (SkipNextAlarmTest).

## Review Triage Log

| # | Layer | Finding | Verdict | Evidence / route |
|---|---|---|---|---|
| 1 | edge+blind | One stored date: two windows near midnight flip-flop, re-post, and leave no job | high | Traced in sync's 3 rounds. Patched: monotonic "handled through" date; every path schedules or cancels. |
| 2 | edge+blind | A rung one-time alarm (disabled) or a ring-then-edit lets a later alarm that day get a reminder | medium | `ringOn` ignores disabled and updatedAt-later rings. Patched: a date with 15 min or less left (or past) is stored as handled. |
| 3 | edge+blind+verif | Partial skip failure shows "Today's alarms are skipped" | medium | SkipTodaysAlarms returned Success after a later failure. Patched: failure returned, reminder kept, retry skips the rest; tested. |
| 4 | edge | Run reschedules itself with REPLACE, cancelling the running worker | medium | Patched: run path APPEND_OR_REPLACE (no successor check: it hung Robolectric); runAt strict again. |
| 5 | edge | Reminder can post before first unlock; action receiver not direct-boot aware | medium | Patched: nothing posted or stored while the user is locked. |
| 6 | blind | "Skip today's alarms" posted the evening before skips tomorrow's | medium | Patched: tomorrow variants of text, action and confirmation (decision beyond the brief, reported). |
| 7 | blind | Confirmation stays after "Don't skip" | medium | Patched: confirmation has its own tag; UndoSkip removes it. |
| 8 | edge+blind | Old Story 7.11 notifications stay after update with a dead action | low | Direct cleanup. Patched. |
| 9 | edge | Fire of a D-1 alarm removes D's reminder (after-midnight pair) | low | Rare (two alarms within 2 h across midnight); fix needs posted-date state. Rejected. |
| 10 | blind | Reminder sync I/O on the fire path and inside the guard | low | Service start is requested before the re-arm; the old pre-alarm sync made the same WorkManager call there. Rejected. |
| 11 | blind | N syncs per Skip press inside the receiver budget | low | A few alarms per day; each sync is small. Rejected. |
| 12 | blind | Spec not in the diff | false | Excluded from the review diff on purpose; it is in the PR. |
| 13 | blind | Existing users switched on with no notice | false | Owner decision 2026-10-31: on by default. Release notes are the owner's. |
| 14 | blind | Time-zone change between post and tap | low | Rare; skips by stored date in the current zone. Rejected. |
| 15 | blind | Docs lack a component row for the notification; AppNavHost empty KDoc line | low | Direct. Patched. |
| 16 | verif | No test that the body tap opens Alarms | medium (gap) | Patched: SkipReminderOpenTest. |
| 17 | verif | Fire removing the reminder untested | medium (gap) | Patched: WakeAlarmFiredHandlerTest. |
| 18 | verif | Production sessionActive wiring untested | medium (gap) | Patched: stored Snoozed session test. |
| 19 | verif | Receiver with a stored session untested | medium (gap) | Patched. |
| 20 | verif | nextRing DST tests deleted with PreAlarmTest | medium (gap) | Patched: moved to SkipNextAlarmTest. |
| 21 | verif | Partial-failure path untested | medium (gap) | Patched with #3. |
| 22 | blind | Store listing word-count rule broken by the new line (impl. note) | low | Patched: "lets you skip that day, for free." keeps "alarm" at its old count. |

## Design Notes

D and its first ring: for each local date from today, the earliest occurrence on that date of every enabled alarm (repeating: `ringsOn(day)` at its time, not when `skippedDate == D`; one-time: its next occurrence), in the current zone. Take the first D whose first ring is after now + 15 min and is not `lastRemindedDate`; delay = max(0, firstRing - 2 h - now). The task recomputes at run time and only posts for that D if 0 < remaining and remaining > 15 min (late jobs). After posting it stores D and calls `sync()` for the next date.

## Verification

**Environment (company PC, no admin):** worktree `C:/Users/BuiTua/Personal/yp-018`; `JAVA_HOME=C:/Users/BuiTua/AppData/Local/Programs/jdk17/jdk-17.0.20.1+1`; ONE Gradle build at a time, targeted tasks, `./gradlew --stop` when done; no adb; never edit `C:/Users/BuiTua/Personal/yawn-and-pawn`.

**Commands:**
- `./gradlew :core:allTests :data:testAndroidHostTest :composeApp:testAndroidHostTest :androidApp:testDebugUnitTest --tests <changed classes>` -- pass (check the real task names in build files)
- `./gradlew :androidApp:recordRoborazziDebug -Proborazzi.test.verify=false --tests <screenshot classes>` -- baselines updated
- `./gradlew spotlessCheck detekt` -- pass; `./gradlew qualityGate` in CI
