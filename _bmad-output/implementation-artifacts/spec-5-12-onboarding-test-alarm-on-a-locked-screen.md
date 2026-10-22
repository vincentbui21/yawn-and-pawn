---
title: 'Story 5.12: Onboarding test alarm on a locked screen'
type: 'feature'
created: '2026-10-21'
status: 'done'
baseline_revision: '427ef1b8da421d26706cfa0ca0d4bcf56e69d415'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-5-context.md'
  - '{project-root}/.claude/skills/pps-design/SKILL.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-5-11-onboarding-part-2-first-alarm-checks-checklist-and-usage-stats.md'
warnings: ['oversized']
deferred:
  - summary: >-
      The epic's Gradle Managed Device test (debug keyguard locked, the test session completed, `lockedTestPassedAt`
      asserted) is not built.
    evidence: |-
      No device or emulator in this lane; the locked path is covered by 5.3's engine tests (testStartedLocked reported
      once) and this story's ViewModel tests with the recorder's fake. The device check is Story 5.13 item 7.
    severity: medium
  - summary: >-
      A test that never rang, was missed (30 min) or was preempted by a real alarm reads as "Your phone wasn't locked."
    evidence: |-
      Only a locked pass is recorded (5.3); telling the other endings apart needs the test's history row. The user can
      ring again or skip.
    severity: low
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

## Run constraints

- Work only in the worktree `C:/Users/BuiTua/Personal/yawn-and-pawn/.claude/worktrees/agent-a8e77f67b41f02d00` (absolute paths). JDK 17: `JAVA_HOME=C:/Users/BuiTua/AppData/Local/Programs/jdk17/jdk-17.0.20.1+1`. Company PC without admin; install nothing.
- Other build lanes share this PC: write all code first, run Gradle only at the end, always `--no-daemon --max-workers=2`, targeted tasks with quoted test filters only (never `qualityGate`). If a JVM dies on memory, retry once later.
- Never use a phone or adb. Never commit, push or open a PR. The repo is public: no secrets, personal data or device serials.

## Verification

**Commands (JDK 17, `--no-daemon --max-workers=2`, targeted only):**
- `:core:jvmTest --tests "*onboarding*" --tests "*reliability*"`; `:data:testAndroidHostTest --tests "*Onboarding*" --tests "*ReliabilityRecords*"` -- pass.
- `:composeApp:testAndroidHostTest --tests "*onboarding*" --tests "*home*" --tests "*reliability*" --tests "*CopyRulesTest*" --tests "*UiModuleTest*"` -- pass.
- `:androidApp:recordRoborazziDebug` (new baselines) then `:androidApp:testDebugUnitTest --tests "*Onboarding*" --tests "*Home*" --tests "*Preview*"` -- pass.
- `spotlessCheck detekt` -- pass.

## pps-design Done checklist

- [x] Only tokens from `DESIGN.md` used: step 8 and the Home note are the approved round 3 / Home pieces; the only UI change is `enabled` on "Ring a test alarm" and hiding the bright wake note (no new token, radius or size).
- [x] Light, Dark checked with screenshots (`OnboardingScreenshotTest`: step 8 Ready, Waiting, NotLocked; `HomeScreenshotTest`: test note; Light, Dark, Light 200 %); Sunrise not relevant (the test's wake screens are 1.18's).
- [x] Every colour pair is in the `DESIGN.md` contrast table (no new pair; the disabled filled button is the existing component state).
- [x] Touch targets ≥ 48 dp ("Ring a test alarm", "Skip for now", `OnboardingSemanticsTest`); no wake action here.
- [x] 200 % font scale and TalkBack: step 8 reads "Test your alarm" first as a heading, dots "Step 8 of 8", the not-locked note is a polite live region; "Ring a test alarm" disabled while waiting, "Skip for now" never.
- [x] Reduced motion: no new animation; the 5.10 dwell covers "Ring a test alarm" and "Skip for now".
- [x] Copy matches `EXPERIENCE.md > Voice and Tone`: no new string ("Test your alarm", "Lock your phone. We'll ring in 10 seconds.", "Ring a test alarm", "Skip for now", "Your phone wasn't locked. Try again with it locked.", "Ring a test alarm with your phone locked to check it works.", "Couldn't save. Try again.").
- [x] State rows: ready, waiting, not locked, arming failed / completion failed ("Couldn't save. Try again."), Home note shown / dismissed / hidden after a locked pass.
- [x] "I'm up" / snooze: not applicable.
- [x] Previews: the round 3 preview keeps its pixels (`brightWakeNote` defaults to true); Roborazzi baselines added (12).

## Spec Change Log

