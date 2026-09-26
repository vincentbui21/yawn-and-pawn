---
title: 'Story 1.6: Time ports, deadlines and alarm occurrence math in core'
type: 'feature'
created: '2026-09-26'
status: 'done'
baseline_commit: '11c373f7631cbd42bbe84bf0897053b0a264f701'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-1-3-generated-design-tokens-and-ppstheme.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Nothing in `:core` knows what time it is or when an alarm should next ring, and nothing stops code from reading the system clock directly, which would make scheduling, deadlines and DST handling untestable and wrong across reboots and zone changes.

**Approach:** Implement Story 1.6 in `epics.md`: time ports in `:core` with fakes in `:testing` and Android adapters in `:androidApp`, a detekt rule banning direct clock access, a boot-aware `Deadline`, and a pure `nextOccurrence`/`durationUntil` with table-driven DST and zone tests.

## Boundaries & Constraints

**Always:**
- `:core` ports (package `com.yawnandpawn.app.core.time`): `Clock` = `kotlin.time.Clock` (wall), `MonotonicClock` (elapsed millis since boot), `BootCounter` (boot count), `TimeZoneProvider` (current `kotlinx.datetime.TimeZone`). A `TimeSnapshot(wallMillis, elapsedMillis, bootCount)` value is how "now" is passed to deadline logic.
- `:testing` fakes: `FakeClock`, `FakeMonotonicClock`, `FakeBootCounter`, `FakeTimeZoneProvider` with `advanceBy(duration)`, `set(...)`, and `reboot()` (new boot count, elapsed reset to 0, wall keeps running); a small helper to build a `TimeSnapshot` from the fakes.
- `:androidApp` adapters in package `com.yawnandpawn.app.android` (`AndroidMonotonicClock` → `SystemClock.elapsedRealtime`, `AndroidBootCounter` → `Settings.Global.BOOT_COUNT`, `AndroidTimeZoneProvider` → system default zone, wall clock binding → `Clock.System`), wired in `appModule`.
- detekt rule `NoDirectTimeAccess`: `Clock.System`, `System.currentTimeMillis()`, `SystemClock` and `TimeZone.currentSystemDefault()` fail outside package `com.yawnandpawn.app.android` (and sub-packages); active for app modules (`:core`, `:data`, `:composeApp`, `:androidApp`, `:testing`), not build tooling; violating + compliant snippet tests; covered by the existing `DetektConfigTest` activation check.
- `Deadline(wallMillis, elapsedMillis, bootCount)`: `isDue(now)` and `remaining(now)` compare elapsed time when `now.bootCount == bootCount`, wall time otherwise; `remaining` is never negative.
- `nextOccurrence(rule, now, zone)`: `AlarmRule(time: LocalTime, repeatDays: Set<DayOfWeek>)` (empty = one-time); first instant strictly after `now` whose local date's weekday is in the set (any day for one-time) at the rule time; DST gap → shifted forward by the gap length; DST overlap → earlier instance only, never the second; zone change recomputes in the new zone. `durationUntil(occurrence, now)` is the single source for the Home countdown (Story 1.9).
- Tests table-driven, backticked sentence names, at least Europe/Berlin, America/New_York and Australia/Lord_Howe (30-minute DST). `:core` stays ≥ 90% line coverage and platform-free.

**Never:**
- No scheduling, storage or UI in this story (Stories 1.7–1.10).
- No `java.time` or Android types in `:core`; no new `:core` dependencies beyond the allowed four.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Same boot, wall jump | deadline in 10 min; wall moved ±2 h, elapsed +5 min | not due, 5 min remaining | N/A |
| After reboot | boot count changed; wall past deadline | due, remaining 0 | N/A |
| Remaining past due | same boot, elapsed beyond deadline | due, remaining 0 (never negative) | N/A |
| One-time, later today | 07:00 rule, now 06:00 | today 07:00 | N/A |
| One-time, passed | 07:00 rule, now 08:00 | tomorrow 07:00 | N/A |
| Exactly now | rule time equals now | next matching day (strictly after) | N/A |
| Weekdays on Friday evening | Mon–Fri 07:00, now Fri 20:00 | Monday 07:00 | N/A |
| DST gap | 02:30 daily, Europe/Berlin, 2027-03-28 | 03:30 local that day | N/A |
| DST gap 30 min | Australia/Lord_Howe gap day, time inside the gap | shifted forward 30 min | N/A |
| DST overlap | 02:30 daily, Europe/Berlin, 2027-10-31 | earlier 02:30; next call after it → 2027-11-01 02:30 | N/A |
| Zone change | same rule, Berlin → New_York | rule time in New_York | N/A |
| Direct clock access | `Clock.System.now()` in `:core` / `:composeApp` | detekt fails | rule id reported |
| Adapter package | same call in `com.yawnandpawn.app.android` | no finding | N/A |

</frozen-after-approval>

## Code Map

