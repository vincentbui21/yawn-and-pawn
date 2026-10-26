---
title: 'Story 6.5: Progress screen with the 30-morning ring, stat tiles and money'
type: 'feature'
created: '2026-10-26'
status: 'ready-for-dev'
baseline_commit: '0d85be9e95e8072ac9762db78b628814435f55d7'
route: 'dispatch'
story_key: '6-5-progress-screen-with-the-30-morning-ring-stat-tiles-and-money'
old_story_key: '6-5-progress-screen-with-stat-tiles-snoozes-chart-and-money'
review_loop_iteration: 0
lane: 'Lane 1 (stats and Progress): 6.5, then 6.6, then 6.7 on the same ViewModel and nav files'
depends_on:
  - '6.1 merged (core.stats WakeStats.kt: wholeMinutes, WakeStatsRule)'
  - '6.2 merged (ObserveProgress, ProgressSnapshot, DaySummary, moneyPaid, insight)'
  - 'design/preview-epic-6 merged (ProgressUiState.paidThisMonth as List<Money>, ring state for a streak over 30, reduced Day detail preview)'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-6-context.md'
  - '{project-root}/.claude/skills/pps-design/SKILL.md'
  - '{project-root}/_bmad-output/planning-artifacts/epics.md (Story 6.5)'
  - '{project-root}/docs/design-preview/feedback.md (round 2 items 21, 23, 24)'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-6-1-streaks-on-time-rate-and-time-to-up-in-core-stats.md (in the 6.1 worktree until merged)'
---

<frozen-after-approval reason="human-owned intent: do not modify unless the human renegotiates">

## Goal

The Progress tab stops being a placeholder. A `ProgressViewModel` collects 6.2's `ObserveProgress` and feeds the
existing, approved `ProgressScreen` (round 2 items 21, 23, 24): the ring of the last 30 mornings with the streak in the
centre, three stat tiles (on time over 30 days, average time to get up, snoozes over 30 days), the streak card, the
calendar (filled by 6.6), and "Money paid" this month (one line per currency) beside the Insight card, with the
Purchase history link. Every number comes from `core.stats`; the UI computes nothing (AD-18, AD-8).

## Acceptance criteria

1. **Layout.** Tapping "Progress" in the nav capsule shows, top to bottom, with no heading, no period tabs, no chart
   and no legend: the ring, the three tiles (2 + 1 at 200%), `card-streak` ("Current streak", "Best streak",
   "Keep it going." while the streak is over 0), the calendar card (this month; markers come in 6.6), then "Money paid"
   beside Insight (stacked at 200%, or "Money paid" full width when Insight is hidden).
