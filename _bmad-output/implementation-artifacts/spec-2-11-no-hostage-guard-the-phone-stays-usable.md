---
title: 'Story 2.11: No-hostage guard: the phone stays usable'
type: 'feature'
created: '2026-10-06'
status: 'in-progress'
baseline_revision: '0a325787d7daa937ed45a88b6e23e1be335bf6f1'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/spec-1-2-ci-pipeline-and-dependency-and-permission-allowlists.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-2-8-alarm-volume-and-volume-keys-on-the-wake-screen.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** The rule "the alarm never holds the phone hostage" (Play policy, FR-SES-9, NFR-13) is enforced only in pieces. The permission check allows anything put on the allowlist and only knows accessibility, device admin and lock-task. Nothing in the build stops a background activity start, an overlay, telephony listening, or a Home or Power key override. Nothing proves on a device that Home, Settings and the dialer stay in front while the alarm keeps playing.

**Approach:** Four layers, each with failing fixtures.
- **Manifest:** the merged-manifest check gets a hard denylist of hostage permissions and new component checks.
- **Code:** one consolidated `NoHostageApis` detekt rule. It absorbs Story 2.8's `NoAudioCaptureOrRouting`, and its telephony ban is the one Story 2.7 relies on.
- **Host test:** a Robolectric test of 5 minutes of heartbeats with the wake screen in the background.
- **Device test:** a GMD test with a debug-only `WakeStatus`.

## Boundaries & Constraints

**Always:**
- **Hostage permissions:** these always fail with "device hostage", even when allowlisted: `SYSTEM_ALERT_WINDOW`, `READ_PHONE_STATE`, `REORDER_TASKS`, `DISABLE_KEYGUARD`, `PACKAGE_USAGE_STATS`, `KILL_BACKGROUND_PROCESSES` and any `android.permission.MANAGE_DEVICE_POLICY_*`.
- **Hostage components:**
  - an intent filter with category `android.intent.category.HOME`;
  - any `android:lockTaskMode` other than `normal`;
  - `android:stopWithTask="true"` on `WakeService`;
  - and, as before, components (or the application) protected by `BIND_ACCESSIBILITY_SERVICE` or `BIND_DEVICE_ADMIN`.
