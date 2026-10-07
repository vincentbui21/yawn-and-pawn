---
title: 'Story 3.12: Checks with TalkBack, end to end'
type: 'feature'
created: '2026-10-07'
status: 'done'
baseline_revision: '5c5503b'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-3-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-3-9-fallback-check-picker.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-3-11-qr-barcode-when-the-camera-fails-and-before-first-unlock.md'
warnings:
  - 'Written before 3.5-3.11 and 3.13 are on main. The "exists" column cites the reviewed commits (3.5 dcd0fb8, 3.6 98934c1, 3.7 3394993, 3.8 9b5a38b, 3.9 4899c12 + 056ce0b, 3.10 a74616c, 3.11 spec). Re-check each row against main before you change anything.'
deferred: []
---

<intent-contract>

## Intent

**Problem:**
- NFR-9 and flow F5 promise that a TalkBack user can stop the alarm with taps only, for free.
- Stories 3.2–3.11 each added labels for their own screen. Nothing yet proves the whole path:
  - Ringing;
  - "I'm up";
  - a QR check whose camera is unavailable;
  - the fallback link;
  - the picker;
  - Math;
  - the end of the session.
- Several rules are still missing:
  - a heading on Word Unscramble and on Memory Sequence;
  - focus going back to the first input after a wrong answer;
  - a check that every control has a label, a role and a state;
  - the 200% layout at 360 × 640 dp for every check;
  - a contrast test for the check-screen colour pairs;
  - the Q12 decision record.

**Approach:**
- Fill the small semantic gaps in the approved composables. Semantics only: there is no visual change at 100%, and at 200% only where a check fails the fit rule.
- Add one Robolectric semantics suite that walks every check screen, the Fallback check picker, "Try it" and Success.
- Add a host F5 flow test that drives the real `WakeActivity` by labels and roles only.
- Add a Gradle Managed Device (ATD) F5 test with Compose accessibility checks. The camera is unavailable because the permission was never granted, so it needs no `pm revoke` and no camera.
- Extend the Story 1.3 contrast test with the check-screen pair list.
- Close PRD Q12.

## Boundaries & Constraints

**Always:**
- Tests find nodes by label, role, heading and traversal order only, never by test tag, colour or position. That is how the "needs no sight" AC is checked.
- Every check uses the same approved composables (`CheckScreen.kt`, `WakeComponents.kt`, `WakeEndScreens.kt`, `CheckPreviewScreen.kt`, `ViewfinderColors.kt`). No new strings: the TalkBack strings are already EXPERIENCE.md key strings.
- Preview baselines (`androidApp/src/test/screenshots/preview`) do not change. New Roborazzi baselines may be added. 100% wake baselines change only if a semantic modifier moves pixels, which it must not.
- The AD-2 table gets no new rows. The engine is not touched.

**Never:**
- No androidTest that needs TalkBack itself, the shade, a system app, a camera or `pm revoke` (Epic 2 retro). TalkBack on a real device is Story 3.14, item 14.
- No `adb`.
- No compact grace header. It was deferred to v2 by owner decision 2026-10-06, and this story does not revive it. See the 200% rule below.

## What TalkBack must do, per surface

`E` = exists (where), `M` = missing (this story).

