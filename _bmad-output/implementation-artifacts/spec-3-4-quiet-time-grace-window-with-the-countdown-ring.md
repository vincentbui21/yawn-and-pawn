---
title: 'Story 3.4: Quiet time (grace window) with the countdown ring'
type: 'feature'
created: '2026-10-06'
status: 'in-progress'
baseline_revision: 'f88e4f6'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-3-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-3-2-solve-math-to-stop-the-alarm.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** The grace window exists in the engine, but the user cannot set it. "Vibrate during quiet time" is a global setting that is off by default. The countdown header announces every second to TalkBack and has no haptic rhythm.

**Approach:**
- `Alarm.vibrateInGrace` (default on) is stored in `app.db` v5, and `ConfigResolver` freezes it.
- The editor shows the "Quiet time" row and the approved Quiet time sub-screen in production, from the form. The values are saved with Save and covered by "Discard changes?".
- The approved `countdown-ring` gains:
  - a tick every 5 s;
  - polite announcements every 10 s and at 5 s;
  - the plain number with reduced motion;
  - the expired state at once when the deadline has passed.

## Boundaries & Constraints

**Always:**
- The countdown is read from the grace `Deadline` through the engine state (no UI timer), and it stays frozen at the call pause.
- One grace window per ring, and none on a merged ring (engine rule, unchanged).
- The approved composables are reused and the preview baselines stay unchanged.
- The strings are verbatim from EXPERIENCE.md: "Quiet time", "Vibrate during quiet time", "{seconds} seconds", "Quiet for {seconds}s. Finish before it rings again.", "{seconds} seconds left" and "Time's up. Alarm's back on until you finish.".

**Never:** No commitment-lock handling (Epic 4). No Settings default wiring (Epic 5). No new AD-2 rows.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Upgrade | v4 `app.db` with alarms | v5, every alarm with `vibrate_in_grace` = 1, all else kept | Room validates against `5.json` |
| Edit | Quiet time 17 s, vibration off, Save | stored 17 / false; Back before Save asks "Discard changes?" | out-of-range values kept in 15–30 |
| Ring | alarm with vibration off in quiet time | `SessionConfig.vibrateInGrace` false, whatever the global setting | — |
| Countdown | 20 s window | ticks at 15, 10 and 5; TalkBack at 10 and 5 | — |
| Call | paused 8 s in | "Quiet for 12s" until the call ends | — |
| Restore | grace deadline already passed | expired state (bell) until `GraceElapsed` makes it loud | — |
| Reduced motion | animator scale 0 | number without the ring | — |

</intent-contract>

## Code Map

- `core/.../alarm/Alarm.kt`, `AlarmUseCases.kt` (`AlarmDraft`) and `session/SessionConfig.kt` (`ConfigResolver`).
- `data/.../alarm/AlarmEntity.kt` and `AlarmMapping.kt`, `db/AppDatabase.kt` (`SCHEMA_VERSION` 5), `db/AppDatabaseMigrations.kt` (`MIGRATION_4_5`), and `schemas/.../5.json`.
- `composeApp/.../editor/`: the form holds `graceSeconds` and `vibrateInGrace`, the row shows always, the pane renders from the form, and the VM handles the intents and saves.
- `composeApp/.../wake/WakeComponents.kt` (`GraceHeader`: tick, announcements, reduced motion, expired-only live line) and `CheckMapping.kt` (a passed deadline is expired).
- Tests: the migration test, `ConfigResolverTest`, the editor VM tests, `GraceRhythmTest`, `CheckMappingTest`, and the Roborazzi tests (`CheckScreenshotTest`, `AlarmScreensScreenshotTest`).

## Tasks & Acceptance

**Acceptance Criteria:**
- Given `./gradlew qualityGate`, then BUILD SUCCESSFUL, Kover is green and the preview baselines are unchanged. The production editor baselines change on purpose (the new Quiet time row).
- Screenshots: grace at 20 s, at 5 s, paused, expired (`wake_check_math_loud_*` from 3.2) and no-grace, in Sunrise at 100% and 200%. The Quiet time sub-screen in Light and Dark at 200%.

