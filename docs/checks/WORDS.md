# Word Unscramble word list

The list the Word Unscramble check (FR-PWK-8, Story 3.7) picks its words from: `androidApp/src/main/assets/words_en.txt`.

- **Source:** drafted for the project by Claude, on 2026-10-06. The owner approved it without reviewing it (PRD Q10, `docs/decisions/q10-check-parameters.md`).
- **Licence:** project-owned. It is the project's own work, with no third-party word list and no attribution needed.
- **Content:** 1,413 plain everyday English words, one per line, lowercase a–z: 493 of 4–5 letters (Easy), 515 of 6–7 (Medium) and 405 of 8–10 (Hard). They are everyday nouns, verbs and adjectives (food, home, nature, animals, places, objects), so a half-asleep user never meets an obscure or upsetting word.
- **Sensitivity:** no profanity, sexual, violent, self-harm, drug, hate, religious or political words. `config/word-blocklist.txt` lists such words by category, and `./gradlew checkWordList` (part of `qualityGate`) fails when the list holds one. It also fails on characters other than a–z, duplicates, lengths outside 4–10, and a length group under 300 words.
- **Anagrams:** a listed word with exactly the target's letters is also accepted ("listen" for "silent"). A scramble is never itself a listed word.

## Changing the list

Edit `words_en.txt` (keep it sorted, one word per line) and run `./gradlew checkWordList`. A word that should never appear goes into `config/word-blocklist.txt` first. Changing the list changes which words existing seeds pick. A session stored before the update then regenerates other words, and its current item restarts, which costs a moment and nothing else. Keep the order sorted, so a seed keeps picking within the same list.
