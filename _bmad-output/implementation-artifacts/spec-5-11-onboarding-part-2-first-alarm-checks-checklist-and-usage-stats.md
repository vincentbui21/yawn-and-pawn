---
title: 'Story 5.11: Onboarding, part 2: first alarm, checks, checklist and usage stats'
type: 'feature'
created: '2026-10-21'
status: 'done'
baseline_revision: '50b4150cc0804e2edcf5a879108b3e1d81d70a24'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-5-context.md'
  - '{project-root}/.claude/skills/pps-design/SKILL.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-5-10-onboarding-part-1-mission-alarm-behaviour-disclosure-and-base-fee.md'
warnings: ['oversized']
deferred:
  - summary: >-
      A failed first-alarm id write, or a process death between SaveAlarm and that write, restores step 4 on a new
      alarm, so a changed form saves a second alarm.
    evidence: |-
      OnboardingViewModel.alarmSaved queues Op.RememberAlarm then Op.Step; a failed RememberAlarm is only logged, and
      restore opens FirstAlarm for a step past Checks without an id. Needs a DataStore write failure or a death in a
      few-ms window; an unchanged form is caught by SaveAlarm's identical-alarm rule (switches the stored one on).
    location: >-
      composeApp/src/commonMain/kotlin/com/yawnandpawn/app/ui/onboarding/OnboardingViewModel.kt
    severity: medium
  - summary: >-
      A transient read failure of the stored first alarm makes onboarding forget its id and open a new alarm.
    evidence: |-
      AlarmEditorViewModel.load sends OpenFailed for any read failure (NotFound or StorageFailure); onboarding's
      alarmUnreadable clears the id. The original alarm stays stored and enabled. Fix needs the editor to report the cause.
    location: >-
      composeApp/src/commonMain/kotlin/com/yawnandpawn/app/ui/onboarding/OnboardingFirstAlarm.kt
    severity: medium
  - summary: >-
      Test gaps: Back during a suspended first-alarm save, a save that returns NotFound, and the route's ON_RESUME
      re-check in the real app.
    evidence: |-
      Covered at ViewModel level only or not at all; needs a suspending SaveAlarm fake and a probe swap in OnboardingFlowTest.
    severity: low
  - summary: >-
      Story 5.12: Back from step 8 must skip step 7 for a user already asked (the restore and step-6 rules already skip it).
    evidence: |-
      OnboardingViewModel Back goes to the previous ordinal; with LAST_BUILT raised to TestAlarm, Back from step 8 would
      show step 7 again.
    severity: low
---

<intent-contract>

## Intent

**Problem:** onboarding (Story 5.10) stops after the base fee: "Continue" on step 3 finishes it, so a new user never sets a first alarm or its check, never sees which phone settings can stop the alarm, and is never asked about usage stats (FR-ONB-1, FR-ONB-2, FR-ONB-6, FR-ALM-1).