| Surface | Read / announced | Focus order | Live regions | Targets / 200% | Status |
|---|---|---|---|---|---|
| Ringing | the clock as the full time ("6:15"), then "I'm up, button", then snooze with its reason | the clock (traversal −1), "I'm up", then the rest | the phone-call note and the Direct Boot note (3.11) | "I'm up" 72 dp, snooze 64 dp; clock capped at 1.3× | E: `ui/wake/RingingScreen.kt` (`traversalIndex`), `RingingSemanticsTest` (1.15), `CLOCK_XL_MAX_FONT_SCALE` |
| Grace header (every check in Grace) | the ring reads "{seconds} seconds left"; after expiry "Time's up. Alarm's back on until you finish." | the header before the check | the ring is announced politely every 10 s and at 5 s, not every second; the expired line is polite | — | E: `WakeComponents.kt` `GraceHeader` (3.4), `GraceHeaderTest`, `CheckScreenshotTest` |
| Math | the problem as a **heading** in spoken form ("23 times 4 plus 17"); keys by digit; "Delete digit"; "Check"; "Answer {value}" after each key | progress, problem, answer, keys row by row, "Check", then the footer | the answer (polite); "Not quite. Try again." (polite) | 64 dp keys pinned above the footer | E: `CheckScreen.kt` `MathProblem`/`NumberPad` (3.2). **M:** focus back to the first input after a wrong answer |
| Word Unscramble | "Word {n} of {count}"; "Slot {n}, empty" / "Slot {n}, {letter}"; "Letter {letter}"; "Shuffle", "Clear" | progress, slots, pool, actions | the answer so far (polite, 3.7); wrong line (polite) | 48 dp tiles; Shuffle/Clear pinned (3.7) | E: `CheckScreen.kt` `WordCheck`/`AnswerSoFar` (3.7). **M:** the progress line as a heading; focus after a wrong answer |
| Memory Sequence (numbered) | "Watch the sequence" / "Your turn"; the sequence as numbers ("3, 7, 1, 9"); "Tile {number}", disabled during playback | progress, phase, tiles row by row | phase (polite), sequence (polite), wrong line (polite) | 3×3 at 84 dp (numbered is always 3×3) | E: `MemoryCheck`/`MemoryTile` (3.8), `AccessibilityState` + `AndroidAccessibilityState` + `FakeAccessibilityState` (3.8). **M:** phase line as a heading; focus to tile 1 when "Your turn" starts after a wrong tap; a check-screen test with TalkBack on |
| QR/Barcode | "Scan your code" (heading); "Camera viewfinder. Point at your code."; "Torch" switch on/off; wrong-code line; unavailable message | heading, viewfinder, torch, line, footer | wrong code (polite), unavailable (polite) | torch 48 dp | E: `QrCheck` (3.10), torch state and link timing (3.11) |
| Footer | "Can't do this check?" (button); snooze with its reason | after the check | — | link ≥ 48 dp, snooze 64 dp, both on screen at 200% | E: `CheckFooter` (3.9) |
| Fallback check picker | "Pick a fallback check" (heading); "Back to check"; each card read once as name + description, role button; Math first | title, close, Math, Word Unscramble, Memory Sequence | — | cards ≥ 64 dp, close 48 dp | E: `WakeEndScreens.kt` `FallbackPickerScreen` (3.9). **M (check):** the Memory card starts the numbered variant (`CheckType.fallbackChoices` after the 3.8 rebase) |
| "Try it" | "Try it" (heading); "Back"; the same check semantics; "Nice. That's how it works."; "Done" | heading, Back, check, done card | the done card (polite) | as the check | E: `CheckPreviewScreen.kt` (3.6). **M:** included in the suite |
| Success | the headline as a heading first, then "Done" | headline, Done | — | Done 72 dp in the thumb zone | E: `SuccessScreen` (3.3), `SuccessScreenTest` |

**First input after a wrong answer (default taken, owner can change):**
- Math: the "1" key, the first key in reading order. The field is empty again.
- Word: the first letter tile in the pool.
- Memory: tile 1, when "Your turn" starts again after the replay.
- QR: none. A scan is not a tap, and the polite line is enough.

The move uses `FocusRequester.requestFocus()` keyed on the failed-attempt count. Compose sends the focus event that TalkBack follows. Whether TalkBack follows it on the Oppo A96 is a device risk, listed under 3.14 item 14.

**Headings (default taken):**
- Word has no instruction line, so "Word {n} of {count}" becomes its heading.
- Memory's phase line ("Watch the sequence" / "Your turn") becomes a heading and stays a polite live region.
- Math (problem), QR ("Scan your code"), the picker (title), "Try it" (title) and Success (headline) already have one.

