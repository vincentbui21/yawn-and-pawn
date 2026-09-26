# Epic 1 Context: Set an alarm that rings

<!-- Compiled from planning artifacts. Edit freely. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Deliver an Android alarm clock (Yawn & Pawn, a pay-per-snooze alarm) where the user can create alarms that ring on time over the lock screen, even in Doze, silent mode, Do Not Disturb, after reboot and across time and time-zone changes. The user can pick and preview built-in sounds, run a no-charge test alarm, and end the alarm with "I'm up". The epic also lays the foundation every later epic plugs into: the KMP project and one-command quality gate, CI and allowlists, generated design tokens, the Play Console record, two decision spikes (payment over the lock screen, device reliability), platform-free time and occurrence math, the complete wake-session state machine with write-ahead persistence, the single history writer, and the wake runtime. Real checks, real billing and escape protections come in later epics. Epic 1 uses placeholders for them.

## Stories

- Story 1.1: Scaffold the KMP project with a quality gate
- Story 1.2: CI pipeline and dependency and permission allowlists
- Story 1.3: Generated design tokens and PpsTheme
- Story 1.4: Google Play Console setup (owner task)
- Story 1.5: Spike S1: pay for a snooze over the lock screen
- Story 1.6: Time ports, deadlines and alarm occurrence math in core
- Story 1.7: Store alarms in app.db
- Story 1.8: Create and edit an alarm
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

## Requirements & Constraints

- The user can create, edit, duplicate, delete and enable/disable several alarms. Each alarm has: time, repeat days (none means one-time), a label of up to 40 characters, sound, volume, a gradual-volume switch (default on, ramps from a start level to the set level over up to 30 s), vibration, a snooze length of 5/9/10/15 min (default 9) and a grace window of 15-30 s (default 20). Checks, fee ladder and motivation fields arrive in later epics.
- Alarms must fire at the exact scheduled time on the alarm audio stream, independent of the phone's media and ringer volume. Target: sound starts within 2 s of the scheduled time and the ringing screen shows within 1 s of the trigger. Alarms survive reboot, app update, time and time-zone changes, and DST. In a DST gap the alarm shifts forward by the gap. In a DST overlap it rings only at the earlier instance.
- Home shows the next alarm as a relative countdown ("Rings in 7 h 12 min", rounded up to the minute). It uses the same occurrence function as the scheduler.
- "Test alarm" runs the full flow 10 s later, using the editor's current values even if unsaved. No payment is possible: Snooze shows disabled as "Test · no charge". History logs it as Test.
- If a ring gets no interaction for 30 min, it stops and is logged Missed. Every interaction resets the timer, and so does every new ring. Snooze time and call time never count. The timer uses monotonic time.
- Sounds: at least 10 bundled royalty-free sounds plus system alarm ringtones. The user can preview each one. Every bundled sound must peak at -3 dBFS or higher and have integrated loudness of -14 LUFS or higher, enforced in the quality gate. A missing or broken sound falls back to the default sound within the same ring. The alarm is never silent.
- Every session is written to history exactly once (upsert by session id, safe to replay). Outcomes: OnTime, Snoozed, Missed, Skipped, Test.
- Permissions: exact alarms use `USE_EXACT_ALARM` on API 33+ and `SCHEDULE_EXACT_ALARM` (maxSdkVersion 32) on API 31-32. There is no inexact fallback. Request notifications on first enabled-alarm save (API 33+). Check the full-screen intent permission on API 34+. Crashlytics is always on and never sends personal data. Analytics is off until Epic 5.
- No device hostage: never start activities from the background. No overlays, accessibility services, device admin, lock-task, or `READ_PHONE_STATE`.
- No backend and no account. Everything except payment works offline.
- Platform: minSdk 26, targetSdk 36, compileSdk 37. English only. All strings live in resources.
- Business logic in `:core` needs at least 90% line coverage (Kover). Every feature needs CLI-runnable tests (unit tests with fakes, Robolectric, emulator).

## Technical Decisions