2. **Ring.** 30 dots, oldest at the top and clockwise to today (accent pill). Each dot is the 6.2 `DaySummary` outcome
   of that date; a day with no session is the faint dot. The centre shows the streak, "/ 30" and "day streak"; when the
   streak is over 30 the "/ 30" is hidden (Q2 default, from the preview's ring state).
3. **Chip.** A first tap on a dot with a session shows its chip ("Tue 23 · Snoozed"). A second tap on the same dot, or
   a tap on the chip, emits `ProgressIntent.DayTapped(date)`, which `ProgressRoute` hands to `onOpenDay`. In 6.5,
   `AppNavHost` passes a no-op; 6.7 wires it to `Route.DayDetail`.
4. **Entry motion.** Cards fade and rise in sequence, the dots sweep in and the streak counts up. With reduced motion
   the final state shows at once (the existing `ProgressMotion`, unchanged).
5. **Tiles.**
   - The labels are "on time", "to get up" and "snoozes".
   - The values are the 30-day on-time rate (locale-formatted percent, "86%" in en-US), the average time to get up
     ("{n} min", or "Under 1 min" when `wholeMinutes` is 0) and the snoozes over 30 days.
   - A `null` value shows "No mornings yet" in `body`, never "0%", "0 min" or a dash.
   - TalkBack reads each tile as one stop: "{label}, {value}" (for example "on time, 86%").
6. **Money.**
   - The card reads "Money paid", then one line per currency from `moneyPaid(ThisMonth)` (sorted by ISO code,
     formatted only by `formatMoney`, in `text`), then "This month", then the "Purchase history" link.
   - Nothing paid shows "Nothing paid" instead of any amount.
   - An estimated price (`!priceSource.isAmountPaid`) and a stranded record never count (6.2 rule, asserted here).
7. **Purchase history.** The link pushes `Route.PurchaseHistory` over Progress (`openPurchaseHistory(from =
   Route.Progress)`). The nav capsule hides, and Back returns to Progress.
8. **Empty** (no history row with an outcome). The screen shows the empty ring with "Your first morning shows up
   here.", the calendar and the "Purchase history" text button. There are no tiles, no streak card and no money card.
9. **Test or Skipped only.** The streaks show 0, the rate and average tiles show "No mornings yet", and the snoozes tile
   shows 0.
10. **Loading.** Until the first snapshot, nothing shows for 300 ms. After that, static `skeleton` blocks show (no
    shimmer ever). After 3 s, the text "Still loading your progress." replaces them. The skeleton is one TalkBack stop,
    "Loading your progress", as a polite live region.
11. **Load failure.** A failing snapshot flow is logged and shows "Couldn't load your progress." with "Try again"
    (`ProgressIntent.RetryLoad` resubscribes). It never shows the empty state.
12. **Live.** A new history row or purchase record, local midnight or a zone change re-renders the screen (6.2's
    `ObserveProgress` emits). A chip whose day left the ring is cleared.
13. **Session.** With a session active, Progress is unreachable: `Route.SessionInProgress` replaces the stack and the
    capsule hides (existing Story 2.6 behaviour; flow test).
14. **Tests and checks.**
    - Roborazzi covers empty, Test-only, the F9 typical month (streak 12, best 12, 86%, 3 min, varied ring), two
      currencies, a tapped dot with its chip, a streak over 30, the skeleton, the slow text and the load failure. Each
      in Light and Dark, plus Light at 200% with nothing clipped.
    - Semantics: every target is at least 48 dp, every tile reads "{label}, {value}", and the empty and Test-only states
      read correctly.
    - The pps-design Done checklist is ticked below. All strings are resources, key strings match EXPERIENCE.md, and
      `CopyRulesTest` passes.
15. **Stale names cleaned.**
    - The sprint-status key is renamed to `6-5-progress-screen-with-the-30-morning-ring-stat-tiles-and-money`.
    - In `docs/design-preview/states.md`, `progress-full` reads "Ring of 30 mornings, tiles, streak, calendar, money,
      insight" and `tap-progress` reads "Tap through Progress: ring, calendar, day detail, purchase history". Both are
      regenerated from the preview catalog, so `PreviewMenuTest` passes.

</frozen-after-approval>

## Before coding: check the 6.2 contract

No 6.2 spec existed when this was written. Read 6.2's merged `core/.../stats/` code first. Use its names, and fill
only the gaps listed below. Each gap goes into `core.stats` as a pure addition next to 6.2's code, covered by 6.2's
Kover rule and its 50 ms performance test.

| 6.5 needs | Expected from 6.2 | If 6.2 does not have it |
|---|---|---|
| A flow of snapshots | `ObserveProgress` (`operator fun invoke(): Flow<ProgressSnapshot>`), bound in Koin by 6.2 | **Blocker:** stop and report |
| Today's date | `ProgressSnapshot.today: LocalDate` | Add it: the `DayTicker` already knows it |
| Streaks | `currentStreak: Int`, `bestStreak: Int` | n/a (AC of 6.2) |
| Rate over 30 days | `onTimeRate30: Int?` (and `onTimeRate7`) | n/a |
| Average | `averageTimeToUp: Duration?` | n/a; the UI maps it through 6.1's `wholeMinutes` |
| Snoozes | `snoozesLast30Days: Int` | n/a |
| Insight | `insight: Insight?` (weekdays or weekends; Q3 rule inside 6.2) | n/a |
| Money this month | `moneyThisMonth: List<Money>` (amount-paid only) | n/a |
| Has history | `hasHistory: Boolean` (any row with a non-null outcome) | n/a |
| Ring days | `lastMornings: List<DaySummary>`: 30 entries, oldest first, ending today | **Add:** `lastMornings(history, records, today, zone) = (RING_DAYS-1 downTo 0).map { dayOutcome(history, records, today.minus(it, DAY), zone) }`, stored in the snapshot |

The "Expected from 6.2" names are guesses. If 6.2 named them differently, keep 6.2's names and change only this
spec's mapping code.

## Code map

- `composeApp/src/commonMain/kotlin/com/yawnandpawn/app/ui/progress/ProgressContract.kt`: `ProgressUiState`,
  `ProgressStats`, `RingDay`, `CalendarMonth`, `DaySelection`, `Insight`, `ProgressIntent`, `RING_DAYS`. The preview
  agent changes `paidThisMonth` to a list.
- `.../ui/progress/ProgressScreen.kt`: `StatTiles`, `TileRow`, `Tile`, `StatTile` (also used by Day detail),
  `StreakCard`, `MoneyAndInsight`, `MoneyCard`, `InsightCard`, `showPurchaseHistory`.
- `.../ui/progress/ProgressRing.kt` (`HeroRing`, `RingCentre`, `tapIntent`, `dayDescription`),
  `ProgressMotion.kt` (`rememberEntered`, `entrance`, `DayChip`), `ProgressCharts.kt` (calendar; 6.6),
  `OutcomeMarker.kt`.
- `.../ui/nav/AppNavHost.kt`: `ProgressTab()` and `emptyProgress(today)`, which this story replaces.
  `.../ui/nav/YouNavigation.kt`: `openPurchaseHistory(from)` already accepts any tab on top. `.../ui/nav/YouEntries.kt`
  is the pattern for entry composables.
- `.../ui/purchases/PurchaseHistoryViewModel.kt`: the pattern for a load flow (`loads` counter, `flatMapLatest`,
  `onStart { loading }`, `catch`, `stateIn(WhileSubscribed(5_000))`). `PurchaseHistoryScreen.kt`: the
  `DelayedSkeleton` pattern and `SKELETON_DELAY_MILLIS`.
- `.../ui/format/Money.kt`: `formatMoney(Money)` and `formatMoney(List<Money>)`. `.../ui/format/TimeFormat.kt` (expect)
  and `androidMain/.../TimeFormat.android.kt`.
- `.../ui/UiModule.kt`: ViewModel bindings. `UiModuleTest`.
- `androidApp/src/debug/.../preview/PreviewRound2.kt` (line 108, `progress_full` title) and `PreviewApp.kt` (line 86,
  tap-progress title): the stale "chart" names. `docs/design-preview/states.md` is generated from them.
- `_bmad-output/implementation-artifacts/sprint-status.yaml`, line 138: the stale key.
- `_bmad-output/implementation-artifacts/deferred-work.md`: the Story 1.9 tab-state item (status update).

## Files to add or change (with signatures)

**`ui/progress/ProgressContract.kt`** (change):

```kotlin
data class ProgressUiState(
    val today: LocalDate? = null,
    val ring: List<RingDay> = emptyList(),
    val stats: ProgressStats? = null,
    val calendar: CalendarMonth? = null,
    val paidThisMonth: List<Money> = emptyList(),   // preview agent; keep its name if it differs
    val insight: Insight? = null,
    val selection: DaySelection? = null,
    val loading: Boolean = false,                   // new: no snapshot yet
    val loadFailed: Boolean = false,                // new: the snapshot flow failed
) { val isEmpty: Boolean get() = stats == null }

sealed interface ProgressIntent {
    // existing: PreviousMonth, NextMonth, DaySelected, DayTapped, PurchaseHistoryClicked
    data object RetryLoad : ProgressIntent          // new
}
```

**`ui/progress/ProgressMapping.kt`** (new, pure, commonMain):

```kotlin
internal fun progressStateOf(snapshot: ProgressSnapshot, selection: DaySelection?): ProgressUiState
internal fun DayOutcome.toUi(): Outcome?            // None -> null; 6.2's enum name may differ
internal fun Insight6_2.toUi(): Insight             // core insight -> ui Insight
internal fun keptSelection(selection: DaySelection?, ring: List<RingDay>, calendar: CalendarMonth?): DaySelection?
```

- `stats` is `null` when `!snapshot.hasHistory`. Otherwise it is
  `ProgressStats(currentStreak, bestStreak, onTimeRate30, averageTimeToUp?.let(::wholeMinutes), snoozesLast30Days)`.
- `ring` is `lastMornings.map { RingDay(it.date, it.outcome.toUi(), it.fallbackUsed) }`.
- `calendar` stays as in 6.5: `CalendarMonth(first of today's month, days = emptyList(), today, hasPrevious = false,
  hasNext = false)`. 6.6 replaces it.
- `keptSelection` drops a ring selection whose date is no longer a ring day with an outcome. A calendar selection is
  kept as is (6.6 refines it).

**`ui/progress/ProgressViewModel.kt`** (new):

```kotlin
class ProgressViewModel(
    private val observeProgress: ObserveProgress,
    private val logger: Logger,
) : ViewModel() {
    val state: StateFlow<ProgressUiState>
    fun onIntent(intent: ProgressIntent)
}
```

- Internals: `loads: MutableStateFlow<Int>` and `selection: MutableStateFlow<DaySelection?>`.
- `state = loads.flatMapLatest { combine(observeProgress(), selection) { s, sel -> progressStateOf(s, sel) }
  .onStart { emit(ProgressUiState(loading = true)) }.catch { log; emit(ProgressUiState(loadFailed = true)) } }
  .stateIn(viewModelScope, WhileSubscribed(5_000), ProgressUiState(loading = true))`.
- When a snapshot drops the selection (`keptSelection`), write the kept value back to `selection`, so a stale chip
  never comes back.
- Intents:
  - `DaySelected(sel)` sets `selection`.
  - `DayTapped` keeps `selection`, so the chip is still there after Back from Day detail. Navigation happens in the
    route.
  - `PurchaseHistoryClicked` is not handled here; navigation happens in the route.
  - `RetryLoad` runs `loads.update { it + 1 }`.
  - `PreviousMonth` and `NextMonth` are ignored in 6.5 (6.6 handles them).
- Read only: no `SessionLockGuard` (nothing is written).

**`ui/progress/ProgressRoute.kt`** (new):

```kotlin
@Composable
fun ProgressRoute(
    onOpenDay: (LocalDate) -> Unit,
    onOpenPurchaseHistory: () -> Unit,
    viewModel: ProgressViewModel = koinViewModel(),
)
```

The route forwards every intent to the ViewModel, and also calls `onOpenDay(date)` on `DayTapped` and
`onOpenPurchaseHistory()` on `PurchaseHistoryClicked`.

**`ui/progress/ProgressScreen.kt`** (change):
- `loading` shows `DelayedSkeleton` (below).
- `loadFailed` shows the failure card: `progress_load_failed` plus a "Try again" button (`home_try_again`), which sends
  `RetryLoad`.
- `StatTile`:
  - A `null` value shows `progress_no_mornings` in `typography.body`.
  - The value gets `fontFeatureSettings = TABULAR_FIGURES`.
  - The tile's semantics become `clearAndSetSemantics { contentDescription = "$label, $value" }`.
- The percent goes through `formatPercent`.
- `MoneyCard`:
  - It takes `paid: List<Money>`: one `Text(formatMoney(it))` line per currency, or `progress_nothing_paid` when the
    list is empty.
  - The merged TalkBack text reads "Money paid, {amounts}, This month".
- Remove the `showPurchaseHistory` parameter (production now always shows the link), or keep it with a default of
  `true`, whichever leaves the preview code unchanged. Do not redraw anything else.

**`ui/progress/ProgressRing.kt`**: in `RingCentre`, hide "/ 30" when `streak > RING_DAYS`. This is the Q2 default; the
preview agent may already have done it, in which case only verify it.

**`ui/components/DelayedSkeleton.kt`** (new, shared with 6.7):

```kotlin
/** EXPERIENCE.md Loading: nothing for SKELETON_DELAY_MILLIS, then static blocks, then [slowText] after SLOW_LOADING_MILLIS. */
@Composable
fun DelayedSkeleton(
    loadingLabel: String,
    slowText: String,
    modifier: Modifier = Modifier,
    blocks: @Composable ColumnScope.() -> Unit,
)
@Composable fun SkeletonBlock(modifier: Modifier = Modifier)   // surface-variant, shapes.sm
const val SLOW_LOADING_MILLIS: Long = 3_000L
const val PROGRESS_SKELETON_TAG: String = "progress-skeleton"
```

Reuse `SKELETON_DELAY_MILLIS` (300 ms). Leave Purchase history's private skeleton alone (no churn).

**`ui/format/NumberFormat.kt`** (new, expect) and **`androidMain/.../ui/format/NumberFormat.android.kt`** (actual):

```kotlin
expect fun formatPercent(percent: Int): String   // android: NumberFormat.getPercentInstance(Locale.getDefault()) of percent / 100.0
```

Delete `progress_percent` from `strings.xml` when nothing uses it any more.

**`ui/nav/ProgressEntries.kt`** (new; this keeps `AppNavHost.kt` edits to one line):

```kotlin
@Composable
internal fun ProgressEntry(backStack: MutableList<NavKey>) =
    ProgressRoute(
        onOpenDay = { },   // Story 6.7: backStack.openDayDetail(it)
        onOpenPurchaseHistory = { backStack.openPurchaseHistory(from = Route.Progress) },
    )
```

**`ui/nav/AppNavHost.kt`**:
- `entry<Route.Progress>(metadata = TabMetadata) { ProgressEntry(backStack) }`.
- Delete `ProgressTab()`, `emptyProgress()` and their now-unused imports (`Clock`, `TimeZoneProvider`, `CalendarMonth`,
  `ProgressScreen`, `ProgressUiState`, `LocalDate`, `toLocalDateTime`, if unused elsewhere).
- Update the doc comment.

**`ui/UiModule.kt`**: `viewModel { ProgressViewModel(observeProgress = get(), logger = get()) }`. `ObserveProgress`
is bound by 6.2. Update the KDoc's port list.

**`composeResources/values/strings.xml`**: add these (copy-rule compliant). EXPERIENCE.md has no key string for them,
so mark each `(EXPERIENCE.md Key strings)` in the story and list them for the v2 wording pass (owner rule: small wording
is not signed off now):

```xml
<string name="progress_no_mornings">No mornings yet</string>         <!-- AC wording, FR-PRG-2 -->
<string name="progress_nothing_paid">Nothing paid</string>           <!-- AC wording, FR-PRG-2 -->
<string name="progress_loading_a11y">Loading your progress</string>
<string name="progress_loading_slow">Still loading your progress.</string>
<string name="progress_load_failed">Couldn't load your progress.</string>
```

Skip any of these that the preview agent already added (keep its key).

**Housekeeping:**
- `sprint-status.yaml`: rename the 6.5 key (keep its status and comment).
- `PreviewRound2.kt`: set the `progress_full` title to "Ring of 30 mornings, tiles, streak, calendar, money, insight".
- `PreviewApp.kt`: set the tap-progress title to "Tap through Progress: ring, calendar, day detail, purchase history".
- Regenerate `docs/design-preview/states.md` with `PREVIEW_WRITE_STATES=true ... --tests "*PreviewMenuTest"`.
- `deferred-work.md`: set the Story 1.9 tab-state item to `status: deferred to v2 by Story 6.5 (see Defaults D1)`.

## ViewModel state and intents (summary)

| State | When | Screen |
|---|---|---|
| `loading = true` | before the first snapshot, and after `RetryLoad` | skeleton after 300 ms, text after 3 s |
| `loadFailed = true` | the snapshot flow threw | failure text and "Try again" |
| `stats == null` | `!hasHistory` | empty ring, calendar, Purchase history link |
| `stats != null` | history with outcomes | ring, tiles, streak card, calendar, money and insight |
| `selection != null` | after `DaySelected` | chip under the ring (or under the calendar in 6.6) |

| Intent | Effect |
|---|---|
| `DaySelected(sel)` | `selection = sel` |
| `DayTapped(date)` | route calls `onOpenDay(date)`; selection kept |
| `PurchaseHistoryClicked` | route calls `onOpenPurchaseHistory()` |
| `RetryLoad` | resubscribe |
| `PreviousMonth`, `NextMonth` | ignored until 6.6 |

## Tests

**ViewModel** (`composeApp/src/commonTest/.../ui/progress/ProgressViewModelTest.kt`):
- Setup: Turbine, with the real 6.2 `ObserveProgress` over `FakeSessionHistoryRepository`,
  `FakePurchaseRecordRepository`, `FakeClock`, `FakeTimeZoneProvider` and `aSessionHistoryRow` / `aPurchaseRecord`
  from `:testing`. If 6.2 made `ObserveProgress` an interface, a fake is fine for the failure cases.
- Cases:
  1. Loading is first, then the empty state: `stats == null`, `ring` has 30 entries all `null`, the calendar shows this
     month, `paidThisMonth` is empty.
  2. Test and Skipped only: streaks 0, `onTime30Days == null`, `averageMinutesToUp == null`, `snoozes30Days == 0`.
  3. F9 month: current 12, best 12, 86, 3, and the ring outcomes in date order with today last.
  4. EUR 2.99 + USD 1.00 + EUR 5.99 this month gives `[EUR 8.98, USD 1.00]`.
  5. A `PriceSource.Snapshot` record and a `Stranded` record are left out of `paidThisMonth`.
  6. An average under 30 s gives `averageMinutesToUp == 0`.
  7. A streak of 31 gives `currentStreak == 31`.
  8. Insight `null` or weekdays maps to the right `Insight`.
  9. `DaySelected` then `DayTapped` keeps the selection. Selecting another day replaces it.
  10. `FakeClock` crosses midnight: the ring shifts and a selection of a date now outside the ring is cleared.
  11. Upserting a history row or a purchase record emits a new state.
  12. The flow throws: `loadFailed`, logged once. `RetryLoad` gives loading, then the data.

**Mapping** (`ProgressMappingTest.kt`): `DayOutcome` to `Outcome` for all six values, `wholeMinutes` boundaries
(29 s, 30 s, 2:29, 2:30), `hasHistory` false gives `stats == null`.

**Nav** (`composeApp/src/commonTest/.../ui/nav/TabNavigationTest.kt`):
- Add: "Purchase history is pushed over Progress once, tabs and plus wait under it, and Back returns to Progress".
- Delete `production Progress is this month's empty calendar with no other months` (`emptyProgress` is gone). Its
  calendar check moves to `ProgressMappingTest`.

**Koin** (`UiModuleTest`): the module builds `ProgressViewModel` (bind a fake or the real `ObserveProgress` over the
fakes).

**Screenshots** (Roborazzi, `androidApp/src/test/kotlin/com/yawnandpawn/app/ui/ProgressScreenshotTest.kt` and
`ProgressSamples.kt`, 411x891 dp like `PurchaseHistoryScreenshotTest`):
- States: `empty`, `test_only`, `typical_f9`, `two_currencies`, `dot_chip`, `streak_over_30`, `skeleton` (main clock
  advanced 300 ms), `loading_slow` (3 s), `load_failed`.
- Each in Light and Dark, plus Light at 200%. Use reduced motion, or advance the clock past the entry animation, so
  the baselines are stable.

**Semantics** (`androidApp/.../ui/ProgressSemanticsTest.kt`, using `TalkBackRules`):
- Tile labels read "on time, 86%", "to get up, 3 min", "snoozes, 4" and "on time, No mornings yet".
- Money reads "Money paid, €8.98, $1.00, This month" (built from `formatMoney`, not hard-coded symbols).
- The "Purchase history" link, the chip and the month buttons are at least 48 dp.
- The ring centre reads the final streak, never a count-up value.
- The empty prompt is read.
- At 200%, no node is clipped. Reuse the `MoneyLayoutFontScaleTest` approach.

**Preview baselines to re-record:**
- Re-record `androidApp/src/test/screenshots/preview/progress_*` (Light, Dark, 200%) if `StatTile` or `MoneyCard`
  pixels change. Tabular figures and "Nothing paid" can change them.
- `day_*` baselines change only if the tile pixels change.
- Run `PreviewScreenshotTest` and `PreviewSemanticsTest`. `Round2TapThroughTest` must still pass.

**Flow via the app nav** (`androidApp/src/test/kotlin/com/yawnandpawn/app/ui/ProgressRouteTest.kt`, Robolectric with
`MainActivity`; `restartKoin(app, module { single<SessionHistoryRepository> { fake }; single<PurchaseRecordRepository>
{ fake } })` like `ReliabilityRouteTest`):
1. Fresh install: tap "Progress" and see "Your first morning shows up here." and the "Purchase history" button.
2. Seeded F9 month: the tiles show "86%", "3 min" and the snooze count, and the money lines show.
3. Tap "Purchase history": the Purchase history title shows and the capsule is hidden. Back returns to Progress with
   the tiles.
4. Start a session (`SessionEngine` dispatch like `YouRouteTest`): "Alarm in progress" shows and the "Progress" tab is
   gone.

`MainActivityTest` line 308 ("Your first morning shows up here.") must still pass.

## Scan tests and rules to respect

- `CopyRulesTest` (composeApp androidHostTest): no em dash, titles of 8 words or fewer, bodies of 25 words or fewer,
  no hype words, no currency symbol in strings.
- `MoneyFormattingScanTest`: money only through `formatMoney` / `moneyFormatter`. No `NumberFormat.getCurrencyInstance`,
  no `DecimalFormat`, no symbols in code. `NumberFormat.getPercentInstance` is allowed; keep it in the androidMain
  actual.
- `PurchaseStateReaderScanTest` (data): never name `purchaseState`, `PurchaseState` or `PlayPurchaseState` in shipped
  sources (not even as a local variable). Use `status` / `RecordStatus`.
- `SessionHistoryWriterScanTest` and `PurchaseLedgerWriterScanTest`: the ViewModel and the debug samples only read.
  No `upsert`, `recordMerge` or `putRecord` outside the recorder and the ledger. Tests may write to the fakes.
- detekt custom rules: `NoRawColor`, `NoRawSp`, `NoRawCornerRadius` (tokens only), `NoDirectTimeAccess` (no
  `Clock.System` / `now()` in UI; today comes from the snapshot), `NoFloatingPointMoney` (percent / 100.0 is not money,
  but never touch micros as Double), `NoUnseededRandom` (samples use fixed data).
- `PreviewMenuTest`: `states.md` must match the catalog after the title changes.
- `ContrastTest`: no new colour pair (failure and skeleton use existing `text`, `text-secondary` and `surface-variant`
  on glass).

## Defaults taken (fast mode; the owner can change them)

- **D1, tab state (Story 1.9 deferred item): not cheap, so it is deferred to v2.** Keeping Progress' month and scroll
  across tab switches needs tab entries to stay on the back stack. That changes `selectTab` rules and every nav test.
  Within the Progress stack (Purchase history and Day detail pushed over it) the scroll, month and chip are kept,
  because the entry stays.
- **D2, Q2:** for a streak over 30, the centre shows the number and "day streak", and hides "/ 30".
- **D3, Q3:** Insight comes from 6.2 (weekday versus weekend average, at least 3 sessions each, a gap of 2 minutes or
  more). When it is `null` the card is hidden and "Money paid" takes the full width (existing layout).
- **D4:** estimated prices and stranded records never count in money (6.2 rule, asserted by ViewModel test 5).
- **D5, tile number style:** the owner-approved preview (round 2, item 23) uses `title` for tile numbers. The AC says
  `display`. **Keep `title`** (approved pixels) and add tabular figures. Flagged in the report.
- **D6:** the percent is locale-formatted through `formatPercent` (AC "locale-formatted").
- **D7:** five new strings, listed above, for the v2 wording pass.
- **D8:** a load failure state with "Try again" is added. The empty state would wrongly say no mornings exist.
- **D9:** the chip is kept after returning from Day detail, and cleared when its day leaves the ring.
- **D10:** in 6.5 the chip's open action is a no-op. 6.7 wires it in the same lane. The calendar stays this month's
  empty grid until 6.6.

## Risks

- **6.2 contract mismatch:** names or a missing `lastMornings` field. The table above says what to do. Only a missing
  `ObserveProgress` is a blocker.
- **Preview agent collisions:** `ProgressContract.kt`, `ProgressScreen.kt`, `ProgressRing.kt`, `PreviewRound2.kt`,
  `PreviewApp.kt`, `states.md` and the `preview/progress_*` baselines. Rebase on `design/preview-epic-6` first. Keep
  its names. Do not redo a change it already made (stale titles, "/ 30" rule, list money).
- **Release timing:** a release between 6.5 and 6.7 ships a chip that opens nothing and an empty calendar. Epic Q5
  plans a release after 6.3, 6.4 and 6.5. **Recommend moving that release to after 6.7** (same lane, auto).
- **Screenshot stability:** the entry animation and count-up. Capture with reduced motion, or advance the main clock
  past about 1.5 s.
- **Skeleton timing in tests:** fakes emit at once, so the skeleton baselines need a never-emitting
  `ObserveProgress` fake.
- **Hot files:** `AppNavHost.kt`, `UiModule.kt`, `strings.xml`. Lane 2 (6.3 or 6.8) may touch the same files. Rebase
  before review.

## pps-design Done checklist

- [ ] Only tokens from `DESIGN.md` used (no raw hex, no new radii, no new font sizes).
- [ ] Light, Dark (and Sunrise where relevant) checked with previews / screenshots.
- [ ] Every colour pair used is in the `DESIGN.md` contrast table.
- [ ] Touch targets ≥ 48 dp; wake actions ≥ 64 dp.
- [ ] Works at 200% font scale and with TalkBack; outcome glyphs present.
- [ ] Reduced-motion path works.
- [ ] Copy matches `EXPERIENCE.md > Voice and Tone` (no em dashes, no filler, strings in resources).
- [ ] Every state row in `EXPERIENCE.md > State Patterns` for this surface is handled.
- [ ] "I'm up" is the most prominent wake action; snooze is visible, plain and priced. (Not applicable.)
- [ ] Compose `@Preview`s for each state (light/dark/Sunrise, empty/error) exist; screenshot tests updated (Roborazzi).

## Verification

Use JDK 17 and `--no-daemon --max-workers=2`, targeted runs only.

- `:composeApp:testAndroidHostTest --tests "*progress*" --tests "*nav*" --tests "*UiModuleTest*" --tests "*CopyRulesTest*" --tests "*MoneyFormattingScanTest*"`
- `:data:testAndroidHostTest --tests "*ScanTest*"`
- `:androidApp:recordRoborazziDebug` (new and changed baselines, checked by eye), then
  `:androidApp:testDebugUnitTest --tests "*Progress*" --tests "*Preview*" --tests "*MainActivityTest*" --tests "*Round2TapThroughTest*"`
- `spotlessCheck detekt`

## Spec Change Log

**6.2 contract as built (real names win).** Checked against `core/.../stats/ObserveProgress.kt`, `ProgressStats.kt` and
`MoneyStats.kt` at 6.2's HEAD 60ff6372.

| Spec guess | 6.2 as built | What 6.5 did |
|---|---|---|
| `ObserveProgress` `invoke(): Flow<ProgressSnapshot>` | a class, `invoke(refresh: Flow<Unit> = emptyFlow())`, bound as a Koin `factory` | used as is (no refresh signal; the `DayTicker` already ticks every minute) |
| `today` | `today: LocalDate` (and `zone`) | used |
| `onTimeRate30` | `onTimeRate30Days` (and `onTimeRate7Days`) | mapped |
| `insight: Insight?` | `insight: InsightLine?` (same two values) | `InsightLine.toUi()` |
| `moneyThisMonth` | `paidThisMonth: List<Money>` (amount paid only, by code) | mapped |
| `hasHistory` (row with an outcome) | `hasHistory` is any stored row, a running session included | the mapping checks `history.any { it.outcome != null }` itself, so a running row alone keeps the empty state; 6.2's meaning is left alone for 6.3 |
| `lastMornings` | missing; `daySummaries(history, range, zone)` exists | **added** `ProgressSnapshot.lastMornings` (`daySummaries` over the last `ProgressRule.RING_DAYS` days, new constant 30) plus a core test, so the ring is computed off the main thread with the rest of the snapshot |

**Deviations from the plan above.**
- Tests use the existing `backgroundScope.launch { state.collect }` pattern of `PurchaseHistoryViewModelTest` instead of Turbine.
- `DelayedSkeleton`'s slow text sits in a glass `GroupCard` in `text` (not bare on the gradient), so no new colour pair.
- The entry animation (`rememberEntered`) now lives in the data branch (`ProgressContent`), so it plays when the data
  first shows after loading, not on the hidden loading frame.
- `StatTile` semantics changed for Day detail's tiles too ("rings, 2" instead of "2, rings"): the same component.
- Money paid keeps one line per currency (owner decision 2026-10-26). TalkBack reads the merged lines "Money paid, €8.98,
  $1.00, This month"; no " · " separator is added anywhere.
- `MainActivityTest`'s empty-Progress check now expects the "Purchase history" button (it asserted its absence while
  the placeholder hid it).

**Strings for the v2 wording pass** (not in EXPERIENCE.md Key strings; owner rule, not blocking): `progress_no_mornings`
"No mornings yet", `progress_nothing_paid` "Nothing paid", `progress_loading_a11y` "Loading your progress",
`progress_loading_slow` "Still loading your progress.", `progress_load_failed` "Couldn't load your progress.".
`progress_percent` is deleted (the percent goes through `formatPercent`).

## Review Triage Log

## Coordinator note (2026-10-26): currency separator
Several currencies are always joined with " + ", using the existing `TOTALS_SEPARATOR` and EXPERIENCE.md "Money, mixed currencies", and sorted by currency code. For example: "€3.27 + $2.18 paid this week". Do not add a " · " variant. If a sorted helper is needed, the first story to land adds `formatPerCurrency` with " + ", and the others reuse it.

## Owner decision (2026-10-26)
The owner approved all three round 5 defaults ("OK with all"):
- money in two currencies is joined with " + ", one line per currency on Progress;
- "/ 30" is hidden once the streak passes 30;
- Day detail uses stored facts only (Q1 option A): "Snoozed · {price}" and "{check} solved".
These are final for this build.
