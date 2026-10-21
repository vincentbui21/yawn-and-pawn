---
title: 'Story 5.12: Onboarding test alarm on a locked screen'
type: 'feature'
created: '2026-10-21'
status: 'ready-for-dev'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-5-context.md'
  - '{project-root}/.claude/skills/pps-design/SKILL.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-5-11-onboarding-part-2-first-alarm-checks-checklist-and-usage-stats.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** onboarding (Story 5.11) completes after the usage stats choice: a new user never rings a test with the phone locked before tonight, the checklist's "Test alarm" row stays unticked, and Home never recommends the test after a skip (FR-ONB-1, FR-ONB-4, FR-ALM-8, UX-DR81).

**Approach:** raise `OnboardingViewModel.LAST_BUILT` to `TestAlarm` and wire the approved round 3 step 8: "Ring a test alarm" rings the stored first alarm through 5.4's `ChecklistTestAlarm` (Story 1.18 `ScheduleTestAlarm`, "Test · no charge"); the step watches 5.3's `lockedTestPassedAt` and completes onboarding when a locked test passes after the ring, shows "Your phone wasn't locked. Try again with it locked." when the ring is over without a pass, and "Skip for now" completes with Home's note "Ring a test alarm with your phone locked to check it works." until a locked test passes or the note is dismissed.

## Boundaries & Constraints

**Always:** reuse the round 3 step 8 and Home note (pixels of the preview unchanged), existing strings verbatim, `ChecklistTestAlarm`, `ScheduleTestAlarm`, 5.3's `LockedTestReporter` (the only writer of `lockedTestPassedAt`), the 5.10 dwell and "Couldn't save. Try again." note; when the test rings is stored (`onboarding_test_rings_at`), so a cleared ViewModel or process death keeps the result; onboarding can always be finished ("Skip for now" works in every step 8 state, waiting included); every user write inside `SessionLockGuard.whenIdle`; core stays pure; ports return `Outcome`.

**Never:** no new strings, tokens, components or permissions; no change to how a test rings, charges or records its pass (1.18, 5.3); no lock-screen detection beyond 5.3's `testStartedLocked`; no Bright wake screen setting (Story 5.2); no nav route pushed over onboarding; no Gradle Managed Device test (no device in this lane).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Step 7 answered / already asked | leaving step 6 or 7 | step 8 opens ("Test your alarm", "Lock your phone. We'll ring in 10 seconds.", "Ring a test alarm", "Skip for now") | — |
| Ring | tap after dwell | test of the stored first alarm (its checks, sound, quiet time) in 10 s; its ring time stored; "Ring a test alarm" disabled while waiting | arming fails → "Couldn't save. Try again.", logged, button back |
| Locked pass | `lockedTestPassedAt` ≥ ring time − 10 s | onboarding completes, Home opens, checklist row OK | completion fails → stays, saveFailed, retried on resume |
| Not locked | ring time + 10 s passed, no pass since the ring | "Your phone wasn't locked. Try again with it locked."; row unticked; Ring enabled | — |
| Back from 8 | user already asked about stats | step 6 (step 7 never asked twice) | unreadable choice counts as not asked → step 7 |
| Skip for now | any step 8 state | `testNoteDue` + completed stored, Home shows the note | write fails → stays, saveFailed |
| Home note | `testNoteDue`, no locked pass | note with "Dismiss"; hidden once a locked test passes or after "Dismiss" | dismissal write fails → stays, logged |
| No screen lock | keyguard never shows | every test ends "wasn't locked"; "Skip for now" finishes | — |
| Process death | stored step 8 + ring time | reopens on step 8 with the derived state | — |

</intent-contract>

## Code Map

- `composeApp/.../ui/onboarding/OnboardingViewModel.kt` -- `LAST_BUILT` (hook), `afterUsageStats()`, `onStepIntent` Back (`step.ordinal - 1`), `onAlarmIntent` ignores Ring/Skip, `next()`, `restore()`, `complete()`, `Op`, `Local`, `render()`.
- `composeApp/.../ui/onboarding/OnboardingContract.kt` -- `TestAlarmStatus { Ready, NotLocked }`, `testStatus`, intents `RingTestAlarm`, `SkipTestAlarm` exist; add `Waiting`, `brightWakeNote`.
- `composeApp/.../ui/onboarding/OnboardingScreen.kt` `Actions` (step 8 buttons), `OnboardingSteps.kt` `TestAlarmStep` (always shows the bright note today).
- `composeApp/.../ui/onboarding/OnboardingChecklist.kt` -- home of `UsageStatsSources`; add `TestAlarmSources`.
- `composeApp/.../ui/reliability/ChecklistTestAlarm.kt` -- `invoke()` rings the soonest enabled alarm or defaults; `toTestDraft()`.
- `core/.../reliability/ReliabilityChecklist.kt` -- `StoredReliability.lockedTestPassedAt`, `ReliabilityRecords` (read only today); `data/.../DataStoreReliabilityRecords.kt`; `testing/.../ReliabilityFakes.kt` `FakeReliabilityRecords`; core test doubles in `ConfirmMakerGuidanceTest.kt`, `ReliabilityChecklistTest.kt`.
- `core/.../session/TestAlarm.kt` `ScheduleTestAlarm.TEST_DELAY`; `core/.../session/EngineKeyguard.kt` reports the pass at Completed (async write).
- `core/.../onboarding/Onboarding.kt`, `data/.../DataStoreOnboardingRepository.kt`, `testing/.../OnboardingFakes.kt`, `core/src/commonTest/.../OnboardingTestDoubles.kt` -- progress, `CompleteOnboarding`.
- `composeApp/.../ui/home/HomeViewModel.kt` (`LocalState`, `render`, `onReliabilityIntent`), `HomeContract.kt` `testSkipped`, `HomeScreen.kt` note + `HomeIntent.TestNoteDismissed` (approved, unwired).
- `composeApp/.../ui/nav/OnboardingGate.kt` / `TabNavigation.applySessionLock` -- a test session replaces the onboarding stack; Compose pauses while MainActivity is stopped, so the ViewModel may or may not survive the ring.
- `composeApp/.../ui/UiModule.kt`, `androidApp/.../YawnAndPawnApp.kt` -- Koin.
- Tests: `OnboardingViewModelFixture`/`OnboardingViewModelTest`, `OnboardingFlowTest` (step 7 now leads to step 8), `OnboardingScreenshotTest`, `OnboardingSemanticsTest`, `OnboardingSamples`, `HomeScreenshotTest`/`HomeSamples`, `HomeViewModelTest`, core `OnboardingTest`, data `DataStoreOnboardingRepositoryTest`, `DataStoreReliabilityRecordsTest`.

