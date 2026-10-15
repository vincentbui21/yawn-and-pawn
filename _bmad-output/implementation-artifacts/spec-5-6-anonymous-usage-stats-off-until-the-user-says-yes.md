---
title: 'Story 5.6: Anonymous usage stats, off until the user says yes'
type: 'feature'
created: '2026-10-16'
status: 'ready-for-dev'
baseline_revision: 'be7a2f04'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-5-context.md'
  - '{project-root}/.claude/skills/pps-design/SKILL.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-1-19-permissions-for-reliable-alarms-and-crash-reporting.md'
warnings: ['oversized']
deferred:
  - 'Story 5.11: the onboarding "Share" / "No thanks" step calls SetAnalyticsConsent and reads analyticsAsked (the onboarding UI already sends OnboardingIntent.UsageStatsChosen)'
  - 'Story 5.13 / owner: the Firebase DebugView check (human-verify) with the debug project'
---

<intent-contract>

## Intent

**Problem:** the app must be able to send three anonymous numbers per wake session (outcome, snooze count, check types) to Firebase Analytics, but only after the user turns "Share anonymous usage stats" on; until then nothing may be collected, queued or sent (AD-15, FR-ONB-6, FR-SET-6).

**Approach:** a core `Telemetry` port with a sealed enum/int-only `TelemetryEvent`, a `ConsentStore` in the settings DataStore, a `ConsentGatedTelemetry` that drops events without consent, events emitted by the engine when a non-test session's `Recorded` commits; on Android a `FirebaseTelemetry` over an analytics facade plus a gate that turns Firebase collection on only with consent and an unlocked, started Firebase; the already-designed Settings switch row is switched on and wired.

## Boundaries & Constraints

**Always:** core stays pure (AD-1); ports return `Outcome` or never throw (AD-12); consent default false; no event is queued for later; no user id, user property or free-text parameter ever reaches Firebase; Firebase collection stays off in the manifest; the `AD_ID` permission (and any other ad permission the Analytics SDK merges) is removed with `tools:node="remove"`; new runtime coordinates go into `config/dependency-allowlist.txt` in the same change; every new string is a resource and matches EXPERIENCE.md verbatim.

**Never:** no onboarding step (Story 5.11); no "Weekly summary" switch (Epic 6); no new screen or new component — reuse `SwitchRow` in the existing untitled general card; no new permission in the allowlist; no analytics before first unlock.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| No consent | 3 events, consent false; then consent on, 1 event | only the last event delivered | consent read failure counts as false |
| Real session ends | Completed or Missed non-test session reaches `Recorded` | 1 `SessionOutcome`, 1 `SnoozeCount`, 1 `CheckTypeUsed` per distinct check type in the plan | telemetry throws → logged, session goes Idle anyway |
| Test session ends | `testMode` session reaches `Recorded` | nothing logged | — |
| Consent on, unlocked, Firebase started | gate sees all three | `setAnalyticsCollectionEnabled(true)` + analytics-storage consent granted (ad consents denied) | Firebase not configured → nothing called |
| Consent off at start | gate sees consent false | `setAnalyticsCollectionEnabled(false)` | — |
| User turns consent off | consent true → false in this process | `setAnalyticsCollectionEnabled(false)` then `resetAnalyticsData()` | — |
| Settings switch | tap "Share anonymous usage stats" | consent stored at once (no Save); switch reflects store | write failure → switch stays at stored value, failure logged |

</intent-contract>

## Code Map

