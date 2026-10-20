---
title: 'Story 5.10: Onboarding, part 1: mission, alarm behaviour disclosure and base fee'
type: 'feature'
created: '2026-10-20'
status: 'ready-for-dev'
baseline_revision: 'a72467587c0ea0e7ba0dbcc0a4bbb84db1cfe4a2'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-5-context.md'
  - '{project-root}/.claude/skills/pps-design/SKILL.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-4-5-snooze-settings-base-fee-and-max-snoozes.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** a new user (first launch, or after "Delete all data") lands on Home without being told how the alarm behaves, without consenting to it, and without choosing a snooze fee; nothing stops an alarm being saved before consent (FR-ONB-1, FR-ONB-5, FR-MSG-1, FR-SET-1).

**Approach:** store onboarding progress (`onboardingCompleted`, the current step, `disclosureAcceptedAt`) in the settings DataStore behind a core `OnboardingRepository` port; gate the app on it with a `Route.Onboarding` that replaces the back stack like the session lock does; wire the existing, owner-approved round 3 `OnboardingScreen` to a real `OnboardingViewModel` for steps 1 to 3; make `SaveAlarm` refuse with `DomainError.DisclosureRequired` until the disclosure is accepted.

## Boundaries & Constraints

**Always:** reuse the round 3 `OnboardingScreen` / `OnboardingSteps` layout and existing string resources verbatim (no new strings); prices are Play's `formattedPrice` via `SnoozePrices.of`, else USD with "Approximate. Your local price shows when you're online."; the fee is saved only through `SetBaseFee`; the session lock still wins over onboarding (a ring or snooze is never held up); core stays pure; ports return `Outcome`; the step survives process death (stored on every change); Back goes to the previous step, and on step 1 is left to the system (the app closes).

