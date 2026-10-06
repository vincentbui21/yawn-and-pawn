---
title: 'Story 3.2: Solve Math to stop the alarm'
type: 'feature'
created: '2026-10-06'
status: 'done'
baseline_revision: '0a58a00'
review_loop_iteration: 1
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-3-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-3-1-check-plugin-contract-and-the-math-generator-in-core.md'
warnings: []
deferred:
  - 'A compact grace header on 360 × 640 at 200% font, so the problem is not below the fold: version 2 (owner decision 2026-10-06, deferred-work.md).'
---

<intent-contract>

## Intent

**Problem:** After Story 3.1, "I'm up" still ends every session through the Placeholder step. The wake screen answers it for the user, so no one has to be awake.

**Approach:** Production gets the real check:
- `PluginCheckValidator` is wired in Koin.
- Every alarm and the test alarm get the default plan Random · Math · Medium · 3.
- The Direct Boot check becomes Math · Medium · 3.

In Grace and Loud the wake screen renders the approved `CheckScreen` (`check-math`, `check-math-wrong`). The screen comes from the engine state through a pure mapper, and the typed digits are UI-only state. The engine emits `StartCheckStep` when the check moves to the next entry.

## Boundaries & Constraints

**Always:**
- The UI never decides correctness: "Check" sends `CheckAnswerSubmitted(Number)`. The screen sees a wrong answer only as more failed attempts on the same entry.
- The approved composables are reused: Math gains operands and operator lists (Medium and Hard), and the number pad sits outside the scrolling area. Where everything fits, the screen looks the same, so the preview baselines do not change.
- The strings are the EXPERIENCE.md Key strings, verbatim.
- A session stored by Epics 1–2 (Placeholder entries) still decodes and still ends with "I'm up" (the screen answers the placeholder).
- WakeActivity changes stay focused, so they merge easily with Story 3.3's success branch.

**Never:** No Success screen (3.3), countdown ring announcements or haptic ticks (3.4), fallback link behaviour (3.9) or Direct Boot note (3.11). No new AD-2 rows. No androidTest that needs the shade, system apps or a camera.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Solve | I'm up, three right answers on the pad | Grace with "Problem 1 of 3" → 2 → 3 → Completed; the screen closes | — |
| Wrong | wrong digits + Check | field cleared, "Not quite. Try again." (polite), shake 200 ms + error haptic, failed attempts +1 | — |
| Empty Check | no digits | nothing submitted (`UserInteracted` only) | — |
| Long input | 6 digits | the field keeps 5 | — |
| Countdown | Grace, 6 s after I'm up | "Quiet for 14s. …" from the grace `Deadline`; frozen at the call pause | — |
| Loud | grace ended / merged ring | "Time's up. Alarm's back on…" / no header | — |
| Leave and return | Home after a solved problem with a digit typed; the notification or "Back to alarm" | "Problem 2 of 3", empty field | — |
| Test vs real alarm | a real alarm during a Math test | the service answers the test's check, the test is recorded as Test, the real session starts | stays ringing if a step cannot be saved (as before) |
| Old session | stored Placeholder entry | the Ringing screen, answered by "I'm up" | — |

</intent-contract>

## Code Map

- `core/.../checks/CheckPlan.kt`: `DEFAULT_ENTRY` and `default()`. `ConfigResolver` uses it, and `DIRECT_BOOT_CHECK` = `DEFAULT_ENTRY`.
- `core/.../session/CheckRules.kt`: `ValidNext` emits `StartCheckStep(entry + 1)` (deferred item). `SessionPolicies.kt`: `PlaceholderCheckValidator` is removed.
- `composeApp/.../ui/wake/CheckMapping.kt` (new): `mathCheckUiState`, `CheckPosition`, `CheckInput`.
- `composeApp/.../ui/wake/CheckScreen.kt` and `WakeContract.kt`: Math operand lists, Minus, nullable grace, the pinned pad, the shake, haptics and the live answer region.
- `androidApp/.../wake/WakeActivity.kt`: renders `WakeScreen.Check`, handles the keys and runs the grace clock. `WakeService.kt`: `endTestSession` answers the test's check.
- `androidApp/src/debug/.../DebugCheckAnswer.kt`: the debug-only answer hook. `androidTest/.../MathCheckDeviceTest.kt` runs on the GMD.
- `:testing` `CheckAnswers.kt` (`rightAnswer`, `wrongAnswer`). `WakeApp.solveCheck()` replaces the placeholder answers in host tests.

## Tasks & Acceptance

