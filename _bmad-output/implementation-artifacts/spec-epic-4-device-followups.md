---
title: 'Epic 4 device follow-ups: the re-ring clock and the reuse sheet after a slow approval'
type: 'bugfix'
created: '2026-10-19'
status: 'done'
baseline_revision: '620fb48d'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/spec-1-15-ringing-screen-over-the-lock-screen-with-i-m-up.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-4-11-billing-orchestration-launch-recovery-item-already-owned-and-stranded-reuse.md'
warnings: []
deferred:
  - 'Story 5.13 (human-verify): on the Oppo, a re-ring shows the time it rings and the clock turns over at the minute; a slow test-card approval followed by Pay shows the reuse sheet with no Play "You already own this item." dialog'
---

<intent-contract>

## Intent

**Problem:** two Story 4.18 device findings (Epic 4 retro, branch `fix/epic-4-device-followups`). (1) After a snooze the Ringing screen's big clock shows the session's first ring (`config.scheduledAt`, 23:01 shown at 23:11), not the time now. (2) Right after a slow test-card approval, the next Pay at that price shows Play's own "You already own this item." before the app's reuse sheet, because the pre-launch owned-purchases list had not caught up.

**Approach:** (1) every Ringing screen (session ring, re-ring, test alarm, Direct Boot, emergency ring, the screen opened before the session) shows the injected `Clock`'s time now, refreshed at each minute boundary; the notification and history keep the alarm's own time. (2) Before launching, when the pre-launch list may lag a payment of the launched product, the coordinator asks Play's owned purchases once more and reconciles; a reuse found then shows the reuse sheet without opening Play. Still unresolved, Play opens and the `ITEM_ALREADY_OWNED` recovery stays the fallback.

## Boundaries & Constraints

**Always:** the app's injected `Clock` (never `Clock.System` in UI code); never grant twice; never launch while a reuse is known to be possible; nothing consumed without the user's yes; no new strings.

**Never:** no change to the notification's time, history rows, the Snoozed screen's "Next ring at" or the reconciler rules.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Re-ring | session scheduled 06:00, now 06:09 | clock 06:09 | — |
| Minute turns | clock at 06:09:40 | new time at 06:10:00, then each minute | — |
| Approved after the query | pre-launch list shows the product pending (another session), the second query shows it purchased | reuse sheet, Play never opened | — |
| Stranded by an update, list lagging | product stranded before the query, list lacks it, second query lists it | reuse sheet, Play never opened | — |
| Still lagging | both lists show it pending | Play opens once; `ITEM_ALREADY_OWNED` recovers the reuse | — |
| Nothing of the product listed | — | Play opens at once, one query | failed query: launch as before |

</intent-contract>

## Code Map

- `composeApp/.../ui/wake/RingingMapping.kt` -- `ringingUiState(session, availability, zone, now, priceOf)` and `alarmOnlyRingingUiState(now, zone)` show [now].
- `composeApp/.../ui/wake/WakeClockTicks.kt` (new) -- `minuteTicks(clock)`, `untilNextMinute(now)`.
- `androidApp/.../android/wake/WakeActivity.kt` -- injects `Clock`, collects `minuteTicks`, passes the time now to every Ringing state.
- `core/.../billing/PurchaseCoordinator.kt` -- `prepareAndLaunch`: a second `PreLaunch` reconcile after `PRE_LAUNCH_REFRESH` (1 s) when `Reconciled.mayBeStale`; `Reconciled` carries the listed owned and purchased products; `Pass.consumed`.
- Tests: `RingingMappingTest`, `WakeClockTicksTest` (new), `WakeActivityTest` (fixed `FakeClock`s, a re-ring clock test), `PurchaseCoordinatorTest` (4 new cases), `FakePlay.onQuery`; samples pass the sample's time (`RingingSamples`, `SnoozeVariantSamples`), and `SnoozedScreenshotTest`'s re-ring samples show the re-ring time (baselines re-recorded on purpose).

## Design Notes

- **DEFAULT TAKEN (owner can change): the refresh waits `PRE_LAUNCH_REFRESH` = 1 s** and runs at most once per Pay, only on evidence the list lags (the launched product still listed without stopping the launch, or stranded before the query and not listed as purchased). The alarm keeps ringing; the confirm sheet's Pay is already committed.
- **DEFAULT TAKEN: the emergency ring and the screen opened before the session also show the time now** (they were the alarm's time).

## Spec Change Log

## Review Triage Log

## Verification

**Commands** (`--no-daemon --max-workers=2`, JDK 17):
- `:core:jvmTest --tests "*PurchaseCoordinator*"` -- expected: pass
- `:composeApp:testAndroidHostTest --tests "com.yawnandpawn.app.ui.wake.*"` -- expected: pass
- `:androidApp:recordRoborazziDebug` for `SnoozedScreenshotTest`, then `:androidApp:testDebugUnitTest` for the wake screenshot classes and `WakeActivityTest`, `TalkBackF5FlowTest`, `DirectBootNoteTest`, `TestAlarmFlowTest` -- expected: pass
- `spotlessCheck detekt` -- expected: pass

## Auto Run Result

Status: done (fast mode; not pushed). Rebased onto main `a7246758`.

**Verification** (`--no-daemon --max-workers=2`, JDK 17), all passing:
- Coordinator: `PurchaseCoordinatorTest` 28, `PurchaseCoordinatorSequenceTest` 13, `PurchaseCoordinatorReviewTest` 19.
- Wake mapping: `com.yawnandpawn.app.ui.wake.*`, including `RingingMappingTest` 13 and `WakeClockTicksTest` 2.
- Wake screen: `WakeActivityTest` 20, `PaymentOutcomeMessagesTest` 8, `TestAlarmFlowTest` 14, `DirectBootNoteTest` 3, `TalkBackF5FlowTest` 1, `SnoozedScreenTest` 11.
- Screenshots: `RingingScreenshotTest` 20, `SnoozedScreenshotTest` 12, `DirectBootNoteScreenshotTest` 4, `SnoozeVariantsTest` 36, `RingingSemanticsTest` 15. Four re-ring baselines were re-recorded on purpose: the 6:15 alarm now shows 6:24.
- Before the rebase, the whole `com.yawnandpawn.app.android.wake.*` package passed.
- `spotlessCheck detekt`.

**Residual risks:**
- The 1 s refresh and Play's list timing are tested on the host only; the Story 5.13 device check covers them.
- `PaymentOutcomeMessagesTest` failed once under load before the rebase, then passed on both re-runs. It looks like a timing flake unrelated to this change.
