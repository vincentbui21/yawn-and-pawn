# Q10: check parameters

- **Date:** 2026-09-26 (counts and difficulties), 2026-10-06 (word list)
- **Question (PRD Q10):** the count ranges for Memory Sequence rounds, Word Unscramble and House Hunt, the Math Hard operand bounds, and the word-list source with its offensive-word filter.
- **Decision:** the owner-approved defaults below. The word list is drafted for the project, and the owner approved it without reviewing it on 2026-10-06 (Story 3.7).

## Counts and difficulties (owner-approved defaults 2026-09-26)

| Check | Count | Default | Easy | Medium | Hard |
|---|---|---|---|---|---|
| Math | 1–10 problems | 3 | `a ± b` | `a × b + c` | `a × b + c × d` |
| Word Unscramble | 1–5 words | 2 | 4–5 letters | 6–7 letters | 8–10 letters |
| Memory Sequence | 1–5 rounds | 2 | 4 tiles, 3×3 | 6 tiles, 3×3 | 8 tiles, 4×4 (3×3 with TalkBack) |
| QR/Barcode | 1 (fixed) | 1 | — | — | — |

Other approved defaults: the Success screen times out after 60 s, and until Story 3.5 every alarm got Random · Math · Medium · 3. The Math operands are 10–99, with multipliers 2–9 (every answer below 10,000).

Open for the owner (2026-10-06): EXPERIENCE.md says the Check setup stepper goes "1 to 5", but the Math count range above is 1–10. The app uses each type's range.

## Word list (owner decision 2026-10-06, Story 3.7)

- **Source:** option A. An English list of plain everyday words, drafted for the project by Claude: 1,414 words, with at least 300 in each length group. It is project-owned, so no third-party licence applies. The owner chose not to review it ("just proceed"), so the drafting itself served as the sensitivity review: plain everyday nouns and verbs only, and no profanity, sexual, violent, self-harm, drug, hate, religious or political words. See `docs/checks/WORDS.md`.
- **Filter:** `config/word-blocklist.txt` lists offensive and sensitive words by category. The `checkWordList` task (a `qualityGate` dependency) fails if the list holds one of them. It also fails on anything that is not lowercase a–z, on duplicates, on words outside 4–10 letters, and on a length group with fewer than 300 words.
- **Anagrams:** any listed word with exactly the same letters as the target is accepted, in any case.
- **Bundling:** the APK asset `words_en.txt`, read once at app start. Assets are readable before the first unlock, so Word Unscramble is Direct Boot safe.
