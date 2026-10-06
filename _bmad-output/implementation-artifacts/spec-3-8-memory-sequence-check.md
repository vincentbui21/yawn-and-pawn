---
title: 'Story 3.8: Memory Sequence check'
type: 'feature'
created: '2026-10-06'
status: 'done'
baseline_revision: '98934c1'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-3-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-3-1-check-plugin-contract-and-the-math-generator-in-core.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-3-6-try-it-previews-for-every-check.md'
  - '{project-root}/.claude/skills/pps-design/SKILL.md'
warnings:
  - 'wake-screen-after-3.2: the Memory wake mapping is pure and lives in its own files (MemoryRound.kt, MemoryPlayback.kt). It plugs into 3.2''s WakeCheck renderer (PR #33) after 3.2 merges. Until then Memory works in "Try it" only, and the wake screen shows nothing new.'
  - 'core-contract-addition: CheckType gains restartFrom(position, difficulty) (default 0), so a wrong tap restarts only the current round (FR-PWK-4), not the whole entry. CheckRules uses it on InvalidRestart. This is additive, with no new AD-2 rows, and every existing type keeps restarting at item 0.'
deferred: []
---

<intent-contract>

## Intent

**Problem:** Memory Sequence (FR-PWK-4) is not a real check yet: no core plugin, no Hard 4×4 grid, no accessible variant, and no "Try it".

**Approach:**
- **Core plugin:** `CheckType.MemorySequence(numbered)` in `core.checks.memory`.
  - Rounds of 4, 6 or 8 tiles by difficulty, on a 3×3 grid, or 4×4 on Hard (always 3×3 when numbered), with no tile twice in a row.
  - Each tap is one item: `ItemCorrect`, then `Correct` at the very end, and a wrong tap is `WrongRestart`.
  - The reducer restarts at the round's first tap with a new seed (`restartFrom`).
- **Accessible variant:** an `AccessibilityState` port (`FakeAccessibilityState`) turns the variant on. `ConfigResolver.resolve(…, accessible)` swaps Memory entries for the numbered variant when the ring's plan is frozen. `WakeService` reads the port.
- **UI:**
  - The approved `MemoryCheck` composable gains `gridSize` (default 3) and the spoken sequence for the numbered variant (an invisible polite live region). It also gets the same shake and error haptic as Math on a wrong tap, and a light haptic per tap.
  - A pure `MemoryPlayback` (350 ms lit, 150 ms gap; input disabled while it plays; brief lit feedback per tap) drives both "Try it" (a `MemoryTrial` in `CheckRegistry`) and, after 3.2, the wake screen (`memoryCheckContent`).
- **Picker:** Memory joins the picker ("Rounds", "Uses numbered tiles with TalkBack.").

## Boundaries & Constraints

**Always:**
- **Seeds:** every choice goes through `SeededRandom` (3.1 contract, `NoUnseededRandom`). The id `MemorySequence` is stable for both variants (history `check_types`).
- **Variant on storage:** stored configs never hold the numbered variant; it is chosen per ring.
- **Strings (existing resources only):** "Watch the sequence", "Your turn", "Round {n} of {count}", "Tile {number}", "Rounds", "Uses numbered tiles with TalkBack." and "Not quite. Try again.". The announced sequence is digits joined by ", " (no words).
- **Sizes:** tiles ≥ 64 dp on every grid, also 4×4 on a 360 dp phone. The preview baselines stay unchanged (new parameters default to the approved look).
- **Timing:** playback keeps its timing with animator duration scale 0 (state changes, no animation).

**Never:**
- No new AD-2 rows and no new strings.
- No WakeActivity change (that comes after 3.2).
- No camera.
- No change to Math's restart (item 0).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Generate | seed, Hard, 2 rounds | 4×4, 2 rounds of 8, no repeat in a row, tiles 1..16 | count coerced into 1–5 |
| Numbered | numbered, Hard | 3×3, rounds of 8 | — |
| Right taps | every tile of round 1 | `ItemCorrect` ×(n−1), then `ItemCorrect` into round 2 … `Correct` at the last | — |
| Wrong tap | tap 3 of round 2 | `WrongRestart`; the reducer sets item = round 2 start, attempts+1, new seed | other types restart at 0 |
| Bad answer | not a tile, out of range, wrong puzzle | `Wrong` | never throws |
| Try it | Medium, then wait | "Watch the sequence" (taps ignored), the tiles light 350/150, then "Your turn" | — |
| Try it wrong | wrong tap | shake + "Not quite. Try again." + a new sequence plays | — |
| Accessible | TalkBack on | the numbered 3×3 and the sequence announced "3, 7, 1, 9" | — |

</intent-contract>

