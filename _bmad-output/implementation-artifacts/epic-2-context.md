# Epic 2 Context: An alarm you can't escape

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Make the Epic 1 alarm hold up on real phones. A session survives process death, OEM task killers, swipes from Recents, app updates and overnight reboots, including a reboot before the first unlock. Clock and time-zone changes cannot end or shorten it. It pauses for phone calls, merges overlapping alarms, and locks the app to the wake screen while the rest of the phone stays fully usable. Without this, any of these tricks would end the morning for free and the pay-to-snooze promise would mean nothing. The epic adds no new session states. It hardens the existing state machine, engine, databases and wake runtime, and ends with an owner escape-attempts checklist on the device matrix.

## Stories

- Story 2.1: Keep the backup alarm armed and recover after a kill
- Story 2.2: Session deadlines survive clock changes and reboots
- Story 2.3: Ring before the first unlock after a reboot
- Story 2.4: Unlocking during a ring makes snooze available in place
- Story 2.5: Leave the alarm and come back through the notification
- Story 2.6: Lock the app to "Alarm in progress" during a session
- Story 2.7: Pause the alarm for phone calls
- Story 2.8: Alarm volume and volume keys on the wake screen
- Story 2.9: Merge an alarm that rings during a session
- Story 2.10: Session conflict rules end to end
- Story 2.11: No-hostage guard: the phone stays usable
- Story 2.12: Back up alarms and history, never the session or media
- Story 2.13: Epic 2 escape-attempts checklist on the device matrix

## Requirements & Constraints

- **Persistence and recovery:** session state (ringing, grace, in check, paying, snoozed) is persisted at once and restored after process death, reboot or app update, and the alarm rings again. The only free escapes are force-stop from App info and powering off (and only while the phone is off).
- **Backup slot:** while a session is active, exactly one exact backup alarm is always armed at most 60 s ahead (re-armed on every heartbeat), or at snooze end while snoozed. A kill re-rings within 60 s on the same step with the same snooze count, and shows no "restored" message.
- **Clock changes:** time and time-zone changes never end, skip or shorten a session. Grace, snooze and the 30-minute timeout run on monotonic time. After a reboot, a snooze end that has already passed rings at once, and a restored ring gets a fresh 30-minute timer.
- **Before first unlock:** alarms ring from device-protected storage. Sounds that need normal storage fall back to the default built-in sound. Checks that are not Direct Boot safe are swapped (in Epic 2 the plan is still the Placeholder step, which is safe). Snooze is disabled with "Unlock your phone to snooze". After unlock, snooze availability updates in place while the substituted check stays for the current ring.
- **Leaving the wake screen:** Home, Recents and other apps always work. The sound never stops. Tapping the notification or the launcher icon returns to the wake screen within 1 s. Nothing starts an activity from the background. On Android 14+ a dismissed notification comes back.
- **Session lock:** while a session is active (snoozed included), the app shows only the wake screen or "Alarm in progress". No alarm, setting or data can be edited, disabled or deleted, and other apps are not affected.
- **Calls:** detected through the audio mode only (ringtone, in-call, in-communication), never with `READ_PHONE_STATE`. A call pauses the sound, the grace countdown and the timeout, and they resume when it ends. Calls change nothing while snoozed, and there is no cap on the pause. Other apps taking audio focus (video, music) never pause or duck the alarm.
- **Volume:** the alarm stream is set to the session volume at each ring start and when grace ends, never continuously. Volume keys do nothing only while the wake screen is in front, and the accessibility shortcut (both keys held) is never captured. Audio routing is left to the system.
- **Merges:** an alarm due during a session joins it. The session keeps its frozen config and fee ladder, and the merge is not a user interaction. During a snooze it re-rings now, with no fee and no grace window. Each merged occurrence is logged, and the alarm's next occurrence is scheduled normally.
- **No hostage (Play policy):** no overlays, accessibility service, device admin, lock-task, launcher posing or `READ_PHONE_STATE`. The emergency dialer and calls always work.
- **Backup:** alarms, settings and history are backed up. The active session, purchase intents and all media are never backed up. After a restore, alarms are re-armed without opening the app, and the app starts Idle.
- **Battery:** no service runs once the session ends, and an orphan slot cleans itself up.
- **Every UI story (2.3, 2.4, 2.6, 2.7):** the `pps-design` Done checklist is ticked in the story. All strings are resources that match the EXPERIENCE.md Key strings verbatim and pass `CopyRulesTest`. Roborazzi covers Sunrise for wake screens, Light/Dark for app screens, and 200% font scale. Wake targets are at least 64 dp and TalkBack reads clock, "I'm up", snooze in that order.

