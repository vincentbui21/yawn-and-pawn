# Deferred work

- source_spec: `_bmad-output/implementation-artifacts/spec-1-1-scaffold-the-kmp-project-with-a-quality-gate.md`
  summary: Add an automated check that `NoPrintlnInCore` fires on a `:core` source through the real detekt config and plugin wiring (and not on other modules).
  evidence: The only test runs the rule with `Config.empty`; the `includes` glob, `active` flags and `detektPlugins` wiring were verified by a manual probe only (2026-09-26), and config validation skips the `yawn-and-pawn` key.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-1-scaffold-the-kmp-project-with-a-quality-gate.md`
  summary: Build the release variant (R8 + resource shrinking) in CI or the quality gate so minify problems surface before a Play upload.
  evidence: `qualityGate` runs only `assembleDebug`/`lintDebug`; `release` has `isMinifyEnabled`/`isShrinkResources` that nothing exercises. Natural home: Story 1.2 (CI) or Story 1.4 (first signed upload).
  status: resolved in Story 1.2: `.github/workflows/ci.yml` runs `:androidApp:bundleRelease` (R8 + shrinking, unsigned) on every push and PR; it passes locally. `qualityGate` itself still builds debug only.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-1-scaffold-the-kmp-project-with-a-quality-gate.md`
  summary: Configure lint severity (warnings/baseline), add backup and data-extraction rules excluding `runtime.db`, and a launcher icon.
  evidence: No `lint {}` block; manifest has `allowBackup="true"` with no rules and no icon. Backup rules belong with Story 1.7 (`app.db`/`runtime.db`), the icon with the store listing.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-1-scaffold-the-kmp-project-with-a-quality-gate.md`
  summary: Run Robolectric host tests on the app's targetSdk (36) instead of SDK 34, e.g. by giving only the Test tasks a Java 21 launcher.
  evidence: `androidApp/src/test/resources/robolectric.properties` pins `sdk=34` because Robolectric 4.17 needs Java 21 for SDK 35+ and the toolchain is 17; API 35/36 behaviour (edge-to-edge, full-screen intent rules) has no host coverage. Needs a JDK 21 on dev machines and CI.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-1-scaffold-the-kmp-project-with-a-quality-gate.md`
  summary: Confirm the Roborazzi baseline recorded on Windows verifies on Linux CI, or re-record on CI / add a compare threshold.
  evidence: Unverified (medium if true). Settled by the first CI run of `qualityGate` in Story 1.2.
  status: resolved in Story 1.2 (PR #2): Linux render differs by a 5.5e-6 diff fraction; shared 0.1% changeThreshold in androidApp/src/test/.../ScreenshotOptions.kt.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-1-scaffold-the-kmp-project-with-a-quality-gate.md`
  summary: Remove the "every Robolectric test class must call stopKoin()" trap (test Application or shared rule).
  evidence: `YawnAndPawnApp` calls global `startKoin`; a later test class without `@After stopKoin()` makes the next class throw `KoinApplicationAlreadyStartedException`.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-2-ci-pipeline-and-dependency-and-permission-allowlists.md`
  summary: Verify the release workflow end to end on the first real tag (tag validation, versionName override reaching the AAB, signing, Play upload status draft vs completed).
  evidence: Unverified (medium if wrong). The tag regex and `-Pyawnandpawn.versionName` were only checked by hand locally; settled by the first `vX.Y.Z` push after Story 1.4 creates the Play app record.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-3-generated-design-tokens-and-ppstheme.md`
  summary: Route the DESIGN.md disabled token pair (disabled-container / disabled-content) into every disabled control instead of Material's onSurface-alpha defaults.
  evidence: Material 3 `ColorScheme` has no disabled roles, so stock Button/Switch/TextField disabled states use onSurface at 12%/38% alpha, a colour DESIGN.md doesn't define. The disabled Snooze control (Epic 1 ringing screen, 1.15) must use the token pair explicitly.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-3-generated-design-tokens-and-ppstheme.md`
  summary: Automatically assert the release build contains no debug-only showcase code or activity.
  evidence: Only a manual inspection was done; moving `ThemeShowcase*` out of `androidApp/src/debug` would ship an exported debug activity with every check green.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-6-time-ports-deadlines-and-alarm-occurrence-math-in-core.md`
  summary: Decide how deadlines and scheduling behave right after a reboot when the wall clock is wrong until network time syncs.
  evidence: Unverified (medium if it happens). After a reboot `Deadline` compares wall time by design (AD-3); an RTC reset or manual clock change can make a snooze deadline due too early or too late. Natural home: Stories 1.10 (reschedule on boot/time change) and 1.12 (SessionEngine restore).
- source_spec: `_bmad-output/implementation-artifacts/spec-1-7-store-alarms-in-app-db.md`
  summary: Decide whether request codes of deleted alarms may be reused, or keep a persisted high-water mark.
  evidence: Unverified (medium once scheduling exists). "Highest in use + 1" reuses a deleted alarm's code; harmful only if a stale PendingIntent survives. Settle in Story 1.10 (scheduler cancels on delete).
- source_spec: `_bmad-output/implementation-artifacts/spec-1-7-store-alarms-in-app-db.md`
  summary: Write a downgrade policy for restoring a newer-schema app.db backup onto an older install.
  evidence: Unverified (medium if it happens). Destructive fallback is forbidden, so a v2 backup restored on v1 fails to open. Arises with Story 1.13 (session_history, schema v2).
- source_spec: `_bmad-output/implementation-artifacts/spec-1-7-store-alarms-in-app-db.md`
  summary: Test that app.db opens before first unlock (credential storage locked).
  evidence: Only the device-protected path is asserted. Belongs with the directBootAware receivers and WakeService in Stories 1.10 and 1.14.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-8-create-and-edit-an-alarm.md`
  summary: Owner copy for storage read failures: the Alarms list failing to load, and an alarm failing to open in the editor.
  evidence: EXPERIENCE.md has no strings for these states. Story 1.8 now hides the misleading empty state on a list failure and closes the editor on a load failure without a message; both need a short owner-approved message (and possibly a retry).
- source_spec: `_bmad-output/implementation-artifacts/spec-1-8-create-and-edit-an-alarm.md`
  summary: On a real phone (Oppo A96, Android 13) the Save button stays hidden behind the keyboard while the label or time field is focused.
  evidence: Device walkthrough 2026-09-27: with the IME shown, uiautomator reports Save at y=2285 under the keyboard, and a tap there hits the keyboard. The `imePadding()` fix is not effective on device (likely the Scaffold's bottom bar is not inset or the window isn't edge-to-edge). Fix in Story 1.9 with an on-device check.
  status: fixed in design preview round 1: `MainActivity` is edge-to-edge with `windowSoftInputMode="adjustResize"`, so the editor Scaffold's `imePadding()` now receives IME insets and the bottom bar (Save) sits above the keyboard. The uiautomator bounds check on the Oppo A96 is still pending (the phone was locked during the run).
- source_spec: `_bmad-output/implementation-artifacts/spec-1-8-create-and-edit-an-alarm.md`
  summary: The app does not draw edge-to-edge: the status bar stays system grey instead of matching the theme background.
  evidence: Device screenshots 2026-09-27 (Oppo A96). `MainActivity` doesn't call `enableEdgeToEdge()`; targetSdk 35+ enforces edge-to-edge anyway, so insets must be handled. Fix in Story 1.9.
  status: fixed in design preview round 1 (`spec-design-preview-whole-app.md`): `MainActivity` and the debug `PreviewActivity` call `enableEdgeToEdge()` (transparent bars over the theme `bg`); on-device confirmation on the Oppo A96 still pending.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-8-create-and-edit-an-alarm.md`
  summary: Owner decision: should the editor open with the keyboard up on the hour field?
  evidence: Material TimeInput focuses the hour and opens the numeric keyboard immediately ("keyboard input first" per EXPERIENCE.md), covering the lower half of the form. Seen on the Oppo A96 walkthrough.
  status: resolved by the owner decision 2026-09-27 (`spec-design-preview-whole-app.md`): the time input is now scrolling wheels (`PpsWheelTimePicker`) and no keyboard ever opens; EXPERIENCE.md and DESIGN.md `time-picker` rows updated.
- source_spec: `_bmad-output/implementation-artifacts/spec-design-preview-whole-app.md`
  summary: Ramp start semantics after the owner removed the starting-volume slider (feedback item 3).
  evidence: The owner wants the ramp to start at "20% of the alarm volume". The model keeps `rampStartPercent = 20` and `AlarmValidation` still treats it as an absolute level that must not exceed the volume, so the editor saves `min(20, volume)`. Story 1.14 (`rampGain`) must read it as 20% of the set volume, and the validation rule can then go.
- source_spec: `_bmad-output/implementation-artifacts/spec-design-preview-whole-app.md`
  summary: Live background blur is only on surfaces over moving content (bottom pill, snooze confirm sheet), not on cards.
  evidence: Cards sit inside the scrolling content over a static gradient; a backdrop blur there looks the same as none and would need each card to re-record its own backdrop. DESIGN.md records this rule; revisit if the owner wants blur visible on cards (would need content behind cards, e.g. a photo background).
- source_spec: `_bmad-output/implementation-artifacts/spec-design-preview-whole-app.md`
  summary: Editor sub-screens are in-screen state (`EditorUiState.pane`), not Navigation 3 routes.
  evidence: Keeps production navigation unchanged (spec Never). Predictive back animates the whole editor, not the sub-screen; revisit when Story 1.9+ wires the editor to real sub-screens.
- source_spec: `_bmad-output/implementation-artifacts/spec-design-preview-whole-app.md`
  summary: Exempt the time-wheel tick from the Story 1.17 bundled-sound loudness check.
  evidence: `composeApp/src/androidMain/res/raw/wheel_tick.wav` (12 ms, 3.2 kHz, peak -12 dBFS, generated in-repo) is a deliberately quiet UI sound (feedback item 17). The loudness gate (peak >= -3 dBFS, >= -14 LUFS) is not built yet, so there is no exemption list to add it to; Story 1.17 must scope its check to alarm sounds or exempt this file.
- source_spec: `_bmad-output/implementation-artifacts/spec-design-preview-whole-app.md`
  summary: Remove the FR-PRG-6 export story via correct-course.
  evidence: Owner decision 2026-10-01 (docs/design-preview/feedback.md item 23): no "Export CSV" in the app. The design preview removed it from Progress and EXPERIENCE.md (IA row struck through, export strings and state row removed, F9 without export). PRD FR-PRG-6 [Could] and its Epic 6 story (epics.md, Refs FR-PRG-6) still exist; run correct-course when Epic 6 comes up to drop the story and update the PRD.
