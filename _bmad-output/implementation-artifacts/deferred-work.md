# Deferred work

- source_spec: `_bmad-output/implementation-artifacts/spec-1-1-scaffold-the-kmp-project-with-a-quality-gate.md`
  summary: Add an automated check that `NoPrintlnInCore` fires on a `:core` source through the real detekt config and plugin wiring (and not on other modules).
  evidence: The only test runs the rule with `Config.empty`; the `includes` glob, `active` flags and `detektPlugins` wiring were verified by a manual probe only (2026-09-26), and config validation skips the `yawn-and-pawn` key.
  status: parked for the Epic 1 retrospective (low) by sprint-change-proposal-2026-10-01.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-1-scaffold-the-kmp-project-with-a-quality-gate.md`
  summary: Build the release variant (R8 + resource shrinking) in CI or the quality gate so minify problems surface before a Play upload.
  evidence: `qualityGate` runs only `assembleDebug`/`lintDebug`; `release` has `isMinifyEnabled`/`isShrinkResources` that nothing exercises. Natural home: Story 1.2 (CI) or Story 1.4 (first signed upload).
  status: resolved in Story 1.2: `.github/workflows/ci.yml` runs `:androidApp:bundleRelease` (R8 + shrinking, unsigned) on every push and PR; it passes locally. `qualityGate` itself still builds debug only.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-1-scaffold-the-kmp-project-with-a-quality-gate.md`
  summary: Configure lint severity (warnings/baseline), add backup and data-extraction rules excluding `runtime.db`, and a launcher icon.
  evidence: No `lint {}` block; manifest has `allowBackup="true"` with no rules and no icon. Backup rules belong with Story 1.7 (`app.db`/`runtime.db`), the icon with the store listing.
  status: assigned by sprint-change-proposal-2026-10-01: backup rules to Story 1.12 (already there), the icon to Story 8.2, lint config parked for the Epic 1 retrospective.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-1-scaffold-the-kmp-project-with-a-quality-gate.md`
  summary: Run Robolectric host tests on the app's targetSdk (36) instead of SDK 34, e.g. by giving only the Test tasks a Java 21 launcher.
  evidence: `androidApp/src/test/resources/robolectric.properties` pins `sdk=34` because Robolectric 4.17 needs Java 21 for SDK 35+ and the toolchain is 17; API 35/36 behaviour (edge-to-edge, full-screen intent rules) has no host coverage. Needs a JDK 21 on dev machines and CI.
  status: parked for the Epic 1 retrospective by sprint-change-proposal-2026-10-01 (a portable JDK 21 is possible without admin).
- source_spec: `_bmad-output/implementation-artifacts/spec-1-1-scaffold-the-kmp-project-with-a-quality-gate.md`
  summary: Confirm the Roborazzi baseline recorded on Windows verifies on Linux CI, or re-record on CI / add a compare threshold.
  evidence: Unverified (medium if true). Settled by the first CI run of `qualityGate` in Story 1.2.
  status: resolved in Story 1.2 (PR #2): Linux render differs by a 5.5e-6 diff fraction; shared 0.1% changeThreshold in androidApp/src/test/.../ScreenshotOptions.kt.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-1-scaffold-the-kmp-project-with-a-quality-gate.md`
  summary: Remove the "every Robolectric test class must call stopKoin()" trap (test Application or shared rule).
  evidence: `YawnAndPawnApp` calls global `startKoin`; a later test class without `@After stopKoin()` makes the next class throw `KoinApplicationAlreadyStartedException`.
  status: assigned to Story 1.12 by sprint-change-proposal-2026-10-01. Resolved in Story 1.12: Robolectric uses TestYawnAndPawnApp (robolectric.properties application=), which stops any running Koin before onCreate; StopAppRule / stopApp() tear down; no test calls stopKoin() directly.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-2-ci-pipeline-and-dependency-and-permission-allowlists.md`
  summary: Verify the release workflow end to end on the first real tag (tag validation, versionName override reaching the AAB, signing, Play upload status draft vs completed).
  evidence: Unverified (medium if wrong). The tag regex and `-Pyawnandpawn.versionName` were only checked by hand locally; settled by the first `vX.Y.Z` push after Story 1.4 creates the Play app record.
  status: assigned to Story 8.7 (after the Play service account exists) by sprint-change-proposal-2026-10-01.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-3-generated-design-tokens-and-ppstheme.md`
  summary: Route the DESIGN.md disabled token pair (disabled-container / disabled-content) into every disabled control instead of Material's onSurface-alpha defaults.
  evidence: Material 3 `ColorScheme` has no disabled roles, so stock Button/Switch/TextField disabled states use onSurface at 12%/38% alpha, a colour DESIGN.md doesn't define. The disabled Snooze control (Epic 1 ringing screen, 1.15) must use the token pair explicitly.
  status: assigned to Story 1.15 by sprint-change-proposal-2026-10-01. Ringing half resolved in Story 1.15: `SnoozeButton` fills `disabled-container-sunrise` and labels in `disabled-content-sunrise` (no Material alpha), checked by a pixel test in `RingingSemanticsTest`. Other disabled controls stay with their own stories.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-3-generated-design-tokens-and-ppstheme.md`
  summary: Automatically assert the release build contains no debug-only showcase code or activity.
  evidence: Only a manual inspection was done; moving `ThemeShowcase*` out of `androidApp/src/debug` would ship an exported debug activity with every check green.
  status: assigned to Story 1.18 by sprint-change-proposal-2026-10-01. Resolved in Story 1.18. The build-logic task `checkReleaseContent` (in `qualityGate`) reads the release merged manifest, the release project classes and the release resource directories. It fails on:
    - any `com/yawnandpawn/app/debug/` class (`debug.preview`, `ThemeShowcase`);
    - `DebugFireReceiver`, `DebugFireProvider` or the `com.yawnandpawn.app.debug.FIRE` action;
    - `ThemeShowcase` or `.debug.preview.` components;
    - `preview_launcher_label` or "Yawn & Pawn Preview".

    The rules have unit tests (`ReleaseContentTest`). Wiring the debug variant into the task by hand made it report 105 items.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-6-time-ports-deadlines-and-alarm-occurrence-math-in-core.md`
  summary: Decide how deadlines and scheduling behave right after a reboot when the wall clock is wrong until network time syncs.
  evidence: Unverified (medium if it happens). After a reboot `Deadline` compares wall time by design (AD-3); an RTC reset or manual clock change can make a snooze deadline due too early or too late. Natural home: Stories 1.10 (reschedule on boot/time change) and 1.12 (SessionEngine restore).
  status: assigned to Story 1.10 by sprint-change-proposal-2026-10-01 (recorded in `docs/decisions/reboot-clock.md`), then carried to Story 2.2. Resolved in Story 2.2: no clock-trust check (decision appended to `docs/decisions/reboot-clock.md`); `TIME_SET` re-runs `rescheduleAll()` and re-arms the session slot, a restored ring rings at once whatever the clock says, and a snooze end compares wall time only across a reboot.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-7-store-alarms-in-app-db.md`
  summary: Decide whether request codes of deleted alarms may be reused, or keep a persisted high-water mark.
  evidence: Unverified (medium once scheduling exists). "Highest in use + 1" reuses a deleted alarm's code; harmful only if a stale PendingIntent survives. Settle in Story 1.10 (scheduler cancels on delete).
  status: assigned to Story 1.10 by sprint-change-proposal-2026-10-01 (persisted high-water mark, codes never reused).
- source_spec: `_bmad-output/implementation-artifacts/spec-1-7-store-alarms-in-app-db.md`
  summary: Write a downgrade policy for restoring a newer-schema app.db backup onto an older install.
  evidence: Unverified (medium if it happens). Destructive fallback is forbidden, so a v2 backup restored on v1 fails to open. Arises with Story 1.13 (session_history, schema v2).
  status: assigned to Story 1.13 by sprint-change-proposal-2026-10-01 (`docs/decisions/db-downgrade.md`). Resolved in Story 1.13: the policy is written (a restored `app.db` above the installed schema is skipped and logged); its implementation is carried to Story 2.12 (entry below).
- source_spec: `_bmad-output/implementation-artifacts/spec-1-7-store-alarms-in-app-db.md`
  summary: Test that app.db opens before first unlock (credential storage locked).
  evidence: Only the device-protected path is asserted. Belongs with the directBootAware receivers and WakeService in Stories 1.10 and 1.14.
  status: assigned to Story 1.10 by sprint-change-proposal-2026-10-01.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-8-create-and-edit-an-alarm.md`
  summary: Owner copy for storage read failures: the Alarms list failing to load, and an alarm failing to open in the editor.
  evidence: EXPERIENCE.md has no strings for these states. Story 1.8 now hides the misleading empty state on a list failure and closes the editor on a load failure without a message; both need a short owner-approved message (and possibly a retry).
  status: assigned to Story 1.9 by sprint-change-proposal-2026-10-01 ("Couldn't load your alarms." with "Try again", and the snackbar "Couldn't open this alarm.").