- **Modules:** AGP 9 KMP project with modules `:core` (commonMain only, jvm target), `:data`, `:composeApp`, `:androidApp`, `:testing` (fakes), plus `:detekt-rules`, `tools/tokens`, `tools/play-catalog`, `config/`, `docs/` and `data/schemas/`. The dependency graph is exactly: androidApp → composeApp, data, core; composeApp → core; data → core; testing → core. `:core` may depend only on stdlib, coroutines, datetime and serialization. `:composeApp` never depends on `:data`. The Gradle check `verifyCoreDependencies` enforces this.
- **Pinned versions:** Kotlin 2.4.20, CMP 1.12.1, AGP 9.3.3, Gradle 9.7.1, JDK 17, Room KMP 3.0.3 (fallback 2.8.5, decision doc for OQ-2), Navigation 3 1.1.2, Koin 4.2.2, Firebase BoM 34.19.0, Roborazzi 1.74.0 (fallback Compose Preview screenshots, decision doc for OQ-3).
- **Package and versioning:** package and applicationId `com.yawnandpawn.app`. The debug build has no applicationId suffix. `versionCode = major*10000 + minor*100 + patch`.
- **Quality gate:** `./gradlew qualityGate` is the definition of done. It runs Spotless, detekt, all host tests, Kover, lint and assembleDebug, plus the token diff, the dependency and permission allowlists and the loudness script. Later checks register as dependencies of it.
- **Time:** only through ports: `Clock`, `MonotonicClock`, `BootCounter`, `TimeZoneProvider`. detekt bans `Clock.System`, `System.currentTimeMillis`, `SystemClock` and `TimeZone.currentSystemDefault()` outside `android.*` adapters. A deadline is stored as `Deadline(wallMillis, elapsedMillis, bootCount)`. On the same boot it compares monotonic time. After a reboot it compares wall time.
- **Scheduling:** the `AlarmScheduler` port is the only way to schedule. The adapter uses only `setAlarmClock()`. Each alarm uses its own stable `requestCode`, and the session slot and the test alarm each have a reserved code. `rescheduleAll()` is idempotent and runs on boot, time and time-zone changes, package replace, exact-alarm permission change and app start. Receivers are `directBootAware`, use `goAsync()` and never start an FGS.
- **Session:** the pure reducer is `reduce(state, event, now) → Transition(state, oneShotEffects)`, plus idempotent `entryEffects(state)`. States: Idle, Ringing, Grace, Loud, Snoozed, Completed, Missed. There is one parameterised test per row of the 31-row AD-2 table, plus a table-coverage test. Unmatched events are ignored and logged, never thrown. Guards come from injected policies, and each has an Epic 1 placeholder:
  - `SnoozeAvailabilityPolicy`: returns TestMode or CatalogueNotLoaded.
  - `CheckValidator`: a single `Placeholder` step.
  - `FallbackPolicy`: fallback not allowed.
  - `FeeLadder`: an interface only.
  - `Billing`: bound to `UnavailableBilling`.
- **SessionEngine:** a single instance serialized by a Mutex. For each event it commits to `runtime.db` in one transaction, then runs effects. If the commit fails, no effects run. `restore()` runs only entry effects and never replays one-shot effects. `SessionConfig` is frozen at `AlarmFired` by a pure `ConfigResolver`.
- **Storage:** Room KMP databases in device-protected storage. `app.db` holds `alarm` (v1) and `session_history` (v2, with a migration test) and is backed up. `runtime.db` holds `active_session` (state as JSON) and is excluded from backup. Settings and flags live in device-protected DataStore. Only `:data` touches databases. Schemas are exported to `data/schemas/`.
- **Wake runtime:** `WakeService` is a foreground service of type `mediaPlayback`, pending the Spike S2 decision. It owns the only `AlarmPlayer`, which uses `USAGE_ALARM`. The player sets the alarm-stream volume at ring start and restores it when the session ends. The ramp comes from the pure `rampGain`. The ongoing notification has a full-screen intent. `WakeActivity` shows over the lock screen and renders from in-memory state with no loading state. If an uncaught exception reaches the `WakeService` boundary: report it, switch to the default sound and dispatch `ProcessRestored`. When the session ends, the service stops (no idle service).
- **Errors and DI:** ports return `Outcome<T, DomainError>`. Koin provides one module val per module, and `:core` has no Koin. Tests build objects directly with the fakes in `:testing`.
- **UI:** one ViewModel per screen, with `StateFlow<UiState>`, `onIntent` and `Channel<UiEffect>`. Navigation 3 uses a sealed `@Serializable Route`. The wake flow lives outside the nav graph.
- **Telemetry:** Firebase initialises only after the user unlocks. Remove `FirebaseInitProvider`. The Firebase plugins apply only when `google-services.json` exists.
- **Secrets:** never in the repo. They live only in GitHub Actions secrets.
- **Conventions:**
  - Naming: ports use plain names, adapters are `Android<Port>` or `Room<Port>`, fakes are `Fake<Port>`.
  - Events use past tense.
  - IDs are UUID v4.
  - Test names are backticked sentences.
  - Commits are conventional, with the story id.