### 2026-10-22 — Rebase onto main with Story 5.11 merged (not a review loopback)
- **Trigger:** 5.11 (PR #77) squash-merged as ae9743af with its review fixes (`FirstAlarmSources.alarms`, `gone(id)` for `openFailed()`, the slide guard over cards and overlays, the stored first alarm adopted on restore).
- **Amended:** nothing in the spec; the restore keeps 5.11's adopted `alarmId` and adds step 8's stored ring time.
- **KEEP:** the derived step 8 result, Back skipping step 7 for a user already asked.

## Review Triage Log

### 2026-10-22 — Review pass (fast mode: one combined self-review; blind, edge-case, verification-gap and intent lenses)
- verdicts: 9 findings — high 0, medium 1, low 5, false 3
- findings:
  - `[medium]` `[defer]` (intent) Gradle Managed Device keyguard test from the epic not built — no device in this lane; deferred (frontmatter), device check in 5.13 item 7.
  - `[low]` `[defer]` (edge) A test that never rang, was missed or preempted reads "wasn't locked" — only a locked pass is recorded; deferred.
  - `[low]` `[reject]` (edge) A pass watch that fails ends the watch — every resume and every step 8 opening reads the records again (`rereadTest`); tested.
  - `[low]` `[reject]` (edge) Home's note hides for good in that ViewModel when the records cannot be read — a recommendation note, fail-closed and logged; the next Home ViewModel reads again.
  - `[low]` `[reject]` (blind) A locked pass from before "Delete all data" hides the note — the eraser clears the reliability DataStore (`AndroidLocalDataEraser`), and step 8 counts only a pass after its own ring.
  - `[low]` `[patch]` (gap) detekt `LongMethod` in `OnboardingSetupStepsTest` and `UiModuleTest` after the rebase — helpers extracted; detekt passes.
  - `[false]` `[reject]` (intent) "Back returns to step 7" — the coordinator's 5.11 deferral and FR-ONB-6 (asked once); DEFAULT TAKEN, listed.
  - `[false]` `[reject]` (intent) Bright wake note missing in the app — Story 5.2 has not built the setting; showing it would claim a behaviour the app lacks; DEFAULT TAKEN.
  - `[false]` `[reject]` (intent) `qualityGate` not run — lane run constraints; CI runs it on the PR.

### 2026-10-22 — PR #79 review (coordinator): no high, 3 medium, 3 low; all patched
- `[medium]` `[patch]` Wall-clock dependence: a clock set back between Ring and the pass lost a real pass, and a clock set back while waiting kept the step waiting. `OnboardingTestAlarm.CLOCK_SKEW` (5 min) widens the pass window; the wait runs in elapsed time, at most `MAX_WAIT` (20 s), so the step can never get stuck; step 8 renders again on `TimeChangeSignal` (bound in `UiModule`). Tests: core rule (pass after a 2 min set-back), `OnboardingTestAlarmStepTest` (set back 2 min then a pass completes; set back 1 h still ends the wait; a set-forward clock re-renders on the signal).
- `[medium]` `[patch]` A ring time that could not be stored lost the pass. The planned ring time is now stored before arming; if that write fails nothing is armed and "Couldn't save. Try again." shows; a failed arming puts the earlier time back. Test: the write fails → no test armed, error shown; then Ring stores first and a new screen takes the pass.
- `[medium]` `[patch]` Acceptance criterion 2 was only covered with fakes. `OnboardingFlowTest` now taps "Ring a test alarm" in the real app, records a locked pass through the real `ReliabilityRecords`, and checks Home without the note, `completed`, no `testNoteDue` and the checklist's test row OK. Writing it found a race, also patched: a pass read while the test was still being armed (step busy) waited for the next resume; the step now completes as soon as arming ends.
- `[low]` `[patch]` "Wasn't locked" could flash before 5.3's write landed: "not locked" shows only after the locked pass was read once (watched or re-read; a failed read counts as read, logged). No dedicated test: the fakes emit at once, so the window cannot be held open without a suspending records fake.
- `[low]` `[patch]` A failed completion after a pass left only a resume to retry. "Skip for now" after a pass now completes it as passed (no Home note). Test added.
- `[low]` `[patch]` Test gap: the Ring test now asserts exactly one `ScheduleTest`, the first alarm still off with its request code, and no call on the alarm scheduler.

## Implementation notes

- **Name deviation:** the pure rule is `OnboardingTestAlarm.result` (with `OnboardingTestResult`), not `OnboardingTest.result`: core's existing test class `com.yawnandpawn.app.core.onboarding.OnboardingTest` has the same fully qualified name, so an `OnboardingTest` object in main would not compile.

**Run as done (2026-10-22, after the rebase onto ae9743af; JDK 17, `--no-daemon --max-workers=2`):**
- `:core:jvmTest` onboarding + reliability, `:data:testAndroidHostTest` Onboarding, ReliabilityRecords, SessionLockGuardScanTest, `:composeApp:testAndroidHostTest` onboarding, home, reliability, CopyRulesTest, UiModuleTest -- pass.
- `:androidApp:testDebugUnitTest` Onboarding*, Home*, Preview*, MainActivityTest, Reliability* with Roborazzi verify (the 12 new baselines checked by eye) -- pass, 0 failures.
- `spotlessCheck detekt` -- pass after the two `LongMethod` test fixes.
- Not run (run constraints): `qualityGate`, Kover, lint, instrumented / Gradle Managed Device tests.

## Auto Run Result

Status: done (fast mode, one combined self-review). Rebased once during the run, onto main after 5.11 (#77) merged.

**Summary:** onboarding now ends with step 8. "Ring a test alarm" rings the stored first alarm as a 1.18 test in 10 s (5.4's `ChecklistTestAlarm`, now with a preferred alarm id; "Test · no charge"); its ring time is stored, so the result survives the session lock clearing the screen or a process death. The result is derived in core (`OnboardingTestAlarm.result`) from that time, 5.3's `lockedTestPassedAt` (watched through the new `ReliabilityRecords.observe()`, read again on each resume) and the clock: a locked pass completes onboarding (Home, checklist row OK); a ring over without one shows "Your phone wasn't locked. Try again with it locked."; while waiting "Ring a test alarm" is disabled. "Skip for now" always completes and stores Home's note, which shows until a locked test passes or "Dismiss". Back from step 8 goes to step 6 for a user already asked.

**Follow-up review recommended:** false (no high, the one medium is a deferred device test).

**Residual risks:** the GMD keyguard test is not built (5.13 item 7 checks it on the Oppo); "wasn't locked" also covers a test that never rang; the bright wake note stays hidden until Story 5.2; `qualityGate` runs in CI only.
