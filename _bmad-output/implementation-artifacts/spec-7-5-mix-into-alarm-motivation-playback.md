---
title: 'Story 7.5: "Mix into alarm" motivation playback'
type: 'feature'
created: '2026-10-28'
status: 'done'
baseline_revision: '863adc3b3493c036af52aff83fcf55d1acae5053'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-7-context.md'
  - '{project-root}/.claude/skills/pps-design/SKILL.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-7-3-play-my-message-after-i-m-up.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** An alarm can carry a motivation message (Stories 7.1-7.3), but it only plays on Success ("After I'm up"). The editor's "When it plays" choice exists only in the design preview (hidden in the app, never stored), so "Mix into alarm" (FR-SND-7) does nothing.

**Approach:** Store a `MotivationMode` (`AfterImUp` default / `MixIntoAlarm`) with the alarm (`app.db` v14), show the approved "When it plays" radio card in the editor, freeze the mode into `SessionConfig`, and let `AndroidAlarmPlayer` alternate the ring between the alarm sound and the frozen recording per a pure core `mixSchedule`, while "After I'm up" stays silent for Mix sessions.

## Boundaries & Constraints

**Always:**
- Editor: build to the approved `editor-motivation` preview (follow it and EXPERIENCE.md over epics.md's "segmented-control"): under the Message card, a "When it plays" `GroupCard` with two `RadioRow`s "After I'm up" / "Mix into alarm" (existing strings `editor_when_it_plays`, `editor_after_im_up`, `editor_mix_into_alarm`), shown only when the message is not None; the Motivation row value becomes "{message} · {timing}" (existing `motivationSummary`, `editor_check_chip`). Changing it is an unsaved change, saved only on Save, never a commitment-lock field; the test alarm uses the editor's value.
- Storage: `alarm.motivation_mode TEXT NOT NULL DEFAULT 'after_up'` (`mix` for Mix), migration 13→14 with exported `14.json` and a migration test that keeps existing alarms and history; unknown text reads as `AfterImUp`. Kept as is when a recording is deleted (the mode only shows with a message).
- Session: `SessionConfig.motivationMode` (default `AfterImUp`, so stored sessions and pending test configs decode), copied by `ConfigResolver.resolve` and `resolveTest`. `MotivationResolver.playableRecordingId` returns null for Mix sessions (no Success card, no "After I'm up"); a new `mixRecordingId(session)` returns the frozen `Recording(id)` only for Mix, never when `startedBeforeUnlock`.
- `mixSchedule(elapsed, soundBlock = 30.seconds, recordingDuration): MixSource` (core, pure): with cycle = soundBlock + recordingDuration and `t = elapsed mod cycle`, `t < soundBlock` → `Alarm`, else `Recording`; `recordingDuration <= 0` → always `Alarm`. `elapsed` counts from the ring's mix anchor: the ring start, and again from grace end and from resume after a call.
- Player: with a mix request (`MixRequest(sessionId, recordingId)`, passed by the runtime only when `mixRecordingId` is non-null and the ring is not a Direct Boot ring), the alarm sound starts and ramps exactly as today; the recording is read off the engine path (repository row → `RecordingFiles.file`, missing/locked → no mix) and prepared once, non-looping, `USAGE_ALARM`, at full gain on the ring's alarm-stream volume. When the schedule says `Recording` and the recording is prepared, it starts from 0 and the alarm sound's gain goes to 0 (the alarm keeps looping silently, so switching back has no gap); on the recording's completion (or when the schedule says `Alarm`), the alarm returns at full gain and the anchor restarts. No silent gap > 250 ms at either switch (the 250 ms ramp loop drives the checks).
- Grace (`mute`) silences both and pauses the recording; `unmuteTo` resumes with the alarm sound at full set volume and restarts the anchor. A call (`pause`/`resume`) pauses both; resume restarts the anchor with the alarm sound. `stop`, a new request, `switchToDefault`/`playDefault` release the recording.
- Never silent (FR-SND-5): a missing row, missing or unreadable file, prepare error, prepare not done within 5 s, or an error while playing → the alarm sound alone (back at full gain at once) for the rest of that session, also on its snooze re-rings (the player remembers the failed session id). The alarm sound's own fallback ladder is unchanged and keeps working while the recording phase holds its gain at 0. Logs carry error codes only, never a name or path.
- Direct Boot: a session started before the first unlock freezes `None` (7.3), and a Direct Boot ring (`directBootRing`) never mixes, so only the substituted default sound plays.
- Strings only from existing resources; `CopyRulesTest` passes.

**Never:**
- No new strings, tokens, colour pairs, permissions or components; no mixing on Success; no change to the "After I'm up" player beyond not starting for Mix sessions.
- No mixing for `Motivation.None`, an unresolved `Random`, the emergency/default ring, or a Direct Boot ring.
- Never volume or ramp changes of its own; never blocking calls inside the engine's Mutex.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Mix ring | Mix, `Recording(a)` 8 s | 0-30 s alarm (ramp), 30-38 s message, 38-68 s alarm, then message … | No error expected |
| Grace | "I'm up" during message | everything muted; grace ends → alarm at full set volume, message again 30 s later | No error expected |
| Snooze re-ring | Mix session snoozed | new ring: alarm 30 s first, then message | No error expected |
| Missing / locked file | row missing or file gone | alarm sound only, whole session | logged by code |
| Error mid-message | recording fails while playing | alarm back at full at once, alarm only for the rest of the session | logged by code |
| Direct Boot | fired before first unlock | default sound only, no message | No error expected |
| After I'm up | mode AfterImUp | ring unchanged; Success card as 7.3 | No error expected |
| Mix + Completed | session passes the check | no Success card, nothing plays after | No error expected |
| Old data | stored alarm/session without mode | `AfterImUp` | No error expected |

</intent-contract>

## Code Map

Prefixes: CORE = `core/src/commonMain/kotlin/com/yawnandpawn/app/core`, AA = `androidApp/src/main/kotlin/com/yawnandpawn/app/android`, CA = `composeApp/src/commonMain/kotlin/com/yawnandpawn/app/ui`, DATA = `data/src/commonMain/kotlin/com/yawnandpawn/app/data`.

- CORE `alarm/Motivation.kt` (sealed `None`/`Random`/`Recording`, `encode`/`parse`, `MotivationSerializer`); `alarm/Alarm.kt:24-47` (`motivation` :44; `hasSameSettingsAs` :113-126 compares motivation, add the mode); `alarm/AlarmUseCases.kt:43-64` `AlarmDraft.motivation` :61, `toAlarm` :343-367; `alarm/RampGain.kt:17` (`RAMP_DURATION = 30.seconds`).
- CORE `session/SessionConfig.kt:37-57` (`motivation` last, defaults let old rows decode); `session/ConfigResolver.kt` `resolveLive` :158, `resolveTest` :206; `session/SessionJson.kt:29-34` (`ignoreUnknownKeys`, `encodeDefaults`); `session/SessionRuntime.kt:82` `soundOf` + `DirectBoot.kt:47` (Direct Boot substitution), `SessionState.kt:119` `directBootRing`; grace: `RingRules.kt:70` (`Mute`), :125 (`UnmuteToVolume`).
- CORE `recording/MotivationResolver.kt:90` `playableRecordingId`; `time/TimePorts.kt:16` `MonotonicClock`.
- DATA `db/AppDatabase.kt` (KDoc :26-41, `SCHEMA_VERSION = 13` :86), `db/AppDatabaseMigrations.kt` (`MIGRATION_11_12` :189-198 pattern, list :235-250), `alarm/AlarmEntity.kt:59-67`, `alarm/AlarmMapping.kt:31-34`/`:56-62` (`checkMode` unknown-text fallback pattern); schemas `data/schemas/com.yawnandpawn.app.data.db.AppDatabase/`.
- AA `wake/AndroidAlarmPlayer.kt` (ctor :44, `play` :127 with `Request` data class, `mute` :166, `unmuteTo` :176, `stop` :200, `startRingLocked` :216, open/fallback :244-329, `applyGainLocked` :359, ramp loop `RAMP_STEP = 250ms`, `fallbackAfter` :408; one `lock`, callbacks on main thread, scope for timers); `wake/Playback.kt` (`Playback` :68, `PlaybackFactory` :80, `MediaPlayerPlaybackFactory` :99 hard-codes looping, `ALARM_AUDIO_ATTRIBUTES`).
- AA `wake/WakeRuntime.kt` (`playMotivation` :207, `playSound`/`playFor` :527/:540 call `player.play(...)`, `ringConfig` :647, `restoredRing`); `wake/WakeModule.kt:83-132` (player construction, `onRingStart`, `playMotivation`); `recording/RecordingModule.kt:58`; `data/src/androidMain/.../recording/RecordingFiles.kt:31` (`file(fileName)`, may throw before unlock); `recording/AndroidMotivationPlayer.kt` `read`/`fileOf` (:182/:304) for the row-and-file pattern.
- CA `editor/EditorSubScreens.kt:315-364` `MotivationPane` (the "When it plays" card at :349-363 already exists); `editor/AlarmEditorContract.kt` (`MotivationTiming` :203, `FullEditorSections.motivationTiming` :227, `motivationTimingShown` :229, `EditorIntent.MotivationTimingSelected` :359, `EditorForm.motivation` :50); `editor/EditorMotivation.kt` (`toCore`/`toChoice`/`withMotivation` :51-79); `editor/AlarmEditorViewModel.kt` (`onMotivationIntent` :427-458, no-op at :449, `wakeCheckOnly` :1027-1038 sets `motivationTimingShown = false`, `toDraft` :995-1020, `toForm` :1090-1107); `editor/AlarmEditorScreen.kt:444-469` `motivationSummary`.
- Tests: `androidApp/src/test/.../wake/AndroidAlarmPlayerTest.kt` (`FakePlaybackFactory`, `FakeMonotonicClock`, `advance(d)` :73), `wake/WakeTestDoubles.kt`, `wake/WakeRuntimeTest.kt` (grace :179), `WakeRuntimeMotivationTest`, `MotivationWakeFlowTest`, `DirectBootRingTest:155`; `data/src/androidHostTest/.../db/AppDatabaseSkipTest.kt` (schema + migration pattern), `AppDatabaseFactoryTest.kt:564-571` (migration list and version); `composeApp/src/commonTest/.../editor/AlarmEditorMotivationTest.kt:99` (asserts hidden — flip); `androidApp/src/test/.../ui/RecordingsScreenshotTest.kt:177-233` (asserts no "When it plays" :181, :223 — change); `core/src/commonTest/.../session/ConfigResolverTest.kt:213-244`; `BackupRulesTest` reads `${SCHEMA_VERSION}.json`.

## Tasks & Acceptance

**Execution:**
- CORE `alarm/MotivationMode.kt` (new) -- `enum MotivationMode { AfterImUp, MixIntoAlarm }` with stable text (`after_up`/`mix`) and `parse` (unknown → `AfterImUp`); `Alarm.motivationMode`, `AlarmDraft.motivationMode`, `hasSameSettingsAs`, `toAlarm`; `SessionConfig.motivationMode` + `ConfigResolver` copies -- one stored, frozen value.
- CORE `recording/MixSchedule.kt` (new) -- `MixSource { Alarm, Recording }`, pure `mixSchedule`; `MotivationResolver.playableRecordingId` excludes Mix, new `mixRecordingId`; tests `MixScheduleTest` (0 s, 29 s, 30 s, 30 s + duration, second cycle, zero duration, re-anchored after grace) and `MotivationResolverTest`/`ConfigResolverTest` additions (mode frozen for real and test rings, old JSON without the key → `AfterImUp`).
- DATA `AlarmEntity`, `AlarmMapping`, `AppDatabaseMigrations` (`MIGRATION_13_14`), `AppDatabase` (v14, KDoc), exported `14.json` -- plus `AppDatabaseMotivationModeTest` (schema 14 + v13→v14 migration keeping alarms/history, unknown text) and `AppDatabaseFactoryTest` list/version.
- AA `wake/Playback.kt` -- a non-looping recording playback seam (`RecordingPlayback`: `start` from 0, `pause`, `setGain`, `release`, `durationMs`; factory `open(file, onPrepared, onCompleted, onError)`, `MediaPlayer` with `ALARM_AUDIO_ATTRIBUTES`); fake in `WakeTestDoubles.kt`.
- AA `wake/AndroidAlarmPlayer.kt` -- `play(..., mix: MixRequest? = null)` (part of `Request`), mix phase state, recording source lambda `suspend (recordingId) -> File?` launched on `scope`, switching in the ramp loop per `mixSchedule`, completion/error handling, grace/call/stop rules, failed-session memory, 5 s prepare watchdog -- per Boundaries.
- AA `wake/WakeRuntime.kt`, `wake/WakeModule.kt` -- pass `MixRequest` from `mixRecordingId` unless `directBootRing`; recording source wired from `RecordingRepository` + `RecordingFiles` (codes-only logs); `playMotivation` keeps using `playableRecordingId` (now null for Mix).
- AA tests -- `AndroidAlarmPlayerMixTest` (alternation order with timings, ramp unchanged in the first 30 s, mute in grace, resume after grace at full gain with the alarm first, call pause/resume, recording error mid-ring → alarm only, also on the next ring of that session, missing file, prepare timeout, stop releases); `WakeRuntimeTest`/`WakeRuntimeMotivationTest` (mix passed only for Mix + Recording, never on a Direct Boot ring; Mix completion plays nothing on Success); `DirectBootRingTest` Mix case.
- CA editor -- `EditorForm.motivationTiming`, `toDraft`/`toForm`/`withMotivation` mapping to `MotivationMode`, `MotivationTimingSelected` edits the form, `wakeCheckOnly` shows the card; `AlarmEditorMotivationTest` (shown with a message, hidden with None, unsaved change, saved and reopened, test ring carries it, row summary).
- `androidApp/src/test/.../ui/RecordingsScreenshotTest.kt` -- update asserts; re-record `editor_motivation_app_*` baselines (Light, Dark, 200%) and add one with "Mix into alarm" selected; preview baselines unchanged.
- Spec -- pps-design Done checklist ticked; owner-review list.

**Acceptance Criteria:**
- Given the editor with a message chosen, when the Motivation sub-screen shows, then "When it plays" offers "After I'm up" (default) and "Mix into alarm", and the choice is saved with the alarm and survives reopening.
- Given a Mix ring with a playable recording, when it rings, then the alarm sound plays the first 30 s, then the recording once, then 30 s of alarm, repeating, with no silent gap over 250 ms.
- Given a Mix session that completes, when Success shows, then no message card shows and nothing plays.
- `./gradlew qualityGate` passes.

## Spec Change Log

## Review Triage Log

### 2026-10-28 — Review pass
- verdicts: 24 findings — high 0, medium 9, low 14, false 1, maybe-false 0
- findings:
  - `[medium]` `patch` (verification-gap) the production prepare path (prepared after `open` returned, `onMixPrepared`) was never run: the fake prepared inside `open` — fake `deferPrepare` mode; test: prepared at 2 s, survives the 5 s watchdog, message at 30 s.
  - `[medium]` `patch` (verification-gap) `MixRecordingSource` and the Koin wiring of the mix never ran with a working recording — `MixRecordingSourceTest` (readable, no row, row missing, absent file, Failure, throws; logs without name or path) and a real-graph `MotivationWakeFlowTest` Mix case (recording opened on its file, prepared, `isMixing`).
  - `[low]` `reject` (verification-gap, other) a recording with no reported length fails the mix while `AndroidMotivationPlayer` falls back to the row length — our own recorder writes m4a with a length; the fallback would add a parameter; listed for the owner.
  - `[medium]` `patch` (blind) the schedule paused the message exactly `durationMs` after `start()`, cutting its tail on a real player — completion ends the turn first; the schedule's slot is the length + 1 s (`MIX_SLACK`).
  - `[low]` `reject` (blind) leading silence in a take / start latency can sound like a gap — the 250 ms rule is about switching between sources (same step); what the phone sounds like is the Story 7.12 audio check.
  - `[low]` `patch` (blind) nothing bounded the time the alarm sits at gain 0 if a header reports a huge length — length capped at `Recording.MAX_DURATION_MS` (+1 s slack); test with a 10-min header.
  - `[low]` `reject` (blind) the hidden mode counts in `hasSameSettingsAs` and unsaved changes when the message is None — rare (pick Mix, then None; or a deleted recording), harmless (a duplicate alarm or a discard prompt), and the fix adds branches in two places.
  - `[medium]` `patch` (blind) `MixRecordingSource` untested — same root as the verification-gap row; `MixRecordingSourceTest`.
  - `[low]` `patch` (blind) `MixScheduleTest` "re-anchored after grace" tests nothing about grace — renamed to what it checks.
  - `[low]` `reject` (blind) the cycle maths is mostly unused and the block is named twice — both sides use the one `MIX_SOUND_BLOCK` constant (the default argument); no caller diverges.
  - `[low]` `reject` (blind) `MIX_SOUND_BLOCK` and `RAMP_DURATION` linked only by KDoc — both 30 s today; no defect, and a ramp change would be its own story.
  - `[low]` `reject` (blind) the failed-session memory is in-memory and `switchToDefault` does not mark it — after a process restart the recording is tried again and, if it fails, the alarm plays alone again: never silent.
  - `[low]` `reject` (blind) the recording is opened under the player lock — local-file `setDataSource` and `prepareAsync` take milliseconds; moving it adds a second hand-off.
  - `[low]` `reject` (blind) failure logs are short sentences, not codes — same convention as `AndroidMotivationPlayer` (7.3); they carry no name or path, which is the privacy rule.
  - `[low]` `patch` (blind) the cannot-open adapter test did not check the release — asserts the created MediaPlayer reached END.
  - `[medium]` `patch` (blind) Mix not tested through the runtime's grace effects or a heartbeat — `WakeRuntimeMotivationTest`: Mute + Grace pause the message, UnmuteToVolume + Loud bring the alarm first, message 30 s later without reopen; a repeated SoundAt keeps the same ring and recording.
  - `[medium]` `patch` (edge) recording `start`/`seekTo` on a MediaPlayer already in its Error state throws inside the ramp loop with the alarm at gain 0 — every recording call guarded (`recordingCall`); a throw fails the mix (alarm alone at full gain); `playing` set only after `start` succeeded; tests.
  - `[medium]` `patch` (edge) recording `pause` throwing during grace or a call skipped the mute/pause — same root and fix; tests for both.
  - `[medium]` `patch` (edge) the schedule cuts the message's tail — same root as the blind row; same fix.
  - `[low]` `reject` (edge) persist the failed session across process death — same as the blind row.
  - `[low]` `reject` (edge) `Alarm.hasSameSettingsAs` with None and a hidden mode — same as the blind row.
  - `[low]` `reject` (edge) file open under the player lock on the engine path — same as the blind row.
  - `[false]` `reject` (intent) the failure fallback should be the default sound (PRD FR-SND-5) — the intent says "the normal alarm sound still plays" and epics.md "plays only the alarm sound"; the alarm's chosen sound keeps its own never-silent ladder down to the default.
  - `[medium]` `patch` (intent) grace and the gap rule were exercised only on the player, not through the engine's effects — same root as the runtime row above.

## Design Notes

The alarm sound never stops while mixing; its gain goes to 0 during the message. That keeps the existing never-silent ladder, focus and wake lock in charge, makes the switch back instant, and means a failing recording only has to restore one gain. The recording row is read on the player's scope with 30 s of slack before it is first needed, so the engine's Mutex is never held on I/O. The epics' "segmented-control" is superseded by the owner-approved preview's radio card, which already exists in code.

## Verification

**Commands:**
- `./gradlew :core:jvmTest --tests "*Mix*" --tests "*Motivation*" --tests "*ConfigResolver*" --tests "*SessionJson*"` -- expected: green.
- `./gradlew :data:testAndroidHostTest --tests "*AppDatabase*"` -- expected: green (task name per the module; `14.json` exported).
- `./gradlew :composeApp:testAndroidHostTest --tests "*CopyRules*" --tests "*AlarmEditor*"` -- expected: green.
- `./gradlew :androidApp:testDebugUnitTest --tests "*AlarmPlayer*" --tests "*WakeRuntime*" --tests "*Motivation*" --tests "*DirectBoot*" --tests "*RecordingsScreenshot*" --tests "*BackupRules*" --tests "*PpsBackupAgent*"` (record baselines once with `-Proborazzi.test.record=true`, then verify) -- expected: green.
- `./gradlew spotlessCheck detekt` -- expected: clean; full `qualityGate` in CI.

## UI Done Checklist (pps-design)

- [x] Only tokens from `DESIGN.md` used: no new UI code; the existing `MotivationPane` "When it plays" `GroupCard` with two `RadioRow`s (tokens as in the approved preview) is now shown by the app.
- [x] Light, Dark checked with screenshots: `editor_motivation_app_random_*` and `_missing_*` re-recorded (they now show the card), new `editor_motivation_app_mix_light`, `_mix_dark`, `_mix_light_font200`; preview baselines unchanged.
- [x] Every colour pair used is in the `DESIGN.md` contrast table: only the existing radio rows on `glass`, no new pair.
- [x] Touch targets ≥ 48 dp: both "When it plays" radio rows checked at 200% on a 360 dp phone (`RecordingsScreenshotTest`).
- [x] Works at 200% font scale and with TalkBack: `_font200` baselines; the rows are radio buttons (role `RadioButton`, selected state) read with their labels.
- [x] Reduced-motion path works: no new animation.
- [x] Copy matches `EXPERIENCE.md > Voice and Tone` (existing strings only): `editor_when_it_plays`, `editor_after_im_up`, `editor_mix_into_alarm`, `editor_check_chip`; `CopyRulesTest` green.
- [x] Every state row for this surface is handled (no message: card hidden; message: card with the stored choice; the mode is kept when the message is deleted and shows again with a message).
- [x] Screenshot tests updated (Roborazzi `editor_motivation_app_*`), semantics in `RecordingsScreenshotTest` (selected rows, intent sent, card hidden with None, main row "Message 1 · Mix into alarm").

## For owner review

- The "When it plays" card is the approved preview; the app state with "Mix into alarm" selected and the row value "Message 1 · Mix into alarm" were never previewed on their own (baselines `editor_motivation_app_*`).
- How Mix sounds (30 s alarm, message, repeat; the message at the alarm-stream volume) needs the phone (Story 7.12).
- A recording whose length the player cannot report is treated as failed (alarm sound alone for the session); a message that becomes ready late in its slot is played whole (the schedule is re-anchored at its start).
- The 250 ms "no gap" rule is met in the control logic (the alarm drops to 0 in the same step the message starts, and returns at once on its completion); what it sounds like (start latency, a take's leading silence) is a Story 7.12 phone check.

## Auto Run Result

Status: done

**Summary:** Alarms store a `MotivationMode` (`after_up` default / `mix`, `app.db` v14) chosen in the editor's approved "When it plays" card (shown with a message; row value "Message 1 · Mix into alarm"), frozen into `SessionConfig`. A Mix ring alternates per the pure `mixSchedule`: 30 s of alarm sound (ramp unchanged), the recording once at full gain on the ring's alarm-stream volume while the alarm loops at gain 0, then 30 s of alarm, repeating. Grace and calls pause the message and restart with the alarm first at full volume; snooze re-rings start over. Any recording failure leaves the alarm sound alone for the rest of the session; Direct Boot never mixes; "After I'm up" stays silent for Mix sessions.

**Files:** core `MotivationMode`, `MixSchedule`/`MixSource`, `MotivationResolver` (`mixRecordingId`), `Alarm`/`AlarmUseCases`/`SessionConfig`; data `AlarmEntity`, `AlarmMapping`, `AppDatabase` v14, `MIGRATION_13_14`, `14.json`; androidApp `AndroidAlarmPlayer` (mix phase, guarded recording calls), `Playback` (`RecordingPlayback` seam + MediaPlayer adapter), `MixRequest`, `MixRecordingSource`, `WakeRuntime` (`mixRequest`), `WakeModule` (`alarmPlayer()`); composeApp editor (`motivationTiming` on the form, card shown); tests across core/data/composeApp/androidApp; 6 re-recorded and 3 new `editor_motivation_app_*` baselines.

**Review:** 24 findings; 9 medium + 4 low patched as 7 fixes (message tail and slot slack, duration cap, guarded recording calls, deferred-prepare test, `MixRecordingSource` + real-graph wiring tests, runtime grace/heartbeat tests, test rename and adapter release assert); 11 rejected (no-length fallback, gap sound, hidden-mode equality x2, unused cycle maths, block/ramp link, in-memory failed session x2, open under lock x2, log wording) plus 1 false claim (fallback sound). Nothing deferred.

**Follow-up review recommended:** true. Two or more medium entries were patched on a first pass, and the guarded-call and slot-slack logic added to `AndroidAlarmPlayer` by the patches has had no review of its own (patched: medium 9, low 4).

**Verification:** every Verification command green after the patches: core MixSchedule 6, MotivationResolver 15, ConfigResolver 16, SessionJson 15; data AppDatabase* incl. AppDatabaseMotivationModeTest 3 and AppDatabaseFactoryTest 30; composeApp AlarmEditor* incl. AlarmEditorMotivationTest 13, CopyRulesTest 17; androidApp AndroidAlarmPlayerMixTest 22, AndroidAlarmPlayerTest 35, MixRecordingSourceTest 3, WakeRuntimeMotivationTest 7, WakeRuntimeTest 43, MotivationWakeFlowTest 5, DirectBootRingTest 5, RecordingsScreenshotTest 22, BackupRules*, PpsBackupAgentTest 15; spotless and detekt clean. Full quality gate in CI.

**Residual risks:** real MediaPlayer timing at the switches, the message's loudness at alarm volume and calls are Robolectric-only (Story 7.12); `app.db` v14 must be renumbered if the other lane's 7.4 merges a migration first; the Mix-selected editor state was never previewed (owner review).
