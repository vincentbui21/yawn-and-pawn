---
title: 'Story 1.14: Ring the alarm: WakeService, AlarmPlayer and the ongoing notification'
type: 'feature'
created: '2026-10-01'
status: 'done'
baseline_revision: 'd3c37235cc17cb788864a474d6b1864d0667aad7'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
  - '{project-root}/_bmad-output/planning-artifacts/architecture/architecture-pay-per-snooze-2026-09-26/ARCHITECTURE-SPINE.md'
  - '{project-root}/.claude/skills/pps-design/SKILL.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Alarms are scheduled and the session engine works, but when an alarm fires nothing rings. The receiver only re-arms the next occurrence. The engine's effects are only logged. There is no service, no player, no notification and no wake screen.

**Approach:**
- **WakeService:** an alarm fire starts a `mediaPlayback` foreground `WakeService`. It builds the session event (`AlarmFired`, or `OverlapAlarmFired` when a session is active) and dispatches it to `SessionEngine`.
- **WakeRuntime:** a `WakeRuntime` `EffectRunner` replaces `LoggingEffectRunner`. It drives a single `AndroidAlarmPlayer` (alarm stream, set volume, 20%-start ramp), vibration, the session slot and the ongoing full-screen notification.
- **WakeActivity:** a skeleton that renders from the engine state over the lock screen.
- **Never silent:** a broken session store or a crash in the wake flow still rings the default sound.

## Boundaries & Constraints

**Always:**
- **Fire → service.** `AlarmFiredReceiver` handles `ACTION_ALARM` in this order:
  1. Read the alarm.
  2. Let `RearmOnFire` re-arm the next occurrence or disable a one-time alarm, as today.
  3. If the alarm existed and was enabled, call `startForegroundService(WakeService)` with `alarmId` and `scheduledAt`. A disabled or deleted alarm does not ring and is logged, as today.
  - `ACTION_SESSION_SLOT` starts the service with a slot action. `ACTION_TEST_ALARM` stays logged-and-ignored (Story 1.18).
  - The receiver KDoc is updated: it now starts the foreground service. Fires from `setAlarmClock` are exempt from background foreground-service start limits.
- **WakeService.** Directly booted (`directBootAware`), `foregroundServiceType="mediaPlayback"` [ASSUMPTION pending Spike S2].
  - It calls `startForeground` (notification id fixed) in `onStartCommand` before any suspend work.
  - **Alarm action:** if `engine.state` is active, it dispatches `OverlapAlarmFired(alarmId, scheduledAt)`. Otherwise it dispatches `AlarmFired` with:
    - `sessionId` from `IdGenerator`;
    - `config = ConfigResolver.resolve(alarm, GlobalSettings(), testMode = false, scheduledAt)` (GlobalSettings defaults until Epic 5);
    - `seeds` from a seed source;
    - `beforeFirstUnlock = !UserManager.isUserUnlocked`.
  - **Slot action:** it dispatches `SlotFired`. If the engine is Idle afterwards, it stops.
  - **Ticks:** while a session is active, it calls `engine.tick()` once a second. Story 1.16 refines this to deadline-based ticks.
  - **Stopping:** it collects `engine.state`. When the state is Idle, Completed or Missed, it stops the sound and vibration, restores the alarm-stream volume, removes the notification, and calls `stopForeground(REMOVE)` + `stopSelf()`. No service is left running (NFR-8).
- **Never silent, store broken.** If dispatching `AlarmFired` returns a Failure (for example, `runtime.db` cannot be written), the service starts an emergency ring:
  - the default sound at the alarm's volume, with vibration;
  - the ongoing notification;
  - `WakeActivity` in emergency mode, where "I'm up" stops it;
  - an automatic stop after 30 minutes.
  - It is logged.
