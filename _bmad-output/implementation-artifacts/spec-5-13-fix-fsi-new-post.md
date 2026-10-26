---
title: 'Story 5.13 device fix: the full-screen intent is always a new post'
type: 'bugfix'
created: '2026-10-26'
status: 'review'
baseline_revision: 'bb25d5bb'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/story-5-13-epic-5-device-verification-checklist.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-epic-4-device-followups.md'
warnings: []
deferred:
  - 'Story 5.13 (human-verify, Oppo A96): repeat the locked, screen-off test alarm and the real-alarm paths listed under Device checks'
---

<intent-contract>

## Intent

**Problem (HIGH, Story 5.13 phone check, Oppo A96, ColorOS, Android 13, Play build 0.1.3):** a test alarm rang while the phone was locked with the screen off. The sound played, but the screen never woke and the wake screen never showed; only the ongoing notification appeared.

**Root cause (verified in the code):** the ringing notification's full-screen intent reached SystemUI only as an UPDATE of a notification already posted under the same id (1014). Android 13's SystemUI launches a full-screen intent only when a notification is first posted, so the update was ignored.
- Test path: `WakeServiceStarter.startTest(token)` did not carry the ring time, and `AlarmFiredReceiver` called `onTestAlarmFired()` without the `EXTRA_SCHEDULED_AT` that `AndroidAlarmScheduler.scheduleTest` put on the fire. For `ACTION_TEST`, `fired` was null, so `enterForeground(null)` posted `buildQuiet()` ("Alarm in progress", no full-screen intent). The session's `WakeUiShown` then called `WakeNotifier.show()`, which did `notify(1014, buildRinging)`: an update, ignored.
- The same quiet-first, upgrade-later pattern broke real alarm paths (from the code, not reproduced on the phone):
  - snooze re-ring (HIGH): the Snoozed screen closes with `ringing = false`, so the quiet on-screen version stays; at the snooze end `show()` only updated it;
  - kill or crash recovery and a refused-start retry (HIGH): `ACTION_SLOT` / `ACTION_RESTORE` in a new process have nothing shown, so they post the quiet one first;
  - leaving with the power key, then the grace end or a loud ring (MEDIUM): the full version that should turn the screen on again was only an update.

**Approach:**
1. The test fire carries its ring time end to end: `AlarmFiredReceiver` → `AlarmFiredHandler.onTestAlarmFired(scheduledAt)` → `WakeServiceStarter.startTest(token, scheduledAt)` → `WakeService.testIntent` (`EXTRA_SCHEDULED_AT`). With nothing shown and nothing ringing, the test start's `startForeground` is the full ringing notification with its full-screen intent.
2. General rule in `WakeNotifier`: whenever the full version (Alarms channel, full-screen intent) replaces a version without it (the quiet start, the on-screen one, the way-back heads-up), it is a NEW post under the other of two ids (1014 / 1016; 1015 is the weekly summary). Order: `notify(newId, full)`, then the wake service's `startForeground(newId, full)` (the foreground notification moves; `ForegroundHost`), then `cancel(oldId)`. `WakeNotifier.currentId` is the id in use. A content-only change, or a version without the full-screen intent, stays a plain update under the same id.
3. Every post goes through the notifier, also the service's own `startForeground` (`WakeNotifier.postForeground`), so the notifier always knows which version is posted under which id.
4. A wake screen opened by a full-screen intent before any ring closes once the wake service stops with nothing rung (a test with nothing pending, an ignored fire): `WakeRuntime.serviceStopped` → `WakeScreenEnd.follow(..., serviceStopped)`.

## Boundaries & Constraints

