---
title: 'Story 7.2: Recordings screen and choosing a message per alarm'
type: 'feature'
created: '2026-10-28'
status: 'done'
baseline_revision: '9bb9bbb67c079f77b5b1d60f76fd3210b35f99fe'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-7-context.md'
  - '{project-root}/.claude/skills/pps-design/SKILL.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-7-1-record-and-store-motivation-messages.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Story 7.1 can record and store messages, but nothing in the app lets the user record, play, re-record or delete one, or choose a message for an alarm; the editor hides its Motivation row and the Recordings UI exists only in the debug design preview.

**Approach:** Wire the approved round-3 design (editor Motivation sub-screen with radio rows, "Record a message" to the Recordings screen) to the 7.1 domain: an alarm `motivation` field (`None` | `Recording(id)` | `Random`) stored in `app.db` (next free version, v12 today) and copied into `SessionConfig`; a Recordings pane inside the editor driven by a state holder over `AudioRecorder`, the recording use cases and a new media-usage preview player; the microphone permission asked from the record button only; deleting a message clears it from the alarms that use it; the reliability checklist shows the Microphone row once the user has tapped record.

## Boundaries & Constraints

**Always:**
- Build to the approved preview (`docs/design-preview/`, `PreviewRound3.kt` recordings states, `editor_motivation`): selection lives in the editor Motivation pane as radio rows None / Random / each recording, then a "Record a message" row that opens Recordings; Recordings manages messages (recorder card, take actions, "Your messages" list). Follow `pps-design` rules and tokens; no new layouts.
- "Random" is offered only when there are at least two recordings (a stored `Random` with fewer stays selected and readable as "Random").
- Strings come from Compose resources and match EXPERIENCE.md key strings verbatim (they already exist: `recordings_*`, `editor_message*`, `editor_motivation`, `sound_file_missing`, `home_fix`, `settings_delete_*`). Any new string is marked `(EXPERIENCE.md Key strings)` in the story and listed for the owner; `CopyRulesTest` passes.
- `motivation` is saved only on "Save" like every editor field, is part of "unsaved changes", is never a commitment-lock weakening change (not a `LockedField`), and `ConfigResolver` copies it into `SessionConfig` (live and test rings; the new `SessionConfig` field has a default so stored sessions still decode). `Random` stays unresolved here (Story 7.3 resolves it).
- `RECORD_AUDIO` is requested only by the first record tap on the Recordings screen (never at app start or onboarding), through a `MicrophonePermission` port copied from `CameraPermission` (granted / denied / blocked, `openSettings()`); recording starts only after it is granted. Denied (once or blocked): the banner "Microphone is off. Turn it on in Settings." with "Fix" opening the app's settings page, the record button disabled, state re-checked on resume; playback, the list and alarms keep working.
- The first record tap is remembered in `ReliabilityRecords`; the checklist's Microphone row is shown only after it, with its status from the permission.
- Recorder: tap record to start, tap again to stop, auto-stop at 60 s and on the 7.1 auto-stops; then Play, "Re-record", "Delete", "Save"; "Save" adds the message to the list; a take under 1 s shows the snackbar "Too short. Try again."; the record button's TalkBack label is "Start recording" / "Stop recording" and the elapsed time is announced every 10 s while recording.
- List rows are at least 56 dp: name, duration in tabular figures, a 48 dp play/stop preview button; a recording whose file is missing shows "File missing. Default sound will play." and its preview is disabled (also as a radio row in the Motivation pane).
- Preview playback (takes and saved messages) uses media usage, one at a time, stops when another starts, when the pane is left, on `ON_STOP`, and never plays while an alarm rings; it logs no paths.
- Delete: `dialog-confirm` "Delete this message? Alarms using it will play no message." with "Delete" (error colour) and "Keep it" as the default dismiss; confirming deletes file and row and sets every alarm whose motivation is that recording to `None` (alarms on `Random` keep `Random`), refused during a session (session lock); an editor open on such an alarm switches its form choice to `None`.
- Recordings and its pane are unreachable during an active session (existing session lock swaps the stack).