- **Never silent, crash.** An uncaught exception in the wake flow reaches the service's `CoroutineExceptionHandler`. It is reported through a new core `CrashReporter` port (`FakeCrashReporter` in `:testing`, a no-op production binding until Story 1.19). The player switches to the default sound, and `ProcessRestored` is dispatched (AD-12, NFR-2).
- **WakeRuntime.**
  - It is the production `EffectRunner`, a Koin single.
  - Entry effects: `SoundAt` (play or continue at volume), `SoundPaused`, `Muted`, `SoundOff`, `Vibrating`, `HeartbeatSlotArmed` / `SlotArmedAt` (through `AlarmScheduler.armSessionSlot`), `WakeUiShown` (notification).
  - One-shot effects: `StartWakeRuntime`, `ArmSlot`, `CancelSlot`, `Mute`, `UnmuteToVolume`, `StrongHaptic`, `StopSound`, `PauseSound`, `ResumeSound`, `ClearRuntimeSession`.
  - Effects that belong to later stories (checks UI, billing, motivation, purchase messages) are logged by type name only.
  - Entry effects are idempotent: the same effect again changes nothing.
  - When an effect needs the service and it isn't running (for example, a restore at app start), the runtime starts it. If the platform refuses (`ForegroundServiceStartNotAllowedException` / `IllegalStateException`), the refusal is logged, and the armed session slot (at most 60 s) brings the ring back through the receiver.
  - It never awaits `engine.dispatch` from inside `run`/`apply`. Any event it produces is launched on `ApplicationScope` outside the effect (KDoc states the rule).
- **AndroidAlarmPlayer.** The only player, owned by the runtime and used by the service.
  - It plays looping with `AudioAttributes` usage `USAGE_ALARM` / content type sonification, independent of media and ringer volume.
  - At the start of a ring it sets `STREAM_ALARM` to `volumePercent` of the stream max. The user's previous alarm-stream volume is saved in device-protected `SharedPreferences` and restored when the session ends, including after a crash.
  - **Ramp:** when `gradualVolume` is on, the player gain follows the pure core `rampGain(elapsed, startFraction = rampStartPercent / 100.0, duration = 30 s)`. It is linear from startFraction to 1.0 and clamped at 1.0, updated about every 250 ms. When it is off, the first frame plays at full gain.
  - **Fallback:** the default sound plays when the chosen sound can't be opened or errors during prepare or playback, in the same ring and never silent. `Alarm.DEFAULT_SOUND_REF` `"builtin:default"` maps to it. Full sound-library resolution is Story 1.17. Any non-default ref resolves to the default for now, and that is logged.
- **Vibration** runs with alarm usage (`VibrationAttributes.USAGE_ALARM` on API 33+, alarm `AudioAttributes` below) in a repeating pattern when `config.vibration` is on, and not at all when it is off. It stops with `SoundOff` and with the session's end.
- **Ramp start semantics.** `rampStartPercent` means a percentage of the set volume.
  - Remove the `AlarmValidation` rule "ramp start must not exceed volume".
  - The editor saves `Alarm.DEFAULT_RAMP_START_PERCENT` (20) instead of `min(20, volume)`.
  - Update the `SessionConfig` and contract KDocs to match.