**Always:** alarm reliability first: the full-screen intent is never an update; at most one alarm notification shows; the existing quiet-on-screen and way-back behaviour stays (Epic 3 device check, PR #41 review, Story 4.15 review).

**Never:** no new strings, no change to the session engine or the reducer, no change to which step decides to show the full version (only how it is posted). SessionLockGuard, PurchaseStateReader and NoDirectTimeAccess scans stay green.

## I/O & Edge-Case Matrix

| Scenario | Before | After |
|----------|--------|-------|
| Test alarm, locked, screen off | quiet first, full by update (ignored) | first post is the full one with the full-screen intent |
| Test fire with no ring time (older intent) | quiet, full by update | quiet first, then the full one as a new post |
| Test fire, nothing pending | quiet, service stops | full first post opens the screen; the service stops; the screen closes |
| Snooze end after the Snoozed screen closed | quiet on-screen → full by update | full as a new post under the other id |
| Snoozed screen finishing exactly at the snooze end | full by update | full as a new post |
| Slot / restore start in a new process into a stored ring | quiet → full by update | quiet → full as a new post |
| Left with the power key, then grace end / loud / heartbeat | way-back heads-up → full by update | full as a new post |
| Slot start during a snooze | full (by update) | keeps the quiet on-screen one (no full-screen intent before the re-ring) |
| First ring | full first post | unchanged (one post, id 1014) |
| Ring paused by a phone call, wake screen covered by the call | full by update (ignored) | never the full version; the call's end posts it as a new post |
| Alarm fire during a snooze | full first (by update) | quiet / way-back kept; the merged ring step posts the full one as a new post |
| Platform refuses moving the foreground (startForeground throws) | — | logged; the new full post stays beside the service's own (two notifications until the session ends); the next new post never reuses the service's id |

</intent-contract>

## Code Map

- `androidApp/.../wake/WakeNotifier.kt` -- `post()` (new-post rule), `postForeground(host, alarmAt, ringing)`, `currentId`, `OTHER_NOTIFICATION_ID = 1016`, `NOTIFICATION_IDS`, `ForegroundHost`, `foregroundStopped(host)`; `Posted.Quiet`; `cancel()` removes both ids; optional `Logger`.
- `androidApp/.../wake/WakeRuntime.kt` -- `enterForeground(host, alarmAt, ringing)` and `foregroundStopped(host)` replace `foregroundNotification` / `foregroundPosted`; `serviceStopped` flow.
- `androidApp/.../wake/WakeService.kt` -- `enterForegroundFor(intent, fired)`: alarm → full; test with nothing shown and Idle → its ring time, full; slot / restore / repost → the shown one, full only while ringing (`forwardsToWakeScreen`); `foregroundHost`; `testIntent()`; `shutdown()` / `onDestroy()` tell the notifier.
- `androidApp/.../wake/WakeServiceStarter.kt`, `WakeAlarmFiredHandler.kt`, `android/AlarmFiredReceiver.kt`, `core/.../alarm/AlarmFiredHandler.kt` -- the test's ring time end to end.
- `androidApp/.../wake/WakeScreenEnd.kt`, `WakeActivity.kt` -- close before any ring once the service stopped.
- `androidApp/.../wake/WakeModule.kt` -- the notifier gets the `Logger`.
- Tests: `NotificationPosts.kt` (new: `RecordingNotificationManager` shadow + per-id replay), `FullScreenNewPostTest` (new), `WakeServiceTestStartTest` (new), `LeaveAndReturnTest`, `SnoozedScreenTest`, `WakeNotifierTest`, `WakeRuntimeTest`, `AlarmFiredReceiverTest`, fakes in `AlarmFiredReceiverBudgetTest` / `WakeAlarmFiredHandlerTest`, `MathCheckScreenTest`.

## Design Notes

- **Two ids, not cancel-then-post:** an app cannot cancel the foreground service's own notification (the platform ignores it), so the new post goes under the other id and `startForeground` moves the service to it; the platform then drops the old foreground notification, and `cancel(oldId)` covers the case without a service.
- **DEFAULT TAKEN: a slot start during a snooze keeps the quiet on-screen version** (before: the full version as an update, which did nothing on Android 13). Now that full-screen posts work, posting it there would flash the wake screen with the Snoozed note before the re-ring.
- **DEFAULT TAKEN: the close of a waiting wake screen keys on the service's stop (`onDestroy`), not on "not running"**, so a screen opened before any service start (tests, Recents after a kill) still waits as before.

## Spec Change Log

- After review: a ring paused by a phone call never posts the full version (`show(alarmAt, ringing)`, `WakeRuntime.pausedByCall`); an alarm fire during a snooze or a call posts no full-screen intent on its foreground start (its merged ring step does); the notifier tracks the service's foreground id apart from `currentId`; without the service, or beside a refused foreground, a new post is cancel-then-notify under the same id; `cancel()` while in the foreground marks the state unknown, so the next full one is a new post.

## Review Triage Log

One combined reviewer (wake-path focus, read-only) on the first version. Every path in the matrix traced as fixed; no deadlock (lock order `effectLock` → notifier lock); no id clash (1014, 1015 weekly summary, 1016); scans unaffected.

| # | Sev | Finding | Decision |
|---|-----|---------|----------|
| 1 | MEDIUM | With the fix working, a ring paused by a phone call would post the full version at each step / heartbeat after the call screen covered the wake screen: the wake screen would jump over the call (locked phone) or head up every ≤60 s. | **Fixed.** Paused rings post the way-back / on-screen version only (`WakeRuntime.showWakeUi`, `WakeService.enterForegroundFor`); `CallEnded`'s step posts the full one as a new post. Test: `LeaveAndReturnTest` "a ring paused by a call never posts the full-screen intent over the call, and the call's end does". |
| 2 | MEDIUM | A refused `startForeground` move left `currentId` on the new id while the service kept the old one; the next "new" post went to the still-shown old id as an update (the original bug), and the app's `cancel(oldId)` of a foreground notification does nothing on a device. | **Fixed.** `foregroundId` tracked apart from `currentId`; a new post never goes under the service's id; beside it, cancel-then-notify under `currentId` is a new post. No `cancel` of the foreground id after a refusal. Tests: `WakeNotifierTest` refused-move test (two ids, then a second new post). |
| 3 | LOW | An `ACTION_ALARM` start during a snooze posted the full version first; a fire then ignored flashed the wake screen, and the full version stayed posted, so the re-ring's `show()` did nothing. | **Fixed.** A fire start is full only when the state is not Snoozed and not call-paused; the merged ring step posts the full one as a new post. |
| 4 | LOW | `cancel()` while in the foreground kept `posted = Full`; a new ring before `shutdown()` would only update. | **Fixed.** `cancel()` with the service in the foreground sets `currentId` to the foreground id and marks the posted version unknown (`Quiet`), so the next full one is a new post. |
| 5 | LOW | The recorder treated every app `cancel` as a removal, unlike a device for a foreground-service notification. | **Fixed.** `NotificationPosts` replays with the platform's rules (app cancel of the foreground notification ignored; a `startForeground` under another id drops the old one); `assertAtMostOneShown` checks both Robolectric and the device model. |
| 6 | LOW | Leaving with the power key while ringing (or in Grace) now really brings the wake screen back at the next heartbeat (≤60 s) or ring step. | **Accepted** (the design since PR #41; it never worked on Android 13). Added to the device checks. |
| 7 | LOW | Pre-existing: a full post shown as a heads-up on an in-use phone, then the phone locked without opening it, is never re-posted. | **Deferred** (not caused by this change; same as before). |
| 8 | LOW | Style: `build()` used by tests only; public `NOTIFICATION_IDS`; 4-term condition; theoretical `show()` between `endSession()` and `stopForeground()`. | **Partly fixed** (the 4-term condition is now `shown == null && idle`); the rest accepted. |

**Independent review of PR #95** (no HIGH, 1 MEDIUM, 5 LOW), fixed on the rebased branch:

| # | Sev | Finding | Decision |
|---|-----|---------|----------|
| R1 | MEDIUM | The test branch of `enterForegroundFor` read `engine.state` before `restore()`: in a new process a test fired into a stored snooze posted the full version, the wake screen opened over the snooze, and the snooze end was then a plain update (the original bug). | **Fixed.** The test's full-first path needs `engine.restored`; otherwise the quiet one. A snooze (restored or kept) never keeps a stale full version: `WakeService.evaluate` → `WakeRuntime.keepQuietUnlessRinging()` (`show(alarmAt, ringing = false)`, Full → Away). Test: `FullScreenNewPostTest` "a test fired into a stored snooze in a new process opens nothing, and the snooze end is a new full post". |
| R2 | LOW | The emergency ring was exempt from the call guard: its notification could go full during a call and jump over it. | **Fixed.** `WakeRuntime.fullScreenAllowed()`: the emergency ring follows `calls.inCall()` (start, ring steps, slot starts); `onEmergencyCall(true)` posts without the full-screen intent, `onEmergencyCall(false)` (the call ended) posts the full one as a new post. Test: `LeaveAndReturnTest` "an emergency ring during a call never posts the full-screen intent over it, and the call's end does". |
| R3 | LOW | A refused move shows two notifications. | **Accepted**; the coordinator checks logcat on the A96. |
| R4 | LOW | The cancel-then-notify fallback under the same id can count as an update while SystemUI keeps a heads-up entry after the cancel. | **Fixed.** Third id 1017 (`THIRD_NOTIFICATION_ID`): a new post always goes under an id with nothing on it (neither the current one nor the service's foreground one); the old non-foreground post is removed after the new one shows. Test: `WakeNotifierTest` refused-move test extended (1016 → 1017 → back to one notification once the move is allowed) and "without the wake service a new post goes under another id". |
| R5 | LOW | `WakeScreenEnd` closed a waiting screen only when `restored`: with a failing store load, a test with nothing pending left a stuck wake screen (Back does nothing). | **Fixed.** The close branch is `!seen && serviceStopped`. Test: `FullScreenNewPostTest` "a test fire with nothing pending and a store that cannot load still closes the waiting wake screen". |
| R6 | — | Tests for the above. | Added (above). The optional "every not-ringing → ringing move has a new full post" sweep was not added. |

## Verification

One Gradle invocation at a time, `--no-daemon --max-workers=2`, JDK 17. All passing:
- `:androidApp:testDebugUnitTest` with `*.Wake*`, `*.LeaveAndReturn*`, `*.Snooze*`, `*.AlarmFired*`, `*.TestAlarm*`, `*.Notification*`, `*.Restore*`, `*.FullScreenNewPost*`, `*.SessionKillRecovery*`, `*.MathCheckScreen*`: 35 classes, 415 tests, 0 failures. Among them `FullScreenNewPostTest` 4, `WakeServiceTestStartTest` 2, `LeaveAndReturnTest` 18, `WakeNotifierTest` 21, `SnoozedScreenTest` 11, `WakeRuntimeTest` 43, `WakeServiceTest` 38, `TestAlarmFlowTest` 14, `SessionKillRecoveryTest` 8, `AlarmFiredReceiverTest` 9.
- Scans: `:core:jvmTest` `AlarmSchedulingTest` + `SessionLockGuardTest` (39), `:data:testAndroidHostTest` `PurchaseStateReaderScanTest` + `SessionLockGuardScanTest` (8), `:detekt-rules:test` `NoDirectTimeAccessTest` (6).
- `spotlessCheck` and `detekt` (all modules).

Note: the `ACTION_TEST` foreground assertions live in `WakeServiceTestStartTest`, not `WakeServiceTest`, because `WakeServiceTest` is at detekt's LargeClass limit.

## Auto Run Result

Status: review (committed on `fix/fsi-new-post`, not pushed).

**Residual risks:**
- Moving the foreground notification calls `startForeground` again from effects (grace end, heartbeat, snooze end), not only from `onStartCommand`. If Android 12+ refuses that from the background on a device, the new full post still shows (the full-screen intent works) but two alarm notifications show until the session ends; it is logged as `OperationFailed operation=move the wake service notification`.
- Now that full-screen posts work, the wake screen really comes back at the next heartbeat (≤60 s) or ring step after leaving with the power key while ringing or in Grace (the PR #41 design, never seen on Android 13 before).
- Host tests only: Robolectric does not run SystemUI; the recorder models the platform's foreground-notification rules but not OEM (ColorOS) behaviour.
- Deferred (pre-existing): a full post shown as a heads-up on an in-use phone, then locked without opening, is not re-posted.

**Device checks to repeat (Oppo A96):** see the deferred item: test alarm locked + screen off; snooze re-ring with the screen off; the checklist's kill-during-a-ring step, then the slot re-ring with the screen off; power key during Ringing and during Grace, then loud; a phone call during a ring; logcat for "startForeground() not allowed" and "move the wake service notification".
