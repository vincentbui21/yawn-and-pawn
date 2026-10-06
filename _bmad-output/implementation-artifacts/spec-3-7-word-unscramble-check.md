---
title: 'Story 3.7: Word Unscramble check'
type: 'feature'
created: '2026-10-06'
status: 'blocked'
baseline_revision: '98934c1'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-3-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-3-1-check-plugin-contract-and-the-math-generator-in-core.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-3-6-try-it-previews-for-every-check.md'
  - '{project-root}/.claude/skills/pps-design/SKILL.md'
warnings:
  - 'blocked-owner-decision: PRD Q10 (word-list source and offensive-word filter) is still open. The AC needs a bundled list with a recorded source and licence, and a blocklist "reviewed by the owner". Neither exists, and docs/decisions/q10-check-parameters.md does not exist on main. See "Owner decisions needed".'
deferred: []
---

<intent-contract>

## Intent

**Problem:** Word Unscramble (FR-PWK-8) is not a real check yet. Users have only Math, and the PRD's minimum launch set includes Word Unscramble.

**Approach:**
- **Core plugin:** a `CheckType.WordUnscramble` in `core.checks.word`. It is built from a word list that the app loads from a bundled asset, so `:core` does no I/O.
- **Puzzle:** `count` distinct words are picked by seed from the difficulty bucket. Each scramble is a deterministic `SeededRandom` permutation that differs from the word and is not itself a listed word.
- **Validation:** the plugin accepts the target or any listed word with the same letters.
- **Screens:** the approved `CheckContent.WordUnscramble` composable is wired to the wake screen (the 3.2 pattern) and to "Try it" (the 3.6 registry). Word Unscramble then joins the pickable checks, with "Words" as the count label.
- **List gate:** a `checkWordList` Gradle task in `qualityGate` guards the list.

## Boundaries & Constraints

**Always:**
- **Word list:** offline, bundled as the APK asset `words_en.txt`. It is readable before the first unlock (an asset needs no credential-protected storage), so the type is `directBootSafe` and not a camera check.
- **Seeds:** every random choice goes through `SeededRandom` from the seed (3.1 contract, `NoUnseededRandom`). Pinned seeds give pinned words and scrambles in tests.
- **Ranges:** count 1–5 with default 2 (q10 default 2026-09-26). Easy is 4–5 letters, Medium 6–7, Hard 8–10.
- **Strings (existing resources only):** "Word {n} of {count}", "Letter {letter}", "Slot {n}, empty", "Slot {n}, {letter}", "Shuffle", "Clear", "Words" and "Not quite. Try again.".
- **Kover:** `core.checks` stays at ≥ 90%. The preview baselines stay unchanged.

**Never:**
- No network.
- No word list in `:core` sources.
- No new strings.
- No redesign of `letter-tile` and slots.
- No change to the Math check.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Generate | seed, Medium, count 2 | 2 distinct 6–7-letter words, each scramble ≠ word and not in the list | count coerced into 1–5; a bucket too small is impossible (gate ≥ 300) |
| Anagram | target "listen", answer "silent" (listed) | Correct / ItemCorrect | — |
| Case | "LISTEN" | accepted | — |
| Wrong | a different word, or letters not in the puzzle | Wrong | never throws |
| Auto-submit | last slot filled | `CheckAnswerSubmitted(Word)` | — |
| Shuffle / Clear | taps | display order only / all letters back | puzzle unchanged |
| List gate | uppercase, duplicate, 3 or 11 letters, blocklisted, bucket < 300 | `checkWordList` fails naming the entry | fixture test per failure |

</intent-contract>

## Owner decisions needed (blocking)

PRD Q10 left the word list's source and the offensive-word filter open ("revisit before the check-type stories"). The AC requires both. Please choose:

