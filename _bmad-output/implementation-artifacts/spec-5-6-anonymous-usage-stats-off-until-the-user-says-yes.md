---
title: 'Story 5.6: Anonymous usage stats, off until the user says yes'
type: 'feature'
created: '2026-10-16'
status: 'done'
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
- `core/.../session/SessionEngine.kt:49-73,212-259` -- new last ctor param `telemetry: TelemetryReporter = TelemetryReporter.None` on both ctors (review fix 1: a non-suspending port; `TelemetryReporter.Launching(telemetry, scope, logger)` runs `Telemetry.log` on the app's scope); after a committed `Recorded` step from Completed/Missed, the pure `sessionTelemetry` is reported via a small internal helper class `EngineTelemetry` (keep `step` under detekt's complexity limit). `SessionRecorder.outcomeOf` (`SessionRecorder.kt:128`) gives the outcome; check types from `session.checkRun.plan.entries`.
- `data/src/commonMain/.../data/settings/DataStoreConsentStore.kt` (new; pattern `DataStoreMissedNoteDismissals` with `retryWhen`) -- booleans `analytics_consent`, `analytics_asked`, in their own non-backed-up file `ConsentDataStore` (`datastore/usage_stats_consent.preferences_pb`, review fix 5); binding in `data/src/androidMain/.../DataModule.kt`.
- `androidApp/build.gradle.kts:168-170` -- add `libs.firebase.analytics` (already in `gradle/libs.versions.toml:115-117`).
- `config/dependency-allowlist.txt:251-279` (+ header line 6) -- add the resolved Analytics coordinates.
- `androidApp/src/main/AndroidManifest.xml:49-54,74-79` -- the six meta-data flags false; `tools:node="remove"` on `com.google.android.gms.permission.AD_ID` and any merged ad-services permission.
- `androidApp/src/main/kotlin/.../android/telemetry/` (new) -- `AnalyticsSink` facade (`isReady`, `setCollectionEnabled`, `grantAnalyticsStorageOnly`, `resetData`, `logEvent(name, params: Map<String, Long|String>)`), `FirebaseAnalyticsSink`, `FirebaseTelemetry` (names `session_outcome`/`snooze_count`/`check_type`, params `outcome`/`count`/`type`), `AnalyticsConsentGate(store, userLock, firebaseStarted: StateFlow<Boolean>, sink, scope, logger)`; Koin module included by `appModule`; NoOp when `isFirebaseConfigured` is false (model: `WakeModule.kt:44-47`, `FirebaseCrashReporter`/`CrashSink`).
- `androidApp/.../android/crash/FirebaseStartup.kt` -- expose `ready: StateFlow<Boolean>` set after `initialize()` succeeds; right before it, turn Analytics collection off through the `AnalyticsSink` (review fix 4). `FirebaseConfiguration` is bound in Koin (review fix 6).
- `androidApp/.../YawnAndPawnApp.kt:211,282-286` -- engine gets `telemetry = TelemetryReporter.Launching(telemetry = get<Telemetry>(), scope = get<ApplicationScope>(), logger = get())`; start the gate after `FirebaseStartup.start()`.
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

### 2026-10-16 — Review pass (fast mode: one combined self-review of the diff; the coordinator runs the independent review)
- verdicts: 7 findings — high 0, medium 0, low 3, false 2, maybe-false 2
- findings:
  - `[low]` `patch` detekt failed `:core:detekt`: `EngineTelemetry.committed` had 3 returns and the extra `if (!unchanged)` made `SessionEngine.step` too complex — `committed` now uses a single `when` over the ended state and is called unconditionally (an ignored `Recorded` leaves `to == from`, so it sends nothing); detekt and the core tests pass.
  - `[low]` `reject` Telemetry runs inside the engine lock, so a new alarm can wait behind it — it is bounded to 2 s (`EngineTelemetry.TIMEOUT`, tested by "a telemetry that hangs…"), runs only at a session's end, and Firebase's `logEvent` is asynchronous.
  - `[false]` `reject` The gate could enable collection while the user is locked — it reads `isUserUnlocked()` on every apply and does nothing until `FirebaseStartup.ready`, which only turns true after the first unlock (test "a locked user never gets collection turned on").
  - `[false]` `reject` A restore could send a session's stats twice — stats go only when `Recorded` commits, and that commit moves the session to Idle and clears its row; a crash before it sends them once at restore (test "a Recorded whose commit fails…").
  - `[maybe-false]` `reject` The real `FirebaseAnalytics.setConsent` / `resetAnalyticsData` calls have only been compiled, never run (no `google-services.json` here) — settled by the human-verify DebugView check (Story 5.13 / owner).
  - `[maybe-false]` `reject` The Analytics SDK brings `play-services-ads-identifier` and the Privacy Sandbox `ads-adservices` libraries onto the runtime classpath — their permissions are removed and ad id, SSAID and ad consents are off in the manifest; excluding the libraries could break measurement. Listed in the allowlist with a review comment for the owner and the Data safety form (Epic 8).
  - `[low]` `reject` Consent lives in the backed-up settings DataStore, so a restore carries it to a new phone — the epic places it there; recorded as a known limit in Design Notes.

