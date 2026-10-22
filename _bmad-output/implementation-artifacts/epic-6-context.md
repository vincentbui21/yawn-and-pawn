# Epic 6 Context: Wake-up progress

<!-- Written 2026-10-22 from planning artifacts (epics.md Epic 6, design preview rounds 1 and 2, sprint-change-proposal-2026-10-01, architecture) and the code on main at ae9743af (Epics 1 to 4 merged, Epic 5 in review). Planning only: no story has started. Regenerate if planning docs change. -->

## Goal

The user sees how their mornings are going. Home shows the current zero-snooze streak and this week's money above everything else. Progress shows the ring of the last 30 mornings, three small stat tiles (on-time rate over 30 days, average time to get up, snoozes over 30 days), the streak card, a month calendar, "Money paid" and an Insight line. Each morning opens a Day detail. A Sunday-evening notification sums up the week, and an on-time Success screen celebrates a growing streak. Every number comes from pure functions in `core.stats` over `session_history` (written only by `SessionRecorder`) and the purchase records (written only by `PurchaseLedger`); nothing stores derived stats (AD-18, AD-8). The epic is built while the Epic 5 closed test runs.

## Stories

- 6.1 Streaks, on-time rate and time to up in `core.stats` (auto)
- 6.2 Snoozes over 30 days, money per currency, day outcomes, Insight and `ObserveProgress` in `core.stats` (auto)
- 6.3 Streak and money hero card on Home (auto)
- 6.4 Zero-snooze celebration on the Success screen (auto, plus a device check in 6.10)
- 6.5 Progress screen with the 30-morning ring, stat tiles and money (auto)
- 6.6 Calendar of morning outcomes (auto, Should)
- 6.7 Day detail for each morning (auto, Should)
- 6.8 Weekly summary notification on Sunday evening (auto, Should, plus real delivery in 6.10)
- ~~6.9 Export history as CSV~~: dropped by sprint-change-proposal-2026-10-01 (FR-PRG-6 removed).
- 6.10 Epic 6 device verification checklist (owner, human-verify; automation never marks it done)

## Changes already made by the 2026-10-01 sprint change proposal

These are in `epics.md` already; no further correct-course is needed for them.
- **Progress = the 30-dot ring with no legend.** Each dot carries its meaning by shape and colour (filled dot on time, dot with a small clock snoozed, hollow ring missed, small neutral dot skipped or test, outlined pill today). A first tap shows a label chip ("Tue 23 · Snoozed"); a second tap or the chip opens Day detail. No "Progress" heading, no period tabs.
- **Dropped:** FR-PRG-6 Export CSV (Story 6.9) and the FR-PRG-2 "Snoozes this week" bar chart. 6.2 builds no weekly series for a chart; the weekly totals stay for 6.8.
- **Day detail redesign:** one-line title, a hero per session, three small tiles (rings · snoozes · paid), and a morning timeline that draws in top to bottom.

Leftover stale names to clean up in 6.5: the sprint-status key `6-5-progress-screen-with-stat-tiles-snoozes-chart-and-money`, and the `progress-full` and `tap-progress` rows in `docs/design-preview/states.md`, which still say "chart".

## Shared stats rules (epics.md, owner-approved default 2026-09-26)

- **Day of a session:** the local date of `scheduledAt` in the zone from `TimeZoneProvider` when computed (a 23:50 alarm finished at 00:10 belongs to the earlier day).
- **Counted session:** outcome OnTime, Snoozed or Missed. Test, Skipped and a null outcome (still running) never count.
- **Good day:** at least one counted session, all OnTime. Any Snoozed or Missed session breaks the streak. Days with nothing counted neither extend nor break it.
- **Week:** ISO week, Monday 00:00 to Sunday 24:00 local. **Month:** local calendar month.
- **Money paid:** records with status granted, consumed or reused, summed in micros per currency, never across currencies. Stranded records never count.
- **Calendar day outcome:** the worst counted outcome (Missed, then Snoozed, then On time). A day with only Test or Skipped shows the neutral dot, labelled Skipped if any session was skipped, otherwise Test.

## What already exists (reuse it, don't rebuild it)