**Execution:**
- Core default plan, Direct Boot check, `StartCheckStep`, validator wiring. The transition-table examples are updated on purpose: R09 and R21 now emit `StartCheckStep(1)`.
- UI mapping and screen, with `CheckMappingTest` (composeApp), `MathCheckScreenTest` (Robolectric, wake screen and notification return), and `CheckScreenshotTest` (Roborazzi Sunrise at 100% and 200%, plus 360 × 640 at 200%).
- `MathCheckDeviceTest` (GMD ATD API 34): debug fire, I'm up, solve with `DebugCheckAnswer`, the alarm stops.

**Acceptance Criteria:**
- Given `./gradlew qualityGate`, then BUILD SUCCESSFUL. The Kover gates (core, session, checks) pass, and `git status --porcelain androidApp/src/test/screenshots/preview` is empty.

## pps-design Done checklist

- [x] Only tokens from `DESIGN.md` used (no raw hex, no new radii, no new font sizes). The shake distance is `space2`.
- [x] Light, Dark and Sunrise where relevant: wake screens are Sunrise only, in screenshots at 100% and 200%.
- [x] Every colour pair used is in the `DESIGN.md` contrast table (no new pair; the approved `check-math` and `check-math-wrong` tokens).
- [x] Touch targets ≥ 48 dp; wake actions ≥ 64 dp (every key asserted ≥ 64 dp; snooze unchanged).
- [x] Works at 200% font scale and with TalkBack: on 360 × 640 at 200%, "Check" and snooze stay displayed. The problem is a heading read in words, backspace reads "Delete digit", "Answer {value}" is a polite live region, and the keys have role button. No outcome glyphs on this screen.
- [x] Reduced-motion path works: the shake is a Compose animation, instant with animator duration scale 0.
- [x] Copy matches `EXPERIENCE.md > Voice and Tone`. "Problem {n} of {count}", "Delete digit", "Answer {value}", "Not quite. Try again.", "Quiet for {seconds}s. …" and "Time's up. …" are verbatim. The operator words are "plus", "minus" and "times" ("minus" owner-approved 2026-10-06 and added to EXPERIENCE.md).
- [x] Every state row for this surface is handled: Grace, Grace expired (Loud), the phone call note. The Direct Boot note is Story 3.11.
- [x] "I'm up" is the most prominent action on Ringing (unchanged). On the Check screen snooze is visible, plain and priced (the same `button-snooze`).
- [x] Previews exist (`check-math`, `check-math-wrong`, unchanged), and the Roborazzi screenshots are added (`wake_check_math_*`).

## Design Notes

- **Minus:** "minus" (Easy `a − b`) was not in EXPERIENCE.md Key strings, which listed "plus" and "times". The owner approved it on 2026-10-06, and EXPERIENCE.md (docs and planning copies) now lists "plus" / "minus" / "times". No production plan uses Easy until Story 3.5.
- **Wrong answers:** they are detected from state (failed attempts going up), not from the `WrongAnswerFeedback` effect. A recreated screen has no effect channel but still shows the right state.
- **Ending a test:** `WakeService.endTestSession` now answers the test's check from its seed. This is the only place outside tests that knows an answer, and it applies only to test sessions.

## Verification

**Commands:**
- `./gradlew qualityGate` -- expected: BUILD SUCCESSFUL.
- `git status --porcelain androidApp/src/test/screenshots/preview` -- expected: empty.

### Review (2 reviewers, fast mode)

Two reviewers read the first commit, then the branch was rebased onto `main`: first `0a58a00` (Story 3.1 with its review fixes), then `0c415e7` (Story 3.3, the basic Success screen). In `WakeActivity`, 3.3's `WakeScreenEnd` / Success branch is kept, and its non-Success branch renders the Check screen. Flow tests that solve the check now end on Success and "Done". Their findings were triaged as follows. All the patches are in `fix(3.2): review fixes`.