## Design Notes

- **DEFAULT TAKEN (owner can change): the switch sits in the existing untitled general card** (EXPERIENCE.md: "a card with 'Weekly summary' and 'Share anonymous usage stats'"); no "Usage stats" header string is added, and "Weekly summary" stays hidden until Epic 6. The row is already in the approved design preview.
- **DEFAULT TAKEN: any choice of consent marks `analyticsAsked`** (Settings or the 5.11 onboarding step), so onboarding never asks again after a Settings choice.
- **DEFAULT TAKEN: analytics data is reset only when the user turns consent from on to off while the app runs** (a stored "no", so `asked` is true), never on the no-consent default an unreadable choice reads as (review fix 7): that default turns collection off but deletes nothing.
- **DEFAULT TAKEN: collection is disabled at Firebase start and fails closed** (review fix 4): Firebase persists the runtime flag, which wins over the manifest, so `FirebaseStartup` turns collection off synchronously right after `initialize()`, before `ready`. The gate only turns it on (consent, unlocked, started); it turns it off itself only when that first call failed or for a later "no", and retries a failed call (30 s, at most 5 times in a row, then at the next change). The manifest also denies analytics storage by default (`google_analytics_default_allow_analytics_storage`); the gate grants it with consent.
- **DEFAULT TAKEN: ad-services permissions merged by the Analytics SDK are removed like `AD_ID`** (no ads, permission allowlist unchanged).
- **DEFAULT TAKEN: only Math, WordUnscramble, MemorySequence and QrBarcode are logged as check types**; any other id (the test-only Placeholder, future types) is not logged until added to the enum.
- **DEFAULT TAKEN: events are sent when `Recorded` commits** — also for a session whose history write was abandoned for a new alarm; a crash before `Recorded` commits re-runs it once on restore. The engine only hands them to a non-suspending `TelemetryReporter` under its lock; the consent read and the Analytics calls run on `ApplicationScope` (review fix 1), so a new alarm never waits for them.
- **DEFAULT TAKEN: `play-services-ads-identifier` is excluded from Firebase Analytics** (review fix 8): the measurement code reads the ad id only when ad storage is granted, which the app never does; `proguard-rules.pro` tells R8 the class is absent on purpose.
- **DEFAULT TAKEN (owner can change): consent is per install** (review fix 5): the choice and `analyticsAsked` live in their own device-protected DataStore `datastore/usage_stats_consent.preferences_pb`, excluded from cloud backup, device transfer and full backup, so a restored phone starts with usage stats off and onboarding asks again.
- Without `google-services.json` (local builds, CI without the secret) the Firebase pieces bind to no-ops.

**pps-design Done checklist:**

- [x] Only `DESIGN.md` tokens are used: the existing `SwitchRow` in the existing untitled general `GroupCard`, no new UI, colour, radius or font size.
- [x] Light and Dark are checked with screenshots (`settings_usage_stats_{off,on}_{light,dark}`; the Snooze card baselines re-recorded with the new card).
- [x] Every colour pair is in the contrast table: the switch's accent / on-accent and outline-on-glass pairs as already used by `SwitchRow`.
- [x] Touch targets are ≥ 48 dp: the whole `SwitchRow` is the toggle target. No wake action.
- [x] It works at 200% font scale (`*_font200` screenshots) and with TalkBack: one toggleable row with role Switch, its label and its on/off state. No outcome glyphs.
- [x] Reduced motion: no new motion; the switch's own state animation follows the system.
- [x] Copy matches EXPERIENCE.md verbatim ("Share anonymous usage stats", the existing `settings_usage_stats` resource). No new strings.
- [x] State rows: off by default, on after a yes, stored at once without Save; a failed write leaves the switch at the stored value (logged); an unreadable choice shows off (logged).
- [x] "I'm up" / snooze: not applicable.
- [x] Screenshot tests are updated (Roborazzi, `UsageStatsSettingsTest` in Light, Dark and Light at 200%); the design preview already shows the row.