**200% rule (default taken):**
- At 360 × 640 dp and 200% font, the primary input, the fallback link when shown and the snooze control must lie fully inside the window with the scroll at 0. The primary input is: Math's pad and "Check"; Word's pool, "Shuffle" and "Clear"; Memory's grid; QR's viewfinder and torch.
- A check that fails gets Math's Story 3.2 pattern: the input is pinned above the footer, and the header and instruction scroll. Where everything fits, it looks the same, so 100% baselines do not change.
- If the input and the footer alone do not fit, record it for the owner next to the v2 compact-header item in `deferred-work.md`. Do not redesign.

</intent-contract>

## Base

- Build on `main` after 3.11 merges. That means 3.5–3.8, 3.9 + 3.13, 3.10 and 3.11 are all on main.
- 3.12 edits the same composables and `WakeCheck`/`WakeActivity` as 3.11 (torch state, link timing), so do not run it in parallel with 3.11 (Epic 2 retro, action 3).
- First pass: re-run every "E" row above against main, by reading the code and running the existing tests. Turn any regression into an "M" row in the review notes.

## Code Map

- `composeApp/.../ui/wake/CheckScreen.kt`:
  - `heading()` on Word's progress line and Memory's phase line;
  - `FocusRequester`s for the first input, keyed on the failed attempts: `CheckContent.Math.wrong`, `WordUnscramble.wrong`, `MemorySequence.wrong` + phase;
  - the pinned-input pattern for any check that fails the 200% rule.
- `composeApp/.../ui/wake/WakeContract.kt`: a `wrongAttempts` key on the Math, Word and Memory content if one is missing (QR has it from 3.10), so the focus move fires on every wrong answer, not only the first.
- `core/.../checks/CheckType.kt`: confirm `fallbackChoices` = [Math, WordUnscramble, MemorySequence(numbered = true)]. Fix it if the 3.8 rebase left the plain variant (epic 3.9: "Memory Sequence in its numbered accessible variant").
- `androidApp/src/test/.../ui/CheckSemanticsSuiteTest.kt` (new, Robolectric):
  - Screens: Math (Easy and Hard), Word, Memory (numbered, watch and turn), QR (scanning, wrong, unavailable with the link), the picker, "Try it" for each type, and Success (each variant).
  - Rules for every node with `OnClick`: a non-empty label, a `Role`, and no action that is reachable only by long click or a custom action.
  - Toggleables (torch) and disabled tiles expose their state.
  - Exactly one heading before the first input.
  - Live regions are present (polite) where the table says.
  - Traversal order follows the table.
  - After a wrong answer, the first input `assertIsFocused()`.
- `androidApp/src/test/.../ui/CheckFontScaleTest.kt` (new, Robolectric + Roborazzi, `w360dp-h640dp`, font 2.0):
  - every check in Grace (the tallest header) and in Loud, plus the picker and Success;
  - bounds assertions for the primary input, the link and snooze;
  - the Ringing clock's text size is at most 1.3× its 100% size.
- `androidApp/src/test/.../android/wake/TalkBackF5FlowTest.kt` (new, Robolectric, the real Koin graph through `WakeApp`, `FakeCodeScanner(permitted = false)`, `FakeAccessibilityState(on = true)`):
  - a QR alarm fires;
  - initial traversal: the clock, then "I'm up";
  - tap "I'm up";
  - the unavailable message and "Can't do this check?" exist in the first frame;
  - tap the link; the first card is Math;
  - for each of the 6 problems, the heading's content description matches the spoken pattern (`^\d+ (plus|minus|times) \d+( (plus|times) \d+)*$`), the answer is typed by key labels, "Answer {value}" is checked, and "Check" is tapped;
  - the session ends and the player is released.
- `androidApp/src/test/.../android/wake/MemoryTalkBackTest.kt` (new): with `FakeAccessibilityState` on, a Memory alarm rings in the numbered variant (numbers on every tile, the sequence announced). With it off, plain.
- `androidApp/src/androidTest/.../FallbackTalkBackDeviceTest.kt` (new, GMD `atdApi34`):
  - Compose accessibility checks enabled (`enableAccessibilityChecks()`);
  - a debug alarm with a QR/Barcode plan and a fixed registered code;
  - `CAMERA` is never granted on the fresh test install, and `assumeTrue` checks that;
  - the same steps as the host F5 test, with the answers from `DebugCheckAnswer`;
  - the alarm stops.
