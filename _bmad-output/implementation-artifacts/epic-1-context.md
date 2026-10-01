# Epic 1 Context: Set an alarm that rings

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Ship an Android alarm clock (Yawn & Pawn, a pay-per-snooze alarm) where the user creates alarms that ring on time over the lock screen, including in Doze, silent mode, Do Not Disturb, after a reboot and across time and time-zone changes. The user picks and previews built-in sounds, runs a test alarm that can never charge, and ends the alarm with "I'm up". The epic also lays the foundation every later epic plugs into: the KMP project and quality gate, CI and allowlists, generated tokens, the Play Console record, two decision spikes, platform-free time and occurrence math, the complete wake-session state machine with write-ahead persistence, the single history writer and the wake runtime. Real checks, billing and escape protections come later and use placeholders here.

## Stories

- Story 1.1: Scaffold the KMP project with a quality gate (done)
- Story 1.2: CI pipeline and dependency and permission allowlists (done)
- Story 1.3: Generated design tokens and PpsTheme (done)
- Story 1.4: Google Play Console setup (owner task, in progress)
- Story 1.5: Spike S1: pay for a snooze over the lock screen
- Story 1.6: Time ports, deadlines and alarm occurrence math in core (done)
- Story 1.7: Store alarms in app.db (done)
- Story 1.8: Create and edit an alarm (done)
- Story 1.9: Alarm list on Home with the next-alarm countdown
- Story 1.10: Schedule alarms exactly and keep them across reboot and clock changes
- Story 1.11: The complete wake-session state machine in core
- Story 1.12: SessionEngine with write-ahead persistence
- Story 1.13: Record every session in history
- Story 1.14: Ring the alarm: WakeService, AlarmPlayer and the ongoing notification
- Story 1.15: Ringing screen over the lock screen with "I'm up"
- Story 1.16: Stop a forgotten alarm after 30 minutes
- Story 1.17: Built-in sound library with preview and a never-silent fallback
- Story 1.18: Test alarm and debug fire-now hook
- Story 1.19: Permissions for reliable alarms and crash reporting
- Story 1.20: Spike S2: alarm reliability on the device matrix
- Story 1.21: Epic 1 device verification checklist

## Design Baseline (read first for every UI story)

Since 2026-10-01 the approved design is **DESIGN.md / EXPERIENCE.md v0.5 plus the design-preview composables already in `:composeApp` (`ui.*`)**. The owner approved them on the phone. A UI story **wires those stateless screens to its ViewModel and real data. It does not redesign them.** Where an older UX requirement or story wording disagrees with the v0.5 spines, the spines win. Any layout change needs the owner's approval before it is built. Things that no longer exist: the FAB, the 3-item nav bar, keyboard time entry, the starting-volume slider, the snoozes bar chart, the outcome legend and CSV export. Copy drafts in `docs/design-preview/copy-to-approve.md` count as approved unless the owner marked one.

## Requirements & Constraints

- **Alarm fields:** time, repeat days (none means one-time), a label of up to 40 characters, sound, volume, vibration, a snooze length of 5/9/10/15 min (default 9) and a grace window of 15 to 30 s (default 20). The UI calls the grace window "Quiet time"; code keeps `graceSeconds`. "Gradually increase volume" (default on) ramps from a fixed 20% of the set volume to the set volume over 30 s. The ramp start is not user-editable.
- **Ringing:** alarms fire at the exact time on the alarm stream, independent of media and ringer volume. Sound starts within 2 s of the scheduled time, and the ringing screen shows within 1 s of the trigger. Alarms survive reboot, app update, time and time-zone changes and DST. In a DST gap the alarm shifts forward by the gap. In a DST overlap it rings only at the earlier instance.
- **Home:** shows the next alarm as "Rings in …", rounded up to the minute, computed with the same occurrence function the scheduler uses.
- **Test alarm:** runs the full flow 10 s later with the editor's current (even unsaved) values. No payment is possible, snooze reads "Test · no charge", and history logs it as Test.
- **Forgotten alarm:** after 30 min with no interaction, the ring stops and is logged Missed. Every interaction and every new ring resets the timer. Snooze time and call time never count, and the timer uses monotonic time.
- **Sounds:** at least 10 bundled royalty-free alarm sounds plus system alarm ringtones, each with a preview. Bundled alarm sounds must peak at −3 dBFS or higher and reach −14 LUFS or higher (checked in the quality gate; UI sounds such as the wheel tick are exempt). A missing or broken sound falls back to the default within the same ring, so the alarm is never silent.
- **History:** each session is written exactly once (upsert by session id, safe to replay). Outcomes are OnTime, Snoozed, Missed, Skipped and Test.
- **Permissions:** `USE_EXACT_ALARM` on API 33+, `SCHEDULE_EXACT_ALARM` (maxSdk 32) on API 31–32, with no inexact fallback. Notifications are requested on the first enabled-alarm save (API 33+). The full-screen intent is checked on API 34+. Crashlytics is always on and sends no personal data; Analytics stays off.
- **No device hostage:** no background activity starts, overlays, accessibility services, device admin, lock-task or `READ_PHONE_STATE`. There is no backend and no account, and everything except payment works offline.
- **Platform:** minSdk 26, targetSdk 36, compileSdk 37, English only, all strings in resources. `:core` needs at least 90% line coverage. Every feature needs tests that run from the CLI.
- **Device matrix:** the owner's Oppo A96 (ColorOS, Android 13) plus Gradle Managed Device emulators (API 26, 31, 34, 36, 37). Other makers are optional through Firebase Test Lab.