## UX & Interaction Patterns

- **Tokens and theme:** tokens are generated from the DESIGN.md frontmatter into a committed `PpsTokens.kt`, and CI fails on any diff. `PpsTheme` has three token sets: Light, Dark and Sunrise. Dynamic colour is off. Every wake screen always uses Sunrise. detekt bans raw `Color(0x…)`, raw radii and raw `sp` outside the theme package.
- **Type, shape and spacing:** Geist with tabular figures for clocks, times and prices. An 8-style type ramp. Radii are 8/16/28 dp or full. Spacing is on a 4 dp grid. Touch targets are 48 dp minimum, 64 dp for wake actions and 72 dp for "I'm up".
- **Ringing screen:** the clock (`clock-xl`, capped at 1.3× font scale) and the optional gradient sit in the top 40%. The flat thumb zone at the bottom holds "I'm up", which is the largest element and always enabled. The disabled snooze control sits 16 dp below it and states its reason. Back does nothing, while Home and Recents still work. TalkBack focuses the clock first, then "I'm up". At 200% font scale both actions stay on screen. When animations are off, every transition is instant.
- **Components and states:** `card-alarm`, `fab`, `chip-day`, `segmented-control`, `slider`, `switch`, `time-picker`, `note-inline`, `dialog-confirm` (safe action is the default dismiss; destructive actions say "This is logged."), `banner-warning` (non-dismissible, clears itself), `sound-row`, `snackbar`. The empty state reads "No alarms yet." and a one-time alarm shows "Rings tomorrow at {time}.".
- **Copy on every UI story:** strings live in resources and match the EXPERIENCE.md key strings verbatim. `CopyRulesTest` must pass. It enforces no em dashes, no banned hype words, no hard-coded currency symbols, headlines of 8 words or fewer and body text of 25 words or fewer. Strings that EXPERIENCE.md doesn't define are flagged for the owner.
- **Done checklist on every UI story:** copy the `pps-design` Done checklist into the story file with every item ticked. Take Roborazzi screenshots in Light and Dark (Sunrise for wake screens) and at 200% font scale.

## Cross-Story Dependencies

- Story 1.1 comes before everything else. Story 1.2 adds the allowlists that later stories must update whenever they add permissions or dependencies (Firebase in 1.19, the permissions for 1.10 and 1.14).
- Story 1.3 must land before any UI story (1.8, 1.9, 1.15-1.19), because it provides the theme and `CopyRulesTest`.
- Story 1.4 (Play Console, license testers) comes before Spike 1.5. The Decision section of 1.5 may add `UnlockRequested`/`UnlockFailed` rows to the state machine, so it must finish before 1.11. Contradictions go through correct-course.
- Story 1.6 feeds 1.9 (countdown), 1.10 (scheduling) and 1.11 (deadlines). Story 1.7 feeds 1.8, 1.9 and 1.10.
- The session chain runs 1.11 → 1.12 → 1.13, then the wake runtime 1.14 → 1.15 → 1.16. Story 1.10's `AlarmFiredHandler` is rebound to the wake runtime in 1.14. Story 1.17 extends 1.14's default-sound fallback. Story 1.18 depends on the test-mode policy and the scheduler.
- Story 1.20 (Spike S2) decides the foreground-service type. Changing it from `mediaPlayback` needs a new story before Epic 2.
- Story 1.21 runs last. It and stories 1.4, 1.5 and 1.20 are human-verify only: automation never marks them done, and failures become bug stories.
- **Downstream:** Epic 2 adds Direct Boot, `LOCKED_BOOT_COMPLETED` and escape handling on this state machine without adding states. Epic 3 replaces the placeholder check. Epic 4 replaces the fake FeeLadder and billing.
- **Planning inconsistencies to resolve:**
  - Story 1.3 writes the tokens file under `com/payper/snooze/ui/theme/`, but the decided package root is `com.yawnandpawn.app`.
  - Stories 1.20 and 1.21 list the device matrix as a Pixel, a Samsung, a Xiaomi and a budget phone. The NFR says the owner's Samsung Galaxy A57 plus managed emulators, with other makers optional through Firebase Test Lab.
