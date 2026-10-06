---
title: 'Story 2.7: Pause the alarm for phone calls'
type: 'feature'
created: '2026-10-06'
status: 'done'
baseline_revision: '52f8acafca9623dd20fa802522272f269c92d046'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/spec-1-14-ring-the-alarm-wakeservice-alarmplayer-and-the-ongoing-notification.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-2-11-no-hostage-guard-the-phone-stays-usable.md'
  - '{project-root}/.claude/skills/pps-design/SKILL.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** The reducer already pauses a ring on `CallStarted` and resumes it on `CallEnded` (Epic 1), but nothing sends those events. A call during a ring therefore gets the alarm blasting in the user's ear. Also, after `ProcessRestored`, or a new ring during a call, the pause is lost (deferred item for 2.7).

**Approach:**
- A self-contained `CallDetector` in `:androidApp` (`android/call`) reads only `AudioManager.getMode()`. It never uses `READ_PHONE_STATE` or telephony (Story 2.11 `NoHostageApis`). It reconciles the session with the phone: in a call and not paused sends `CallStarted`; paused and the call over sends `CallEnded`.
- It reconciles on every session state change (new ring, restore), on the player's audio-focus changes, on mode changes (API 31+ listener), and once per second while paused on API 26–30.
- The player requests alarm audio focus and never ducks. A ring that starts during a call stays silent before its first audible frame.
- The approved `ringing-phone-call` note renders from `session.paused`, which already exists, and is now announced politely.

## Boundaries & Constraints

**Always:**
- **In a call** means the audio mode is `MODE_IN_CALL`, `MODE_IN_COMMUNICATION` or `MODE_RINGTONE`; from API 30 also `MODE_CALL_SCREENING`, and from API 33 `MODE_CALL_REDIRECT` and `MODE_COMMUNICATION_REDIRECT` (the phone is still busy with the call). `MODE_NORMAL` is no call. Read through a `CallState` port (`AudioModeCallState`), behind the shared `StuckCallGuard` (see the cap). No other signal is used.
- **Reconciling:** each `CallDetector.check()` runs on `WakeScope` (the main thread) under its own `Mutex`, so it is serialized and never inside an engine effect. Checks only run while the detector is started (they are children of its job; one after `stop()` does nothing). It reads `SessionEngine.state`:
  - Ringing, Grace or Loud, in a call and not paused: dispatch `CallStarted`;
  - paused and no longer in a call: dispatch `CallEnded`;
  - not paused, no call, and the runtime holds the player silent (a ring that opened during a call that ended before `CallStarted`): `WakeRuntime.onCallOver()`;
  - anything else, including `Snoozed`, Idle and Completed: nothing.
  - So a restore (the reducer clears the pause) or a new ring during a call is paused again, and a restored ring whose call ended is never stuck paused.
  - A dispatch that fails (the commit failed) is checked again after 1 s on every API level.