## Technical Decisions

- **Modules:** `:core` (commonMain only), `:data`, `:composeApp`, `:androidApp`, `:testing` (fakes). `:core` depends only on stdlib, coroutines, datetime and serialization. `:composeApp` never depends on `:data`. The package root is `com.yawnandpawn.app`, and the debug build has no applicationId suffix.
- **Definition of done:** `./gradlew qualityGate` passes. New permissions or dependencies must update the allowlists in the same change.
- **Time:** read only through the ports `Clock`, `MonotonicClock`, `BootCounter` and `TimeZoneProvider` (detekt bans direct system clocks). A deadline is `Deadline(wallMillis, elapsedMillis, bootCount)`: compare monotonic time on the same boot and wall time after a reboot.
- **Scheduling:** the `AlarmScheduler` port is the only way to schedule, and the adapter uses only `setAlarmClock()`. Each alarm has a stable `requestCode`, never reused (a persisted high-water mark). The session slot and the test alarm each have a reserved code. `rescheduleAll()` is idempotent and runs on boot, time and zone changes, package replace, the exact-alarm permission change and app start. Receivers are `directBootAware`, use `goAsync()` and never start an FGS.
- **Session:** the pure `reduce(state, event, now) → Transition(state, oneShotEffects)` plus idempotent `entryEffects(state)`. States are Idle, Ringing, Grace, Loud, Snoozed, Completed and Missed. Each AD-2 table row has one parameterised test, plus a table-coverage test. Unmatched events are ignored and logged. Guards are injected policies with Epic 1 placeholders: snooze availability is TestMode or CatalogueNotLoaded, the check is a single `Placeholder` step, fallback is not allowed, `FeeLadder` is an interface only, and `Billing` is `UnavailableBilling`. `UnlockRequested`/`UnlockFailed` are **not** added in Epic 1.
- **SessionEngine:** a single instance behind a Mutex. It commits each transition to `runtime.db`, then runs effects. If the commit fails, no effect runs. `restore()` runs entry effects only and never replays one-shot effects. `SessionConfig` is frozen at `AlarmFired` by a pure `ConfigResolver`. `rampStartPercent` stays in the config as a fixed 20, meaning 20% of the set volume.
- **Storage:** Room KMP in device-protected storage. `app.db` holds `alarm` and `session_history` (v2, with a migration test) and is backed up. `runtime.db` holds `active_session` and is excluded from backup. Settings live in device-protected DataStore. Only `:data` touches databases.
- **Wake runtime:** `WakeService` is an FGS of type `mediaPlayback` (pending Spike S2). It owns the single `AlarmPlayer` (`USAGE_ALARM`), which sets the alarm-stream volume at ring start and restores it at the end. The ramp uses the pure `rampGain(elapsed, startFraction, duration)`. The ongoing notification carries a full-screen intent. `WakeActivity` renders from in-memory state with no loading state. An uncaught exception is reported, the player switches to the default sound and `ProcessRestored` is dispatched. The service stops when the session ends.
- **UI:** one ViewModel per screen (`StateFlow<UiState>`, `onIntent`, `Channel<UiEffect>`). The shell has four Navigation 3 tab routes (Alarms, Progress, Settings, You). The centre "+" is an action that pushes the editor route, not a tab. Editor sub-screens are pane state in the editor ViewModel, not routes. Glass blur uses the platform `RenderEffect` on API 31+ with no extra library. The wake flow lives outside the nav graph.
- **Other:** ports return `Outcome<T, DomainError>`. Koin provides one module val per module, and `:core` has no Koin. Firebase initialises only after the user unlocks, and its plugins apply only when `google-services.json` exists. Secrets live only in CI secrets. Naming: `Android<Port>`, `Room<Port>`, `Fake<Port>`. Events are past tense, IDs are UUID v4, test names are backticked sentences, and commits are conventional with the story id.

