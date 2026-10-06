---
title: 'Story 3.3: Success screen after the check (basic)'
type: 'feature'
created: '2026-10-06'
status: 'done'
baseline_revision: '10582a6'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-3-context.md'
  - '{project-root}/.claude/skills/pps-design/SKILL.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** When a session reaches `Completed`, `WakeActivity` simply finishes. The user gets no calm confirmation that the alarm is done and nothing was charged (FR-MSG-3 basic, FR-PWK-9).

**Approach:** `WakeActivity` shows the approved `SuccessScreen` (Sunrise) for the session it showed once that session completes. This is UI-only state keyed by `sessionId`, while the engine goes on to `Recorded` → `Idle` in the background. The screen closes on "Done" or after 60 s. The engine's `StateFlow` conflates `Completed` into `Idle`, so `SessionEngine` gains an in-memory `ended` flow: the last session that ended here. The screen reads it to tell a completed session from a missed one.

## Boundaries & Constraints

**Always:**
- Reuse the approved `SuccessScreen` and its strings (`success_zero_snooze_first`, `success_after_snooze`, `success_test`, `success_done`), which match the EXPERIENCE.md Key strings verbatim. No new strings.
- Variants: a test session shows "Test finished. Your alarm works."; 0 snoozes shows "Up on time."; one or more snoozes shows "You're up. That's what counts.". The after-snooze state is reachable only through the `FakeBilling` events until Epic 4.
- Basic mode until Epic 6: no count-up, no confetti, no streak number. The "{paid} paid this morning" line waits for Epic 4 (Money in the session), and the pending-payment note waits for Epic 4 too. The success haptic (`Confirm`) plays once per success, even across an activity recreation. "Done" is a full-width 72 dp `button-wake-primary` in the thumb zone.
- Back does nothing. Home (activity stopped) closes Success, so the next app open shows Home. A new alarm while Success is visible switches the single `WakeActivity` to the ringing screen of the new session.
- The preview catalogue keeps its approved Epic 6 look (celebration, 64 dp Done, paid line). Preview baselines stay unchanged.

**Never:** No new session states, table rows or strings. No streaks (Epic 6) and no Money (Epic 4). No `androidTest`. No UI timer as the source of session timing; the 60 s close is only a screen timeout.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| On time | Completed, `snoozesGranted` 0, not test | "Up on time." + Done; engine Idle, store cleared, notification gone, sound off | No error expected |
| After snooze | Completed, `snoozesGranted` ≥ 1 | "You're up. That's what counts." with no paid line | No error expected |
| Test | Completed, `testMode` | "Test finished. Your alarm works." | No error expected |
| Done | Tap "Done" | Activity finishes | No error expected |
| Timeout | 60 s without a tap | Activity finishes (59 s: still shown) | No error expected |
| Home | Activity stopped while Success shows | Activity finishes; MainActivity shows Home | No error expected |
| New alarm | `AlarmFired` (new id) while Success shows | Ringing screen of the new session, not finishing | No error expected |
| Missed | Session ends Missed | Activity finishes, no Success | No error expected |
| Emergency ring | Emergency ring stopped | Activity finishes, no Success (no session) | No error expected |

</intent-contract>

## Code Map

- `core/.../session/SessionEngine.kt` -- adds the in-memory `ended: StateFlow<SessionState.Active?>`, set before `state` on every committed Completed or Missed.
- `composeApp/.../ui/wake/SuccessMapping.kt` (new) -- pure `successUiState(session)`: Test, OnTime(0) or AfterSnooze(no paid).
- `composeApp/.../ui/wake/WakeContract.kt` -- `AfterSnooze.paidThisMorning` becomes nullable (null: no line).
- `composeApp/.../ui/wake/WakeEndScreens.kt` -- `SuccessScreen(basic = false)`. In basic mode it has no celebration, one saved success haptic and a 72 dp Done.
- `androidApp/.../wake/WakeScreenEnd.kt` (new) -- follows what rings, holds the Success `sessionId` (saved state), and closes on stop while Success shows.
- `androidApp/.../wake/WakeActivity.kt` -- renders Success (basic) from `WakeScreenEnd`, Done, and the 60 s timeout. The new-alarm switch follows from the engine state.
- Tests: `SessionEngineTest`, `SuccessMappingTest` (new), `SuccessScreenTest` + `SuccessScreenshotTest` (new, Robolectric), `WakeActivitySuccessTest` (new), and the existing finish expectations in `WakeActivityTest`, `OneTimeAlarmFlowTest` and `TestAlarmFlowTest` updated to Success → Done.

## Tasks & Acceptance

**Execution:**
- `SessionEngine.kt` -- `ended` flow -- the UI cannot see `Completed` through the conflated `state`.
- `SuccessMapping.kt`, `WakeContract.kt`, `WakeEndScreens.kt` -- the basic success variant -- the story ACs without changing the approved preview.
- `WakeActivity.kt` -- show, close and switch -- the story ACs.
- Tests listed in the Code Map, plus Roborazzi `wake_success_{on_time,after_snooze,test}_sunrise[_font200].png`.

