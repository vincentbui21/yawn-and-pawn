---
title: 'Story 7.3: Play my message "After I''m up"'
type: 'feature'
created: '2026-10-28'
status: 'done'
baseline_revision: 'c3fcc6180672c126ba87344695b880588545d949'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-7-context.md'
  - '{project-root}/.claude/skills/pps-design/SKILL.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-7-2-recordings-screen-and-choosing-a-message-per-alarm.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Story 7.2 lets an alarm carry a motivation (`None` / `Random` / `Recording(id)`) frozen into `SessionConfig`, but nothing plays it: `Random` is never resolved, `SessionEffect.PlayMotivation` (emitted only on `Completed`) is just logged, and the Success screen has no player.

**Approach:** Resolve the motivation once at the session start (`Random` → a concrete recording from the session seed; before the first unlock → `None`), so the frozen and serialized config replays the same message after a restore. When the runtime runs `PlayMotivation`, a new core port `MotivationPlayer` (Android: `MediaPlayer`, `USAGE_ALARM`, the user's own alarm-stream volume, once) plays the frozen recording, and the Success screen shows a `motivation-player` card bound to that playback.

## Boundaries & Constraints

**Always:**
- Resolution happens where the session starts (`WakeService.dispatchAlarmFired` and `onTest`), with a pure core function: `beforeFirstUnlock` → `None` (stays `None` for the whole session, also after unlock); `Random` → one of the recordings that are not `missing`, ordered oldest first (`createdAt`, then `id`), picked with `SeedDeriver.seed(sessionId, ringIndex = 0, entryIndex = <new MOTIVATION constant, distinct from PICK>, attempt = 0)`; no usable recording → `None`; `Recording(id)` and `None` unchanged. The session id is created before the config is resolved. Reading the recordings never blocks the ring: at most 500 ms, failure or timeout → `None`, logged without names or paths. Test sessions resolve and play too.
- Only `Completed` plays (the reducer already emits `PlayMotivation` only there). The runtime plays only `Recording(id)` from the Completed session's frozen config and never when `startedBeforeUnlock`; an unresolved `Random` (a session stored before this story) plays nothing.
- Playback: `USAGE_ALARM` attributes, no volume change of its own: it first restores the user's alarm volume (`AlarmVolume.restore()`, idempotent) so it never plays at the ring's volume; once, no loop; transient audio focus; refuses and stops while an alarm rings (`onRingStart` stops it, like the previews); missing row, missing or locked file, prepare or playback error → nothing plays, the player state goes null (card hidden), logged with error codes only.
- Success card (`motivation-player` per DESIGN.md): `surface-sunrise` card, `rounded.md`, 48 dp play/pause and 48 dp replay icon buttons in `text-sunrise`, a progress bar in accent; placed between the streak/headline card and "Done"; shown only while the player state belongs to the ended session and is non-null; auto-plays. TalkBack labels reuse `recordings_play` "Play message", `recordings_pause` "Pause message", `recordings_replay` "Replay message". After the message ends the card stays (progress full, play restarts it; replay always restarts from 0).
- "Done", leaving (activity `onStop`, Home) and the 60 s Success auto-close stop playback immediately. The zero-snooze celebration (Story 6.4) runs unchanged alongside. The auto-close never cuts a message that is playing: it closes once playback stops (or at once if nothing plays).
- Strings only from existing resources; `CopyRulesTest` passes. Existing Success screenshots stay unchanged (no card when no message).

**Never:**
- No "Mix into alarm" / mode control (Story 7.5); no editor or Recordings changes; no default-sound fallback after the check (the alarm has already stopped).
- No new strings, permissions, tokens, colour pairs, or file paths / recording names in logs.
- No playback for Missed, a paid snooze ending, or a Direct Boot session.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Message chosen | `Recording(a)`, check passed | Success shows the card, message plays once at the user's alarm volume | No error expected |
| Random | 3 recordings, session id S | frozen `Recording(x)`, same x for the same S and after `ProcessRestored` | none usable → `None` |
| Direct Boot | fired before first unlock, `Recording(a)` | frozen `None`; Success without card, even if unlocked later | No error expected |
| Missing file | row missing or file gone | no card, nothing plays | logged by code |
| Error mid-play | MediaPlayer error | card hides, Success otherwise unchanged | logged by code |
| Done / Home | playing | playback stops at once, screen closes | No error expected |
| Missed / paid snooze | session ends otherwise | nothing plays | No error expected |
| New ring | playing when an alarm rings | playback stops | No error expected |
| Read failure | `Random`, repository fails or > 500 ms | frozen `None`, ring unaffected | logged |

</intent-contract>

## Code Map

Prefixes: CORE = `core/src/commonMain/kotlin/com/yawnandpawn/app/core`, AA = `androidApp/src/main/kotlin/com/yawnandpawn/app/android`, CA = `composeApp/src/commonMain/kotlin/com/yawnandpawn/app/ui`.

- CORE `session/SessionEffect.kt:117` `PlayMotivation` (no args, one-shot, never replayed on restore); emitted in `session/CheckRules.kt:122-135` (`ValidLast` → Completed with `[StopSound, CancelSlot, PlayMotivation]`).
- CORE `session/SessionConfig.kt:56` `motivation: Motivation = None`; `ConfigResolver.resolve` l.101 / `resolveTest` l.176 copy it unresolved (keep; resolution is a separate step). `SessionData` (`session/SessionState.kt:141-171`): `sessionId`, `config`, `startedBeforeUnlock`. Serialized by `session/SessionJson.kt`, so a resolved `Recording(id)` survives restore.
- CORE `alarm/Motivation.kt` (`None`, `Random`, `Recording(id)`); `recording/Recording.kt:15-72` (`Recording.missing`, `RecordingRepository.observeAll()` oldest first, `get(id): Outcome<Recording?, DomainError>`); `recording/RecordingPreview.kt` (port pattern to copy); `checks/SeedDeriver.kt:21-40` (`seed(...)`, `PICK = -1` used for check picks).
- AA `wake/WakeService.kt:429-466` `dispatchAlarmFired` (resolve at :451, `locked` at :460, `ids.newId()` at :463) and `onTest` :507-537 (`locked` :520, event :524); `endTestSession` :403 (a preempted test reaches Completed; the following ring's `onRingStart` stops playback).
- AA `wake/WakeRuntime.kt:77-101` ctor (lambda pattern `onConsume`), `runEffect` :162-190 (`PlayMotivation` falls into the logged `else`), `session()` returns the state published before effects run (Completed here); `endSession()` :293 restores volume. Koin: `wake/WakeModule.kt:92-113` (runtime), `:80-84` `onRingStart` (stop `MotivationPlayer` there too).
- AA `wake/AlarmVolume.kt:21-75` (`restore()` idempotent); `sound/Playback.kt:13-18` `ALARM_AUDIO_ATTRIBUTES`; `recording/AndroidRecordingPreview.kt:35-169` (MediaPlayer pattern: `prepareAsync`, 250 ms position tick, stale-player identity, `ringing()` refusal, `RecordingFocus`); `recording/RecordingFocus.kt`; `recording/RecordingModule.kt:38-51` bindings; `data/src/androidMain/.../recording/RecordingFiles.kt` (`file(fileName)`, `isUnlocked`).
- AA `wake/WakeActivity.kt:400-429` `SuccessShown` (60 s auto-close via `end.successRemaining(SUCCESS_TIMEOUT)`), `onIntent` :508 (`DoneClicked` → `finish()`); `wake/WakeScreenEnd.kt:25-148` (per-session claims in saved state, `onStop` finishes :131).
- CA `wake/WakeContract.kt:206-236` (`SuccessKind`, `SuccessUiState`), intents ~:295; `wake/SuccessMapping.kt:22-36`; `wake/WakeEndScreens.kt:116-182` `SuccessScreen`; `wake/WakeComponents.kt:141` (Sunrise via `WakeSurface`); tokens `Sunrise.surface` (`PpsTokens.kt:158`). Strings `recordings_play/pause/replay` (strings.xml :537-539).
- Fakes: `testing/src/commonMain/.../testing/RecordingFakes.kt` (`aRecording`, `FakeRecordingRepository` with `markMissing`/`failure`, `FakeRecordingPreview`).
- Tests to extend: `core/src/commonTest/.../session/ConfigResolverTest.kt:213`, `SessionEngineTest.kt:191` (restore), `androidApp/src/test/.../wake/WakeRuntimeTest.kt:443-457` (PlayMotivation no longer just logged), `wake/WakeActivitySuccessTest.kt` + `SuccessTestSupport.kt`, `recording/AndroidRecordingPreviewTest.kt` (ShadowMediaPlayer pattern), `ui/SuccessScreenshotTest.kt` (baselines `androidApp/src/test/screenshots/wake_success_*`), `DirectBootRingTest.kt`.

## Tasks & Acceptance

**Execution:**
- CORE `recording/MotivationResolver.kt` (new) -- pure `resolve(motivation, sessionId, recordings, beforeFirstUnlock)` and `suspend freeze(config, sessionId, beforeFirstUnlock, repository, logger)` (reads only for `Random`, 500 ms timeout, failure → None), plus `playableRecordingId(session: SessionData): String?` (Recording id when not `startedBeforeUnlock`); `SeedDeriver` gets a `MOTIVATION` entry constant -- one tested place for the rules.
- CORE `recording/MotivationPlayer.kt` (new) -- port: `state: StateFlow<MotivationPlayback?>` (`sessionId`, `playing`, `positionMs`, `durationMs`), `play(sessionId, recordingId)`, `pause()`, `resume()` (restarts from 0 when ended), `replay()`, `stop()`; `testing/.../RecordingFakes.kt` `FakeMotivationPlayer`.
- `core/src/commonTest/.../recording/MotivationResolverTest.kt` -- Random determinism and spread, same id for same session, missing excluded, none usable → None, Direct Boot → None, Recording/None unchanged, read failure and timeout → None; serialized-config restore keeps the resolved id (SessionJson round trip or engine restore).
- AA `wake/WakeService.kt` -- create the session id first, `MotivationResolver.freeze` the config for real and test fires (`RecordingRepository` from Koin, resolved before the dispatch suspends like `prices`).
- AA `wake/WakeRuntime.kt` + `wake/WakeModule.kt` -- `onPlayMotivation: (SessionData) -> Unit` lambda; `PlayMotivation` reads the Completed session and calls `MotivationPlayer.play` when `playableRecordingId` is non-null (still logged as an effect); `onRingStart` also stops the `MotivationPlayer`; update `WakeRuntimeTest`.
- AA `recording/AndroidMotivationPlayer.kt` (new) + `RecordingModule.kt` -- MediaPlayer implementation per Boundaries (repository row → `RecordingFiles.file`, `ALARM_AUDIO_ATTRIBUTES`, `AlarmVolume.restore()` before start, transient focus, no loop, 250 ms position tick, stale-player guard, errors → state null, logs codes only); `androidApp/src/test/.../recording/AndroidMotivationPlayerTest.kt` (plays with alarm usage, no loop, missing row/file → null, prepare/mid error → null, refused while ringing, stop/pause/replay, ring start stops it).
- CA `wake/WakeContract.kt`, `SuccessMapping.kt`, `WakeEndScreens.kt` -- `SuccessUiState.motivation: MotivationPlayerUi? = null` (`playing`, `progress` 0..1); intents `MotivationPlayPauseClicked`, `MotivationReplayClicked`; `MotivationPlayerCard` composable per Boundaries; semantics test for labels and 48 dp targets.
- AA `wake/WakeActivity.kt` -- collect `MotivationPlayer.state` for the shown session into the Success state; route the two intents; Done and `onStop` stop playback; the auto-close waits while it plays; `WakeActivitySuccessTest` cases (card shown and auto-played on Completed, Done stops, onStop stops, missing hides, Direct Boot nothing, auto-close waits).
- `androidApp/src/test/.../ui/SuccessScreenshotTest.kt` -- new baselines with the card: zero-snooze (OnTime streak) and after-snooze, Sunrise 100% and 200% font; existing baselines unchanged.
- Spec -- pps-design Done checklist ticked; owner-review list (Success with the player was never previewed).

**Acceptance Criteria:**
- Given a test or real session with `Recording(a)` ending Completed, when Success shows, then the card is visible and `MotivationPlayer.play(sessionId, a)` was called once.
- Given a session with `Random` and a restore (`ProcessRestored`), when it completes, then the same recording id plays as the one frozen at the fire.
- Given a session that started before the first unlock, when it completes after an unlock, then nothing plays and no card shows.
- Given the message is playing, when "Done" is tapped or the activity stops, then playback stops immediately.
- `./gradlew qualityGate` passes.

## Design Notes

Resolution lives outside the pure reducer because it needs the recordings list; doing it at the two dispatch sites, with the session id known, keeps the reducer pure and makes the frozen config the single source of truth (the restore test then holds by construction). Playing from the runtime effect (the AD-2 one-shot) rather than from the Success screen keeps "only Completed plays" in the engine; the screen only shows and controls the playback state, filtered by session id. The player restores the user's alarm volume itself because `WakeService.shutdown()` restores it only after the effects ran.

## Verification

**Commands:**
- `./gradlew :core:jvmTest --tests "*Motivation*" --tests "*ConfigResolver*" --tests "*SessionEngine*"` -- expected: green.
- `./gradlew :composeApp:testAndroidHostTest --tests "*CopyRules*" --tests "*Success*"` -- expected: green.
- `./gradlew :androidApp:testDebugUnitTest --tests "*Motivation*" --tests "*WakeRuntime*" --tests "*WakeActivitySuccess*" --tests "*SuccessScreenshot*" --tests "*DirectBoot*"` (record new baselines with `-Proborazzi.test.record=true` once, then verify) -- expected: green, existing baselines unchanged.
- `./gradlew spotlessCheck detekt` -- expected: clean; full `qualityGate` runs in CI.

## UI Done Checklist (pps-design)

- [x] Only tokens from `DESIGN.md` used (no raw hex, no new radii, no new font sizes): the card is `surface-sunrise` with `rounded.md`, icons in `text-sunrise`, the bar `accent-sunrise` over `outline-subtle-sunrise` (`rounded.full`, `space-1` high), 48 dp buttons (`target-min`), `space-2` / `space-4` spacing. The replay icon is the Material Symbols Rounded "replay" drawable, added like the existing `symbol_*` ones.
- [x] Light, Dark (and Sunrise where relevant) checked with previews / screenshots: Success is a wake screen, always Sunrise; `SuccessScreenshotTest` adds `wake_success_message_streak_sunrise` and `wake_success_message_after_snooze_sunrise` at 100% and 200%; existing Success baselines unchanged.
- [x] Every colour pair used is in the `DESIGN.md` contrast table: text / surface (Sunrise) 17.85, accent / surface (Sunrise, graphic) 3.37; the track is decorative, as the countdown ring's. No new pair.
- [x] Touch targets ≥ 48 dp; wake actions ≥ 64 dp: play / pause and replay are 48 dp (checked at 200% on a 360 dp phone); "Done" stays the 72 dp action in the thumb zone with the card.
- [x] Works at 200% font scale and with TalkBack: "Play message" / "Pause message", the progress bar's range, "Replay message", read after the headline and streak and before "Done".
- [x] Reduced-motion path works: no new animation; the zero-snooze celebration runs unchanged alongside the card.
- [x] Copy matches `EXPERIENCE.md > Voice and Tone`: only existing strings (`recordings_play`, `recordings_pause`, `recordings_replay`); no new string; `CopyRulesTest` passes.
- [x] Every state row in `EXPERIENCE.md > State Patterns` for this surface is handled: playing (auto-play), paused, played to its end (full bar, play starts over), no message / failed (no card), "Done" stops and closes.
- [x] "I'm up" is the most prominent wake action: not a ringing surface; "Done" stays the largest action on Success.
- [x] Screenshot tests updated (Roborazzi `wake_success_message_*`); semantics tests in `SuccessScreenTest`.

## For owner review

- Success with the message card was never previewed: the card sits between the headline/streak card and "Done" (play / pause, a thin progress bar, replay), playing, and after it ends ("Play message", full bar). Baselines `androidApp/src/test/screenshots/wake_success_message_*`.
- The card reuses the Recordings labels "Play message" / "Pause message" / "Replay message"; a lost audio focus (a call) pauses the message rather than ending it.
- The 60 s auto-close waits for a message that is still playing; a paused message is stopped when the screen closes.

## Review Triage Log

### 2026-10-28 — Review pass
- verdicts: 15 routed findings — high 0, medium 8, low 6, false 1 (duplicates share a route; counts from the handed-over triage, the full reviewer text was not carried across the lane hand-off)
- findings:
  - `[medium]` `patch` (edge) the message could start over a phone call when audio focus is lost while it prepares — `focusLost` flag, `prepared` refuses to start; test.
  - `[medium]` `patch` (edge) a player reporting no duration made pause/resume restart from the beginning — falls back to the row's `durationMs`; test.
  - `[medium]` `patch` (edge) Success turned keep-screen-on off, so a screen timeout could cut the message — keep-screen-on while this session's message plays.
  - `[medium]` `patch` (edge) the 60 s auto-close could wait forever on a stuck "playing" state — capped at `MESSAGE_WAIT` (longest recording + 5 s); test.
  - `[medium]` `patch` (edge) leaving the screen before Success registered left the message playing — `onStop` also stops a message the player already holds; test.
  - `[medium]` `patch` (blind) on Random the recordings read ran after the settings read, delaying the ring — read in parallel (`fireConfig`, `async`), NFR-1.
  - `[medium]` `patch` (edge) a stray `CancellationException` from the recordings read could stop the alarm from ringing — treated as a failed read unless the caller itself is cancelled; test.
  - `[medium]` `patch` (verification-gap) no real-DI proof that a ring stops the message, no test alarm with Random — `MotivationWiringTest` (app Koin graph), `MotivationWakeFlowTest` test-alarm Random case.
  - `[low]` `reject` (blind) the card appears a beat after Success (layout shift) — minor; the card is placed below the headline and above "Done", nothing tappable moves under the thumb.
  - `[low]` `reject` (blind) TalkBack reads the progress bar as a raw range — minor; the play/pause and replay labels carry the meaning, revisit in the v2 copy/a11y pass.
  - `[low]` `reject` (blind) duplicated playable checks in runtime and module, fragile asserts in tests — style only, no failure named.
  - `[low]` `reject` (edge) the MotivationPlayer singleton is created on the first ring (ring-start hook lookup) — cheap object, no MediaPlayer until a message plays.
  - `[low]` `reject` (edge) a prepare that never answers keeps transient focus — MediaPlayer reports a failed prepare via onError; the next play, stop, Done or ring releases it.
  - `[low]` `reject` (edge) a test alarm's config is lost if the service is killed during the 500 ms read — same as before this story for every pending test config; the test simply does not ring.
  - `[false]` `reject` (edge) Random can pick a recording whose file is missing — `pick` filters `missing` rows before choosing; covered by `MotivationResolverTest`.

## Auto Run Result

Status: done

**Summary:** A session freezes its motivation where it starts (`MotivationResolver.freeze` in `WakeService` for real and test fires, the session id created first): Random becomes one recording picked from the session seed (`SeedDeriver.MOTIVATION`), before the first unlock it is None, a slow or failing recordings read (500 ms) freezes None and never delays or stops the ring. On `Completed` the runtime's `PlayMotivation` starts `MotivationPlayer` (Android: `MediaPlayer`, `USAGE_ALARM`, the user's own alarm volume restored first, once, transient focus, never during a ring and stopped by one). Success shows a `motivation-player` card (play/pause, progress, replay) for that session; Done, leaving and the 60 s close stop it; the close waits for a playing message (at most `MESSAGE_WAIT`), and the screen stays on while it plays.

**Review:** 15 findings; 8 patched with tests (focus lost while preparing, unknown duration, keep-screen-on, auto-close cap, early leave, parallel read, stray cancellation, real-DI and test-alarm Random tests); 6 minor rejected; 1 false claim. Nothing deferred.

**Follow-up review recommended:** false (no high findings; every medium patch carries a test).

**Verification:** targeted runs green: core MotivationResolverTest 13, ConfigResolver 19, SessionEngine* 88; composeApp CopyRulesTest 16, SuccessMappingTest 12; androidApp AndroidMotivationPlayerTest 13, MotivationWiringTest 1, MotivationWakeFlowTest 4, WakeActivitySuccessTest 28, WakeRuntime* 48, SuccessScreenTest 17, SuccessScreenshotTest 20 (existing baselines unchanged), DirectBoot* 7; spotless and detekt clean. The full quality gate runs in CI on the PR.

**Residual risks:** real MediaPlayer, audio focus with a phone call, the alarm-volume restore and keep-screen-on are Robolectric-only (Story 7.12 audio item); Success with the card was never previewed (owner review list above).