- **Triggers:** every emission of `SessionEngine.state`; every ring start (`WakeRuntime.onRing`, see Lifetime); `AUDIOFOCUS_LOSS*` and `AUDIOFOCUS_GAIN` from the player's focus listener; the API 31+ `addOnModeChangedListener`; and on API 26–30 a 1 s poll while the session is paused, a call is pending or an emergency ring plays.
- **Lifetime:** the detector runs while the wake service runs, and from every ring start. `WakeService` starts it in `onCreate` and stops it in `onDestroy`; these are its only lines in `WakeService`. `WakeRuntime` calls `onRing` (Koin: `CallDetector.follow()`, an idempotent start plus a check) on every ringing entry effect and at the emergency ring's start, so a ring whose service start was refused still follows the call.
- **Focus:** when a ring opens audibly, `AndroidAlarmPlayer` requests audio focus (`AudioFocusRequest`, `AUDIOFOCUS_GAIN`, `USAGE_ALARM`, `setWillPauseWhenDucked(false)`) and abandons it at `stop()` (the session's end or `SoundOff`).
  - Focus is held only when the request is granted. A refused request (the ring opened during a call) is requested again at the next resume.
  - A ring that opens paused for a call requests focus only when it resumes, so a silent alarm never sends the call's app `AUDIOFOCUS_LOSS`.
  - A ring that starts over (a new request) keeps the focus it holds, so other apps never get a brief `AUDIOFOCUS_GAIN`.
  - A focus change never changes the gain, pauses or ducks. It only notifies the detector.
  - A focus loss with `MODE_NORMAL` (music, video, navigation) dispatches nothing.
- **Before the first audible frame:**
  - `WakeRuntime` applies `SoundAt` and then, when `CallState.inCall()`, pauses the player in the same step, while the sound is still preparing. `Vibrating` is skipped then.
  - The detector's next check pauses the session itself. If the call ends before that, `WakeRuntime.onCallOver()` lets the held ring play: focus, full gain, the `SoundStarted` timing if not yet logged, and vibration when the state wants it. It does nothing when the player is not held, the session is paused, or an emergency ring plays.
  - `onCallOver` reads the session again under the runtime's effect lock, which every effect also holds, so a call ending on the main thread never restarts a vibration (or sound) that an effect on `ApplicationScope` just stopped.
- **Resuming:** on `CallEnded` the existing `ResumeSound` and entry effects resume at the set volume with no ramp (gain 1, whether the ring opened paused or paused mid-ramp), and vibration if on. Grace continues with its remaining seconds and the timeout moves by the call length (Epic 1 reducer, already tested).
- **Cap (never silent forever):** a call mode that keeps the ring paused for 30 minutes (`SessionReducer.NO_INTERACTION_TIMEOUT`, measured on the monotonic clock from when the detector first saw the pause) is treated as stuck, for example a VoIP app that left `MODE_IN_COMMUNICATION` set. The detector logs it, the `StuckCallGuard` ignores that call mode until the phone leaves the call modes once, and `CallEnded` resumes the ring. The runtime reads the same guard, so the ring is neither paused again nor reopened silent.
- **Emergency ring:** it has no session to pause, so it follows the call directly. It opens silent and without vibration during a call. `WakeRuntime.onEmergencyCall(inCall)` pauses it when a call starts and rings and vibrates it again when the call ends. Its own 30-minute limit still applies.
- **UI:** the approved `WakeNote.PhoneCall` ("Paused for your call. Rings again when it ends.") already shows while `session.paused`. It gets `liveRegion = Polite`, with no visual change.
- **Screenshots:** production Roborazzi `wake_ringing_phone_call_sunrise` at 100% and 200%. The preview baselines stay untouched.

**Never:**
- No `READ_PHONE_STATE`, `TelephonyManager`, `PhoneStateListener` or `TelephonyCallback`.
- No new strings or screens, and no reducer change.
- No androidTest: the ATD image has no dialer and no call.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Call during ring | Ringing; mode set to `IN_CALL`, focus loss | `CallStarted`; player paused, vibration off; note shown | No error expected |
| Call ends | Paused; mode `NORMAL`, focus gain or poll | `CallEnded`; player playing at the set volume, gain 1 | No error expected |
| VoIP / incoming ring | `IN_COMMUNICATION` or `RINGTONE` | Treated as a call | No error expected |
| Screening / redirect | `CALL_SCREENING` (API 30+), `CALL_REDIRECT` or `COMMUNICATION_REDIRECT` (API 33+) | Treated as a call | No error expected |
| Stuck call mode | Paused for 30 min, mode still a call mode | Logged; `CallEnded`; ringing at gain 1, not paused again until the mode leaves the call modes | Logged as `OperationFailed` |
| Commit fails | `CallStarted` or `CallEnded` not committed | Checked again after 1 s, on every API level | Logged by the engine |
| Emergency ring mid-call | Emergency ring, mode `IN_CALL` | Silent and still; rings and vibrates when the call ends | No error expected |
| Ring without the service | Service start refused; ring during a call | The ring's own start of the detector pauses it | No error expected |
| Other app takes focus | Ringing; mode `NORMAL`; focus loss | Still playing, gain unchanged; no event dispatched | No error expected |
| Ring starts mid-call | Mode `IN_CALL`, then `AlarmFired` | Player paused before it starts (never audible); then `CallStarted` | No error expected |
| Restore mid-call | Restored ring, pause cleared by the reducer, mode `IN_CALL` | `CallStarted` again | No error expected |
| Restore after call | Restored ring, mode `NORMAL` | Playing, not paused (the reducer cleared the pause); the detector dispatches nothing | No error expected |
| Snoozed | Snoozed, mode `IN_CALL` | Nothing dispatched | No error expected |
| API 30 poll | Paused; mode becomes `NORMAL` without a callback | `CallEnded` within about 1 s | No error expected |
| Focus request | Ring opens | `AUDIOFOCUS_GAIN`, `USAGE_ALARM`, `willPauseWhenDucked = false`; kept when the ring starts over; abandoned at the session end | No error expected |
| Focus mid-call | Ring opens paused, or the request is refused | No request while paused; requested (again) at the resume | No error expected |

</intent-contract>

## Code Map

- `core/.../session/RingRules.kt` (`CallStarted` / `CallEnded` rows, restore clears the pause) and `SessionTimers.kt` (pause shifts deadlines). No change.
- `androidApp/.../wake/AndroidAlarmPlayer.kt` -- `startRingLocked` and `release`; add focus.
- `androidApp/.../wake/WakeRuntime.kt` -- `SoundAt`, `Vibrating`, `PauseSound` / `ResumeSound`.
- `androidApp/.../wake/WakeService.kt` -- `onCreate` / `onDestroy`.
- `androidApp/.../wake/WakeModule` (Koin `wakeModule()`) -- the bindings.
- `composeApp/.../ui/wake/WakeComponents.kt` (`WakeNoteView`) and `RingingMapping.kt` (`note = PhoneCall` when paused).
- `androidApp/src/test/.../ui/RingingScreenshotTest.kt` and `RingingSamples.kt` -- the production wake screenshots.

## Tasks & Acceptance

**Execution:**
- `android/call/CallState.kt` (port and audio-mode adapter) and `android/call/CallDetector.kt`.
- `android/wake/AlarmAudioFocus.kt`; player and runtime changes; `WakeService` start and stop; Koin wiring.
- `WakeNoteView` polite live region.
- Tests: `CallDetectorTest` (Robolectric with `ShadowAudioManager`, the engine with fakes, API 34 and API 30), `WakeRuntimeTest` (mid-call ring and `onCallOver`), `AndroidAlarmPlayerTest` (focus request and abandon, focus change keeps the gain), screenshots.

**Acceptance Criteria:**
- Given `./gradlew qualityGate`, when it runs, then it passes, Kover is green, and `git status --porcelain androidApp/src/test/screenshots/preview` is empty.

## Spec Change Log

- 2026-10-06 (implementation):
  - Android lint `NewApi` cannot follow the injectable `sdkInt` guard around `addOnModeChangedListener`, so `AudioModeCallState.watch` suppresses it. The calls still run only on API 31+.
  - `WakeServiceTest` "the tick loop ends the grace window" waited only for the Loud state. The state is published before its effects run, so it now also waits for the unmute. That makes the test deterministic: the grace end now also sets the stream volume (Story 2.8), which widened the window.
- 2026-10-06 (review fixes, see the Review Triage Log):
  - Boundaries now cover the 30-minute cap (which replaces "no cap on the pause"), the extra call modes, the emergency ring during a call, deferred and re-requested focus, focus kept across a restarted ring, the effect lock, the ring-start trigger, and the recheck after a failed dispatch.
  - The scope is now `WakeScope`, not `ApplicationScope`.
- 2026-10-06 (rebase onto the final Epic 2 stack: main's 2.1, 2.2, 2.3, 2.5, 2.9, 2.12, Lane 2's 2.6, 2.8, 2.11, then 2.4):
  - `WakeRuntime` keeps 2.1's restored-ring ramp, slots and emergency backup slot, 2.4's `onInitBilling`, and 2.7's `calls`, `onRing` and effect lock. A ring during a call opens paused, also when restored. The emergency ring calls `keepEmergencySlot()` and then `onRing()`.
  - `WakeService` keeps 2.1's retry, 2.4's unlock watch and 2.6's session lock next to the call detector's start and stop.
  - `CallDetectorTest` sets a call mode through a `phoneMode()` helper: 2.11's `NoHostageApis` reports any assignment of a call mode, tests included. The adapter only reads `AudioManager.getMode()` and uses no telephony.

## Review Triage Log

### Review (2 reviewers, fast mode)

Two reviewers (code and verification) gave 15 findings. All 15 were accepted and fixed in `fix(2.7): review fixes`, and none were deferred.

- **Code (12):**
  1. `onCallOver` raced the engine's effects: it now runs under the runtime's effect lock, reads the session again there, and only runs when the player is held.
  2. A failed `CallStarted` / `CallEnded` dispatch is checked again after 1 s on every API level.
  3. A stuck call mode is capped at 30 minutes (`StuckCallGuard`) and logged.
  4. Screening (API 30) and redirect (API 33) modes count as a call.
  5. Focus is held only when granted, and requested again at resume.
  6. Resume is at full gain with no ramp, as the spec says.
  7. A ring that starts over keeps its focus; focus is abandoned only at `stop()`.
  8. `SoundStarted` is timed at the first resume of a ring that opened paused.
  9. Every ring start makes the detector follow it (`WakeRuntime.onRing`).
  10. The emergency ring pauses during a call.
  11. Checks are children of the detector's job, so a check after `stop()` does nothing.
  12. A ring opened paused defers its focus request until resume.
- **Spec corrections:** the detector runs on `WakeScope`, not `ApplicationScope`; the side effects of `onCallOver` are now listed; the "restore after a call" test is relabelled, since the reducer clears the pause. That test now asserts that the detector dispatches nothing.
- **Verification (3):**
  13. The real `AudioModeCallState` is tested through `ShadowAudioManager`'s mode dispatch: an end-to-end pause and resume, and the listener's registration and removal.
  14. Focus changes are fired through the captured `lastAudioFocusRequest` listener (the Koin wiring).
  15. A real `@Config(sdk = [30])` poll test.

## Verification

**Commands:**
- `./gradlew :androidApp:recordRoborazziDebug -Proborazzi.test.verify=false --tests` with the screenshot test -- records the 2 new baselines.
- `./gradlew qualityGate` -- expected: BUILD SUCCESSFUL.

## Design Notes

- **Reconcile, not edge-trigger.** The detector never remembers what it sent. Each check compares the audio mode with the session, so it covers the deferred 2.7 item without special cases:
  - after `ProcessRestored`, the reducer clears the pause, and a call still going on means `CallStarted` again;
  - at every new ring during a call, likewise;
  - a missed `CallEnded`, for example the call ending during a kill, is caught too.
- **Silent before the session knows.** The detector's check runs after the commit that starts the ring, so it lags by a few milliseconds. `WakeRuntime` therefore opens a mid-call ring paused (`AndroidAlarmPlayer.play(paused = true)` never starts the playback) and skips vibration. `onCallOver` releases it if the call ends before `CallStarted` lands.
- **Scope.** The detector runs on `WakeScope`, the main thread, like the player's own timers. Checks are serialized by a `Mutex` and never run inside an engine effect. The engine runs effects on `ApplicationScope`, so the runtime's `onCallOver` and `onEmergencyCall` take the same lock as `WakeRuntime.run` / `apply`.
- **`onCallOver` side effects:** `AndroidAlarmPlayer.resume()` (audio focus requested if not held, gain 1 with the ramp ended, playback started, `SoundStarted` timed once per fire) and `AlarmVibrator.start()` when the state's entry effects include `Vibrating`.
- **Review decisions (2026-10-06):**
  - Stuck call mode: capped at 30 minutes, then ignored until the mode leaves the call modes. Never silent forever.
  - Extra call modes: screening (API 30) and redirects (API 33) count as a call.
  - Emergency ring: paused during a call like a session ring, not exempt.
  - Resume gain: full gain with no ramp, as the spec says. The code now matches; a ring that opened paused does not ramp either.

## pps-design Done checklist

- [x] Only tokens from `DESIGN.md`: no visual change, the approved `WakeNoteView` and `NoteInline` are reused.
- [x] Sunrise checked: `wake_ringing_phone_call_sunrise(_font200)` match the approved `ringing-phone-call` preview state.
- [x] Contrast pairs: unchanged, already in the table.
- [x] Targets: unchanged; the wake actions stay 64 dp or more.
- [x] 200% and TalkBack: font200 screenshot taken, and the note is a polite live region (`RingingSemanticsTest`).
- [x] Reduced motion: no new motion.
- [x] Copy: the existing resource "Paused for your call. Rings again when it ends.", verbatim from EXPERIENCE.md Key strings; `CopyRulesTest` passes.
- [x] State patterns: "Phone call" (sound, grace and timer paused, with the note) handled; Snoozed is unaffected.
- [x] "I'm up" stays the most prominent action, and snooze stays visible and plain.
- [x] The preview state exists (`ringing_phone_call`), and the Roborazzi production screenshots were added.

## Auto Run Result

**Summary:**
- **Call adapter:** `CallDetector` and `AudioModeCallState` in `android/call` pause and resume the ring from the audio mode only: in call, VoIP or ringtone pauses; `MODE_NORMAL` resumes. There is no telephony and no `READ_PHONE_STATE`.
  - It reconciles on every session state change, on the alarm's audio-focus changes, on API 31+ mode changes, and with a 1 s poll on API 26–30 while paused.
  - Restores and new rings during a call are paused again, and a restored ring whose call ended is not stuck.
- **Focus:** the player requests `AUDIOFOCUS_GAIN` with `USAGE_ALARM` and `setWillPauseWhenDucked(false)`, never ducks or pauses on a focus change, and abandons focus at the end of the ring.
- **Mid-call ring:** a ring that starts during a call never starts its playback until the session pauses or the call ends.
- **UI:** the call note is now a polite live region.

**Files changed:**
- New: `android/call/CallState.kt`, `CallDetector.kt`, `android/wake/AlarmAudioFocus.kt`.
- Changed: `AndroidAlarmPlayer.kt` (focus, `play(paused)`), `WakeRuntime.kt` (`CallState`, `onCallOver`), `WakeModule.kt`, `WakeService.kt` (start and stop the detector), `composeApp/.../WakeComponents.kt` (live region).
- Tests: `CallDetectorTest` (8), `WakeRuntimeTest` (+3), `AndroidAlarmPlayerTest` (+2), `RingingSemanticsTest` (+1), `RingingScreenshotTest` (+2), plus the `WakeApp` and `RingingSamples` harness.
- 2 new baselines in `src/test/screenshots`; preview baselines untouched.

**Verification:**
- `./gradlew qualityGate`: BUILD SUCCESSFUL.
- `git status --porcelain androidApp/src/test/screenshots/preview` is empty.
- No androidTest: the ATD image has no telephony. Real calls are human-verify in Story 2.13.

**Review:** the step-04 review (2 reviewers, fast mode) ran afterwards. Its 15 findings were fixed in `fix(2.7): review fixes`; see the Review Triage Log.