- `androidApp/src/debug/.../debug/fire/DebugFire.kt`: `FireRequest` gains an optional check plan (debug only; `checkReleaseContent` keeps it out of release).
- `composeApp/src/androidHostTest/.../ui/theme/CheckScreenContrastTest.kt` (new): the check-screen pair list, checked against the DESIGN.md table with the Story 1.3 parser (`ContrastRow`). A pair missing from the table fails the test.
- `docs/decisions/q12-accessible-fallback.md` (new). `docs/prd.md` Q12 gets "closed, see decision".
- Dependencies: `androidx.compose.ui:ui-test-junit4-accessibility` (the androidx version that Compose Multiplatform 1.12.1 maps to) and its Accessibility Test Framework, `androidTest` only. Add them to `config/dependency-allowlist.txt` in the same change.

## Implementation notes (the final 3.10/3.11 code and what 3.12 found)

Built on `story/3-11-qr-camera-fails-and-before-unlock` (5c5503b: main 7315457 + 3.10 + 3.11 with review fixes). Fast mode: every item below is "default taken, owner can change".

**"E" rows re-verified, no regressions.** 3.11 already made the torch a `Role.Switch` with its state, keeps the latched link and open picker in the `WakeKept` ViewModel, and has `WakeCamera` and the Direct Boot `wakeNote`; QR already had `wrongAttempts`. None changes this plan.

**Changes to the plan:**
- **`fallbackChoices` stays as it is.** The picker maps types by id, so the numbered variant is chosen where a card is turned into the fallback: `WakeFallback.asFallback` now always gives Memory Sequence `numbered = true`, TalkBack on or not (the 3.9 code numbered it only with TalkBack on). `WakeCheck` no longer needs `AccessibilityState`.
- **Focus after a wrong answer.** `focusRequester` + `focusable()` on the first input only ("1", the first pool letter, tile 1). `clickable` alone can't take focus in touch mode. No pixels change, and the focus moves are instant. Memory moves focus when "Your turn" starts after the replay, never while the tiles are disabled.
- **The 200% rule failed for three checks.** They now use Story 3.2's pinned-input pattern: Memory's grid (Loud: the expired line pushed rows 3 below the window), Word's letters (a 10-letter word's second row) and QR's viewfinder (its bottom 16 dp). Each is pinned above the footer while the header and instruction scroll. In a window shorter than 480 dp the input scrolls with the rest, as Math's pad does. When everything fits it looks the same: the preview baselines and every 100% baseline are unchanged. Three 200% baselines change on purpose, because their input used to be cut off by the scrolling area and is now whole: `wake_check_memory_wrong_sunrise_font200`, `wake_check_word_hard_sunrise_font200` and `wake_check_word_wrong_sunrise_font200`. At 200% on 360 × 640 the grace header fills most of the scrolling area. That is the v2 compact-header item and is noted in `deferred-work.md`.
- **The picker at 200%.** Its close button and Math (first, always offered) are on screen without scrolling. Word and Memory scroll into view; it is a list, and TalkBack scrolls it.
- **The fallback link contrast.** `accent-text` sits in the flat thumb zone on `bg-sunrise` (5.06:1). `AccentTextPlacementTest` computes the background behind the top of every `accent-text` label from the gradient (link, "Shuffle", "Clear", also in "Try it") at 411 × 891, 360 × 640 at 200% and 640 × 360. All pass 4.5:1, so no token change.
- **Contrast pairs.** Besides the listed pairs, the check screens also draw `text` and `text-secondary` on the gradient and on `bg`, `text / surface` (memory numbers) and `outline / bg` (snooze border). All of them are already table rows, so DESIGN.md needs no new row.
- **Accessibility checks.** `enableAccessibilityChecks()` is not supported under Robolectric (the library logs a warning and checks nothing). The host suite checks the rules by hand. Only the GMD test uses the Accessibility Test Framework, on API 34+.
- **F5 on the managed device needs no camera, `pm revoke` or system app.** The test swaps the Koin `CodeScanner` for one that reports `NoPermission`. A GMD install may grant runtime permissions, so `assumeTrue` on CAMERA could skip the test. The test makes its QR alarm with `SaveAlarm` (a `checks` list with a code) and fires it with the existing `DebugFire.FireRequest(alarmId)`, so `DebugFire` gets no plan option.
- **Dependency allowlist.** It covers the runtime classpath only ("test-only dependencies never appear here"), so the androidTest-only accessibility artifact is not listed.

