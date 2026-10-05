---
title: 'Story 2.5: Leave the alarm and come back through the notification'
type: 'feature'
created: '2026-10-05'
status: 'in-progress'
baseline_revision: '6b1fc58d74b276c446138e55f900ef5b3774e428'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-1-14-ring-the-alarm-wakeservice-alarmplayer-and-the-ongoing-notification.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-1-15-ringing-screen-over-the-lock-screen-with-i-m-up.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** The user can leave the wake screen (Home, Recents, another app). The way back is shaky:
- On Android 14+ the ongoing notification can be swiped away. Nothing posts it again, so the one-tap way back is gone.
- Opening the app from the launcher during a ring shows the normal app instead of the alarm.
- Nothing proves that leaving keeps the sound playing, or that the notification brings back the one wake screen.

**Approach:**
- The ringing notification gets a `deleteIntent` that restarts `WakeService` with a repost action, and `startForeground` posts it again.
- `WakeNotifier.show` posts again whenever the notification is missing, so the `WakeUiShown` entry effect restores it on the next step (heartbeat).
- `MainActivity` forwards to `WakeActivity` while resumed and the alarm rings.
- Robolectric tests cover leaving and returning, and a GMD instrumented test covers the notification tap.

## Boundaries & Constraints

**Always:**
- **Leaving the wake screen:** Home, Recents and other apps always work. Pausing or stopping `WakeActivity` changes nothing in the session: `WakeService` stays in the foreground, and the player and vibration go on. Back stays a no-op.
- **Notification tap:** the content intent and the full-screen intent both open `WakeActivity` (`singleTask`, own `taskAffinity`). Opened again, it renders the current `SessionEngine.state` with no extra state from the intent.
- **The notification:**
  - It has no actions, ever. Its text stays "{time} alarm · Tap to return to your alarm" (EXPERIENCE.md) in Ringing, Grace and Loud.
  - Its `deleteIntent` is an immutable `PendingIntent.getService` for `WakeService.ACTION_REPOST`. That start only re-enters the foreground with the ringing notification of the alarm shown, then evaluates the state like any start: it stops the service when nothing rings. It dispatches no session event and is not timed.
- **Re-posting:** `WakeNotifier.show(alarmAt)` returns early only when that alarm's notification is actually active (`NotificationManager.activeNotifications`). Otherwise it posts again, so every `WakeUiShown` (each committed step, including the heartbeat `SlotFired`) restores a missing notification.
- **Launcher forwarding:** `MainActivity`, while RESUMED, starts `WakeActivity.intent()` as soon as the session is in `Ring` (Ringing, Grace or Loud) or an emergency ring plays. That happens at resume, or when a ring starts while the app is in front. It forwards at most once per resume.
  - In `Snoozed`, Idle, Completed or Missed it never forwards. The Snoozed screen comes in Story 2.6.
  - The decision is a pure function, `forwardsToWakeScreen(state, emergency)`.
- **No background starts:** nothing starts an activity from a service, receiver or background coroutine. The only new `startActivity` is in `MainActivity` while it is resumed.

**Never:**
- No new screen, string, notification action, overlay, or global key handling.
- No change to the session state machine or to core.
- No `stopWithTask` change.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Home during a ring | Ringing, `WakeActivity` paused then stopped | Player playing, service in foreground, no activity started | No error expected |
| Back | Back pressed on `WakeActivity` | Not finishing, state unchanged | No error expected |
| Notification tapped | Ringing; content intent sent 3 times | Each targets `WakeActivity` (singleTask, own affinity); the screen shows the current state | No error expected |
| Swiped away | Ringing; notification cancelled, `deleteIntent` sent | `WakeService` started with `ACTION_REPOST`; the notification is back; player still playing | Session ended meanwhile: quiet start, then the service stops |
| Missing at heartbeat | Ringing; notification cancelled, `SlotFired` | Notification posted again | No error expected |
| No actions | Any ringing notification | `actions` empty; delete intent present | No error expected |
| Launcher in Ringing | Ringing; `MainActivity` resumed | `WakeActivity` started once | No error expected |
| Launcher in Snoozed / Idle | Snoozed or Idle | No `WakeActivity` start | No error expected |

</intent-contract>

## Code Map

