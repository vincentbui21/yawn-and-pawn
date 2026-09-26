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
