---
title: 'Story 6.7: Day detail for each morning'
type: 'feature'
created: '2026-10-26'
status: 'ready-for-dev'
baseline_commit: 'stack on the merged 6.6 (fill in at start)'
route: 'dispatch'
story_key: '6-7-day-detail-for-each-morning'
review_loop_iteration: 0
lane: 'Lane 1 (stats and Progress): after 6.6; owns Route.DayDetail'
depends_on:
  - '6.1 merged (wholeMinutes)'
  - '6.2 merged (sessionsOn, money rules)'
  - '6.5 and 6.6 merged (ProgressRoute.onOpenDay, ProgressEntries.kt, ProgressRouteTest, StatTile semantics, DelayedSkeleton)'
  - 'design/preview-epic-6 merged (reduced Day detail preview: stored facts only)'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-6-context.md (Q1 default)'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-6-5-progress-screen.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-6-6-calendar.md'
  - '{project-root}/.claude/skills/pps-design/SKILL.md'
  - '{project-root}/_bmad-output/planning-artifacts/epics.md (Story 6.7)'
  - '{project-root}/docs/design-preview/feedback.md (round 2 item 25)'
---

<frozen-after-approval reason="human-owned intent: do not modify unless the human renegotiates">

## Goal

Tapping a ring dot or calendar day twice, or tapping its chip, opens Day detail for that date. Day detail is a new
`Route.DayDetail(date)` pushed over Progress, and the screen is the approved `DayDetailScreen` (round 2, item 25),
reduced to what history really stores (**Q1 default: stored facts only, never an invented time**). Each session of the
day (from 6.2's `sessionsOn`, in scheduled order) gets:
- a hero: the outcome, "{time} · {label}" and the key figure;
- three tiles: rings, snoozes and paid;
- a timeline that draws in.

Merged alarms, stranded purchases, and "turned off" or "deleted" notes from `commitment_event` complete the day.

## Acceptance criteria

1. **Open and close.**
   - `DayTapped(date)` from the ring chip, the calendar chip, or a second tap on a selected dot or day pushes
     `Route.DayDetail(date)` over Progress, once (a double tap pushes one).
   - The capsule hides. Back returns to Progress with its month, scroll and chip kept.
   - The title is one line: `formatDate(date, DateStyle.WeekdayDayMonth)`, for example "Wed, Sep 23" in en-US.
2. **Sessions.** Every session of the date (day = local date of `scheduledAt`) gets its own section, ordered by
   `scheduledAt`. A 23:50 alarm that ended at 00:10 is on the earlier date.
3. **Counted session (On time, Snoozed or Missed).**
   - **Hero:**
     - the `outcome-marker` and its label;
     - the alarm time (the local time of `scheduledAt`) with " · {label}" while the alarm exists and has a label (time
       only when it was deleted or has no label);
     - the key figure: "{n} min" or "Under 1 min" plus "to get up", from `wholeMinutes(timeToCompleteMs)`. A Missed
       session shows "Stopped after 30 minutes" instead.
   - **Tiles:**
     - "rings" is 1 + `snoozeCount` (Q1 default);
     - "snoozes" is `snoozeCount`;
     - "paid" is the session's amount-paid totals, one per currency via `formatMoney` in `text`, or "No charge".
   - **Timeline (stored facts only, in time order):**
     - "Alarm rang" at `firstRingAt`, or "Rang before your first unlock" when `directBoot`;
     - one row per paid purchase record of the session (granted, consumed or reused), at its purchase time: "Snoozed ·
       {price}", or "Snoozed" when the price is only an estimate;
     - one "{time} alarm merged into this session" row per `session_merge` row, at `mergedAt`;
     - last, at `endedAt`: "{checks} solved" (the glossary names of `checkTypes`) for On time or Snoozed, or "Stopped
       after 30 minutes" for Missed.
     - The timeline draws in top to bottom; with reduced motion it shows at once.
   - **Fallback:** when `fallbackUsed`, the hero adds the `alt_route` badge and "Fallback check used".
   - **Stranded:** each stranded record linked to the session shows as a `purchase-row`-style line: "Not used, refunded
     automatically by Google", with its amount in `text-secondary` (only when it is the amount paid). TalkBack reads
     "{price} refunded". It is never in "paid".
4. **Test or Skipped session.** The hero only: the marker, "Test" or "Skipped", and the alarm time. No tiles and no
   timeline.
5. **Alarm changes.** Each `commitment_event` whose `occurrenceAt` falls on the date (local date) adds a note after the
   sessions: "Your {time} alarm was turned off. Logged." or "Your {time} alarm was deleted. Logged.", where `{time}` is
   the local time of `occurrenceAt`. No new table or migration.
6. **TalkBack.**
   - The title is a heading.
   - The hero is one stop; the outcome is read as a word ("Snoozed"), never a colour or icon name.
   - Each tile reads "{label}, {value}" (from 6.5's `StatTile`).
   - Each timeline row is one stop: "{time}, {text}".
   - A stranded line reads "Not used, refunded automatically by Google, {price} refunded".
7. **Missing facts are omitted.**
   - A null `endedAt` leaves out the end row.
   - A null `timeToCompleteMs` leaves out the key figure.
   - An unknown check id leaves out that name; with no known name the end row is left out.
   - An unreadable price leaves out the amount.
   - Nothing is ever shown as "0", "0 min" or "null".
8. **Load states.**
   - Loading: nothing for 300 ms, then the shared `DelayedSkeleton` from 6.5; after 3 s its slow text.
   - A failing history or records flow shows "Couldn't load your progress." with "Try again".
   - A failed `merges` or commitment-events read is logged and only leaves out those rows or notes.
   - A day with no session and no change closes Day detail (back to Progress).
9. **Session and restore.**
   - With a session active, `SessionInProgress` replaces the stack (Story 2.6).
   - Day detail survives a configuration change and process restore (the route holds the date, serialised).
10. **Tests and checks.**
    - Roborazzi covers an On time session, a Snoozed session with two currencies and a stranded purchase, a Missed
      session, a fallback plus Direct Boot session, a merged occurrence, a Test session, a Skipped session and a
      turned-off note. Each in Light and Dark, plus Light at 200%.
    - The pps-design checklist is ticked, all strings are resources, and `CopyRulesTest` passes.

</frozen-after-approval>

## Before coding: check the 6.2 contract and the preview model

| 6.7 needs | Expected | If missing |
|---|---|---|
| A day's sessions | 6.2 `sessionsOn(history, records, date, zone)`: sessions ordered by `scheduledAt`, each with its paid `List<Money>` (amount-paid only) and its stranded records | If it returns totals only, also filter `records` by `sessionId` in the mapper for the per-snooze rows |
| Whole minutes | 6.1 `wholeMinutes(Duration): Int` | n/a |
| All history rows | 6.2 `SessionHistoryRepository.observeAll()` | **Blocker** (6.2 AC) |
| Reduced preview model | the preview agent's `SessionDetail` / `MorningEvent` for stored facts | Apply the model changes below yourself |

If the preview agent already reshaped `SessionDetail` or `MorningEvent`, keep its names and fill in only what is
missing from the list below.

## Code map

- `composeApp/.../ui/daydetail/DayDetailScreen.kt`:
  - `MorningEvent` (Rang, Snoozed, ImUp, QuietTimeRanOut, CheckSwitched, FallbackUsed, Merged, CheckSolved, Stopped);
  - `SessionDetail` (derives rings, snoozes, paid and minutesToUp from events today);
  - `AlarmChange`, `DayDetailUiState`, `DayDetailScreen`, `HeroCard`, `SessionTiles`, `Timeline`, `TimelineRow`,
    `EventGlyph`, `eventText`.
- `.../ui/nav/Route.kt` (add the route), `TabNavigation.kt` (`Route.tab` exhaustive `when`; stack-rule comment),
  `PushedEntries.kt`, `ProgressEntries.kt` (6.5; the `onOpenDay` no-op), `YouNavigation.kt` and
  `ReliabilityNavigation.kt` (patterns for "push only from X on top").
- `.../ui/purchases/PurchaseHistoryScreen.kt`: the `purchase-row` look for a stranded line
  (`purchase_price_refunded_a11y`). `PurchaseHistoryContract.purchaseOf`: the alarm-label rules.
- `.../ui/checks/CheckTypes.kt`: `CheckType`, `displayName()`, `icon`, `core` and `CoreCheckType.toUi()` (map by
  stable id).
- `core/.../history/SessionHistory.kt`: `SessionHistoryRow`, `SessionMergeRow`, `merges(sessionId)`.
- `core/.../billing/PurchaseRecords.kt`: `PurchaseRecord`, `RecordStatus`, `PriceSource.isAmountPaid`.
- `core/.../config/ConfigPorts.kt`: `CommitmentEvent(alarmId, occurrenceAt, action: CommitmentAction { Disabled,
  Deleted }, at)` and `CommitmentEventRepository.all()`.
- `core/.../alarm/`: `AlarmRepository.observeAll()`, `Alarm.label`.
- `.../ui/UiModule.kt`: the parameterised `viewModel { params -> ... }` pattern (`alarmEditor`).
- Strings that exist: `day_detail_before_unlock`, `day_detail_turned_off`, `day_detail_deleted`, `day_detail_merged`,
  `day_event_rang`, `day_event_stopped`, `day_tile_rings`, `day_tile_paid`, `day_tile_no_charge`,
  `progress_tile_snoozes`, `progress_tile_to_get_up`, `progress_minutes`, `progress_under_minute`, `purchase_stranded`,
  `purchase_price_refunded_a11y`, `outcome_snoozed`, `alarm_card_summary`, `editor_back`, `progress_load_failed` (6.5),
  `home_try_again`.

## Files to add or change (with signatures)

**`ui/nav/Route.kt`**:

```kotlin
/**
 * Day detail of [date] (Story 6.7), pushed over Progress from a ring or calendar chip; Back returns to Progress. Not a
 * tab, so the nav capsule hides.
 */
@Serializable
data class DayDetail(val date: LocalDate) : Route
```

`LocalDate` is `@Serializable` (ISO string) in kotlinx-datetime 0.8. If the saved-state round trip fails, use
`val isoDate: String` with a `date` getter.

**`ui/nav/TabNavigation.kt`**: add `is Route.DayDetail -> null` in `Route.tab`. Update the header comment: Day detail
over Progress.

**`ui/nav/ProgressNavigation.kt`** (new):

```kotlin
/** Pushes Day detail of [date] over Progress (Story 6.7). Ignored unless Progress is on top, so a double tap or a late chip tap never stacks two. */
fun MutableList<NavKey>.openDayDetail(date: LocalDate) {
    if (lastOrNull() != Route.Progress) return
    add(Route.DayDetail(date))
}
```

**`ui/nav/ProgressEntries.kt`**: `onOpenDay = { backStack.openDayDetail(it) }`.

**`ui/nav/PushedEntries.kt`**: `entry<Route.DayDetail> { route -> DayDetailRoute(date = route.date, onBack = {
backStack.close(route) }) }`.

**`ui/daydetail/DayDetailScreen.kt`** (model changes, unless the preview agent already made them):

```kotlin
sealed interface MorningEvent {
    val time: LocalTime?        // was non-null; null only for a snooze bought before this ring (a reused payment)
    data class Rang(override val time: LocalTime, val again: Boolean = false, val beforeFirstUnlock: Boolean = false) : MorningEvent
    data class Snoozed(override val time: LocalTime?, val minutes: Int? = null, val price: Money?) : MorningEvent
    data class Merged(override val time: LocalTime, val alarmTime: LocalTime) : MorningEvent
    data class CheckSolved(override val time: LocalTime, val checks: List<CheckType>, val tries: Int? = null) : MorningEvent
    data class Stopped(override val time: LocalTime) : MorningEvent
    // ImUp, QuietTimeRanOut, CheckSwitched and FallbackUsed stay for the approved round 2 preview; production never builds them.
}

data class SessionDetail(
    val alarmTime: LocalTime,
    val alarmLabel: String?,
    val outcome: Outcome,
    val events: List<MorningEvent> = emptyList(),
    val rings: Int = 0,                         // stored: 1 + snoozeCount for a counted session
    val snoozes: Int = 0,                       // stored: snoozeCount
    val paid: List<Money> = emptyList(),        // amount-paid totals per currency
    val minutesToUp: Int? = null,               // wholeMinutes(timeToCompleteMs); null when unknown
    val fallbackUsed: Boolean = false,
    val stranded: List<Money?> = emptyList(),   // one per stranded record; null when its price is only an estimate
) { val counted: Boolean get() = outcome != Outcome.Test && outcome != Outcome.Skipped }

data class DayDetailUiState(
    val date: LocalDate,
    val sessions: List<SessionDetail> = emptyList(),
    val changes: List<AlarmChange> = emptyList(),
    val loading: Boolean = false,
    val loadFailed: Boolean = false,
    val nothingLogged: Boolean = false,         // loaded, no session and no change: the route closes the screen
)

sealed interface DayDetailIntent { data object RetryLoad : DayDetailIntent }
```

- Keep `DayDetailScreen(state, is24Hour, onBack, modifier)`. Add `onIntent: (DayDetailIntent) -> Unit = {}`.
- The preview samples move from derived to stored fields. Fill `rings`, `snoozes`, `paid` and `minutesToUp` in
  `PreviewProgressSamples`, so the round 2 pixels stay the same.

**`DayDetailScreen` rendering** (no redraw of approved parts):
- `HeroCard`:
  - When `fallbackUsed`, add a row with `FallbackBadge()` and `day_detail_fallback_used`.
  - The key figure reads `minutesToUp`.
  - Missed shows `day_event_stopped` (as now).
- `SessionTiles` reads the stored `rings`, `snoozes` and `paid`.
- `StrandedLine` (new private composable) goes after the tiles:
  - `purchase_stranded` in `body` / `text`, plus `formatMoney(price)` in `text-secondary` when the price is not `null`;
  - one merged stop with contentDescription "{stranded}, {price refunded}";
  - inside a `glass` card like the timeline.
- `TimelineRow` with `time == null` leaves the time column empty, and its TalkBack text is just the event text.
- `eventText`:
  - `Snoozed` with a price reads `day_event_snoozed_paid` ("Snoozed · {price}"), or `outcome_snoozed` when the price is
    `null`. With `minutes` set (preview only), it keeps `day_event_snoozed`.
  - `CheckSolved` reads `day_event_solved` ("{checks} solved", names joined with ", "). With `tries` set (preview only),
    it keeps the approved strings.
- `EventGlyph` for `CheckSolved` uses the first check's icon.
- Loading uses `DelayedSkeleton(loadingLabel = progress_loading_a11y, slowText = progress_loading_slow)`, with blocks
  shaped like a hero card and a tile row.
- `loadFailed` shows `progress_load_failed` with "Try again" (`RetryLoad`).

**`ui/daydetail/DayDetailMapping.kt`** (new, pure, commonMain):

```kotlin
internal fun dayDetailOf(
    date: LocalDate,
    history: List<SessionHistoryRow>,
    records: List<PurchaseRecord>,
    merges: Map<String, List<SessionMergeRow>>,   // by session id
    events: List<CommitmentEvent>,
    alarms: Map<String, Alarm>,
    zone: TimeZone,
): DayDetailUiState

internal fun sessionDetailOf(
    row: SessionHistoryRow,
    paid: List<Money>,                  // from sessionsOn
    records: List<PurchaseRecord>,      // this session's records
    merges: List<SessionMergeRow>,
    alarm: Alarm?,
    zone: TimeZone,
): SessionDetail

internal fun morningEventsOf(
    row: SessionHistoryRow,
    paidRecords: List<PurchaseRecord>,
    merges: List<SessionMergeRow>,
    zone: TimeZone,
): List<MorningEvent>

internal fun alarmChangesOn(events: List<CommitmentEvent>, date: LocalDate, zone: TimeZone): List<AlarmChange>
```

- **Sessions:** use `sessionsOn(history, records, date, zone)` (6.2) for the day, order and paid totals. Rows with a
  null outcome (still running) are left out.
- **Alarm label:** `alarms[row.alarmId]?.label?.trim()?.takeIf { it.isNotEmpty() }`. A deleted alarm or no label
  gives `null`, so the hero shows the time only.
- **Events (counted sessions only):**
  - `Rang(firstRingAt local, beforeFirstUnlock = directBoot)`.
  - One `Snoozed` per record with status Granted, Consumed or Reused, sorted by `snoozeNumber`, then `purchasedAt`:
    - `time` is the local time of `purchasedAt` when it is inside `[firstRingAt, endedAt ?: firstRingAt + 30 min]`,
      else `null`;
    - `price` is `record.price.takeIf { record.priceSource.isAmountPaid }`.
  - `Merged(mergedAt local, scheduledAt local)` per merge.
  - Sort those by instant (a null-time snooze keeps its `snoozeNumber` place after `Rang`).
  - The end row comes last, only when `endedAt != null`:
    - Missed gives `Stopped(endedAt)`.
    - Otherwise `CheckSolved(endedAt, checkTypes.mapNotNull(::checkTypeOfId))`, left out when that list is empty.
- **Stranded:** records with status `Stranded` and `sessionId == row.sessionId` give
  `price.takeIf { isAmountPaid }`.
- **Tiles:** `rings = 1 + snoozeCount` and `snoozes = snoozeCount` for counted sessions. Test and Skipped give 0, and
  their tiles are not shown.
- **Key figure:** `minutesToUp = timeToCompleteMs?.milliseconds?.let(::wholeMinutes)`, only for On time and Snoozed.
- **Changes:** `alarmChangesOn` keeps events whose `occurrenceAt` local date is `date`, ordered by `occurrenceAt`, as
  `AlarmChange(local time, deleted = action == Deleted)`.
- **Nothing logged:** `nothingLogged = sessions.isEmpty() && changes.isEmpty()`.

**`ui/checks/CheckTypes.kt`**: add `fun checkTypeOfId(id: String): CheckType? = CheckType.entries.firstOrNull { it.core?.id == id }`. The ids are `CheckType.id`, as `SessionRecorder` stores them.

**`ui/daydetail/DayDetailViewModel.kt`** (new):

```kotlin
class DayDetailViewModel(
    private val date: LocalDate,
    private val history: SessionHistoryRepository,
    private val records: PurchaseRecordRepository,
    alarms: AlarmRepository,
    private val commitmentEvents: CommitmentEventRepository,
    private val timeZoneProvider: TimeZoneProvider,
    private val logger: Logger,
) : ViewModel() {
    val state: StateFlow<DayDetailUiState>
    fun onIntent(intent: DayDetailIntent)
}
```

- Build `state` as `loads.flatMapLatest { combine(history.observeAll(), records.observeAll(), alarmsById) { ... } }`.
  Each emission:
  - reads `zone = timeZoneProvider.current()`;
  - reads `history.merges(id)` for the day's sessions and `commitmentEvents.all()` (a `Failure` is logged with
    `LogEvent.OperationFailed`, and that part is left empty);
  - maps with `dayDetailOf`.
- `.onStart { emit(DayDetailUiState(date, loading = true)) }`.
- `.catch { log; emit(DayDetailUiState(date, loadFailed = true)) }`.
- `stateIn(WhileSubscribed(5_000))`.
- `alarmsById` follows the `PurchaseHistoryViewModel` pattern (a failure is logged and gives an empty map).
- `RetryLoad` runs `loads.update { it + 1 }`.
- Read only.

**`ui/daydetail/DayDetailRoute.kt`** (new):

```kotlin
@Composable
fun DayDetailRoute(
    date: LocalDate,
    onBack: () -> Unit,
    viewModel: DayDetailViewModel = koinViewModel(key = date.toString()) { parametersOf(date) },
)
```

- `LaunchedEffect(state.nothingLogged) { if (it) onBack() }`.
- `is24Hour = is24HourClock()`.

**`ui/UiModule.kt`**: `viewModel { params -> DayDetailViewModel(params.get(), get(), get(), get(), get(), get(), get()) }`.
`CommitmentEventRepository` is bound by `:data` (Story 4.4). Check that `UiModuleTest` provides it.

**`strings.xml`** (new, for the v2 wording list; `(EXPERIENCE.md Key strings)` drafts):

```xml
<string name="day_event_snoozed_paid">Snoozed · %1$s</string>
<string name="day_event_solved">%1$s solved</string>
<string name="day_detail_fallback_used">Fallback check used</string>   <!-- AC wording -->
```

## ViewModel state and intents

| State | When | Screen |
|---|---|---|
| `loading` | before the first combined emission, and after `RetryLoad` | title, then the skeleton after 300 ms and the text after 3 s |
| `loadFailed` | the history, records or alarms flow threw | title, failure text, "Try again" |
| `sessions` and `changes` | loaded | heroes, tiles, timelines, stranded lines and notes |
| `nothingLogged` | loaded with nothing for the date | the route calls `onBack()` once |

| Intent | Effect |
|---|---|
| `RetryLoad` | resubscribe |
| Back (screen `onBack`) | `backStack.close(route)` |

## Tests

**Mapping** (`composeApp/src/commonTest/.../ui/daydetail/DayDetailMappingTest.kt`; `aSessionHistoryRow`,
`aPurchaseRecord`, fixed zone `Europe/Berlin`):
1. On time:
   - Rang 06:30 then "Math solved" at 06:33 gives `minutesToUp = 3`, rings 1, snoozes 0 and paid empty.
   - The label is shown while the alarm exists. A deleted alarm gives `alarmLabel = null`.
2. Snoozed:
   - Two snoozes, EUR 0.99 and USD 1.00, plus one stranded EUR 0.99 record.
   - Paid is `[EUR 0.99, USD 1.00]`, rings 3 and snoozes 2.
   - Two `Snoozed` rows sit at their purchase times, in order.
   - The stranded list is `[EUR 0.99]` and is not counted in paid.
3. A `PriceSource.Snapshot` snooze record gives `Snoozed(price = null)` and is not in paid.
4. A reused record bought before `firstRingAt` gives `Snoozed(time = null)`, placed after `Rang`.
5. Missed gives a `Stopped` row at `endedAt`, no `CheckSolved`, and `minutesToUp == null`.
6. Fallback plus Direct Boot gives `fallbackUsed = true` and `Rang(beforeFirstUnlock = true)`. `CheckSolved` names
   `checkTypes` (the fallback check).
7. Two merges give two `Merged` rows at `mergedAt`, with the merged occurrence's time, sorted among the snoozes.
8. Test or Skipped gives the hero data only (no events, rings 0).
9. Null `endedAt` and null `timeToCompleteMs` give no end row and `minutesToUp == null`.
10. Unknown check ids give no end row.
11. Day boundaries:
    - A 23:50 alarm that ended at 00:10 is on the earlier date, and its end row reads 00:10.
    - Two sessions are ordered by `scheduledAt`.
    - A null-outcome row is left out.
12. `CommitmentEvent`s:
    - A Disabled and a Deleted event on the date give two `AlarmChange` entries in order.
    - An event on another date is left out.
    - A day with only a change gives `sessions` empty and `nothingLogged = false`.
13. No session and no change gives `nothingLogged = true`.

**ViewModel** (`DayDetailViewModelTest`; fakes `FakeSessionHistoryRepository`, `FakePurchaseRecordRepository`,
`FakeAlarmRepository`, `FakeCommitmentEventRepository`, `FakeTimeZoneProvider`, a recording `Logger`; Turbine):
1. `loading` first, then the data.
2. Upserting a purchase record for the session emits again with the new snooze row.
3. A `merges` failure is logged and the rest is shown.
4. A `commitmentEvents.all()` failure is logged and shows no notes.
5. A failing history flow gives `loadFailed`. `RetryLoad` reloads.
6. A deleted alarm (removed from the fake) re-renders with the time only.

**Nav** (`TabNavigationTest`):
1. `openDayDetail` pushes `[Alarms, Progress, DayDetail(d)]` once. A second call changes nothing.
2. It is ignored from Alarms, Settings, You, Purchase history and the editor.
3. `close(route)` returns to Progress.
4. `selectTab` and `openEditor` are ignored while Day detail is on top (pushed screen rules).
5. `Route.DayDetail(d).tab == null`.

**SessionLockTest** adds `Route.DayDetail(d)` to the known routes, and the lock replaces it.

**Route save** (`RouteSavedStateTest`, new or added to an existing saved-state test): encode and decode
`Route.DayDetail(LocalDate(2027, 3, 28))` with `RouteSavedStateConfiguration`.

**Koin** (`UiModuleTest`): the module builds `DayDetailViewModel` with `parametersOf(LocalDate(...))`.

**Screenshots** (`androidApp/src/test/kotlin/com/yawnandpawn/app/ui/DayDetailScreenshotTest.kt` and
`DayDetailSamples.kt`, 411x891 dp):
- States: `on_time`, `snoozed_two_currencies_stranded`, `missed`, `fallback_direct_boot`, `merged`, `test`, `skipped`,
  `turned_off_note`, `loading`, `load_failed`.
- Each in Light and Dark, plus Light at 200%. Use reduced motion so the timeline is fully drawn.

**Semantics** (`DayDetailSemanticsTest`, using `TalkBackRules`):
1. The title is a heading.
2. The hero reads "Snoozed, 6:30 AM · Work, 18 min, to get up" (the order and words, not a colour or icon name).
3. Tiles read "rings, 3", "snoozes, 2" and "paid, {formatted}". With nothing paid, "paid, No charge".
4. Timeline stops read "{time}, {text}". A time-less snooze reads only its text.
5. The stranded line reads "Not used, refunded automatically by Google, {price} refunded".
6. No node text equals "0 min", "null" or "0".
7. The Back target is at least 48 dp.

**Preview baselines:** re-record `androidApp/src/test/screenshots/preview/day_*` (Light, Dark, 200%) if the model
change or the hero fallback row moves pixels. The round 2 states must look the same. The preview agent's reduced state
gets its own baseline from that agent. `PreviewMenuTest`: no new state from this story.

**Flow via the app nav** (extend `ProgressRouteTest`, or add `DayDetailRouteTest`; `restartKoin` with fakes seeded with
a Snoozed session today, EUR plus USD and a stranded record, a merge and a Disabled event on today):
1. Progress: tap today's calendar cell, the chip shows. Tap the chip: Day detail shows today's title, the capsule is
   hidden, and the texts "Snoozed · €0.99", "Not used, refunded automatically by Google" and "Your 7:00 AM alarm was
   turned off. Logged." appear. (Assert the formatter's output, not literal symbols in shipped code.)
2. Back returns to Progress with the chip still there and the same month.
3. Double-tap the chip quickly: one Day detail (Back once returns to Progress).
4. A second tap on a selected ring dot also opens it.
5. `scenario.recreate()` on Day detail keeps the same date's screen.
6. Start a session: "Alarm in progress" replaces Day detail. After the session, Home shows.

## Scan tests and rules to respect

- `CopyRulesTest`: the three new strings. "·" is allowed, em dashes are not.
- `MoneyFormattingScanTest`: amounts only via `formatMoney`. The flow test compares against `formatMoney` /
  `moneyFormatter` output, not a hard-coded symbol in main code.
- `PurchaseStateReaderScanTest`: use `record.status` / `RecordStatus`. Never write `purchaseState` anywhere in shipped
  code.
- `SessionHistoryWriterScanTest`: `merges()` is a read. Never call `recordMerge` or `upsert` outside the recorder.
- `PurchaseLedgerWriterScanTest`: no `putRecord` or `deleteNotOwed`.
- `DeleteAllDataWriterScanTest` and `SessionLockGuardScanTest`: nothing written, so no guard needed.
- detekt: `NoDirectTimeAccess` (zone from `TimeZoneProvider`, no clock read), `NoFloatingPointMoney`, `NoRawColor`,
  `NoRawSp`, `NoRawCornerRadius`.
- `ContrastTest`: no new pair. The stranded amount is `text-secondary` on glass, as in Purchase history.
- `PreviewMenuTest` and `states.md`: unchanged unless the preview agent adds a state.

## Defaults taken (fast mode; the owner can change them)

- **Q1 (epic default): stored facts only.**
  - The timeline holds: rang (or before the first unlock), each paid snooze with its time and price, merged alarms,
    and the end ("{checks} solved" or "Stopped after 30 minutes").
  - Not shown: "rang again", "I'm up", "quiet time ran out", "check switched" and the try count.
  - Rings are 1 + snoozes. The owner sees the reduced preview from the preview agent first.
- **E1, snooze length not stored:** the approved "Snoozed {minutes} min · {price}" becomes "Snoozed · {price}". The
  alarm's current snooze length may differ from that morning's, so it is not used.
- **E2, fallback:** the hero carries the `alt_route` badge and "Fallback check used" (AC wording). It is not a
  timeline row, because its time is not stored.
- **E3, merged alarms:** a timeline row at `mergedAt` (approved round 2 design) rather than a separate `note-inline`.
  The string is the same, "{time} alarm merged into this session". This conflicts with the AC wording, which says
  `note-inline`; flagged.
- **E4, reused payments:** a reused payment's purchase time can fall before this ring. Its row shows no time instead
  of a wrong one.
- **E5, turned-off and deleted notes:** they appear only on days that also have a session. Days without a session have
  no marker and cannot be opened (6.6 C7).
- **E6, empty day:** a day with nothing logged (for example data deleted while the route was saved) closes Day detail.
- **E7, load failure:** reuses Progress's "Couldn't load your progress." and "Try again". No new failure string.
- **E8, new strings:** three, listed above, for the v2 wording pass.
- **E9, check names:** all checks of the session are joined with ", " (a plan with two steps reads "Math, Word
  Unscramble solved").

## Risks

- **Preview agent collision on `DayDetailScreen.kt`** and `PreviewProgressSamples.kt` (the model change). Rebase on
  `design/preview-epic-6` first and keep its names. The round 2 `day_*` baselines must not change by accident.
- **`LocalDate` in a `@Serializable` route:** if Nav3 saved state cannot encode it, fall back to an ISO `String`
  (tested).
- **ViewModel key:** each `Route.DayDetail` entry has its own ViewModel store (the nav entry decorator), but pass
  `key = date.toString()` anyway so two dates never share one.
- **Money mismatch with Purchase history:** use the same `isAmountPaid` rule, and the same stranded wording and colour.
- **History versus records disagree** (records unreadable, or kept after Delete all data): the snoozes tile comes from
  history, while the timeline lists the records it finds, so the counts can differ. This is accepted and documented.
- **Hot files:** `Route.kt`, `TabNavigation.kt`, `PushedEntries.kt`, `UiModule.kt`, `strings.xml`. Lane 2 may touch
  `UiModule.kt` and `strings.xml`. Rebase before review.
- **Release:** the first closed-test release with Progress should wait for this story (see 6.5 risks).

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

- `:composeApp:testAndroidHostTest --tests "*daydetail*" --tests "*nav*" --tests "*UiModuleTest*" --tests "*CopyRulesTest*" --tests "*MoneyFormattingScanTest*"`
- `:data:testAndroidHostTest --tests "*ScanTest*"`
- `:androidApp:recordRoborazziDebug`, then `:androidApp:testDebugUnitTest --tests "*DayDetail*" --tests "*Progress*" --tests "*Preview*" --tests "*Round2TapThroughTest*"`
- `spotlessCheck detekt`

## Spec Change Log

## Review Triage Log

## Owner decision (2026-10-26)
The owner approved all three round 5 defaults ("OK with all"):
- money in two currencies is joined with " + ", one line per currency on Progress;
- "/ 30" is hidden once the streak passes 30;
- Day detail uses stored facts only (Q1 option A): "Snoozed · {price}" and "{check} solved".
These are final for this build.
