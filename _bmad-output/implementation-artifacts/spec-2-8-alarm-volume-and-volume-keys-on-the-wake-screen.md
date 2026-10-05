---
title: 'Story 2.8: Alarm volume and volume keys on the wake screen'
type: 'feature'
created: '2026-10-05'
status: 'in-progress'
baseline_revision: '2e35f53d85df05b5029002bf60191dbd49ccaff6'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/spec-1-14-ring-the-alarm-wakeservice-alarmplayer-and-the-ongoing-notification.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-1-17-built-in-sound-library-with-preview-and-a-never-silent-fallback.md'
  - '{project-root}/docs/spikes/S1.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** On the wake screen the volume keys still turn the alarm down. The alarm stream is set only when a sound first opens, so the end of the grace window does not bring the set volume back. Nothing prevents a later `MediaSession`, `VolumeProvider` or audio rerouting from taking over the keys or the speaker (FR-SES-6, NFR-13).

**Approach:**
- `WakeActivity` consumes the volume keys only while it is resumed, through a pure `VolumeKeyGate`, and lets the accessibility shortcut (both keys held) through.
- The grace end re-applies the alarm stream to the session volume, as the ring start already does. Nothing re-applies it continuously.
- A detekt rule bans the key-capture and audio-routing APIs.
- For the Spike S1 gap, a one-shot `WakeRuntime.reassertRingVolume()` hook is added for Epic 4.

## Boundaries & Constraints

**Always:**
- **Setting the volume:** the alarm stream is set to the session's `volumePercent` at every ring start and once at the grace end. Ring starts are: a new player request, which covers the first ring, a re-ring after a snooze or a merge into a snooze, and a ring restored in a new process. The grace end is `SessionEffect.UnmuteToVolume`: the stream is set again and the gain goes to full, with the ramp over.
- **Never continuously:**
  - Re-applying the same entry effects (heartbeat, interactions) never touches the stream.
  - The user's own alarm volume is saved once per session and restored when it ends (Story 1.14 `AlarmVolume`, unchanged).
- **Key gate:** `VolumeKeyGate` is pure and takes a `KeyEvent` action and key code.
  - VOLUME_UP, VOLUME_DOWN and VOLUME_MUTE are consumed while the screen is resumed. Every other key, and every key after `onPause`, is not consumed.
  - Once both UP and DOWN are down (the accessibility shortcut), no volume key is consumed until both are released. This includes the second key's down and both ups.
  - A consumed key sends no session event.
- **Wake screen:** `WakeActivity.dispatchKeyEvent` asks the gate first, and sets the gate's `resumed` in `onResume` and `onPause`.
- **Grace vibration:** vibration continues in Grace only with `vibrateInGrace`, and resumes in Loud with `vibration`. This is the existing entry-effect rule; tests cover both values.
- **Detekt rule `NoAudioCaptureOrRouting`** (:androidApp and :composeApp sources) reports:
  - the types `MediaSession`, `MediaSessionCompat`, `VolumeProvider` and `VolumeProviderCompat`;
  - the calls `setPreferredDevice`, `setSpeakerphoneOn`, `setCommunicationDevice` and `registerMediaButtonEventReceiver`;
  - the property `isSpeakerphoneOn`.
  - It has violating and compliant snippet tests, and `DetektConfigTest` checks it is active.
- **Manifest test:** the app declares no `MEDIA_BUTTON` receiver and no media browser service.
- **Spike S1 decision:** accept the gap while the Play sheet is on top. Play's activity has the keys, and FR-SES-6 forbids re-applying continuously. Re-assert once when the purchase flow hands back to the wake screen.
  - `WakeRuntime.reassertRingVolume()` sets the stream to the current ring's volume, only while Ringing or Loud and not muted, paused or in an emergency ring. Otherwise it does nothing.
  - Nothing calls it in Epic 2. Epic 4's orchestration (4.11) calls it on every payment outcome that returns to ringing. Recorded in `deferred-work.md`.

**Never:**
- No UI change, no new string, no `MediaSession` or volume provider.
- No continuous volume enforcement, no audio-focus or routing change, no global key capture.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Ring start | Ringing at 80% | Alarm stream = 80% of max | No error expected |
| User turns the stream down in Loud | Stream set to 1, then entry effects re-run | Stays 1 | No error expected |
| Grace end | `UnmuteToVolume(80)` after the stream was set to 1 | Stream back to 80%, gain 1 | No error expected |
| Session ends | Saved user volume 2 | Stream 2 | No error expected |
| Volume key while resumed | DOWN down and up | Both consumed; no stream change | No error expected |
| Accessibility shortcut | DOWN down, UP down, UP up, DOWN up | First DOWN consumed; the other three not consumed | No error expected |
| After `onPause` | DOWN | Not consumed | No error expected |
| Re-assert hook | Loud at 80%, stream 1 | Stream 80% | Grace, Snoozed, Idle, paused: nothing |
| Banned API | `MediaSession(...)`, `setSpeakerphoneOn(true)` | detekt finding | No error expected |

</intent-contract>

## Code Map