### Core and data
- **Session history (Story 1.13):** `core/.../history/SessionHistory.kt`. `SessionHistoryRow` holds `sessionId`, `alarmId`, `scheduledAt`, `firstRingAt`, `endedAt`, `snoozeCount`, `checkTypes`, `timeToCompleteMs`, `fallbackUsed`, `fallbackFrom`, `directBoot` and `outcome` (`SessionOutcome` OnTime, Snoozed, Missed, Skipped, Test). `SessionHistoryRepository` has `upsert`, `find`, `observeLatestMissed`, `recordMerge` and `merges(sessionId)` (Story 2.9 `session_merge` rows, "{time} alarm merged into this session"). **There is no read of all rows yet:** 6.2 adds `observeAll()` (Room plus the `:testing` fake). `SessionRecorder` stays the only writer.
- **Purchase records (Story 4.10):** `core/.../billing/PurchaseRecords.kt`. `PurchaseRecord` has `sessionId`, `price: Money`, `priceSource`, `purchasedAt` and `status`; `PurchaseRecordRepository.observeAll()` returns `PurchaseRecordsRead(records, unreadable)`. A record whose price is only an estimate is never shown as an amount paid (`priceSource.isAmountPaid`, used by Purchase history and "Problem with a charge?").
- **Money:** `Money` (micros plus currency), `totalsByCurrency(amounts)` and `MoneyFormatter.formatTotals(...)`, which already joins currencies with " · ". The UI formats only through `moneyFormatter` (AD-8).
- **Commitment events (Story 4.4):** `CommitmentEventRepository.all()` gives the "turned off" and "deleted" rows that Day detail lists. No new table is needed.
- **`core.stats` package exists** (`ReRegisterSuggestion.kt`, Story 3.13). New stats files go next to it.
- **Background work (Story 4.3):** `core/.../work/BackgroundWork.kt`. The port is `enqueue(BackgroundJob)` only: no initial delay, no cancel, and a pending one-time job with the same name is kept (KEEP). `BackgroundTaskKind` has `PriceRefresh` and `ConsumeRetry`. 6.8 needs a delay, REPLACE and a cancel.
- **Time:** the Story 1.6 `Clock` and `TimeZoneProvider` ports, and `FakeClock` in `:testing`.

### UI (design preview code in `:composeApp` `commonMain`: wire it, don't redraw it)
- **Progress screen:** `ui/progress/` has `ProgressScreen` (stateless), `ProgressContract` (`ProgressUiState`, `ProgressIntent`, `Outcome`, `RingDay`, `CalendarMonth`, `DaySelection`, `Insight`), `ProgressRing`, `OutcomeMarker`, `ProgressCharts` (tiles), `ProgressMotion` (entry animation, reduced motion). The strings exist (`progress_*`, `progress_insight_weekdays/weekends`).
- **Progress tab placeholder:** `ui/nav/AppNavHost.kt` `ProgressTab()` renders `emptyProgress(today)` with `onIntent = {}` and `showPurchaseHistory = false`. 6.5 replaces it with a `ProgressViewModel`.
- **Day detail:** `ui/daydetail/DayDetailScreen.kt` (stateless), with `DayDetailUiState`, `SessionDetail`, `MorningEvent` (Rang, Snoozed, ImUp, QuietTimeRanOut, CheckSwitched, FallbackUsed, Merged, CheckSolved, Stopped) and `AlarmChange`. There is **no `Route.DayDetail` yet**; 6.7 adds it.
- **Success confetti exists:** `ui/wake/Celebration.kt` (count-up from n − 1 with a bounce, about 1.5 s of confetti, one success haptic, reduced motion shows the final state) and `WakeEndScreens.kt`. Production passes `SuccessKind.OnTime(streakDays = 0)` (`SuccessMapping.kt`), so today it shows only "Up on time.". 6.4 feeds the real streak and adds the "streak did not grow" path (same text, no animation).
- **Home hero exists:** `HomeHero(streakDays, paidThisWeek)`, `HomeHeader` (collapsing header, compact pinned row) and the `home_streak_*` strings. `HomeViewModel` never sets `hero`, so it is hidden in production. 6.3 fills it.
- **Settings "Weekly summary" switch exists** in `SettingsScreen` (`SettingsRow.WeeklySummary`), hidden in production by `BuiltSettingsRows`, with no ViewModel handling. 6.8 stores it and shows it.
- **Purchase history (Story 4.16)** is the target of the Progress "Purchase history" link (`Route.PurchaseHistory`).

### Gaps between the preview models and the AC
- `HomeHero.paidThisWeek` and `ProgressUiState.paidThisMonth` hold one `Money?`. The AC needs one amount per currency ("€8.98 · $1.00 paid this week"). 6.3 and 6.5 change them to `List<Money>`.
- `SessionDetail` derives everything from timed `MorningEvent`s. History stores only some of those times (see owner question Q1).
- The ring centre always shows "/ 30" (see Q2).

## What each story adds

