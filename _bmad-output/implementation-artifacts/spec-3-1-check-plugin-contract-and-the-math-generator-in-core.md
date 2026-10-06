---
title: 'Story 3.1: Check plugin contract and the Math generator in core'
type: 'feature'
created: '2026-10-06'
status: 'in-progress'
baseline_revision: '10582a6'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-3-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-1-11-the-complete-wake-session-state-machine-in-core.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** The wake session only knows the Epic 1 `Placeholder` step. Seeds come from a random port (`SeedSource`) in the wake service, so a puzzle is not derivable from the session. Each later check story needs one shared contract for difficulty, progress and correctness (AD-9).

**Approach:** Add a pure `core.checks` package:
- the sealed `CheckType` contract (`Math` plus the transitional `Placeholder`), `Puzzle`, `CheckAnswer`, `Difficulty` and `CheckResult`;
- the Math generator;
- `SeedDeriver` and `PlanResolver`.

Reshape the plan and run:
- `CheckPlan(mode, entries)`;
- `CheckRun.step` becomes a `StepPointer(entry, item)`;
- the reducer derives seeds itself when a ring starts.

`PluginCheckValidator` maps plugin results onto the existing AD-2 rows. Production keeps the `Placeholder` plan and validator until Story 3.2.

## Boundaries & Constraints

**Always:**
- `:core` stays pure. No `Random` in `core.checks`/`core.session`; a detekt rule (`NoUnseededRandom`) bans unseeded random there. The PRNG is our own SplitMix64, so a seed gives the same puzzle in every Kotlin version.
- The AD-2 table keeps its 31 rows. New `StepResult` values map onto R09/R10, and their examples are added to those rows.
- Sessions and pending test configs stored by the previous version still decode. `SessionJson` migrates the old `steps` plan and the integer `step` before decoding. The v1 fixtures stay, and a v2 fixture is added.
- History keeps the stable type id (`Placeholder`, `Math`) in `check_types`.

**Never:** No UI, no Koin switch to `PluginCheckValidator`, and no `app.db` change. The Direct Boot check stays `Placeholder` (3.2), and the fallback attempts reset and `StartCheckStep` stay with 3.9 and 3.2. No new AD-2 rows.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Math answer | `Number("0047")` for 47 | `ItemCorrect` if more problems remain, else `Correct` | — |
| Bad answer | `""`, `"-3"`, `" 4"`, `"4a"`, wrong type of answer or puzzle, out-of-range position | `Wrong` | never throws |
| All plan | 2 entries | resolved run holds both in order | — |
| Random plan | 3 entries, ring 1 and ring 2 | one entry per ring, picked by the ring's seed | empty plan resolves to empty |
| Plugin → row | `ItemCorrect` / `Correct` not last / `Correct` last / `Wrong` / `WrongRestart` | `ValidNextItem` / `ValidNext` / `ValidLast` / `Invalid` / `InvalidRestart` (R09, R09, R11, R10, R10) | missing entry or seed → `Invalid` |
| Restart | `InvalidRestart` on entry e | attempts+1, item 0, seed of e re-derived with `attempt = failedAttempts` | — |
| Old row | v1 JSON with `steps` and `"step":1` | decodes to entry 1, item 0, with `Placeholder` entries | unreadable only if it really is |

</intent-contract>

## Code Map

- `core/.../core/checks/` (new):
  - `CheckType.kt` (with `Difficulty`), `Puzzle.kt`, `CheckAnswer.kt` (moved from `core.session`) and `CheckResult.kt`;
  - `CheckPlan.kt` (`CheckMode`, `CheckEntry`, and `CheckPlan`, moved from `core.session`);
  - `SeedDeriver.kt` (with `SeededRandom`) and `PlanResolver.kt`;
  - `math/MathProblem.kt` and `math/MathGenerator.kt`.
- `core/.../core/session/CheckRun.kt`: `StepPointer`, `CheckRun.forRing` and `withPlan`. `CheckStep` is removed.
- `CheckRules.kt`, `IdleRules.kt`, `SnoozedRules.kt`, `RingRules.kt`, `PurchaseRules.kt`, `SessionTimers.kt`: seeds are derived in the reducer; pointer moves.
- `SessionPolicies.kt`: `StepResult` + `ValidNextItem`, `InvalidRestart`; `PluginCheckValidator`; the adapted `PlaceholderCheckValidator`.
- `SessionEvent.kt`: `seeds` removed from `AlarmFired`, `TestAlarmFired`, `PurchaseGranted` and `ReuseAccepted`. `SeedSource.kt` is deleted.
- `SessionJson.kt`: decodes the legacy shape. `DirectBoot.kt`: works per entry. `SessionRecorder.kt`: writes the type ids.
- `config/detekt-rules`: `NoUnseededRandom` + test; `config/detekt/detekt.yml`.
- `core/build.gradle.kts`: Kover `checks` variant ≥ 90%.
- Callers: `WakeService`, `WakeModule`, `RingingMapping`, `:testing` fakes and tests.

