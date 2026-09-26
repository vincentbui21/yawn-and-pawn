## Epic 2: An alarm you can't escape

The alarm keeps its promise: it survives process death, app kills and overnight reboots (including before the first unlock), clock and time-zone changes can't end or shorten it, it pauses for phone calls, merges overlapping alarms, and locks the app to the wake screen during a session while the rest of the phone stays fully usable. This epic adds no new session states: it builds on the Epic 1 AD-2 state machine, `SessionEngine`, `runtime.db`, `app.db`, `WakeService`, `WakeActivity` and the reserved session-slot request code, and makes them hold up on real phones. It ends with a human-verify escape-attempt checklist on the device matrix.

Every UI story in this epic carries the two standing acceptance criteria from Epic 1, repeated in the story so the build loop can check them: (1) the `pps-design` Done checklist is copied into the story file with every item ticked; (2) all new user-facing strings live in Compose Multiplatform resources, match `EXPERIENCE.md > Voice and Tone > Key strings` verbatim where a key string exists, and pass the automated copy-rules test from Story 1.3 (FR-MSG-4). A string that EXPERIENCE.md does not define is marked `[ASSUMPTION: add to EXPERIENCE.md Key strings]` in the story and listed for the owner.

### Story 2.1: Keep the backup alarm armed and recover after a kill

As a user,
I want the alarm to come back by itself within a minute if the app is killed while it rings,
So that killing the app, an OEM task killer or a crash never ends my morning for free.
**Refs:** FR-SES-1, FR-SES-2, FR-SES-10, NFR-2, NFR-8, AD-2, AD-3, AD-4, AD-5, AD-12 · **Priority:** Must · **Verify:** auto (device kills are human-verify in Story 2.13)

**Acceptance Criteria:**

**Given** a session in `Ringing`, `Grace` or `Loud`
**When** the entry effects run after any dispatch
**Then** the "slot armed" entry effect calls `AlarmScheduler.armSessionSlot` with a `Deadline` at most 60 s ahead (now + 60 s, re-armed on every `SlotFired` per the AD-2 heartbeat row), and in `Snoozed` it arms the slot at snooze end
**And** the "slot armed" entry effect list from Story 1.11 is extended to include `Grace`, so a `SlotFired` during `Grace` (no AD-2 row: ignored and logged, state unchanged) still leaves the heartbeat armed; `SessionEngine` re-runs the idempotent `entryEffects(state)` after every dispatch, including an ignored one (no commit is written when the state is unchanged)
**And** `FakeAlarmScheduler` shows exactly one pending slot request code at any time during a session (never two), and none after `Completed`, `Missed` or `Idle`
**And** the reducer's transition table is unchanged (the Story 1.11 table-coverage test still passes without new rows)

**Given** `AndroidAlarmScheduler.armSessionSlot(deadline)`
**When** it arms the slot
**Then** it calls only `setAlarmClock()` with the reserved slot request code, a trigger time of current wall time + `deadline.remaining(now)` (AD-3), a show intent to `MainActivity`, and an immutable operation `PendingIntent` to `SessionSlotReceiver` (`directBootAware`, `exported="false"`)
**And** re-arming replaces the pending slot (same request code, `FLAG_UPDATE_CURRENT`), verified with `ShadowAlarmManager`
**And** the heartbeat mechanism follows the Q18 finding recorded in `docs/spikes/S2.md` (Story 1.20); any change of mechanism needs `bmad-correct-course` first

**Given** the process has been killed during a session
**When** the slot fires and `SessionSlotReceiver` runs
**Then** it calls `startForegroundService(WakeService)` with the slot action; `WakeService` calls `startForeground` first, then `SessionEngine.restore()` (which dispatches `ProcessRestored` when `runtime.db` holds a session), and only then dispatches `SlotFired`
**And** `WakeService` always completes `restore()` before dispatching any broadcast-derived event (`AlarmFired`, `TestAlarmFired`, `OverlapAlarmFired`, `SlotFired`), so an alarm occurrence that fires into a killed process with a persisted session becomes `OverlapAlarmFired`, never a second session (test)
**And** when `runtime.db` holds no session (orphan slot), the service logs it, cancels the slot, removes its notification and stops itself, leaving no running service (NFR-8)

**Given** a restored session
**When** `ProcessRestored` has been handled
**Then** only entry effects run: the sound plays again at the set volume on `USAGE_ALARM` (no ramp on a restored ring), the ongoing notification with its full-screen intent is posted again, `WakeActivity` shows the same step with the same snooze count and check progress, and no "restored" message is shown (UX-DR78)
**And** `paying` is cleared, billing is never relaunched (the Story 1.12 test is extended to go through `SessionSlotReceiver`), and the restored ring has a fresh 30-minute interaction deadline
**And** a restored `Grace` whose grace deadline has passed moves to `Loud` on the same dispatch through `dueEvents`