- `androidApp/.../wake/AndroidAlarmPlayer.kt` -- `startRingLocked` sets the stream; `unmute()` (grace end) does not yet.
- `androidApp/.../wake/AlarmVolume.kt` -- `setForRing` saves the user volume once; `restore`.
- `androidApp/.../wake/WakeRuntime.kt` -- `runSound` maps `UnmuteToVolume`.
- `androidApp/.../wake/WakeActivity.kt` -- has the Back callback; no key handling yet.
- `config/detekt-rules/...` (`NoInexactAlarm` pattern, `YawnAndPawnRuleSetProvider`, `DetektConfigTest`) and `config/detekt/detekt.yml`.
- `androidApp/src/test/.../wake/WakeRuntimeTest.kt` -- the runtime harness with the real `AlarmVolume` and `ShadowAudioManager`.

## Tasks & Acceptance

**Execution:**
- `AndroidAlarmPlayer.unmuteTo(volumePercent)` and `reassertVolume()`; `WakeRuntime` maps `UnmuteToVolume` and adds `reassertRingVolume()`.
- `android/wake/VolumeKeyGate.kt` and the `WakeActivity` wiring.
- `NoAudioCaptureOrRouting` rule, provider entry, `detekt.yml` entry and tests.
- Tests: `VolumeKeyGateTest` (pure rows), `WakeActivity` key tests (synthesized `KeyEvent`s, resumed and paused), `WakeRuntimeTest` volume rows and the grace vibration pair, and the manifest test.
- `deferred-work.md` -- the S1 decision and the Epic 4 hook.

**Acceptance Criteria:**
- Given `./gradlew qualityGate`, when it runs, then it passes, Kover is green, and `git status --porcelain androidApp/src/test/screenshots/preview` is empty.

## Spec Change Log

- 2026-10-06 (implementation): Android lint (`RestrictedApi`) rejects overriding `ComponentActivity.dispatchKeyEvent`, so `WakeActivity` asks the gate in `onKeyDown` and `onKeyUp` instead. The window's own volume handling runs only for keys the activity did not consume, so the effect is the same. Also, `VolumeKeyGate` now forgets held keys on every resume change, so a key pressed while another screen was in front never counts towards the shortcut (found by `WakeVolumeKeysTest`). KEEP: no `dispatchKeyEvent` override.

## Review Triage Log

## Verification

**Commands:**
- `./gradlew :detekt-rules:test :androidApp:testDebugUnitTest --tests` with the new and changed classes -- expected: pass.
- `./gradlew qualityGate` -- expected: BUILD SUCCESSFUL.

## Design Notes

- **Spike S1 decision: accept the gap and re-assert once.** While Google Play's purchase sheet is on top, its activity owns the volume keys, and the wake screen cannot consume them. Re-applying the volume during the sheet would be continuous enforcement, which FR-SES-6 forbids. So the gap is accepted, and `WakeRuntime.reassertRingVolume()` sets the stream back once when the flow hands the screen back to the ring.
- **Hook for Story 4.11:** Epic 4's purchase orchestration must call `reassertRingVolume()` on every outcome that returns to ringing: cancelled, error, offline, unlock cancelled and pending. This is recorded in `deferred-work.md`, and `WakeRuntimeTest` pins the behaviour.

## Auto Run Result

**Summary:**
- **Volume keys:** `WakeActivity` consumes volume up, down and mute only while it is resumed. The pure `VolumeKeyGate` lets the accessibility shortcut through from the second key down until both keys are released. Any lifecycle change forgets the held keys. A consumed key sends no session event.
- **Grace end:** the end of the grace window (`UnmuteToVolume`) now sets the alarm stream back to the session volume (`AndroidAlarmPlayer.unmuteTo`) and the gain to full. The ring start already set it. Nothing re-applies it in between, and the user's own volume still comes back at the session end.
- **Detekt rule:** `NoAudioCaptureOrRouting` bans `MediaSession`, `VolumeProvider` (and their compat versions), `setPreferredDevice`, `setSpeakerphoneOn` / `isSpeakerphoneOn`, `setCommunicationDevice` and `registerMediaButtonEventReceiver` in `:androidApp` and `:composeApp`.
- **Manifest test:** the app declares no media button receiver and no media browser service.
- **Spike S1 hook:** `WakeRuntime.reassertRingVolume()`, unused until Epic 4.

**Files changed:**
- `android/wake/AndroidAlarmPlayer.kt` (`unmute()` replaced by `unmuteTo(volumePercent)`, plus `reassertVolume()`), `WakeRuntime.kt`, `WakeActivity.kt`, and the new `VolumeKeyGate.kt`.
- `config/detekt-rules`: the new rule and its test, the provider, and the provider-list test. `config/detekt/detekt.yml`.
- Tests: `VolumeKeyGateTest`, `WakeVolumeKeysTest`, `WakeRuntimeTest` (volume rows, vibrate-in-grace pair, hook), `AndroidAlarmPlayerTest`.
- `deferred-work.md` (S1 decision).

**Verification:**
- `./gradlew qualityGate`: BUILD SUCCESSFUL.
- `git status --porcelain androidApp/src/test/screenshots/preview` is empty.
- No UI or string change.

**Not run:** the step-04 review (fast mode). The real keys, the accessibility shortcut and headphone routing are human-verify in Story 2.13.
