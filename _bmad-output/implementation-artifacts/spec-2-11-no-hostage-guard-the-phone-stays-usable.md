---
title: 'Story 2.11: No-hostage guard: the phone stays usable'
type: 'feature'
created: '2026-10-06'
status: 'done'
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
- **Host test:** a Robolectric test of 5 minutes of heartbeats with the wake screen in the background, and a debug-only `WakeStatus`.
- **Device check:** Home, Settings and the dialer while ringing, on the phone in Story 2.13's checklist (review: the CI managed device cannot run it).

## Boundaries & Constraints

**Always:**
- **Hostage permissions:** these always fail with "device hostage", even when allowlisted: `SYSTEM_ALERT_WINDOW`, `READ_PHONE_STATE`, `REORDER_TASKS`, `DISABLE_KEYGUARD`, `PACKAGE_USAGE_STATS`, `KILL_BACKGROUND_PROCESSES` and any `android.permission.MANAGE_DEVICE_POLICY_*`.
- **Hostage components:**
  - an `<intent-filter>` with category `android.intent.category.HOME` or `android.intent.category.SECONDARY_HOME` (a `<queries><intent>` lookup passes);
  - any `android:lockTaskMode` other than `normal`;
  - any `android:stopWithTask` on `WakeService` other than `"false"` (`"true"` or a resource reference);
  - and, as before, components (or the application) protected by `BIND_ACCESSIBILITY_SERVICE` or `BIND_DEVICE_ADMIN`.