## Tasks & Acceptance

**Execution:**
- [x] Re-verify the "E" rows on main (read the code, run the existing tests). List any regressions.
- [x] Headings, first-input focus and the `wrongAttempts` keys. Check `fallbackChoices` for the numbered Memory.
- [x] `CheckSemanticsSuiteTest`, `MemoryTalkBackTest`, `TalkBackF5FlowTest`.
- [x] `CheckFontScaleTest` with the new Roborazzi baselines (`a11y_*_w360_h640_font200`). Apply the pinned-input pattern only where it fails.
- [x] `CheckScreenContrastTest` with these pairs. Confirm on screen where each is drawn and correct the list from the code:
  - `text / glass+sunrise-gradient-top` (the header card, the unavailable card, the picker cards);
  - `text-secondary / glass+sunrise-gradient-top` (picker descriptions);
  - `error / sunrise-gradient-top` and `error / bg` (the wrong-answer line);
  - `accent / glass+sunrise-gradient-top` (the countdown ring);
  - `accent / surface` (a lit memory tile);
  - `on-accent / accent` (the lit tile number and "Check");
  - `outline / surface` (the memory tile border);
  - `text / surface-variant` (pad keys and letter tiles);
  - `accent-text / bg` (the fallback link; on `sunrise-gradient-top` it fails at 4.39, so confirm the footer sits on `bg`);
  - `disabled-content / disabled-container` (disabled snooze);
  - `inverse-text / inverse-surface` (the viewfinder overlay).
  A pair the list needs that is not in the table: compute it with the test's helper and add the row with its ratio to DESIGN.md. If it fails its limit, do not change visuals; record it in `deferred-work.md` for the owner (default taken).
- [x] `FallbackTalkBackDeviceTest`, the `DebugFire` plan option and the dependency allowlist.
- [x] `q12-accessible-fallback.md`: the TalkBack path, which is:
  - the link at once when the camera is unavailable, else after 5 failures;
  - Math first, with spoken problems, announced keys and the announced answer;
  - Memory Sequence numbered and announced, chosen automatically when TalkBack is on;
  - Word with spoken tiles;
  - no timing and no gestures beyond a tap;
  - the device check in 3.14 item 14.
  Then mark Q12 closed in `docs/prd.md`, linking the decision.

**Acceptance Criteria:**
- Given an instrumented test on the Gradle Managed Device with Compose accessibility checks enabled, when it runs flow F5 (a debug alarm with a QR/Barcode check and no camera permission), then:
  - initial focus is the clock, then "I'm up";
  - after "I'm up", the camera message and the fallback link are focusable immediately;
  - the Fallback check picker lists Math first;
  - the Math problem reads its spoken form, and the pad announces its keys and the answer;
  - "Check" completes the session and the alarm stops;
  - no step needs sight, a timed gesture or anything but a tap (every actionable node has a label and a role).
- Given every check screen, the Fallback check picker and the Success screen, when the semantic tests run, then:
  - every control has a label, a role and a state;
  - the problem or instruction is a heading;
  - wrong-answer feedback and the grace countdown are polite live regions;
  - focus order follows reading order and goes back to the first input after a wrong answer;
  - the numbered Memory Sequence variant is chosen automatically when TalkBack is on (`FakeAccessibilityState`).
