---
title: 'Release builds report to the production Firebase project, debug builds to the debug one'
type: 'bugfix'
created: '2026-11-01'
status: 'done'
route: 'oneshot'
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Since 0.1.5 releases are built on the owner's PC, and the only Firebase config there is the git-ignored `androidApp/google-services.json` of the debug project (`yawn-and-pawn-debug`). So the closed-test builds 0.1.5-0.1.8 send their crashes and usage stats to the debug project, and the production project (`yawn-and-pawn`) has received nothing. Nothing stops this from happening again.

**Approach:** Use the Google Services plugin's per-build-type files: `androidApp/src/release/google-services.json` (production) and `androidApp/src/debug/google-services.json` (debug) win over the module-root file. Apply the Firebase plugins when any of the three exists; a build type with no file builds without Firebase (plugin `missingGoogleServicesStrategy = WARN`). A build type whose resolved file belongs to the other build type's project fails before it builds anything (release never on `yawn-and-pawn-debug`, debug never on `yawn-and-pawn`), with a message naming the file and where the right one goes. CI writes its debug config to `src/debug/`, `release.yml` its production config to `src/release/`. The secrets guard keeps failing on any tracked `google-services.json`; its test gains the per-build-type paths. `docs/ci-release.md`, `docs/dev-setup.md` and the closed-test owner checklist say where each file goes.

</frozen-after-approval>

## Implementation Notes

- `androidApp/build.gradle.kts`: resolves each build type's file the way google-services 4.5.0 does (`src/<type>/` then module root; confirmed from the plugin's `getJsonLocations`). Plugins apply when any build type has a file; `missingGoogleServicesStrategy = WARN` lets the other build type build without Firebase. A build type whose file's `project_info.project_id` is the other build type's project registers `checkFirebaseProject<Type>`, which `pre<Type>Build` depends on, so the build fails before compiling. The ids live in one map (`debug` → `yawn-and-pawn-debug`, `release` → `yawn-and-pawn`); any other project id (e.g. a fork's own) is not blocked.
- Decision: the guard is symmetric (a debug build on the production project fails too), so local debug noise never reaches production crash and usage data. Release-only would have met the request; symmetric costs nothing in CI because each workflow writes only its own build type's file.
- Surprise: with only `src/debug/` (the CI shape) the release bundle failed in `uploadCrashlyticsMappingFileRelease` ("appIdFile ... gmpAppId/release.txt doesn't exist"). Fixed by turning off Crashlytics mapping upload for build types with no file (`CrashlyticsExtension.mappingFileUploadEnabled = false`).
- Consequence: in a checkout whose only file is the debug one at the module root (the owner's PC today), every release-variant task fails, including `qualityGate`'s `checkReleaseContent`, until the production file is in `androidApp/src/release/` (or the root file moves to `src/debug/`). Intended: loud, with the fix in the message.
- CI writes `androidApp/src/debug/google-services.json`, `release.yml` writes `androidApp/src/release/google-services.json`. `.gitignore` (`google-services.json`, any depth) and the guard regex (`(^|/)google-services\.json$`) already covered the new paths; the guard test now lists them.
- Docs: `docs/ci-release.md` (new "Firebase config files" section, local release step, secret table), `docs/dev-setup.md` rule 7, `docs/closed-test/owner-checklist.md`, the root `build.gradle.kts` plugin comment.
- Verified by hand with fake JSON files (fake API key, real project ids): CI shape (`src/debug` only) `bundleRelease` succeeds, debug values carry `yawn-and-pawn-debug`; root debug only: `bundleRelease` fails in `checkFirebaseProjectRelease` with the message; root debug + `src/release` production: release values carry `yawn-and-pawn` (app id `1:593245376095:android:aa0ab6e87080fcab02d5ea`), debug `yawn-and-pawn-debug`; root production only: `preDebugBuild` fails, `preReleaseBuild` passes. No file: `qualityGate`.

## Review Triage Log

Blind Hunter (one oneshot layer), 9 findings:

- No doc step for moving the owner's existing root file — medium, patched: `docs/ci-release.md` "On each computer, once" with the two PowerShell lines.
- The docs table's "either" row for the root file is misleading — low, patched: the row now says it is better left out and why.
- A release with no production file still ships quietly without Crashlytics — medium, deferred (the request keeps "no config → builds without Firebase"); docs now say to check for the plugin warning before uploading.
- The guard has no automated test, and CI no longer runs Firebase on a release build — medium (verification gap), deferred; checked by hand in four cases above.
- Broken JSON stops every build with an unclear error — low, patched: parse failure skips the guard and leaves the error to the google-services plugin for that build type only.
- The file is read outside a provider (configuration cache) — low (the cache is off), patched: read through `providers.fileContents`, as `config/app-links.properties` already is.
- Tester data already in the debug project has no privacy settings — medium, deferred (outside this change).
- The spec lacks baseline, Verification and Tasks sections — false: the oneshot route removes those sections by design. The phone check (a release build's test crash showing in the production Crashlytics) is listed for the owner in the hand-back.
- The Story 5.13 device checklist names no location for the debug file — low, patched.