- **`NoHostageApis`:** active for `:androidApp` and `:composeApp` sources, `debug` included. Every message cites NFR-13. An import alias of any banned name is reported too. It reports:
  - **Lock task, overlays and the keyguard:** calls `startLockTask`, `setLockTaskPackages`, `moveTaskToFront` and `newKeyguardLock`; `addView` on a window manager; the names `TYPE_APPLICATION_OVERLAY`, `TYPE_SYSTEM_ALERT`, `DevicePolicyManager` and `AccessibilityService`.
    - A window manager receiver is one whose text names it (any case: `windowManager`, `activity.windowManager`, `WINDOW_SERVICE`), with a plain or safe call or as the implicit receiver of `with(x)`, `x.apply`, `x.run` (or `it` in `x.let` / `x.also`). It can also be a property or parameter of the file declared as one by type, initializer or delegate (`val wm = getSystemService(..) as WindowManager`).
  - **Telephony:** the names `TelephonyManager`, `PhoneStateListener` and `TelephonyCallback`, and the call `registerTelephonyCallback`. Calls are detected through the audio mode only (Story 2.7).
  - **Audio** (moved from 2.8's rule, which is removed, with its review fixes): `MediaSession`, `MediaSessionCompat`, `VolumeProvider` and `VolumeProviderCompat`, also as `::MediaSession` or `MediaSession::class`. Also `setPreferredDevice`, `setSpeakerphoneOn`, `isSpeakerphoneOn` (property or call), `setCommunicationDevice`, `registerMediaButtonEventReceiver`, `startBluetoothSco`, `setBluetoothScoOn` and `isBluetoothScoOn`. And `setMode(...)` or `mode = ...` with `MODE_IN_COMMUNICATION` or `MODE_IN_CALL`; reading the mode and `MODE_NORMAL` pass.
  - **Background activity starts:** `startActivity`, `startActivities`, `startActivityIfNeeded`, `startIntentSender` and `PendingIntent.send` (calls or `::` references). They count when any enclosing class or object (object literals and local classes included) extends a `Service`, `BroadcastReceiver`, `Worker` / `CoroutineWorker`, `ContentProvider` or `Application`. They also count anywhere in the receivers' package `com.yawnandpawn.app.android` (not its subpackages) and in `com.yawnandpawn.app.android.wake`, except inside `WakeActivity`.
  - **System keys:** `KEYCODE_HOME`, `KEYCODE_APP_SWITCH`, `KEYCODE_POWER` and Compose's `Key.Home`, `Key.AppSwitch` and `Key.Power`, anywhere but an import.
- **Foreground starters move out of `android.wake`:**
  - 2.5's `forwardToWakeScreenWhileResumed` and 2.6's `AndroidWakeScreenOpener` start `WakeActivity` from the screen in front. They move to `com.yawnandpawn.app.android.screen`, with no behaviour change.
  - The wake package then holds only code that never starts an activity. `android.screen` is exempt for these two foreground starters only.
- **Host test:** a ringing session in the real wake service, `WakeActivity` paused and stopped, then 5 heartbeat slot fires over 5 minutes of fake time. Each heartbeat is handled (the slot is re-armed). Afterwards there is no started activity and the player still plays.
- **`WakeStatus`:** a debug source-set object in `com.yawnandpawn.app.debug` (so `checkReleaseContent` already keeps it out of release). `isPlaying()` reads the wake runtime's player: a ring on, its sound open and prepared, not muted, not paused. False without a Koin graph.
- **Device check (Story 2.13):** while ringing, Home, then Settings, then the dialer (no call), each for 10 s. The wake screen never comes back and the alarm keeps playing. It is on Story 2.13's phone checklist (`deferred-work.md`), not a GMD test.

**Never:**
- No UI and no strings.
- No change to the session behaviour.
- No new permission and no new runtime dependency.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Hostage permission allowlisted | Allowlist and manifest both have `READ_PHONE_STATE` (and each other one) | Violation "device hostage" | Build fails |
| `MANAGE_DEVICE_POLICY_*` | `MANAGE_DEVICE_POLICY_LOCK_TASK` | Violation | Build fails |
| Launcher pose | Activity `<intent-filter>` with category HOME or SECONDARY_HOME; HOME in `<queries><intent>` | Violation naming the component; none for the query | Build fails |
| Lock task | `lockTaskMode="always"` or `"if_whitelisted"`; `"normal"` | Violation; none for `normal` | Build fails |
| `stopWithTask` | `WakeService stopWithTask="true"` or `"@bool/x"`; `"false"`; another service with `"true"` | Violation; none for `"false"` or the other | Build fails |
| Banned API | Each banned call or name in app code, or an import alias of it | One finding citing NFR-13; compliant snippet: none | detekt fails |
| Window manager | `windowManager.addView`, `wm?.addView`, `with(wm) { addView }`, `val wm = getSystemService(..) as WindowManager`; `ViewGroup.addView` | Finding; none for the view group | detekt fails |
| Background start | Any start form in a `Service`, `BroadcastReceiver`, `Worker`, `ContentProvider` or `Application` (object literals and local classes included), in `com.yawnandpawn.app.android` or `android.wake`; inside `WakeActivity`, `android.screen` or `android.reliability` | Finding; none inside `WakeActivity`, the screen in front or an ordinary activity | detekt fails |
| System keys | `KEYCODE_HOME` / `APP_SWITCH` / `POWER` or `Key.Home` / `AppSwitch` / `Power` anywhere; volume keys | Finding; none | detekt fails |
| 5 minutes in background | Ringing, screen stopped, 5 heartbeats, each re-arming the slot | No started activity, player playing | No error expected |

</intent-contract>

## Code Map

- `build-logic/.../PermissionAllowlist.kt` and its test -- the merged-manifest checks. `config/permission-allowlist.txt` header.
- `config/detekt-rules/.../NoAudioCaptureOrRouting.kt` (2.8) -- folded into `NoHostageApis`; the provider, the provider-list test and `detekt.yml` are updated.
- `androidApp/.../wake/WakeScreenForwarding.kt`, `AndroidWakeScreenOpener.kt` -- move to `android/screen`. Callers: `MainActivity`, `YawnAndPawnApp`, and the tests `WakeScreenForwardingTest` and `LeaveAndReturnTest`.
- `androidApp/src/debug/.../debug/` -- `WakeStatus`; its test in `src/testDebug`. `AndroidAlarmPlayer.isPrepared`.

## Tasks & Acceptance

**Execution:**
- `PermissionAllowlist.kt` checks, plus fixture tests for every new rule.
- `NoHostageApis.kt` with violating and compliant snippets for each banned item; remove `NoAudioCaptureOrRouting` and its test.
- Move the two foreground starters into `android.screen`.
- `debug/WakeStatus.kt` with `WakeStatusTest`, and `android/wake/NoHostageBackgroundTest.kt` (Robolectric).

**Acceptance Criteria:**
- Given `./gradlew qualityGate`, when it runs, then it passes, Kover is green, and `git status --porcelain androidApp/src/test/screenshots/preview` is empty.
- Given `:androidApp:assembleDebugAndroidTest`, when it builds, then it compiles (this story adds no GMD test).

## Spec Change Log

- 2026-10-06 (implementation): a run of the gate under heavy machine load (36 min, three lanes building at once) hit the 1-minute `runTest` timeout in the unrelated `:data` `RoomAlarmRepositoryTest`. It passed on the next run, with no change to it. This is recorded as a load-sensitivity risk, not a code issue.
- 2026-10-06 (review fixes): the GMD test `PhoneStaysUsableTest` is removed. The CI managed device is an ATD API 34 image (likely without the Settings and Dialer apps), and main dropped the UiAutomator dependency with Story 2.5's notification test. The Home, Settings and dialer check moves to Story 2.13's phone checklist (`deferred-work.md`). The host evidence is `NoHostageBackgroundTest` and `WakeStatusTest`.

## Review Triage Log

### Review (2 reviewers, fast mode), 2026-10-06

Fixed (`fix(2.11): review fixes`):
- **`addView` receivers missed** (patch): lowercase `windowManager` / `activity.windowManager`, safe calls, implicit receivers (`with(wm)`, `wm.apply`, `it` in `wm.let`) and window managers declared with an inferred type (initializer or delegate) are now reported. `NoHostageApisTest`.
- **Background starts missed** (patch): every enclosing class or object counts (object literals, local classes); `startActivityIfNeeded`, `startIntentSender`, `PendingIntent.send` and `::startActivity` are starts; `Worker` / `CoroutineWorker`, `ContentProvider` and `Application` subclasses are background components. The background package was `android.receiver`, which does not exist: the receivers live in `com.yawnandpawn.app.android`, which is now covered (without its subpackages). `NoHostageApisTest`.
- **System keys only in key handlers** (patch): `KEYCODE_HOME` / `APP_SWITCH` / `POWER` and Compose `Key.Home` / `AppSwitch` / `Power` are reported anywhere but an import. `NoHostageApisTest`.
- **Import aliases** (patch): an aliased import of any banned name is reported. `NoHostageApisTest`.
- **Parity with `NoAudioCaptureOrRouting`** (patch): `audio.isSpeakerphoneOn()` as a call is reported again, and 2.8's review fixes are carried over (aliases, `::MediaSession`, `MediaSession::class`, Bluetooth SCO, `isBluetoothScoOn`, `setMode` / `mode =` with a call mode). `DetektConfigTest` checks the rule's `includes` scope.
- **Manifest gaps** (patch): `CATEGORY_HOME` counts only inside an `<intent-filter>` (not a `<queries>` lookup), `SECONDARY_HOME` fails too, and `stopWithTask` on `WakeService` fails for any value but `"false"` (resource references included). `PermissionAllowlistTest` fixtures.
- **No-op wait** (patch): `NoHostageBackgroundTest` waits each heartbeat until the slot is re-armed (the slot fire was handled), and checks started activities once more after the loop.
- **`WakeStatus` too lenient** (patch): `isPlaying()` also needs a ring on and a prepared sound (`AndroidAlarmPlayer.isPrepared`). `WakeStatusTest` covers muted, paused, stopped, still preparing and no Koin graph.
- **GMD test cannot run in CI** (patch): removed; the device check goes to Story 2.13 (`deferred-work.md`).

Deferred:
- **`WakeScreenOpener` from background code** (low): the Koin-injected opener could be called from background code, and the rule would not see it (its start sits in `android.screen`). Its only caller is the "Back to alarm" tap. Noted for Story 2.13 or a later rule (`deferred-work.md`).

## Verification

**Commands:**
- `./gradlew :build-logic:test :detekt-rules:test` -- expected: pass.
- `./gradlew qualityGate` -- expected: BUILD SUCCESSFUL.

## Design Notes

- **One rule, not two.** `NoHostageApis` replaces 2.8's `NoAudioCaptureOrRouting`. It contains all of that rule's bans, so the 2.8 behaviour is unchanged, but there is now one place to extend. Its telephony ban (`TelephonyManager`, `PhoneStateListener`, `TelephonyCallback`, `registerTelephonyCallback`) is the one Story 2.7 relies on. 2.7 detects calls through `AudioManager.getMode()` only.
- **Foreground starters in `android.screen`.** The rule treats every activity start in `android.wake` (other than `WakeActivity`) and in the receivers' package `com.yawnandpawn.app.android` as a background start. So the two helpers that start the wake screen from the screen in front, 2.5's `forwardToWakeScreenWhileResumed` (from `MainActivity` while resumed) and 2.6's `AndroidWakeScreenOpener` ("Back to alarm"), now live in `com.yawnandpawn.app.android.screen`. This was a package move only.
- **Matching without type resolution.** The rule matches names, as the existing custom rules do. `addView` counts only when its receiver names a window manager or is declared as one in the same file, so `ViewGroup.addView` passes.

## Auto Run Result

**Summary:**
- **Merged-manifest check:**
  - Hostage permissions always fail: `SYSTEM_ALERT_WINDOW`, `READ_PHONE_STATE`, `REORDER_TASKS`, `DISABLE_KEYGUARD`, `PACKAGE_USAGE_STATS`, `KILL_BACKGROUND_PROCESSES` and `MANAGE_DEVICE_POLICY_*`, even when allowlisted.
  - `CATEGORY_HOME` fails, as does any `lockTaskMode` other than `normal` (previously any value failed) and `stopWithTask="true"` on `WakeService`. The accessibility and device-admin rules stay.
- **`NoHostageApis`:** one detekt rule for lock task, task moves, overlays, the keyguard, device admin, accessibility, telephony, media session, volume provider, audio routing, background activity starts and Home, Recents or Power key overrides. Every message cites NFR-13.
- **Robolectric test:** with the wake screen stopped, 5 heartbeat minutes start no activity and the alarm plays throughout; the debug-only `WakeStatus` reports playing.
- **Device check:** Home, Settings and the dialer while ringing, on Story 2.13's phone checklist (the GMD test was removed in review).

**Files changed:**
- `build-logic/.../PermissionAllowlist.kt` and its test; the `config/permission-allowlist.txt` header.
- `config/detekt-rules`: `NoHostageApis.kt` and its test, the provider, the provider-list test and `DetektConfigTest`. `NoAudioCaptureOrRouting` and its test are removed. `config/detekt/detekt.yml`.
- `android/screen/WakeScreenForwarding.kt` and `AndroidWakeScreenOpener.kt` (moved), with their test; the `MainActivity` and `YawnAndPawnApp` imports.
- `src/debug/.../debug/WakeStatus.kt` and `src/testDebug/.../WakeStatusTest.kt`, `AndroidAlarmPlayer.isPrepared`, `android/wake/NoHostageBackgroundTest.kt`.

**Verification:**
- `./gradlew qualityGate`: BUILD SUCCESSFUL, with no change under `screenshots/preview`.
- `:androidApp:assembleDebugAndroidTest` compiles.

**Review:** 2 reviewers, fast mode (Review Triage Log).
