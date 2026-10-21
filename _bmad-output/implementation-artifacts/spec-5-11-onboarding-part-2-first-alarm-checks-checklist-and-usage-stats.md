---
title: 'Story 5.11: Onboarding, part 2: first alarm, checks, checklist and usage stats'
type: 'feature'
created: '2026-10-21'
status: 'ready-for-dev'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-5-context.md'
  - '{project-root}/.claude/skills/pps-design/SKILL.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-5-10-onboarding-part-1-mission-alarm-behaviour-disclosure-and-base-fee.md'
warnings: ['oversized']
deferred: []
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
- `composeApp/.../ui/reliability/ReliabilityScreen.kt` -- `ChecklistItem`, `ItemStatus`, `ChecklistRow`, `ChecklistCard` (approved). 5.4's `toChecklistRows`/`CoreRow.item` are NOT on main: keep the mapping private to onboarding.
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

## Design Notes

- **DEFAULT TAKEN (owner can change):** the first alarm opens at the editor's new-alarm time 7:00 (the round 3 preview's 7:30 is sample data from flow F1).
- **DEFAULT TAKEN:** with no check, "Continue" is disabled (approved design, EXPERIENCE.md) and "Pick at least one check." shows under the picker (epic).
- **DEFAULT TAKEN:** until Story 5.12, onboarding completes after the usage stats choice; a user already asked skips step 7.
- **DEFAULT TAKEN:** until Story 5.4 merges, "Fix" on Do Not Disturb and Battery only re-checks (5.4 brings their deep links: on rebase switch onboarding to `ReliabilitySettings.open(item.row)` and 5.4's row mapping).
- **Child editor, not a copy:** steps 4 and 5 are the editor's form, so check toggles, camera asks, Check setup, "Try it", QR registration, save validation and the notification ask behave exactly as in the editor. The child lives in its own `ViewModelStore` (`ViewModelProvider.create(store, viewModelFactory { initializer { ... } })`), so its scope ends with onboarding or when a fresh child opens on the saved id. Sketch:
  - opening step 5 sends `PaneOpened(WakeCheck)` (TalkBack refresh, and Check setup's Back returns there); picker intents map like the editor's `WakeCheckPane`;
  - "Continue" on step 5 sends `SaveClicked`; `onSaved(id)` moves to step 6 at once and queues storing the id, then the step; the child that saved is left alone (it still runs the notification ask) and a fresh child on the id opens only when the user goes back to step 4 or 5;
  - the overlay shows while step 5 is open and the child's pane is `CheckSetup`, `TryIt` or `ScanCode`; Back there goes to the child (`BackRequested`); the route keeps the last overlay state while it slides out;
  - child effects: `ShowSaveFailed` → onboarding snackbar ("Couldn't save the alarm. Try again."); `OpenFailed` → fresh new-alarm child and the stored id cleared; the rest are ignored (`Close` follows `onSaved`).
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