- Given 360 × 640 dp at 200% font, when Roborazzi records every check screen, the picker and Success, then the primary input, the fallback link when shown and the snooze control are all on screen without scrolling, and the clock stays capped at 1.3×.
- Given the DESIGN.md contrast table, when the Story 1.3 contrast test runs, then it also checks the list of colour pairs used by the check screens (including `error-sunrise` feedback, lit memory tiles and the countdown ring), and it fails on any pair missing from the table.
- PRD Q12 is closed in `docs/decisions/q12-accessible-fallback.md`.
- The `pps-design` Done checklist below is ticked. All strings are resources, key strings match EXPERIENCE.md verbatim, and `CopyRulesTest` passes (FR-MSG-4).
- `./gradlew qualityGate :androidApp:assembleDebugAndroidTest --no-daemon` passes. The GMD test passes in CI (`atdApi34DebugAndroidTest`).

## pps-design Done checklist

- [x] Only tokens from `DESIGN.md` used. Semantic changes only, plus pinned inputs where 200% fails.
- [x] Sunrise screenshots at 100% (unchanged) and 200% at 360 × 640 for every check, the picker and Success.
- [x] Every colour pair used is in the contrast table, which `CheckScreenContrastTest` now enforces.
- [x] Targets: wake actions ≥ 64 dp, tiles, torch and link ≥ 48 dp (asserted in the suite).
- [x] 200% font and TalkBack: the F5 flow by labels only; headings; live regions; focus after a wrong answer.
- [x] Reduced motion: the focus moves are instant; no new animation.
- [x] Copy verbatim from EXPERIENCE.md Key strings; no new strings.
- [x] State rows: "Large font (200%) and TalkBack" (all), wrong answer, camera unavailable, fallback.
- [x] "I'm up" is still the first action after the clock, and snooze is visible and plain.
- [x] Previews unchanged; new Roborazzi baselines (`a11y_*`).

## Design Notes

**Defaults taken in fast mode (the owner can change any of them):**
- **F5 without `pm revoke`.** The epic says `pm revoke`. Revoking a runtime permission kills the app process, and that would end the instrumentation run. A fresh test install never has `CAMERA` granted, which is the same state F5 describes (a cleanup revoked it). The test assumes that state, and the camera adapter reports `NoPermission` exactly as after a revoke. The ATD image probably has no camera anyway (epic 3 context, "Testing on CI").
- **The headings, the first-input focus targets and the 200% rule** are as described in the intent above.
- **Fallback Memory is always numbered**, as the epic's 3.9 text says, not only when TalkBack is on.
- **Accessibility checks on the host.** `enableAccessibilityChecks()` is meant for instrumented tests. If it also runs under Robolectric 4.17, turn it on in `CheckSemanticsSuiteTest` too. If not, the host suite checks the same rules by hand and only the GMD test uses the Accessibility Test Framework.

**Open questions for the owner (not blocking):**
- Is "Word {n} of {count}" the right heading for Word, or should EXPERIENCE.md add an instruction line ("Unscramble the word")? Adding the line would be a new key string.
- If a check cannot fit its input and the footer at 200% on 360 × 640 even with the header scrolled, does it join the v2 compact-header item?

## Verification

**Commands:**
- `./gradlew qualityGate :androidApp:assembleDebugAndroidTest --no-daemon` -- expected: BUILD SUCCESSFUL.
- `git status --porcelain androidApp/src/test/screenshots/preview` -- expected: empty.
- CI: `:androidApp:atdApi34DebugAndroidTest` -- expected: `FallbackTalkBackDeviceTest` and `MathCheckDeviceTest` pass.

**Device (human-verify, Story 3.14 — not run here):**
- Item 14: F5 with TalkBack on, end to end without looking, on at least two devices. Also confirm that focus goes back to the first input after a wrong answer, and that the torch reads its state.
- Item 15: at 200% on the smallest device, every check keeps its main input and the snooze control on screen.
