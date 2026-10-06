---
title: 'Story 2.7: Pause the alarm for phone calls'
type: 'feature'
created: '2026-10-06'
status: 'in-progress'
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
- **In a call** means the audio mode is `MODE_IN_CALL`, `MODE_IN_COMMUNICATION` or `MODE_RINGTONE`. `MODE_NORMAL` is no call. Read through a `CallState` port (`AudioModeCallState`). No other signal is used.
- **Reconciling:** each `CallDetector.check()` runs on `ApplicationScope` under its own `Mutex`, so it is serialized and never inside an engine effect. It reads `SessionEngine.state`:
  - Ringing, Grace or Loud, in a call and not paused: dispatch `CallStarted`;
  - paused and no longer in a call: dispatch `CallEnded`;
  - anything else, including `Snoozed`, Idle and Completed: nothing.
  - So a restore (the reducer clears the pause) or a new ring during a call is paused again, and a restored ring whose call ended is never stuck paused.
- **Triggers:** every emission of `SessionEngine.state`; `AUDIOFOCUS_LOSS*` and `AUDIOFOCUS_GAIN` from the player's focus listener; the API 31+ `addOnModeChangedListener`; and on API 26–30 a 1 s poll while the session is paused or a call is pending.
- **Lifetime:** the detector runs while the wake service runs. `WakeService` starts it in `onCreate` and stops it in `onDestroy`; these are its only lines in `WakeService`.
- **Focus:** when a ring opens, `AndroidAlarmPlayer` requests audio focus (`AudioFocusRequest`, `AUDIOFOCUS_GAIN`, `USAGE_ALARM`, `setWillPauseWhenDucked(false)`) and abandons it when the ring is released.
  - A focus change never changes the gain, pauses or ducks. It only notifies the detector.
  - A focus loss with `MODE_NORMAL` (music, video, navigation) dispatches nothing.
- **Before the first audible frame:**
  - `WakeRuntime` applies `SoundAt` and then, when `CallState.inCall()`, pauses the player in the same step, while the sound is still preparing. `Vibrating` is skipped then.
  - The detector's next check pauses the session itself. If the call ends before that, `WakeRuntime.onCallOver()` resumes the player when the session is not paused.
- **Resuming:** on `CallEnded` the existing `ResumeSound` and entry effects resume at the set volume with no ramp, and vibration if on. Grace continues with its remaining seconds and the timeout moves by the call length (Epic 1 reducer, already tested). There is no cap on the pause.
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
| Other app takes focus | Ringing; mode `NORMAL`; focus loss | Still playing, gain unchanged; no event dispatched | No error expected |
| Ring starts mid-call | Mode `IN_CALL`, then `AlarmFired` | Player paused before it starts (never audible); then `CallStarted` | No error expected |
| Restore mid-call | Restored ring, pause cleared by the reducer, mode `IN_CALL` | `CallStarted` again | No error expected |
| Restore after call | Restored ring, mode `NORMAL` | Playing, not paused | No error expected |
| Snoozed | Snoozed, mode `IN_CALL` | Nothing dispatched | No error expected |
| API 30 poll | Paused; mode becomes `NORMAL` without a callback | `CallEnded` within about 1 s | No error expected |
| Focus request | Ring opens | `AUDIOFOCUS_GAIN`, `USAGE_ALARM`, `willPauseWhenDucked = false`; abandoned at the session end | No error expected |

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

## Review Triage Log

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
- **Scope.** The detector runs on `WakeScope`, the main thread, like the player's own timers. Checks are serialized by a `Mutex` and never run inside an engine effect.

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

**Not run:** the step-04 review (fast mode).
