---
title: 'Story 1.1: Scaffold the KMP project with a quality gate'
type: 'chore'
created: '2026-09-26'
status: 'done'
baseline_commit: 'f60a8deaed52e0de216f68b96dd5fc01ef40b118'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
  - '{project-root}/_bmad-output/planning-artifacts/architecture/architecture-pay-per-snooze-2026-09-26/ARCHITECTURE-SPINE.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The repo holds only planning files. Every later story (and the bmad loop) needs one agreed project structure, pinned versions and a single command that means "done".

**Approach:** Create the AGP 9 Kotlin Multiplatform project exactly as the Architecture Structural Seed and Story 1.1 in `epics.md` describe: five modules plus `:detekt-rules`, a version catalog pinning the full Stack table, a minimal launchable app, the `verifyCoreDependencies` check, and a root `qualityGate` task that aggregates every check and passes.

## Boundaries & Constraints

**Always:**
- Package/applicationId/namespace root `com.yawnandpawn.app`; sub-namespaces `...core`, `...data`, `...ui` (composeApp), `...testing`.
- Dependency graph exactly: androidApp → composeApp, data, core; composeApp → core; data → core; testing → core; test-only androidApp/composeApp/data → testing.
- `:core` and `:testing`: KMP, `jvm()` target only, only `commonMain`/`commonTest`. `:core` deps limited to stdlib, coroutines, datetime, serialization; no Koin.
- Versions exactly as the Stack table / story AC (Gradle wrapper 9.7.1, JVM toolchain 17). compileSdk 37, targetSdk 36, minSdk 26. versionName 0.1.0; versionCode = major*10000 + minor*100 + patch; no debug applicationIdSuffix; R8 on release.
- Every user-visible string in resources (app name "Yawn & Pawn" as a CMP string resource). Test names are backticked sentences. Conventional commits with the story id.
- Root build file documents that later stories add their checks (token diff, allowlists, loudness) as `qualityGate` dependencies.

**Never:**
- Silently change a pinned version. If a pinned version cannot work, stop and report it (only Room 3→2.8.5 and Roborazzi→Compose Preview screenshots have pre-approved fallbacks; Roborazzi fallback requires `docs/decisions/oq-3-screenshots.md`).
- No Room, DataStore, Firebase, billing, navigation or theme code yet (catalog entries only). No raw colours/sizes in the placeholder screen beyond MaterialTheme defaults; the real theme is Story 1.3.
- No secrets, signing configs with real keys, `google-services.json` or CI workflow (Story 1.2).
- No iOS targets.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Clean core | `:core` uses only allowed deps and imports | `verifyCoreDependencies` passes | N/A |
| Forbidden core dep | `:core` declares e.g. Koin or Room | Build fails naming the dependency | Message names coordinates |
| Forbidden core import | a `:core` source imports `android.`/`androidx.`/`org.koin.`/Compose/Room | Build fails naming file and import | Message names file + import |
| Forbidden edge | `:composeApp` depends on `:data` | Build fails naming the edge | Message names `composeApp -> data` |
| versionCode | 0.1.0 / 1.2.3 | 100 / 10203 | N/A |
| println in core | `println(...)` in `:core` | detekt fails via custom rule | Rule id reported |

</frozen-after-approval>

## Code Map

- Repo today: planning only (`_bmad/`, `_bmad-output/`, `.claude/skills/`, `docs/`, `README.md`, `.gitignore` already ignores Gradle output, `local.properties`, keystores, `google-services.json`). Keep `docs/` content; only add subfolders.
- `_bmad-output/planning-artifacts/epics.md:490-524` -- Story 1.1 ACs (source of truth for this spec).
- `ARCHITECTURE-SPINE.md` Stack, Structural Seed, AD-1, AD-13, AD-14, Conventions -- versions, folders, rules.
- All Stack versions verified published on Maven Central / Google Maven / Gradle plugin portal (2026-09-26). Unpinned test libs: Robolectric 4.17, JUnit 4.13.2, activity-compose 1.13.0, CMP lifecycle-viewmodel 2.11.0.

## Tasks & Acceptance