**Never:** no steps 4 to 8 behaviour (Stories 5.11, 5.12: their intents stay ignored); no new tokens, components or strings; no change to `GlobalSettingsRepository`/`GlobalSettings`; no consent screen skip; no edit to `deferred-work.md` (5.9's entry lives on its unmerged branch).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| First launch | settings DataStore empty | onboarding step 1, no nav capsule | store read error logged, reads as not completed |
| Restored from backup | `onboarding_completed` true | Home directly | — |
| Process death on step 3 | stored step `BaseFee` | reopens on step 3 | unknown stored step name → step 1 |
| "I understand" | step 2 | `disclosureAcceptedAt` = now (first acceptance kept), step 3 | write failure → stays on step 2, logged |
| SaveAlarm before consent | `disclosureAcceptedAt` null | `DisclosureRequired`, nothing stored or armed | read failure → `StorageFailure` (fail closed) |
| Base fee step offline | empty price cache, refresh fails | USD ladder + approximate note, "Continue" works | refresh failure logged only |
| Raise/lower fee | tier 1..10 | `SetBaseFee(tier ± 1)`, value shown at once | failure → stored value comes back, logged |
| "Continue" on step 3 | — | `onboardingCompleted` true, Home opens (until 5.11 adds steps 4 to 8) | write failure → stays, logged |
| Delete all data | settings DataStore `clear()` (Story 5.9) | gate flips to onboarding step 1 | — |
| Session starts during onboarding | ring/snooze | "Alarm in progress" shows; onboarding returns after | — |

</intent-contract>

## Code Map

- `composeApp/.../ui/onboarding/OnboardingContract.kt` -- `OnboardingStep` (8 steps), `OnboardingUiState` (fee fields today `Money`-based for the preview), `OnboardingIntent`. Change fee fields to `baseFeeTier: Int`, `baseFee: String`, `feeLadder: List<String>`, `pricesApproximate`.
- `composeApp/.../ui/onboarding/OnboardingScreen.kt` / `OnboardingSteps.kt` -- the stateless round 3 screens (dots on glass capsule, back arrow from step 2, actions in their own bottom area); `BaseFeeStep` renders the stepper and notes; `StepHeader`/`MissionStep` headlines. Reuse `feeLadderText` from `ui/settings/SnoozePanes.kt`.
- `composeApp/.../ui/settings/SnoozePrices.kt` -- `SnoozePrices.of(tier, maxSnoozes, snapshot, now, formatter)` → `FeePrices` (Play strings or USD + approximate).
- `composeApp/.../ui/settings/SettingsViewModel.kt` -- pattern for requested-value overlay and logging (`LogEvent.OperationFailed`).
- `composeApp/.../ui/nav/AppNavHost.kt` (shared, 5.8/5.9 edit it) -- add the onboarding gate after the session lock; `Route.kt` + `TabNavigation.kt` (`Route.tab` exhaustive `when`); `SessionLockState.kt` `RestoringScreen` for the loading frame. 5.9 (`origin/story/5-9-delete-all-data`) adds `pendingDeletion(backStack)` and `resetToHome()` right there; the gate must sit after it and does not depend on it.
- `composeApp/.../ui/UiModule.kt` -- add `OnboardingViewModel`.
- `core/.../config/ConfigPorts.kt`, `core/.../telemetry/Consent.kt` -- port/use-case style to mirror. New `core/.../onboarding/Onboarding.kt`.
- `core/.../alarm/AlarmUseCases.kt` `SaveAlarm` (line ~102) -- add the disclosure check inside `sessionLock.whenIdle`, both new and edit paths; constructor callers: `YawnAndPawnApp.kt:171`, `testing/.../SchedulerFakes.kt:182`, core tests `AlarmSchedulingTest`, `AlarmUseCasesTest`, `CheckConfigUseCasesTest`, `SaveAlarmLockTest`, `SaveAlarmMovedRingTest`.
- `core/.../error/DomainError.kt`, `core/.../log/Logger.kt` `diagnostic()` -- new `DisclosureRequired` arm.
- `data/.../settings/DataStoreTestAlarmStore.kt`, `DataStoreConsentStore.kt` -- DataStore adapter pattern; `data/.../DataModule.kt` binds on `SettingsDataStore().store` (cleared by 5.9's `clearSettings`, backed up by `data_extraction_rules.xml`).
- `androidApp/.../YawnAndPawnApp.kt` -- core use case factories (`SaveAlarm`, `SetBaseFee`).
- `androidApp/src/test/.../TestYawnAndPawnApp.kt` `testAppModule` -- bind a completed `FakeOnboardingRepository`, so every existing app test still opens on Home.
- `androidApp/src/debug/.../preview/PreviewSetupSamples.kt`, `TapThroughOnboarding.kt` -- preview builds `OnboardingUiState` with `Money`; adapt to the new fee fields (pixels unchanged).
- `androidApp/src/test/.../ui/SnoozeSettingsScreenshotTest.kt`, `UsageStatsSettingsTest.kt` -- Roborazzi / real-app test patterns (`withScreen`, `screenshotOptions`, app subclass with `overrideModules`).

## Tasks & Acceptance

**Execution:**
- `core/src/commonMain/.../onboarding/Onboarding.kt` -- `OnboardingProgress(completed, step: String?, disclosureAcceptedAt: Instant?)`, port `OnboardingRepository` (`observe`, `get`, `setStep`, `setDisclosureAccepted`, `setCompleted`), use cases `AcceptDisclosure(repo, clock, sessionLock)` (keeps the first time), `CompleteOnboarding`, `SaveOnboardingStep` -- the consent and progress rules in core.
- `core/.../error/DomainError.kt`, `core/.../log/Logger.kt` -- `DisclosureRequired` + diagnostic.
- `core/.../alarm/AlarmUseCases.kt` -- `SaveAlarm` takes `OnboardingRepository`, returns `DisclosureRequired` while unset; update all constructor callers.
- `testing/.../OnboardingFakes.kt` -- `FakeOnboardingRepository` (+ `OnboardingProgress` presets), failure switches.
- `data/.../settings/DataStoreOnboardingRepository.kt` + `DataModule.kt` binding -- keys `onboarding_completed`, `onboarding_step`, `disclosure_accepted_at` in the settings DataStore.
- `composeApp/.../ui/onboarding/OnboardingViewModel.kt` + `OnboardingRoute.kt` -- state from stored progress, settings and prices; step persistence; `PriceCatalog.refresh()` when the base fee step opens; fee via `SetBaseFee` with a requested-tier overlay; completion; `NavigationBackHandler` from step 2.
- `composeApp/.../ui/onboarding/OnboardingContract.kt`, `OnboardingSteps.kt`, `OnboardingScreen.kt` -- string-based fee fields; headline first in TalkBack order (`traversalIndex`).
- `composeApp/.../ui/nav/Route.kt`, `TabNavigation.kt`, new `OnboardingGate.kt`, `AppNavHost.kt` -- `Route.Onboarding`, `applyOnboarding(required)`, `onboardingGate()` composable; shown stack follows the gate in the same composition.
- `composeApp/.../ui/UiModule.kt`, `androidApp/.../YawnAndPawnApp.kt` -- Koin.
- `androidApp/src/debug/.../preview/*` -- adapt the preview to the new fee fields.
- Tests: core (`OnboardingTest`, SaveAlarm disclosure), data (`DataStoreOnboardingRepositoryTest`, incl. `clear()` resets), composeApp (`OnboardingViewModelTest`, `OnboardingNavigationTest`), androidApp (`OnboardingFlowTest` real app, `OnboardingScreenshotTest` steps 1–3 + approximate in Light/Dark/200 %, `OnboardingSemanticsTest`).

**Acceptance Criteria:**
- Given `onboardingCompleted` false, when the app opens, then the Onboarding route shows instead of Home with "Step 1 of 8" dots and no nav capsule; Back from step 2/3 returns one step; step 1 has no in-app Back.
- Given step 2, when "I understand" is tapped, then `disclosureAcceptedAt` is stored and `SaveAlarm` stops returning `DisclosureRequired`.
- Given step 3, when it opens, then `PriceCatalog.refresh()` is attempted and the stepper/ladder show Play prices (or USD + approximate note), and "Continue" completes onboarding and opens Home.
- Given steps 1 to 3, when Roborazzi and semantic tests run, then screenshots exist (base fee loaded and approximate) in Light, Dark and 200 %, each step's headline is a heading first in traversal order, and targets are ≥ 48 dp.

## Spec Change Log

## Review Triage Log

## Design Notes

- **DEFAULT TAKEN (owner can change):** until Story 5.11 builds steps 4 to 8, "Continue" on step 3 completes onboarding and opens Home; the dots still show 8 steps.
- **DEFAULT TAKEN (owner can change):** `DisclosureRequired` applies to every `SaveAlarm` (new and edited alarms); an alarm already stored before this build is untouched and still rings, and the gate sends its owner through steps 1 to 3 on the next open.
- **DEFAULT TAKEN (owner can change):** pressing "I understand" again keeps the first acceptance time.
- **Gate as a route:** `Route.Onboarding` is the whole stack while required (like `Route.SessionInProgress`), so its ViewModel is entry-scoped: a second onboarding after "Delete all data" starts fresh. The display follows `required` in the same composition; a `SideEffect` makes the real stack match. Once completed, an onboarding stack becomes `[Alarms]`.
- **Delete all data (5.9 deferred item):** the keys live in the settings DataStore, which 5.9 clears with `clear()`, so the gate flips to onboarding by itself; `resetToHome()` leaves `[Alarms]` underneath, shown once onboarding completes. When 5.9 merges, mark its deferred entry resolved.
- **Fee display:** shown tier = requested tier ?: stored live tier; under a commitment lock (an upgraded user with alarms) lowering is stored as pending and the requested value stays shown, like Settings.

## Verification

**Commands (Gradle with `--no-daemon --max-workers=2`):**
- `:core:jvmTest` (or the core host test task) filtered to `*Onboarding*`, `*SaveAlarm*`, `*AlarmUseCases*` -- pass.
- `:data:testAndroidHostTest --tests "*Onboarding*"` -- pass.
- `:composeApp` host tests `--tests "*onboarding*" --tests "*nav*"` -- pass.
- `:androidApp:testDebugUnitTest --tests "*Onboarding*" --tests "*Preview*" --tests "*MainActivityTest*"` with Roborazzi record then verify -- pass.
- `spotlessCheck detekt` -- pass.

**Run constraints (company PC shared with two other build lanes):**
- JDK 17: `JAVA_HOME=C:/Users/BuiTua/AppData/Local/Programs/jdk17/jdk-17.0.20.1+1`. No admin rights; nothing installed.
- Write all code first; Gradle only at the end, always `--no-daemon --max-workers=2`, targeted tasks and test filters only (never the full `qualityGate`; quote filters so PowerShell does not expand them). If a JVM dies on memory, retry once later.
- Never use the phone or adb. Never commit, push or open a PR (the orchestrator commits). The repo is public: no secrets, personal data or device serials.