**Never:**
- No "When it plays" / "Mix into alarm" control (Story 7.5) and no playback on Success (Story 7.3); the Motivation row value is the message name, "Random" or "None".
- No Motivation row in the onboarding first-alarm step.
- No storage permission; no new allowlist entry; no recording names or paths in logs.
- No change to `LockedField` / `CommitmentRules`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| First record tap, grant | permission never asked | system dialog, then recording starts; Microphone row now shown in checklist | No error expected |
| First record tap, deny | user denies | banner + "Fix", record disabled; nothing recorded | Fix opens app settings; re-check on resume |
| Stop after 9 s, Save | recording | take actions; Save adds "Message n" to the list | No error expected |
| Stop at 0.6 s | recording | snackbar "Too short. Try again.", recorder idle | take deleted |
| 60 s / background / screen off | recording | take offered as after a manual stop | as 7.1 |
| Choose a message | Motivation pane | radio selected; main row shows its name; saved only on Save | Discard prompt on Back with changes |
| Random offered | 1 vs 2+ recordings | hidden with 1, shown with 2+ | No error expected |
| Delete used message | alarm A uses it, alarm B Random | file and row gone; A → None; B stays Random | SessionActive refused, nothing changes |
| Missing file | restored row | "File missing. Default sound will play.", preview disabled | No error expected |
| Old database | v11 alarm rows | migrate to v12 with motivation None | No error expected |

</intent-contract>

## Code Map

Prefixes: CA = `composeApp/src/commonMain/kotlin/com/yawnandpawn/app/ui`, CORE = `core/src/commonMain/kotlin/com/yawnandpawn/app/core`.

- `CA/recordings/Recordings.kt` -- approved screen: `RecorderState` (l.72), `RecordedMessage` keyed by `number` (l.89, change to id + name + missing), `RecordingsUiState` (l.98), `RecordingsIntent` (l.115), `RecordingsScreen` (l.154) on `SubScreen`; has banner, snackbar, delete dialog, 72 dp record button; lacks missing-file row and 10 s announcement.
- Debug preview users to keep compiling and matching: `androidApp/src/debug/.../preview/PreviewRound3.kt:113`, `PreviewSetupSamples.kt:196-217`, `PreviewRound3Reducer.kt:148-183`; baselines `androidApp/src/test/screenshots/preview/recordings_*`, `editor_motivation_*` (re-record if the layout changes).
- `CA/editor/AlarmEditorContract.kt` -- `EditorPane` (l.71, add a Recordings pane at depth 2), `MotivationChoice` (l.175, carries a name, change to id-based), `FullEditorSections` (l.189: `recordings`, `motivation`, `rows`), intents `MotivationChosen` (l.326), `RecordMessageClicked` (l.335); `EditorForm` (l.26) lacks motivation.
- `CA/editor/EditorSubScreens.kt:311` -- `MotivationPane` (radio rows, "Record a message", "When it plays" card: hide the timing card in this story).
- `CA/editor/AlarmEditorScreen.kt` -- Motivation row l.404-411 shown when `EditorPane.Motivation in full.rows`; `motivationSummary()` l.429; `EditorPaneContent` l.471-505 (pattern: `ScanCode` → `QrRegistrationRoute(onCodeChosen = { onIntent(...) })`); `resumed` counter l.140.
- `CA/editor/AlarmEditorViewModel.kt` -- ctor l.104-146; `wakeCheckOnly()` l.921 hides Motivation (comment l.919); intents fall to no-op l.332; `load()` l.569 / `Alarm.toForm()` l.972; `save()` l.676 / `toDraft()` l.892; `hasUnsavedChanges` l.791; `requestBack()` l.754; Koin `alarmEditor()` in `CA/UiModule.kt:115-140` (onboarding l.93-98).
- `CA/qr/QrRegistrationModel.kt:136` -- plain state-holder pattern (koinInject ports) to copy for a `RecordingsModel`.
- `CA/qr/CameraPermission.kt`, `androidApp/.../android/qr/AndroidCameraPermission.kt`, `androidApp/.../MainActivity.kt:69-74,95-107` -- permission port, launcher attach/detach, blocked detection, `openSettings()` (l.90).
- CORE `alarm/Alarm.kt:19-38` (+ `hasSameSettingsAs()` l.103), `alarm/AlarmUseCases.kt:43-60` (`AlarmDraft`), `toAlarm` l.320, `storeEdit` l.189/222; `config/PendingChange.kt:20` (`LockedField`, leave unchanged).
- CORE `session/SessionConfig.kt:35-53` (`@Serializable`), `ConfigResolver.resolveLive` l.127-154, `resolveTest` l.177-200.
- CORE `recording/*` (7.1 domain) and `RecordingUseCases.kt:131` `DeleteRecording` (extend to clear alarms, under `AlarmWriteLock` and `SessionLockGuard.whenIdle`); `data/src/androidHostTest/.../SessionLockGuardScanTest.kt:40-65` lists writers.
- CORE `reliability/ReliabilityChecklist.kt` (`ChecklistRow.Microphone` l.31, hidden at l.163; Camera pattern l.162; `StoredReliability` ~l.92; `ReliabilityRecords` l.109), `reliability/Reliability.kt:16-25` (`ReliabilityStatus`), `androidApp/.../reliability/AndroidReliability.kt:77`, `data/src/commonMain/.../settings/DataStoreReliabilityRecords.kt` keys l.69-78; UI `CA/reliability/ReliabilityScreen.kt:88`, `ReliabilityViewModel.kt:187`.
- `data/.../alarm/AlarmEntity.kt`, `AlarmMapping.kt` (safe parse like `checkMode` l.41), `AlarmDao` (add bulk clear query), `db/AppDatabase.kt` (SCHEMA_VERSION 11), `db/AppDatabaseMigrations.kt` (`MIGRATION_4_5` ADD COLUMN pattern), `data/schemas/...`, `AppDatabaseFactoryTest.kt` (v11 tests l.491-565).
- `CORE/sound/SoundPorts.kt:24` / `androidApp/.../sound/AndroidSoundPreview.kt` -- alarm-usage preview, not reusable; new port for media-usage recording preview; `data/src/androidMain/.../recording/RecordingFiles.kt:31` gives files (take files too).
- `androidApp/.../android/recording/RecordingModule.kt` -- recorder and use-case bindings; add preview player and permission.
- Tests: ViewModel tests `composeApp/src/commonTest/.../ui/**` (e.g. `you/YouViewModelTest.kt`, editor tests split per feature `AlarmEditorSoundTest.kt`); fakes `testing/.../RecordingFakes.kt`; screenshots `androidApp/src/test/kotlin/com/yawnandpawn/app/ui/SoundPickerScreenshotTest.kt` template, baselines `androidApp/src/test/screenshots/`; `composeApp/src/androidHostTest/.../ui/CopyRulesTest.kt`.