**Acceptance Criteria:**
- Given a session the screen showed reaches Completed, when `WakeActivity` observes it, then it shows Success instead of finishing, while the engine reaches Idle with `runtime.db` cleared, the notification removed and the sound stopped.
- Given Success, when "Done" is tapped or 60 s pass, then the activity finishes. Back does nothing, and Home closes it.
- Given Success, when a new alarm fires, then the screen shows the new ringing session.
- Given TalkBack, then the headline (a heading) comes first, then "Done". Screenshots cover the three variants in Sunrise at 100% and 200%.

## pps-design Done checklist

- [x] Only tokens from `DESIGN.md` used (no raw hex, no new radii, no new font sizes): the approved `SuccessScreen`, `WakePrimaryButton` (hero 72 dp) and glass card.
- [x] Light, Dark (and Sunrise where relevant) checked with previews / screenshots: wake screen, Sunrise only (UX-DR2); Roborazzi at 100% and 200%.
- [x] Every colour pair used is in the `DESIGN.md` contrast table: `text-sunrise` on glass and `on-accent-sunrise` on `accent-sunrise`, both already used by the approved screen.
- [x] Touch targets ≥ 48 dp; wake actions ≥ 64 dp: "Done" is 72 dp (test).
- [x] Works at 200% font scale and with TalkBack; outcome glyphs present: Done stays on screen in the thumb zone at 200% (test); the headline is a heading read first. No outcome glyph applies (no history outcome is shown).
- [x] Reduced-motion path works: basic Success has no motion at all.
- [x] Copy matches `EXPERIENCE.md > Voice and Tone` (no em dashes, no filler, strings in resources): existing resources only; `CopyRulesTest` passes.
- [x] Every state row in `EXPERIENCE.md > State Patterns` for this surface is handled: zero snooze (basic: "Up on time.", haptic; the streak animation is Epic 6), after snooze (headline; the paid line is Epic 4), test. "Pending not used" is Epic 4.
- [x] "I'm up" is the most prominent wake action; snooze is visible, plain and priced: not on this screen. "Done" is its only action.
- [x] Compose `@Preview`s for each state (light/dark/Sunrise, empty/error) exist; screenshot tests updated (Paparazzi or Roborazzi): the preview catalogue's `success-*` states, plus the new production screenshots.

## Spec Change Log

## Review Triage Log

### Review (2 reviewers, fast mode), 2026-10-06

6 findings, all patched (`fix(3.3): review fixes`):

| # | Finding | Verdict | Action |
|---|---------|---------|--------|
| 1 | After a kill, the screen is recreated with `seen` while the engine is Idle before `restore()`, so it closed before the ring came back | high | `WakeScreenEnd.follow` closes on Idle only once `engine.restored` is true, and the effect is keyed on it. `WakeScreenEndTest` |
| 2 | An emergency ring that replaced Success kept the old session id, so Success (haptic, timer) came back when the emergency ring stopped | medium | A ring with no session id clears `shownSessionId`, matching the matrix row "Emergency ring stopped". `WakeScreenEndTest` and `WakeActivitySuccessTest` |
| 3 | The 60 s timeout restarted on a recreation | medium | The Success start time (`MonotonicClock`) is in the saved state, and the delay is the time left. A test recreates at 40 s and the screen closes at 60 s in all |
| 4 | The haptic flag lived in `rememberSaveable` inside `key()`, so Success re-entering composition played it again | low | `SuccessScreen(claimHaptic)`. `WakeScreenEnd.claimHaptic(sessionId)` is saved state. Unit and composable re-entry tests |
| 5 | The recreation test did not catch removing the `isChangingConfigurations` guard | low | Asserts that neither the old nor the new instance is finishing |
| 6 | No test for a restore after a process death while Success showed | low | A saved bundle plus a fresh engine (no `ended`) closes the screen |

Mutation checks: removing fixes 1–3 makes the new tests fail.

## Design Notes

- **Why `ended` in the engine:** `SessionEngine` publishes Completed and then Idle inside one lock, so a UI collector usually sees only Idle and cannot tell a completion from a miss. `ended` is set before `state`, so a reader that sees the session gone can read `ended.value` and trust it. It lives in memory only (it is not persisted) and changes nothing in the AD-2 table.
- **Done at 72 dp:** the story and epic context say 72 dp `button-wake-primary`. The approved preview used 64 dp (`hero = false`). The production basic variant follows the story, and the preview look is kept for Epic 6 to settle.

- **Deferred:** a session whose ring the screen never saw (for example, opened from a restore straight into a stored Completed) shows no Success and waits. This is the same as the Epic 1–2 wait. The Success state lives in memory, so after a process death Success is not shown again: the restored screen closes.

## Verification

Results from the run (2026-10-06):
- New tests: `SessionEngineTest` (+2), `SuccessMappingTest` (3), `SuccessScreenTest` (7), `SuccessScreenshotTest` (6 new baselines `wake_success_*`) and `WakeActivitySuccessTest` (10). Existing finish expectations were updated in `WakeActivityTest`, `OneTimeAlarmFlowTest` and `TestAlarmFlowTest`.
- A mutation check: replaying the haptic on recreation makes the haptic test fail.

**Commands:**
- `./gradlew qualityGate` -- expected: BUILD SUCCESSFUL.
- `git status --porcelain androidApp/src/test/screenshots/preview` -- expected: empty.