## Verification

**Commands** (all with `--no-daemon --max-workers=2`, JDK 17):
- `./gradlew :core:jvmTest --tests "*Telemetry*" --tests "*SessionEngine*"` -- expected: pass
- `./gradlew :data:testAndroidHostTest --tests "*ConsentStore*" --tests "*DataModuleTest*"` -- expected: pass
- `./gradlew :testing:allTests` and `:composeApp:testAndroidHostTest --tests "*Settings*"` (plus `*CopyRules*`) -- expected: pass
- `./gradlew :androidApp:testDebugUnitTest --tests "*Telemetry*" --tests "*Analytics*" --tests "*CrashReporting*" --tests "*Settings*"` -- expected: pass
- `./gradlew :androidApp:checkDependencyAllowlist :androidApp:checkPermissionAllowlist` -- expected: pass
- `./gradlew spotlessCheck detekt` -- expected: pass

## Auto Run Result

Status: done (fast mode; not pushed — the coordinator pushes, runs the independent review and CI, and merges)

**Summary:**
- **Core:** a `Telemetry` port with exactly three enum/int-only events and a `ConsentStore` (off by default; any choice marks the user as asked). `ConsentGatedTelemetry` drops events without consent and never queues them. The engine sends a real session's stats once its `Recorded` commits, capped at 2 s. Test sessions send nothing.
- **Android:**
  - Firebase Analytics is added behind an `AnalyticsSink` facade that has no user-id or user-property call.
  - The manifest has seven flags off, and `AD_ID`, the two ad-services permissions and the install-referrer permission are removed, so the permission allowlist is unchanged.
  - `AnalyticsConsentGate` turns collection on only with consent, the user unlocked and Firebase started (analytics storage only). It turns collection off at every start without consent, and resets the data when consent goes from on to off.
  - Everything is a no-op without `google-services.json`.
- **Settings:** the existing "Share anonymous usage stats" switch row is enabled and stores at once.

**Files:**
- `core/.../telemetry/` (new): `Telemetry.kt`, `Consent.kt`, `SessionTelemetry.kt`. `core/.../session/EngineTelemetry.kt` (new); `SessionEngine.kt` takes a `telemetry` parameter.
- `data/.../settings/DataStoreConsentStore.kt` (new); `DataModule.kt` binding.
- `testing/.../TelemetryFakes.kt` (new): `FakeTelemetry`, `FakeConsentStore`.
- `androidApp/build.gradle.kts`: `firebase-analytics`. `config/dependency-allowlist.txt`: 15 Analytics coordinates in a reviewed section. `AndroidManifest.xml`: flags and permission removals.
- `androidApp/.../android/telemetry/` (new): sink, Firebase telemetry, consent gate, Koin module. `FirebaseStartup.ready`. `YawnAndPawnApp.kt` wiring.
- `composeApp/.../settings/SettingsRoute.kt`, `SettingsViewModel.kt`, `ui/UiModule.kt`.
- Tests:
  - core: `TelemetryEventTest`, `ConsentGatedTelemetryTest`, `SessionEngineTelemetryTest`
  - data: `DataStoreConsentStoreTest`, `DataModuleTest`
  - testing: `TelemetryFakesTest`
  - composeApp: `SettingsViewModelTest`, `SettingsViewModelSequenceTest`
  - androidApp: `FirebaseTelemetryTest`, `AnalyticsConsentGateTest`, `AnalyticsManifestTest`, `CrashReportingTest`, `UsageStatsSettingsTest`
  - Screenshots: 6 new `settings_usage_stats_*` and 3 re-recorded `settings_snooze_*` (the new card shows on that screen).