## Tasks & Acceptance

**Execution:**
- CORE `alarm/Motivation.kt` (new) + `Alarm.kt`, `AlarmUseCases.kt` -- `Motivation` sealed type `None | Random | Recording(id)` with a stable text encoding (`none`, `random`, `recording:{id}`, unknown → None); field on `Alarm` and `AlarmDraft` (default None), carried by save/edit and `hasSameSettingsAs`.
- `data` alarm entity, mapping, `AlarmDao`, `MIGRATION_11_12`, `12.json`, `SCHEMA_VERSION`, `AppDatabaseFactoryTest` -- `alarm.motivation TEXT NOT NULL DEFAULT 'none'`; a query that sets `none` where motivation is `recording:{id}`.
- CORE `session/SessionConfig.kt`, `ConfigResolver` -- `motivation: Motivation = None` copied live and for test rings; tests.
- CORE `recording/RecordingUseCases.kt` -- `DeleteRecording` also clears that recording from alarms (one write path, under the alarm write lock and session lock); tests incl. Random untouched and SessionActive.
- CORE reliability + data DataStore + `AndroidReliability` -- `recordTapped` record and `microphoneGranted` status; Microphone row shown only when tapped; tests.
- CORE or CA `MicrophonePermission` port + `androidApp/.../recording/AndroidMicrophonePermission.kt` + `MainActivity` launcher -- copy of the camera pattern; Koin binding; test.
- CORE `recording/RecordingPreview.kt` port (play a saved recording or a take, stop, `playing` state with position) + `androidApp/.../recording/AndroidRecordingPreview.kt` (MediaPlayer, USAGE_MEDIA / CONTENT_TYPE_SPEECH, no volume change, refuses while ringing, missing/locked file → nothing plays, logs codes only) + fake in `testing`; Robolectric test.
- CA `recordings/RecordingsModel.kt` (new) -- state holder for the pane: permission flow, recorder start/stop/auto-stop collection, level/elapsed ticks, take play/re-record/save/delete, list from `RecordingRepository.observeAll()`, preview toggles, delete dialog, snackbar; reports a saved or deleted recording back to the editor; tests with fakes.
- CA `recordings/Recordings.kt` -- id-based rows with name and missing state, missing-file note and disabled preview, 10 s elapsed announcement; update debug preview samples/reducer accordingly.
- CA editor contract, VM, screen, sub-screens -- Motivation row shown in the app editor (not onboarding); `EditorForm.motivation`; `MotivationChosen(id-based)`; Random only with 2+ recordings; "Record a message" opens the Recordings pane; timing card hidden; summary = name / "Random" / "None"; a recording deleted from the pane resets a form choice that pointed at it; a new recording saved while the choice is None becomes the choice; tests (load, choose, save, discard prompt, Random visibility, delete reset).
- `androidApp/src/test/.../ui/RecordingsScreenshotTest.kt` (+ editor Motivation states) -- empty, recording, after stop, list with a missing file, mic denied, Motivation pane with Random selected and with a missing file; Light, Dark and 200%; target-size semantics test (all ≥ 48 dp).
- Story file / spec -- copy the `pps-design` Done checklist ticked; list any new string `(EXPERIENCE.md Key strings)` and the un-previewed states (missing-file rows, Motivation with Random hidden) for owner review.

