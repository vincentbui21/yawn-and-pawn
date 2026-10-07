---
title: 'Story 3.11: QR/Barcode when the camera fails, and before first unlock'
type: 'feature'
created: '2026-10-07'
status: 'done'
baseline_revision: '47a24a3'
review_loop_iteration: 1
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-3-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-3-9-fallback-check-picker.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-3-10-qr-barcode-register-and-scan.md'
warnings:
  - 'Written before 3.5-3.10 and 3.13 are on main. File and symbol names come from the reviewed commits (3.9 4899c12 + 056ce0b, 3.10 a74616c plus its review-fix list). Check the names against main when you start; see Base.'
deferred: []
---

<intent-contract>

## Intent

**Problem:**
- Story 3.10 shows "Camera isn't available. Pick a fallback check." only when the permission is missing or CameraX cannot bind.
- A camera that binds and then sends no frames, fails mid-scan or is taken by another app leaves the user with a black viewfinder and no way out except 5 wrong scans, which they cannot make.
- The 3.9 wake screen always asks for the fallback with reason `FailedAttempts`. The policy therefore denies the fallback while the camera is down and fewer than 5 attempts have failed.
- The camera stays bound while `WakeActivity` is paused.
- Before the first unlock, the QR entry quietly becomes Math, but the "Your phone restarted, so today's check is Math." note is never shown (deferred from Story 2.3 to Epic 3).

**Approach:**
- A pure `CameraMonitor` in `:composeApp` `commonMain` (`ui/qr`) holds the camera's status for the wake QR check: `Starting`, `Live` or `Unavailable(problem)`. Inputs:
  - frame heartbeats from the scanner;
  - error events from the scanner;
  - a 5 s no-frame watchdog on the `MonotonicClock`.
- `WakeCheck` keeps the scanner bound while the camera is unavailable for a reason that can recover, so the scan resumes by itself.
- The fallback reason sent to the policy is `CameraUnavailable` once the camera has failed on this entry in this ring. That flag is latched, so the link stays after the camera recovers. Otherwise the reason is `FailedAttempts`.
- The scanner is composed only while `WakeActivity` is resumed. Pausing releases the camera. Resuming binds it again and restarts the watchdog.
- A pure core predicate decides whether the current ring had a camera entry swapped for the Direct Boot check. If so, the Ringing and Check screens show `WakeNote.DirectBoot`.

## Boundaries & Constraints

**Always:**
- The alarm can always be stopped. A camera that is unavailable for any reason brings up the message and the fallback link at once (with the watchdog, within 5.0 s of the bind on the monotonic clock). A link once shown in a ring stays until the fallback is used or the entry is passed.
- The UI never decides correctness and the AD-2 table gets no new rows. `FallbackRequested(type, reason)` already exists (3.9).
- Every time is measured on `MonotonicClock` (AD-3), never the wall clock. Tests use `FakeMonotonicClock`.
- No image or raw code value is logged or stored. Camera problems are logged once per change as `OperationFailed("camera", <problem name>)`.
- The approved composables and preview baselines stay as they are. The unavailable state is the approved `check-qr-camera-unavailable`. The only visual change is the Direct Boot note, which is the approved `ringing-locked` note added to the Check screen.
- The `SessionJson` shape and its golden fixtures do not change. The Direct Boot note is derived, not stored.

