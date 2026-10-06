---
title: 'Story 3.7: Word Unscramble check'
type: 'feature'
created: '2026-10-06'
status: 'done'
baseline_revision: '9b5a38b'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-3-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-3-1-check-plugin-contract-and-the-math-generator-in-core.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-3-6-try-it-previews-for-every-check.md'
  - '{project-root}/.claude/skills/pps-design/SKILL.md'
warnings: []
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

## Owner decisions (2026-10-06)

PRD Q10's word-list item is resolved and recorded in `docs/decisions/q10-check-parameters.md` (new):

1. **Source: option A.** A list of everyday English words drafted for the project by Claude (1,414 words: 494 Easy, 515 Medium, 405 Hard; 1,413 after the review removed "dune", whose scrambles could spell a blocklisted word), project-owned. The owner approved it without reviewing it ("just proceed"), so the drafting itself served as the sensitivity review. See `docs/checks/WORDS.md`.
2. **Blocklist:** `config/word-blocklist.txt`, by category (profanity, sexual and body, violence and weapons, death and self-harm, drugs and alcohol, hate, crime and politics, religion). `checkWordList` enforces it.
3. **Anagrams:** any listed word with exactly the same letters is accepted, in any case.

## Code Map

- **Core (`core/.../checks/`):**
  - `word/WordList.kt`: `WordList` (lowercase a–z, sorted buckets, the anagram index) and `WordBank`, the process-wide list that the app installs at start (`:core` does no I/O).
  - `word/WordGenerator.kt`: distinct seeded picks with a seeded Fisher–Yates scramble. A scramble that is the word or a listed word is drawn again (up to 64 times); a word without one is skipped.
  - `CheckType.WordUnscramble`: a data object; generate and validate go through `WordBank`. Also `Puzzle.Word` and `CheckAnswer.Word`.
- **Asset and gate:**
  - `androidApp/src/main/assets/words_en.txt`.
  - `android/WordListLoader.kt`, called from `YawnAndPawnApp.onCreate` before Koin.
  - `build-logic/.../WordList.kt`: `WordListRules`, `CheckWordListTask` and the plugin `yawnandpawn.word-list`. The root build configures it, and `qualityGate` depends on `checkWordList`.
  - `config/word-blocklist.txt`, `docs/checks/WORDS.md` and `docs/decisions/q10-check-parameters.md`.