## Code Map

- **Core:**
  - `core/.../checks/memory/MemoryGenerator.kt`, `Puzzle.Memory`, `CheckAnswer.Tile`, and `CheckType.MemorySequence` with `restartFrom`.
  - `core/.../session/CheckRules.kt`: the restart item.
  - `core/.../session/SessionConfig.kt`: `resolve(accessible)`.
  - `core/.../accessibility/AccessibilityState.kt` (port).
- **Testing:** `FakeAccessibilityState`.
- **androidApp:** `AndroidAccessibilityState` (AccessibilityManager touch exploration), Koin, and `WakeService` passing `accessible`.
- **composeApp:**
  - `ui/wake/MemoryPlayback.kt` and `MemoryRound.kt` (new).
  - `WakeContract` (`gridSize`, `announced`) and `CheckScreen` (`MemoryCheck`).
  - `ui/checks/CheckRegistry.kt` (`MemoryTrial`) and `CheckTypes.kt` (mapping by id).
  - The editor (TalkBack note, trial ticks, accessibility port).

## Tasks & Acceptance

**Execution:**
- Core plugin and rule, with tests: pinned seeds, 10,000 seeds per difficulty (grid, lengths, no repeat), tap validation, restart position, variant, and resolver substitution. Kover `checks` stays ≥ 90%.
- `MemoryPlayback`, the trial and the mapping, with tests.
- The editor ticks, with VM tests on virtual time.
- Roborazzi: 3×3, 4×4, numbered, playback and wrong, in Sunrise at 100% and 200%, plus Try it. A semantics test checks tiles ≥ 64 dp.

**Acceptance Criteria:**
- Given `./gradlew qualityGate`, then BUILD SUCCESSFUL, and `git status --porcelain androidApp/src/test/screenshots/preview` is empty.

## pps-design Done checklist

- [x] Only tokens from `DESIGN.md` used (no raw hex, no new radii, no new font sizes): the 4×4 tile is `target-wake` (64 dp), the 3×3 tile is unchanged (`target-wake + space5`), and the shake is `space2`.
- [x] Light, Dark (and Sunrise where relevant) checked with previews / screenshots: wake and Try it are always Sunrise; 14 Roborazzi tests at 100% and 200% on a 360 dp phone.
- [x] Every colour pair used is in the `DESIGN.md` contrast table: no new pairs (`accent-sunrise` / `on-accent-sunrise` for lit tiles, as in `check-memory-watch`).
- [x] Touch targets ≥ 48 dp; wake actions ≥ 64 dp: every 4×4 tile is ≥ 64 dp at 200% on 360 dp (test), and 3×3 tiles are 84 dp.
- [x] Works at 200% font scale and with TalkBack; outcome glyphs present: the numbered variant shows its numbers, reads "Tile {number}", and announces the round as numbers (test). Tiles are disabled while the sequence plays. No outcome glyphs on this screen.
- [x] Reduced-motion path works: playback is state changes on a timer (the same timing at animator scale 0). The shake is a Compose animation, instant at scale 0.
- [x] Copy matches `EXPERIENCE.md > Voice and Tone` (no em dashes, no filler, strings in resources): existing resources only; `CopyRulesTest` passes.
- [x] Every state row in `EXPERIENCE.md > State Patterns` for this surface is handled: watch, your turn, a wrong tap (restarts the round with a new sequence), and the TalkBack variant.
- [x] "I'm up" is the most prominent wake action; snooze is visible, plain and priced: the check footer keeps the approved snooze control (on screen at 200%, test).
- [x] Compose `@Preview`s for each state (light/dark/Sunrise, empty/error) exist; screenshot tests updated (Paparazzi or Roborazzi): `check-memory-watch`, `check-memory-turn` and `try-it-memory` unchanged, plus the new production screenshots.

## Spec Change Log

## Review Triage Log

## Design Notes

- **Restart a round, not the entry:** FR-PWK-4 and the AC say a wrong tap restarts *that round*. 3.1's `InvalidRestart` resets the item to 0, which would throw away finished rounds. `restartFrom` (default 0) keeps the plugin contract (the plugin does not know the session) while the reducer stays the one place that moves the pointer and derives the seed.
- **Restore mid-round:** the screen replays the round's sequence from the start, and input goes on from the next tap the engine waits for (the taps already made stay counted).
- **Numbered variant as a field:** `MemorySequence(numbered = false)` is a data class with one stable id, so history and stored configs see one type. The numbered flag lives only in a ring's frozen plan.

## Verification

**Commands:**
- `./gradlew qualityGate` -- expected: BUILD SUCCESSFUL.
- `git status --porcelain androidApp/src/test/screenshots/preview` -- expected: empty.