- `core/src/commonMain/.../core/telemetry/` (new package) -- `Telemetry` (suspend `log`, `None`), `TelemetryEvent` (`SessionOutcome(outcome: history.SessionOutcome)`, `SnoozeCount(count: Int)`, `CheckTypeUsed(type: TelemetryCheckType)`), `TelemetryCheckType` enum (Math, WordUnscramble, MemorySequence, QrBarcode; other `CheckType` ids are not logged), `AnalyticsConsent(consent=false, asked=false)`, `ConsentStore` (`observe(): Flow<AnalyticsConsent>`, `suspend setConsent(on): Outcome` — also sets asked), `SetAnalyticsConsent` use case, `ConsentGatedTelemetry(delegate, store)`, pure `sessionTelemetry(session, end)`.
- `core/.../session/SessionEngine.kt:49-73,212-259` -- new last ctor param `telemetry: Telemetry = Telemetry.None` on both ctors; after a committed `Recorded` step from Completed/Missed, log `sessionTelemetry` via a small internal helper class (keep `step` under detekt's complexity limit). `SessionRecorder.outcomeOf` (`SessionRecorder.kt:128`) gives the outcome; check types from `session.checkRun.plan.entries`.
- `data/src/commonMain/.../data/settings/DataStoreConsentStore.kt` (new; pattern `DataStoreMissedNoteDismissals` with `retryWhen`) -- booleans `analytics_consent`, `analytics_asked`; binding in `data/src/androidMain/.../DataModule.kt:86-91`.
- `androidApp/build.gradle.kts:168-170` -- add `libs.firebase.analytics` (already in `gradle/libs.versions.toml:115-117`).
- `config/dependency-allowlist.txt:251-279` (+ header line 6) -- add the resolved Analytics coordinates.
- `androidApp/src/main/AndroidManifest.xml:49-54,74-79` -- the six meta-data flags false; `tools:node="remove"` on `com.google.android.gms.permission.AD_ID` and any merged ad-services permission.
- `androidApp/src/main/kotlin/.../android/telemetry/` (new) -- `AnalyticsSink` facade (`isReady`, `setCollectionEnabled`, `grantAnalyticsStorageOnly`, `resetData`, `logEvent(name, params: Map<String, Long|String>)`), `FirebaseAnalyticsSink`, `FirebaseTelemetry` (names `session_outcome`/`snooze_count`/`check_type`, params `outcome`/`count`/`type`), `AnalyticsConsentGate(store, userLock, firebaseStarted: StateFlow<Boolean>, sink, scope, logger)`; Koin module included by `appModule`; NoOp when `isFirebaseConfigured` is false (model: `WakeModule.kt:44-47`, `FirebaseCrashReporter`/`CrashSink`).
- `androidApp/.../android/crash/FirebaseStartup.kt` -- expose `ready: StateFlow<Boolean>` set after `initialize()` succeeds.
- `androidApp/.../YawnAndPawnApp.kt:211,282-286` -- engine gets `telemetry = get()`; start the gate on `ApplicationScope` after `FirebaseStartup.start()`.
- `composeApp/.../ui/settings/SettingsRoute.kt:13` (add `SettingsRow.UsageStats` to `BuiltSettingsRows`), `SettingsViewModel.kt` (combine consent into `usageStats`, handle `UsageStatsToggled`), `ui/UiModule.kt:25`. Row UI and string `settings_usage_stats` already exist (`SettingsScreen.kt:221-238`, `strings.xml:339`).
- `testing/.../testing/` -- `FakeTelemetry` (records events), `FakeConsentStore`.
- Tests: core `telemetry/*Test.kt` (+ a `core/src/jvmTest` reflection test if needed for "no String field other than enum names"), engine telemetry test (model `SessionEngineHistoryTest.kt`), `DataStoreConsentStoreTest`, `DataModuleTest`, androidApp Robolectric tests for `FirebaseTelemetry`/gate on a fake sink, a manifest meta-data test (model `CrashReportingTest.kt:189-201`), a source scan that `setUserId`/`setUserProperty` never appear in `androidApp/src/main`, `SettingsViewModelTest`, Settings Roborazzi screenshots (model `androidApp/src/test/.../ui/SnoozeSettingsScreenshotTest.kt`; re-record baselines the new row changes).

## Tasks & Acceptance

**Execution:**
- core telemetry package + engine hook + tests.
- data `DataStoreConsentStore` + binding + tests; `:testing` fakes.
- androidApp dependency, allowlist, manifest, telemetry package, `FirebaseStartup.ready`, wiring + tests.
- composeApp Settings wiring + ViewModel tests + screenshots; copy the `pps-design` Done checklist into this spec under Design Notes with every item ticked.

**Acceptance Criteria:**
- Given `TelemetryEvent`, when its subclasses' fields are inspected, then each is an `Int` or an enum (test).
- Given 3 events before consent and 1 after, then only 1 is delivered (test).
- Given the merged debug manifest, then the six flags are false and no `AD_ID` permission is present; `checkDependencyAllowlist` and `checkPermissionAllowlist` pass.
- Given Settings, when it renders, then "Share anonymous usage stats" shows off by default and a tap stores consent at once; screenshots exist in Light, Dark and at 200% font scale.

## Spec Change Log

## Review Triage Log

## Design Notes

- **DEFAULT TAKEN (owner can change): the switch sits in the existing untitled general card** (EXPERIENCE.md: "a card with 'Weekly summary' and 'Share anonymous usage stats'"); no "Usage stats" header string is added, and "Weekly summary" stays hidden until Epic 6. The row is already in the approved design preview.
- **DEFAULT TAKEN: any choice of consent marks `analyticsAsked`** (Settings or the 5.11 onboarding step), so onboarding never asks again after a Settings choice.
- **DEFAULT TAKEN: analytics data is reset only when consent goes from on to off while the app runs**; every start without consent explicitly disables collection (Firebase persists the runtime flag).
- **DEFAULT TAKEN: ad-services permissions merged by the Analytics SDK are removed like `AD_ID`** (no ads, permission allowlist unchanged).
- **DEFAULT TAKEN: only Math, WordUnscramble, MemorySequence and QrBarcode are logged as check types**; any other id (the test-only Placeholder, future types) is not logged until added to the enum.
- **DEFAULT TAKEN: events are sent when `Recorded` commits** — also for a session whose history write was abandoned for a new alarm; a crash before `Recorded` commits re-runs it once on restore.
- **Known limit:** consent lives in the backed-up settings DataStore, so a restore carries the user's choice to a new phone.
- Without `google-services.json` (local builds, CI without the secret) the Firebase pieces bind to no-ops.

## Verification

**Commands** (all with `--no-daemon --max-workers=2`, JDK 17):
- `./gradlew :core:jvmTest --tests "*Telemetry*" --tests "*SessionEngine*"` -- expected: pass
- `./gradlew :data:testAndroidHostTest --tests "*ConsentStore*" --tests "*DataModuleTest*"` -- expected: pass
- `./gradlew :testing:allTests` and `:composeApp:testAndroidHostTest --tests "*Settings*"` (plus `*CopyRules*`) -- expected: pass
- `./gradlew :androidApp:testDebugUnitTest --tests "*Telemetry*" --tests "*Analytics*" --tests "*CrashReporting*" --tests "*Settings*"` -- expected: pass
- `./gradlew :androidApp:checkDependencyAllowlist :androidApp:checkPermissionAllowlist` -- expected: pass
- `./gradlew spotlessCheck detekt` -- expected: pass
