---
title: 'Story 6.6: Calendar of morning outcomes'
type: 'feature'
created: '2026-10-26'
status: 'review'
baseline_commit: 'c94ac295'
route: 'dispatch'
story_key: '6-6-calendar-of-morning-outcomes'
review_loop_iteration: 0
lane: 'Lane 1 (stats and Progress): after 6.5, before 6.7; the same ProgressViewModel and screen'
depends_on:
  - '6.2 merged (DaySummary, dayOutcome, monthSummary, DayTicker)'
  - '6.5 merged (ProgressViewModel, ProgressMapping, ProgressRoute, ProgressRouteTest)'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-6-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-6-5-progress-screen.md'
  - '{project-root}/.claude/skills/pps-design/SKILL.md'
  - '{project-root}/_bmad-output/planning-artifacts/epics.md (Story 6.6)'
  - '{project-root}/docs/design-preview/feedback.md (round 2 items 21 and 23)'
---

<frozen-after-approval reason="human-owned intent: do not modify unless the human renegotiates">

## Goal

The Progress calendar card (already drawn by `OutcomeCalendar` in `ProgressCharts.kt`, approved in round 2) shows real
outcomes. Each day's `outcome-marker` comes from 6.2's `monthSummary`. "Previous month" and "Next month" page between
the month of the oldest history row and the current month. A day tap shows the chip and a second tap opens Day detail
(6.7 wires the route). TalkBack reads each day. Shape, colour and the chip label carry the meaning, so there is no
legend.

## Acceptance criteria

1. **Grid.** The calendar card sits below `card-streak`. It shows the shown month as a 7-column, Monday-first grid of
   48 dp `calendar-day` cells, with the locale-formatted month and year as the title (a heading) and weekday initials
   above. It is present in the empty state too (6.5 AC 8).
2. **Markers.** Each cell shows the date in `caption`. Under it is the marker for `DaySummary.outcome`: filled `success`
   dot (On time), dot with a clock in `snoozed` (Snoozed), hollow `missed` ring (Missed), or small `outline` dot
   (Skipped or Test). A day with no session (`None`) shows only the date. A fallback check is told to TalkBack only;
   the `alt_route` badge is in Day detail only.
3. **Today and future days.** Today's date is in the outlined accent pill. Days after today show only the date and are
   never tappable, even if a row exists (clock set back).
4. **Chip.** A first tap on a day with a session shows its chip under the grid ("{weekday} {day} · {outcome}", glossary
   words; instant with reduced motion). A second tap on that day, or a tap on the chip, emits `DayTapped(date)` (6.7
   opens Day detail). There is no legend.
5. **Month buttons.**
   - "Previous month" and "Next month" are 48 dp icon buttons. Each moves the grid one month, with the existing slide;
     with reduced motion the change is instant.
   - "Next month" is disabled on the current month. "Previous month" is disabled on the month of the oldest history
     row, and always when there is no history.
   - A tap on a disabled direction changes nothing (ViewModel guard too).
6. **Live.** The grid recomputes from `monthSummary` when a session is recorded or a purchase record changes, and when
   the local date or zone changes. While the current month is shown, the grid follows today into a new month at
   midnight.
7. **TalkBack.** A cell reads "{weekday} {day}, {outcome}", adding ", fallback check used" and ", {n} sessions" (n > 1)
   when they apply. An empty day reads "{weekday} {day}, no alarm". A cell with sessions is a button. Once it is the
   selected cell, its click label is "open day detail", so TalkBack says "Double-tap to open day detail".
8. **Multi-session day.** A day with a Snoozed 06:00 and an On time 06:30 shows Snoozed (the worst counted outcome)
   and TalkBack adds ", 2 sessions".
9. **Turned off or deleted.** An alarm turned off or deleted inside the commitment-lock window, with no session that
   day, shows no marker (no session row: neither Missed nor counted).
10. **Tests and checks.**
    - Roborazzi covers a month with every outcome, a fallback day, a multi-session day, today, an empty previous month
      and a label chip. Each in Light and Dark, plus Light at 200%, with no clipped cell.
    - A test asserts every glyph and colour pair the calendar uses is in the `DESIGN.md` verified contrast table.
    - The pps-design checklist is ticked, all strings are resources, and `CopyRulesTest` passes.

</frozen-after-approval>

## Before coding: check the 6.2 contract

