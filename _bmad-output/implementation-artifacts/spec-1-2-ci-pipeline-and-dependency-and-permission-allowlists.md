---
title: 'Story 1.2: CI pipeline and dependency and permission allowlists'
type: 'chore'
created: '2026-09-26'
status: 'done'
baseline_commit: '4f363daa3dfb5914ab976b8028f4adfe64507205'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-1-1-scaffold-the-kmp-project-with-a-quality-gate.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Nothing checks pushes, and nothing stops the build loop from silently adding a network SDK, a device-hostage permission, or a committed secret. There is also no path from a release tag to the Play internal track.

**Approach:** Implement Story 1.2 in `epics.md` exactly: a CI workflow (qualityGate + Gradle Managed Device smoke test), `checkDependencyAllowlist` and `checkPermissionAllowlist` Gradle checks wired into `qualityGate`, and a tag-triggered release workflow that skips (never fails) without secrets, plus a CI guard against committed secrets. The owner kept all four pieces in one story (2026-09-26).

## Boundaries & Constraints

**Always:**
- Checks are pure, unit-tested functions in `build-logic` (same pattern as `verifyCoreDependencies`); Gradle tasks only gather inputs and fail with one line per violation. Both tasks are `qualityGate` dependencies, added next to the existing "later checks" comment in the root build file.
- `config/dependency-allowlist.txt`: one `group:artifact` per line (no versions), `#` comments allowed, covering the union of `:androidApp` `debugRuntimeClasspath` and `releaseRuntimeClasspath` as resolved today.
- `config/permission-allowlist.txt`: exactly the story's 13 permissions (short names mean `android.permission.*`), plus each reviewed library-merged permission on its own line with a `#` reason. `SCHEDULE_EXACT_ALARM` must carry `maxSdkVersion="32"` in the merged manifest when present. Components protected by `BIND_ACCESSIBILITY_SERVICE` or `BIND_DEVICE_ADMIN`, or any `android:lockTaskMode`, fail.
- CI: Temurin 17, Gradle caching, `./gradlew qualityGate`, then the managed-device instrumented test (ATD image, API 34) running one smoke test that launches `MainActivity`. Runs on push and pull_request. On failure the job summary names the failing Gradle task(s).
- Release workflow on tags `v*.*.*`: `versionName` comes from the tag (validated `X.Y.Z`); signing and Play credentials only from GitHub secrets; when any secret is missing the job logs a notice and skips upload with success.
- CI step fails if git tracks any keystore/`*.jks`/`*.keystore`/`*.p12`, `keystore.properties`, service-account JSON or `google-services.json`; `.gitignore` covers the same patterns.
- Pin every GitHub Action to a released major version (or SHA). Test names are backticked sentences.

**Never:**
- No secrets, keystores or real credentials in the repo; no Play upload without secrets; no change to pinned Stack versions.
- Don't add the new checks' runtime cost to `:core`; don't add network-capable runtime dependencies (test-only androidx.test deps are fine).
- Don't weaken `qualityGate` locally to make CI pass.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Deps clean | resolved runtime coords ⊆ allowlist | `checkDependencyAllowlist` passes | N/A |
| Unlisted dep | fixture allowlist with one line removed | fails, printing each unlisted `group:artifact` | one line per coordinate |
| Permissions clean | merged manifest permissions ⊆ allowlist | `checkPermissionAllowlist` passes | N/A |
| READ_PHONE_STATE | fixture manifest adds it | fails naming it | names permission + variant |
| SYSTEM_ALERT_WINDOW | fixture manifest adds it | fails naming it | names permission |
| Exact alarm unbounded | `SCHEDULE_EXACT_ALARM` without `maxSdkVersion="32"` | fails | names the missing bound |
| Hostage component | service with `BIND_ACCESSIBILITY_SERVICE`/`BIND_DEVICE_ADMIN`, or `lockTaskMode` | fails | names component |
| Release, no secrets | tag pushed, secrets absent | upload job skipped with a notice, workflow green | N/A |
| Bad tag | `v1.2` or `v1.2.3-rc` | release job fails before building | message names the tag |
| Secret committed | `release.jks` tracked | CI guard step fails | lists the files |

</frozen-after-approval>

## Code Map

