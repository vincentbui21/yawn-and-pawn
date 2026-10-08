# Q10: check parameters

- **Date:** 2026-09-26 (counts and difficulties), 2026-10-06 (word list), 2026-10-08 (Easy default and count ranges, after the Epic 3 device check)
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

**Count ranges (owner decision 2026-10-08):** each check keeps its own count range: Math 1–10, Word Unscramble 1–5, Memory Sequence 1–5. EXPERIENCE.md's Check setup stepper now says so (it said "1 to 5" for every check).

## Default check: Math · Easy · 3 (owner decision 2026-10-08)

After the Epic 3 device check (Story 3.14) the owner found Medium too hard as a default: "Make Easy the default and leave Medium as it is."

- The default check is now **Math · Easy · 3** (`CheckPlan.DEFAULT_ENTRY`): a new alarm's check, a newly ticked Math in the editor, and the plan of a ring (or a test ring) without checks. The count stays 3. Medium itself is unchanged.
- Word Unscramble and Memory Sequence still start at Medium when ticked.
- **Default taken (owner can change):** the Direct Boot substitute (FR-ALM-11, the check that replaces a camera check before the first unlock) follows the default, so it is Math · Easy · 3 too. So is the entry that replaces a QR/Barcode check without a code.
- Unchanged: the fallback check is still Hard at double the count. Stored alarms keep their stored difficulty, and the v5→v6 migration still gives alarms stored before Story 3.5 the Math · Medium · 3 they rang until then (`CheckConfig.LEGACY_DEFAULT_ENTRY`).

## Other owner decisions (2026-10-08)

- **Fallback Memory Sequence:** the fallback check's Memory Sequence always uses numbered tiles, with or without TalkBack (the default taken in Story 3.12, now confirmed).
- **Word Unscramble heading:** the check's heading stays "Word {n} of {count}", with no instruction line (the default taken in Story 3.12, now confirmed; no new key string).

## Word list (owner decision 2026-10-06, Story 3.7)

- **Source:** option A. An English list of plain everyday words, drafted for the project by Claude: 1,414 words (1,413 since the Story 3.7 review removed "dune", an anagram of a blocklisted word), with at least 300 in each length group. It is project-owned, so no third-party licence applies. The owner chose not to review it ("just proceed"), so the drafting itself served as the sensitivity review: plain everyday nouns and verbs only, and no profanity, sexual, violent, self-harm, drug, hate, religious or political words. See `docs/checks/WORDS.md`.
- **Filter:** `config/word-blocklist.txt` lists offensive and sensitive words by category. The `checkWordList` task (a `qualityGate` dependency) fails if the list holds one of them. It also fails on anything that is not lowercase a–z, on duplicates, on words outside 4–10 letters, and on a length group with fewer than 300 words.
- **Anagrams:** any listed word with exactly the same letters as the target is accepted, in any case.
- **Bundling:** the APK asset `words_en.txt`, read once at app start. Assets are readable before the first unlock, so Word Unscramble is Direct Boot safe.