## Tasks & Acceptance

**Execution:**
- `core.checks` contract + Math + SeedDeriver + PlanResolver -- with table tests over 10,000 seeds per difficulty and a determinism test (10,000 seeds twice).
- Session model and rules -- deliberate updates to the transition-table examples (R01/R02 derived seeds, R04/R05 re-resolved ring, R09 attempts reset + item example, R10 restart example, R12 fallback seeds, R16/R18 no event seeds).
- `SessionJson` legacy decode -- v1 fixtures still decode; the v2 fixture is committed.
- `NoUnseededRandom` detekt rule -- every banned form is reported; seeded forms and other packages are not.
- Callers and fakes -- compile, and behaviour is unchanged.

**Acceptance Criteria:**
- Given `./gradlew qualityGate`, then BUILD SUCCESSFUL. Kover passes `core` and `core.session` at ≥ 90% and the `checks` variant at ≥ 90%. `git status --porcelain androidApp/src/test/screenshots/preview` is empty.
- Given a production alarm, then it still rings the `Placeholder` plan with `PlaceholderCheckValidator`, with no user-visible change.

## Design Notes

- The plugin result is named `CheckResult` because the session `StepResult` keeps the AD-2 row mapping. `CheckResult.WrongRestart` carries no seed: a plugin does not know the session coordinates. The reducer derives the new seed with `SeedDeriver.seed(sessionId, ringIndex, entryKey, attempt = failedAttempts)`, so seeds still come only from `SeedDeriver`.
- Seed keys: entry `i` uses `i`, and the Random pick uses `-1`. Fallback plans use `1000 + i` and pick with `999`, so a fallback never reuses the plan's seeds (part of the 3.9 deferred item).
- `CheckType.Placeholder` is the Epic 1 stand-in, moved into the sealed hierarchy so that production plans and stored sessions keep working. Story 3.2 removes it from production plans.
- The resolved plan of a run is a `CheckPlan` in `All` mode. `generate` coerces `count` into `countRange`, so a stored plan never makes the wake flow throw.

## Verification

**Commands:**
- `./gradlew qualityGate` -- expected: BUILD SUCCESSFUL.
- `git status --porcelain androidApp/src/test/screenshots/preview` -- expected: empty.

## Auto Run Result

Status: implemented in fast mode (one agent), waiting for review. Branch `story/3-1-check-plugin-contract-and-the-math-generator-in-core`, based on `origin/main` (10582a6).

**Summary:**
- **Contract:** `core.checks` holds the AD-9 contract, `CheckType.Math` and the transitional `CheckType.Placeholder`.
- **Seeds:** the reducer derives every seed through `SeedDeriver` and resolves each ring's plan with `PlanResolver`. This covers the first ring, the ring after a snooze or merge (Random can pick another type) and the fallback (its own keys). `SeedSource`, `RandomSeedSource` and `FakeSeedSource` are removed, and so is `seeds` from `AlarmFired`, `TestAlarmFired`, `PurchaseGranted` and `ReuseAccepted`.
- **Validator:** `PluginCheckValidator` is in core but not wired. Production still uses the Placeholder plan and validator.

**Deliberate test changes:**
- **AD-2 table:** the rows are unchanged (31). R09 and R10 gain the `ValidNextItem` and `InvalidRestart` examples. R09 now expects the failed attempts to reset on an entry advance. R01, R02, R04, R05 and R12 expect derived seeds. R16 and R18 keep the seeds at the grant, and the next ring re-derives them.
- **Conflict scenario 2:** `SessionConflictScenariosTest` scenario 2 now checks the new seeds on ring 2 instead of at the grant.
- **Rename:** `WakeFakes.kt` is renamed to `FakeCrashReporter.kt` (ktlint filename rule, after `FakeSeedSource` left).

**Verification:** `./gradlew qualityGate` gives BUILD SUCCESSFUL (16m 38s). Kover reports `core.checks` at 99.3% line coverage, and `koverVerifySession` passes. The preview baselines are unchanged.

**Residual risks:**
- The pinned seed and puzzle tests (`SeedDeriverTest`, `CheckTypeTest`) make any change to the derivation or the generator a deliberate test change, because stored sessions depend on both.
- Each `validate` regenerates the puzzle. That is cheap for Math, but a heavier type might want caching.