- `build.gradle.kts` -- `qualityGate` task and the "later checks" comment; register the two new tasks here (like `verifyCoreDependencies`).
- `build-logic/src/main/kotlin/com/yawnandpawn/app/buildlogic/` -- `CoreDependencyRules.kt` + `VerifyCoreDependenciesPlugin.kt` are the pattern to copy (pure rules object, task with `@Input`/`@InputFiles`, marker `@OutputFile`, TestKit test in `VerifyCoreDependenciesPluginTest.kt`).
- `androidApp/build.gradle.kts` -- add `testOptions.managedDevices` (ATD API 34), `testInstrumentationRunner`, androidTest deps, release `signingConfig` read from env/Gradle properties only when present, and a `versionName` override property for the release workflow (default stays `0.1.0`; `versionCodeOf` still derives the code).
- `androidApp/src/main/AndroidManifest.xml` -- currently declares no permissions; the merged manifest may contain library permissions (e.g. androidx.core's `${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`), which is the "reviewed library-merged" case.
- `gradle/libs.versions.toml` -- add androidx.test (runner, ext-junit, core) and anything else as unpinned-by-Stack entries with the "verified published" comment.
- `.gitignore` -- already ignores `*.jks`, `*.keystore`, `keystore.properties`, `google-services.json`, `play-service-account*.json`, `local.properties`; add `*.p12`.
- `deferred-work.md` -- two items land here: Windows-recorded Roborazzi baseline vs Linux CI, and building the release variant in CI.

## Tasks & Acceptance

**Execution:**
- [x] `build-logic/.../DependencyAllowlist.kt` + `CheckDependencyAllowlistTask` + tests -- parse allowlist, diff against resolved coordinates, fixture test with a removed line fails.
- [x] `build-logic/.../PermissionAllowlist.kt` + `CheckPermissionAllowlistTask` + tests -- parse merged manifest XML (debug + release, via AGP's merged-manifest artifact), enforce the matrix rows with fixture manifests.
- [x] `config/dependency-allowlist.txt`, `config/permission-allowlist.txt` -- generated from today's resolution/merged manifests, then reviewed; each library-merged permission has a reason comment.
- [x] `build.gradle.kts` -- register both checks, add to `qualityGate`, update the comment.
- [x] `androidApp/` -- GMD definition, instrumentation runner, `src/androidTest/.../MainActivitySmokeTest.kt` (launches `MainActivity`, finds "Yawn & Pawn"), optional release signing from env, versionName override.
- [x] `.github/workflows/ci.yml` -- push + pull_request; checkout, Temurin 17, Gradle setup/caching, `qualityGate`, `:androidApp:bundleRelease` (unsigned; closes the deferred release-variant item), GMD `…DebugAndroidTest` (KVM enabled), secrets-guard step, failure summary naming failed tasks; upload test/lint reports as artifacts on failure.
- [x] `.github/workflows/release.yml` -- tag trigger, tag validation, secret presence check → notice + skip, else decode keystore, build signed AAB with tag versionName, upload to internal track.
- [x] `docs/ci-release.md` -- list the GitHub secret names the release workflow expects and how to add them (owner-facing, short).

**Acceptance Criteria:**
- Given the branch, when `./gradlew qualityGate` runs locally, then it passes and includes `checkDependencyAllowlist` and `checkPermissionAllowlist`.
- Given the PR is opened, when CI runs on GitHub, then qualityGate, the release bundle and the managed-device smoke test all pass (checked on the PR before merge; local Windows cannot run the emulator).

## Implementation Notes

- `./gradlew qualityGate` passes locally (BUILD SUCCESSFUL, about 6 min on the company laptop) and runs `:androidApp:checkDependencyAllowlist` and `:androidApp:checkPermissionAllowlist`. `:androidApp:assembleDebugAndroidTest :androidApp:bundleRelease` pass (unsigned AAB). A throwaway keystore plus the four `UPLOAD_*` env vars and `-Pyawnandpawn.versionName=1.2.3` produced a signed AAB with versionName 1.2.3 / versionCode 10203 (keystore deleted afterwards).
- Plugin `yawnandpawn.allowlists` (build-logic) is applied in the root build file like `verifyCoreDependencies`, but registers both tasks **on `:androidApp`**: they resolve `:androidApp` runtime classpaths, and Gradle 9 forbids resolving another project's configuration from a root task. `./gradlew checkDependencyAllowlist` / `checkPermissionAllowlist` still work from the root (task selectors); `qualityGate` depends on the `:androidApp:` paths.
- Inputs come from the AGP variant API for every variant (debug, release): `variant.runtimeConfiguration` (resolved graph walked from the root component, external modules only) and `SingleArtifact.MERGED_MANIFEST`. build-logic gets `com.android.tools.build:gradle-api` as `compileOnly` from the shared version catalog (`libs.android.gradle.api`, version.ref `agp`), so it cannot drift from the applied AGP. AGP classes are only touched inside `pluginManager.withPlugin("com.android.application")`.
- Both tasks fail when no variant was wired ("gathered no runtime classpath / merged manifest"), so broken wiring cannot pass silently. The dependency check also rejects malformed allowlist lines (e.g. with a version). Stale allowlist entries are not reported.
- The permission check also covers `uses-permission-sdk-23`; the SCHEDULE_EXACT_ALARM bound must be exactly `32`. Today's merged manifests contain one library permission, `com.yawnandpawn.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (androidx.core), listed with a reason.
- Dependency allowlist: 168 `group:artifact` entries, identical for debug and release: Kotlin/KotlinX, AndroidX, Compose Multiplatform, Koin, Stately, jspecify, listenablefuture. appcompat/fragment arrive via koin-android. No network library.
- Tests: `DependencyAllowlistTest` (6), `PermissionAllowlistTest` (12, every matrix row incl. READ_PHONE_STATE, SYSTEM_ALERT_WINDOW, unbounded exact alarm, accessibility service, device admin, lockTaskMode) and TestKit `AllowlistsPluginTest` (5; a local Maven repo fixture proves a removed transitive allowlist line fails the task). Manual probe: READ_PHONE_STATE added to the app manifest failed `checkPermissionAllowlist` for debug and release (reverted).
- Deviation: the instrumented smoke test is named `launching_MainActivity_shows_the_app_name` (underscored sentence), not a backticked one. With minSdk 26 the test APK is dexed below DEX 040, and D8 rejects spaces in method names (`dexBuilderDebugAndroidTest` failed). Host tests keep backticked names.
- Deviation: `release.yml` triggers on every `v*` tag (not the glob `v*.*.*`) so that `v1.2` fails loudly in "Validate tag" as the I/O matrix requires; `v*.*.*` would silently ignore `v1.2`. Validation regex `^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]?)\.(0|[1-9][0-9]?)$` (minor/patch 0-99 to match `versionCodeOf`).
- GMD `atdApi34` (Pixel 6, API 34, `aosp-atd`) gives task `:androidApp:atdApi34DebugAndroidTest`, run in CI with `swiftshader_indirect` after enabling KVM. androidTest deps: androidx.test runner 1.7.0, core 1.7.0, ext-junit 1.3.0 (latest stable on Google Maven, 2026-09-26) plus the existing CMP `ui-test-junit4`.
- Actions pinned to the latest released majors (checked 2026-09-26): checkout@v7, setup-java@v6, gradle/actions/setup-gradle@v6, upload-artifact@v7, r0adkll/upload-google-play@v1. Workflow YAML parsed with the `yaml` npm package; actionlint is not available on this machine.
- Secrets guard is `.github/scripts/check-no-tracked-secrets.sh` (run in both workflows), verified in a throwaway repo: tracked `release.jks`, `cert.p12`, `keystore.properties`, `google-services.json`, `play-service-account.json` were all listed, exit 1. `.gitignore` gained `*.p12` and `*service-account*.json`; `.gitattributes` gained LF for `*.sh`/`*.yaml`.
- Review fixes: each allowlist task fails unless the variants it gathered are exactly its `expectedVariants` input (default `{debug, release}`). Components inherit a hostage `android:permission` from `<application>`, which is flagged too. The secrets guard reads `git -c core.quotePath=false ls-files -z`, also covers `local.properties`, `.env*`, `*.pem`, `*.pk8` and `*.p8`, and is tested by `.github/scripts/test-check-no-tracked-secrets.sh` (run in CI before the guard). Partial `UPLOAD_*` signing configuration fails the build. CI `push` runs only on `main`. The release job runs `qualityGate`, pins the Play upload action by SHA (v1.1.5), rejects v0.0.0 and majors above 20999, strips whitespace from the keystore base64, and keeps the AAB and mapping for 90 days.
- Not verifiable locally: the GitHub CI run (managed device, Linux Roborazzi verify). No Roborazzi threshold was set pre-emptively; the deferred-work item stays open until the first PR run.

- First CI run (PR #2): `empty_screen.png` failed Roborazzi verify on Linux with a 5.5e-6 diff fraction (about 18 anti-aliased text-edge pixels; images visually identical). Added `androidApp/src/test/.../ScreenshotOptions.kt` with a shared 0.1% `changeThreshold` used by `MainActivityTest`; verified locally by swapping in the CI-rendered image as baseline (passes) and restoring the Windows baseline.

- CI bring-up: the managed emulator failed to start on ubuntu-latest with an empty error; a temporary diagnostic step showed `qemu-system-x86_64: libpulse.so.0: cannot open shared object file`. ci.yml now installs `libpulse0` before the GMD step (diagnostic step removed).

- CI bring-up (cont.): with libpulse0 present the emulator still exited silently; a manual boot in CI showed `FATAL | Your device does not have enough disk space to run avd`. ci.yml now frees disk space (unused preinstalled toolchains, Docker images) before setup-java. Diagnostics removed.

## Spec Change Log

## Review Triage Log

Pass 1 (blind-hunter, edge-case-hunter, verification-gap):

| # | Finding | Verdict | Evidence | Route |
|---|---|---|---|---|
| 1 | Variant wiring untested; gate only proves ≥1 variant wired | medium | Tasks throw only on empty input; dropping `release` still passes. Permission-task empty guard untested | patch |
| 2 | Malformed allowlist line not tested at task level | low | Only the pure `malformedLines` is tested; removing it from the task keeps tests green | patch |
| 3 | Application-level `android:permission` inherited by components not checked | low | Check reads only the component's own attribute | patch |
| 4 | Secrets guard untested, unquoted paths, C-quoted paths, fewer patterns than claimed | medium | Guard only runs on a clean repo; `printf $tracked` unquoted; header claims `.gitignore` parity but omits `local.properties`/`.env*` | patch |
| 5 | Partial `UPLOAD_*` env silently builds unsigned | medium | `takeIf { all non-blank }` → null → unsigned release; Play rejects late | patch |
| 6 | CI runs twice per PR commit | medium | `on: push` without branch filter + `pull_request`; concurrency groups differ by event | patch |
| 7 | Release uploads without running checks | medium | `upload` job builds straight from the tag | patch |
| 8 | Credential-holding third-party action pinned to floating `@v1` | low | Spec allows major pins; SHA pin is a direct hardening | patch |
| 9 | Tag regex accepts `v0.0.0` and unbounded majors | low | versionCode 0 / overflow; regex change is a direct correction | patch |
| 10 | Keystore base64 with CRLF/whitespace fails to decode | low | `echo | base64 --decode`; one-line `tr -d` | patch |
| 11 | Signed AAB and mapping not archived | low | No artifact step; Play keeps the uploaded mapping, but the repo side keeps nothing | patch |
| 12 | Docs lack keystore/service-account creation steps | low | Only secret names listed | patch |
| 13 | Tag validation and versionName override not automatically verified | maybe-false (medium if true) | Settled by the first real tag push (Story 1.4) | defer |
| 14 | Unresolved dependency results skipped | low | Run inside `qualityGate`, resolution failures already fail compile/assemble | reject |
| 15 | Accessibility/device-admin detection via intent-filter/meta-data | false | The system only binds these components when protected by the BIND_* permission, which is checked (now incl. application-level) | reject |
| 16 | Stale allowlist entries never reported | low / false | Permission list is intentionally pre-seeded by the story; stale dependency lines are a nicety with extra logic | reject |
| 17 | Emulator image/AVD not cached | low | Optimisation; CI hasn't run yet | reject |
| 18 | `resolvedDependencies` malformed entry gives IndexOutOfBounds | low | Internal property only set by the wiring | reject |
| 19 | `uses-permission-sdk-m` untested/obsolete; `$failed` unquoted in summary | low | Task names contain no spaces; harmless alias | reject |

## Design Notes

- Screenshot baseline: if the Windows-recorded `empty_screen.png` fails Roborazzi verify on Linux CI, set a small, documented compare threshold (Roborazzi `compareOptions` change threshold) rather than keeping per-OS baselines; record the chosen value and why in Implementation Notes. Local verification must keep passing on Windows.
- Play upload: use a maintained upload action (e.g. `r0adkll/upload-google-play`) with `track: internal`, fed by a service-account JSON secret, instead of adding a Gradle publishing plugin (no new build dependency to pin). Suggested secret names: `UPLOAD_KEYSTORE_BASE64`, `UPLOAD_KEYSTORE_PASSWORD`, `UPLOAD_KEY_ALIAS`, `UPLOAD_KEY_PASSWORD`, `PLAY_SERVICE_ACCOUNT_JSON`. Note in `docs/ci-release.md` that the very first upload to a new Play app must be done manually in Play Console (Story 1.4).
- Environment (company PC): JDK 17 at `C:/Users/BuiTua/AppData/Local/Programs/jdk17/jdk-17.0.20.1+1` (export `JAVA_HOME` before `./gradlew`), SDK via git-ignored `local.properties`. Emulators/GMD cannot run locally; compile the instrumented test with `:androidApp:assembleDebugAndroidTest`.

## Verification

**Commands:**
- `./gradlew qualityGate` -- expected: BUILD SUCCESSFUL, both allowlist checks executed.
- `./gradlew :androidApp:assembleDebugAndroidTest :androidApp:bundleRelease` -- expected: success (unsigned release bundle without secrets).
- Temporarily add `<uses-permission android:name="android.permission.READ_PHONE_STATE"/>` to the app manifest and run `./gradlew checkPermissionAllowlist` -- expected: failure naming it (revert after).
- Validate workflow YAML syntax (e.g. `python -c "import yaml,sys; yaml.safe_load(open(p))"` for each file, or actionlint if available).

**Manual checks (if no CLI):**
- After the PR is opened: the CI run on GitHub is green, including the managed-device job.