**Review:** 7 findings: 1 patched (the low detekt fix), 0 deferred and 6 rejected, with reasons in the triage log. Follow-up review recommended: false (only one low was patched).

**Verification** (all with `--no-daemon --max-workers=2` on JDK 17), all passing:
- `:core:jvmTest` (`*Telemetry*`, `*SessionEngine*`)
- `:testing:allTests`
- `:data:testAndroidHostTest` (`*ConsentStore*`, `*DataModuleTest*`)
- `:composeApp:testAndroidHostTest` (`*Settings*`, `*CopyRules*`)
- `:androidApp:recordRoborazziDebug` for the Settings screenshots, then a verify run
- `:androidApp:testDebugUnitTest`:
  - telemetry, analytics and crash tests: `CrashReportingTest` 13, `AnalyticsConsentGateTest` 12, `AnalyticsManifestTest` 4, `FirebaseTelemetryTest` 4
  - Settings tests: `SnoozeSettings*` 26, `UsageStatsSettingsTest` 7
- `:androidApp:checkDependencyAllowlist` and `:androidApp:checkPermissionAllowlist`
- `spotlessCheck detekt`

Not run here because of the run's constraints: `qualityGate` and Kover.

**Residual risks:**
- The real Firebase calls have not run, because there is no `google-services.json` here. The DebugView human-verify check is still open.
- The ad libraries that come with Analytics need owner review and a Data safety declaration.
- Consent is backed up together with the other settings.

## Review (fast mode)

Independent review (1 reviewer, two lenses) of head `aea4b9f3`: 10 findings. Fixed here: 1, 4, 5, 6, 7, 8, 9, 10.

1. **HIGH, fixed: telemetry ran inside the engine's lock.** A new core port `TelemetryReporter` (`report(events)`, never suspends) with `None` and `Launching(telemetry, scope, logger)`, as Story 5.3's `LockedTestReporter.Launching`. The engine's last parameter is `telemetry: TelemetryReporter = TelemetryReporter.None`. Under the lock `EngineTelemetry` only computes the pure `sessionTelemetry` and calls `report`; a throwing reporter is logged by type. `Launching` sends the events in order on the app scope, and logs a throwing telemetry by type and a cancelled one as "cancelled". The app wires `TelemetryReporter.Launching(telemetry = get<Telemetry>(), scope = get<ApplicationScope>(), logger = get())`. Test: a telemetry suspended on a `CompletableDeferred` while a concurrent `dispatch(AlarmFired)` reaches Ringing with its `SoundAt` effect run before the deferred completes.
2. **MEDIUM: conflicts with 5.3** (`SessionEngine`, `YawnAndPawnApp`). Pending rebase after the 5.3 / 5.1 merge.
3. **MEDIUM: conflicts with 5.1** (Settings rows, `SettingsViewModel`, `UiModule`, the `settings_snooze_*` baselines). Pending rebase after the 5.3 / 5.1 merge.
4. **MEDIUM, fixed: collection could start before the gate turned it off.** `FirebaseStartup` turns collection off through the `AnalyticsSink` synchronously right after `initialize()` and before `ready` (fail closed). It exposes `startedCollectionOff` through a small `FirebaseState` interface the gate follows. The gate only turns collection on. It turns it off itself only when that first call failed, or for a later "no". A failed call is retried after 30 s, at most 5 times in a row, then at the next change. The manifest adds `google_analytics_default_allow_analytics_storage=false`; the gate grants it with consent. Tests: call order (init, then off with `ready` still false), a failing off is logged and Firebase still starts, the gate calls nothing when the start turned collection off, a failed off is retried after the delay, and the retry cap.
5. **MEDIUM, fixed: a restore turned stats on on a new phone.** DEFAULT TAKEN (owner can change): consent is per install. Consent and `asked` moved to their own device-protected, non-backed-up DataStore `datastore/usage_stats_consent.preferences_pb` (`ConsentDataStore`, built like `PriceCacheDataStore`; a corrupt file reads as no consent). It is excluded in every section of `data_extraction_rules.xml` and `backup_rules.xml`. Tests: `BackupRulesTest` entries, `BackupRulesCoverageTest` (the morning writes the file, and a simulated restore that keeps only the cloud-backup includes reads consent off and not asked), `DataStoreConsentStoreTest` (path, corrupt file) and `DataModuleTest` (the settings DataStore holds no consent key).
6. **MEDIUM, fixed: the production wiring was never run.** `FirebaseConfiguration(present)` is bound in Koin (`reliabilityModule`) and used by `telemetryModule` and the `FirebaseStartup` bindings, so a test can run the app as a configured build. `TelemetryWiringTest` runs the real app start with a recording sink: collection false is recorded once Firebase is ready, and a yes then turns it on. A real session completed with consent on (`WakeApp`) gives the 3 log calls `session_outcome`, `snooze_count` and `check_type`.
7. **LOW, fixed: a passing read error reset the analytics data.** Only the user's own "no" (consent false with `asked` true, after consent was on in this process) resets. The no-consent default of an unreadable choice only turns collection off. Tests: true, then the error default, then true gives on, off, on and no reset; only a stored "no" resets.
8. **LOW, fixed: `play-services-ads-identifier` on the classpath.** It is excluded from `firebase-analytics` in `androidApp/build.gradle.kts`, removed from `config/dependency-allowlist.txt`, and `checkDependencyAllowlist` passes, so it no longer resolves. The measurement SDK (23.2.0, checked with `javap`) reads the ad id only when ad storage is granted, which the app never does. `proguard-rules.pro` adds `-dontwarn com.google.android.gms.ads.identifier.**` so R8 accepts the missing class. Assertion: `AnalyticsManifestTest` checks the class is not on the classpath, the exclude is in the build file and the allowlist has no entry. Not run here: the release R8 build (`bundleRelease`, CI) and a real-Firebase measurement check (DebugView, Story 5.13).
9. **LOW, fixed: the timeout test proved little.** The 2 s timeout and its test are gone with item 1, replaced by item 1's test.
10. **LOW, fixed:** the 5.13 DebugView checklist line (only the 3 custom events plus the approved automatic events; with consent on, Crashlytics also logs `app_exception`, to note for Data safety) is in `epic-5-context.md` under "Notes from implemented stories".

