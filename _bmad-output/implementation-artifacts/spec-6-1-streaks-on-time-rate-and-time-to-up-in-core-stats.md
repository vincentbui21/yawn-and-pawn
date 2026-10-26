---
title: 'Story 6.1: Streaks, on-time rate and time to up in core.stats'
type: 'feature'
created: '2026-10-26'
status: 'done'
baseline_commit: '0d85be9e95e8072ac9762db78b628814435f55d7'
route: 'dispatch'
story_key: '6-1-streaks-on-time-rate-and-time-to-up-in-core-stats'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-6-context.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Home, Progress, the celebration and the weekly summary (Stories 6.2 to 6.8) all need the zero-snooze streak, the on-time rate and the average time to get up. If each computes them itself they will disagree, especially around midnight, DST and zone changes.

**Approach:** One set of pure functions in `core.stats` over `List<SessionHistoryRow>`, using the Epic 6 shared stats rules exactly (epics.md, owner-approved default 2026-09-26). Nothing is stored (AD-18); time comes from the call site (AD-3).

Decisions taken by default (unattended fast-mode run, owner-approved):
- The AC's `SessionHistoryEntry` is the existing `SessionHistoryRow`.
- `:core` tests may not depend on `:testing` (`CoreDependencyRules.testingConsumers`), so the AC's `aSession(...)` builder is a test-local builder in `core/src/commonTest/.../stats/`.
- `wholeMinutes` returns an `Int`; 0 means "under a minute" (exactly the durations under 30 s, and never negative).
- `core.stats` gets its own Kover 90% line rule like `core.session`, `core.checks`, `core.billing`, `core.config` and `core.reset`.

## Boundaries & Constraints

**Always:**
- Day of a session = `scheduledAt.toLocalDateTime(zone).date` (a 23:50 alarm ending at 00:10 belongs to the earlier day).
- Counted = outcome OnTime, Snoozed or Missed. Test, Skipped and null never count anywhere.
- Good day = at least one counted session, all OnTime. Any Snoozed or Missed breaks the run; days with nothing counted are skipped without breaking it.
- Functions are pure, commonMain only, no clock read, no platform import (AD-1).

**Never:** storage of derived stats, UI, new dependencies, changes to `SessionHistoryRow` or the repository port (6.2 adds `observeAll()`).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior |
|----------|--------------|---------------------------|
| Empty | no rows | current 0, best 0, rate null, average null |
| Weekdays only | 5 on-time weekdays, no weekend | current 5 |
| Broken run | Mon OnTime, Tue Missed, Wed OnTime | current 1, best 1 |
| Mixed day | one OnTime + one Snoozed on a day | that day breaks the streak |
| Neutral day | Test-only or Skipped-only day between two good days | streak 2 |
| Snoozed today | 9-day run then Snoozed today | current 0, best 9 |
| In progress | null-outcome row today | ignored |
| Clock set back | good or bad days after `today` | ignored by current streak and windows |
| Rates | 6/7, 1/3, 2/3, Missed only, Test/Skipped only | 86, 33, 67, 0, null |
| Window edge | session on `today − (days − 1)` / the day before | included / excluded |
| Average | OnTime and Snoozed in 30 days, null ms, Missed | mean of the first; others skipped; none → null |
| Minutes | 29 s, 30 s, 2:29, 2:30 | 0, 1, 2, 3 |
| DST | Europe/Berlin 2027-03-28 (23 h) and 2027-10-31 (25 h) | one day each; windows still 7/30 dates |
| Zone change | 06:30 Asia/Ho_Chi_Minh on 2027-01-15, read in Europe/London | day 2027-01-14 |
| After midnight | scheduled 23:50, ended 00:10 | earlier day |

</frozen-after-approval>

## Code Map

