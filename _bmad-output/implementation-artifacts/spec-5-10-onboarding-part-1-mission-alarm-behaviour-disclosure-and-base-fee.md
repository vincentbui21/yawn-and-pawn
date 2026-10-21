---
title: 'Story 5.10: Onboarding, part 1: mission, alarm behaviour disclosure and base fee'
type: 'feature'
created: '2026-10-20'
status: 'done'
baseline_revision: '20f06661cbbb6d7aa452616f02530878da87e059'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-5-context.md'
  - '{project-root}/.claude/skills/pps-design/SKILL.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-4-5-snooze-settings-base-fee-and-max-snoozes.md'
warnings: ['oversized']
deferred:
  - summary: >-
      When Story 5.9 merges, mark its deferred entry "Onboarding opens after Delete all data (Story 5.10)" resolved, keep
      `pendingDeletion(backStack)` before `onboardingGate()` in AppNavHost, and let `resetToHome()` leave an
      `[Onboarding]` stack alone.
    evidence: |-
      5.9 (origin/story/5-9-delete-all-data) edits the same lines of AppNavHost; the onboarding keys live in the settings
      DataStore that 5.9's clearSettings() clears, so the gate flips to onboarding by itself (OnboardingFlowTest simulates
      it with clear()). Nothing on main runs the real DeleteAllData path yet.
    location: >-
      composeApp/src/commonMain/kotlin/com/yawnandpawn/app/ui/nav/AppNavHost.kt
    severity: low
  - summary: >-
      DebugFire's real-mode "fire now" saves through SaveAlarm, which refuses with DisclosureRequired until onboarding
      step 2 is passed on that install.
    evidence: |-
      androidApp/src/debug/.../fire/DebugFire.kt saveAlarm(syntheticDraft()); debug only. Device runs (Story 5.13) go
      through onboarding first, or the hook could accept the disclosure itself.
    location: >-
      androidApp/src/debug/kotlin/com/yawnandpawn/app/debug/fire/DebugFire.kt
    severity: low
  - summary: >-
      DuplicateAlarm stores and arms an alarm without the disclosure check.
    evidence: |-
      Pre-existing use case, not reachable before onboarding (Home is gated) and not used by the app's Duplicate (which
      prefills the editor and saves through SaveAlarm).
    location: >-
      core/src/commonMain/kotlin/com/yawnandpawn/app/core/alarm/AlarmUseCases.kt
    severity: low
  - summary: >-
      A failed "I understand" or "Continue" write shows no message (stays on the step, logged). Owner may want a
      message.
    evidence: |-
      DEFAULT TAKEN per the spec matrix; no EXPERIENCE.md string exists for it.
    location: >-
      composeApp/src/commonMain/kotlin/com/yawnandpawn/app/ui/onboarding/OnboardingViewModel.kt
    severity: low
  - summary: >-
      For an upgraded user with alarms, a lower fee chosen in onboarding is stored as pending; after the ViewModel is
      recreated the step shows the live (higher) tier, without Settings' "Saved. Takes effect after ..." note.
    evidence: |-
      The `chosen` overlay is in memory only; a first run has no alarm, so no lock applies.
    location: >-
      composeApp/src/commonMain/kotlin/com/yawnandpawn/app/ui/onboarding/OnboardingViewModel.kt
    severity: low
  - summary: >-
      Test gaps: Home never composing before the gate resolves; a session starting mid-onboarding through AppNavHost;
      the gate's READ_GATE catch; the fee overlay while a save is suspended.
    evidence: |-
      Covered at helper / ViewModel level only (OnboardingNavigationTest, OnboardingViewModelTest).
    severity: low
  - summary: >-
      Owner sign-off: step 1's heading (read first by TalkBack) is the app name per the approved round 3 design; the
      epic calls the mission sentence the headline content.
    evidence: |-
      DEFAULT TAKEN (owner can change). OnboardingSemanticsTest asserts "Yawn & Pawn" first.
    severity: low
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