**Acceptance Criteria:**
- Given an alarm saved with a chosen message, when it is reopened, then the Motivation row shows that message, and a ring's `SessionConfig` carries `Recording(id)`.
- Given the app editor, when it renders, then the Motivation row is present; given the onboarding first-alarm step, then it is not.
- Given a wake session in progress, when anything tries to delete a recording, then `SessionActive` is returned and nothing changes.
- Given the reliability checklist, when record was never tapped, then no Microphone row; after a tap, the row reflects the permission.
- `./gradlew qualityGate` passes.

## Design Notes

The epics text describes selection inside Recordings ("selection mode with a radio"); the owner-approved round-3 preview puts selection in the Motivation pane and keeps Recordings for managing. The preview wins (EXPERIENCE.md and the epic context say so). Recordings is an editor pane (depth 2 under Motivation), like Check setup / QR registration, so the editor ViewModel and its unsaved form survive the round trip; results flow back as editor intents.

## Verification

**Commands:**
- `./gradlew :core:jvmTest :testing:jvmTest` -- expected: green.
- `./gradlew :data:testAndroidHostTest` -- expected: green incl. v12 migration.
- `./gradlew :composeApp:testAndroidHostTest` (or the module's host-test task) -- expected: green incl. CopyRulesTest and the new model/editor tests.
- `./gradlew :androidApp:testDebugUnitTest --tests "*Recording*" --tests "*Reliability*" --tests "*Preview*"` with `-Proborazzi.test.record=true` only when baselines must be (re)recorded, then verify -- expected: green.
- `./gradlew qualityGate` -- expected: green (caller runs it).

## UI Done Checklist (pps-design)

- [x] Only tokens from `DESIGN.md` used (no raw hex, no new radii, no new font sizes): the screen and the Motivation pane reuse the approved round-3 components; the disabled preview icon uses `text-secondary`, as the Sound list's.
- [x] Light, Dark (and Sunrise where relevant) checked with previews / screenshots: `RecordingsScreenshotTest` (Light, Dark, Light 200%); Recordings is not a wake screen, so no Sunrise.
- [x] Every colour pair used is in the `DESIGN.md` contrast table (no new pair).
- [x] Touch targets ≥ 48 dp; wake actions ≥ 64 dp: the semantics test checks every clickable node at 200% on a 360 dp phone, the record button is 72 dp, list rows ≥ 56 dp.
- [x] Works at 200% font scale and with TalkBack: "Start recording" / "Stop recording", "Play message" / "Pause message", "Delete {name}", the radio rows; the elapsed time is a polite live region that changes every 10 s while recording.
- [x] Reduced-motion path works: no new animation (the pane uses the existing 250 ms sub-screen slide).
- [x] Copy matches `EXPERIENCE.md > Voice and Tone`: only existing resources (`recordings_*`, `editor_message*`, `editor_motivation`, `editor_record_message`, `sound_file_missing`, `home_fix`, `settings_delete_*`); two new snackbar strings listed below for the owner; `CopyRulesTest` passes.
- [x] Every state row in `EXPERIENCE.md > State Patterns` for this surface is handled: no recordings, recording, a take, saved messages, microphone denied, too short, delete dialog, missing file.
- [x] "I'm up" is the most prominent wake action: not a wake surface (no change).
- [x] Compose previews for each state exist (debug design preview `recordings_*`, `editor_motivation_*`, unchanged baselines) and screenshot tests are added (`recordings_app_*`, `editor_motivation_app_*`).

## For owner review

- New strings `(EXPERIENCE.md Key strings)` (drafts, snackbars on Recordings): "Couldn't start recording. Try again." (`recordings_start_failed`) and "Couldn't delete this message. Try again." (`recordings_delete_failed`); a take that cannot be stored reuses "Couldn't save. Try again." (`onboarding_save_failed`).
- States not in the approved preview: a message whose file is missing ("File missing. Default sound will play." under its name, preview disabled) in Recordings and as a Motivation radio row; the Motivation sub-screen with one recording (no "Random" row); "When it plays" hidden until Story 7.5, so the Motivation row reads the message name, "Random" or "None" only.
- The preview button keeps the approved "Play message" / "Pause message" labels; a tap while it plays stops the preview (it restarts from the beginning).

## Spec Change Log

## Review Triage Log

### 2026-10-28 — Review pass
- verdicts: 36 findings — high 0, medium 24, low 11, false 1, maybe-false 0 (intent-audit divergences that follow the contract's chosen reading are not counted; duplicates share a route)
- findings:
  - `[medium]` `patch` (blind) preview listeners run outside the lock and race a ring-triggered stop — listeners routed through a synchronized identity-checked method; onError test.
  - `[medium]` `patch` (blind) the media preview takes no audio focus — transient focus requested on play, abandoned on end; test.
  - `[medium]` `patch` (blind) preview tests miss the mid-playback error and the ring wiring — error test and RecordingWiringTest.
  - `[low]` `reject` (blind) one plain "Deny" disables record until Settings — matches the approved round-3 state ("banner with Fix and the record button disabled") and the story (denied once or permanently shows the banner).
  - `[medium]` `patch` (blind) save, start and delete failures give no feedback — snackbars: "Couldn't save. Try again." (existing) and two new strings listed for the owner.
  - `[medium]` `patch` (blind) leaving while a record tap is in flight orphans a recording — `left` flag cancels a late start.
  - `[medium]` `patch` (blind) DeleteRecording clears alarms before deleting the recording — recording deleted first.
  - `[medium]` `patch` (blind) Random alarms keep Random with no recording left — cleared to None when no recording remains (story text).
  - `[low]` `reject` (blind) "File missing. Default sound will play." reads oddly for a message — the story mandates this EXPERIENCE.md State Patterns string verbatim; listed for the owner's v2 copy pass.
  - `[medium]` `patch` (blind) a chosen id missing from the loaded list reads None but saves the dangling id — reconciled to None once the list loads, without unsaved changes.
  - `[medium]` `patch` (blind) Recordings wiring in uiModule is untested — UiModuleTest asserts the Motivation row in the app editor and not in onboarding.
  - `[low]` `reject` (blind) AndroidMicrophonePermission duplicates the camera class — a refactor of working code with no named failure.
  - `[low]` `reject` (blind) TalkBack focus on the timer reads the 10 s rounded value — the live region is the requirement; focus reading up to 9 s behind is minor and the fix needs a second semantics node.
  - `[low]` `patch` (blind) RecordingsModelTest misses branches — covered by the tests added with the patches above.
  - `[medium]` `patch` (verification-gap) ring stopping a Recordings preview not checked through the real wiring — RecordingWiringTest.
  - `[medium]` `patch` (verification-gap) RecordingsRoute lifecycle and editor callbacks untested — RecordingsRouteLifecycleTest.
  - `[medium]` `patch` (verification-gap) uiModule Motivation row wiring untested — shared with the blind row.
  - `[medium]` `patch` (verification-gap) MainActivity's RECORD_AUDIO launcher not checked against the Koin instance — activity test.
  - `[low]` `patch` (verification-gap) Random visible when chosen with one recording untested — semantics assertion.
  - `[medium]` `patch` (verification-gap, other) preview listener race — shared with the first blind row.
  - `[medium]` `patch` (edge) delete cancelled by leaving loses onDeleted — NonCancellable delete and callback.
  - `[medium]` `patch` (edge) DeleteRecording partial failure — shared with the blind ordering row.
  - `[medium]` `patch` (edge) leaving during saveTake discards the take being saved — a running save owns its take (NonCancellable).
  - `[medium]` `patch` (edge) leaving while start is in flight — shared with the blind row.
  - `[medium]` `patch` (edge) re-record discards the old take before the new start succeeds — discard after success.
  - `[medium]` `patch` (edge) start failure shows nothing — shared with the feedback row.
  - `[medium]` `patch` (edge) save failure discards silently — shared with the feedback row.
  - `[medium]` `patch` (edge) preview playable while recording gets captured into the take — play ignored and disabled while recording.
  - `[low]` `patch` (edge) take play while saving — ignored while saving.
  - `[low]` `patch` (edge) delete-take discard cancelled by leaving — NonCancellable discard.
  - `[medium]` `patch` (edge) chosen id not in list — shared with the reconciliation row.
  - `[low]` `reject` (edge) SaveAlarm could persist a dangling recording id — the editor reconciles before Save; Story 7.3 resolves a missing recording to no message at ring time.
  - `[medium]` `patch` (edge) Random with zero recordings — shared with the Random row.
  - `[low]` `reject` (edge) `recordings_name` string now unused — kept for the v2 localisation of the auto name; Compose resources are not flagged by lint.
  - `[false]` `reject` (edge) Random shown with fewer than two recordings when it is the choice — the contract says a stored Random "stays selected and readable".
  - `[low]` `patch` (intent) a newly saved message became the choice, which neither the contract nor the preview asks for — removed; the user picks it in the Motivation pane.

## Auto Run Result

Status: done

**Summary:** The editor's Motivation row and sub-screen are live in the app (not in onboarding): radio rows None / Random (2+ recordings, or already chosen) / each message, then "Record a message" opening a Recordings pane (depth 2). The choice is `Motivation` (`none` / `random` / `recording:{id}`) on `Alarm`, `AlarmDraft` and `SessionConfig` (default None), stored in `app.db` v12 and saved only on Save; never a lock weakening. Recordings is driven by `RecordingsModel` over the 7.1 recorder and use cases, a `MicrophonePermission` port (asked on the first record tap only), and a media-usage `RecordingPreview` that takes transient focus and stops when a ring starts. Deleting a message deletes it, then clears it from the alarms using it (Random alarms fall back to None only when no recording remains). The reliability checklist shows the Microphone row after the first record tap.

**Review:** 36 findings; patches applied for the preview race and focus, wiring and route tests, failure snackbars (two new strings for the owner), leaving mid-action, NonCancellable delete/save/discard, re-record order, no preview while recording, DeleteRecording order and Random fallback, dangling-id reconciliation, no auto-select, uiModule and launcher tests, Random semantics. Rejected: Deny-disables-record (approved design), missing-file wording (story mandates the string; owner v2), permission class duplication, TalkBack focus rounding, SaveAlarm dangling id, unused `recordings_name`, one false claim. Nothing deferred.

**Follow-up review recommended:** false (no high patched; medium patches all carry tests).

**Verification:** implementer's targeted runs green (core RecordingUseCasesTest 15; composeApp RecordingsModelTest 18, AlarmEditorMotivationTest 10, UiModuleTest 7, CopyRulesTest; androidApp recorder/preview/wiring/launcher/route/screenshot tests and PreviewScreenshotTest 102, existing baselines unchanged); spotless and detekt clean. The full quality gate runs in CI on the PR.

**Residual risks:** real MediaPlayer, focus and permission-dialog behaviour and the TalkBack 10 s announcement are Robolectric-only (Story 7.12 items 1 and 13); `app.db` v12 may need renumbering if the parallel lane merges a migration first.