- **Default sound.** `androidApp/src/main/res/raw/alarm_default.ogg` (OGG Vorbis):
  - an in-repo generated alarm tone, 2 to 4 s, seamless loop, peak at or above −3 dBFS, integrated loudness at or above −14 LUFS (Story 1.17's gate);
  - made by a committed generator script under `tools/sounds/`, run with uv in user scope (for example `soundfile` with its bundled libsndfile);
  - documented in `docs/sounds/LICENSES.md` (source: generated in-repo, licence CC0, the generator command).
- **Notification.**
  - Channel id `alarms`, name "Alarms", importance high.
  - Category `alarm`, ongoing, not auto-cancel, visibility public.
  - Small icon: a monochrome vector `ic_stat_alarm` from the sunrise symbol. Colour: the DESIGN.md accent token through a colour resource, with a test asserting it equals the generated token.
  - Title: the alarm time, formatted for the device's 12/24 h setting. Text: "{time} alarm · Tap to return to your alarm".
  - Full-screen intent and content intent to `WakeActivity` (immutable PendingIntents). No action stops the sound.
  - The channel name and text are Android string resources in `androidApp/src/main/res/values/strings.xml`, copied from EXPERIENCE.md verbatim. `CopyRulesTest` (or an equivalent androidApp test) covers that file too.
- **WakeActivity.** A skeleton: Story 1.15 replaces its content with the full Ringing screen.
  - Manifest: `directBootAware`, `showWhenLocked` / `turnScreenOn` (API 27+, flags on API 26), `excludeFromRecents`, `launchMode="singleTask"`, its own task affinity, not exported.
  - Back does nothing (an `OnBackPressedCallback`).
  - It renders from `engine.state` with no loading state, under `PpsTheme(wake = true)`, showing the alarm time and an "I'm up" button (the existing `wake_im_up` string) at 72 dp or more.
  - "I'm up" dispatches `ImUpTapped`. Any other tap dispatches `UserInteracted`. Dispatches are launched outside composition.
  - It finishes when the state is Idle, Completed or Missed.
  - In emergency mode "I'm up" stops the emergency ring.
  - Nothing in the app starts an activity from the background. Only the full-screen intent and the notification open it.
- **Manifest:** `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `USE_FULL_SCREEN_INTENT`, `WAKE_LOCK`, `VIBRATE` and `POST_NOTIFICATIONS` (the request itself is Story 1.19) are declared. All are already in the allowlist, and the permission and dependency checks pass. If `androidx.core` (NotificationCompat, ContextCompat) becomes a direct dependency, it is already allowlisted.
- **Call contract hook (Story 2.7):** the WakeRuntime and `CallStarted` KDoc state that the call adapter re-sends `CallStarted` after `ProcessRestored` and at every new ring while a call is active. No call adapter is built here.
- **Deferred tests** (from Stories 1.10 and 1.12):
  - `ApplicationScope`'s exception handler logs and keeps sibling jobs alive;
  - a receiver whose work exceeds `WORK_BUDGET` logs the timeout and still finishes the pending result;
  - a throwing handler still finishes the pending result.

**Never:**
- No `startActivity` from the background, no overlay, and no full-screen intent abuse beyond the alarm.
- No sound library or picker (Story 1.17), no full Ringing UI or snooze UI (Story 1.15), and no test-alarm session (Story 1.18).
- No permission prompt (Story 1.19). No `systemExempted` service type until Spike S2 decides.
- No `Clock.System` or `println` in core. No raw colours in Compose code.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Ring | Enabled alarm fires, engine Idle | Service in foreground; engine Ringing; player on USAGE_ALARM at the set volume; notification posted | No error expected |
| Overlap | Fire while a session is active | `OverlapAlarmFired` dispatched; no second player | No error expected |
| Disabled | A disabled or deleted alarm fires | No service; logged | No error expected |
| Ramp on | `gradualVolume`, start 20% | Gain 0.2 at 0 s, 0.6 at 15 s, 1.0 at 30 s and at 45 s | No error expected |
| Ramp off | `gradualVolume` false | Gain 1.0 from the first frame | No error expected |
| Vibration | vibration on / off | Vibrator started with alarm usage / never started | No error expected |
| Bad sound | Sound can't open, or errors mid-ring | Default sound plays in the same ring | Logged, never silent |
| Store broken | `AlarmFired` dispatch returns Failure | Emergency ring: default sound, notification, WakeActivity; stops on "I'm up" or after 30 min | Logged |
| Crash | Exception in the wake flow | `CrashReporter` gets it; default sound; `ProcessRestored` dispatched | Reported |
| End | Session Completed, Missed or Idle | Sound and vibration stop, volume restored, notification removed, slot cancelled, service stopped | No error expected |
| Restart blocked | Restore at app start, FGS start refused | Logged; the armed slot revives the ring within 60 s | Logged |

</intent-contract>

## Code Map

- `androidApp/src/main/kotlin/com/yawnandpawn/app/android/AlarmFiredReceiver.kt` -- the actions `ACTION_ALARM` / `ACTION_SESSION_SLOT` / `ACTION_TEST_ALARM`, the extras `alarmId` / `scheduledAt`, the `goAsync` + `ApplicationScope` + `WORK_BUDGET` (8 s) pattern, and the handler calls. Add the service start.
- `core/src/commonMain/kotlin/com/yawnandpawn/app/core/alarm/AlarmFiredHandler.kt` -- `AlarmFired(alarmId, scheduledAt)`, the `AlarmFiredHandler` interface, and `RearmOnFire` (reads under `AlarmWriteLock`, logs `FireIgnored`). Keep its scheduling behaviour. A wrapper or the receiver adds the service start; don't duplicate the re-arm logic.
- `core/.../session/SessionEngine.kt` -- `state` (line 76), `dispatch` (line 79), `tick` (line 100), `restore` (line 131). `SessionPorts.kt` lines 146 to 164: the runner must never call dispatch inside `run`/`apply`.
- `core/.../session/SessionEffect.kt` -- one-shot effects (lines 32 to 154) and `EntryEffect` (lines 180 onward). `SessionRuntime.kt:14` maps state to entry effects. `IdleRules.kt` lines 42 to 64: a start produces `StartWakeRuntime`, `ArmSlot` (+60 s) and `RecordSessionStart`.
- `core/.../session/SessionEvent.kt` -- `AlarmFired(sessionId, config?, seeds, beforeFirstUnlock)` (line 40), `OverlapAlarmFired(alarmId, scheduledAt)` (line 62), `SlotFired`, `ProcessRestored`. `RingRules.kt:32` ignores `AlarmFired` while ringing, so the caller must choose `OverlapAlarmFired`.
- `core/.../session/SessionConfig.kt` -- `GlobalSettings` (line 38), `ConfigResolver.resolve(alarm, globalSettings, testMode, scheduledAt)` (line 57); fix the KDoc at line 14. `CheckRun.kt:32` has `CheckPlan.placeholder()`. `core/.../id/IdGenerator.kt` (single).
- `core/.../alarm/AlarmValidation.kt:15` -- the ramp rule to remove. `composeApp/.../ui/editor/AlarmEditorViewModel.kt:303` has `minOf(...)`, to replace. `AlarmEditorContract.kt:28` KDoc. `core/.../alarm/Alarm.kt:25,40` has `rampStartPercent` and `DEFAULT_RAMP_START_PERCENT`.
- `androidApp/src/main/kotlin/com/yawnandpawn/app/android/AndroidAlarmScheduler.kt` -- `armSessionSlot(Deadline)` (code 1), `cancelSessionSlot`. `ApplicationScope.kt`, `LoggingEffectRunner.kt` (to replace in Koin; keep or delete the class) and `AndroidLogger.kt` (exhaustive `when`; new LogEvents need branches).
- `androidApp/src/main/kotlin/com/yawnandpawn/app/YawnAndPawnApp.kt` -- `appModule`: line 57 `RearmOnFire` and lines 65 to 75 the session bindings (`single<EffectRunner> { LoggingEffectRunner }` becomes the WakeRuntime). Add `CrashReporter`, the seed source and the WakeRuntime.
- `androidApp/src/main/AndroidManifest.xml` -- add the permissions, the `WakeService` and the `WakeActivity`. `config/permission-allowlist.txt` already lists them.
- `androidApp/src/main/res/` -- `values/strings.xml` (only `app_name`); add the notification strings, `drawable/ic_stat_alarm.xml`, `values/colors.xml` and `raw/alarm_default.ogg`. The sunrise source is `composeApp/src/commonMain/composeResources/drawable/symbol_wb_twilight.xml`. The token is the generated `PpsTokens` accent (`ui/theme`).
- `composeApp/src/commonMain/kotlin/com/yawnandpawn/app/ui/theme/` -- `PpsTheme(wake = true)` for the skeleton. Strings: `wake_im_up`. `composeApp/src/androidHostTest/.../CopyRulesTest.kt` scans composeResources; extend it, or add an androidApp equivalent.
- Tests:
  - `androidApp/src/test/kotlin/com/yawnandpawn/app/` -- `TestApp.kt` (`StopAppRule`, `awaitChildren`), `android/SchedulingApp.kt` (Koin overrides, `awaitWork`).
  - `AlarmFiredReceiverTest.kt:107` (slot fires are now handled), `AlarmWiringTest.kt:56`, `SessionWiringTest.kt:47` (`LoggingEffectRunner` assertion) and `ReceiversManifestTest.kt` -- these change.
  - Fakes in `testing/.../SessionEngineFakes.kt`.

## Tasks & Acceptance

**Execution:**
- `core/.../alarm/RampGain.kt` (pure `rampGain`), `core/.../crash/CrashReporter.kt` (port), the seed-source port (or a pure helper), the ramp-rule removal and the KDoc fixes.
- `testing/...` -- `FakeCrashReporter` (and a fake seed source if it's a port), with tests.
- `androidApp/.../wake/` -- `WakeService`, `WakeRuntime`, `AndroidAlarmPlayer` (with a small playback seam so Robolectric can drive errors), `AlarmVibrator`, `WakeNotifier`, `AlarmVolume` (save and restore), `WakeActivity` (skeleton), `EmergencyRing`, `NoOpCrashReporter`; plus the receiver change, the Koin wiring and the manifest.
- `composeApp/.../editor/AlarmEditorViewModel.kt` -- save a ramp start of 20.
- `androidApp/src/main/res/...` -- strings, icon, colour and `raw/alarm_default.ogg`. `tools/sounds/generate_default_alarm.py` and `docs/sounds/LICENSES.md`.
- Tests:
  - `rampGain` at 0, 15, 30 and 45 s, and with start 1.0;
  - Robolectric: receiver → service start → engine Ringing; overlap; disabled alarm → no service; player `USAGE_ALARM` and stream volume set and restored; ramp applied; ramp off → full gain; vibration on/off; notification fields (channel, importance, category, ongoing, full-screen intent, content intent, text, no actions); stop on completion; missing or broken sound → default; store-broken emergency ring; crash → reporter + default + `ProcessRestored`; FGS start refused → logged; WakeActivity flags, Back no-op, "I'm up" → `ImUpTapped`;
  - the three deferred scope and receiver tests;
  - updated wiring and manifest tests, and the editor test (20 saved);
  - the colour-token test and the copy test for the androidApp strings.

**Acceptance Criteria:**
- Given an enabled alarm fires, when Robolectric runs the receiver and the service, then the engine is Ringing, the player plays on `USAGE_ALARM`, and the notification has the full-screen intent to `WakeActivity`.
- Given the session completes, when its state is published, then no service, sound, vibration or notification remains, and the alarm-stream volume is back to the user's value.
- Given `./gradlew qualityGate`, when it runs, then it passes with the permission and dependency allowlists green.

## Spec Change Log

## Review Triage Log

### 2026-10-01 — Review pass (short form)
The owner asked for a faster process during this pass (option A+B+C+D, 2026-10-01). This pass still ran all four layers, but triage now patches only high and medium findings and logs the rest in summary.
- verdicts: about 55 findings across the four layers. About 8 high and 20 medium (patched), about 20 low (rejected unless trivial), and several false (intent-alignment rows that describe the chosen readings).
- **Patched, high:**
  - The wake screen closes when opened before the session is committed.
  - A re-arm failure or budget overrun prevents the service start.
  - The stale `armedSlot` after an early slot fire means a snooze never ends.
  - Effect exceptions are swallowed by the engine's guard, leaving a silent session.
  - Failed restore, SlotFired and Overlap dispatches, and a crash with a pending fire outside Ring, leave the alarm silent. These now start the emergency ring.
- **Patched, medium:**
  - The crash-loop cap.
  - The `stopSelf` / startId race.
  - Non-alarm starts flashing a full-screen notification.
  - The fallback-open budget across a long ring.
  - `pause()` on a prepared player.
  - The emergency-limit race.
  - The cold-start volume-restore race.
  - Old alarms' ramp start (the resolver now fixes it at 20).
  - Tests for the ramp loop, the service tick, an unreadable alarm, overlap while snoozed, a cold-start merge, the MediaPlayer error listener, app-start volume restore and `beforeFirstUnlock`.
- **Patched, low (trivial):** machine-specific paths removed from LICENSES.md.
- **Deferred:**
  - A refused FGS start for a fresh alarm (no slot to retry), and `stopSelf` after a refused `startForeground` → Spike S2 / Story 1.20 (device evidence).
  - No notification permission on Android 13+ until Story 1.19 → Story 1.19.
  - `MediaPlayer.prepare()` on the main thread → Story 1.17 (sound library and user files).
  - 24 h notification time test → Story 1.15.
  - A history write retried only on the next start or alarm after the service stops → low, kept (Story 1.13 settles it on the next alarm).
  - Emergency-ring backstop slot → Story 2.1.
- **Rejected (low):**
  - Alarm-stream volume kept raised during a snooze (alarm stream only; restored at the session end).
  - The sound generator writes before checking (dev tool; checks fail the run).
  - The intent auditor's descriptive divergences, which the chosen readings cover.

## Design Notes

- **Player seam:** wrap `MediaPlayer` behind a tiny interface (prepare, start, setVolume, setLooping, release, error callback) so tests can force errors without relying on Robolectric's ShadowMediaPlayer quirks. Robolectric's ShadowMediaPlayer and ShadowAudioManager are fine where they work.
- **Service–runtime link:** the runtime holds a reference to the running service (set in `onCreate`, cleared in `onDestroy`). Notification posting and stopping go through the service when it exists. Without a service, the runtime starts it (see Always).
- **Tick loop:** a coroutine on the service scope, `while (active) { engine.tick(); delay(1.seconds) }`, cancelled on stop.
- **Environment (company PC):**
  - Export `JAVA_HOME=C:/Users/BuiTua/AppData/Local/Programs/jdk17/jdk-17.0.20.1+1`.
  - Run the sound generator with `C:/Users/BuiTua/AppData/Local/Microsoft/WinGet/Packages/astral-sh.uv_Microsoft.Winget.Source_8wekyb3d8bbwe/uv.exe run --with soundfile --with numpy tools/sounds/generate_default_alarm.py`, with `UV_PYTHON=C:/Users/BuiTua/AppData/Local/Programs/Python/Python313/python.exe` and `UV_PYTHON_DOWNLOADS=never`.
  - Never use the owner's phone.

## Verification

**Commands:**
- `./gradlew qualityGate` -- expected: BUILD SUCCESSFUL, with both allowlists, lint and the Kover rules green.
- `git status --porcelain androidApp/src/test/screenshots` -- expected: empty, or only new baselines this story adds intentionally.

## Auto Run Result

**Summary:** an alarm that fires now rings.
- **Fire path:** `AlarmFiredReceiver` re-arms through `RearmOnFire`, then starts the foreground `WakeService` (`mediaPlayback`; the start is in a `finally`, so a failed re-arm still rings). The service dispatches `AlarmFired` (session id, resolved config, seeds, `beforeFirstUnlock`), or `OverlapAlarmFired` while ringing or snoozed. It ticks the engine once a second and stops when the session ends.
- **Effects:** `WakeRuntime` replaces `LoggingEffectRunner`. It drives the single `AndroidAlarmPlayer`, vibration, the session slot and the notification. Every effect is guarded, so an exception is reported and falls back to the default sound.
- **Player:** `USAGE_ALARM`, the stream set to the alarm volume and restored afterwards (including after a crash), and a 20% start ramp through core `rampGain`. A failing sound falls back to the default, then the system alarm tone, with a retry every 5 s.
- **Notification:** channel Alarms, the full-screen intent and no stop action. Starts without a session use a quiet "Alarm in progress" notification instead.
- **WakeActivity:** a skeleton over the lock screen, with "I'm up" and Back doing nothing. It waits for the session when opened early.
- **Never silent:** a broken store, failed dispatches and crashes start a 30-minute emergency ring. A crash loop is capped at 3.
- **Ramp start:** the ramp-start rule is removed, and the resolver always uses 20.
- **Default sound:** `alarm_default.ogg` is generated in the repo (CC0, `tools/sounds`, `docs/sounds/LICENSES.md`).
- **Deferred tests from earlier stories:** the `ApplicationScope` and receiver-budget tests are added.

**Process:** the review was run with all four layers. During it, the owner chose the faster process for the rest of the epic: A+B+C+D (pipeline CI, two reviewers, high/medium patches only, one gate, parallel lanes). Triage here already used the high/medium-only rule; see the short-form log.

**Follow-up review recommendation:** `true`. Many high and medium patches went into the wake service lifecycle, and none of it has been run on a device. Stories 1.20 and 1.21 on the Oppo A96 are the real check.

**Verification:**
- `./gradlew :androidApp:cleanTestDebugUnitTest qualityGate`: BUILD SUCCESSFUL.
- The new suites passed, including WakeServiceTest (19), WakeRuntimeTest (23) and AndroidAlarmPlayerTest (17).
- No screenshot changed.

**Residual risks:**
- The `mediaPlayback` service type is pending Spike S2.
- Until Story 1.19 there is no notification permission prompt.
- `prepare()` runs on the main thread (Story 1.17).
- The deferred items are in `deferred-work.md`.