- `androidApp/.../android/wake/WakeNotifier.kt` -- `build()` (content, full-screen intent, no actions); `show()` returns early on `shownFor`.
- `androidApp/.../android/wake/WakeService.kt` -- `onStartCommand` enters the foreground with `runtime.shownAlarmAt()`, then runs one command. Add `ACTION_REPOST` (no command).
- `androidApp/.../android/wake/WakeRuntime.kt` -- `showWakeUi()` on `WakeUiShown`; `emergency`.
- `androidApp/.../android/wake/WakeActivity.kt` -- `intent()` (NEW_TASK and NO_USER_ACTION), Back callback.
- `androidApp/src/main/AndroidManifest.xml` -- `WakeActivity` is already `singleTask` with `taskAffinity`; no change.
- `androidApp/.../MainActivity.kt` -- add the forwarding.
- `androidApp/src/test/.../android/wake/WakeApp.kt`, `WakeServiceTest.kt`, `WakeNotifierTest.kt`, `WakeActivityTest.kt` -- the Robolectric harness and existing tests.
- `androidApp/src/androidTest/...` and `androidApp/build.gradle.kts` (managed device `atdApi34`, run in CI) -- the GMD test. UiAutomator is a new androidTest dependency.

## Tasks & Acceptance

**Execution:**
- `WakeNotifier.kt` -- the delete intent, and `show` posts again when the notification is missing.
- `WakeService.kt` -- `ACTION_REPOST`, plus `repostIntent()`.
- `MainActivity.kt` and a new `android/wake/WakeScreenForwarding.kt` -- the pure decision and the RESUMED collector.
- Tests:
  - `android/wake/LeaveAndReturnTest.kt` -- every matrix row;
  - `WakeScreenForwardingTest` -- the pure decision for every state;
  - `androidTest/.../ReturnThroughNotificationTest.kt` -- GMD: debug fire, Home, open the shade, tap the notification, `WakeActivity` resumed within 1,000 ms, and three taps leave one instance.
- `gradle/libs.versions.toml`, `androidApp/build.gradle.kts` -- `androidx.test.uiautomator` for androidTest only. Update `config/dependency-allowlist.txt` if the check covers androidTest.

**Acceptance Criteria:**
- Given the GMD test in CI (`atdApi34DebugAndroidTest`), when the notification is tapped after Home, then `WakeActivity` is resumed within 1,000 ms and one instance exists after three taps.
- Given `./gradlew qualityGate`, when it runs, then it passes, and `git status --porcelain androidApp/src/test/screenshots/preview` is empty.

## Spec Change Log

- 2026-10-05 (implementation): `WakeRuntimeTest` "the foreground notification is the one entry effects would post" assumed `startForeground` without posting anything. Now that `show` checks the active notifications, the test posts the notification as `startForeground` does, and asserts that the same instance stays posted. A new test covers re-posting after a swipe. KEEP: `show` never re-posts a notification that is still active.

## Review Triage Log

## Verification

**Commands:**
- `./gradlew :androidApp:testDebugUnitTest --tests` with each new test class -- expected: all pass.
- `./gradlew :androidApp:assembleDebugAndroidTest` -- expected: the instrumented test compiles. No KVM on this PC, so it runs in CI only.
- `./gradlew qualityGate` -- expected: BUILD SUCCESSFUL.

## Auto Run Result

**Summary:** the user can leave the alarm and come back in one tap.
- **Leaving changes nothing:** the sound, vibration, foreground service and notification all go on. Back is still a no-op once the screen is in front again.
- **The ringing notification:**
  - It has no actions, and its tap and full-screen intent open the one `singleTask` wake screen on the current state.
  - Its new `deleteIntent` restarts `WakeService` with `ACTION_REPOST`, which only re-enters the foreground with the ringing notification.
  - `WakeNotifier.show` now posts again when the notification is no longer active, so the next `WakeUiShown` (each step, the heartbeat included) restores it.
- **Opening the app during a ring:** `MainActivity` hands over to `WakeActivity` while resumed and the alarm rings (Ringing, Grace, Loud or emergency), once per resume. It never does in Snoozed, Idle, Completed or Missed.

**Files changed:**
- `android/wake/WakeNotifier.kt`, `WakeService.kt`, new `WakeScreenForwarding.kt`, and `MainActivity.kt`.
- Tests: `LeaveAndReturnTest` (6 tests), `WakeScreenForwardingTest`, and `WakeRuntimeTest` (1 adjusted, 1 added).
- `androidTest/ReturnThroughNotificationTest` (GMD).
- `libs.versions.toml` and `androidApp/build.gradle.kts`: UiAutomator 2.3.0, androidTest only. The allowlist checks only the app's runtime classpaths, so it is unchanged.

**Verification:**
- `./gradlew qualityGate`: BUILD SUCCESSFUL, with no preview-baseline change.
- `:androidApp:assembleDebugAndroidTest` compiles.
- The GMD test runs only in CI (`atdApi34DebugAndroidTest`), because this PC has no KVM.

**Not run:** the step-04 review (fast mode). The device heads-up behaviour is checked in Story 2.13 (human-verify).