**Approach:** wire steps 4 to 7 of the approved round 3 `OnboardingScreen` to the real `OnboardingViewModel`: steps 4 and 5 drive a child `AlarmEditorViewModel` (the editor's form, 5.1 defaults, Check picker, Check setup, "Try it", QR registration, `SaveAlarm` and the once-only notification request) so the first alarm is the editor's alarm; step 6 shows the 5.3 checklist (`EvaluateReliability`) in the approved `ChecklistCard`; step 7 stores the choice through 5.6's `SetAnalyticsConsent`. After step 7 onboarding completes (step 8 is Story 5.12).

## Boundaries & Constraints

**Always:** reuse the round 3 screens, the editor's ViewModel and panes, `ChecklistCard`, `ManufacturerGuidanceRoute`, and existing string resources (EXPERIENCE.md key strings verbatim); the step and the first alarm's id are stored on every change so process death resumes there and never creates a second alarm; the session lock still wins; core stays pure; ports return `Outcome`; one save path for alarms (`SaveAlarm`, consent-gated since 5.10).

**Never:** no step 8 behaviour ("Ring a test alarm", "Skip for now", the not-locked state: Story 5.12, intents ignored); no new tokens, components or strings; no new permission; no change to `ReliabilitySettings`/`ReliabilityItem` (Story 5.4, PR #74, replaces them); no nav routes pushed over `Route.Onboarding` (overlays are in-screen); no edit to `deferred-work.md` (no item is assigned to 5.11).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Step 4 opens | new user | editor's new alarm (7:00, Settings defaults for snooze length, quiet time, its vibration), "When should it ring?" | defaults unreadable → built-in, logged (editor) |
| Passed one-time time | Once, time before now | "Rings tomorrow at {time}." on the time card | — |
| Step 5 none ticked | no check | "Continue" disabled and "Pick at least one check." shown | — |
| QR picked | QR/Barcode ticked | camera asked with its reason; refused → "Camera isn't available." + Fix (editor) | — |
| QR without code, Continue | QR selected, no code | Check setup opens over step 5 ("Scan a code to use this check.") | nothing saved |
| Continue on step 5 | valid checks | `SaveAlarm` (enabled), notification request once (API 33+), id + step 6 stored | save failure → stays, "Couldn't save the alarm. Try again." |
| Back to 4/5 after save, Continue | saved alarm id | the same alarm is edited, still one alarm | stored alarm unreadable → new-alarm form again, id cleared |
| Process death after save | stored step `Reliability`, alarm id | reopens on step 6; Back edits that alarm | stored step past `Checks` without an id → step 4 |
| Step 6 opens / app resumes | probe, alarms, records | checklist rows except Hidden and the test row; re-checked on every resume | evaluation never fails (5.3) |
| Fix | Notifications, Full-screen, Exact | that system screen (`ReliabilitySettings`, 1.19 items) | — |
| Fix | Manufacturer | 5.5 maker guidance over step 6; closing re-checks | — |
| Fix | Camera | permission dialog first; after a refusal, the app's settings | — |
| Fix | Do Not Disturb, Battery | re-checks only (deep links arrive with Story 5.4) | — |
| Step 7 choice | "Share" / "No thanks" | consent on/off, `analyticsAsked` true, onboarding completes, Home | write failure → stays on step 7, logged |
| Already asked | `asked` true when leaving step 6 | step 7 skipped; onboarding completes | — |

</intent-contract>

## Code Map

- `composeApp/.../ui/onboarding/OnboardingViewModel.kt` -- 5.10 VM: `Local`, one write queue `ops`, `restore()`, `next()`, `goTo()`, `LAST_BUILT = BaseFee`, `render()`. Extend to steps 4-7; `LAST_BUILT = Analytics` is 5.12's hook.
- `composeApp/.../ui/onboarding/OnboardingContract.kt` -- `OnboardingUiState` already has `alarmTime`, `repeatDays`, `customRepeat`, `checks: CheckPickerUiState`, `reliability: List<ChecklistRow>`; intents `TimeChanged`, `RepeatChosen`, `DayToggled`, `Checks`, `FixClicked`, `UsageStatsChosen`. Add `ringsTomorrowAt`, the Check setup overlay (`EditorUiState?`), `manufacturerGuidance`, intent `CheckSetup(EditorIntent)`.
- `composeApp/.../ui/onboarding/OnboardingScreen.kt`, `OnboardingSteps.kt` -- stateless round 3 steps; `FirstAlarmStep` passes no `ringsTomorrowAt` yet; `Actions` disables "Continue" on step 5 with no check; `ReliabilityStep` renders `ChecklistCard`.
- `composeApp/.../ui/onboarding/OnboardingRoute.kt` -- add overlays (editor's `AlarmEditorScreen` for CheckSetup/TryIt/ScanCode panes, `ManufacturerGuidanceRoute`), snackbar, `ON_RESUME` refresh, `resumed` counter for QR registration.
- `composeApp/.../ui/editor/AlarmEditorViewModel.kt` -- `save()` (line ~671): add an optional `onSaved: ((String) -> Unit)?` constructor param called with the stored alarm's id on success. Child-editor contract: pane `WakeCheck` = step 5; panes `CheckSetup`, `TryIt`, `ScanCode` = overlay; `BackRequested` from them steps back inside the editor; after a successful save it stays `isSaving` (so a fresh child is opened for the id); `OpenFailed` effect when a stored alarm cannot be read; `ShowSaveFailed` effect.
- `composeApp/.../ui/editor/AlarmEditorScreen.kt` `AlarmEditorScreen(state, is24Hour, onIntent, resumed)` renders those panes; `EditorSubScreens.kt` `rememberLastNonNull` (internal) keeps the overlay content while it slides out.
- `composeApp/.../ui/UiModule.kt` -- `AlarmEditorViewModel` construction to share with an onboarding editor factory.
- `composeApp/.../ui/reliability/ReliabilityScreen.kt` -- `ChecklistItem`, `ItemStatus`, `ChecklistRow`, `ChecklistCard` (approved). Since the rebase onto 5.4 (see Spec Change Log): `ReliabilityViewModel`, `toChecklistRows` and `ReliabilitySettings.open(row)` are on main and reused.
- `composeApp/.../ui/reliability/ManufacturerGuidance.kt` -- `ManufacturerGuidanceRoute(onClose)`.
- `core/.../reliability/ReliabilityChecklist.kt` `EvaluateReliability`, `ChecklistRow`, `RowStatus`; `core/.../reliability/Reliability.kt` `ReliabilitySettings.open(ReliabilityItem)` (1.19: Notifications, FullScreenIntent, ExactAlarms).
- `composeApp/.../ui/qr/CameraPermission.kt` -- `request()`, `openSettings()`.
- `core/.../telemetry/Consent.kt` -- `ConsentStore.observe()` (`asked`), `SetAnalyticsConsent`.
- `core/.../onboarding/Onboarding.kt`, `data/.../settings/DataStoreOnboardingRepository.kt`, `testing/.../OnboardingFakes.kt` -- add the first alarm's id (`onboarding_alarm_id`) and a `RememberOnboardingAlarm` use case.
- `androidApp/.../YawnAndPawnApp.kt` -- core use case bindings (where `SaveOnboardingStep` is bound).
- Tests to extend: `composeApp/src/commonTest/.../onboarding/OnboardingViewModelTest.kt` (5.10 helper `viewModel()`; editor built like `AlarmEditorQrTest.viewModel()` with `AlarmUseCasesFixture`), `androidApp/src/test/.../ui/OnboardingFlowTest.kt` ("Continue" on step 3 now opens step 4), `OnboardingScreenshotTest.kt`, `OnboardingSemanticsTest.kt`, `OnboardingSamples.kt`; core `OnboardingTest`, data `DataStoreOnboardingRepositoryTest`.
- `androidApp/src/debug/.../preview/*` -- the round 3 preview keeps compiling against the contract (pixels unchanged).

## Tasks & Acceptance

**Execution:**
- `core/.../onboarding/Onboarding.kt` -- `OnboardingProgress.alarmId`, `OnboardingRepository.setAlarmId(id: String?)`, `RememberOnboardingAlarm(repo, sessionLock)`.
- `data/.../DataStoreOnboardingRepository.kt`, `testing/.../OnboardingFakes.kt` -- the key and the fake.
- `composeApp/.../ui/editor/AlarmEditorViewModel.kt` -- `onSaved` param (default null, no behaviour change otherwise).
- `composeApp/.../ui/onboarding/OnboardingFirstAlarm.kt` (new) -- owns the child editor in its own `ViewModelStore` (cleared on replace and on close), its state and effects.
- `composeApp/.../ui/onboarding/OnboardingViewModel.kt` + contract -- steps 4 to 7, Fix targets, guidance and Check setup overlays, consent, completion after step 7, restore rules.
- `composeApp/.../ui/onboarding/OnboardingRoute.kt`, `OnboardingSteps.kt` -- overlays, snackbar, resume refresh, "Rings tomorrow at", no empty checklist card.
- `composeApp/.../ui/UiModule.kt`, `androidApp/.../YawnAndPawnApp.kt` -- Koin.
- Tests: core, data, `OnboardingViewModelTest` (whole flow with fakes: exactly one alarm, consent false after "No thanks"; edge cases of the matrix), `OnboardingFlowTest` (real app to Home), `OnboardingScreenshotTest` (first alarm, checks with QR selected, checks none, checklist with two Fix rows, usage stats; Light, Dark, 200 %), `OnboardingSemanticsTest` (steps 4 to 7 headline first, 48 dp, Share/No thanks equal and not focused).

**Acceptance Criteria:**
- Given step 3, when "Continue" is tapped, then step 4 opens (no longer Home).
- Given steps 4 to 7 walked with "No thanks", when onboarding ends, then exactly one enabled alarm is stored with the chosen time, repeat, checks and the Settings defaults, consent is false with `asked` true, the Settings switch shows off, and Home opens.
- Given steps 4 to 7, when Roborazzi and semantic tests run, then screenshots exist in Light, Dark and 200 %, each headline is read first as a heading, targets are at least 48 dp.

## Spec Change Log

### 2026-10-21 — Rebase onto 5.10's review fixes (not a review loopback)
- **Trigger:** 5.10's review (merged as #75) added `OnboardingUiState.saveFailed` ("Couldn't save. Try again." above the step's action), the `STEP_DWELL_MILLIS` dwell and the screen's slide guard; the coordinator asked for the same on steps 4 to 8.
- **Amended:** Design Notes, child effects: the first alarm's failed save now uses `saveFailed` instead of a snackbar with the editor's "Couldn't save the alarm. Try again."; dwell note added.
- **Known-bad state avoided:** two different failure messages on one screen, and a double tap landing on step 7's "Share" / "No thanks".
- **KEEP:** the child editor design, the restore rules, the 5.12 hook.

### 2026-10-21 — Rebase onto main with Story 5.4 merged (not a review loopback)
- **Trigger:** 5.4 (PR #74) merged; the coordinator asked to reuse its real checklist pieces rather than a copy.
- **Amended:** Design Notes (step 6 is a child `ReliabilityViewModel`); the DEFAULT TAKEN "DND and Battery only re-check" and its deferred item are gone (5.4's deep links now apply); the private row mapping, evaluation, camera and Fix code in onboarding were removed.
- **Known-bad state avoided:** two checklist implementations that drift (rows, Fix targets, camera rule).
- **KEEP:** the guidance as an in-screen overlay; rows without the test row; re-check on open, resume and guidance close; the dwell on "Fix".

## Review Triage Log

### 2026-10-21 — Review pass (fast mode: one combined reviewer; blind, edge-case, verification-gap and intent lenses)
- verdicts: 42 findings — high 0, medium 11, low 22, false 9, maybe-false 0 (rows repeat across lenses; 4 distinct medium root causes)
- findings:
  - `[medium]` `[defer]` (blind 1) A failed id write, or a process death between `SaveAlarm` and the id write, restores step 4 on a new alarm, so a changed form saves a second alarm — needs a storage failure or a death in a few-ms window; an unchanged form is caught by SaveAlarm's identical-alarm rule. Deferred.
  - `[medium]` `[patch]` (blind 2) Back-editing the stored first alarm inside the 8 h lock window stored easier checks as pending with no note — step 4 now shows the editor's `weakeningNote` and step 5 its `checksNote` ("Saved. Takes effect after ..."), as in the editor.
  - `[medium]` `[defer]` (blind 3) A transient read failure of the stored first alarm forgets its id (the editor's `OpenFailed` does not tell NotFound from StorageFailure) — rare; the fix needs the editor to report the cause. Deferred.
  - `[false]` `[reject]` (blind 4) Spec'd snackbar missing — the rebase onto 5.10 moved every onboarding failure to 5.10's `saveFailed` note on purpose (Spec Change Log); the code matches the amended spec.
  - `[false]` `[reject]` (blind 5) SessionActive / InvalidAlarm(Label) save failures show nothing — SessionActive hands the screen to the session lock (correct), and onboarding has no label field.
  - `[low]` `[patch]` (blind 6) "Fix" on Do Not Disturb / Battery only re-checked — resolved by the rebase onto 5.4: step 6 now uses 5.4's `ReliabilityViewModel`, whose "Fix" opens every row's screen; test updated.
  - `[low]` `[patch]` (blind 7) Step 5 lost its scroll position under Check setup — the steps sit in a `SaveableStateHolder` in `OnboardingRoute`, so the picker returns where it was.
  - `[low]` `[patch]` (blind 8) A step 7 restored after the choice was stored asked again — restore now skips to what follows step 7 when `asked` is true; test added.
  - `[low]` `[patch]` (blind 9) Overlapping checklist evaluations could overwrite newer rows — step 6 now runs on 5.4's `ReliabilityViewModel`, whose evaluations run one at a time behind its `Mutex`.
  - `[low]` `[patch]` (blind 10) "Fix" on Camera tapped twice asked twice — onboarding's own camera code is gone; step 6 uses 5.4's checklist (the onboarding dwell still applies to "Fix").
  - `[low]` `[reject]` (blind 11) No busy state on step 5 while saving — the save is a local write of a few ms and extra taps are ignored; adding a UI state is more surface than the harm.
  - `[low]` `[reject]` (blind 12) A wheel edit during a fresh child's load snaps back — the load is a local read of a few ms after Back; queueing intents adds complexity for a rare race.
  - `[low]` `[patch]` (blind 13) pps-design Done checklist missing from the story file — added below.
  - `[low]` `[reject]` (blind 14) `LargeClass` suppressed on `OnboardingViewModel` — no caller or rule breaks; the first alarm already lives in its own class.
  - `[medium]` `[defer]` (edge 1) Same as blind 1.
  - `[medium]` `[patch]` (edge 2) Same root cause as blind 2 (`CloseSavedUnderLock` ignored) — fixed by the notes; the save itself happened, so moving on is right.
  - `[medium]` `[patch]` (edge 3) Same root cause as blind 2 (`checksNote` dropped) — patched.
  - `[medium]` `[defer]` (edge 4) Same as blind 3.
  - `[low]` `[patch]` (edge 5) A late Back on the sliding-out overlay could reach the editor's main screen and close it as if its alarm were gone — overlay intents now reach the child only while an overlay pane shows; test added.
  - `[low]` `[patch]` (edge 6) Stale taps on the sliding-out overlay — same fix as edge 5.
  - `[low]` `[patch]` (edge 7) Same as blind 9.
  - `[low]` `[patch]` (edge 8) Same as blind 10.
  - `[low]` `[patch]` (edge 9) Same as blind 8.
  - `[low]` `[reject]` (edge 10) Same as blind 12.
  - `[false]` `[reject]` (edge 11) Same as blind 5.
  - `[medium]` `[defer]` (edge claim 1) "never creates a second alarm" overstated — same as blind 1; recorded in deferred.
  - `[false]` `[reject]` (edge claim 2) Snackbar claim — same as blind 4.
  - `[low]` `[patch]` (edge claim 3) Close-without-save path undocumented and lock note lost — documented in `OnboardingFirstAlarm`; lock note patched (blind 2).
  - `[low]` `[patch]` (gap 1) Picker row tap / Random-All mapping untested — `OnboardingViewModelTest` covers `SetupClicked` opening Check setup and `ModeSelected(All)`.
  - `[low]` `[patch]` (gap 2) Route-level Back over overlays untested — `OnboardingFlowTest` opens Check setup from the Math row and presses system Back (step 5 stays, not step 4). The ON_RESUME re-check stays covered at ViewModel level only.
  - `[low]` `[defer]` (gap 3) Save in flight blocking Back untested — needs a suspending SaveAlarm fake.
  - `[low]` `[defer]` (gap 4) Close after a NotFound save untested — deleting the alarm during onboarding is not reachable from the UI.
  - `[medium]` `[patch]` (gap other 1) Same as blind 2.
  - `[medium]` `[defer]` (gap other 2) Same as blind 1.
  - `[low]` `[patch]` (intent 1) Fix deep links for DND / Battery — same as blind 6, resolved by 5.4.
  - `[false]` `[reject]` (intent 2) Save-failure copy — same as blind 4.
  - `[false]` `[reject]` (intent 3) QR registration on pick — the Epic 3 behaviour as built (3.10): the camera is asked on pick, registration opens from the row, or by itself on "Continue" without a code; the round 3 tap-through does the same.
  - `[false]` `[reject]` (intent 4) Check setup as an in-route overlay instead of a pushed screen — the same Check setup, "Try it" and QR registration content; the onboarding gate keeps the stack fixed, so nothing can be pushed over it (5.10).
  - `[medium]` `[patch]` (intent 5) Lock surface differs from the editor — same as blind 2.
  - `[low]` `[patch]` (intent 6) "Never asked again" on a restored step 7 — same as blind 8; 5.12's Back from step 8 must skip step 7 when asked (noted for 5.12).
  - `[false]` `[reject]` (intent 7) qualityGate not run — run constraints of this lane; CI runs it on the PR. The checklist part is patched (blind 13).
  - `[false]` `[reject]` (intent 8) 7:00 vs the preview's 7:30 — DEFAULT TAKEN, listed for the owner.

## pps-design Done checklist

- [x] Only tokens from `DESIGN.md` used: steps 4 to 7 reuse the round 3 screens, the editor's cards and panes, `ChecklistCard`, `WeakeningNoteInline`; no new token, radius or size.
- [x] Light, Dark checked with screenshots (`OnboardingScreenshotTest`: first alarm, checks with QR selected, checks none, checklist with two Fix rows, usage stats; Light, Dark, Light 200 %); Sunrise not relevant (no wake screen; "Try it" is the editor's approved Sunrise preview).
- [x] Every colour pair is in the `DESIGN.md` contrast table (no new pair).
- [x] Touch targets ≥ 48 dp (`OnboardingSemanticsTest`); no wake action here.
- [x] 200 % font scale and TalkBack: headline read first as a heading on steps 4 to 7, dots "Step N of 8", "Share" and "No thanks" the same size and not focused, "Continue" disabled with no check; checklist rows from the approved `ChecklistCard` (title, reason, status).
- [x] Reduced motion: steps and overlays use `subScreenTransition` (instant with animations off); the ViewModel dwell covers taps with animations off.
- [x] Copy matches `EXPERIENCE.md > Voice and Tone`: no new string; key strings verbatim ("When should it ring?", "How will you prove you're up?", "Make sure it rings", "Share anonymous usage stats?", "Off unless you turn it on.", "Share", "No thanks", "Pick at least one check.", "Rings tomorrow at {time}."); `CopyRulesTest` passes.
- [x] State rows: no check (Continue disabled + "Pick at least one check."), camera refused ("Camera isn't available." + Fix), QR without a code (Check setup), save failed ("Couldn't save. Try again."), checklist before its first evaluation (no empty card), commitment lock note.
- [x] "I'm up" / snooze: not applicable.
- [x] Previews: the round 3 preview states exist; Roborazzi baselines added for steps 4 to 7.

## Design Notes

- **DEFAULT TAKEN (owner can change):** the first alarm opens at the editor's new-alarm time 7:00 (the round 3 preview's 7:30 is sample data from flow F1).
- **DEFAULT TAKEN:** with no check, "Continue" is disabled (approved design, EXPERIENCE.md) and "Pick at least one check." shows under the picker (epic).
- **DEFAULT TAKEN:** until Story 5.12, onboarding completes after the usage stats choice; a user already asked skips step 7.
- **Step 6 is Story 5.4's checklist (merged before this story):** a child `ReliabilityViewModel` (`OnboardingChecklist`, in its own `ViewModelStore`, built by `ChecklistScreens` exactly like the checklist screen's), its rows without "Test alarm"; "Fix" is 5.4's for every row (resolver-checked deep links, the camera ask), the maker row opens 5.5's guidance over the step.
- **Child editor, not a copy:** steps 4 and 5 are the editor's form, so check toggles, camera asks, Check setup, "Try it", QR registration, save validation and the notification ask behave exactly as in the editor. The child lives in its own `ViewModelStore` (`ViewModelProvider.create(store, viewModelFactory { initializer { ... } })`), so its scope ends with onboarding or when a fresh child opens on the saved id. Sketch:
  - opening step 5 sends `PaneOpened(WakeCheck)` (TalkBack refresh, and Check setup's Back returns there); picker intents map like the editor's `WakeCheckPane`;
  - "Continue" on step 5 sends `SaveClicked`; `onSaved(id)` moves to step 6 at once and queues storing the id, then the step; the child that saved is left alone (it still runs the notification ask) and a fresh child on the id opens only when the user goes back to step 4 or 5;
  - the overlay shows while step 5 is open and the child's pane is `CheckSetup`, `TryIt` or `ScanCode`; Back there goes to the child (`BackRequested`); the route keeps the last overlay state while it slides out;
  - child effects: `ShowSaveFailed` → onboarding's "Couldn't save. Try again." note (`OnboardingUiState.saveFailed`, 5.10's review fix, see Spec Change Log); `OpenFailed` → fresh new-alarm child and the stored id cleared; the rest are ignored (`Close` follows `onSaved`).
- **Same dwell as 5.10 (coordinator, 2026-10-21):** a step's action (`Next`, "Share" / "No thanks", "Fix") counts only after `STEP_DWELL_MILLIS` on screen; the screen also ignores the actions while a step slides in (5.10's guard covers every step).
- **Checklist rows:** every `ChecklistEntry` that is not Hidden and not `LockedScreenTest`, `NeedsFix` as Missing; evaluated when step 6 opens, on every `ON_RESUME` while it shows, and after the guidance closes or the camera is asked.
- **5.12 hook:** `LAST_BUILT = Analytics`; one function decides what follows step 7 (complete now, `TestAlarm` once 5.12 raises `LAST_BUILT`); the stored first-alarm id is the alarm 5.12 tests with.

## Run constraints

- JDK 17: `JAVA_HOME=C:/Users/BuiTua/AppData/Local/Programs/jdk17/jdk-17.0.20.1+1`. Company PC without admin; install nothing.
- Another build lane shares this PC: write all code first, run Gradle only at the end, always `--no-daemon --max-workers=2`, targeted tasks and quoted test filters only (never `qualityGate`, never a `*Settings*` filter). If a JVM dies on memory, retry once later.
- Never use a phone or adb. Never commit, push or open a PR. The repo is public: no secrets, personal data or device serials.

## Verification

**Commands (Gradle `--no-daemon --max-workers=2`, JDK 17, targeted only):**
- `:core:jvmTest --tests "*onboarding*"`; `:data:testAndroidHostTest --tests "*Onboarding*"`; `:composeApp:testAndroidHostTest --tests "*onboarding*" --tests "*editor*" --tests "*CopyRulesTest*"` -- pass.
- `:androidApp:recordRoborazziDebug` then `:androidApp:testDebugUnitTest --tests "*Onboarding*" --tests "*Preview*"` -- pass.
- `spotlessCheck detekt` -- pass.

**Run as done (2026-10-21, JDK 17, `--no-daemon --max-workers=2`):**
- `:core:jvmTest` onboarding + SaveAlarm (OnboardingTest 10), `:data:testAndroidHostTest` (DataStoreOnboardingRepositoryTest 10, SessionLockGuardScanTest) -- pass.
- `:composeApp:testAndroidHostTest` onboarding (OnboardingViewModelTest 21, OnboardingSetupStepsTest 15), AlarmEditor*, CopyRulesTest, UiModuleTest, reliability, nav -- pass.
- `:androidApp:testDebugUnitTest` with Roborazzi verify: OnboardingFlowTest 8, OnboardingScreenshotTest 27, OnboardingSemanticsTest 10, OnboardingUpgradeTest, Preview* (7 classes), MainActivityTest 21, Reliability* (route, screenshots, semantics, settings, probe) -- pass. The 15 new baselines were recorded by the implementation run and checked by eye.
- `spotlessCheck detekt` -- pass.
- Not run (run constraints): `qualityGate`, Kover, lint, instrumented tests.

## Auto Run Result

Status: done (fast mode, one combined self-review). The branch was rebased three times during the run: onto 5.10's review fixes, onto main after 5.10 (#75) merged, and onto main after 5.4 (#74) merged.

**Summary:** onboarding now runs steps 4 to 7 and then completes (step 8 is Story 5.12). Steps 4 and 5 are a child `AlarmEditorViewModel`: the editor's new alarm with the Settings defaults (5.1), the shared time wheel and repeat cards with "Rings tomorrow at", the Check picker with Check setup, "Try it" and QR registration over the step, and "Continue" saving through `SaveAlarm` (5.10's consent gate) with the once-only notification ask. The first alarm's id is stored, so Back or a process death edits that alarm instead of adding a second. Step 6 is a child of 5.4's `ReliabilityViewModel` (its rows without "Test alarm", its "Fix" for every row, the maker guidance over the step, re-checked on open, resume and guidance close). Step 7 stores the choice through 5.6's `SetAnalyticsConsent`; a user already asked never sees it again. Every step action waits 5.10's dwell; failures show 5.10's "Couldn't save. Try again.".

**Files:**
- `core/.../onboarding/Onboarding.kt` -- `OnboardingProgress.alarmId`, `OnboardingRepository.setAlarmId`, `RememberOnboardingAlarm`.
- `data/.../settings/DataStoreOnboardingRepository.kt` -- key `onboarding_alarm_id` (cleared by Delete all data).
- `testing/.../OnboardingFakes.kt`, `core/src/commonTest/.../OnboardingTestDoubles.kt` -- the new method.
- `composeApp/.../editor/AlarmEditorViewModel.kt` -- optional `onSaved(id)` after a successful save.
- `composeApp/.../onboarding/OnboardingFirstAlarm.kt` (new) -- the child editor in its own `ViewModelStore`.
- `composeApp/.../onboarding/OnboardingChecklist.kt` (new) -- the child 5.4 checklist, `UsageStatsSources`, picker mapping.
- `composeApp/.../onboarding/OnboardingViewModel.kt`, `OnboardingContract.kt` -- steps 4 to 7, restore rules, dwell, lock notes, 5.12 hook (`LAST_BUILT = Analytics`, `afterUsageStats()`).
- `composeApp/.../onboarding/OnboardingRoute.kt`, `OnboardingSteps.kt`, `OnboardingScreen.kt` -- overlays (editor panes, maker guidance), resume re-check, kept scroll, lock notes.
- `composeApp/.../ui/UiModule.kt`, `androidApp/.../YawnAndPawnApp.kt` -- Koin (one shared editor builder).
- Tests: `OnboardingTest`, `DataStoreOnboardingRepositoryTest`, `SessionLockGuardScanTest`, `AlarmEditorViewModelTest`, `OnboardingViewModelFixture` + `OnboardingViewModelTest` + `OnboardingSetupStepsTest` (split for size), `OnboardingFlowTest`, `OnboardingScreenshotTest` (15 baselines), `OnboardingSemanticsTest`, `OnboardingSamples`.

**Review:** 42 rows (medium 11, low 22, false 9; 4 distinct medium root causes). Patched: the commitment-lock notes on steps 4 and 5 (medium), late overlay taps reaching the editor, step 5's scroll under overlays, a restored step 7 asking again, the pps-design checklist, two coverage gaps (picker mapping, route Back over Check setup); the DND/Battery "Fix", the evaluation race and the camera double ask are resolved by reusing 5.4's checklist. Deferred: 4 (frontmatter). Rejected: the rest, with reasons in the triage log.

**Follow-up review recommended:** false (one medium entry patched, no high). The swap to 5.4's `ReliabilityViewModel` came after the review pass; the updated ViewModel tests and the real-app flow test cover it.

**Residual risks:** the intent contract's matrix row "Fix on DND/Battery re-checks only" is outdated (5.4 now opens their screens; the contract is read-only); the deferred second-alarm windows (failed id write, transient read failure); `qualityGate` runs in CI only; 5.12 must skip step 7 on Back for a user already asked.