- source_spec: `_bmad-output/implementation-artifacts/spec-1-8-create-and-edit-an-alarm.md`
  summary: On a real phone (Oppo A96, Android 13) the Save button stays hidden behind the keyboard while the label or time field is focused.
  evidence: Device walkthrough 2026-09-27: with the IME shown, uiautomator reports Save at y=2285 under the keyboard, and a tap there hits the keyboard. The `imePadding()` fix is not effective on device (likely the Scaffold's bottom bar is not inset or the window isn't edge-to-edge). Fix in Story 1.9 with an on-device check.
  status: fixed in design preview round 1: `MainActivity` is edge-to-edge with `windowSoftInputMode="adjustResize"`, so the editor Scaffold's `imePadding()` now receives IME insets and the bottom bar (Save) sits above the keyboard. The uiautomator bounds check on the Oppo A96 is still pending (the phone was locked during the run). Device confirmation assigned to Story 1.21 item 18 by sprint-change-proposal-2026-10-01.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-8-create-and-edit-an-alarm.md`
  summary: The app does not draw edge-to-edge: the status bar stays system grey instead of matching the theme background.
  evidence: Device screenshots 2026-09-27 (Oppo A96). `MainActivity` doesn't call `enableEdgeToEdge()`; targetSdk 35+ enforces edge-to-edge anyway, so insets must be handled. Fix in Story 1.9.
  status: fixed in design preview round 1 (`spec-design-preview-whole-app.md`): `MainActivity` and the debug `PreviewActivity` call `enableEdgeToEdge()` (transparent bars over the theme `bg`); on-device confirmation on the Oppo A96 still pending. Device confirmation assigned to Story 1.21 item 19 by sprint-change-proposal-2026-10-01.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-8-create-and-edit-an-alarm.md`
  summary: Owner decision: should the editor open with the keyboard up on the hour field?
  evidence: Material TimeInput focuses the hour and opens the numeric keyboard immediately ("keyboard input first" per EXPERIENCE.md), covering the lower half of the form. Seen on the Oppo A96 walkthrough.
  status: resolved by the owner decision 2026-09-27 (`spec-design-preview-whole-app.md`): the time input is now scrolling wheels (`PpsWheelTimePicker`) and no keyboard ever opens; EXPERIENCE.md and DESIGN.md `time-picker` rows updated.
- source_spec: `_bmad-output/implementation-artifacts/spec-design-preview-whole-app.md`
  summary: Ramp start semantics after the owner removed the starting-volume slider (feedback item 3).
  evidence: The owner wants the ramp to start at "20% of the alarm volume". The model keeps `rampStartPercent = 20` and `AlarmValidation` still treats it as an absolute level that must not exceed the volume, so the editor saves `min(20, volume)`. Story 1.14 (`rampGain`) must read it as 20% of the set volume, and the validation rule can then go.
  status: assigned to Story 1.14 by sprint-change-proposal-2026-10-01.
- source_spec: `_bmad-output/implementation-artifacts/spec-design-preview-whole-app.md`
  summary: Live background blur is only on surfaces over moving content (bottom pill, snooze confirm sheet), not on cards.
  evidence: Cards sit inside the scrolling content over a static gradient; a backdrop blur there looks the same as none and would need each card to re-record its own backdrop. DESIGN.md records this rule; revisit if the owner wants blur visible on cards (would need content behind cards, e.g. a photo background).
  status: kept as designed by sprint-change-proposal-2026-10-01; the owner may revisit.
- source_spec: `_bmad-output/implementation-artifacts/spec-design-preview-whole-app.md`
  summary: Editor sub-screens are in-screen state (`EditorUiState.pane`), not Navigation 3 routes.
  evidence: Keeps production navigation unchanged (spec Never). Predictive back animates the whole editor, not the sub-screen; revisit when Story 1.9+ wires the editor to real sub-screens.
  status: assigned to Story 1.21 item 21 by sprint-change-proposal-2026-10-01 (sub-screens become Navigation 3 routes in a bug story only if predictive back looks wrong).
- source_spec: `_bmad-output/implementation-artifacts/spec-design-preview-whole-app.md`
  summary: Exempt the time-wheel tick from the Story 1.17 bundled-sound loudness check.
  evidence: `composeApp/src/androidMain/res/raw/wheel_tick.wav` (12 ms, 3.2 kHz, peak -12 dBFS, generated in-repo) is a deliberately quiet UI sound (feedback item 17). The loudness gate (peak >= -3 dBFS, >= -14 LUFS) is not built yet, so there is no exemption list to add it to; Story 1.17 must scope its check to alarm sounds or exempt this file.
  status: assigned to Story 1.17 by sprint-change-proposal-2026-10-01 (the check measures `res/raw/alarm_*` only and lists `wheel_tick.wav` as exempt). Resolved in Story 1.17: `checkSoundLoudness` measures `androidApp/src/main/res/raw/alarm_*` only and prints `wheel_tick.wav` as exempt (not measured).
- source_spec: `_bmad-output/implementation-artifacts/spec-design-preview-whole-app.md`
  summary: Remove the FR-PRG-6 export story via correct-course.
  evidence: Owner decision 2026-10-01 (docs/design-preview/feedback.md item 23): no "Export CSV" in the app. The design preview removed it from Progress and EXPERIENCE.md (IA row struck through, export strings and state row removed, F9 without export). PRD FR-PRG-6 [Could] and its Epic 6 story (epics.md, Refs FR-PRG-6) still exist; run correct-course when Epic 6 comes up to drop the story and update the PRD.
  status: resolved by sprint-change-proposal-2026-10-01 (PRD v0.3, Story 6.9 dropped, Story 6.5 updated).
- source_spec: `_bmad-output/implementation-artifacts/spec-design-preview-whole-app.md`
  summary: Drop the "snoozes chart" from FR-PRG-2 via correct-course.
  evidence: Owner decision 2026-10-01 (docs/design-preview/feedback.md item 24): no snoozes bar chart on Progress; the ring of 30 mornings shows each snoozed day and the "snoozes" tile gives the count. The design preview removed the chart, its strings and its preview state, and EXPERIENCE.md / DESIGN.md no longer list `bar-chart`. PRD FR-PRG-2 and the Epic 6 progress story still name a snoozes chart; run correct-course with the Progress epic to update them.
  status: resolved by sprint-change-proposal-2026-10-01 (PRD v0.3, Story 6.9 dropped, Story 6.5 updated).
- source_spec: `_bmad-output/implementation-artifacts/spec-1-9-alarm-list-on-home-with-the-next-alarm-countdown.md`
  summary: Owner copy decision: should Home and the editor say something visible when Duplicate, Delete or a switch fails to save?
  evidence: Story 1.9 follows its I/O matrix: failures are logged, the card stays, the switch reverts, and Duplicate does nothing visible. Storage failures are rare, but a silent Duplicate looks like a broken button. A short message (for example the editor's existing "Couldn't save the alarm. Try again.") needs owner approval. Raise at the Epic 1 review.
  status: resolved by owner decision 2026-10-02: a switch, Duplicate or Delete that cannot be stored shows the snackbar "Couldn't save the alarm. Try again." on Home (`HomeUiState.saveFailed`, 4 s) and in the editor overflow menu (`EditorEffect.ShowSaveFailed`); the switch still reverts. EXPERIENCE.md Key strings updated.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-9-alarm-list-on-home-with-the-next-alarm-countdown.md`
  summary: Editor overflow "Duplicate" copies the stored alarm and opens the copy, dropping unsaved edits without "Discard changes?".
  evidence: Implementation decision in Story 1.9 (AlarmEditorViewModel.onMenuIntent). Options: ask "Discard changes?" first, or duplicate the form as edited. Raise at the Epic 1 review.
  status: resolved by owner decision 2026-10-02: with unsaved changes, Duplicate asks the same "Discard changes?" dialog as Back first; "Discard" duplicates the saved alarm and opens the copy, "Keep editing" does nothing. Without unsaved changes it duplicates at once. EXPERIENCE.md Key strings updated.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-9-alarm-list-on-home-with-the-next-alarm-countdown.md`
  summary: Keep each tab's saved state (scroll, sub-screen) when switching between Progress, Settings and You.
  evidence: The back stack is [Alarms] or [Alarms, Tab]; selecting another tab removes the previous non-root tab, so its saveable state is dropped. Harmless in Epic 1 (those tabs are placeholders); revisit when Epic 5 (Settings) or Epic 6 (Progress) gives them content.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-9-alarm-list-on-home-with-the-next-alarm-countdown.md`
  summary: Move the AlarmDeleted log into the core delete use case once a second delete path exists.
  evidence: Story 1.9 logs AlarmDeleted in `ui.home.AlarmActions`, shared by Home and the editor. Epic 4's commitment-lock delete confirmation and Story 5.9 "Delete all data" should decide whether they log per alarm; if so, log inside `DeleteAlarm` so no path can skip it.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-10-schedule-alarms-exactly-and-keep-them-across-reboot-and-clock-changes.md`
  summary: Story 1.13: `app.db` is now version 2 (Story 1.10 added `request_code_sequence`), so `session_history` is the v2 to v3 migration.
  evidence: `AppDatabase` is `version = 2` with `MIGRATION_1_2` in `APP_DATABASE_MIGRATIONS` and `data/schemas/.../2.json` exported. Story 1.13 must add `MIGRATION_2_3`, export `3.json`, keep a hand-built v2 file migration test next to the v1 one in `AppDatabaseFactoryTest`, and write `docs/decisions/db-downgrade.md` for v3.
  status: resolved in Story 1.13: `AppDatabase` is version 3 with `MIGRATION_2_3`, `3.json` is exported next to `1.json` and `2.json`, and `AppDatabaseFactoryTest` migrates a hand-built v2 file (alarms and mark 1005 kept) and a v1 file through 1 to 2 to 3.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-11-the-complete-wake-session-state-machine-in-core.md`
  summary: Call adapter contract: re-send CallStarted after ProcessRestored and whenever a new ring starts while a call is still active.
  evidence: The reducer clears the pause on restore and every new ring (after a snooze, a grant during a call, a merge) starts unpaused; Snoozed + CallStarted changes nothing (AD-2). Without the re-send the next ring plays over an ongoing call. Story 2.7 (pause for phone calls) must implement and test it.
  status: resolved in Story 2.7. `CallDetector` reconciles the session with the audio mode on every state change, so a restore or a new ring during a call is paused again. A ring that starts mid-call opens silent until then. Tested in `CallDetectorTest` (restore during and after a call, ring during a call) and `WakeRuntimeTest`.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-11-the-complete-wake-session-state-machine-in-core.md`
  summary: AD-2 has a UserUnlocked row only for Ringing; unlocking during Grace, Loud or Snoozed leaves beforeFirstUnlock set for the whole session.
  evidence: Normative table (ARCHITECTURE-SPINE.md AD-2) row "Ringing (before first unlock) | UserUnlocked". The user usually unlocks while doing the check (Grace or Loud). Settle with Story 2.3 (ring before first unlock) via correct-course: add rows for Grace, Loud and Snoozed.
  status: settled in Story 2.3 without correct-course, the Story 2.4 way ("init outside the table"); no rows were added. Snooze availability reads the live `UserLockState` (`NoBillingSnoozeAvailability`, precedence TestMode > BeforeFirstUnlock > CatalogueNotLoaded), so an unlock in Grace, Loud or Snoozed lifts "Unlock your phone to snooze" without a transition. The session flag `beforeFirstUnlock` only keeps the Direct Boot sound and check for the current ring. Story 2.4 starts billing (and lifts the substitutions where Epic 3 needs it) from the unlock signal, outside the table.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-11-the-complete-wake-session-state-machine-in-core.md`
  summary: Check-run consistency for real checks: the fallback keeps the old seeds and failedAttempts and emits no StartCheckStep; ValidNext advances without StartCheckStep(step + 1); the fallback policy cannot see a matcher error.
  evidence: Harmless in Epic 1 (one Placeholder step, fallback never allowed). Story 3.2 (multi-step checks), Story 3.9 (fallback picker: new seeds in FallbackRequested, reset attempts) and Story 7.7 (House Hunt matcher error flag) must settle them.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-11-the-complete-wake-session-state-machine-in-core.md`
  summary: Session persistence details for Story 1.12 and 1.13: a fixed Json configuration (ignoreUnknownKeys, explicit class discriminator) with a compatibility test for older payloads, and one owner of the history write.
  evidence: SessionState is @Serializable with default Json only and no versioning; Completed/Missed emit both a one-shot RecordOutcome and the entry effect HistoryWriteRequested. Story 1.12 (RoomActiveSessionStore) must pin the Json config and test decoding; Story 1.13 must make SessionRecorder the single idempotent writer that dispatches Recorded from one of the two.
  status: Json half resolved in Story 1.12 (SessionJson: ignoreUnknownKeys, classDiscriminator "type", encodeDefaults; golden v1 fixtures plus an extra-field decode test). History half resolved in Story 1.13: `RecordOutcome` is removed, `SessionRecorder` is the only writer, the engine writes on the idempotent `HistoryWriteRequested` entry effect and reduces `Recorded` itself in the same lock.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-11-the-complete-wake-session-state-machine-in-core.md`
  summary: Epic 4 availability and reuse: the real SnoozeAvailabilityPolicy must price through FeeLadder(baseFeeTier, snoozesGranted + 1) with a reducer-level test, and ReuseAccepted must be validated against the offered product.
  evidence: The Epic 1 reducer accepts any offer the policy returns and any ReuseAccepted outside test mode. Stories 4.7 (snoozeAvailability) and 4.9/4.11 (reconciler, orchestration) own these checks.
  status: reuse half resolved in Story 4.11. `ReuseAccepted` snoozes only for the product Snooze offers now (the Pay guard; `SessionPoliciesTest`), and `PurchaseCoordinator` sends it only with the token it offered to that session. The pricing half stays with Story 4.7.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-13-record-every-session-in-history.md`
  summary: Implement the accepted `app.db` downgrade policy: a restored `app.db` whose `user_version` is above the installed `AppDatabase` version is skipped and logged, keeping the current file, and the user is told.
  evidence: Policy in `docs/decisions/db-downgrade.md`. With no destructive fallback Room cannot open a newer file, so a restored v4 `app.db` on a v3 install would make every alarm and history read fail. Story 1.13 changes no backup behaviour.
  status: assigned to Story 2.12 (PpsBackupAgent: check the incoming file's schema version in `onRestoreFile`, show the user a notice when a restore is skipped (owner-approved copy), never set `restoreAnyVersion`, Robolectric test with a hand-built v4 file). Guard resolved in Story 2.12: `PpsBackupAgent` and `AppDatabaseRestoreGuard` skip and log a newer `app.db` and record it in `SkippedRestoreNotice`; `PpsBackupAgentTest` covers v4, v3, v2 and non-database files. The visible notice is carried in the entry below.
- source_spec: `_bmad-output/implementation-artifacts/spec-2-12-back-up-alarms-and-history-never-the-session-or-media.md`
  summary: Owner copy and a home for the skipped-restore notice. Tell the user that their alarms and history were not restored because the backup comes from a newer version of the app, and that installing the latest version brings them back.
  evidence: |
    - Story 2.12 stores the flag: `SkippedRestoreNotice.skippedSchema`, device-protected and never backed up.
    - EXPERIENCE.md has no string for it, so nothing shows it yet.
    - Needed: an owner-approved string in EXPERIENCE.md Key strings, then a small UI story. For example, an info `banner-warning` on Home, dismissed by clearing the flag.
    - It is rare. Android itself normally declines a backup from a newer `versionCode`, because `restoreAnyVersion` is false.
    - Raise at the Epic 2 review.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-13-record-every-session-in-history.md`
  summary: Merged occurrences never reach history: `SessionEffect.RecordMergedOccurrence` is still only logged by the Epic 1 runner, and nothing writes the merged alarm's occurrence (alarm id, scheduled time, the session it joined).
  evidence: Story 1.13 made `SessionRecorder` the only history writer for the session row; `RecordMergedOccurrence` goes to the `EffectRunner` (`LoggingEffectRunner`), which logs its type name only. The consumer is the Day detail of Progress (Epic 6), which lists every occurrence of a morning, merged ones included.
  status: assigned to Story 2.9 (merge an alarm during a session): route the effect to `SessionRecorder` (the only writer, AD-18) with an idempotent `session_merge` row keyed by session id, alarm id and scheduled time, plus its `app.db` migration. Resolved in Story 2.9: `SessionEngine` hands `RecordMergedOccurrence` to `SessionRecorder.recordMerge`, which inserts or ignores a `session_merge` row (`app.db` v3 to v4, `MIGRATION_3_4`, `4.json`), so a replay leaves one row; the writer scan covers `recordMerge` and the DAO write `insertMerge`.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-13-record-every-session-in-history.md`
  summary: Never silent when runtime.db cannot be written: if the engine cannot commit a new session (storage broken), the alarm still has to ring.
  evidence: The engine runs no effect without a successful commit (AD-2 write-ahead), so a failing ActiveSessionStore means AlarmFired never produces Ringing; Story 1.13 only unblocks a stuck ended session. Story 1.14 (WakeService) must ring the default sound directly when the dispatch of AlarmFired fails, and log it (NFR-2).
- source_spec: `_bmad-output/implementation-artifacts/spec-1-14-ring-the-alarm-wakeservice-alarmplayer-and-the-ongoing-notification.md`
  summary: Story 1.14 follow-ups for later stories.
  evidence: |
    - A refused foreground-service start for a fresh alarm has no session slot to retry it, and stopSelf after a refused startForeground may crash. Settle with device evidence in Spike S2 / Story 1.20. Retry half resolved in Story 2.1: a refused alarm or slot start (in the receiver's handler) and a refused `startForeground` (in `WakeService`) re-arm the session slot one heartbeat later carrying the alarm (`SessionSlotRearm.afterRefusedStart`, dropped once 30 min late); its fire handles the alarm in a new process. The `stopSelf` crash risk and whether a retried start is allowed stay with Spike S2 / Story 2.13 (entry below).
    - Android 13+ without POST_NOTIFICATIONS shows no notification or full-screen intent, so nothing stops the ring before the 30-minute limit. Story 1.19 requests the permission and must log or flag the missing permission. Resolved in Story 1.19: the editor asks for POST_NOTIFICATIONS once after the first save, and the Home reliability banner flags it (and a revoked full-screen intent on API 34+, or denied exact alarms on API 31-32) on every start, with "Fix" opening the setting.
    - MediaPlayer.prepare() runs on the main thread under the player lock, which is an ANR risk for content URIs. Story 1.17 (sound library, user files) should move it to prepareAsync or off main. Resolved in Story 1.17: `MediaPlayerPlaybackFactory` and the preview player use `prepareAsync`; a prepare error falls back like a playback error.
    - Only the 12 h format is tested for the notification and wake-screen time. Story 1.15 adds the 24 h case. (Resolved in Story 1.15: `WakeNotifierTest` and `WakeActivityTest` cover the 24-hour setting.)
    - The emergency ring arms no backstop slot, so a process death during it is not recovered. Story 2.1 (recover after a kill). Resolved in Story 2.1: with no session ringing or snoozed, the emergency ring arms the session slot one heartbeat away carrying its alarm, re-armed on every slot fire while it plays and cancelled by "I'm up" or the 30-minute limit; after a kill the slot rings that alarm again (a real session once storage works, else the emergency ring with a fresh limit).
- source_spec: `_bmad-output/implementation-artifacts/spec-1-15-ringing-screen-over-the-lock-screen-with-i-m-up.md`
  summary: Story 1.15 follow-ups.
  evidence: |
    - The GMD timing test (fire a debug-scheduled alarm, WakeActivity resumed with "I'm up" shown within 1,000 ms of the receiver, NFR-7) is not built. It needs the debug fire-now hook (Story 1.18), the POST_NOTIFICATIONS grant on the emulator (Story 1.19; GrantPermissionRule would add androidx.test:rules), and a locked or screen-off emulator, since an unlocked API 34 device shows the full-screen intent as a heads-up. Assigned to Story 1.21 (with 1.18's hook); device timing is human-verify there anyway.
    - Design question for the owner (no layout change made): the Epic 1 snooze label "Snooze unavailable: prices not loaded yet" wraps to two lines in `button-wake`, so the disabled snooze is about as tall as "I'm up" at 100% and taller at 200%. "I'm up" stays 72 dp, filled and first, but it is not the tallest action in that state. Options: a shorter reason, or "I'm up" matching the snooze height. Needs owner approval (design baseline); then a small UI story. Resolved by owner decision 2026-10-02: the visible label is "Prices not loaded yet" (one line at 360 dp, 100%), TalkBack keeps "Snooze unavailable, prices not loaded yet"; EXPERIENCE.md Key strings and the Story 1.15 baselines updated, and `RingingSemanticsTest` checks "I'm up" is the tallest action in that state.
    - The placeholder check is answered by the wake screen while it is shown (Grace or Loud). A user who presses Home right after "I'm up" leaves the session muted, then Loud after the grace window, until they reopen the screen from the notification. This is the Epic 3 check-screen behaviour, and Epic 3 replaces the placeholder.
- source_spec: `docs/spikes/S1.md`
  summary: Payment over the lock screen needs an unlock step: add `UnlockRequested` / `UnlockFailed` (an Unlocking sub-state) to AD-2 and the reducer, with the transition rows in docs/spikes/S1.md, and update FR-RNG-3 text that assumes paying over the keyguard.
  evidence: Spike S1 run V1s: the Play sheet never shows over a keyguard; with a PIN, requestDismissKeyguard → onDismissSucceeded → launchBillingFlow worked 5/5 (L1–L5), cancel gives onDismissCancelled (V2). Owner chose option B (price first, then PIN) on 2026-10-05.
  status: assigned to a bmad-correct-course pass before Epic 4 (Stories 4.11 orchestration and 4.13 confirm sheet).
- source_spec: `docs/spikes/S1.md`
  summary: The volume keys can turn the alarm stream down while the Play purchase sheet is on top; the wake runtime cannot intercept them then.
  evidence: Spike S1 runs U1 and L1: alarm stream went 16/16 → 1/16 from key presses with the sheet open, sound still playing.
  status: assigned to Story 2.8 (volume keys) to decide: re-assert the alarm-stream volume while a purchase is in flight, or accept it; Epic 4 orchestration must keep the sound running under the sheet. Decided in Story 2.8:
    - The gap while the Play sheet is on top is accepted. Play's activity owns the keys then, and FR-SES-6 forbids re-applying the volume continuously.
    - The volume is re-asserted once when the purchase flow hands the screen back. `WakeRuntime.reassertRingVolume()` sets the alarm stream to the ring's volume while the session rings loud (Ringing or Loud, not paused, no emergency ring) and does nothing otherwise. It is tested in `WakeRuntimeTest`; nothing calls it in Epic 2.
    - Story 4.11 (purchase orchestration) must call it on every payment outcome that returns to ringing, including cancelled, error, offline, unlock cancelled and pending. Resolved in Story 4.11: `WakeRuntime` calls it once for every `ShowPurchaseOutcome` (Failed, Cancelled, Offline, UnlockFailed), `ShowPaymentPending`, `ShowReuseSheet` and `HideReuseSheet` (`WakeRuntimeTest`).
- source_spec: `docs/spikes/S1.md`
  summary: Billing results can arrive very late: offline, the Play sheet shows an error and only reports a result when the user closes it (no timeout); declines arrive as BILLING_UNAVAILABLE; consume can fail transiently with SERVICE_UNAVAILABLE.
  evidence: Spike S1 runs N1u (USER_CANCELED after 200 s), N2u (NETWORK_ERROR), C1u (BILLING_UNAVAILABLE), L5 (consume SERVICE_UNAVAILABLE, retry OK).
  status: assigned to Stories 4.10 (consume with retry), 4.11 (orchestration must not block the session on a billing result; map codes) and 4.14 (outcome messages). 4.11 part resolved: `PurchaseCoordinator` runs every billing call on the app scope, never inside the engine's step (a hung consume or launch never delays "I'm up" or the check, `PurchaseCoordinatorTest`); results carry `PurchaseFailureKind` (Offline, UnlockFailed, Error). The response-code table itself (BILLING_UNAVAILABLE → Error) is Story 4.12's.
- source_spec: `docs/spikes/S1.md`
  summary: Play shows the EU "Review and agree" (right of withdrawal) screen before every purchase on a Finnish account, so each paid snooze needs one extra tap; the first purchase also asks about purchase authentication.
  evidence: Spike S1, every run on 2026-10-05.
  status: assigned to Story 4.13 (confirm sheet copy and timing) and 4.18 (licence-tester verification).
- source_spec: `docs/spikes/S1.md`
  summary: Re-check the Spike S1 lock-screen findings on a Pixel and a Samsung device, and the slow-card anomaly (a completed slow-card purchase was missing from queryPurchasesAsync about 5 minutes later).
  evidence: Spike S1 ran on the Oppo A96 only; V1s purchase gone by 20:11:36.
  status: assigned to Story 4.18.
- source_spec: `_bmad-output/implementation-artifacts/spec-2-5-leave-the-alarm-and-come-back-through-the-notification.md`
  summary: Check on the phone that tapping the ringing notification from the shade after Home brings the wake screen back within 1,000 ms, and that three taps leave one wake screen.
  evidence: The GMD test for it failed in CI because the managed device is an ATD image with no notification shade (PR #24, 2026-10-06); it was removed. Robolectric LeaveAndReturnTest covers the logic.
  status: assigned to Story 2.13 (device checklist).
- source_spec: `_bmad-output/implementation-artifacts/spec-2-1-keep-the-backup-alarm-armed-and-recover-after-a-kill.md`
  summary: Story 2.1 follow-ups that need device evidence (Spike S2 / Story 2.13).
  evidence: |
    - Whether `stopSelf` after a refused `startForeground` crashes the process (`Context.startForegroundService() did not then call Service.startForeground()`), and whether a start retried from a session-slot fire is allowed after a refusal. The retry cadence is one heartbeat (60 s), the same as the session slot; device logs (`SessionSlotRearmed reason=wake service start refused`) should confirm it on the matrix.
    - The refused-`startForeground` branch of `WakeService.onStartCommand` (it re-arms the slot through `SessionSlotRearm.afterRefusedStart`) has no host test: Robolectric cannot make `startForeground` throw. The rearm itself is unit-tested in core and through the alarm handler.
    - The actual kill recovery (OEM task killer, swipe from Recents, `adb shell am kill`) within 60 s on the same step is human-verify in Story 2.13; the host tests rebuild the engine over the same `runtime.db`.
    - Review (2026-10-05): the refused-retry is now capped at 30 minutes (the slot carries `retrySince`), so a retry bound is in place; the device cadence still needs Spike S2. The refused-`startForeground` host test stays deferred (above).
    - Review (2026-10-05): a stored Completed or Missed session is settled (history row, runtime.db cleared) only by a restore, which runs only where a foreground service may start (`WakeService`, `MainActivity`, `WakeActivity`). Without a slot fire or an app open it waits until the UI opens. Low impact (the history row is late, nothing rings); Story 2.3 / 2.13 to decide whether a system event should settle it without a service.
    - Review (2026-10-05): which alarm the session slot carries is known only to the process that armed it (`AlarmScheduler.sessionSlotAlarm`: PendingIntent extras cannot be read back). A payload-less re-arm in a new process (a system event after a kill, within the 60 s after a refused start) can still replace a slot that carries an alarm. Rare; persisting the payload (device-protected storage) would close it. Story 2.13.
- source_spec: `_bmad-output/implementation-artifacts/spec-2-2-session-deadlines-survive-clock-changes-and-reboots.md`
  summary: On a device without `Settings.Global.BOOT_COUNT`, a reboot is detected only when the elapsed clock has gone back below the time a deadline was made at.
  evidence: |
    - `AndroidBootCounter` now reports -1 every boot on such a device, so a wall-clock change can no longer move a deadline.
    - If the restore after a reboot runs at a higher uptime than the snooze was granted at, the stored snooze end is read on the new boot's elapsed clock. It can then end up to one snooze length late. A restored Grace session is affected the same way: the restore keeps its grace end, so Loud can come up to one grace window late (the restore runs "now + 1 s" after the reboot, so the error is bounded by the window; refreshing the grace end would make every such restore a full window late, so the code is unchanged). Ringing and Loud are not affected, because the restore gives them a fresh 30-minute deadline.
    - A session row written by an older app version on such a device holds a negative boot identity derived from the wall clock. `Deadline` treats any negative boot count as the missing marker (review of Story 2.2), so that session keeps its monotonic deadlines after the update. Its deadlines have no creation time (0), so a reboot during that one session is not detected until it ends.
    - History's time to complete on such a device is always wall time (review of Story 2.2): a reboot to a higher uptime cannot be detected, and a clock change during one session is rarer than that error.
    - The usual restore runs within a minute of `LOCKED_BOOT_COMPLETED`, so this is rare. `BOOT_COUNT` exists on API 24+, and minSdk is 26.
    - Story 2.13 records whether any device in the matrix lacks `BOOT_COUNT`.
- source_spec: `_bmad-output/implementation-artifacts/spec-2-3-ring-before-the-first-unlock-after-a-reboot.md`
  summary: Story 2.3 notes for the rebase onto main and for later stories.
  evidence: |
    - The new detekt rule `CredentialStorageAccess` made `AndroidNotificationPermission` move its preferences ("reliability", the "asked once" flag for the notification permission) to device-protected storage. The file is now `shared_prefs/reliability.xml` in the device-protected domain. When this branch is rebased onto main, Story 2.12's `backup_rules.xml`, `data_extraction_rules.xml` and `BackupRulesTest` must cover it (exclude it, or include it as a setting). The flag in the old credential-protected file is not migrated, so a user may be asked for the notification permission once more.
    - A Snoozed session restored while locked and not yet over is still ignored by `ProcessRestored` (no table change). Its next ring is marked before the first unlock when it starts, because the snooze rows apply the lock state to every new ring.
    - The Direct Boot note ("Your phone restarted, so today's check is Math.", preview `ringing-locked`) is Epic 3, together with Math as the Direct Boot check. Epic 2 shows only the lock-icon snooze. **Resolved in Story 3.11:** `DirectBootSubstitution.swappedThisRing` and `wakeNote` show the note on the Ringing and Check screens of a ring whose camera check was swapped for Math.
- source_spec: `_bmad-output/implementation-artifacts/spec-2-9-merge-an-alarm-that-rings-during-a-session.md`
  summary: Story 2.9 notes for the rebase onto main and for the owner.
  evidence: |
    - Rebase: Story 2.12 on main made `AppDatabase.SCHEMA_VERSION` the single source for `@Database(version = …)`. This stacked branch still has the literal and sets it to 4. On rebase, set `SCHEMA_VERSION = 4` and keep `MIGRATION_3_4`, `4.json` and the migration tests. `session_merge` lives inside `app.db`, which is backed up as a whole, so the backup rules need no change; `PpsBackupAgentTest` follows the constant.
    - Owner decision needed (low): the Story 2.9 AC says a real alarm during a test session is "merged like any other … outcome stays Test". Story 1.18's reviewed fix (2026-10-02) instead ends the test (recorded Test) and gives the real alarm its own session. Story 2.9 keeps the 1.18 rule, so a real morning is never recorded as a test; it only merges, with a `session_merge` row, when the test cannot be ended. If the owner prefers the AC wording, change it through correct-course.
- source_spec: `_bmad-output/implementation-artifacts/spec-2-8-alarm-volume-and-volume-keys-on-the-wake-screen.md`
  summary: With the screen turned off by the power button while the alarm rings, the wake screen is paused, so the volume keys lower the alarm stream directly. The gap is accepted: FR-SES-6 forbids re-applying the volume continuously, so only the next ring start or the grace end sets it again.
  evidence: Story 2.8 review (2026-10-06): `VolumeKeyGate` swallows keys only while `WakeActivity` is resumed with window focus; the activity is paused with the screen off.
  status: assigned to Story 2.13 (device check on the Oppo A96): confirm what the volume keys do with the screen off during a ring, and that the accessibility shortcut and headphone routing behave.
- source_spec: `_bmad-output/implementation-artifacts/spec-2-11-no-hostage-guard-the-phone-stays-usable.md`
  summary: Story 2.11 device check of the no-hostage guard, and the wake-screen opener seen from background code.
  evidence: |
    - The GMD test `PhoneStaysUsableTest` was removed in review: the CI managed device is an ATD API 34 image (likely without the Settings and Dialer apps), and main no longer has the UiAutomator dependency (removed with Story 2.5's notification-shade test). The host evidence is `NoHostageBackgroundTest` (5 heartbeat minutes in the background: no activity start, the alarm playing) and `WakeStatusTest`.
    - The Koin-injected `WakeScreenOpener` (`AndroidWakeScreenOpener` in `android.screen`) could be called from background code, and `NoHostageApis` would not see it, since its start sits in `android.screen`. Low risk: its only caller is the "Back to alarm" tap.
  status: assigned to Story 2.13 (phone checklist): while the alarm rings, press Home, then open Settings, then the dialer (no call), each for 10 s; the wake screen never comes back on its own and the alarm keeps playing throughout. The `WakeScreenOpener` point is noted for Story 2.13 or a later detekt rule (low).
- source_spec: `_bmad-output/implementation-artifacts/story-2-13-epic-2-escape-attempts-checklist.md`
  summary: Version 2 device pass for the Epic 2 escape checklist items the owner deferred: before-first-unlock UI and unlock-in-place on the lock screen (4, 5), restart and power-off during a ring (6, 7), clock and time-zone change during a ring (8), Bluetooth and wired headphones (9), real and VoIP calls (10), the accessibility shortcut (12), a real call and the lock-screen emergency dialer during a ring (16), other makers' task managers (1) and the emulator / other-maker matrix. Also the observations: no heads-up on the re-ring after a crash while the phone is in use (A), a force-stopped session rings again when the app is reopened (B, confirm intended), the Recents thumbnail can show the alarm list during a ring (C), one fire 2.4 s late (D), and the debug fire hook not storing its time (E).
  evidence: Story 2.13 run on the Oppo A96 on 2026-10-06; owner decision the same day to push to launch and handle these in version 2.
  status: deferred to version 2 (after launch) by owner decision 2026-10-06.
- source_spec: `_bmad-output/implementation-artifacts/spec-3-2-solve-math-to-stop-the-alarm.md`
  summary: A compact grace header on small phones at large font scales, so the Math problem is not pushed below the fold.
  evidence: On 360 × 640 dp at 200% font the grace header fills the scrolling area of the Check screen; the problem is below it and must be scrolled to. "Check" and snooze stay on screen (screenshot `wake_check_math_hard_sunrise_w360_h640_font200`).
  status: deferred to version 2 (after launch) by owner decision 2026-10-06.
- source_spec: `_bmad-output/implementation-artifacts/spec-3-9-fallback-check-picker.md`
  summary: The Fallback check picker has no snooze control in its footer, although the Story 3.9 acceptance criteria ask for one.
  evidence: Decided 2026-10-06 (coordinator): keep the approved `fallback-picker` screen (preview baseline) as it is, without a footer. Snooze is unavailable until billing (Epic 4), so the picker loses nothing today; while it is open, Grace keeps counting and Loud keeps ringing.
  status: assigned to Epic 4 (the first snooze story that makes snooze available on wake screens): decide whether the picker gets the same `button-snooze` footer as the Check screen, and update the preview baseline if so.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-11-the-complete-wake-session-state-machine-in-core.md`
  summary: (follow-up of the check-run consistency item) The fallback now has its own seeds, reset failed attempts and a `StartCheckStep` (Story 3.9). The policy input is `FallbackRequest(type, reason)`, open for the image matcher's error.
  evidence: Story 3.9 settles the fallback part; the matcher-error input stays for Story 7.7.
  status: matcher error assigned to Story 7.7.
- source_spec: `_bmad-output/implementation-artifacts/spec-3-13-suggest-re-registering-after-3-fallbacks-in-7-days.md`
  summary: Home's re-register banner (Story 3.13) is built and tested, but it stays hidden in production until Story 3.10 provides the QR/Barcode type, its stored registration and its registration screen.
  evidence: |
    - No camera check type exists before 3.10. So production binds `CheckRegistrations.None`, and `uiCheckType` maps no core type to `QrBarcode`. The rule and the banner are tested with the placeholder standing in for a camera check.
    - "Re-register" opens the alarm's editor (`HomeEffect.OpenEditor(alarmId)`) instead of QR registration for that alarm, which does not exist yet.
  status: |
    Assigned to Story 3.10:
    - Bind a `CheckRegistrations` that reads each alarm's QR/Barcode config, with its last registration time (`updated_at`).
    - Map the new core type in `uiCheckType`.
    - Point `HomeIntent.ReregisterClicked` at QR registration for that alarm.
    - Check that saving a new code clears the banner. The rule already counts only fallbacks after the last registration.
- source_spec: `_bmad-output/implementation-artifacts/spec-3-12-checks-with-talkback-end-to-end.md`
  summary: (joins the v2 compact grace header item) At 200% font on 360 × 640 dp every check now keeps its input and snooze on screen (Story 3.12 pinned the Memory grid, the Word letters and the QR viewfinder), but the grace header fills most of the scrolling area, so the instruction ("Your turn", the different-code line, the Word slots) must be scrolled to by a sighted user.
  evidence: screenshots `a11y_*_w360_h640_font200`. Since the Story 3.12 review the scrolling area opens at its end, so the instruction next to the input shows and the grace header scrolls up. One state still does not fit - after 5 different codes, the QR viewfinder (240 dp), the 3-line message, the link and snooze are taller than the window, so the viewfinder scrolls (the torch, link and snooze stay on screen; `a11y_qr_loud_w360_h640_font200`).
  status: deferred to version 2 with the compact grace header (owner decision 2026-10-06). Story 3.12's two owner questions were decided on 2026-10-08 (`docs/decisions/q10-check-parameters.md`): the Word Unscramble heading stays "Word {n} of {count}" with no instruction line, and the fallback's Memory Sequence always uses numbered tiles.
- source_spec: `_bmad-output/implementation-artifacts/spec-4-2-money-moneyformatter-and-the-feeladder-in-core.md`
  summary: (device row for the Story 4.18 checklist) Money formatting on the phone matches the host tests and Google Play's own price strings.
  evidence: `AndroidMoneyFormatter` formats with ICU (`android.icu`), and a whole amount uses the currency's cash digits (Story 4.2 review). Robolectric runs ICU from the android-all jar, but the phone's ICU and CLDR versions can differ. Check on the Oppo, with the phone language set to each in turn: English (US) "$1.00", Deutsch "1,00 €", Bahasa Indonesia "Rp 15.000" (or "Rp15.000", as Play shows it), Magyar "15 000 Ft". In each, compare Purchase history and Home "paid" with the price on Play's sheet.
  status: assigned to Story 4.18 (human-verify).
- source_spec: `_bmad-output/implementation-artifacts/spec-4-3-cache-play-prices-for-offline-display.md`
  summary: The cached Play prices are for display only. The price charged and recorded must be Play's live one.
  evidence: A cached entry can be up to 30 days old (`PriceCachePolicy.EXPIRES_AFTER`, Story 4.3 review). Play's own sheet always charges its live price, but the intent and history would record the cached `Money` if they read the cache.
  status: |
    Assigned:
    - 4.12: re-query the product's `ProductDetails` at `SnoozeTapped`/`PayConfirmed` (launching billing needs the live `ProductDetails` anyway) and store the answer in the cache.
    - 4.13 (done): the confirm sheet shows the live price when it arrives and re-arms its 500 ms guard when the shown price changes; Pay sends only the live price.
    - 4.8: `PurchaseIntent` records the live `Money` (micros and currency) of that query, never the cached entry.
- source_spec: `_bmad-output/implementation-artifacts/spec-4-3-cache-play-prices-for-offline-display.md`
  summary: (device row for the Story 4.18 checklist) WorkManager starts only after the first unlock, and the price refresh runs after a reboot.
  evidence: The on-demand start and the locked-start path are tested in Robolectric only (`PriceRefreshSchedulingTest`, `BackgroundWorkWiringTest`).
  status: assigned to Story 4.18 (human-verify). On the Oppo, reboot and let an alarm ring before unlocking (no crash, it rings). Then unlock, open the app online, and check that prices show.
- source_spec: `_bmad-output/implementation-artifacts/spec-4-13-snooze-confirm-sheet.md`
  summary: Story 4.13 wires the confirm sheet over ports whose real adapters come later, and two of its behaviours need the phone.
  evidence: |
    - Ports with `None` defaults in `WakeModule`: `LivePriceSource` (4.12: ProductDetails) and `BillingCountry` (4.12: cached `getBillingConfigAsync` country for the tax note). `DisplayPrices` reads the 4.3 price cache (`CatalogDisplayPrices`); "Use it" / "Not now" go through the 4.11 coordinator (`ReuseChoices`); the only `UnlockPort` binding is 4.11's.
    - Lost unlock callbacks are resolved by the 4.11 coordinator (`onWakeScreenResumed` from `WakeActivity.onResume`); the sheet sends no unlock result of its own.
    - Only `PurchaseOutcome.UnlockFailed` shows a message (until the next tap or 10 s); 4.14 maps every outcome with the full snackbar rules.
  status: adapters assigned to Story 4.12; device checks (Pay on a locked Oppo with PIN, fingerprint and cancel; the EU "Review and agree" extra tap; a price change between open and Pay) assigned to Story 4.18.