## UX & Interaction Patterns

- **Shell and Home:** the floating glass nav capsule has five slots: Alarms · Progress · + · Settings · You. "+" opens the editor with defaults from any tab. The capsule is hidden on sub-screens and during the session lock. The Home header collapses on scroll: "Yawn & Pawn" stays pinned with a glass chip, and the countdown sits in the header. Alarm cards are glass and animate in and out. Repeat summaries are "Every day", "Weekdays", "Weekends", "Once" or short day names. Tabs whose stories are not done open their existing screens in an empty or default state and hide any row that would be a dead link.
- **Editor (already built):** grouped cards hold the time wheel (snaps, haptic tick and a quiet tick sound, never a keyboard), repeat, name and vibration. Rows open sub-screens (Sound, Snooze, Wake-up check, Quiet time, Motivation). "Test alarm" is a text button under the cards. Cancel and Save sit in the `SaveCancelPill`, above the keyboard and never over content.
- **Sound sub-screen (`ui/sound`):** the volume slider and the "Gradually increase volume" switch, then Built-in and System sections. Each row has radio selection, a name and a 48 dp preview button.
- **Ringing (`ui/wake`, always Sunrise):** layout per DESIGN.md v0.5. "I'm up" is 72 dp, the largest element, always enabled, in the bottom 40%, with a gentle pulse. The disabled snooze is 64 dp, sits 16 dp below and states its reason, using the explicit `disabled-*-sunrise` tokens (not alpha). The clock is capped at 1.3× font scale. TalkBack focuses the clock, then "I'm up". Back does nothing.
- **Theme and rules:** every screen draws `PpsBackground`. Cards are `glass`, and bars or sheets over moving content are `glass-strong`. Touch targets are at least 48 dp (64 dp for wake actions). Reduced motion makes every animation instant. No raw colours, radii or `sp` outside the theme.
- **Copy:** storage-failure strings are "Couldn't load your alarms." with "Try again", and the snackbar "Couldn't open this alarm.". Empty state: "No alarms yet.". One-time note: "Rings tomorrow at {time}.". Strings match EXPERIENCE.md key strings verbatim and must pass `CopyRulesTest` (no em dashes, no hype words, short headlines).
- **Every UI story:** copy the `pps-design` Done checklist into the story with every item ticked, and add Roborazzi screenshots in Light and Dark (Sunrise for wake screens) and at 200% font scale.

## Cross-Story Dependencies

- **Planned auto-run order:** 1.9 → 1.19, one PR each. 1.11 goes ahead without the S1 unlock rows; if Spike S1 later needs them, they become a small story at the start of Epic 4.
- **Session and wake chain:** 1.11 → 1.12 → 1.13, then 1.14 → 1.15 → 1.16. 1.14 rebinds 1.10's `AlarmFiredHandler` to the wake runtime. 1.17 extends 1.14's default-sound fallback. 1.18 needs the test-mode policy and the scheduler.
- **Inputs to 1.9:** the countdown uses 1.6's occurrence math, and the alarm list uses 1.7's repository. The streak hero joins the Home header in Story 6.3.
- **Deferred items now in story ACs:**
  - 1.10: request-code high-water mark, reboot wall-clock note in `docs/decisions/reboot-clock.md` (carried to 2.2), opening `app.db` while credential storage is locked.
  - 1.12: shared Koin test setup with no per-test `stopKoin()`.
  - 1.13: `docs/decisions/db-downgrade.md`.
  - 1.18: the release build must contain no debug-preview code.
- **Human-verify only (automation never marks them done):** 1.4, 1.5 and 1.20 are owner work, and 1.21 runs last. The owner does them on the Oppo A96 in one end-of-epic sitting. Failures become bug stories. 1.21 also checks the wheel tick, Save staying above the keyboard, edge-to-edge drawing, the nav capsule and collapsing header, and predictive back on sub-screens. If predictive back fails, sub-screens become Navigation 3 routes in a bug story.
- **Spike S2 (1.20):** decides the FGS type. Any change from `mediaPlayback` needs a new story before Epic 2.
- **Downstream:** Epic 2 adds Direct Boot and escape handling with no new states. Epic 3 replaces the placeholder check. Epic 4 replaces the fake FeeLadder and billing.
