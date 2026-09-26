# Deferred work

- source_spec: `_bmad-output/implementation-artifacts/spec-1-1-scaffold-the-kmp-project-with-a-quality-gate.md`
  summary: Add an automated check that `NoPrintlnInCore` fires on a `:core` source through the real detekt config and plugin wiring (and not on other modules).
  evidence: The only test runs the rule with `Config.empty`; the `includes` glob, `active` flags and `detektPlugins` wiring were verified by a manual probe only (2026-09-26), and config validation skips the `yawn-and-pawn` key.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-1-scaffold-the-kmp-project-with-a-quality-gate.md`
  summary: Build the release variant (R8 + resource shrinking) in CI or the quality gate so minify problems surface before a Play upload.
  evidence: `qualityGate` runs only `assembleDebug`/`lintDebug`; `release` has `isMinifyEnabled`/`isShrinkResources` that nothing exercises. Natural home: Story 1.2 (CI) or Story 1.4 (first signed upload).
- source_spec: `_bmad-output/implementation-artifacts/spec-1-1-scaffold-the-kmp-project-with-a-quality-gate.md`
  summary: Configure lint severity (warnings/baseline), add backup and data-extraction rules excluding `runtime.db`, and a launcher icon.
  evidence: No `lint {}` block; manifest has `allowBackup="true"` with no rules and no icon. Backup rules belong with Story 1.7 (`app.db`/`runtime.db`), the icon with the store listing.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-1-scaffold-the-kmp-project-with-a-quality-gate.md`
  summary: Run Robolectric host tests on the app's targetSdk (36) instead of SDK 34, e.g. by giving only the Test tasks a Java 21 launcher.
  evidence: `androidApp/src/test/resources/robolectric.properties` pins `sdk=34` because Robolectric 4.17 needs Java 21 for SDK 35+ and the toolchain is 17; API 35/36 behaviour (edge-to-edge, full-screen intent rules) has no host coverage. Needs a JDK 21 on dev machines and CI.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-1-scaffold-the-kmp-project-with-a-quality-gate.md`
  summary: Confirm the Roborazzi baseline recorded on Windows verifies on Linux CI, or re-record on CI / add a compare threshold.
  evidence: Unverified (medium if true). Settled by the first CI run of `qualityGate` in Story 1.2.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-1-scaffold-the-kmp-project-with-a-quality-gate.md`
  summary: Remove the "every Robolectric test class must call stopKoin()" trap (test Application or shared rule).
  evidence: `YawnAndPawnApp` calls global `startKoin`; a later test class without `@After stopKoin()` makes the next class throw `KoinApplicationAlreadyStartedException`.