- `core/src/commonMain/kotlin/com/yawnandpawn/app/core/history/SessionHistory.kt` -- `SessionHistoryRow` (`scheduledAt`, `timeToCompleteMs`, `outcome`) and `SessionOutcome`. Read only; do not change.
- `core/src/commonMain/kotlin/com/yawnandpawn/app/core/stats/ReRegisterSuggestion.kt` -- the existing `core.stats` file (Story 3.13). Leave it alone; new code goes in a new file beside it.
- `core/src/commonMain/kotlin/com/yawnandpawn/app/core/alarm/AlarmOccurrence.kt` -- the existing `toLocalDateTime(zone).date` pattern (kotlinx-datetime 0.8, `kotlin.time.Instant`).
- `core/src/commonMain/kotlin/com/yawnandpawn/app/core/session/SessionRecorder.kt` -- writes `timeToCompleteMs` only for completed sessions, never negative.
- `core/build.gradle.kts` -- Kover variants with a 90% `minBound` per guarded package; add `stats`.
- `build.gradle.kts` (root, `tasks.named("koverVerify")`) -- `dependsOn` each `:core:koverVerify<Variant>`; add `:core:koverVerifyStats`.
- `build-logic/.../CoreDependencyRules.kt` -- forbids `:core` → `:testing`; do not change.

## Tasks & Acceptance

**Execution:**
- [x] `core/src/commonMain/kotlin/com/yawnandpawn/app/core/stats/WakeStats.kt` -- add `sessionDay(row, zone)`, `isCounted(row)`, `currentStreak(history, today, zone)`, `bestStreak(history, zone)`, `onTimeRate(history, today, zone, days): Int?`, `averageTimeToUp(history, today, zone): Duration?`, `wholeMinutes(duration): Int` and a `WakeStats` object (detekt `MatchingDeclarationName`) (`RATE_DAYS_SHORT = 7`, `RATE_DAYS_LONG = 30`, `AVERAGE_DAYS = 30`) -- the shared rules in one place.
- [x] `core/src/commonTest/kotlin/com/yawnandpawn/app/core/stats/StatsBuilders.kt` -- test-local `aSession(day, outcome, zone, time, timeToComplete)` builder.
- [x] `core/src/commonTest/kotlin/com/yawnandpawn/app/core/stats/WakeStatsTest.kt` -- table tests for every row of the matrix and every AC example.
- [x] `core/src/commonTest/kotlin/com/yawnandpawn/app/core/stats/WakeStatsPropertyTest.kt` -- seeded 1,000 random histories: `bestStreak ≥ currentStreak ≥ 0`, and adding a Test, Skipped or null-outcome row changes neither.
- [x] `core/build.gradle.kts`, `build.gradle.kts` -- the `stats` Kover variant (90%) wired into `koverVerify`.

**Acceptance Criteria:**
- Given any history, when the functions run, then they read no clock and give the same result for the same inputs.
- Given `days < 1`, when `onTimeRate` is called, then it throws `IllegalArgumentException` (a programming error, not user data).
- Given `./gradlew :core:jvmTest koverVerify`, when run, then all tests pass and `core.stats` is at least 90% line coverage.

## Implementation Notes

- Implemented directly by the build agent (it is itself a delegated agent; one Gradle lane only), not by a second subagent.
- `toSortedMap` is JVM-only, so the day list is a `List<Pair<LocalDate, Boolean>>` sorted with common stdlib calls.
- detekt `MatchingDeclarationName` failed on an object named `WakeStatsRule` in `WakeStats.kt`; the object is `WakeStats`.
- `AGENTS.md` does not exist in this repository (checked the worktree and `origin/main`); nothing to follow there.
- Test-local builder `aSession(day, outcome, at, zone, timeToUp)` in `StatsBuilders.kt` (parameter names differ slightly from the task line above).
- Files: `core/src/commonMain/.../stats/WakeStats.kt`; tests `StatsBuilders.kt`, `WakeStatsTest.kt` (26 tests), `WakeStatsPropertyTest.kt`; Kover `stats` variant in `core/build.gradle.kts`, wired into the root `koverVerify`.
- Verified after the review patches: `:core:jvmTest koverVerify spotlessCheck detekt` BUILD SUCCESSFUL (90 core suites, 0 failures; `koverVerifyStats` and the other five package rules pass). The full `qualityGate` (lint, assemble, Android tests) was not run here; the coordinator's CI runs it.
- Matrix audit: every row has a covering test; the 30-day window across DST is covered by `average time to up covers the last 30 days only` (window 2027-03-12 to 2027-04-10 spans 2027-03-28).