- **UI (`composeApp`):**
  - `ui/wake/WordInput.kt`: `WordRound` / `wordRound(state)` for 3.2's `WakeCheck`, and `WordInput` (the pool order, the slots, Shuffle and Clear, all pure).
  - `ui/checks/WordTrial.kt`, registered in `CheckRegistry`; the UI maps Word to the core type.
  - `CheckScreen.WordCheck`:
    - a shake with the error haptic on a wrong word;
    - a light haptic per tile tap;
    - a polite live region with the answer so far (inside the progress line's Box, so no layout change);
    - "Shuffle" and "Clear" pinned under the scrolling area on the Check screen, like Math's pad (unchanged where everything fits).

## Review Triage Log

### Review (2 reviewers, fast mode)

Two reviewers read `3394993`: a verification-gap reviewer and an edge-case reviewer. In the lane 2 stack, the story comes after 3.5, 3.6 and 3.8 on main with 3.2. All findings are patches, fixed in `fix(3.7): review fixes`:

- **patch (high): Word could be picked, but the wake screen could not run it.** `WakeCheck` now shows Word on the real wake screen. A `WordAnswer` is keyed to the engine's position (entry, item, seed, failed attempts), like `CheckInput.following`. A new item, or the same item after a restore, starts with empty slots. Once every slot is filled the screen sends `UserInteracted` + `CheckAnswerSubmitted(Word)`, in order (`sendAnswer`). A wrong word (more failed attempts) clears the slots and shows "Not quite. Try again." until the next tap. `wordRound(state)` reads the usable run. `WordWakeCheckTest` (Robolectric, the real app and word list) solves both words and ends with Success. It also checks that a wrong word clears the slots and that the word can then be solved. `WordTrialTest` covers `WordAnswer`.
- **patch (high): an unreadable asset crashed every start, and an empty list made Word unsolvable.** `WordListLoader.load` is wrapped in `runCatching`, logs `OperationFailed("load word list")` and installs an empty list. With no list, `ConfigResolver` puts Math (same difficulty, default count) in place of a Word entry, or drops the Word entry when the plan already has Math. A ring never freezes an unsolvable check. Tested in `ConfigResolverTest`, `WakeServiceTest` (empty list at ring → Math) and `WordListLoaderTest` (broken assets → empty list, logged, no crash).
- **patch: a scramble could spell a blocklisted word.** `checkWordList` now also fails on a listed word that has a blocklisted word's letters (fixture test in `WordListTest`). `WordListLoaderTest` checks the real list against `config/word-blocklist.txt`, so no scramble can spell a blocklisted word. The check found one case: "dune" (an anagram of a blocklisted word), now removed from the list (1,413 words, 493 Easy).
- **patch: `WordList.set` read the constructor argument.** Because of the shadowing, " Notes " did not count as "notes". It is now `this.words.toSet()` (`WordCheckTest`).
- **patch: "weekend" (7 letters) was in the 8–10 group of the fixture.** Moved to the 6–7 group. The list is sorted, so the pinned puzzles do not change.
- **tests:** `CheckPluginSessionTest`: a two-word plan passes on listed anagrams (item, then last), and a reinstalled list gives the same scramble and answers. The Direct Boot loader test is renamed to what Robolectric can check, and item 19 of the Story 3.14 checklist covers the real reboot. `CheckTypeTest`: stable serial names for the Word entry, answer and puzzle. `WordTrialTest`: the anagram assertion now always runs. `WordCheckScreenshotTest`: "Not quite. Try again." on a wrong word, one `Reject` haptic and a `KeyboardTap` per tile. `WordListLoaderTest`: the real list over 2,000 seeds per difficulty gives 5 distinct words with real scrambles. `WordListTest`: CRLF passes, a missing final newline passes, a BOM fails line 1, and a blank middle line is named. `CheckRegistryTest` uninstalls its list after each test.
- The wrong-word shake uses 3.6's shared `rememberWrongShake`.
- **not changed (optional):** "Shuffle" can repeat the previous order or lay out the answer.

## Design Notes

- **Why a `WordBank` and not a list inside the type:** `CheckType` is serialized into stored sessions by id. A type that holds 1,400 words cannot be (and must not be) stored, so the list is installed once per process, before any ring or preview. A Direct Boot start reads the same asset.
- **Changing the list later** changes the words an existing seed picks. A stored session then shows a different current word, which is harmless (WORDS.md says so).
- **Memory fix carried here:** in 3.8, the numbered-variant announcement sat in a `spacedBy` column, so it added a gap while watching. It now sits in a Box with the phase text, the same way Word does it.

## pps-design Done checklist

- [x] Only tokens from `DESIGN.md` used (no raw hex, no new radii, no new font sizes): the approved `letter-tile`, slots and text buttons; the shake is `space2`.
- [x] Light, Dark (and Sunrise where relevant) checked with previews / screenshots: wake and Try it are always Sunrise; Roborazzi on 360 × 640 dp at 100% and 200%.
- [x] Every colour pair used is in the `DESIGN.md` contrast table: no new pairs (`check-word`).
- [x] Touch targets ≥ 48 dp; wake actions ≥ 64 dp: `letter-tile` is 48 dp (UX-DR: letter tiles 48 dp); snooze unchanged.
- [x] Works at 200% font scale and with TalkBack; outcome glyphs present: on 360 × 640 at 200%, "Shuffle", "Clear" and snooze are displayed (test). "Letter {letter}", "Slot {n}, empty / {letter}" and the answer so far are announced (test). No outcome glyphs on this screen.
- [x] Reduced-motion path works: the shake is a Compose animation (instant at scale 0); there is no other motion.
- [x] Copy matches `EXPERIENCE.md > Voice and Tone` (no em dashes, no filler, strings in resources): existing resources only; `CopyRulesTest` passes.
- [x] Every state row in `EXPERIENCE.md > State Patterns` for this surface is handled: playing, a wrong word (cleared slots, "Not quite. Try again.", shake), and solved.
- [x] "I'm up" is the most prominent wake action; snooze is visible, plain and priced: the check footer keeps the approved snooze control (on screen at 200%, test).
- [x] Compose `@Preview`s for each state (light/dark/Sunrise, empty/error) exist; screenshot tests updated (Paparazzi or Roborazzi): `check-word` and `try-it-word` unchanged (95 preview baselines verified), plus the 8 new `wake_check_word_*` / `try_it_word_*` screenshots.

## Verification

Results from the run (2026-10-06):
- Tests:
  - `WordCheckTest` (core): 10,000 seeds per difficulty, pinned picks, anagram and case validation, and an empty bank.
  - `WordListTest` (build-logic): one fixture per failure, plus the task on a fixture project.
  - `WordTrialTest`: tiles, trial and wake mapping.
  - `WordListLoaderTest`: the asset installed at start, and readable from the device-protected context.
  - `WordCheckScreenshotTest`: 8 screenshots and a TalkBack test.

**Commands:**
- `./gradlew checkWordList qualityGate` -- expected: BUILD SUCCESSFUL.
- `git status --porcelain androidApp/src/test/screenshots/preview` -- expected: empty.