## Technical Decisions

- **What Epic 1 already built (extend it, don't rebuild):**
  - **Session core:** `reduce` in `SessionReducer` with rule files `RingRules`, `SnoozedRules`, `CheckRules`, `PurchaseRules` and `IdleRules`, plus `entryEffects`, `dueEvents` and `nextTickIn` in `SessionRuntime`. The events `SlotFired`, `ProcessRestored`, `OverlapAlarmFired`, `CallStarted`, `CallEnded` and `UserUnlocked` already exist. So do the effects `ArmSlot`, `CancelSlot`, `RecordMergedOccurrence`, `RescheduleAlarm`, `PauseSound`, `ResumeSound`, `LiftDirectBootSubstitutions` and `InitBilling`, and the entry effects `HeartbeatSlotArmed`, `SlotArmedAt`, `SoundPaused` and `WakeUiShown`.
  - **Engine:** `SessionEngine` (Mutex, write-ahead commit to `runtime.db` through `RoomActiveSessionStore`, `restore()`, `tick()`) with `SessionJson`.
  - **Recording:** `SessionRecorder` is the only history writer. The `Deadline` and time ports and `NoBillingSnoozeAvailability` (the Epic 1 `SnoozeAvailabilityPolicy`) are also in place.
  - **Scheduling:** `AlarmScheduler` already has `armSessionSlot`, `cancelSessionSlot` and `scheduleTest`. `AndroidAlarmScheduler` uses `setAlarmClock` only, and `AlarmScheduling.rescheduleAll()` exists.
  - **Android receivers:** `AlarmFiredReceiver` currently also receives the slot (`ACTION_SESSION_SLOT` → `WakeAlarmFiredHandler.onSessionSlotFired`), although Story 2.1 names a `SessionSlotReceiver`. `SystemEventsReceiver` does not yet list `LOCKED_BOOT_COMPLETED`.
  - **Wake runtime:** `WakeService` (`mediaPlayback`, actions `WAKE_ALARM`, `WAKE_SLOT`, `WAKE_RESTORE` and `WAKE_TEST`) and `WakeRuntime`, which is the `EffectRunner` and also runs the emergency default-sound ring.
  - **Wake playback and screen:** `AndroidAlarmPlayer` and `AlarmVolume` (save and restore of the alarm stream; no audio focus yet), `AlarmVibrator` and `WakeNotifier` (full-screen intent; no `deleteIntent` yet). `WakeActivity` is `singleTask` with its own affinity, and Back is already a no-op.
  - **Elsewhere:** `ReliabilityProbe`/`AndroidReliabilityProbe` (the Home reliability banner), `UnavailableBilling` and Firebase gated on unlock (`FirebaseStartup`).
  - **Storage:** `app.db` is already **version 3** (`request_code_sequence`, then `session_history`), so Story 2.9's `session_merge` migration is **v3 → v4**, not v2 → v3 as written. `runtime.db` is v1. Backup XML files already exist.
  - **Restore entry point:** `YawnAndPawnApp.onCreate` currently calls `engine.restore()`. Story 2.1 moves it to `WakeService` start, `MainActivity` creation and `WakeActivity` creation only.
- **State machine is fixed:** the AD-2 table is normative. Unmatched events are ignored and logged. No new states and no new rows unless they go through correct-course. Adapters never mutate state; they run effects and feed results back as events.
- **Startup order:** `WakeService` calls `startForeground` first, then `restore()`, and only then dispatches any broadcast-derived event. An alarm firing into a killed process that has a persisted session becomes `OverlapAlarmFired`, never a second session. Non-alarm broadcasts never start a foreground service. They arm the slot for immediate delivery (Android 15+ rule: no media FGS from boot receivers).
- **Deadlines:** `Deadline(wallMillis, elapsedMillis, bootCount)` is compared on monotonic time within the same boot and on wall time after a reboot. A lower elapsed time always means a reboot (for devices where `BOOT_COUNT` is missing). The adapter converts a deadline to wall time only when it arms `setAlarmClock`, and re-arms on `TIME_SET`/`TIMEZONE_CHANGED`. The frozen `SessionConfig` is never re-resolved.
- **Direct Boot:** both databases and DataStore live under `createDeviceProtectedStorageContext()`. A detekt rule bans credential-storage APIs outside the `android.media` package (Epic 7 media). A `UserLockState` port (core) has an Android adapter and a fake. The pure `DirectBootSubstitution.apply(config)` runs on `AlarmFired`, `TestAlarmFired` and `ProcessRestored` while locked. Snooze availability precedence is TestMode, then BeforeFirstUnlock, then CatalogueNotLoaded. History records `direct_boot`. Billing and Firebase initialise only after unlock, and also outside the table when unlock happens in Grace or Loud.
- **Spike S1 owner decision (2026-10-05), relevant to 2.4 and 2.8:**
  - **Locked payment flow:** on the Oppo A96 the Play purchase sheet never shows over a keyguard. A paid snooze while locked shows the price over the lock screen. Pay then calls `requestDismissKeyguard` (the PIN prompt), and `onDismissSucceeded` launches billing. Cancel returns to the ringing screen with the sound on. So an unlock can happen through `requestDismissKeyguard` while `WakeActivity` stays on top, and 2.4's in-place update must handle that path.
  - **Volume keys:** while another app's activity (the Play sheet) is on top, the volume keys change the alarm stream. This fits 2.8's rule: keys are consumed only while `WakeActivity` is resumed, there is no global key capture, and the volume is re-applied only at the next ring start or grace end.
- **Calls:** a `CallDetector` in `:androidApp` reads `AudioManager.getMode()` on focus loss, through the mode listener (API 31+) and at ring start. On API 26–30 it polls every 1 s while paused. The player requests `AUDIOFOCUS_GAIN` with `USAGE_ALARM` and never ducks itself.
- **Session lock:** a single `SessionInProgress` Navigation 3 route replaces the back stack while the state is not Idle. A `SessionLockGuard` makes the core mutating use cases return `DomainError.SessionActive`. Later mutating use cases must use the same guard (enforced by a test). `AlarmFiredHandler` still reschedules or disables alarms during a session.
- **Merge storage:** `session_merge` (key: session id + alarm id + scheduled time) is written only through `SessionRecorder`, idempotently. The engine Mutex guarantees one session for simultaneous fires.
- **Enforcement:** the `NoHostageApis` detekt rule, the permission and merged-manifest allowlist fixtures (`stopWithTask`, `CATEGORY_HOME`, `lockTaskMode`), and a debug-only `WakeStatus` query that is absent from release (`checkReleaseContent`).
- **Backup:** `PpsBackupAgent` (with `fullBackupOnly`) runs `rescheduleAll()` in `onRestoreFinished`. `BackupRulesCoverageTest` fails on any file that is neither included nor excluded.
- **FGS and heartbeat:** the foreground-service type stays `mediaPlayback` and the heartbeat mechanism stays as planned until Spike S2 (1.20, still backlog; `docs/spikes/S2.md` does not exist yet) reports. Any change needs correct-course first.

## UX & Interaction Patterns

- **Wake screen during recovery:** it renders from in-memory state with no loading and no "restored" message. A restored ring plays at the set volume with no ramp.
- **Snooze before first unlock:** `button-snooze-disabled` with the leading `lock` icon and "Unlock your phone to snooze". TalkBack reads "Snooze unavailable, Unlock your phone to snooze". A test session still shows "Test · no charge". After unlock the label re-renders in place, and the activity is not finished or recreated. The visible prices reason is "Prices not loaded yet" (owner decision 2026-10-02; TalkBack: "Snooze unavailable, prices not loaded yet"). That Key string wins over the longer wording in the 2.4 and 2.13 texts.
- **Call note:** a Sunrise `note-inline` with "Paused for your call. Rings again when it ends.", announced politely by TalkBack.
- **Notification:** the text is "{time} alarm · Tap to return to your alarm" in every ringing state. It never has an action that stops or lowers the sound.
- **Session panel:** `panel-session-in-progress` is a `surface` card, `rounded.md`, with "Alarm in progress" as the headline and heading and one `button-filled` "Back to alarm" (at least 48 dp). The nav capsule is hidden, and all five tabs are covered. An open editor or Sound picker closes without a dialog, previews stop, and the app returns to Home when the session ends.
- **Keys:** Back does nothing on wake screens, and Home and Recents always work.

## Cross-Story Dependencies

- **Order:** 2.1 → 2.2 → 2.3 → 2.4, because each builds on the previous slot, deadline and Direct Boot work. 2.5 comes before 2.6, which reuses the forwarding from `MainActivity`. 2.11's detekt rule and allowlist enforce the bans that 2.5, 2.6, 2.7 and 2.8 rely on. 2.10 runs after 2.1–2.9 because it exercises all of them together. 2.13 is human-verify, comes last, and automation never marks it done. Its failures become bug stories.
- **Epic 1 inputs:** 1.20 (Spike S2) sets the FGS type and the heartbeat mechanism. 1.5 (Spike S1) settles the unlock step (owner decision above). 1.21 is the owner's Epic 1 device sitting.
- **Downstream:**
  - Epic 3 sets Math as the Direct Boot check, adds the "Your phone restarted, so today's check is Math." notice, and adds the per-alarm "vibrate in grace" switch.
  - Epic 4 replaces the availability policy (keeping the precedence) and builds the locked payment flow ("Unlock to pay {price}", "Phone still locked. No charge."). It also repeats the snoozed-state escape items in its checklist.
  - Epic 6 shows "{time} alarm merged into this session" from `session_merge`.
- **Open deferred items assigned to Epic 2:**
  - 2.1: the emergency default-sound ring arms no backstop slot, so a process death during it is not recovered.
  - 2.2: decide whether a wrong wall clock right after a reboot (before network time) needs more than the existing `TIME_SET` re-run (see `docs/decisions/reboot-clock.md`).
  - 2.3: AD-2 has a `UserUnlocked` row only for Ringing, so an unlock during Grace, Loud or Snoozed leaves `beforeFirstUnlock` set. Settle it through correct-course (add rows), or confirm 2.4's "init outside the table" approach.
  - 2.7: the call adapter must re-send `CallStarted` after `ProcessRestored` and whenever a new ring starts during a call, or the next ring plays over the call.
  - 2.9: route `RecordMergedOccurrence` (today only logged by the runner) to `SessionRecorder` as an idempotent `session_merge` row with its `app.db` migration.
  - 2.12: implement the downgrade policy. `PpsBackupAgent` skips and logs a restored `app.db` whose schema is newer than the installed one, tells the user (owner-approved copy needed), and never sets `restoreAnyVersion`. Test it with a hand-built newer file.