**Given** a process started only for a non-alarm broadcast (boot, `TIME_SET`, `TIMEZONE_CHANGED`, `MY_PACKAGE_REPLACED`, `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`, user unlocked, debug hooks)
**When** `runtime.db` holds an active session
**Then** the process does not run the restore entry effects and never starts a foreground service; it arms the session slot instead: for immediate delivery (wall now + 1 s) in `Ringing`, `Grace` or `Loud`, at snooze end in `Snoozed`, so the alarm broadcast starts `WakeService` (Android 12+ foreground-service start rules; no media foreground service from boot receivers on Android 15+)
**And** `SessionEngine.restore()` runs its entry effects only from `WakeService` start, `MainActivity` creation or `WakeActivity` creation (this narrows Story 1.12's "restore at process start" to these entry points; a unit test fails if `restore()` is called from `Application.onCreate` or any receiver)

**Given** the user swipes the app from Recents during a session
**When** `WakeService.onTaskRemoved` is called
**Then** the session, the player and the foreground notification keep running (`stopWithTask` is not set to true; Robolectric test calls `onTaskRemoved` and asserts the player is still playing)

**Given** Robolectric tests that "kill" the process by discarding the `SessionEngine` instance and rebuilding it from the Room `runtime.db` on a temporary device-protected path
**When** a kill happens in `Ringing`, `Grace`, `Loud`, `Snoozed` and `Ringing` with `paying` set, and the slot is delivered
**Then** each resumes as described above within one slot delivery, `Snoozed` stays silent until snooze end and then rings as `Ringing(ringIndex + 1)`, and history still ends with exactly one row per session
**And** `./gradlew qualityGate` passes

### Story 2.2: Session deadlines survive clock changes and reboots

As a user,
I want changing the clock, the time zone or restarting the phone to neither end nor shorten my alarm,
So that there is no trick with the settings app that stops it for free.
**Refs:** FR-SES-1, FR-SES-5, FR-SES-10, FR-ALM-5, FR-ALM-9, AD-2, AD-3, AD-4 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** an active session
**When** `TIME_SET` or `TIMEZONE_CHANGED` is received
**Then** `SystemEventsReceiver` runs `rescheduleAll()` (Story 1.10) and also re-arms the session slot from its stored `Deadline`: on the same boot the new trigger is current wall time + the remaining monotonic duration, so the session is not ended, skipped or shortened (FR-SES-5)
**And** the frozen `SessionConfig` is not re-resolved, and the Home countdown and scheduled occurrences use the new time and zone

**Given** tests with `FakeClock`, `FakeMonotonicClock`, `FakeBootCounter` and `FakeTimeZoneProvider`
**When** the wall clock jumps by +2 h, by −2 h, and the zone changes Europe/Berlin → America/New_York
**Then** in `Ringing` and `Loud` the session keeps ringing and the 30-minute timeout still fires 30 monotonic minutes after the last interaction
**And** in `Grace` the grace window ends after the same number of monotonic seconds
**And** in `Snoozed` a 9-minute snooze with a +1 h jump at minute 3 re-rings 6 monotonic minutes later, and with a −1 h jump also 6 minutes later
**And** a scheduled alarm occurrence due during the session is still delivered and merged per FR-SES-7 (Story 2.9 covers the merge itself)

**Given** a persisted session and a reboot (the stored `Deadline.bootCount` differs from `BootCounter`)
**When** `BOOT_COMPLETED` or `LOCKED_BOOT_COMPLETED` is received
**Then** the slot is armed from wall time: `Snoozed` with snooze end still ahead → at the snooze end wall time; `Snoozed` with snooze end already past (phone off through it) → wall now + 1 s, and restore turns it into `Ringing(ringIndex + 1)` immediately; `Ringing`, `Grace` or `Loud` → wall now + 1 s, and the restored ring gets a fresh 30-minute deadline (PRD §6.4)
**And** a session restored after the phone was off for 10 hours still rings (powering off is an accepted escape only while the phone is off, FR-SES-1)

**Given** a device where `Settings.Global.BOOT_COUNT` is missing or unreadable
**When** `Deadline` compares boots
**Then** it treats the deadline as the same boot only when the boot count matches and the current elapsed time is not lower than the stored elapsed time; a lower elapsed time always means a reboot and falls back to wall time (unit tests for both cases; this tightens the Story 1.6 rule without changing its results on normal devices)

**Given** an app update during a session
**When** `MY_PACKAGE_REPLACED` is received
**Then** the slot is armed for immediate delivery and the session is restored with the same step and counts (FR-SES-1, Robolectric test)
**And** `./gradlew qualityGate` passes

### Story 2.3: Ring before the first unlock after a reboot

As a user,
I want my alarm to ring after an overnight restart even if I haven't unlocked the phone yet,
So that a system update at 3:00 doesn't make me late.
**Refs:** FR-ALM-11, FR-ALM-5, FR-SES-1, FR-SES-10, FR-RNG-7 (reason only), FR-MSG-4, NFR-2, NFR-9, AD-4, AD-6, AD-7, AD-15, UX-DR14, UX-DR64, UX-DR66, UX-DR78, UX-DR84, UX-DR93 · **Priority:** Must · **Verify:** auto (real reboots are human-verify in Story 2.13)

**Acceptance Criteria:**

**Given** the manifest
**When** `SystemEventsReceiver` (already `directBootAware`) also declares `LOCKED_BOOT_COMPLETED`
**Then** on that broadcast it calls `rescheduleAll()` from the device-protected `app.db` and arms the session slot per Story 2.2 inside `goAsync()`, never starts a foreground service, and `BOOT_COMPLETED` after unlock repeats the same work idempotently (`FakeAlarmScheduler` shows identical calls)

**Given** a Robolectric test with `ShadowUserManager` set to locked and a test context that throws on any access to credential-protected storage (`filesDir`, `getDatabasePath`, `getSharedPreferences`, `dataDir` of the normal context)
**When** it runs `LOCKED_BOOT_COMPLETED` → `rescheduleAll()` → alarm occurrence fires → `WakeService` → `Ringing`
**Then** the alarm rings with sound and the ringing notification, and no credential-protected path is touched (`app.db`, `runtime.db` and the DataStore files all resolve under `createDeviceProtectedStorageContext()`)
**And** Firebase stays uninitialised (Story 1.19 gate) and no `Billing` initialisation call happens while locked (asserted with `FakeBilling`)

**Given** detekt with the custom `:detekt-rules`
**When** code in `:data` or `:androidApp` calls `getDatabasePath`, `filesDir`, `cacheDir`, `getSharedPreferences`, `preferencesDataStore` or `dataStoreFile` on a context that is not the device-protected context
**Then** detekt fails, except inside the `android.media` package reserved for credential-protected media (Epic 7), and the rule has a violating and a compliant snippet test

**Given** a `UserLockState` port in `:core` (`isUserUnlocked(): Boolean`, `observe(): Flow<Boolean>`) with an Android adapter (`UserManager.isUserUnlocked`) and `FakeUserLockState` in `:testing`
**When** `SessionEngine` dispatches `AlarmFired`, `TestAlarmFired` or `ProcessRestored` while the user is locked
**Then** the session gets `beforeFirstUnlock = true` and the pure `DirectBootSubstitution.apply(config)` is applied to the ring's config: a `soundRef` that is not `SoundRef.BuiltIn` becomes the default built-in sound (system ringtones need the media provider, which is not available before unlock), and every check-plan step whose check type is not marked Direct Boot safe is replaced by the Direct Boot check
**And** in this epic the plan is the Epic 1 `Placeholder` step, which is marked Direct Boot safe and stays; Epic 3 sets Math as the Direct Boot check and adds the notice
**And** a chosen built-in sound plays unchanged, and when the user is unlocked `apply` returns the config unchanged (tests)
**And** a session that started unlocked and is restored after a reboot before unlock gets the substitutions for the restored ring only; its fee ladder, max snoozes and snooze length stay frozen

**Given** the Epic 1 production `SnoozeAvailabilityPolicy`
**When** it is extended with the live `UserLockState` value in its environment input
**Then** it returns, in this order of precedence: `Unavailable(TestMode)` for test sessions, `Unavailable(BeforeFirstUnlock)` while the user is locked, otherwise `Unavailable(CatalogueNotLoaded)` (the real `snoozeAvailability` in Epic 4 keeps this precedence)
**And** the ringing screen renders `button-snooze-disabled` with the leading `lock` icon and "Unlock your phone to snooze", and TalkBack reads "Snooze unavailable, Unlock your phone to snooze"; a test session before unlock still shows "Test · no charge"

**Given** a session with any ring before first unlock
**When** `SessionRecorder` writes the history row
**Then** `direct_boot` is true (also when only a restored ring was before unlock), and false otherwise (test)

**Given** the before-first-unlock ringing screen
**When** Roborazzi and semantic tests run
**Then** screenshots exist in Sunrise at 100% and 200% font scale with the lock-icon snooze control, targets are ≥ 64 dp, and TalkBack order is clock, "I'm up", snooze
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 2.4: Unlocking during a ring makes snooze available in place

As a user,
I want the snooze button to switch on as soon as I unlock my phone, without the wake screen going away,
So that a restart never takes away the choices I normally have.
**Refs:** FR-ALM-11, FR-SES-10, FR-MSG-4, AD-2, AD-7, AD-15, UX-DR14, UX-DR78, UX-DR93 · **Priority:** Must · **Verify:** auto (lock-screen behaviour is human-verify in Story 2.13)

**Acceptance Criteria:**

**Given** a session with `beforeFirstUnlock = true`
**When** `WakeService` runs
**Then** it registers a context-registered `UserUnlockedReceiver` for `ACTION_USER_UNLOCKED` (not deliverable to manifest receivers), unregisters it when the session ends, and also treats `BOOT_COMPLETED` and `WakeActivity.onResume` with `UserManager.isUserUnlocked() == true` as unlock signals
**And** each signal dispatches `UserUnlocked` to `SessionEngine`; repeated signals are ignored and logged per AD-2 and never throw

**Given** `Ringing` before first unlock
**When** `UserUnlocked` is dispatched
**Then** the AD-2 row applies: `beforeFirstUnlock` stays recorded for history, the one-shot effect "lift Direct Boot substitutions at next check step" is scheduled so the substituted check and default sound stay for the current ring and the next ring (after a snooze or merge) uses the chosen sound and check, and "init billing" calls `Billing.init()` (a no-op on the Epic 1 `UnavailableBilling`, recorded by `FakeBilling` in tests)
**And** billing and Firebase also initialise on unlock outside the table (AD-15), so an unlock during `Grace` or `Loud`, where AD-2 has no `UserUnlocked` row and the event is ignored and logged, still initialises them

**Given** the wake screen is showing
**When** the user unlocks in `Ringing`, `Grace` or `Loud`
**Then** the snooze control re-renders in place from `SessionEngine`'s combined state-and-environment availability flow, without the screen closing: in this epic it becomes "Snooze unavailable: prices not loaded yet" (the catalogue arrives in Epic 4), and with a fake policy returning `Available(price)` it becomes "Snooze · {price}" (screenshot)
**And** a Robolectric test flips `FakeUserLockState` while `WakeActivity` is resumed and asserts the activity is not finished or recreated and the label changes within one frame

**Given** Roborazzi tests
**When** they record the before/after-unlock pair
**Then** screenshots exist for "Unlock your phone to snooze" → "Snooze unavailable: prices not loaded yet" and → "Snooze · {price}" (fake) in Sunrise at 100% and 200% font scale
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 2.5: Leave the alarm and come back through the notification

As a user,
I want to switch to another app during the alarm and get back to it with one tap,
So that I can check a message without the alarm stopping or taking over my phone.
**Refs:** FR-SES-4, FR-SES-9, FR-MSG-4, NFR-7, NFR-13, AD-5, AD-11, UX-DR24, UX-DR74 · **Priority:** Must · **Verify:** auto, plus (human-verify) heads-up behaviour in Story 2.13

**Acceptance Criteria:**

**Given** a session in `Ringing`, `Grace` or `Loud` with `WakeActivity` in the foreground
**When** the user presses Home, switches to another app or opens Recents
**Then** `WakeActivity` goes to the background, `WakeService` stays in the foreground, the sound keeps playing (Robolectric asserts the player is playing after `onPause`/`onStop`), and nothing starts an activity from the background (Story 2.11 guard)
**And** Back on `WakeActivity` still does nothing and does not finish it

**Given** the ongoing notification (Story 1.14)
**When** the user taps it, or the full-screen intent fires again
**Then** exactly one `WakeActivity` instance exists (`launchMode="singleTask"` with its own task affinity; tapping three times leaves one instance, test), and it renders the current state from `SessionEngine.state`
**And** an instrumented test on the Gradle Managed Device fires a debug alarm, presses Home, opens the notification shade and taps the notification with UiAutomator, and asserts `WakeActivity` is resumed within 1,000 ms (FR-SES-4)

**Given** Android 14+ where the user can swipe the ongoing notification away
**When** it is dismissed
**Then** the sound continues, the notification's `deleteIntent` makes `WakeService` post it again immediately, and the "wake UI shown" entry effect re-posts it on the next heartbeat if it is still missing (Robolectric test delivers the delete intent and asserts the notification is back and the player still plays)
**And** the notification never has an action that stops or lowers the sound

**Given** the user opens the app from the launcher icon during `Ringing`, `Grace` or `Loud`
**When** `MainActivity` resumes
**Then** it starts `WakeActivity` from the foreground within 1,000 ms (allowed: the user opened the app), and in `Snoozed` it shows the session panel from Story 2.6 instead
**And** the notification text stays "{time} alarm · Tap to return to your alarm" in every ringing state (EXPERIENCE.md Key strings)
**And** `./gradlew qualityGate` passes

### Story 2.6: Lock the app to "Alarm in progress" during a session

As a user,
I want the app to show only the alarm while a session is running,
So that I can't delete, disable or edit my way out of it, while every other app still works.
**Refs:** FR-SES-3, FR-SES-9, FR-MSG-4, NFR-9, AD-11, AD-16, UX-DR34, UX-DR44, UX-DR51, UX-DR61, UX-DR64, UX-DR66, UX-DR67, UX-DR84 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** `SessionEngine.state` is not `Idle`
**When** `MainActivity` is shown (and in `Ringing`, `Grace` or `Loud` right after it has forwarded to the wake screen per Story 2.5)
**Then** the Navigation 3 back stack is replaced by a single `SessionInProgress` route showing `panel-session-in-progress`: a `surface` card, `rounded.md`, "Alarm in progress" in `headline` and one `button-filled` "Back to alarm" (EXPERIENCE.md Key strings) that opens `WakeActivity`
**And** the `nav-bar`, once it exists (Epics 5 and 6), is hidden, and no other route is reachable: a `SessionLockTest` iterates every `Route` subclass (so routes added later are covered automatically) and asserts that navigating to it while a session is active leaves the `SessionInProgress` route on screen
**And** in `Snoozed` "Back to alarm" opens `WakeActivity` on the current state (the Snoozed wake screen content arrives in Epic 4)

**Given** the Alarm editor or Sound picker is open when an alarm fires
**When** the session starts
**Then** the editor closes without a dialog and its unsaved draft is discarded, any sound preview stops, and after the session ends the app returns to Home
**And** when the session reaches `Idle`, the panel is replaced by Home within one frame

**Given** the core use cases `SaveAlarm`, `SetAlarmEnabled`, `DeleteAlarm` and `DuplicateAlarm`
**When** they run while a session is active
**Then** they return `DomainError.SessionActive` without writing, through one `SessionLockGuard` reading `SessionEngine.state` (defence in depth behind the UI lock)
**And** the `AlarmFiredHandler` still schedules the next occurrence of a repeating alarm and disables a one-time alarm during a session (it does not use these use cases; test)
**And** later mutating use cases (settings, base fee, Delete all data) are required to use the same guard, stated in the guard's KDoc and checked by a unit test that fails when a `core.alarm` or `core.config` use case writes a repository without calling the guard

**Given** the panel
**When** Roborazzi and semantic tests run
**Then** screenshots exist in Light and Dark and at 200% font scale, "Alarm in progress" is a heading for TalkBack, "Back to alarm" is ≥ 48 dp with role button
**And** the lock affects only this app: no lock-task, launcher or overlay behaviour is used (Story 2.11 guard)
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 2.7: Pause the alarm for phone calls

As a user,
I want the alarm to go quiet while I'm on a call and come back when it ends,
So that I can answer my phone without the alarm blasting in my ear.
**Refs:** FR-SES-8, FR-SES-10, FR-ALM-9, FR-MSG-4, NFR-13, AD-2, AD-5, UX-DR35, UX-DR64, UX-DR78 · **Priority:** Must · **Verify:** auto (real calls are human-verify in Story 2.13)

**Acceptance Criteria:**

**Given** `AndroidAlarmPlayer`
**When** a ring starts
**Then** it requests audio focus with `AUDIOFOCUS_GAIN`, `USAGE_ALARM` attributes and `setWillPauseWhenDucked(false)`, and never lowers its own gain on a focus change

**Given** a `CallDetector` adapter in `:androidApp` using only `AudioManager.getMode()`
**When** audio focus is lost, the mode changes (`addOnModeChangedListener` on API 31+) or a ring begins
**Then** it reports a call when the mode is `MODE_IN_CALL`, `MODE_IN_COMMUNICATION` (VoIP) or `MODE_RINGTONE` (incoming call ringing), and dispatches `CallStarted`; when the mode returns to `MODE_NORMAL` it dispatches `CallEnded`
**And** on API 26–30, while paused, the mode is re-checked once per second and on every focus gain, so call end is detected without a listener
**And** a call already in progress when a ring begins (for example a snooze ending mid-call) pauses the ring before the first audible frame (test)
**And** `READ_PHONE_STATE`, `TelephonyManager.listen` and `registerTelephonyCallback` are never used (Story 2.11 detekt rule plus the Story 1.2 permission fixture)

**Given** another app takes audio focus with the mode `MODE_NORMAL` (video, music, navigation prompt)
**When** the focus loss arrives
**Then** the alarm keeps playing at the set volume on the alarm stream, is not paused or ducked, and no session event is dispatched (Robolectric with `ShadowAudioManager`)

**Given** `Ringing`, `Grace` or `Loud`
**When** `CallStarted` is dispatched
**Then** the sound and vibration pause, the grace countdown and the 30-minute timer are frozen (Epic 1 reducer), and every wake screen shows a Sunrise `note-inline` "Paused for your call. Rings again when it ends." (EXPERIENCE.md Key strings), announced politely by TalkBack
**And** on `CallEnded` the sound resumes at the set volume (no ramp) and vibration resumes if on, the grace window continues with its remaining seconds, and the timeout is extended by the call length (test: a 10-minute call during a ring moves the timeout from 30:00 to 40:00)
**And** in `Snoozed` calls change nothing and the snooze keeps counting
**And** there is no cap on the pause length (PRD Q14, revisit after the closed test)

**Given** a session persisted while paused and restored after a kill or reboot
**When** `ProcessRestored` runs and the call has ended in the meantime
**Then** `CallDetector` checks the mode immediately and dispatches `CallEnded`, so the restored ring is not stuck paused (test)
**And** Roborazzi screenshots cover the ringing screen with the call note in Sunrise at 100% and 200% font scale
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 2.8: Alarm volume and volume keys on the wake screen

As a user,
I want the alarm to stay at the volume I set, with the volume keys doing nothing only while the alarm screen is in front,
So that I can't quietly turn it down from the wake screen, and the rest of my phone behaves normally.
**Refs:** FR-SES-6, NFR-13, AD-5, UX-DR69, UX-DR75 · **Priority:** Must · **Verify:** auto (real keys are human-verify in Story 2.13)

**Acceptance Criteria:**

**Given** a session
**When** a ring starts (first ring, re-ring after a snooze or merge, restored ring) or a grace window ends (`Grace` → `Loud`)
**Then** `AndroidAlarmPlayer` sets the alarm stream to the session's `volumePercent` and the player gain to full (after the ramp, for a first ring with `gradualVolume`)
**And** the volume is never re-applied continuously: a test changes the alarm stream volume with `ShadowAudioManager` during `Loud` and asserts it is not reset until the next ring start or grace end
**And** the user's previous alarm-stream volume is still restored when the session ends (Story 1.14)

**Given** `WakeActivity` is resumed
**When** `KEYCODE_VOLUME_UP`, `KEYCODE_VOLUME_DOWN` or `KEYCODE_VOLUME_MUTE` is pressed
**Then** the key is consumed and does nothing (no stream change, no `UserInteracted`)
**And** when both volume keys are held together (the accessibility shortcut), neither key is consumed from the moment the second key goes down until both are released (UX-DR69; test with synthesized `KeyEvent`s)
**And** after `onPause` the activity consumes nothing, and the app never registers a `MediaSession`, `VolumeProvider` or any global key capture (test inspects the merged manifest and detekt bans the APIs in `com.payper.snooze.android.wake`)

**Given** `Grace`
**When** the entry effects run
**Then** vibration continues during the grace window only if `vibrateInGrace` is true in the frozen config and stops otherwise, and it resumes in `Loud` when `vibration` is on (Robolectric with `ShadowVibrator` for both values; the per-alarm switch arrives in Epic 3)

**Given** wired or Bluetooth headphones are connected
**When** the alarm plays
**Then** the app leaves routing to the system for `USAGE_ALARM` and never calls `setPreferredDevice`, `setSpeakerphoneOn` or `setCommunicationDevice` (NFR-13 "never reroute audio"; enforced by the Story 2.11 detekt rule)
**And** `./gradlew qualityGate` passes

### Story 2.9: Merge an alarm that rings during a session

As a user with more than one alarm,
I want a second alarm that goes off during my morning to join the current one instead of starting over,
So that I never pay twice or get a new free grace window because two alarms overlapped.
**Refs:** FR-SES-7, FR-SES-10, FR-PRG-1, AD-2, AD-4, AD-6, AD-18 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** `:data`
**When** the merge log is added
**Then** `app.db` migrates from version 2 to 3 adding `session_merge` (`session_id`, `alarm_id`, `scheduled_at`, `merged_at`; primary key `session_id` + `alarm_id` + `scheduled_at`), with the exported v3 schema and a Room migration test from v2 with alarms and history preserved
**And** the "record merged occurrence" effect writes it only through `SessionRecorder` (the Story 1.13 single-writer scan is extended to this table), and replaying the effect after a crash leaves one row (test)

**Given** a session in `Ringing`, `Grace` or `Loud`
**When** another enabled alarm's occurrence fires and `WakeService` dispatches `OverlapAlarmFired`
**Then** the state, sound, volume, frozen config, fee ladder, check progress, grace countdown and 30-minute timeout are unchanged (the merge is not a user interaction), one `session_merge` row is written, and the `AlarmFiredHandler` schedules that alarm's next occurrence (repeating) or disables it (one-time)
**And** the merged alarm's own settings (sound, snooze length, grace, checks) are ignored for this session (test with different values)

**Given** a session in `Snoozed`
**When** `OverlapAlarmFired` is dispatched
**Then** the snooze ends early and the session enters `Ringing(ringIndex + 1, noGraceThisRing = true)` now: no fee (`snoozesGranted` unchanged, `FakeBilling.launch` never called), the heartbeat slot replaces the snooze-end slot, a fresh 30-minute deadline starts, and "I'm up" goes straight to `Loud` without a grace window
**And** one `session_merge` row is written

**Given** two alarms scheduled for the same minute
**When** both receivers deliver within milliseconds
**Then** `SessionEngine`'s mutex yields exactly one session and one merge row, in either delivery order (test with both orders)
**And** an overlap delivered into a killed process is merged after restore (Story 2.1), and a disabled or deleted alarm that fires during a session writes no merge row and is logged (Story 1.14 rule)
**And** a real alarm that fires during a test session is merged like any other and recorded in `session_merge`, and the session's outcome stays Test (documented in the test name)
**And** the Day detail note "{time} alarm merged into this session" is shown in Epic 6 from this table
**And** `./gradlew qualityGate` passes

### Story 2.10: Session conflict rules end to end

As the owner,
I want every PRD §6.4 conflict rule exercised through the real wake runtime with fakes,
So that the pieces from this epic and Epic 1 are proven to agree before payments arrive.
**Refs:** FR-SES-10, FR-SES-1, FR-SES-7, FR-SES-8, FR-ALM-11, FR-RNG-5 (core behaviour), AD-2, AD-3, AD-7 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** a Robolectric `SessionConflictScenariosTest` that drives `WakeService`, the real `SessionEngine`, Room `runtime.db` and `app.db` on temporary device-protected paths, `FakeBilling`, a fake `SnoozeAvailabilityPolicy` returning `Available(price)`, `FakeUserLockState`, `ShadowAudioManager` and the fake clocks
**When** each PRD §6.4 row runs as one named scenario
**Then** these pass:
1. Payment in progress when the grace window ends: `PayConfirmed` during `Grace` with `FakeBilling` holding the result, grace elapses → `Loud` at full set volume while `paying` is set; then `PurchaseCancelled` → `Loud` continues ringing.
2. Purchase granted during a check: `Loud` at step 2 → `PurchaseGranted` → `Snoozed`, sound off, `CheckRun` progress discarded with new seeds; at snooze end → `Ringing(2)`; "I'm up" → a new `Grace`.
3. Overlapping alarm during `Loud` (merged, nothing changes) and during `Snoozed` (re-rings now, no fee, no grace).
4. Call during `Grace`: countdown frozen for the call and resumes with the same remaining seconds.
5. Reboot or process death: a snooze end already past rings immediately; a restored ring gets a fresh 30-minute timer; `paying` is cleared and billing is not relaunched.
6. Before first unlock: default sound, "Unlock your phone to snooze"; unlock → snooze availability changes in place and the substituted plan stays for the ring.

**Given** combined conflicts
**When** they run
**Then** these also pass: a call during payment (grace and timeout frozen, `paying` kept until the billing result); an overlap during a call (merged, still paused); a reboot while paused with the call over (resumes ringing); a clock change of +2 h during a snooze (re-rings on monotonic time)
**And** every scenario ends by asserting the `session_history` row (outcome, `snooze_count`, `direct_boot`) and `session_merge` rows, and that `runtime.db` is empty after `Recorded`
**And** `./gradlew qualityGate` passes

### Story 2.11: No-hostage guard: the phone stays usable

As a user (and as Google Play reviewers),
I want proof that the alarm never blocks Home, Recents, calls, the emergency dialer or other apps,
So that the app stays within Play policy and my phone is always mine.
**Refs:** FR-SES-9, NFR-13, AD-5, AD-14, UX-DR69, UX-DR74 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** the Story 1.2 `checkPermissionAllowlist` task
**When** it is extended
**Then** fixture tests prove that each of `SYSTEM_ALERT_WINDOW`, `READ_PHONE_STATE`, `REORDER_TASKS`, `DISABLE_KEYGUARD`, `PACKAGE_USAGE_STATS`, `KILL_BACKGROUND_PROCESSES` and any `MANAGE_DEVICE_POLICY_*` permission fails the check
**And** the merged-manifest check also fails on an intent filter with `CATEGORY_HOME` (the app posing as a launcher), on any `lockTaskMode` other than the default, on `stopWithTask="true"` for `WakeService`, and on any component protected by `BIND_ACCESSIBILITY_SERVICE` or `BIND_DEVICE_ADMIN` (Story 1.2 rules kept), each with a failing fixture

**Given** a `NoHostageApis` detekt rule in `:detekt-rules`
**When** app code uses `startLockTask`, `setLockTaskPackages`, `TYPE_APPLICATION_OVERLAY`, `TYPE_SYSTEM_ALERT`, `WindowManager.addView`, `ActivityManager.moveTaskToFront`, `DevicePolicyManager`, `AccessibilityService`, `KeyguardManager.newKeyguardLock`, `TelephonyManager.listen`, `registerTelephonyCallback`, `setPreferredDevice`, `setSpeakerphoneOn`, `setCommunicationDevice`, or calls `startActivity`/`startActivities` from a `Service`, a `BroadcastReceiver` or any class under `com.payper.snooze.android.receiver` or `com.payper.snooze.android.wake` other than `WakeActivity`
**Then** detekt fails with a message citing NFR-13, and each banned call has a violating and a compliant snippet test
**And** overriding `onKeyDown`/`dispatchKeyEvent` for `KEYCODE_HOME`, `KEYCODE_APP_SWITCH` or `KEYCODE_POWER` is also flagged

**Given** a Robolectric test of a ringing session
**When** `WakeActivity` is paused and stopped and 5 minutes of fake time pass with heartbeats
**Then** `ShadowApplication.getNextStartedActivity()` is null (no activity start from the service or receivers), and the player is still playing

**Given** an instrumented test on the Gradle Managed Device with a debug-fired ringing session
**When** the test presses Home, then launches the system Settings app and the dialer (`Intent.ACTION_DIAL`, no call placed)
**Then** the launcher, Settings and the dialer each come to the foreground and stay there for 10 s without `WakeActivity` resuming, and a debug-only `WakeStatus` query (debug source set, absent from release like `DebugFireReceiver`) reports the player playing throughout
**And** `./gradlew qualityGate` passes

### Story 2.12: Back up alarms and history, never the session or media

As a user,
I want my alarms, settings and history to come back on a new phone, without a half-finished alarm coming with them,
So that switching phones is painless and never restores a stale session.
**Refs:** NFR-4, NFR-14, AD-4, AD-6 · **Priority:** Must · **Verify:** auto, plus (human-verify) a real restore in Story 2.13

**Acceptance Criteria:**

**Given** `dataExtractionRules` (API 31+, both `cloud-backup` and `device-transfer` sections) and `fullBackupContent` (API ≤ 30)
**When** the rules are finalised
**Then** they include, in the device-protected domain only, `app.db` with its `-wal` file and the device-protected DataStore files created so far (settings and the missed-note dismissals from Story 1.16)
**And** they exclude `runtime.db` with its `-wal` and `-shm` files, and the whole credential-protected storage (`root`, `file`, `database`, `sharedpref` domains), which is where media (House Hunt photos, recordings, custom sounds) will live (AD-6)
**And** the Robolectric XML test from Stories 1.7 and 1.12 is updated to assert every include and exclude entry in both files

**Given** a `BackupRulesCoverageTest`
**When** it runs a full simulated morning (alarm saved, session rung, check done, history recorded, missed note dismissed) with real Room and DataStore on a temporary device-protected directory
**Then** every file created under the app's storage is either included or explicitly excluded by the rules, and the test fails naming any new file that is neither, so later stories must add their files to the rules in the same change

**Given** the manifest has `android:allowBackup="true"`, `android:fullBackupOnly="true"` and `android:backupAgent` set to `PpsBackupAgent` (Auto Backup file handling unchanged)
**When** a restore finishes
**Then** `PpsBackupAgent.onRestoreFinished()` runs `rescheduleAll()` so restored enabled alarms are armed without the user opening the app, and `runtime.db` is absent, so the app starts `Idle` (Robolectric test with `FakeAlarmScheduler`)
**And** restored `session_history` and `session_merge` rows are kept unchanged
**And** `./gradlew qualityGate` passes

### Story 2.13: Epic 2 escape-attempts checklist on the device matrix

As the owner,
I want to try every way out of the alarm on real phones,
So that I know the only free exits are the ones we accept, and the phone stays usable.
**Refs:** FR-ALM-11, FR-SES-1–10, NFR-1, NFR-2, NFR-7, NFR-8, NFR-13, NFR-14; PRD §6.4, Spike S2 · **Priority:** Must · **Verify:** human-verify

**Acceptance Criteria:**

**Given** the latest `main` debug build on each device of the matrix (Pixel, Samsung, Xiaomi, budget device) and `docs/spikes/S2.md` for reference
**When** the owner runs the checklist
**Then** for each item the story file records pass/fail, device, Android version and date:
1. OEM Task Manager "Stop" during a ring (Samsung Device care, Xiaomi Security, others where present): the alarm rings again within 60 s on the same step; if the OEM action is a force-stop (the alarm does not return), record it as the accepted force-stop escape.
2. Swipe the app from Recents while ringing, in the grace window and on the check: the sound never stops, or it returns within 60 s on the same step.
3. `adb shell am kill com.payper.snooze` and `adb shell kill` on the process during a ring: it re-rings within 60 s with the same snooze count and step, and no "restored" message.
4. Reboot before unlock: set an alarm 5 minutes ahead with a system ringtone, reboot and don't unlock. It rings on the lock screen with the default built-in sound and a lock-icon "Unlock your phone to snooze".
5. From item 4, unlock with PIN or fingerprint on the lock screen: the wake screen stays on top and the snooze label changes in place to "Snooze unavailable: prices not loaded yet".
6. Restart the phone while an alarm rings: after boot, before unlock, it rings again; record the seconds from lock screen shown to sound.
7. Power off during a ring for 10 minutes, power on: it rings again.
8. During a ring change the time by +1 h and by −1 h, and change the time zone: it keeps ringing; on one device, time the no-interaction stop with a stopwatch: 30 real minutes.
9. Bluetooth headphones connected, then wired headphones: the alarm is audible and never silent; record where the sound plays (speaker, headphones or both).
10. Incoming call from a second phone during a ring: sound pauses and "Paused for your call. Rings again when it ends." shows; reject it and the alarm resumes; repeat, answer, talk 1 minute, hang up and the alarm resumes. Repeat with a WhatsApp or other VoIP call.
11. Start a video in YouTube (or another video app) during a ring, and have one playing when the alarm fires: the alarm keeps playing at full volume, not paused or ducked.
12. On the wake screen the volume keys do nothing; after pressing Home they change volume normally; with the accessibility shortcut enabled, holding both volume keys for 3 s triggers it.
13. Leave the wake screen (Home, another app, Recents): the sound continues; tapping the notification returns to the wake screen within 1 s (screen recording); on Android 14+ swiping the notification away keeps the sound and the notification comes back; the launcher icon also opens the wake screen.
14. With the phone unlocked and in use when the alarm fires, the heads-up notification appears and tapping it opens the wake screen.
15. Opening the app during a session shows only the wake screen or "Alarm in progress" and "Back to alarm"; no alarm can be edited, disabled or deleted.
16. During a ring: open other apps, place a normal call from the dialer, and open (but do not use) the emergency dialer from the lock screen; all work.
17. Two alarms one minute apart: the second merges silently into one session.
18. `adb shell bmgr backupnow com.payper.snooze`, uninstall, reinstall with restore: alarms and history are back, alarms ring without opening the app, and no session is restored.
19. After each session ends, `adb shell dumpsys activity services com.payper.snooze` shows no running service (NFR-8).
20. Force-stop from App info during a ring stops the alarm (accepted escape) and the next scheduled alarm still rings after the app is next opened.

**Given** any failed item
**When** the owner records it
**Then** each failure becomes a new bug story referencing this item, contradictions with the Architecture Spine go through `bmad-correct-course`, and this story stays open until all items pass or are explicitly waived by the owner in the story file
**And** snoozed-state escape items (kill, reboot and swipe during a paid snooze) are repeated in the Epic 4 checklist, since paid snoozes arrive there
**And** automation never marks this story done