| 6.6 needs | Expected from 6.2 | If 6.2 does not have it |
|---|---|---|
| Outcomes for one month | `monthSummary(history, records, yearMonth, zone): List<DaySummary>` (pure) | n/a (AC of 6.2) |
| A live flow for one month | none promised | **Add `ObserveMonthOutcomes`** (below) in `core.stats`, next to `ObserveProgress`, reusing its inputs and `DayTicker` |
| Oldest history day | none promised (maybe `ProgressSnapshot.firstDay`) | `ObserveMonthOutcomes` computes it as `history.minOfOrNull { sessionDay(it, zone) }` over rows with a non-null outcome |
| `DaySummary` fields | `date`, `outcome` (None, OnTime, Snoozed, Missed, Skipped, Test), `sessionCount`, `fallbackUsed` | n/a |

If 6.2 already exposes a per-month flow, use it and skip `ObserveMonthOutcomes`.

## Code map

- `composeApp/.../ui/progress/ProgressCharts.kt`: `OutcomeCalendar`, `MonthGrid`, `MonthHeader`, `MonthButton`,
  `DayCell` (clickable when `day != null`), `DayInitial`, `bleed`.
- `.../ui/progress/ProgressContract.kt`: `CalendarMonth(firstDay, days, today, hasPrevious, hasNext)`,
  `CalendarDay(date, outcome, fallbackUsed, sessions)`, `DaySelection(inCalendar)`.
- `.../ui/progress/ProgressRing.kt`: `dayDescription(date, day)` (the TalkBack text, already EXPERIENCE.md wording) and
  `tapIntent(tapped, selection)`.
- `.../ui/progress/ProgressMotion.kt`: `DayChip` (shared with the ring).
- `.../ui/progress/ProgressViewModel.kt`, `ProgressMapping.kt`, `ProgressRoute.kt` (6.5).
- `androidApp/src/debug/.../preview/PreviewRound2Reducer.kt`: the preview's own Previous/Next handling (not production;
  leave it).
- `composeApp/src/androidHostTest/.../ui/theme/ContrastTest.kt`: the `DESIGN.md` table parser (`ContrastRow`), to
  reuse.
- Strings exist: `progress_previous_month`, `progress_next_month`, `progress_day_outcome`, `progress_day_fallback`,
  `progress_day_sessions`, `progress_day_no_alarm`, `progress_chip`.

## Files to add or change (with signatures)

**`core/src/commonMain/kotlin/com/yawnandpawn/app/core/stats/ObserveMonthOutcomes.kt`** (new, only if 6.2 lacks it):

```kotlin
/** One month of the Progress calendar: the outcome of every day, today and the oldest history day (AD-18). */
data class MonthOutcomes(
    val month: YearMonth,
    val days: List<DaySummary>,       // one per day of [month], from monthSummary
    val today: LocalDate,
    val firstHistoryDay: LocalDate?,  // day of the oldest row with an outcome; null without history
)

class ObserveMonthOutcomes(
    private val history: SessionHistoryRepository,
    private val records: PurchaseRecordRepository,
    private val days: DayTicker,      // 6.2's; emits today and zone on midnight and on a zone change
) {
    /** [months] emits the month to show; null means "the month of today", which follows today across midnight. */
    operator fun invoke(months: Flow<YearMonth?>): Flow<MonthOutcomes>
}
```

- It combines `history.observeAll()`, `records.observeAll()`, the ticker and `months`.
- The work is pure inside `monthSummary`.
- Bind it in Koin wherever 6.2 binds `ObserveProgress`.
- Its tests go in `core/src/commonTest/.../stats/ObserveMonthOutcomesTest.kt`, using the test-local builders (`:core`
  tests may not use `:testing`; see the 6.1 spec).
- It is covered by the `stats` Kover rule (90%).

**`ui/progress/ProgressMapping.kt`** (extend):

```kotlin
internal fun calendarOf(month: MonthOutcomes): CalendarMonth
// firstDay = month.month.firstDay
// days = month.days.filter { it.outcome != None }.map { CalendarDay(it.date, it.outcome.toUi()!!, it.fallbackUsed, it.sessionCount) }
// today = month.today.takeIf { it.yearMonth == month.month }   // the pill only in today's month
// hasPrevious = month.firstHistoryDay != null && month.month > month.firstHistoryDay.yearMonth
// hasNext = month.month < month.today.yearMonth
```