**Never:**
- No androidTest that needs a camera, a system app or `pm revoke`. Real camera failures are items 9 and 12 of Story 3.14.
- No `adb`.
- No new user-facing strings. The 3.10 and 3.11 key strings are already resources.
- No change to `CameraFallbackPolicy`'s rules. Only the reason the screen sends changes.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected behaviour | Notes |
|----------|---------------|--------------------|-------|
| Watchdog, just under | bound at t0, no frame, monotonic t0 + 4 900 ms | `Starting`, the viewfinder shows, no message, no link | AC: 4.9 s |
| Watchdog fires | no frame, t0 + 5 000 ms | `Unavailable(NoFrames)`: the message, and the link with reason `CameraUnavailable` | AC: 5.0 s. Wall clock moved by an hour in the same test: no effect |
| Frames flowing | a heartbeat at least every 500 ms | `Live`; the watchdog never fires | |
| Stall mid-scan | `Live`, then no heartbeat for 5 000 ms | `Unavailable(NoFrames)`: the message and the link | The same watchdog: "5 s since the last frame" |
| CameraX error mid-scan | `Live`, then `CameraUnavailable(CameraError)` (cameraState error, 3.10 fix 1) | the message and the link at once | the scanner stays bound, and CameraX retries |
| Disconnect | another app takes the camera (`ERROR_CAMERA_IN_USE`), or frames stop | the message and the link at once (error), or within 5 s (stall) | |
| Recovery | `Unavailable(CameraError/NoFrames/Disconnected)`, then heartbeats for at least 1 s | `Live`: the viewfinder and scanning come back, and the link **stays** (latched) | 1 s debounce, so a flaky camera does not flicker (default taken) |
| Sticky problems | `NoPermission`, `PrivacyBlocked` (3.10 fix 2), `BindFailed` | the message and the link at once; no recovery until the next resume | black frames with the privacy toggle on decode "successfully", so heartbeats must not clear it |
| Decoder failing | ML Kit failure streak (3.10 fix 3) | `Unavailable(DecoderFailing)`; a failed frame is not a heartbeat | so the watchdog also fires, and the status cannot flap back |
| Permission revoked mid-ring | Android kills the process | the session is restored (Epic 2). The wake screen shows QR with `NoPermission`, so the message and the link show at once, on the same step | no `pm revoke` in tests: `FakeCodeScanner(permitted = false)` |
| Pause | `ON_PAUSE` (screen off, Home, the notification shade where it pauses) | the scanner leaves composition: CameraX unbinds and the torch goes off | `FakeCodeScanner.stops == 1` |
| Resume | `ON_RESUME` | the scanner binds again (`starts == 2`), the watchdog restarts from 0, the permission is read again | an earlier latch stays for the ring |
| Grace redraws | grace ticks every 250 ms, then expires to Loud | the scanner never rebinds (`starts == 1`) | 3.10 fix 14 (stable feed) |
| Restored after a kill | stored Grace/Loud session on a QR entry with 2 failed attempts | the Check screen on the same step, the scanner bound once, failed attempts still 2 | |
| 5 wrong codes | working camera, five different codes (2 s gate) | the link appears after the 5th, reason `FailedAttempts` | also the lost-code path (FR-PWK-11) |
| Camera down and fewer than 5 failures | `Unavailable`, 1 failed attempt, Math tapped | `FallbackRequested(Math, CameraUnavailable)`, allowed: Math · Hard · 6 | 3.9 sent `FailedAttempts` here, which was denied |
| Fallback used | after the fallback | no QR screen and no scanner; the link never comes back this session | 3.9 |
| Before first unlock | a ring while locked, plan [QR] or All [QR, Word] | QR becomes Math · Medium · 3 (existing substitution). Ringing and Check show "Your phone restarted, so today's check is Math." | |
| Random, safe pick | locked, Random [QR, Word], the ring's pick seed picks Word | Word, **no** note | the note means "your check changed" |
| Unlock during the ring | `directBootRing` true, user unlocks | Math and the note stay for this ring | existing `directBootRing` |
| Next ring | an unlocked ring (re-ring after a snooze, or the next alarm) | QR/Barcode again, no note | `FakeUserLockState` |
| Call during a locked ring | `paused` and swapped | the phone-call note shows (one note slot) | the DirectBoot note comes back after the call (default taken) |
| QR without a code | a damaged row, locked | `PlanResolver` already made it Math, so no swap and no note | |

</intent-contract>

## Base

- Build on `main` once 3.5–3.8, 3.9 + 3.13 and 3.10 (with its review fixes) are merged. Do not stack on unreviewed 3.10 code: this story edits the same files (`CodeScanner.kt`, `CameraXCodeScanner.kt`, `CodeAnalyzer.kt`, `WakeCheck.kt`, `WakeActivity.kt`, `QrCheckMapping.kt`, `FakeCodeScanner.kt`). This follows the Epic 2 retro, action 3.
- Expected from 3.10's fixes, which this story assumes:
  - camera errors arrive through `cameraState` as `CameraUnavailable`;
  - the privacy toggle and an ML Kit failure streak report `CameraUnavailable`;
  - the scanner set-up runs inside `try`;
  - per-code streaks;
  - no `MlKitInitProvider`;
  - a stable feed lambda;
  - `cameraAvailable == false` sent to 3.9's policy as `CameraUnavailable` (the 3.9 hook).