## pps-design Done checklist

- [x] Only tokens from `DESIGN.md` (no new colours, radii or sizes).
- [x] Light, Dark and Sunrise: the wake header is Sunrise; the Quiet time sub-screen is Light and Dark at 200%.
- [x] Every colour pair is in the contrast table (the approved ring and glass pairs, unchanged).
- [x] Targets ≥ 48 dp (slider thumb and switch row unchanged); the wake actions are unchanged.
- [x] 200% font and TalkBack: the slider's state is "{seconds} seconds". The countdown is announced every 10 s and at 5 s (not every second), the ring reads "{seconds} seconds left" on focus, and the expired line is a polite live region.
- [x] Reduced motion: the plain number replaces the ring, and changes are instant.
- [x] Copy is verbatim from EXPERIENCE.md, with no new strings.
- [x] State rows: Grace running, Grace expired ("Alarm's back on" line), paused for a call, and no grace on a merged ring.
- [x] "I'm up" and snooze are unchanged.
- [x] Previews unchanged (`editor-quiet-time`, `check-math`). Roborazzi screenshots added.

## Design Notes

- **Moving the fields:** the quiet time fields move from `FullEditorSections` to `EditorForm`, so Save and "Discard changes?" cover them with no extra code. The row shows whether or not `full` is set, in the same position as in the design preview. Lane 2's Story 3.5 (Wake-up check) touches `RowsCard`, so expect a small merge there.
- **The expired header:** the approved bell plus "Time's up. Alarm's back on until you finish." is kept as is. AC "Alarm's back on" is that line (Component Patterns), and no separate label is added.
- **Announcements:** the live node holds text only at an announced second. An empty description is not spoken, so TalkBack hears "{seconds} seconds left" at 10 s and 5 s only.
- **Schema version:** `app.db` becomes v5. If Lane 2's 3.5 also adds a migration, it takes v6 at merge time.

## Verification

**Commands:**
- `./gradlew qualityGate` -- expected: BUILD SUCCESSFUL.
- `git status --porcelain androidApp/src/test/screenshots/preview` -- expected: empty.

## Auto Run Result

Status: implemented in fast mode (one agent), waiting for review. Branch `story/3-4-quiet-time-grace-window-with-the-countdown-ring`, stacked on Story 3.2 (`f88e4f6`).

**Summary:**
- **Storage:** `app.db` is v5 (`alarm.vibrate_in_grace`, default 1, `MIGRATION_4_5`, exported `5.json`, migration test). `Alarm` and `AlarmDraft` hold `vibrateInGrace` (default on), and `ConfigResolver` freezes the alarm's own value. `GlobalSettings.vibrateInGrace` stays as the future Settings default.
- **Editor:** the Quiet time row and sub-screen work in production from the form, saved with Save and covered by "Discard changes?".
- **Countdown:** the header ticks every 5 s, TalkBack announces every 10 s and at 5 s (no longer every second), the plain number shows with reduced motion, and a passed deadline shows the expired state.

**Deliberate test changes:**
- The production editor and sound-picker editor baselines are re-recorded (the new "Quiet time" row).
- `MainActivityTest` now waits for Home on its first frame. It failed alone on a cold JVM while the session lock restored; this makes the wait deterministic.

**Verification:** `./gradlew qualityGate :androidApp:assembleDebugAndroidTest` gives BUILD SUCCESSFUL (21 min). The Kover gates pass and the preview baselines are unchanged. There are 10 new screenshots: grace at 20 s, 5 s, paused and no-grace (100% and 200%), and the Quiet time sub-screen (Light and Dark at 200%).

**Residual risks:**
- **Schema version:** if Lane 2's 3.5 also bumps `app.db`, its migration becomes 5→6 at merge.
- **TalkBack on a device:** the live announcement node relies on TalkBack speaking a live region when its text appears. This is a Story 3.14 device check.