Widen `keptSelection(...)`: a calendar selection survives only while its date is in the shown month and still has a
session.

**`ui/progress/ProgressViewModel.kt`** (extend; new constructor parameter):

```kotlin
class ProgressViewModel(
    private val observeProgress: ObserveProgress,
    private val observeMonthOutcomes: ObserveMonthOutcomes,   // or 6.2's equivalent
    private val logger: Logger,
) : ViewModel()
```

- New internal state: `shownMonth: MutableStateFlow<YearMonth?>`. `null` means the current month (default).
- `state = loads.flatMapLatest { combine(observeProgress(), observeMonthOutcomes(shownMonth), selection) { … } }`. The
  calendar comes from `calendarOf(monthOutcomes)` and replaces 6.5's empty current month. Keep 6.5's loading, failure
  and retry exactly as they are (one `catch` around the combined flow).
- `PreviousMonth`: when the last state's `calendar.hasPrevious`, set `shownMonth` to the shown month minus 1 and clear
  a calendar selection. Otherwise do nothing.
- `NextMonth`: when `hasNext`, set the shown month plus 1. If that is today's month, store `null`, so the grid follows
  today again. Clear a calendar selection.
- `DaySelected(sel)` with `sel.inCalendar` sets the selection. One selection at a time: selecting a calendar day clears
  a ring selection and the other way round. This is existing contract behaviour; keep it.

**`ui/progress/ProgressCharts.kt`** (`DayCell`):
- Clickable only when `day != null && (month.today == null || date <= month.today)`. A future day in a past-month view
  cannot happen.
- `clickable(role = Role.Button, onClickLabel = if (isSelected) stringResource(Res.string.progress_open_day_a11y) else
  null, ...)`.
- No pixel change.

**`ui/UiModule.kt`**: pass `observeMonthOutcomes = get()` to `ProgressViewModel`.

**`strings.xml`**: add `<string name="progress_open_day_a11y">open day detail</string>`. It is TalkBack only, a draft
for the v2 wording list.

## ViewModel state and intents (delta on 6.5)