**Execution:**
- [x] `gradle/wrapper/*`, `gradlew`, `gradlew.bat` -- Gradle 9.7.1 wrapper -- one pinned build tool.
- [x] `gradle/libs.versions.toml` -- every Stack version plus libraries/plugins aliases -- single version source.
- [x] `settings.gradle.kts` -- repositories, foojay toolchain resolver, include `:core :data :composeApp :androidApp :testing` and `:detekt-rules` at `config/detekt-rules` -- structure AC.
- [x] `build.gradle.kts`, `gradle.properties` -- plugins `apply false`, Spotless (ktlint) + detekt applied to all modules, Kover aggregation with `:core` ≥ 90% line rule, `verifyCoreDependencies` and `qualityGate` tasks with the "later checks" comment -- AD-14 gate.
- [x] `build-logic/` (included build) -- `verifyCoreDependencies` logic as a plain, unit-tested function (dependency list, import scan, project edges) with passing/failing fixtures -- AD-1, testability.
- [x] `core/` -- KMP jvm-only, commonMain placeholder (e.g. `AppVersion` versionCode function) + commonTest -- coverage ≥ 90%, versionCode test.
- [x] `testing/` -- KMP jvm-only, depends on `:core`, placeholder fake/builder package.
- [x] `data/` -- KMP (android library via AGP 9 KMP plugin) commonMain/androidMain, `val dataModule = module { }`, a host test.
- [x] `composeApp/` -- KMP (android library) with CMP, `App()` composable showing the app name string resource, `val uiModule = module { }`, a host test.
- [x] `androidApp/` -- Android app module: `MainActivity`, `YawnAndPawnApp` starting Koin with all module vals, manifest, versioning, R8 release, Robolectric test finding the app name, Roborazzi record/verify of the empty screen with committed baseline.
- [x] `config/detekt/detekt.yml`, `config/detekt-rules/` -- detekt config and custom rule banning `println` in `:core`, with a rule test.
- [x] `tools/tokens/.gitkeep`, `tools/play-catalog/.gitkeep`, `data/schemas/.gitkeep`, `config/.gitkeep` as needed -- seed folders exist.

**Acceptance Criteria:**
- Given the scaffold, when `./gradlew qualityGate` runs, then spotlessCheck, detekt, `:core:allTests`, `:data:allTests`, `:composeApp` host tests, `:androidApp:testDebugUnitTest` (incl. Roborazzi verify), koverVerify, `:androidApp:lintDebug`, `:androidApp:assembleDebug` and `verifyCoreDependencies` all run in one invocation and pass.
- Given the debug APK, when launched, then `MainActivity` shows `App()` with "Yawn & Pawn" and Koin is started.

## Implementation Notes