## Tasks & Acceptance

**Execution:**
- `core/.../onboarding/Onboarding.kt` -- `OnboardingProgress.testRingsAt`, `testNoteDue`; repo `setTestRingsAt`, `setTestNoteDue`; `RememberOnboardingTest`; `CompleteOnboarding(testSkipped)`; pure `OnboardingTest.result(ringsAt, passedAt, now)`; `OnboardingTestNote` (shown flow, dismiss).
- `core/.../reliability/ReliabilityChecklist.kt` -- `ReliabilityRecords.observe()`; data adapter, fakes, core doubles.
- `data/.../DataStoreOnboardingRepository.kt`, `testing/.../OnboardingFakes.kt`, core doubles -- the two keys.
- `composeApp/.../reliability/ChecklistTestAlarm.kt` -- `invoke(preferred: String? = null)`.
- `composeApp/.../onboarding/*` -- step 8 behaviour, `TestAlarmSources`, Back skip, waiting tick, resume re-read; `brightWakeNote` false (5.2 hook).
- `composeApp/.../home/HomeViewModel.kt` -- the note and its dismissal.
- `UiModule.kt`, `YawnAndPawnApp.kt` -- bindings.
- Tests for every matrix row (ViewModel), core rules, data keys, real-app flow to Home with the note, screenshots step 8 Ready / Waiting / NotLocked and Home note (Light, Dark, 200 %), semantics.

**Acceptance Criteria:**
- Given steps 4 to 7 walked, when step 7 is answered, then step 8 opens (not Home).
- Given step 8, when "Skip for now" is tapped, then Home opens with the note, the checklist "Test alarm" row is unticked, and the note goes after a locked test passes or "Dismiss".
- Given step 8 screenshots and Home with the note, when Roborazzi runs, then baselines exist in Light, Dark and 200 %.

## Design Notes

- **DEFAULT TAKEN (owner can change), no screen lock (5.3 open question):** a phone whose lock is "None" can never pass; it gets the not-locked message and finishes with "Skip for now" (always shown, never disabled); Home then keeps the dismissible note. No extra detection or copy.
- **DEFAULT TAKEN, Home note lifetime:** epic "until a locked test passes" and EXPERIENCE.md "until dismissed" both apply: the note goes on whichever comes first.
- **DEFAULT TAKEN, Back from step 8:** goes to step 6 for a user already asked (5.11 deferral, FR-ONB-6); the epic's "Back returns to step 7" holds only for a user whose choice could not be read.
- **DEFAULT TAKEN, bright wake note:** shown only when Bright wake screen is on; the setting arrives with Story 5.2, so the app hides it until then (`brightWakeNote`, default true for previews).
- **DEFAULT TAKEN, waiting state:** the approved step always shows "Lock your phone. We'll ring in 10 seconds."; while a test is pending "Ring a test alarm" is disabled until 10 s after the ring time; then a ring that was not passed reads as not locked (a ring that never came, was missed or preempted reads the same).
- **DEFAULT TAKEN, arming failure:** "Couldn't save. Try again." (5.10's step failure note), logged.
- **Pass detection:** `OnboardingTest.result` is pure: `Passed` when `passedAt ≥ ringsAt − TEST_DELAY`; else `Waiting` before `ringsAt + RESULT_SLACK` (10 s); else `NotLocked`; `NotRung` with no ring time. The ViewModel watches `ReliabilityRecords.observe()` (the pass is written asynchronously after Completed), re-reads on every resume, and re-renders at `ringsAt + RESULT_SLACK`.

## Verification

**Commands (JDK 17, `--no-daemon --max-workers=2`, targeted only):**
- `:core:jvmTest --tests "*onboarding*" --tests "*reliability*"`; `:data:testAndroidHostTest --tests "*Onboarding*" --tests "*ReliabilityRecords*"` -- pass.
- `:composeApp:testAndroidHostTest --tests "*onboarding*" --tests "*home*" --tests "*reliability*" --tests "*CopyRulesTest*" --tests "*UiModuleTest*"` -- pass.
- `:androidApp:recordRoborazziDebug` (new baselines) then `:androidApp:testDebugUnitTest --tests "*Onboarding*" --tests "*Home*" --tests "*Preview*"` -- pass.
- `spotlessCheck detekt` -- pass.