- `core/src/commonMain/kotlin/com/yawnandpawn/app/core/` -- only `AppVersion.kt` today; add `time/` (ports, `TimeSnapshot`, `Deadline`) and `alarm/` (`AlarmRule`, `nextOccurrence`, `durationUntil`) per the Conventions sub-packages.
- `core/build.gradle.kts` -- kotlinx-datetime already a dependency; `kotlin.time.Clock` is stdlib in Kotlin 2.4.
- `testing/src/commonMain/kotlin/com/yawnandpawn/app/testing/` -- builders live here; add the four fakes.
- `androidApp/src/main/kotlin/com/yawnandpawn/app/YawnAndPawnApp.kt` -- `appModule` is empty; bind adapters there (adapters themselves in `.../app/android/`).
- `config/detekt-rules/` -- rule pattern and `isInThemePackage`-style package check (Story 1.3) to copy for the `android` package exemption; `DetektConfigTest` already asserts every provider rule is active; register the new rule in `YawnAndPawnRuleSetProvider` and `config/detekt/detekt.yml` (scope via `includes`/`excludes` so build tooling isn't affected).
- `build-logic/.../CoreDependencyRules.kt` -- `:core` import check; no change expected.

## Tasks & Acceptance

**Execution:**
- [x] `core/.../time/` -- ports, `TimeSnapshot`, `Deadline` + table-driven tests.
- [x] `core/.../alarm/` -- `AlarmRule`, `nextOccurrence`, `durationUntil` + table-driven tests for every matrix row across the three zones.
- [x] `testing/.../` -- four fakes with `advanceBy`, `set`, `reboot` + tests.
- [x] `androidApp/src/main/kotlin/com/yawnandpawn/app/android/` -- adapters; `appModule` bindings; Robolectric test that Koin resolves each port.
- [x] `config/detekt-rules/` + `detekt.yml` -- `NoDirectTimeAccess` with tests.

**Acceptance Criteria:**
- Given the branch, when `./gradlew qualityGate` runs, then it passes with `:core` ≥ 90% line coverage and the new rule active.

## Implementation Notes

- `./gradlew qualityGate` passes locally (BUILD SUCCESSFUL); `koverLog` reports `:core` line coverage 100%.
- **Core** (`core/.../core/time/`): `Clock` is a `typealias` for `kotlin.time.Clock`. `MonotonicClock`, `BootCounter` (Int, like `Settings.Global.BOOT_COUNT`) and `TimeZoneProvider` are `fun interface`s. `TimeSnapshot.of(clock, monotonic, bootCounter)` reads all three. `Deadline.after(now, duration)` builds a deadline. `isDue` is `remaining == 0`. `core/build.gradle.kts` changed kotlinx-datetime from `implementation` to `api`, because `TimeZone`/`LocalTime`/`DayOfWeek` are in the public API. This adds no new dependency.
- **Occurrence** (`core/.../core/alarm/`): `AlarmRule` (with `isOneTime`, `ringsOn(day)`) in `AlarmRule.kt`; `nextOccurrence` and `durationUntil` (coerced to >= 0) in `AlarmOccurrence.kt`. The algorithm follows the Design Notes: scan days 0..7, then `first { it > now }`. Tests cover every matrix row in Berlin, New York and Lord Howe, plus the overlap cases in all three zones (earlier instance, a call at it, a call between the two instances). Finding: a gap-shifted instant is the *same instant* as the rule time on the pre-change offset (02:30 CET = 03:30 CEST). The shift is in local time only, and the test asserts both.
- **Fakes** (`testing/.../TimeFakes.kt`): each fake has `set`, plus `advanceBy`/`reboot` where they make sense (`FakeClock`: advanceBy, set; `FakeMonotonicClock`: advanceBy, set, reboot, never negative; `FakeBootCounter`: set, reboot; `FakeTimeZoneProvider`: set). `FakeTime` groups the four fakes: `advanceBy` moves wall and elapsed time, `setWall` jumps the wall only, `reboot()` means next boot count, elapsed 0, wall unchanged. `snapshot()` is the `TimeSnapshot` helper.
- **Android** (`androidApp/.../app/android/`): `AndroidMonotonicClock`, `AndroidBootCounter(contentResolver)` (default 0), `AndroidTimeZoneProvider` and `androidTimeModule` (`single<Clock> { kotlin.time.Clock.System }`). `appModule` does `includes(androidTimeModule)`, so the `Clock.System` binding stays inside the exempt package. `AndroidTimeAdaptersTest` (Robolectric) checks Koin resolution for each port and each adapter's reading.
- **detekt** `NoDirectTimeAccess`: reports `Clock.System` (qualified too), `System.currentTimeMillis()`, any `SystemClock` reference (calls, callable refs, fully qualified) and `TimeZone.currentSystemDefault()`, plus the static imports `...Clock.System`, `System.currentTimeMillis` and `TimeZone.Companion.currentSystemDefault`. Exempt: `com.yawnandpawn.app.android` and sub-packages (a look-alike `...androidx` or `...ui.android` is reported). Scoped by `includes` to `core`, `data`, `composeApp`, `androidApp`, `testing` sources. The root detekt run over `build-logic`/`tools/tokens` does not load the custom rules anyway. Five tests. Manual probe: `kotlin.time.Clock.System.now()` in `:core` and `:composeApp` failed `detekt` with `[NoDirectTimeAccess]`, and `System.currentTimeMillis()` in `build-logic` was not reported (all reverted).
- **Environment pitfalls (company laptop):** (1) After changing a detekt rule, the Gradle daemon kept the old rule classes and the detekt result got cached, so the first probe passed wrongly. Use `./gradlew --stop` and `--rerun` when probing rules. (2) `core.autocrlf=true` checks out `tools/tokens/src/test/resources/fixture-design.md` with CRLF, and `DesignTokenParserTest` "a duplicate key is rejected" then fails (its string replace expects LF). This was already the case before this story. Locally the working copy was converted to LF (no content diff in git). A lasting fix would be `*.md text eol=lf` (or a path rule) in `.gitattributes`, which is out of scope here.

- Orchestrator: an accidental `git checkout -- .` reverted the agent's tracked-file edits; the agent re-applied them (core/build.gradle.kts, YawnAndPawnApp.kt, YawnAndPawnRuleSetProvider.kt, detekt.yml, NoPrintlnInCoreTest.kt) and a clean `qualityGate --rerun-tasks` passed. Also fixed the Windows CRLF checkout of the Story 1.3 token fixture with a `.gitattributes` LF rule for `tools/tokens/src/test/resources/**`.

## Spec Change Log

## Review Triage Log

Pass 1 (blind-hunter, edge-case-hunter, verification-gap):

| # | Finding | Verdict | Evidence | Route |
|---|---|---|---|---|
| 1 | Missing `BOOT_COUNT` → 0 every boot → reboot undetected by `Deadline`; KDoc claim false; fallback untested | medium | `getInt(..., BOOT_COUNT, 0)`; same-boot branch compares restarted elapsed clock | patch |
| 2 | `NoDirectTimeAccess` bypasses: static `SystemClock.*` import, `TimeZone.Companion.currentSystemDefault()`, aliased imports | medium | Rule matches receiver text / import suffixes only | patch |
| 3 | Other system-time reads not banned (`nanoTime`, `java.time *.now()`, `Date()`, `Calendar`, `ZoneId.systemDefault`, `java.util.TimeZone.getDefault`) | medium | AD-3: time only through ports; these read the clock directly | patch |
| 4 | Exemption by package lets any module opt out | medium | Package check only | patch |
| 5 | Import suffix match lacks `.` boundary (false positive) | low | `endsWith` without dot | patch |
| 6 | detekt.yml `includes` scoping untested | medium | `DetektConfigTest` checks only `active` | patch |
| 7 | `Deadline.after` negative/infinite duration overflow; subtraction overflow | low | `now.wallMillis + millis` with `Long.MAX_VALUE` | patch |
| 8 | `FakeTime` desync on negative/sub-ms advance; negative constructor | low | Wall mutated before monotonic throws | patch |
| 9 | Post-reboot wall clock may be wrong until time sync | maybe-false (medium if true) | Deadline falls back to wall after reboot by design (AD-3); handling belongs with scheduling/restore (1.10–1.12) | defer |
| 10 | Callable references / `with(Clock)` scopes bypass the rule | low | Needs type resolution; unlikely in practice | reject |
| 11 | `nextOccurrence` on a skipped calendar day (Pacific/Apia 2011) / `first {}` throwing | low / false | Scan of 0..7 days always contains an allowed weekday strictly after now; skipped-day zones are historical edge cases | reject |
| 12 | `.gitattributes` note contradiction / existing checkouts not renormalized | low | Renormalized and re-checked-out on this laptop (`file` shows LF); fix to Implementation Notes wording is a spec edit | reject |
| 13 | No zone in `TimeSnapshot`; no `now()`/`zone()` helpers on `FakeTime` | low | Convenience for Story 1.9; not a defect | reject |

## Design Notes

- Occurrence algorithm that satisfies both DST rules: walk local dates from `now`'s local date forward (at most 8 days); for each allowed weekday build `LocalDateTime(date, time).toInstant(zone)` (kotlinx-datetime resolves gaps forward by the gap and overlaps to the earlier offset); return the first candidate strictly after `now`. The second overlap instance is never generated, so "never the second 02:30" holds by construction; assert it explicitly in the overlap test.
- Environment (company PC): JDK 17 at `C:/Users/BuiTua/AppData/Local/Programs/jdk17/jdk-17.0.20.1+1` (export `JAVA_HOME` before `./gradlew`).

## Verification

**Commands:**
- `./gradlew qualityGate` -- expected: BUILD SUCCESSFUL, koverVerify ≥ 90%.
- Temporarily add `Clock.System.now()` to a `:core` file and run `./gradlew :core:detekt` -- expected: `NoDirectTimeAccess` failure (revert).