### 2026-10-21 — Review pass (fast mode: one combined reviewer, blind / edge-case / verification-gap / intent lenses)
- verdicts: 35 findings — high 2, medium 3, low 24, false 4, maybe-false 2
- findings:
  - `[high]` `[patch]` Double tap on "Let's set it up" (or a tap on the outgoing step during the slide) queued "I understand" without the disclosure being shown; same race on "I understand" skipped the fee step — `OnboardingIntent.Next` now carries the step it was tapped on (`Next(from)`), the ViewModel ignores a tap from a step already left; preview reducer follows; two ViewModel tests added.
  - `[low]` `[patch]` − / + saved the fee when tapped on the outgoing step 3 during a Back slide — guarded to `step == BaseFee`; test added.
  - `[low]` `[defer]` DebugFire's real-mode "fire now" saves through `SaveAlarm`, so on a fresh debug install it returns `DisclosureRequired` until onboarding step 2 is passed — debug only; device runs go through onboarding first (deferred, 5.13 note).
  - `[low]` `[defer]` `DuplicateAlarm` stores an alarm with no consent check — not reachable before onboarding (Home is gated) and unused by the app; pre-existing use case.
  - `[medium]` `[patch]` `priceCatalog.observe()` had no `catch`: a throwing cache would leave onboarding blank — caught, logged (`load prices`), reads as empty (USD + approximate); test added.
  - `[low]` `[reject]` No tick input, so a price that expires (30 days) while the user sits on step 3 is not re-rendered — negligible in practice; the fix adds a flow input.
  - `[low]` `[defer]` A failed "I understand" / "Continue" write shows nothing (stays, logged) — spec behaviour; owner may want a message (DEFAULT TAKEN).
  - `[low]` `[reject]` The gate's terminal `catch` stops following the store after a non-IO bug — only a programming error reaches it; the store already retries IO errors.
  - `[medium]` `[patch]` The gate started at Loading on every Activity recreation, flashing the empty screen over Home on rotation or theme change — the gate now starts from the last value it showed (`rememberSaveable`), so only a cold start shows Loading.
  - `[low]` `[reject]` First launch shows the flat `bg` then the gradient before step 1 — two frames, cosmetic; fixing it means restructuring the gate.
  - `[medium]` `[patch]` A passing IOException made the store emit a fresh progress, flipping a finished user to onboarding and clearing their stack — `DataStoreOnboardingRepository.observe` now emits the fresh progress only before the first good read; after it the last value stays; data test added.
  - `[low]` `[defer]` The `chosen` fee overlay is in memory: an upgraded user's pending lower fee shows as the live (higher) tier after the ViewModel is recreated — only users with alarms inside a lock window, and the lock note is shown.
  - `[low]` `[reject]` Queued writes are dropped when a session starts — they would be refused with `SessionActive` anyway; the stored step is at most one step behind.
  - `[maybe-false]` `[reject]` The write queue dies if a use case throws — the use cases return `Outcome`; settled by a use case that throws, none found. If true it would be low.
  - `[low]` `[reject]` System Back is swallowed (no effect) while "I understand" / "Continue" is being stored — harmless.
  - `[false]` `[reject]` After 5.9 merges, onboarding shows while the deletion still runs — 5.9's `pendingDeletion` gate sits before this gate and shows the neutral screen while the deletion is pending, so onboarding shows only after it completes.
  - `[low]` `[defer]` 5.9's `resetToHome()` does not know `Route.Onboarding` — harmless (shown stack follows the gate), but merge with a check that `pendingDeletion` stays before the gate.
  - `[false]` `[reject]` Claim "resetToHome leaves [Alarms] underneath" is inaccurate — fix is a spec edit; the end state matches.
  - `[false]` `[reject]` Claim about read errors for finished users — fixed by the medium patch above.
  - `[false]` `[reject]` Claim "shown tier = requested ?: stored" omits `chosen` — spec wording only.
  - `[high]` `[patch]` Claim "no consent screen skip" broken by the double tap — same root cause as the first row.
  - `[low]` `[patch]` Verification gap: system Back not tested — `OnboardingFlowTest` now presses system Back on step 2 and checks the mission and the stored step. Back on step 1 is left to the system (handler disabled), not asserted (Robolectric's API 31+ root-activity Back moves the task instead of finishing).
  - `[low]` `[defer]` Verification gap: no test that Home never composes before the gate resolves.
  - `[low]` `[defer]` Verification gap: session start during onboarding tested at the stack-helper level only.
  - `[low]` `[defer]` Verification gap: gate `READ_GATE` catch path untested.
  - `[low]` `[defer]` Verification gap: fee overlay while a save is suspended, and newer-request precedence, not tested with a suspending fake.
  - `[low]` `[patch]` Verification gap: busy / double tap — covered by the two new `Next(from)` tests.
  - `[maybe-false]` `[reject]` Verification gap: second refresh after Back then "I understand" — `goTo`/Accept both call `refreshPrices`; a weak count assertion only.
  - `[low]` `[defer]` Verification gap: real `DeleteAllData` → onboarding not run (5.9 not on main); simulated with `clear()`.
  - `[low]` `[reject]` Verification gap: dots not tappable unasserted — `ProgressDots` is pre-existing and has no click action.
  - `[low]` `[defer]` Intent: the epic calls the mission sentence the step-1 headline; the approved round 3 design makes the app name the heading read first — DEFAULT TAKEN (follows the approved design), owner can change.
  - `[low]` `[patch]` Intent: the `pps-design` Done checklist was missing from the story file — added below.
  - `[low]` `[defer]` Intent: `./gradlew qualityGate` not run — the run's constraints allow only targeted tasks plus spotless and detekt; CI runs it on the PR.
  - `[low]` `[defer]` Intent: the Delete-all-data item is delivered by the store design, verified with `clear()`; mark 5.9's deferred entry resolved when both are on main.
  - `[low]` `[reject]` Intent: the pending-fee note is not shown for upgraded users — beyond this story's AC (no alarm exists on a real first run).

## Design Notes

- **DEFAULT TAKEN (owner can change):** until Story 5.11 builds steps 4 to 8, "Continue" on step 3 completes onboarding and opens Home; the dots still show 8 steps.
- **DEFAULT TAKEN (owner can change):** `DisclosureRequired` applies to every `SaveAlarm` (new and edited alarms); an alarm already stored before this build is untouched and still rings, and the gate sends its owner through steps 1 to 3 on the next open.
- **DEFAULT TAKEN (owner can change):** pressing "I understand" again keeps the first acceptance time.
- **Gate as a route:** `Route.Onboarding` is the whole stack while required (like `Route.SessionInProgress`), so its ViewModel is entry-scoped: a second onboarding after "Delete all data" starts fresh. The display follows `required` in the same composition; a `SideEffect` makes the real stack match. Once completed, an onboarding stack becomes `[Alarms]`.
- **Delete all data (5.9 deferred item):** the keys live in the settings DataStore, which 5.9 clears with `clear()`, so the gate flips to onboarding by itself; `resetToHome()` leaves `[Alarms]` underneath, shown once onboarding completes. When 5.9 merges, mark its deferred entry resolved.
- **Fee display:** shown tier = requested tier ?: stored live tier; under a commitment lock (an upgraded user with alarms) lowering is stored as pending and the requested value stays shown, like Settings.

## pps-design Done checklist

- [x] Only tokens from `DESIGN.md` used (no raw hex, no new radii, no new font sizes): the round 3 screens are reused; the only UI changes are string prices and a `traversalIndex`.
- [x] Light, Dark checked with screenshots (`OnboardingScreenshotTest`: mission, disclosure, base fee loaded and approximate, Light / Dark / Light 200 %); Sunrise not relevant (not a wake screen).
- [x] Every colour pair used is in the `DESIGN.md` contrast table (no new pair; the round 3 pairs are unchanged).
- [x] Touch targets ≥ 48 dp (`OnboardingSemanticsTest`: Back, "I understand", stepper); no wake action here.
- [x] Works at 200 % font scale and with TalkBack: each step's headline is a heading read first (`traversalIndex`), the dots read "Step N of 8", every control is labelled with a role; no outcome glyphs on these steps.
- [x] Reduced-motion path works: the step slide is the shared `subScreenTransition` (instant with animations off).
- [x] Copy matches `EXPERIENCE.md > Voice and Tone`: no new string; the existing resources are used verbatim; `CopyRulesTest` unaffected.
- [x] Every state row for this surface is handled: prices never loaded (USD + "Approximate. Your local price shows when you're online.", onboarding continues).
- [x] "I'm up" / snooze: not applicable (no wake screen).
- [x] Previews for each state exist (design preview round 3 items, now on the string-price contract) and screenshot tests were added (Roborazzi).

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

## Auto Run Result

Status: done (fast mode, one combined self-review). Rebased onto main 2d35e706 (5.6 and 5.8 merged); 5.9 (PR #72) not merged yet.

**Summary:** first launch (and, once 5.9 lands, "Delete all data") opens onboarding instead of Home: `Route.Onboarding` replaces the stack behind the session lock until `onboarding_completed` is stored. Steps 1 to 3 of the approved round 3 screens are wired to `OnboardingViewModel`: mission, the alarm behaviour disclosure ("I understand" stores `disclosure_accepted_at`), and the base fee (Epic 4 stepper and ladder with Play prices or USD + approximate note, `PriceCatalog.refresh()` on open, saved through `SetBaseFee`). "Continue" on step 3 completes onboarding until 5.11 adds steps 4 to 8. The step is stored on every change, so a process death resumes there. `SaveAlarm` returns `DomainError.DisclosureRequired` until the disclosure is accepted.

**Files:**
- `core/.../onboarding/Onboarding.kt` -- `OnboardingProgress`, `OnboardingRepository`, `AcceptDisclosure` (keeps the first time), `SaveOnboardingStep`, `CompleteOnboarding` (refused before consent); all inside the session lock.
- `core/.../alarm/AlarmUseCases.kt`, `error/DomainError.kt`, `log/Logger.kt` -- `SaveAlarm` consent check (fail closed), `DisclosureRequired`.
- `data/.../settings/DataStoreOnboardingRepository.kt`, `SettingsDataStore.kt`, `DataModule.kt` -- keys `onboarding_completed`, `onboarding_step`, `disclosure_accepted_at` in the backed-up settings DataStore; a read error before the first read reads as fresh, after it keeps the last value.
- `composeApp/.../ui/onboarding/OnboardingViewModel.kt`, `OnboardingRoute.kt` -- steps 1 to 3, one write queue, fee overlay, Back handling.
- `composeApp/.../ui/onboarding/OnboardingContract.kt`, `OnboardingScreen.kt`, `OnboardingSteps.kt` -- string prices (`baseFeeTier`, `baseFee`, `feeLadder`), `Next(from)`, headline read first (`traversalIndex`).
- `composeApp/.../ui/nav/OnboardingGate.kt`, `Route.kt`, `TabNavigation.kt`, `AppNavHost.kt`, `ui/UiModule.kt` -- the gate, `applyOnboarding`, `shownStack`, Koin.
- `androidApp/.../YawnAndPawnApp.kt` -- use case bindings; `src/debug/.../preview/*` -- the preview on the new contract (pixels unchanged).
- `testing/.../OnboardingFakes.kt`, `SchedulerFakes.kt`; `androidApp/src/test/.../TestYawnAndPawnApp.kt` (a completed onboarding for every other app test); `FallbackTalkBackDeviceTest.kt` (accepts the disclosure before saving).
- Tests: `OnboardingTest`, `SaveAlarmDisclosureTest`, `DataStoreOnboardingRepositoryTest`, `OnboardingViewModelTest`, `OnboardingNavigationTest`, `OnboardingFlowTest`, `OnboardingSemanticsTest`, `OnboardingScreenshotTest` (12 new Roborazzi baselines).

**Review:** 35 findings (high 2, medium 3, low 24, false 4, maybe-false 2). Patched: high 2 (one root cause: the double-tap consent skip), medium 3 (price cache catch, gate flash on recreation, read error resetting a finished user), low 5. Deferred: 7 (frontmatter). Rejected: the rest, with reasons in the triage log.

**Follow-up review recommended:** true. Patched high: the `Next(from)` guard is verified in the ViewModel and the preview reducer, but no UI-level test double-taps the real button during the slide.

**Verification (Gradle `--no-daemon --max-workers=2`, JDK 17):**
- `:core:jvmTest` (onboarding, alarm, log packages), `:data:testAndroidHostTest` (DataStoreOnboardingRepositoryTest 8, DataModuleTest 7, SessionLockGuardScanTest), `:composeApp:testAndroidHostTest` (onboarding 18, nav incl. OnboardingNavigationTest 5, CopyRulesTest 15) -- pass.
- `:androidApp:recordRoborazziDebug` for `OnboardingScreenshotTest` (12 images, checked by eye), then `:androidApp:testDebugUnitTest` verify: OnboardingFlowTest 4, OnboardingScreenshotTest 12, OnboardingSemanticsTest 4, PreviewScreenshotTest 95, PreviewSemanticsTest 190, PreviewEditor* 519, PreviewMenuTest 8, Round3TapThroughTest 8, MainActivityTest 20, UsageStatsSettingsTest 7, SessionLockScreenTest 7, SnoozeSettingsRouteTest, HomeInsetsTest, ThemeOverrideTest -- pass.
- `spotlessCheck detekt` -- pass.
- Not run (run constraints): the full `qualityGate`, Kover, lint, the instrumented tests (`FallbackTalkBackDeviceTest`, `MainActivitySmokeTest`, which still finds "Yawn & Pawn" on step 1).

**Residual risks:** 5.9 merge order in `AppNavHost` (deferred item 1); an installed debug build with alarms opens onboarding once after upgrading; the full `qualityGate` runs in CI only.