- If any of these is missing on main, do it here first and say so in the review notes.
- Names to confirm on main:
  - `WakeCheck.FALLBACK_REASON` (3.9) becomes a function of the camera status;
  - `qrCheckUiState(..., cameraAvailable, torchOn)`;
  - `ScanEvent.{Detected, CameraUnavailable}`;
  - `FakeCodeScanner.{frames, fail, running}`;
  - `CheckType.fallbackChoices`;
  - `CameraFallbackPolicy.FAILED_ATTEMPTS`.

## Implementation notes (3.10's final code changes the plan)

Built on `origin/story/3-10-qr-barcode` (47a24a3, PR #37 in CI), not on `main`: the orchestrator's unattended Epic 3 run stacks it, and it is rebased onto `main` once #37 is merged. Every 3.10 fix the Base lists is there; these points change or pin the plan (defaults taken, the owner can change any of them):
- **Names on 3.10's branch.** The fallback reason is already a function (`WakeQr.fallbackReason(state)`, in `WakeQr.kt`, the QR part of `WakeCheck`), not a `FALLBACK_REASON` constant. The camera monitor lives in `WakeQr`. `FakeCodeScanner.fail()` had no problem argument; it now takes one.
- **What ends a scan.** In 3.10 every failure ends the scan (the camera is released and `CameraUnavailable` is sent once). Now only the sticky problems end it: `NoPermission`, `BindFailed` (any setup step throws) and `PrivacyBlocked` (`ERROR_CAMERA_DISABLED`). The camera in use (`ERROR_CAMERA_IN_USE`, `ERROR_MAX_CAMERAS_IN_USE`) is `Disconnected`, and every other ending error of 3.10's `cameraErrorEnds` (fatal, stream config, Do Not Disturb) is `CameraError`. Both are reported at once while the camera stays bound, so CameraX can reopen it. A 10-frame decode-failure streak is `DecoderFailing`, reported while the analysis goes on. `ERROR_OTHER_RECOVERABLE_ERROR` is still left to CameraX, as in 3.10, and if it stops the frames, the watchdog fires.
- **Scan / Preview split, kept.** It is still needed: the approved unavailable state has no viewfinder, so a feed drawn inside the viewfinder would unbind the camera exactly when recovery must be watched. The `Preview` use case is bound with `Scan`. `Preview()` sets its surface provider while composed and clears it on leaving (`setSurfaceProvider(null)` makes the use case inactive). This replaces binding and unbinding the use case and gives the same `ImageAnalysis`-only session. `Feed` (registration, "Try it") runs its own `Scan` + `Preview` pair, so a wake scan never takes over the registration's preview.
- **Scan in composition.** `WakeActivity` composes the scan while it is resumed, the screen is a QR check and the permission is granted, and it keeps it composed through every problem. The CameraX scanner releases the camera itself on a sticky one. The scan also follows the lifecycle: each `ON_RESUME` restarts the watchdog (`bound`), and the watchdog ticks only while resumed. Recomposition can wait until `ON_START` after a quick pause and stop (screen off), so the camera then stays composed but is closed by CameraX. A stopped screen must not count as "no frames".
- **The camera privacy toggle on phones that mute the camera** (black frames, no error; no public API). Black frames decode with no code, so they are heartbeats and the watchdog cannot see them. A dark-frame heuristic is not used, because it would hide the viewfinder. Instead there is a "nothing read" rule: once the camera has been bound for **60 s** with no code read at all (none detected, right or wrong), the fallback link shows (reason `CameraUnavailable`, latched like the camera latch). The viewfinder, torch and scanning stay, and the check is never passed: the fallback is still a Hard check. A covered lens therefore gets the link after 60 s, which is slower than the existing path (any barcode held up 5 times). The same rule also serves a damaged code that ML Kit cannot read. Device check: 3.14 item 9.
- **Logging.** `WakeQr` logs each change into an unavailable status once, as `OperationFailed("camera", <problem>)`. The scanner keeps 3.10's step logs (which step failed), with no code or image.
- **Torch after screen off/on** (deferred from 3.10). The switch keeps its state. The torch goes off with the camera on pause, and the rebound camera lights it again on resume, so the switch never shows a state the torch is not in.
- **Heartbeats in tests.** `FakeCodeScanner.frames()` sends no heartbeat; `heartbeat()` does. A `Detected` event also counts as a frame for the monitor, since the camera had to deliver it.
- **Direct Boot screen test.** The Robolectric note test is a new `DirectBootNoteTest` (`WakeApp`, `FakeUserLockState`, Compose rule). `DirectBootRingTest` runs under the locked-storage shadow and has no Compose rule. The next ring after the unlock is checked in Robolectric as the next alarm, and as the ring after a snooze in core (`DirectBootTest`).

## Code Map

- `composeApp/.../ui/qr/CodeScanner.kt`:
  - `ScanEvent` gains `Frame` (a heartbeat) and `CameraUnavailable(problem: CameraProblem)`. `CameraProblem` is `NoPermission`, `BindFailed`, `CameraError`, `Disconnected`, `PrivacyBlocked` or `DecoderFailing`, with `recoverable` on each.
  - The port splits `Feed` into a non-visual `Scan(torchOn, onEvent)` (ImageAnalysis plus torch, bound while composed) and a visual `Preview()`, which attaches a surface inside the viewfinder through `LocalViewfinderFeed`. `Feed` stays as `Scan` + `Preview` for registration and "Try it".
- `composeApp/.../ui/qr/CameraMonitor.kt` (new, pure): `CameraStatus`, and `CameraMonitor(timeoutMillis = 5_000, recoverMillis = 1_000)` with `bound(now)`, `frame(now)`, `problem(p, now)`, `tick(now)`, `status` and `everUnavailable`. No Compose, no clock of its own.
- `androidApp/.../qr/CameraXCodeScanner.kt`:
  - `Scan` binds `ImageAnalysis` alone. `Preview` binds or unbinds the `Preview` use case on the same lifecycle owner.
  - `cameraState` errors map to `CameraError` or `Disconnected` (`ERROR_CAMERA_IN_USE`, `ERROR_MAX_CAMERAS_IN_USE`), `ERROR_CAMERA_DISABLED` to `PrivacyBlocked`/`BindFailed`, and a bind exception to `BindFailed`.
- `androidApp/.../qr/CodeAnalyzer.kt`: posts `ScanEvent.Frame` on the main executor at most once per 500 ms of analyser time, only for frames the decoder processed. A decoder failure is never a heartbeat.
- `androidApp/.../wake/WakeCheck.kt`:
  - owns one `CameraMonitor` per (session, ring, entry);
  - runs the watchdog `tick` every 250 ms while `Scan` is composed;
  - `cameraAvailable` comes from the status;
  - `fallbackReason(state)` is `CameraUnavailable` when `everUnavailable` for the current entry, else `FailedAttempts`. It is used by `fallbackOffered` and `onFallback`;
  - `scan()` returns the stable non-visual scanner slot.
- `androidApp/.../wake/WakeActivity.kt`: composes `check.scan(...)` next to `WakeContent` only while the lifecycle is at least `RESUMED` (`currentStateAsState`) and the screen is a QR check with permission. It keeps it composed through `Unavailable(recoverable)`, so CameraX can recover.
- `core/.../session/DirectBoot.kt`: `DirectBootSubstitution.swappedThisRing(session): Boolean`. It is `directBootRing && !checkRun.fallbackUsed` and the ring's unsubstituted resolution (`PlanResolver.resolve(nextRingPlan(config.checkPlan), pickSeed(sessionId, ringIndex))`) has an entry that is not `directBootSafe`. Pure, never stored.
- `composeApp/.../ui/wake/CheckMapping.kt`: a shared `wakeNote(session)` returns `PhoneCall` while paused, else `DirectBoot` when `swappedThisRing`, else null. It is used by `mathCheckUiState`, `qrCheckUiState`, the Word and Memory mappers (3.7, 3.8) and `RingingMapping.ringingUiState`.
- `composeApp/.../ui/components/ViewfinderColors.kt` (`QrGuide`): the torch becomes `Modifier.toggleable(value = torchOn, role = Role.Switch)` with the "Torch" label, so TalkBack reads "Torch, on/off". There is no new string or icon, and pixels are unchanged.
- Tests: `:testing` gets nothing new (`FakeMonotonicClock` and `FakeUserLockState` exist). The `:androidApp` test `FakeCodeScanner` gains `heartbeat()`, `fail(problem)`, `starts` and `stops`.

## Tasks & Acceptance

**Execution:**
- [x] `CameraMonitor` with `CameraMonitorTest` (commonTest, `FakeMonotonicClock`):
  - 4 900 ms is `Starting`, 5 000 ms is `Unavailable(NoFrames)`;
  - a stall after `Live` at the last frame + 5 000;
  - an error mid-scan;
  - recovery after 1 s of heartbeats;
  - a sticky problem ignores heartbeats;
  - `everUnavailable` stays true after recovery;
  - a wall-clock jump has no effect.
- [x] Port split (`Scan` / `Preview`), the heartbeat in `CodeAnalyzer` (throttle test: 20 frames in 400 ms give 1 heartbeat; a failed decode gives none), and the `cameraState` mapping in `CameraXCodeScanner` (Robolectric, as 3.10 fix 8).
- [x] `WakeCheck`/`WakeActivity` wiring: the status-driven `cameraAvailable`, the latched reason, scanning only while resumed, the scanner kept through recoverable problems, and the torch as a switch.
- [x] `QrCameraFailureTest` (Robolectric, `FakeCodeScanner` + `FakeMonotonicClock`, the real Koin graph through `WakeApp`):
  - 4.9 s gives no message;
  - 5.0 s gives the message and the link;
  - an error mid-scan shows both at once;
  - a disconnect (error, then frames resume) brings the viewfinder back while the link stays;
  - tapping Math with 1 failed attempt sends `FallbackRequested(Math, CameraUnavailable)` and the session runs Math · Hard · 6.
- [x] `QrLifecycleTest` (Robolectric):
  - pause: `stops == 1`, the torch is released;
  - resume: `starts == 2` and the watchdog restarts (4.9 s after the resume, no message);
  - grace → Loud: `starts == 1`;
  - restore after a kill on the same step with the same failed attempts gives `starts == 1`.
- [x] `QrFailedAttemptsTest`: 4 different codes give no link; the 5th gives the link with reason `FailedAttempts`.
- [x] Direct Boot:
  - `swappedThisRing` and `wakeNote` with core and composeApp tests: All [QR], Random picking QR, Random picking Word, after unlock in the ring, the next unlocked ring, fallback used, paused and swapped;
  - an extension of `DirectBootRingTest` (Robolectric, `FakeUserLockState`): the locked ring shows the note on Ringing and on the Math Check screen; it stays after the unlock; the next ring is QR with no note.
- [x] Semantics (TalkBack AC):
  - the viewfinder reads "Camera viewfinder. Point at your code.";
  - the torch has role Switch and toggleable state Off, then On after a tap;
  - "That's a different code. Scan your registered one." and the unavailable message are polite live regions;
  - the fallback link exists with a click action and is focusable in the first frame after `CameraUnavailable`, with no clock advance.
- [x] Roborazzi (Sunrise, 100% and 200%):
  - `wake_check_qr_watchdog` (message and link);
  - `wake_check_qr_link_after_failures` (viewfinder, wrong-code line and link);
  - `wake_ringing_direct_boot` (the note and the lock snooze);
  - `wake_check_math_direct_boot` (the note above the problem).
- [x] Deferred-work: mark the Story 2.3 item "The Direct Boot note … is Epic 3" as resolved here.

**Acceptance Criteria:**
- Given the QR check has bound the camera, when no frame arrives within 5 s (monotonic clock), CameraX reports an error, or the camera is disconnected, then the check shows "Camera isn't available. Pick a fallback check." and the fallback link immediately (`FallbackRequested` reason `CameraUnavailable`). If the camera later recovers, the scan resumes and the link stays. Tests with `FakeCodeScanner` and `FakeMonotonicClock` cover 4.9 s (no message), 5.0 s (message), an error callback mid-scan and a disconnect.
- Given the user scans wrong codes, when the fifth failed attempt is recorded on the entry, then the fallback link appears (reason `FailedAttempts`).
- Given `WakeActivity` is paused, when it resumes, then the camera was released while paused and binds again on resume with the 5 s watchdog restarted. A session restored after a kill binds the camera again on the same step.
- Given a ring before the first unlock whose plan contains QR/Barcode, when `DirectBootSubstitution` applies, then the entry becomes Math · Medium · 3, and the Ringing and Check screens show the Sunrise `note-inline` "Your phone restarted, so today's check is Math.". The note and Math stay for that ring after the unlock, and the next ring after the unlock uses QR/Barcode again (`FakeUserLockState`).
- Given TalkBack is on, when the QR check is shown, then the viewfinder reads "Camera viewfinder. Point at your code.", the torch reads its state, results are announced politely, and the fallback link is focusable as soon as it appears.
- Roborazzi screenshots cover the watchdog message, the link after 5 failures and the Direct Boot note on the Ringing and Check screens, in Sunrise at 100% and 200%.
- The `pps-design` Done checklist below is ticked. All strings are resources, key strings match EXPERIENCE.md verbatim, and `CopyRulesTest` passes (FR-MSG-4).
- `./gradlew qualityGate :androidApp:assembleDebugAndroidTest --no-daemon` passes, and `git status --porcelain androidApp/src/test/screenshots/preview` is empty.

## pps-design Done checklist

- [x] Only tokens from `DESIGN.md` used (no raw hex, no new radii, no new font sizes).
- [x] Sunrise screenshots of the new states at 100% and 200% (wake screens only; no app screen changes).
- [x] Every colour pair is in the contrast table: `note-inline` on Ringing and Check is the approved `ringing-locked` pair, and the unavailable card is `text / glass+sunrise-gradient-top`. No new pair.
- [x] Touch targets: the torch is 48 dp, the fallback link is at least 48 dp, and snooze stays 64 dp.
- [x] 200% font and TalkBack: the viewfinder label, the torch switch state, polite live regions, and the link focusable at once.
- [x] Reduced motion: no new animation.
- [x] Copy verbatim from EXPERIENCE.md Key strings ("Camera isn't available. Pick a fallback check.", "Can't do this check?", "Your phone restarted, so today's check is Math.", "Camera viewfinder. Point at your code.", "Torch"). No new strings.
- [x] State rows: "Camera denied, unavailable or failed to start" and "Before first unlock (Direct Boot)" (EXPERIENCE.md State Patterns).
- [x] "I'm up" and snooze unchanged; snooze stays visible with the message and the link.
- [x] Previews unchanged (`check-qr-camera-unavailable`, `ringing-locked`); new Roborazzi baselines added.

## Design Notes

**Defaults taken in fast mode (the owner can change any of them):**
- **One watchdog for start and stall.** "No frame within 5 s" is measured from the bind and from the last frame, so a feed that freezes mid-scan without an error also brings up the link. The epic's wording covers only the start.
- **The link is latched for the entry.** Once the camera has been unavailable on the current entry in this ring, the link stays and the picker sends `CameraUnavailable`, even after the camera recovers (AC: "the link stays available"). The latch is UI state only. After a process kill it is gone, but the camera rebinds and, if it is still broken, the link is back within 5 s.
- **Recovery needs 1 s of frames.** This stops a flaky camera flickering between the viewfinder and the message. Sticky problems (`NoPermission`, `PrivacyBlocked`, `BindFailed`) never recover by heartbeat, only on the next resume.
- **Scanning without a preview.** While the message shows, the approved state has no viewfinder. The scanner keeps an `ImageAnalysis`-only binding, which CameraX supports, so a recovering camera is noticed. The `Preview` use case is bound only while the viewfinder is drawn. Risk: one extra session reconfiguration on each switch. This is checked on device in 3.14, item 9.
- **Released on pause.** The scanner is composed only while `RESUMED`, which is stricter than CameraX's default (released on stop), as the AC asks.
- **The note means "your check changed".** It shows only when this ring's own pick had a camera entry swapped. A Random pick that was already safe gets no note.
- **One note slot.** `CheckUiState.note` and `RingingUiState.note` hold one `WakeNote`. While a call pauses the ring, the phone-call note wins, and the Direct Boot note comes back after the call. The alternative, two notes, would need a layout change to the approved screens.
- **Torch state without new strings.** `Role.Switch` with toggleable state lets TalkBack say "on"/"off" in the phone's language.

**Risks for 3.14:**
- CameraX's behaviour on the Oppo A96 when a video-call app holds the camera (error or silent stall) is item 9.
- The privacy toggle on API 31–32, the 3.10 fix-2 fallback, is not on the checklist yet. Add it to 3.14 item 9 if the build agent cannot cover it with Robolectric.

## Verification

**Commands:**
- `./gradlew qualityGate :androidApp:assembleDebugAndroidTest --no-daemon` -- expected: BUILD SUCCESSFUL, Kover gates pass.
- `git status --porcelain androidApp/src/test/screenshots/preview` -- expected: empty.

**Device (human-verify, Story 3.14 — not run here):**
- Item 9: revoke the camera permission, then the message and the link show; a video-call app holding the camera brings the message within 5 s.
- Item 8: 5 wrong codes bring up the link.
- Item 12: reboot before unlock gives Math and the note; after the unlock Math stays; the next alarm is QR.
- Item 14: TalkBack reads the torch state.

## Auto Run Result

Status: implemented in fast mode (one agent), stacked on Story 3.10 (`47a24a3`). Review has not run yet. Read this with the Implementation notes above.

**Built:**
- `CameraMonitor` and `CameraStatus` (`composeApp` `ui/qr`). `problem(p)` takes no time, because a problem does not depend on one.
- `ScanEvent.Frame` and `ScanEvent.CameraUnavailable(CameraProblem)`.
- The `Scan` / `Preview` / `Feed` port.
- The `CodeAnalyzer` heartbeat.
- `cameraProblem(state)`, which replaces `cameraErrorEnds`.
- `WakeQr`, with the new `WakeCamera` (a monitor per entry, kept as snapshot state for the screen): a 250 ms watchdog tick while resumed, the latched `CameraUnavailable` reason, and the "nothing read" link.
- `WakeActivity` composes the scan only while resumed.
- The torch as a `Role.Switch`, only where its state is known (the wake check and "Try it"). Registration keeps its plain button.
- `DirectBootSubstitution.swappedThisRing` with `CheckRun.resolvedFor`, which `forRing` now uses too.
- `wakeNote(session)` in every wake mapper.

**Tests:**
- `CameraMonitorTest`, `WakeNoteTest` (composeApp), `SwappedThisRingTest` (core).
- `QrCameraFailureTest`, `QrLifecycleTest`, `QrFailedAttemptsTest` and `DirectBootNoteTest` (Robolectric, `FakeCodeScanner` + `FakeMonotonicClock`, the real Koin graph).
- `CameraXCodeScannerTest` and `CodeAnalyzerTest` updated. The storage scan now covers `WakeQr.kt`.
- 8 new Roborazzi baselines (the `_sunrise` suffix follows the existing names): `wake_check_qr_watchdog`, `wake_check_qr_link_after_failures`, `wake_ringing_direct_boot`, `wake_check_math_direct_boot`, each at 100% and 200%. No existing baseline changed: the torch switch draws the same pixels.

**Defaults taken, beyond the Design Notes (the owner can change any of them):**
- The 60 s "nothing read" link (see Implementation notes).
- The torch stays lit across a pause.
- Opening the Fallback check picker leaves the QR screen, so it releases the camera, and "Back to check" binds it again.

**Verification:**
- `./gradlew qualityGate :androidApp:assembleDebugAndroidTest --no-daemon` gives BUILD SUCCESSFUL.
- `git status --porcelain androidApp/src/test/screenshots/preview` is empty.
- Earlier gate runs on the shared PC, with other lanes building at the same time, failed once each on tests this story does not touch: `RingingSemanticsTest` ("Failed to capture a node to bitmap"), `SuccessScreenTest` and `AlarmScreensScreenshotTest` (timing). Each passed when run alone, and the whole `:androidApp:testDebugUnitTest` then passed. Watch for this in CI.

## Review (2 reviewers, fast mode)

Two reviewers (verification gaps and edge cases) read `c83296f`. All 20 items are fixed, each with a test (`fix(3.11): review fixes`). Alarm safety came first.

**Kept across a recreated screen (1, 18).** Auto dark mode can flip at sunrise while the alarm rings, and rotation or a font-scale change also recreates the screen. `WakeKept`, a `ViewModel`, now keeps `WakeQr`: the per-entry `WakeCamera` with its latch and monitor, and the torch switch. It also keeps whether the Fallback check picker is open. `QrCameraFailureTest` recreates the screen with a latched link and the torch on, and Math is then allowed with 0 failed attempts. A second test checks that an open picker stays open.

**Nothing-read timer (16).** It counts from the bind or the last code read, whichever is later. Before, one wrong code switched the rule off for good. The old test "a wrong code never gets the link" is inverted: after a wrong code at 1 s, the link shows at 61.1 s. Boundaries (4): 59.999 s gives no link and 60 s gives it; in Robolectric there is no link at 59.9 s.

**Direct Boot note after a kill (19).** `swappedThisRing` is now read from the run: no fallback, and some index where the ring's own resolution is unsafe while the run holds the Direct Boot Math. It no longer needs `directBootRing`, so a ring restored unlocked keeps its Math and its note (`SwappedThisRingTest`). Word step (14): `DirectBootNoteTest` covers All [QR, Word] rung while locked; after the Math is solved, the Word step still shows the note.

**Torch (7, 17).** The scanner lights the torch each time CameraX reports the camera `OPEN`, so it comes back after the screen goes off and on. The switch effect now runs only on a change. `CameraXCodeScannerTest` covers an `OPEN`, `CLOSED`, `OPEN` sequence, and a scan composed, removed and composed again (2 binds, torch `[true, true]`).

**Watchdog start (20).** The scanner sends a new `ScanEvent.Opened` on each camera `OPEN`. While the camera starts, the 5 s count runs from that open, so a slow cold start is not a dead camera. A camera that never opens is caught 15 s after the bind (`CameraMonitor.OPEN_LIMIT_MILLIS`). `FakeCodeScanner(opensAtOnce = false)` with `open()` tests a 4 s open and a first frame 4.5 s later (no message), and a camera that never opens (message at 15 s). A new entry on a camera that is already open counts from the new entry.

**Other items:**
- (2) Recovery boundary: frames every 100 ms give unavailable at +900 ms and Live at +1000 ms.
- (3) Heartbeat boundary: no heartbeat at 1499 ms, the next one at 1500 ms.
- (5) The wall-clock check moved to Robolectric: `WakeApp` with a `FakeClock` moved by an hour, with no effect.
- (6) The failing-decoder monitor test now sends 10 s of ticks with no frames, and checks that one decoded heartbeat is not enough.
- (8) New `WakeCameraTest`: a paused watchdog does not run, the latch ends with its entry, and a change is logged once.
- (9) The message and a focusable link appear in the very next frame (the test clock is paused).
- (10) No preview while unavailable, and one after 1 s of frames.
- (11) The `QrCheckScreenTest` bound-camera test uses a non-sticky problem; a separate test covers a sticky one.
- (12) The picker releases the camera; "Back to check" binds it again with the link kept and the watchdog counting from 0.
- (13) `WakeCheck.screen` reads the lifecycle state, so each resume redraws the screen and reads the permission again, also in Loud. Tests cover a permission revoked while paused, and a restore with no permission (`starts == 0`).
- (15) The latch ends with its entry: on a second QR entry there is no link, and the watchdog counts from 0.

**Residual risks (3.14 item 9):**
- On a real phone: CameraX with a preview surface attached and detached on an `ImageAnalysis` session, black-frame privacy toggles, and video-call apps holding the camera.
- When the screen goes off quickly, recomposition can wait until `ON_START`. The watchdog ignores the stopped time, so this is safe.