- **6.1:** `sessionDay`, `isCounted`, `currentStreak`, `bestStreak`, `onTimeRate(days)`, `averageTimeToUp`, `wholeMinutes`, with table tests, a seeded property test, DST and zone-change tests. Pure, new files only.
- **6.2:** `snoozesLast30Days`, `insight`, `moneyPaid(period)`, `dayOutcome`, `monthSummary`, `sessionsOn`; `SessionHistoryRepository.observeAll()`; the `ObserveProgress` use case (`Flow<ProgressSnapshot>`, recomputed on either table, on local midnight and on a zone change through a `DayTicker`); a 3-year performance test (under 50 ms).
- **6.3:** `HomeViewModel` collects `ObserveProgress`; hero visible with at least one alarm or one history row; money line "Nothing paid this week. Keep it that way." or "{paid} paid this week" per currency; TalkBack reads the card as one element.
- **6.4:** `WakeService` loads a history snapshot after the ringing screen shows; `streakBefore` and `streakAfter`; Success shows the streak, animates only when it grew, falls back to "You're up. That's what counts." when the snapshot is missing.
- **6.5:** `ProgressViewModel` over `ObserveProgress`; tiles, `card-streak`, Money paid (this month, per currency) beside Insight; ring dot chip and Day detail navigation; empty and Test-only states; skeleton after 300 ms.
- **6.6:** the calendar fed by `monthSummary`; previous and next month limits; TalkBack "{weekday} {day}, {outcome}".
- **6.7:** `Route.DayDetail(date)`, `DayDetailViewModel` over `sessionsOn`, merges, commitment events and the alarm label (time only when the alarm was deleted).
- **6.8:** `nextWeeklySummaryAt`, `weeklySummary`; the `BackgroundWork` port gains a delayed, replaceable, cancellable job (`BackgroundTaskKind.WeeklySummary`); the worker, the "Weekly summary" channel, the last-posted ISO week in DataStore, re-enqueue from `SystemEventsReceiver` (boot, time set, zone changed); the Settings switch stored through a core use case.

## Dependency graph

```
6.1 ──> 6.2 ──┬──> 6.3 (Home hero)
  │           ├──> 6.5 ──> 6.6 (calendar, same ViewModel)
  │           │      └───> 6.7 (Day detail; needs 6.5's chip intent)
  │           └──> 6.8 (weekly money; the scheduling half needs only 6.1)
  └──> 6.4 (needs currentStreak; the history read comes with 6.2 or a one-shot read of its own)
everything ──> 6.10 (owner)
```

- **Hard edges:** 6.1 before everything; 6.2 before 6.3, 6.5, 6.6, 6.7 and 6.8's money; 6.5 before 6.6 (shared `ProgressViewModel` and screen).
- **Soft edges:** 6.7 can start once 6.5 emits `DayTapped`; it owns the new route. 6.4 can start once 6.1 is merged if it reads history once through a small `all()` read, or wait for 6.2's `observeAll()`.

## Suggested two-lane order (at most 2 Gradle lanes on this PC)

| Order | Lane 1: stats and Progress | Lane 2: wake, Home and notification |
|---|---|---|
| 1 | 6.1 core.stats streaks and rates | Epic 5 leftovers (5.2 option C, 5.12, fixes from the 5.13 session) |
| 2 | 6.2 money, day outcomes, `ObserveProgress` | 6.4 celebration (**waits for 6.1 merged**) |
| 3 | 6.5 Progress screen (**waits for 6.2 merged**) | 6.3 Home hero (**waits for 6.2 merged**) |
| 4 | 6.6 calendar (stacks on 6.5) | 6.8 weekly summary (**waits for 6.2 merged**) |
| 5 | 6.7 Day detail (**waits for 6.5 merged**) | spare: review fixes, deferred work |
| 6 | Integration review, then a release for 6.10 | |

- **Shared-file hotspots:** `strings.xml`, `AppNavHost.kt` and `Route.kt` (6.5, 6.7), the Koin modules in `YawnAndPawnApp.kt` (every story), `WakeService` and `SuccessMapping` (6.4 only), `HomeViewModel` (6.3 only), `SettingsViewModel` and `SystemEventsReceiver` (6.8 only). Keep 6.5 and 6.7 in the same lane so the nav changes don't collide.
- **Schema:** no `app.db` or `runtime.db` migration is planned (unless the owner picks Q1 option B). 6.8 adds DataStore keys (weekly summary on/off, last posted ISO week); update `BackupRulesCoverageTest` if the key's file is backed up.
- **Dependencies:** none new. WorkManager is already allowlisted (Story 4.3).

## Designs

**Approved designs exist (build from them):**
- **6.3 Home hero:** round 1, items 18 (collapsing header) and the `home-list`, `home-half-collapsed`, `home-collapsed`, `home-paid` preview states.
- **6.4 Celebration:** round 1, items 19 and 20; states `success-on-time`, `success-first`, `success-after-snooze`, `success-test`. The animation is already built.
- **6.5 Progress:** round 2, items 21, 23 and 24; states `progress-full`, `progress-dot-chip`, `progress-empty`, and the dark and 200% screenshots in `docs/design-preview/round-2/`.
- **6.6 Calendar:** part of round 2 Progress (`progress-calendar-chip`).
- **6.7 Day detail:** round 2, item 25; states `day-snoozed`, `day-fallback`, `day-before-unlock`, `day-two-sessions`, `day-missed`, `day-test`, `day-skipped`.
- **6.8 Settings switch:** round 2 Settings (`settings-top`, `settings-bottom`).

