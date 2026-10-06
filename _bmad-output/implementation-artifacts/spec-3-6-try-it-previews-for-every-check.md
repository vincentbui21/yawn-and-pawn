---
title: 'Story 3.6: "Try it" previews for every check'
type: 'feature'
created: '2026-10-06'
status: 'done'
baseline_revision: 'dcd0fb8'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-3-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-3-5-choose-the-checks-for-each-alarm.md'
  - '{project-root}/.claude/skills/pps-design/SKILL.md'
warnings:
  - 'shares-3.2-ui: the Math presentation changes of Story 3.2 (f88e4f6) are applied verbatim to the same files: CheckContent.Math operand and operator lists, Minus, nullable grace, the shake and haptics, the pinned pad, math_word_* strings, and the preview samples. Merged in either order, the identical hunks fold together; if 3.2 changes them in review, take 3.2''s version.'
deferred: []
---

<intent-contract>

## Intent

**Problem:** "Try it" in Check setup does nothing. A user cannot practise tomorrow's check before saving it (FR-PWK-12).

**Approach:**
- **What it opens:** "Try it" pushes the approved `CheckPreviewScreen` (Sunrise, the "Try it" heading, Back) over the editor. It shows the check at the difficulty currently set in Check setup, with count 1.
- **Where the puzzle comes from:** a `CheckRegistry` in `:composeApp` maps each check type to its trial, which turns the core puzzle into the screen content. It starts from a seed the editor ViewModel draws, not `:core`. The core `validate` alone decides correctness.
- **Wrong answers** look the same as on the real check: the cleared field, "Not quite. Try again.", the shake and the error haptic.
- **Solved** shows "Nice. That's how it works." with "Done". "Done" or Back returns to Check setup with the unsaved editor state intact.

## Boundaries & Constraints

**Always:**
- **No stakes:** a preview never ringing, never touching the session, never charging, never writing history and never scheduling follows from its design. The trial is a pure value in the editor ViewModel, with no engine, player, store, history or scheduler. An app-level Robolectric test proves it over the real Koin graph.
- **Reuse only:**
  - the approved `CheckPreviewScreen` (the done state uses its 64 dp `WakePrimaryButton` "Done", as approved);
  - `CheckContentView` and `MathProblem`;
  - the existing strings: "Try it", "Back", "Nice. That's how it works.", "Done", "Problem {n} of {count}", "Not quite. Try again.", "Check" and "Delete digit".
- **Registry:** a type is pickable only when it has a core plugin and a registered trial. A test checks that every type the picker lists has one.

**Never:**
- No session event, sound, grace window, snooze footer, history row or scheduler call.
- No new strings and no new design.
- No "Try it" on the picker's `check-type-card` rows: the round 3 approved picker has none, so it lives in Check setup only (owner-approved layout, EXPERIENCE.md "Check setup … Try it").
- Nothing seeded in `:core`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Open | Check setup Math · Hard · 5, "Try it" | Sunrise preview, "Problem 1 of 1", Hard problem `generate(seed, Hard, 1)` | — |
| Wrong | wrong digits + Check | field cleared, "Not quite. Try again." | — |
| Empty | Check with no digits | nothing | — |
| Long | 6 digits | the field keeps 5 | — |
| Right | the answer + Check | "Nice. That's how it works." + Done | — |
| Leave | Done / Back (any time) | Check setup, form unchanged, no unsaved change added | — |
| No stakes | a full preview run | engine Idle, store commits 0, history empty, nothing armed, no media player | — |

</intent-contract>

## Code Map

- **From 3.2, verbatim:** `composeApp/.../ui/wake/{WakeContract,CheckScreen}.kt`, `strings.xml` (`math_word_*`), and `androidApp/src/debug/.../preview/*` (the samples).
- `composeApp/.../ui/checks/CheckRegistry.kt` (new) -- `CheckTrial`, `MathTrial` and `CheckRegistry`. `PickableCheckTypes` also requires a registered trial.
- `composeApp/.../ui/editor/*` -- `EditorPane.TryIt`, `EditorUiState.tryIt`, `EditorIntent.TryIt`, the ViewModel's `previewSeed`, and the screen rendering `CheckPreviewScreen`.
- Tests: `CheckRegistryTest` (commonTest), `AlarmEditorTryItTest` (VM), `TryItNoStakesTest` (Robolectric, real Koin graph) and `TryItScreenshotTest` (Roborazzi `try_it_math_{running,done}_sunrise[_font200]`).