## Spec Change Log

## Review Triage Log

Pass 1 (blind hunter, edge-case hunter, verification gap; fast mode).

| # | Source | Finding | Verdict | Evidence / route |
|---|--------|---------|---------|------------------|
| 1 | blind | `bestStreak` keeps days after `today`, `currentStreak` drops them | low | Real only after a clock set-back. The AC fixes the signature `bestStreak(history, zone)` and says only the current streak ignores later days. Adding a parameter is more than a direct fix for a rare case. Rejected. |
| 2 | blind, edge | `wholeMinutes` overflows for `Duration.INFINITE` or huge values | low | `Long.MAX_VALUE + 30_000` wraps to a negative number. No caller can produce it today, but the fix is a direct clamp. Patched: clamped to `Int.MAX_VALUE` minutes, plus an `INFINITE` test case. |
| 3 | blind | No test shows a streak surviving weeks of no counted sessions or Skipped days | low | The behaviour is the epic rule but was not written down in a test. Patched: added the test `weeks without a counted session keep the streak, skipped or not`. |
| 4 | blind | 30-day window not tested across DST | false | `average time to up covers the last 30 days only` uses today 2027-04-10, so its window (03-12 to 04-10) spans 2027-03-28, with day −29 included and day −30 excluded. `onTimeRate` uses the same `window`. |
| 5 | blind | DST tests use local times in the gap or the overlap and assert only the date | false | Both resolutions of 02:30 (in the gap or the overlap) fall on the same local date, and the rule is about the date only. The assertion checks exactly the rule. |
| 6 | blind | Property test could also check that rates stay in 0..100, and other invariants | low | A cheap extra check. Patched: rates in 0..100. Other invariants are already implied (order-independence is exercised through the shuffle). |
| 7 | blind | Every call scans the whole history again | false | At 3 years of history (about 1,100 rows) each pass is microseconds. Story 6.2 owns the snapshot and the 50 ms performance test. No harm shown. |
| 8 | blind | Snoozes over 30 days, Insight and the weekly figures have no shared function | false | Out of scope by intent: Story 6.2 (snoozes, Insight) and 6.8 (weekly summary) own them in epics.md. |
| 9 | blind, edge | `onTimeRate` with a huge `days` throws a date-arithmetic exception, not `IllegalArgumentException` | low | Callers pass the constants 7 and 30. Either way it fails loudly on a programming error. Rejected (the fix adds a bound and a constant). |
| 10 | blind | The `stats` Kover rule also covers `ReRegisterSuggestion.kt` (Story 3.13), but the comment names only 6.1 | low | True. Patched the comment to "core.stats (Stories 3.13 and 6.1)". Coverage of the whole package passes. |
| 11 | blind | Builder gives Missed and running-style rows a time to complete that production never writes | low | Running rows already get null. Missed rows with a time are deliberate, to prove the outcome filter. No test passes for the wrong reason. Rejected. |
| 12 | edge | Builder parameter names differ from the spec task line | low | Test-only naming. The fix would be a spec edit. Rejected, and noted in Implementation Notes. |
| 13 | verification-gap | No gaps found | — | — |

## Design Notes

Rate rounding is half-up in integers: `(onTime * 200 + counted) / (2 * counted)` (6/7 → 86, 1/8 → 13). The average is `sum(ms).milliseconds / count`. The current streak first drops days after `today`, then walks the counted days newest first and stops at the first bad day; the best streak is the longest run of good counted days in date order over all rows. Because the current run is a run in the full ordered list, `best ≥ current` always holds.

## Verification

**Commands:**
- `./gradlew --no-daemon --max-workers=2 :core:jvmTest` -- expected: pass.
- `./gradlew --no-daemon --max-workers=2 koverVerify` -- expected: pass, including `:core:koverVerifyStats`.
- `./gradlew --no-daemon --max-workers=2 spotlessCheck detekt` -- expected: pass.