11. **CI failure on `aea4b9f3`, fixed: `SessionLockGuardScanTest`.** `SetAnalyticsConsent` wrote the consent outside the session lock guard. It now takes the `SessionLockGuard` and stores the choice inside `whenIdle` like every user-facing write (Story 2.6). During a session it stores nothing and returns `SessionActive`; Settings and onboarding are behind the session lock then anyway. The scan's expected guarded writers list `SetAnalyticsConsent`. Tests: `SessionLockGuardScanTest` (4), a new `ConsentGatedTelemetryTest` case (no write and `SessionActive` while the lock holds), and `TelemetryWiringTest` loads the stored session before the yes, as the app start does.

**Verification** (all with `--no-daemon --max-workers=2`, JDK 17), all passing:
- `:data:testAndroidHostTest` (`*SessionLockGuardScanTest*`, `*ConsentStore*`): `SessionLockGuardScanTest` 4, `DataStoreConsentStoreTest` 9 (item 11).
- `:composeApp:testAndroidHostTest` (`com.yawnandpawn.app.ui.settings.*`) and `:androidApp:testDebugUnitTest` (`TelemetryWiringTest`, `UsageStatsSettingsTest` 7) after item 11.
- `:core:jvmTest` (`*Telemetry*`, `*SessionEngine*`): 85 tests, including `SessionEngineTelemetryTest` 10; `ConsentGatedTelemetryTest` 6 after item 11.
- `:testing:allTests`: 73 tests.
- `:data:testAndroidHostTest` (`*ConsentStore*`, `*DataModuleTest*`): 16 tests (`DataStoreConsentStoreTest` 9, `DataModuleTest` 7).
- `:androidApp:testDebugUnitTest` (`*Telemetry*`, `*Analytics*`, `*CrashReporting*`, `*BackupRules*`, `*UsageStatsSettingsTest*`, `*SessionWiringTest*`): 65 tests, among them `AnalyticsConsentGateTest` 17, `CrashReportingTest` 15, `TelemetryWiringTest` 2, `BackupRulesCoverageTest` 4 and `BackupRulesTest` 6.
- `:androidApp:checkDependencyAllowlist` and `:androidApp:checkPermissionAllowlist`.
- `spotlessApply`, then `spotlessCheck detekt`.