**Need an owner look before the build (memory: the owner approves screens first):**
- **Money in two currencies** on the Home hero and the Progress "Money paid" card. The preview shows one currency only. A preview state each is enough.
- **Day detail with the facts history really holds**, if Q1 keeps the default (fewer timeline lines than the approved mock-up).
- **The ring centre for a streak over 30** (Q2).
- **The weekly summary notification:** a system notification with approved text only. No mock-up is needed; show a screenshot of it in the 6.8 review.

## Open owner questions (each with a proposed default)

1. **Day detail timeline data.** History stores the first ring, the end, the snooze count, the check types, fallback, Direct Boot and merges; Purchase records add each snooze's time and price. It does not store "rang again", "I'm up", "quiet time ran out" or the number of tries, which the approved timeline shows.
   **Default:** build the timeline from stored facts only: rang (first ring, "before your first unlock" when Direct Boot), each snooze with its price and time, merged alarms, fallback used, check solved at the end time (no try count), or "Stopped after 30 minutes". Rings = 1 + snoozes. Show the owner the reduced screen first.
   *Alternative:* a `session_event` table written by `SessionRecorder` (an `app.db` migration, backup and Delete all data updates, about one extra story). It fills the full timeline, for new sessions only.
2. **Ring centre when the streak is over 30.**
   **Default:** show the streak number and "day streak", and hide "/ 30" once the streak passes 30.
3. **Insight rule.** Only two lines exist ("You get up fastest on weekdays." / "…on weekends.").
   **Default:** compare the average time to get up on weekdays and weekends over the last 30 days. Show the line only when each side has at least 3 OnTime or Snoozed sessions and the gap is 2 minutes or more; otherwise hide the card, and "Money paid" takes the full width.
4. **Bright wake screen changed to option C ("Ringing only").** Success is already at the phone's level, so 6.4's "Done restores the brightness" and 6.10 item 5 no longer apply as written.
   **Default:** change those two lines to "Success shows at the phone's own brightness" (a small `epics.md` edit before 6.4 starts; no correct-course needed).
5. **Releases to the closed testers during Epic 6.**
   **Default:** one Internal-to-closed release once 6.3, 6.4 and 6.5 are merged (the visible part), and one at the end of the epic. Each release takes the next versionCode.

Decided by default, no question needed:
- **Estimated prices in money totals:** Home, Progress, Day detail and the weekly summary count only records whose price is the amount paid (`priceSource.isAmountPaid`), so they always match Purchase history.
- **Tab state:** keep the Progress month and scroll when switching tabs (open `deferred-work.md` item from Story 1.9) in 6.5, if it's cheap; otherwise leave it for v2.

## Risks

- **Day detail data gap** (Q1): the approved timeline promises more than history holds. Without the default or a new table, the build would invent times.
- **Preview models are single-currency:** changing `HomeHero` and `ProgressUiState` to lists means new screenshot baselines in 6.3 and 6.5.
- **Day and zone bugs:** the "day of a session", ISO weeks, DST and zone changes are easy to get wrong. 6.1 and 6.2 carry the table tests; 6.10 item 10 checks a zone change on the phone.
- **Weekly summary on ColorOS:** WorkManager can run late under Oppo battery rules. A run more than 24 h late skips the week by design, so a tester may never see it. The `BackgroundWork` port change (delay, REPLACE, cancel) must not change how price refresh and consume retry behave.
- **Celebration path:** the history snapshot must load after the ringing screen's first frame (NFR-7). `Celebration` counts from n − 1 today; 6.4 adds the no-growth path and the missing-snapshot fallback.
- **Money after "Delete all data":** Story 5.9 keeps purchase records still owed a consume, so a hero could show money right after a delete. Rare; accept it, and note it in the 6.3 story.
- **Building history for 6.10:** the owner check needs a week of mornings (debug fire-now hook and manual date changes on the Oppo). Changing the date on ColorOS triggers `rescheduleAll` and may fire alarms; plan that session on its own. The closed testers' real use is a second source.
- **Lane collisions** on `AppNavHost`, `strings.xml` and the Koin module; keep the order above and rebase before review.

## Deferred work and notes for Epic 6

- `deferred-work.md` (Story 1.9): keep each tab's saved state when switching tabs. Revisit in 6.5.
- The FR-PRG-6 export and the FR-PRG-2 chart items in `deferred-work.md` are resolved by the 2026-10-01 proposal.
- Round 1 owner note (Story 3.14): the confetti celebration was promised for Epic 6. It is 6.4.