- **patch: a double "Check" tap was a false wrong answer.** A second tap before the next problem recomposed sent the same digits against it. `WakeCheck.onKey` now clears the field on submit, so the second tap sends nothing (`WakeCheckTest`).
- **patch: a key tapped just after the engine moved on was lost.** The digit landed on the old position and the next recomposition cleared it. `onKey` now moves the input to the engine's current position first (`WakeCheckTest`).
- **patch: an ended check flipped to the Ringing screen.** In Completed or Missed, with the ringing notification still posted, the screen fell back to the alarm-only Ringing screen before it finished. Since 3.3, Completed shows Success, so Missed is the case that is left. The notification's alarm time now stands in only before the screen has shown a session, so the last Check screen stays (`MathCheckScreenTest`, a check that ends Missed).
- **patch: a damaged row could not be stopped.** Grace or Loud on a Math entry with a missing seed or an item past the end showed the Ringing screen, where "I'm up" is ignored. Core's new public `CheckRun.usable` derives the missing seeds (`withMissingSeeds`, as the reducer already does) and moves the item into the puzzle (`withItemInPuzzle`: past the end, the last item). `PluginCheckValidator` checks the answer on that item and the mapper shows that problem, so the screen and the engine agree, and the last item's answer passes the entry (`CheckPluginSessionTest`, `CheckMappingTest`). The stored step is not rewritten, so the AD-2 table examples are unchanged.
- **patch: "Check" and snooze could be pushed off a short window.** Locking `WakeActivity` to portrait was rejected: Android 16 ignores orientation locks on large screens, and split screen stays short. Below 480 dp of content height, the number pad now scrolls with the problem, and the snooze footer stays pinned. Every phone in portrait keeps the pinned pad, so the baselines are unchanged. New screenshots: `wake_check_math_grace_sunrise_w640_h360` at 100% and 200%.
- **patch: ending a test could send 100 ignored answers.** If the test's "I'm up" was not saved, the test was still Ringing and `endTestSession` kept answering. It now answers only in Grace or Loud, and stops when an answer does not move the run (`TestAlarmFlowTest`).
- **patch: verification gaps.**
  - `TestAlarmFlowTest`: a real alarm during a test whose plan is `CheckPlan.default()` (Math). The test row is Test, the real session rings with `testMode = false`, and there is no merge row.
  - `MathCheckScreenTest`, empty Check: the test waits for the interaction to reach the engine before it asserts no failed attempt.
  - `MathCheckScreenTest`: the live grace countdown moves from 20 s to 13–14 s after 6 s.
- **patch: `MathCheckDeviceTest`.** It now waits per problem for the step or the failed attempts to change, and asserts that a right answer never counts as a failure. It checks that the alarm plays before "I'm up", that the session is not a Ring at the end, and that the ring is over with its sound released. It deletes the synthetic "Debug fire" alarm.
- **verification after the fixes and the rebase onto `0c415e7`:** `./gradlew qualityGate :androidApp:assembleDebugAndroidTest` gives BUILD SUCCESSFUL. One earlier run had a flaky bitmap capture in `RingingSemanticsTest`, a screen this story does not change, and the rerun passed. The Kover gates pass, and the preview baselines are unchanged.
- **owner decisions (2026-10-06):** the TalkBack word "minus" is approved and added to EXPERIENCE.md. The 360 × 640 at 200% grace header that pushes the problem below the fold is deferred to version 2 (`deferred-work.md`).

## Auto Run Result

Status: implemented in fast mode (one agent), waiting for review. Branch `story/3-2-solve-math-to-stop-the-alarm`, stacked on Story 3.1 (`ad9736b`, in review).

**Summary:** Production now rings Random · Math · Medium · 3 (alarms and the test alarm), validated by `PluginCheckValidator`, and the Direct Boot check is Math · Medium · 3. The wake screen renders the approved Check screen in Grace and Loud. It follows the engine, so it shows the current problem whether it was opened from the notification or from "Back to alarm" after Home. `ValidNext` emits `StartCheckStep(entry + 1)`. `PlaceholderCheckValidator` is gone, while `CheckType.Placeholder` stays for stored Epic 1–2 sessions and for tests that are not about the check. The check part of `WakeActivity` lives in the new `WakeCheck.kt`, which keeps the activity diff small for Story 3.3's merge.

**Verification:**
- `./gradlew qualityGate :androidApp:assembleDebugAndroidTest` gives BUILD SUCCESSFUL. The first run hit a Kotlin incremental-compilation error in `:composeApp:compileAndroidHostTest`, and the rerun passed.
- The Kover gates are green, including `koverVerifyChecks`. The preview baselines are unchanged.
- There are 10 new Roborazzi baselines (`wake_check_math_*`).
- `MathCheckDeviceTest` compiles but has not run locally (no emulator on this machine); it runs on the CI GMD.

**Residual risks:**
- **Small phone at 200%:** on 360 × 640 at 200% font, the grace header fills the scrolling area, so the problem is below it and must be scrolled to. "Check" and snooze stay on screen, as required. A compact header is deferred to version 2 (owner decision 2026-10-06).
- **The word "minus":** approved by the owner on 2026-10-06.
- **Device test unproven:** the GMD test depends on the debug alarm ringing on the ATD image (exact alarm, then the foreground service). It opens the wake screen directly rather than through the full-screen intent.