- **`NoHostageApis`:** active for `:androidApp` and `:composeApp` sources, `debug` included. Every message cites NFR-13. It reports:
  - **Lock task, overlays and the keyguard:** calls `startLockTask`, `setLockTaskPackages`, `moveTaskToFront` and `newKeyguardLock`; `addView` on a window manager (receiver text naming `WindowManager`); the names `TYPE_APPLICATION_OVERLAY`, `TYPE_SYSTEM_ALERT`, `DevicePolicyManager` and `AccessibilityService`.
  - **Telephony:** the names `TelephonyManager`, `PhoneStateListener` and `TelephonyCallback`, and the call `registerTelephonyCallback`. Calls are detected through the audio mode only (Story 2.7).
  - **Audio** (moved from 2.8's rule, which is removed): `MediaSession`, `MediaSessionCompat`, `VolumeProvider` and `VolumeProviderCompat`; `setPreferredDevice`, `setSpeakerphoneOn`, `isSpeakerphoneOn`, `setCommunicationDevice` and `registerMediaButtonEventReceiver`.
  - **Background activity starts:** `startActivity` or `startActivities` called inside a class that extends a `Service` or a `BroadcastReceiver`, or anywhere in packages `com.yawnandpawn.app.android.receiver` and `com.yawnandpawn.app.android.wake`, except inside `WakeActivity`.
  - **Key overrides:** an override of `onKeyDown`, `onKeyUp`, `onKeyLongPress` or `dispatchKeyEvent` that names `KEYCODE_HOME`, `KEYCODE_APP_SWITCH` or `KEYCODE_POWER`.
- **Foreground starters move out of `android.wake`:**
  - 2.5's `forwardToWakeScreenWhileResumed` and 2.6's `AndroidWakeScreenOpener` start `WakeActivity` from the screen in front. They move to `com.yawnandpawn.app.android.screen`, with no behaviour change.
  - The wake package then holds only code that never starts an activity.
- **Host test:** a ringing session in the real wake service, `WakeActivity` paused and stopped, then 5 heartbeat slot fires over 5 minutes of fake time. Afterwards there is no started activity and the player still plays.
- **`WakeStatus`:** a debug source-set object in `com.yawnandpawn.app.debug` (so `checkReleaseContent` already keeps it out of release). `isPlaying()` reads the wake runtime's player (sound open, not muted, not paused).
- **Device test:** debug fire, then Home, then Settings (`android.settings.SETTINGS`), then the dialer (`ACTION_DIAL`, no call). Each stays in front for 10 s, `WakeActivity` never resumes, and `WakeStatus.isPlaying()` is true throughout. It runs in CI only.

**Never:**
- No UI and no strings.
- No change to the session behaviour.
- No new permission and no new runtime dependency.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Hostage permission allowlisted | Allowlist and manifest both have `READ_PHONE_STATE` (and each other one) | Violation "device hostage" | Build fails |
| `MANAGE_DEVICE_POLICY_*` | `MANAGE_DEVICE_POLICY_LOCK_TASK` | Violation | Build fails |
| Launcher pose | Activity filter with category HOME | Violation naming the component | Build fails |
| Lock task | `lockTaskMode="always"` or `"if_whitelisted"`; `"normal"` | Violation; none for `normal` | Build fails |
| `stopWithTask` | `WakeService stopWithTask="true"`; another service with it | Violation; none for the other | Build fails |
| Banned API | Each banned call or name in app code | One finding citing NFR-13; compliant snippet: none | detekt fails |
| Background start | `startActivity` in a `Service`, a `BroadcastReceiver` or `android.wake`; inside `WakeActivity` | Finding; none inside `WakeActivity` or an ordinary activity | detekt fails |
| Key override | `onKeyDown` naming `KEYCODE_HOME` / `APP_SWITCH` / `POWER`; volume keys only | Finding; none | detekt fails |
| 5 minutes in background | Ringing, screen stopped, 5 heartbeats | No started activity, player playing | No error expected |

</intent-contract>

## Code Map

- `build-logic/.../PermissionAllowlist.kt` and its test -- the merged-manifest checks. `config/permission-allowlist.txt` header.
- `config/detekt-rules/.../NoAudioCaptureOrRouting.kt` (2.8) -- folded into `NoHostageApis`; the provider, the provider-list test and `detekt.yml` are updated.
- `androidApp/.../wake/WakeScreenForwarding.kt`, `AndroidWakeScreenOpener.kt` -- move to `android/screen`. Callers: `MainActivity`, `YawnAndPawnApp`, and the tests `WakeScreenForwardingTest` and `LeaveAndReturnTest`.
- `androidApp/src/debug/.../debug/` -- `WakeStatus`. `androidApp/src/androidTest/...` -- the GMD test (UiAutomator from 2.5).

## Tasks & Acceptance

**Execution:**
- `PermissionAllowlist.kt` checks, plus fixture tests for every new rule.
- `NoHostageApis.kt` with violating and compliant snippets for each banned item; remove `NoAudioCaptureOrRouting` and its test.
- Move the two foreground starters into `android.screen`.
- `debug/WakeStatus.kt`, `androidTest/PhoneStaysUsableTest.kt`, and `android/wake/NoHostageBackgroundTest.kt` (Robolectric).

**Acceptance Criteria:**
- Given `./gradlew qualityGate`, when it runs, then it passes, Kover is green, and `git status --porcelain androidApp/src/test/screenshots/preview` is empty.
- Given `:androidApp:assembleDebugAndroidTest`, when it builds, then the GMD test compiles. It runs in CI.

## Spec Change Log

- 2026-10-06 (implementation): a run of the gate under heavy machine load (36 min, three lanes building at once) hit the 1-minute `runTest` timeout in the unrelated `:data` `RoomAlarmRepositoryTest`. It passed on the next run, with no change to it. This is recorded as a load-sensitivity risk, not a code issue.

## Review Triage Log

## Verification

**Commands:**
- `./gradlew :build-logic:test :detekt-rules:test` -- expected: pass.
- `./gradlew qualityGate` -- expected: BUILD SUCCESSFUL.

## Design Notes

- **One rule, not two.** `NoHostageApis` replaces 2.8's `NoAudioCaptureOrRouting`. It contains all of that rule's bans, so the 2.8 behaviour is unchanged, but there is now one place to extend. Its telephony ban (`TelephonyManager`, `PhoneStateListener`, `TelephonyCallback`, `registerTelephonyCallback`) is the one Story 2.7 relies on. 2.7 detects calls through `AudioManager.getMode()` only.
- **Foreground starters in `android.screen`.** The rule treats every activity start in `android.wake` (other than `WakeActivity`) and `android.receiver` as a background start. So the two helpers that start the wake screen from the screen in front, 2.5's `forwardToWakeScreenWhileResumed` (from `MainActivity` while resumed) and 2.6's `AndroidWakeScreenOpener` ("Back to alarm"), now live in `com.yawnandpawn.app.android.screen`. This was a package move only.
- **Matching without type resolution.** The rule matches names, as the existing custom rules do. `addView` counts only when its receiver names a window manager or is declared as one in the same file, so `ViewGroup.addView` passes.

## Auto Run Result

**Summary:**
- **Merged-manifest check:**
  - Hostage permissions always fail: `SYSTEM_ALERT_WINDOW`, `READ_PHONE_STATE`, `REORDER_TASKS`, `DISABLE_KEYGUARD`, `PACKAGE_USAGE_STATS`, `KILL_BACKGROUND_PROCESSES` and `MANAGE_DEVICE_POLICY_*`, even when allowlisted.
  - `CATEGORY_HOME` fails, as does any `lockTaskMode` other than `normal` (previously any value failed) and `stopWithTask="true"` on `WakeService`. The accessibility and device-admin rules stay.
- **`NoHostageApis`:** one detekt rule for lock task, task moves, overlays, the keyguard, device admin, accessibility, telephony, media session, volume provider, audio routing, background activity starts and Home, Recents or Power key overrides. Every message cites NFR-13.
- **Robolectric test:** with the wake screen stopped, 5 heartbeat minutes start no activity and the alarm plays throughout.
- **GMD test:** the launcher, Settings and the dialer each stay in front for 10 s while the debug-only `WakeStatus` reports playing. It runs in CI.

**Files changed:**
- `build-logic/.../PermissionAllowlist.kt` and its test; the `config/permission-allowlist.txt` header.
- `config/detekt-rules`: `NoHostageApis.kt` and its test, the provider, and the provider-list test. `NoAudioCaptureOrRouting` and its test are removed. `config/detekt/detekt.yml`.
- `android/screen/WakeScreenForwarding.kt` and `AndroidWakeScreenOpener.kt` (moved), with their test; the `MainActivity` and `YawnAndPawnApp` imports.
- `src/debug/.../debug/WakeStatus.kt`, `androidTest/PhoneStaysUsableTest.kt`, `android/wake/NoHostageBackgroundTest.kt`.

**Verification:**
- `./gradlew qualityGate`: BUILD SUCCESSFUL, with no change under `screenshots/preview`.
- `:androidApp:assembleDebugAndroidTest` builds the GMD tests.

**Not run:** the step-04 review (fast mode). The GMD test does not run locally (no KVM on this PC).