1. **The source of `words_en.txt`.** A list is needed with ≥ 300 words in each of 4–5, 6–7 and 8–10 letters. The words must be common, so a half-asleep user is never stuck on an obscure one (3.14 checklist item 4).
   - **A. A curated list we write ourselves** (project-owned, CC0). About 1,000 everyday words. It has no licence questions and no obscure words, and the owner reviews the full list, which doubles as the sensitivity review. **Recommended.**
   - **B. SCOWL "size 35" (en_US)**, filtered by length and the blocklist. SCOWL's licence is permissive (MIT-like, with the attribution notice copied into `docs/licenses`). It needs a one-time download from wordlist.aspell.net, and some words at 8–10 letters are less common.
   - **C. ENABLE / ENABLE2K** (public domain). It has many obscure and archaic words. Not recommended for this check.
2. **The blocklist `config/word-blocklist.txt`.** Offensive and sensitive words (slurs, profanity, sexual, violence and self-harm, drugs, and possibly religion, politics and body or medical terms). With A, the blocklist guards future edits, and the owner reviews the list itself. With B, I draft a blocklist and the owner reviews it before merge.
3. **Anagrams:** confirm that "any listed word with exactly the same letters" is accepted (the AC says yes). This matters only for the source chosen.
4. **Where the decision is recorded:** `docs/decisions/q10-check-parameters.md` is not on main yet. 3.7 creates it with the Q10 word-list resolution (plus the already approved count ranges), unless another story owns it.

## Code Map (planned)

- **Core (`core/.../checks/`):**
  - `word/WordList.kt`: the buckets (4–5, 6–7, 8–10) and the anagram index (sorted letters to words).
  - `word/WordGenerator.kt`: the distinct seeded picks and the scramble, retried with the next `SeededRandom` draw until it is neither the word nor a listed word. A word whose every permutation is listed is excluded from the bucket.
  - `CheckType.kt`: `WordUnscramble(list)` with id `WordUnscramble`, `countRange` 1..5, default 2, and `hasDifficulty`. It is a data class built with the list, so equality and serialization go by id; the decision is recorded in the spec.
  - `Puzzle.Word(words, scrambles)` and `CheckAnswer.Word(text)`.
- **Assets and gate:**
  - `androidApp/src/main/assets/words_en.txt`.
  - `WordListLoader` in the androidApp wiring, with the list read once at start.
  - `build-logic`: the `checkWordList` task and its fixtures; `qualityGate` gains `checkWordList`.
  - `config/word-blocklist.txt`, `docs/checks/WORDS.md` and `docs/decisions/q10-check-parameters.md`.
- **UI (composeApp):** a `wordCheckUiState` mapping, `CheckInput` with letters for the wake screen (the 3.2 pattern), a `WordTrial` in `CheckRegistry`, and `PickableCheckTypes`, which then includes Word Unscramble.
- **Tests:**
  - `WordCheckTest`: pinned seeds, 10,000 seeds per difficulty for the lengths, distinctness, scramble ≠ word and not listed, anagram and case validation.
  - `CheckWordListTaskTest`: one fixture per failure.
  - Wake mapping and Try it tests.
  - Roborazzi: Easy, Hard (wrapped), wrong, and the preview, in Sunrise at 100% and 200%, plus a semantics test that Shuffle, Clear and snooze are on screen.

## Dependency note

The wake-screen half follows 3.2's mapping (`CheckPosition` / `CheckInput` / `mathCheckUiState` in PR #33), which is not on this stack. I would add the Word mapping next to it in a new `WordCheckMapping.kt` to avoid conflicts. The WakeActivity hook that renders it lands only once 3.2 is merged; until then the Word check works in "Try it", and 3.2's renderer ignores a non-Math entry. The alternative is to wait for 3.2 to merge and stack on main.

## pps-design Done checklist

To be ticked when it is implemented, after the owner decisions above.

## Verification

**Commands (planned):**
- `./gradlew checkWordList qualityGate` -- expected: BUILD SUCCESSFUL.
- `git status --porcelain androidApp/src/test/screenshots/preview` -- expected: empty.