| Intent | Guard | Effect |
|---|---|---|
| `PreviousMonth` | `calendar.hasPrevious` | `shownMonth = shown - 1`; calendar selection cleared |
| `NextMonth` | `calendar.hasNext` | `shownMonth = shown + 1` (or `null` when that is today's month); calendar selection cleared |
| `DaySelected(inCalendar = true)` | the day has a session and is not in the future | selection set; chip under the grid |
| `DayTapped(date)` | n/a | route `onOpenDay(date)` (6.7) |

`CalendarMonth` in the state:
- `firstDay` is the first of the shown month.
- `days` are only the days with a session.
- `today` is set only in today's month.
- `hasPrevious` and `hasNext` follow AC 5.

## Tests

**Core** (only if `ObserveMonthOutcomes` is added; `ObserveMonthOutcomesTest`):
- `null` months follows today: a `FakeClock`-like ticker crossing from Jan 31 to Feb 1 emits February.
- A fixed month stays put across midnight.
- An upserted row in the shown month emits again.
- `firstHistoryDay` comes from the oldest row with an outcome. A null-outcome row is ignored.
- Zone change: a 06:30 Asia/Ho_Chi_Minh row read in Europe/London moves to the previous day and month.

**ViewModel** (`ProgressViewModelTest`, new cases; fakes as in 6.5):
1. History since two months back:
   - The current month gives `hasNext = false` and `hasPrevious = true`.
   - `PreviousMonth` twice reaches the oldest month (`hasPrevious = false`). A third `PreviousMonth` changes nothing.
   - `NextMonth` back to the current month gives `hasNext = false`.
2. No history: the current month with `hasPrevious = false` and `hasNext = false`. Both intents change nothing.
3. A day with a Snoozed 06:00 and an On time 06:30 gives `CalendarDay(outcome = Snoozed, sessions = 2)`.
4. A fallback session gives `fallbackUsed = true`. Test-only and Skipped-only days give `Test` and `Skipped`
   (Skipped wins).
5. A `CommitmentEvent` with no session that day leaves no `CalendarDay` for that date.
6. A calendar selection is cleared by `PreviousMonth`. A ring selection is not cleared by a month change.
7. On the current month, `FakeClock` crossing into a new month moves the grid to the new month and keeps
   `hasPrevious`. On a past month the grid stays.
8. A session recorded today re-renders today's cell.

**Mapping** (`ProgressMappingTest`): `calendarOf` sets `today` only in today's month, `hasPrevious` and `hasNext` at
the edges, and `None` days are left out.

**UI unit / semantics** (`androidApp/.../ui/ProgressCalendarSemanticsTest.kt`, using `TalkBackRules`):
- Cells read "Tuesday 14, On time", "…, fallback check used", "…, 2 sessions" and "Wednesday 15, no alarm".
- A cell with sessions has `Role.Button`. The selected cell's click label is "open day detail".
- A future day with a row (clock set back) has no click action.
- The month title is a heading.
- The month buttons are at least 48 dp and are disabled at the limits.
- Cells are at least 48 dp tall. Nothing is clipped at 200% on 360 dp.

**Contrast** (`composeApp/src/androidHostTest/.../ui/theme/CalendarContrastTest.kt`):
- Using `ContrastTest`'s parser, assert that `DESIGN.md` has a verified row, Light and Dark, for each pair the
  calendar draws over `glass` on `bg`:
  - `success`, `snoozed`, `missed` and `outline` (graphics);
  - `accent` (today's pill border);
  - `text` and `text-secondary` (dates, weekday initials, title);
  - `inverse-text` on `inverse-surface` (chip);
  - `disabled-content` (disabled month button).
- If a pair has no row, **stop and report it**. Do not add rows (the owner signs off contrast).

**Screenshots** (`ProgressScreenshotTest` or a new `ProgressCalendarScreenshotTest`, with samples in
`ProgressSamples.kt`):
- States: `calendar_every_outcome`, `calendar_fallback`, `calendar_multi_session`, `calendar_today`,
  `calendar_empty_previous_month` (a past month with no session; Previous disabled when it is the oldest),
  `calendar_chip`.
- Each in Light and Dark, plus Light at 200%. Screenshot the calendar card node only, so the baselines stay small.
- Re-record the 6.5 Progress baselines whose calendar now shows markers (`typical_f9`, `two_currencies`, `dot_chip`,
  `streak_over_30`).

**Preview baselines:** no change expected (preview states feed the screen directly). If `DayCell` pixels change, re-record
`preview/progress_full*` and `progress_calendar_chip*`.

**Flow via the app nav** (extend `ProgressRouteTest`), seeded with history in this month and last month:
1. Tap "Progress": the calendar title is this month, and "Next month" is disabled.
2. Tap "Previous month": the title becomes last month, and "Previous month" is disabled (the oldest).
3. Tap "Next month": back to this month.
4. Tap a day with a session: the chip shows "{Short weekday} {day} · Snoozed". A second tap emits the open (6.7
   asserts Day detail opens; here assert the chip and no crash).

## Scan tests and rules to respect

- `CopyRulesTest` for `progress_open_day_a11y`.
- `MoneyFormattingScanTest`: no money here.
- `PurchaseStateReaderScanTest`: no `purchaseState` names.
- `SessionHistoryWriterScanTest` and `PurchaseLedgerWriterScanTest`: read only.
- detekt: `NoDirectTimeAccess` (today from `MonthOutcomes.today`, never `Clock.System`), `NoRawColor`, `NoRawSp`,
  `NoRawCornerRadius`.
- `ContrastTest` and the new `CalendarContrastTest`.
- `PreviewMenuTest`: no new preview state needed (`progress-calendar-chip` exists).
- `:core` Kover `stats` at 90% or more if `ObserveMonthOutcomes` is added. `CoreDependencyRules`: core tests do not use
  `:testing`.

## Defaults taken (fast mode; the owner can change them)

- **C1:** the shown month follows today while the current month is shown (stored as `null`). A past month stays put.
- **C2:** a month change clears a calendar chip (as in the preview reducer). A ring chip is kept.
- **C3:** future days are never tappable, even with rows (clock set back). Their dates still show.
- **C4:** the TalkBack outcome word is the glossary label as written ("On time"). TalkBack does not voice case, so the
  AC's "on time" reads the same. No new lowercase strings.
- **C5:** the double-tap hint names "open day detail" only on the selected cell. Other cells keep TalkBack's default
  hint ("Double-tap to activate" shows the chip). One new TalkBack-only string.
- **C6:** a past month with no session in the allowed range shows the bare grid (the "empty previous month" state).
- **C7:** turned-off or deleted alarms leave no marker (PRD Q15 resolution as written). Day detail lists them only on a
  day that also has a session (6.7).

## Risks

- **6.2 has no per-month flow:** `ObserveMonthOutcomes` is about 40 lines plus tests in core. It must share 6.2's
  `DayTicker` and not start a second ticker.
- **YearMonth API:** `kotlinx.datetime.YearMonth` exists in kotlinx-datetime 0.8.0. Use its `firstDay`,
  `plus(1, MONTH)` and `minus(1, MONTH)` (check the exact API; fall back to `LocalDate` arithmetic if needed).
- **Combining three flows:** one `catch` must still turn any failure into 6.5's `loadFailed`, and `RetryLoad` must
  resubscribe both.
- **200% at 360 dp:** seven 48 dp cells with the 12 dp `bleed` fit by design. Assert it at 200% in the semantics test,
  not only by screenshot.
- **Shared files with 6.7:** `ProgressViewModel` and `ProgressRouteTest`. 6.7 stacks on this.

## pps-design Done checklist

- [x] Only tokens from `DESIGN.md` used (no raw hex, no new radii, no new font sizes). No pixel change to `DayCell`.
- [x] Light, Dark (and Sunrise where relevant) checked with previews / screenshots. Light, Dark and Light 200%.
- [x] Every colour pair used is in the `DESIGN.md` contrast table (`CalendarContrastTest`; no row added).
- [x] Touch targets ≥ 48 dp; wake actions ≥ 64 dp. Days and month buttons 48 dp, at 200% on 360 dp too.
- [x] Works at 200% font scale and with TalkBack; outcome glyphs present.
- [x] Reduced-motion path works. The month changes with no transition (`EnterTransition.None`); the chip was already
  instant.
- [x] Copy matches `EXPERIENCE.md > Voice and Tone` (no em dashes, no filler, strings in resources). Two TalkBack-only
  strings for the v2 wording list: `progress_open_day_a11y` "open day detail", `progress_day_date` "%1$s %2$d".
- [x] Every state row in `EXPERIENCE.md > State Patterns` for this surface is handled.
- [x] "I'm up" is the most prominent wake action; snooze is visible, plain and priced. (Not applicable.)
- [x] Compose `@Preview`s for each state (light/dark/Sunrise, empty/error) exist; screenshot tests updated (Roborazzi).
  The preview's `progress-calendar-chip` state already exists; Roborazzi has the calendar card states.

## Verification

Use JDK 17 and `--no-daemon --max-workers=2`, targeted runs only.

- `:core:jvmTest --tests "*stats*"` and `koverVerify` (only if core changed).
- `:composeApp:testAndroidHostTest --tests "*progress*" --tests "*Contrast*" --tests "*CopyRulesTest*" --tests "*UiModuleTest*"`
- `:androidApp:recordRoborazziDebug`, then `:androidApp:testDebugUnitTest --tests "*Progress*" --tests "*Preview*"`
- `spotlessCheck detekt`

## Spec Change Log

**Built on** 6.5's HEAD 6de81f14, rebased onto main c94ac295 once 6.5 merged (#86). The 6.2 and 6.5 code as built wins
over the plan above.

**6.2 contract as built.**

| Spec guess | 6.2 / 6.5 as built | What 6.6 did |
|---|---|---|
| A per-month flow may be missing; add `ObserveMonthOutcomes` | `ProgressSnapshot` already carries `history`, `records`, `today` and `zone` ("for 6.5's month paging") from the one `DayTicker` | **No `ObserveMonthOutcomes`.** `calendarOf(snapshot, shown: YearMonth?)` in `ProgressMapping.kt` maps 6.2's `monthSummary` over the snapshot's history in its zone. One subscription, one ticker, one `catch`, and "Try again" resubscribes everything. |
| `firstHistoryDay` from `MonthOutcomes` | none | **Added** `firstSessionDay(history, zone)` in `core.stats` (`ProgressStats.kt`), the day of the oldest finished session; running rows are left out. Core test with a zone change (Ho Chi Minh City to London). |
| New ViewModel parameter `observeMonthOutcomes`, `UiModule` change | n/a | **No `UiModule` change.** The ViewModel gains `computeContext` (default `Dispatchers.Default`) so the month is mapped off the main thread, like 6.2's snapshot. |
| `calendarOf(month: MonthOutcomes)` | n/a | `calendarOf(snapshot, shown)`; `progressStateOf(snapshot, selection, calendar = calendarOf(snapshot, null))`. 6.5's `thisMonth` is gone. |

**Other deviations.**
- The shown month is kept inside its limits in `calendarOf`: never after today's month (clock set back) and never before
  the oldest session's month; with no history it is today's month ("Delete all data" while a past month is shown). When
  the shown month becomes today's month again, the ViewModel stores `null`, so the calendar follows today again.
- Days after today are left out of `CalendarMonth.days` by the mapping, and `DayCell` also ignores a future day it is
  given (preview states), so it shows no marker and is no button.
- TalkBack: a day after today reads "{weekday} {day}" only (not "no alarm": an alarm may still ring), and today adds
  ", today" as the ring does. The month title is a polite live region, so paging reads the new month. The chip also has
  the "open day detail" click label.
- A calendar chip survives a new snapshot only while its date is a day of the shown month with a session; its outcome
  follows the day's (like the ring's).
- "Try again" after a failure keeps the month that was shown.
- `CalendarContrastTest` checks the pairs on `glass+gradient-top` (the table's convention for every glass card), not
  "glass on bg". Every pair has a passing Light and Dark row; no row was added.
- Screenshots: 4 calendar states, not 6. A fallback day and a two-session day look like any other day of their outcome
  (TalkBack only), so they are in `progress_calendar_every_outcome` and checked in the semantics test. The card alone is
  captured through a new `PROGRESS_CALENDAR_TAG` test tag. The "today" state is the F9 sample's March.
- 6.5 baselines re-recorded because their samples now carry calendar markers: `progress_typical_f9`,
  `progress_two_currencies`, `progress_dot_chip`, `progress_streak_over_30` and also `progress_test_only` (Test and
  Skipped dots), each Light, Dark and Light 200%.
- ViewModel test 5 (a `CommitmentEvent` leaves no day): the ViewModel has no commitment-event input at all, so it cannot
  mark one. The test that stands in for it checks that only finished sessions mark a day.
- The 200% clipping check: 6.5's `didOverflowWidth` workaround in `ProgressSemanticsTest` is replaced by a shared
  `cutOffTexts()` (`TextFit.kt`). The semantics layout result re-lays the paragraph out at the room's full width and
  reports the text's own narrower width beside it, so `didOverflowWidth` is true for any text with room to spare. The
  check now compares the glyphs' width with the text's width and their edges with the paragraph's, plus ellipsis, height
  and the last line shown; it fails when there is no text at all. A self-test proves it catches four kinds of cut text
  and passes a centred day number.
- The route test's second tap only proves Progress stays with its chip: `onOpenDay` is a no-op until 6.7.

## Review Triage Log

One combined reviewer (adversarial, edge cases, spec compliance), read only, 2026-10-26: 1 HIGH, 2 MEDIUM, 6 LOW.

| ID | Finding | Decision |
|---|---|---|
| H1 | The route test's second tap matched both the day and the chip (same TalkBack words for a one-session day) | Fixed: the matcher leaves out the chip's text |
| M1 | Five test lines over 140 characters | Fixed: wrapped |
| M2 | `cutOffTexts()` could miss a centred line pushed past the text's width; no guard against checking nothing | Partly: the guard is added (fails with no text) and a centred too-long case joins the self-test. The suggested bound (`right <= size.width`) is rejected: the semantics result measures line edges in the wider re-laid paragraph (a centred "day streak" sits at 18..156 px while the text is 137 px), so that bound flags every centred text that fits. Glyph width against text width is the sound check. |
| L1 | The stored month is never reset to `null` or kept inside the oldest month | Fixed: `calendarOf` keeps it in [oldest, today's month] (today's month without history); the ViewModel stores `null` once it is today's month. New mapping and ViewModel tests |
| L2 | A future day read "no alarm"; today did not say "today" | Fixed: date only after today; ", today" on today. Semantics test |
| L3 | The chip had no click label | Fixed: "open day detail" (shared `DayChip`, so the ring's chip too) |
| L4 | A month change was not announced | Fixed: the title is a polite live region |
| L5 | `calendarOf` ran on the main thread | Fixed: `flowOn(computeContext)`, `Dispatchers.Default` in the app, the test dispatcher in tests |
| L6 | No AC9 test with a `CommitmentEvent`; no reduced-motion test of the month slide | Accepted: the ViewModel never reads commitment events (see the change log); the reduced-motion branch is one `if` with no observable state in Robolectric beyond the final frame |