- `./gradlew qualityGate --rerun-tasks` passes (57 s on the company laptop). Beyond the AC list it also runs `:testing:allTests`, `:detekt-rules:test` and `build-logic:test`.
- Roborazzi works with AGP 9 host tests (OQ-3 confirmed, no fallback, no decision doc). Baseline `androidApp/src/test/screenshots/empty_screen.png` was recorded on Windows; Linux CI (Story 1.2) may need a re-record or tolerance.
- Robolectric host tests run on SDK 34 (`robolectric.properties`): Robolectric 4.17 needs Java 21 for SDK 35+, and the toolchain is pinned to 17. The app still compiles against 37 and targets 36.
- `compose-material3` = 1.9.0 (the version CMP 1.12.1 maps `compose.material3` to; no 1.12.1 artifact exists).
- `detekt-test` 2.0.0-alpha.5 depends on unpublished detekt-api test fixtures; excluded and `detekt-api` added directly. detekt config validation excludes the `yawn-and-pawn` key (root and `:detekt-rules` don't load that rule set).
- versionCode formula exists twice: `build-logic` `versionCodeOf` (used by the build script) and `:core` `AppVersion` (domain). Build scripts can't depend on `:core`; both are tested with 0.1.0 → 100 and 1.2.3 → 10203.
- `gradlew` must be committed with the executable bit (`git update-index --chmod=+x gradlew`).
- Negative checks run manually and reverted: Koin in `:core`, `android.util.Log` import in `:core`, `composeApp → data`, `println` in `:core` all failed the build with named violations.

## Spec Change Log

## Review Triage Log

Pass 1 (blind-hunter, edge-case-hunter, verification-gap):

| # | Finding | Verdict | Evidence | Route |
|---|---|---|---|---|
| 1 | `gradlew` staged as mode 100644 | medium | `git ls-files -s gradlew` shows 100644 (intent-to-add); POSIX CI would get "Permission denied" | patch (`git update-index --chmod=+x` at commit) |
| 2 | Core allowlist prefixes too open (`kotlinx-coroutines-*`, `-serialization-*`), coroutines-test allowed in main | medium | `allowedCorePrefixes` uses `startsWith`; `kotlinx-coroutines-swing` is a jvm artifact that would resolve and pass | patch |
| 3 | Redundant `androidx.compose./room./room3.` import prefixes | low | Covered by `androidx.`; fix is a deletion | patch (with #2) |
| 4 | ksp/kapt/annotationProcessor configurations not scanned | low | `declaringConfiguration` regex has no processor names; one-line regex fix | patch |
| 5 | New modules outside the five known are never edge-checked | medium | Edges gathered only over `allowedEdges.keys`; AD-1 says the graph is exactly the architecture graph | patch |
| 6 | Plugin gathering/wiring untested (only the pure function is) | medium | No TestKit test; empty `ListProperty` inputs would pass silently | patch |
| 7 | `NoPrintlnInCore` scoping/wiring only checked by hand; may never fire | low | Probe run: `println` in `:core` failed `:core:detekt` with `[NoPrintlnInCore]`, same code in `:data` passed. "Never fires" is false; the regression gap is real | defer |
| 8 | build-logic has no toolchain resolver | low | `build-logic/settings.gradle.kts` lacks foojay; root comment claims auto-provisioning | patch |
| 9 | `.gitattributes` has no catch-all | low | Unlisted text files follow `core.autocrlf`; one-line fix | patch |
| 10 | `AppVersioningTest` asserts a test-builder default, not the build's version | low | Bumping `appVersionName` breaks the test until `anAppVersion()` defaults change | patch |
| 11 | Release (R8/shrink) variant never built by the gate | medium | Gate runs only debug tasks; release minify config is unverified until an upload | defer |
| 12 | Lint warnings don't fail; no backup/data-extraction rules; no launcher icon | low | No `lint {}` block; backup rules belong with `runtime.db` (Story 1.7), icon with store listing | defer |
| 13 | Host tests run on SDK 34, not targetSdk 36 | medium | `robolectric.properties sdk=34` (Robolectric needs Java 21 for 35+); API 35/36 behaviour has no host coverage | defer |
| 14 | Screenshot baseline recorded on Windows may fail on Linux CI | maybe-false (medium if true) | Settled by the first CI run in Story 1.2 | defer |
| 15 | Global `startKoin` forces `stopKoin()` in every Robolectric test class | low | Current tests all tear down; later test classes can forget and hit `KoinApplicationAlreadyStartedException` | defer |
| 16 | Koin module tests cannot fail | low | Modules are empty; meaningful `verify()` needs koin-test and bindings from later stories | reject (low, new dependency) |
| 17 | `NoPrintlnInCore` false positives on member `print()`, misses `::println`/aliases | low | Unlikely in `:core` today; fix needs resolution or extra branches | reject |
| 18 | Import scan misses `java.*` and fully qualified references | false / low | AD-1 forbids android/androidx/Koin/Compose/Room, not `java.*` (time APIs are AD-3, detekt in Story 1.6); FQN-without-import is low with a non-trivial fix | reject |
| 19 | `isTestConfiguration` treats `testFixtures*` / names containing "Test" as test-only | low | No such configurations exist; fix adds branches | reject |
| 20 | versionCode parsing accepts `+1`, 0.0.0 → 0, overflow for huge majors | low | Unrealistic inputs for a hand-edited version literal | reject |
| 21 | Unknown module in `allowedEdges` gives unclear `UnknownProjectException`; srcDirs outside `src/` unscanned | low | Unlikely; adds guards | reject |
| 22 | `enableEdgeToEdge()` missing, light-only launch theme | low | Theme is explicitly Story 1.3 (intent Never) | reject |
| 23 | Spec contains machine-specific paths | n/a | Fix is to edit this build's spec | reject |

## Design Notes

- `verifyCoreDependencies` lives in `build-logic` so its logic is a pure function testable with fixtures (fixture = declared coordinates + source texts + project edges → list of violations); the Gradle task only gathers inputs and fails with the violation messages.
- AGP 9: KMP library modules use `com.android.kotlin.multiplatform.library`; the app uses `com.android.application` with AGP's built-in Kotlin. Android host consumers of the jvm-only `:testing` rely on Kotlin's jvm→androidJvm compatibility.
- Environment (company PC, no admin; unsigned downloaded executables are blocked from running): JDK 17 at `C:/Users/BuiTua/AppData/Local/Programs/jdk17/jdk-17.0.20.1+1` (user env `JAVA_HOME` may not be visible to an already-running shell — export it explicitly before `./gradlew`), registered for toolchains in `~/.gradle/gradle.properties` with auto-download off. Android SDK at `C:/Users/BuiTua/AppData/Local/Android/Sdk` (platforms `android-37.0`, `android-36`; build-tools 37.0.0, 36.0.0; licences accepted), referenced by the git-ignored `local.properties`. Keep the foojay resolver in settings for CI/other machines. Run Gradle from Git Bash (`./gradlew`) or PowerShell (`.\gradlew.bat`).

## Verification

**Commands:**
- `./gradlew qualityGate` -- expected: BUILD SUCCESSFUL, all listed tasks executed.
- `./gradlew :androidApp:dependencies --configuration debugRuntimeClasspath` -- expected: no `:data` path from composeApp; core has only allowed deps.
- Temporarily add a forbidden import to `:core` and run `./gradlew verifyCoreDependencies` -- expected: failure naming it (revert after).