## Tasks & Acceptance

**Execution:**
- Bring 3.2's presentation pieces over verbatim, unchanged, so the preview baselines do not change.
- The registry and Math trial, with tests.
- The editor wiring, with VM tests (open at the set difficulty, wrong, right, Done/Back keeps the form).
- The no-stakes app test and the screenshots.

**Acceptance Criteria:**
- Given `./gradlew qualityGate`, then BUILD SUCCESSFUL, and `git status --porcelain androidApp/src/test/screenshots/preview` is empty.

## pps-design Done checklist

- [x] Only tokens from `DESIGN.md` used (no raw hex, no new radii, no new font sizes): the approved `CheckPreviewScreen` and 3.2's Math check (the shake distance is `space2`).
- [x] Light, Dark (and Sunrise where relevant) checked with previews / screenshots: the preview is a wake-style screen, always Sunrise; Roborazzi at 100% and 200%.
- [x] Every colour pair used is in the `DESIGN.md` contrast table: no new pairs (`try-it-math`, `try-it-done`, `check-math-wrong`).
- [x] Touch targets ≥ 48 dp; wake actions ≥ 64 dp: pad keys 64 dp, "Done" 64 dp, Back 48 dp (test).
- [x] Works at 200% font scale and with TalkBack; outcome glyphs present: the 200% screenshots; the problem is a heading read in words, the answer is a polite live region, and the done card is a polite live region. The done card's check glyph is in `text`, not colour alone.
- [x] Reduced-motion path works: the shake is a Compose animation, instant at animator duration scale 0. The push uses the editor's sub-screen slide.
- [x] Copy matches `EXPERIENCE.md > Voice and Tone` (no em dashes, no filler, strings in resources): existing resources only, and `CopyRulesTest` passes. "minus" (3.2) is not reachable here before an Easy subtraction, and is listed by 3.2 for the owner.
- [x] Every state row in `EXPERIENCE.md > State Patterns` for this surface is handled: running, wrong, done (EXPERIENCE.md Check setup row: "without quiet-time ring or snooze, a close 'Back', and on success 'Nice. That's how it works.' with 'Done'").
- [x] "I'm up" is the most prominent wake action; snooze is visible, plain and priced: not on a preview (no snooze by design, FR-PWK-12).
- [x] Compose `@Preview`s for each state (light/dark/Sunrise, empty/error) exist; screenshot tests updated (Paparazzi or Roborazzi): `try-it-math` and `try-it-done` in the catalogue, unchanged, plus the new production screenshots.

## Spec Change Log

## Review Triage Log

## Design Notes

- **Why the preview is an editor pane:** the unsaved editor state lives in the editor ViewModel, so a pane (`TryIt`, one level below Check setup) keeps it intact for free. It also uses the editor's pushed slide, and Back works as it does for any sub-screen.
- **Finding in 3.2's Math screen (fixed here, one line, report to 3.2):** `Modifier.clearAndSetSemantics { contentDescription = spoken }.semantics { heading() }` loses the heading, because the later `semantics` is cleared too. The problem was not a heading for TalkBack (NFR-9). Here the heading is set inside the same `clearAndSetSemantics` block. `TryItNoStakesTest` finds the problem by heading, so it fails without the fix. 3.2 should take the same line so that the two copies stay identical.
- **Why the seed is a ViewModel parameter:** `previewSeed: () -> Long` defaults to `Random.nextLong()`, and tests pass a fixed one. `:core` stays deterministic and seed-free.

- **Flaky test made deterministic:** `MainActivityTest`'s first test asserted "Yawn & Pawn" before the stored session was restored (since 2.6, a neutral screen shows until then). It now waits for the text, as it already did for "No alarms yet.".

## Verification

Results from the run (2026-10-06):
- New tests: `CheckRegistryTest` (6), `AlarmEditorTryItTest` (4), `TryItNoStakesTest` (real Koin graph: engine Idle, 0 commits, no history, nothing armed, no media player, back on Check setup), and `TryItScreenshotTest` (4 baselines plus the target check).

**Commands:**
- `./gradlew qualityGate` -- expected: BUILD SUCCESSFUL.
- `git status --porcelain androidApp/src/test/screenshots/preview` -- expected: empty.
