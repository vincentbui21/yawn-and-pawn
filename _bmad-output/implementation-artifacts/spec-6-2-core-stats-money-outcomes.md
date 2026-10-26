---
title: 'Story 6.2: Snoozes over 30 days, money per currency, day outcomes, Insight and ObserveProgress in core.stats'
type: 'feature'
created: '2026-10-26'
status: 'review'
baseline_commit: '0f97bb06'
route: 'dispatch'
story_key: '6-2-snoozes-per-week-money-per-currency-and-day-outcomes-in-core'
review_loop_iteration: 1
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-6-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-6-1-streaks-on-time-rate-and-time-to-up-in-core-stats.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Home (6.3), Progress (6.5, 6.6), Day detail (6.7) and the weekly summary (6.8) need the snoozes over 30 days, the money paid per currency, each day's outcome, the Insight line, and one live stream of all of it. If each screen computes them itself, they will disagree with each other and with Purchase history, especially around midnight, the ISO week edge, DST and zone changes.

**Approach:** Pure functions in `core.stats`, next to 6.1's `WakeStats.kt`, over `List<SessionHistoryRow>` and `List<PurchaseRecord>`. They use the Epic 6 shared stats rules (epics.md, owner-approved default 2026-09-26). One use case, `ObserveProgress`, turns the two tables plus a `DayTicker` (current local date and zone) into a `Flow<ProgressSnapshot>`. Nothing is stored (AD-18). Time comes from the Story 1.6 ports at the call site (AD-3), and amounts are per currency and never converted (AD-8).

Defaults taken (unattended fast-mode run; the owner can change them):
- **Estimated prices are not counted** (epic-6-context.md, "Decided by default"). A record counts as paid only when its status is Granted, Consumed or Reused **and** `priceSource.isAmountPaid` is true. This keeps Home, Progress, Day detail and the weekly summary in step with Purchase history (`purchaseOf`) and "Problem with a charge?".
- **Insight rule (Q3 default):** compare the average time to get up on weekdays (Mon to Fri) with weekends (Sat, Sun) over the last 30 local days. Only OnTime and Snoozed sessions with a `timeToCompleteMs` count, the same set as 6.1's `averageTimeToUp`. The line shows only when each side has at least 3 such sessions and the gap between the averages is 2 minutes or more. The faster side wins. Otherwise the result is `null`, and 6.5 hides the card.
- **Turbine is not used in `:core`:** `CoreDependencyRules.allowedCoreTestArtifacts` allows only `kotlin-test` and `kotlinx-coroutines-test`, and Turbine is used nowhere in the repo. The flow tests collect into a list in `backgroundScope` with `runCurrent()`. `:core` tests may not use `:testing` (`CoreDependencyRules.testingConsumers`), so they use test-local doubles in `core/src/commonTest/.../stats/`. The `:testing` fakes (`FakeSessionHistoryRepository`, `FakePurchaseRecordRepository`, `FakeClock`) are exercised against the real `ObserveProgress` in Story 6.3's `HomeViewModel` tests.
- **`dayOutcome` and `monthSummary` take no `records`:** the AC lists `records` in their signatures, but a day's outcome needs no money, and detekt would flag the unused parameter. `sessionsOn` keeps `records`.
- **Running sessions (null outcome)** are left out of `dayOutcome`, `monthSummary`, `sessionsOn` and `snoozesLast30Days`, as if not yet recorded. `hasHistory` is true for any stored row, whatever its outcome.
- **`DaySummary.sessionCount`** counts every finished session of the day, Test and Skipped included (Day detail lists them). **`fallbackUsed`** is true when any finished session that day used the fallback.
- **Money period by purchase time:** a record falls in a week or month by the local date of `purchasedAt` in the current zone, not by its session's day.
- **Unreadable purchase rows:** `PurchaseRecordsRead.unreadable` is passed through as `ProgressSnapshot.unreadablePurchases`. Totals use only the readable records (the repository already logs the count). No screen in 6.2 or 6.3 shows it.
- **`SessionHistoryRepository.observeAll()` throws into the flow on a storage failure or an unreadable row**, the same contract as `observeLatestMissed` and `observeFallbacks`. The collector (ViewModel) catches it.

## Boundaries & Constraints

**Always:**
- Day of a session = 6.1's `sessionDay(row, zone)`. Counted = 6.1's `isCounted(row)`. Never redefine them.
- Week = ISO week, Monday 00:00 to Sunday 24:00 local: the `LocalDate` range from the Monday on or before `today` through the following Sunday. Month = `today`'s local calendar month. Both are compared on local **dates**, so a DST day (23 h or 25 h) is still one day.
- Money is summed in `Long` micros per currency, never across currencies, and returned sorted by ISO currency code. The result is an empty list when nothing was paid, never a zero `Money`.
- Stranded records never count. A record's identity is its `tokenHash` (the repository keys on it), so a payment that moved from Stranded to Reused is one record and is counted once.
- Functions are pure, live in commonMain, read no clock, import no platform code (AD-1) and store nothing (AD-18).
- `SessionRecorder` stays the only writer of `session_history`, and `PurchaseLedger` the only writer of purchase records. 6.2 adds a read only.

**Never:**
- Name a purchase's Play state anywhere: no `purchaseState`, `PurchaseState` or `PlayPurchaseState`, not even as a variable name (`PurchaseStateReaderScanTest`). Use `PurchaseRecord.status` (`RecordStatus`), as `PurchaseHistoryContract` does.
- Call any write verb on a repository, store, DAO or recorder from `core.stats`. `stats` is a full package of `SessionLockGuardScanTest`, so every class in it is scanned.
- Read `kotlin.time.Clock.System`, `TimeZone.currentSystemDefault()` or similar directly (detekt `NoDirectTimeAccess`, which also covers `core/src/**` tests).
- Add UI, a weekly series for a chart (dropped 2026-10-01), a Room migration, or a new dependency.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior |
|----------|--------------|---------------------------|
| Snoozes window edge | today 2027-03-31; Snoozed (2 snoozes) on 2027-03-02, Snoozed (5) on 2027-03-01 | 2 (the 30th day back is in, the 31st is out) |
| Snoozes by outcome | Snoozed 2, Missed with 3 snoozes, Test with 1, Skipped 0, running row with 1 | 5 |
| Snoozes empty | no rows, or only Test/Skipped | 0 |
| Money per currency | EUR 2.99 + USD 1.00 + EUR 5.99, all Consumed, Intent price, this week | `[EUR 8.98, USD 1.00]` (2 entries, code order) |
| Money statuses | Granted, Consumed, Reused counted; Stranded not | only the first three are summed |
| Stranded then reused | one token hash, status Reused | counted once |
| Estimated price | Consumed, `priceSource` Snapshot / Tier / Unknown | not counted |
| Nothing paid | no records, or only stranded or estimated ones | `[]` |
| Month edge (DST day) | Europe/Berlin, today 2027-10-31; purchase at 2027-10-31T23:59 local (25 h day) / 2027-11-01T00:00 local | ThisMonth (October) counts the first, not the second |
| Week edge | Europe/Berlin, today 2027-11-01 (Monday); purchase at 2027-11-01T00:00 local / 2027-10-31T23:59 local | ThisWeek counts the first, not the second |
| All time | records over 3 years, 2 currencies | one total per currency |
| Zone change | purchase at 2027-01-15T06:30 Asia/Ho_Chi_Minh, read in Europe/London with today 2027-01-15 | its local date is 2027-01-14 (week/month by that date) |
| Day outcome worst-first | one day: OnTime + Snoozed + Missed / OnTime + Snoozed / OnTime only | Missed / Snoozed / OnTime |
| Day neutral | only Skipped + Test / only Test / only Skipped | Skipped / Test / Skipped |
| Day none | no session, or only a running row | `None`, `sessionCount` 0 |
| Day mixed neutral | OnTime + Test | OnTime, `sessionCount` 2 |
| Fallback flag | any finished session that day with `fallbackUsed` | `fallbackUsed` true |
| After midnight | scheduled 23:50 on day D, ended 00:10 on D+1 | belongs to D |
| Month summary | February 2028 (leap year) / March 2027 (DST change on the 28th) | 29 / 31 `DaySummary` entries, one per date, in date order |
| Sessions on a day | 2 sessions; records for each (consumed, reused, stranded, estimate) | ordered by `scheduledAt`; `paid` totals per currency from counted records with an amount; `purchases` = counted records, oldest first; `stranded` listed separately |
| Insight weekdays | 30 days: 4 weekday sessions at 3 min, 3 weekend sessions at 6 min | `FastestOnWeekdays` |
| Insight weekends | weekdays 8 min average, weekends 5 min (3 each) | `FastestOnWeekends` |
| Insight thin | 2 weekend sessions, or a gap of 1 min 59 s, or no data | `null` |
| Insight gap exactly 2 min | 3 and 3 sessions, averages 3:00 and 5:00 | shown |
| Insight ignores | Missed, Test, Skipped, null `timeToCompleteMs`, sessions older than 30 days | not counted |
| ObserveProgress emits | a history row is upserted / a purchase record is put / the clock crosses local midnight and a tick arrives / the zone changes and a tick arrives / `refresh` emits after a date change | a new `ProgressSnapshot` each time |
| ObserveProgress quiet | a minute tick on the same date and zone | no new emission (`distinctUntilChanged`) |
| Performance | about 1,100 daily rows over 3 years plus 300 records | `progressSnapshot` well under 50 ms on the JVM |

</frozen-after-approval>

## Code Map

- `core/src/commonMain/kotlin/com/yawnandpawn/app/core/stats/WakeStats.kt` (Story 6.1): `sessionDay`, `isCounted`, `currentStreak`, `bestStreak`, `onTimeRate`, `averageTimeToUp`, `wholeMinutes`, `WakeStatsRule`, and a **private** `window(today, days): ClosedRange<LocalDate>`. 6.2 changes only `private fun window` to `internal fun window`. If 6.1 renamed it, use the merged name. Nothing else changes.
- `core/src/commonTest/kotlin/com/yawnandpawn/app/core/stats/StatsBuilders.kt` (6.1): `StatsZone` (Europe/Berlin), `aSession(day, outcome, at, zone, timeToUp)` and `LocalDate.day(offset)`. 6.2 adds defaulted parameters only (see Tasks), so 6.1's tests keep compiling.
- `core/src/commonMain/kotlin/com/yawnandpawn/app/core/history/SessionHistory.kt`: `SessionHistoryRow`, `SessionOutcome`, and the `SessionHistoryRepository` port, which gains `observeAll()`.
- `core/src/commonMain/kotlin/com/yawnandpawn/app/core/billing/PurchaseRecords.kt`: `PurchaseRecord` (`tokenHash`, `sessionId`, `price`, `priceSource`, `purchasedAt`, `status`), `PriceSource.isAmountPaid`, `PurchaseRecordRepository.observeAll(): Flow<PurchaseRecordsRead>` and `PurchaseRecordsRead(records, unreadable)`. Read only.
- `core/.../billing/PurchaseReconciler.kt`: `enum class RecordStatus { Absent, Granted, Consumed, Stranded, Reused }`.
- `core/.../billing/Money.kt`: `Money(micros, currency)`. `totalsByCurrency` keeps first-seen order, so 6.2 sorts by code itself.
- `core/.../time/TimePorts.kt`: `Clock` (typealias of `kotlin.time.Clock`), `TimeZoneProvider.current()`, and `TimeChangeSignal.changes()` (Android: `TIME_TICK` every minute, `TIME_SET`, `TIMEZONE_CHANGED`, while collected).
- `data/src/commonMain/kotlin/com/yawnandpawn/app/data/history/SessionHistoryDao.kt` and `RoomSessionHistoryRepository.kt`: add the read. A `@Query` does not change the Room schema, so there is no migration and no schema JSON change.
- `testing/src/commonMain/kotlin/com/yawnandpawn/app/testing/FakeSessionHistoryRepository.kt`: add `observeAll()` (its `changes` counter already ticks on upsert and delete).
- Other `SessionHistoryRepository` implementers that must gain the override: `core/src/commonTest/.../session/SessionEngineTestDoubles.kt` (`InMemoryHistory`), `core/src/commonTest/.../history/MissedNotesTest.kt` (an anonymous `object`), and `androidApp/src/test/kotlin/com/yawnandpawn/app/android/wake/WakeActivityTest.kt` (the class at about line 479). Run the Grep `SessionHistoryRepository\s*\{` again before the build, in case a new one has appeared.
- `androidApp/src/main/kotlin/com/yawnandpawn/app/YawnAndPawnApp.kt`: Koin. `MissedNotes` and `ReRegisterSuggestions` are the pattern, at about lines 275 to 277.
- Scan tests to respect: `data/src/androidHostTest/.../SessionLockGuardScanTest.kt` (`FULL_PACKAGES` includes `stats`; `WRITES` matches receivers ending in Repository, Store, Dao, Dismissals or Recorder, followed by a write verb), `PurchaseStateReaderScanTest.kt`, `billing/PurchaseLedgerWriterScanTest.kt` (reads are allowed; `PurchaseHistoryViewModel` already reads `observeAll()`), `history/SessionHistoryWriterScanTest.kt` (writes only), and detekt `NoDirectTimeAccess`.

## Tasks & Acceptance

**Execution:**
- [x] `core/.../history/SessionHistory.kt`: add to `SessionHistoryRepository`:
  ```kotlin
  /**
   * Every row, oldest `scheduled_at` first (then session id), again after every change (Progress and Home, Epic 6).
   * A read only. A storage failure or an unreadable row is thrown into the flow; the collector catches it.
   */
  fun observeAll(): Flow<List<SessionHistoryRow>>
  ```
- [x] `data/.../history/SessionHistoryDao.kt`: `@Query("SELECT * FROM session_history ORDER BY scheduled_at, session_id") abstract fun observeAll(): Flow<List<SessionHistoryEntity>>`. `RoomSessionHistoryRepository.observeAll() = dao.observeAll().map { rows -> rows.map { it.toRow() } }`, with the same "a read only" comment as `observeFallbacks`.
- [x] `testing/.../FakeSessionHistoryRepository.kt`: `override fun observeAll() = changes.map { observeFailure?.let { throw it }; stored.values.sortedWith(compareBy({ it.scheduledAt }, { it.sessionId })) }`. Update the KDoc so `observeFailure` covers it too.
- [x] The three test-double implementers above: add `observeAll()`. `InMemoryHistory` and `WakeActivityTest`'s double return `emptyFlow()` with the comment "never read here", or a `MutableStateFlow` if a test needs it. `MissedNotesTest`'s object returns `emptyFlow()`.
- [x] `core/src/commonMain/kotlin/com/yawnandpawn/app/core/stats/ProgressStats.kt` (new):
  ```kotlin
  object ProgressRule {
      const val SNOOZE_DAYS = 30
      const val INSIGHT_DAYS = 30
      const val INSIGHT_MIN_SESSIONS = 3      // per side
      val INSIGHT_MIN_GAP: Duration = 2.minutes
  }
  enum class InsightLine { FastestOnWeekdays, FastestOnWeekends }
  enum class MoneyPeriod { ThisWeek, ThisMonth, AllTime }
  enum class DayOutcome { None, OnTime, Snoozed, Missed, Skipped, Test }
  data class DaySummary(val date: LocalDate, val outcome: DayOutcome, val sessionCount: Int, val fallbackUsed: Boolean)
  /** One session of a day for Day detail (6.7). */
  data class SessionOnDay(
      val row: SessionHistoryRow,
      /** Counted records of this session (Granted, Consumed, Reused), oldest purchase first, estimates included (6.7 shows them without an amount). */
      val purchases: List<PurchaseRecord>,
      /** What this session cost, one total per currency, by code; only records with an amount paid. */
      val paid: List<Money>,
      /** Stranded records of this session (refunded by Google), oldest first. */
      val stranded: List<PurchaseRecord>,
  )

  fun snoozesLast30Days(history: List<SessionHistoryRow>, today: LocalDate, zone: TimeZone): Int
  fun insight(history: List<SessionHistoryRow>, today: LocalDate, zone: TimeZone): InsightLine?
  /** Status Granted, Consumed or Reused, and the price is what was charged. */
  fun isPaidAmount(record: PurchaseRecord): Boolean
  /** Monday through Sunday of [date]'s ISO week. */
  fun isoWeekOf(date: LocalDate): ClosedRange<LocalDate>
  fun moneyPaid(records: List<PurchaseRecord>, period: MoneyPeriod, today: LocalDate, zone: TimeZone): List<Money>
  fun dayOutcome(history: List<SessionHistoryRow>, date: LocalDate, zone: TimeZone): DaySummary
  /** One DaySummary per date in [dates], in order (6.5's ring uses the last 30 days). */
  fun daySummaries(history: List<SessionHistoryRow>, dates: ClosedRange<LocalDate>, zone: TimeZone): List<DaySummary>
  fun monthSummary(history: List<SessionHistoryRow>, month: YearMonth, zone: TimeZone): List<DaySummary>
  fun sessionsOn(history: List<SessionHistoryRow>, records: List<PurchaseRecord>, date: LocalDate, zone: TimeZone): List<SessionOnDay>
  ```
  Notes:
  - `moneyPaid` = `records.filter(::isPaidAmount)`, then the period filter on `purchasedAt.toLocalDateTime(zone).date` (`isoWeekOf(today)`, or the same year and month as `today`, or everything), then `groupBy { currency }`, the sum of `micros` as `Long`, `sortedBy { currency }`.
  - `daySummaries` groups once with `groupBy { sessionDay(it, zone) }` over the rows whose outcome is not null, so a month costs one pass, not one pass per day.
  - `kotlinx.datetime.YearMonth` exists in kotlinx-datetime 0.7+ (the repo uses 0.8.0): use `month.firstDay..month.lastDay`. If the build cannot resolve it, take `(year: Int, month: Month)` and say so in the Spec Change Log.
- [x] `core/src/commonMain/kotlin/com/yawnandpawn/app/core/stats/ObserveProgress.kt` (new):
  ```kotlin
  /** The local date and zone stats are computed for. Equal when the date and the zone id are equal. */
  data class StatsDay(val today: LocalDate, val zoneId: String) { val zone: TimeZone get() = TimeZone.of(zoneId) }

  /** The current StatsDay: once at start, then after each time signal or [refresh], only when the date or zone changed. */
  class DayTicker(private val clock: Clock, private val timeZoneProvider: TimeZoneProvider, private val timeChanges: TimeChangeSignal) {
      fun current(): StatsDay
      fun days(refresh: Flow<Unit> = emptyFlow()): Flow<StatsDay> =
          merge(timeChanges.changes(), refresh).onStart { emit(Unit) }.map { current() }.distinctUntilChanged()
  }

  data class ProgressSnapshot(
      val today: LocalDate,
      val zone: TimeZone,
      val currentStreak: Int,
      val bestStreak: Int,
      val onTimeRate7Days: Int?,
      val onTimeRate30Days: Int?,
      val averageTimeToUp: Duration?,
      val snoozesLast30Days: Int,
      val insight: InsightLine?,
      val paidThisWeek: List<Money>,
      val paidThisMonth: List<Money>,
      val paidAllTime: List<Money>,
      /** At least one history row is stored (any outcome): Home shows the hero (6.3), Progress leaves its empty state (6.5). */
      val hasHistory: Boolean,
      /** Stored purchase rows that could not be read; the totals leave them out. */
      val unreadablePurchases: Int,
      /** The inputs, for 6.5's month paging and 6.7's Day detail without a second read. */
      val history: List<SessionHistoryRow>,
      val records: List<PurchaseRecord>,
  )

  /** Pure: everything Home and Progress show, for [today] in [zone]. */
  fun progressSnapshot(history: List<SessionHistoryRow>, records: PurchaseRecordsRead, today: LocalDate, zone: TimeZone): ProgressSnapshot

  /**
   * FR-PRG-1/2/3: recomputed when session_history or the purchase records change, and when the local date or zone
   * changes. [computeContext] runs the computation off the caller's thread (Dispatchers.Default in the app; tests pass
   * EmptyCoroutineContext). Storage failures propagate to the collector.
   */
  class ObserveProgress(
      private val history: SessionHistoryRepository,
      private val records: PurchaseRecordRepository,
      private val days: DayTicker,
      private val computeContext: CoroutineContext = Dispatchers.Default,
  ) {
      operator fun invoke(refresh: Flow<Unit> = emptyFlow()): Flow<ProgressSnapshot> =
          combine(history.observeAll(), records.observeAll(), days.days(refresh)) { rows, read, day ->
              progressSnapshot(rows, read, day.today, day.zone)
          }.distinctUntilChanged().flowOn(computeContext)
  }
  ```
  Keep the constructor parameter names `history`, `records` and `days`. A receiver ending in `Repository`, `Store`, `Dao` or `Recorder` is what `SessionLockGuardScanTest.WRITES` looks at. Only read verbs are called, so it passes either way. It is still clearer.
- [x] `androidApp/.../YawnAndPawnApp.kt`: `factory { DayTicker(get(), get(), get()) }` and `factory { ObserveProgress(get(), get(), get()) }`, beside `MissedNotes`.
- [x] `core/src/commonTest/.../stats/StatsBuilders.kt`: add defaulted `snoozes: Int = <current rule>` and `fallbackUsed: Boolean = false` to `aSession`, and a new `aRecord(sessionId, price: Money, status = RecordStatus.Consumed, source = PriceSource.Intent, at: LocalDateTime, zone = StatsZone, tokenHash = "t${++n}")` that builds a `PurchaseRecord` (`consumedAt` set only for Consumed and Reused).
- [x] `core/src/commonTest/.../stats/ProgressTestDoubles.kt` (new, internal): `MutableHistory : SessionHistoryRepository` (a `MutableStateFlow<List<SessionHistoryRow>>`, with `observeAll`, `upsert` and `find`; the rest returns `emptyFlow()` or `Success`), `MutableRecords : PurchaseRecordRepository` (a `MutableStateFlow<PurchaseRecordsRead>`, with `observeFailure: Throwable?`), `TestClock(var now: Instant) : Clock`, `TestZones(var zone: TimeZone) : TimeZoneProvider`, and `TestTimeSignal : TimeChangeSignal` (a `MutableSharedFlow<Unit>(extraBufferCapacity = 1)` with `tick()`).
- [x] `core/src/commonTest/.../stats/ProgressStatsTest.kt` (new): table tests for every pure row of the matrix (snoozes, money, day outcome, month summary, sessions on a day, insight, ISO week).
- [x] `core/src/commonTest/.../stats/ObserveProgressTest.kt` (new): flow tests for the ObserveProgress rows, `DayTicker` (same-day tick → no emission; date change; zone change; `refresh`; zone equality by id), a failure in either repository flow propagating, and `computeContext` honoured (pass the `StandardTestDispatcher`).
- [x] `core/src/commonTest/.../stats/ProgressPerformanceTest.kt` (new): 3 years of daily sessions (1,096 rows, mixed outcomes, some fallbacks) and 300 records in 2 currencies; warm up 20 runs, then take the **fastest of 10** with `TimeSource.Monotonic.measureTime` (not banned by `NoDirectTimeAccess`) and assert it is under 50 ms.
- [x] `data/src/androidHostTest/.../history/RoomSessionHistoryRepositoryTest.kt`: `observeAll` orders by `scheduled_at` then id, emits again after `upsert` and after `deleteAllHistory`, and an unreadable outcome string throws into the flow.

**Acceptance Criteria:**
- Given a history, when `snoozesLast30Days(history, today, zone)` runs, then it sums `snoozeCount` over OnTime, Snoozed and Missed rows whose day is in `today − 29 … today`. A session on the 30th day back counts, one on the 31st does not, and no weekly series is built.
- Given a history, when `insight(history, today, zone)` runs, then it returns `FastestOnWeekdays` or `FastestOnWeekends` under the Q3 default rule, or `null` when the data is too thin.
- Given purchase records, when `moneyPaid(records, period, today, zone)` runs for `ThisWeek`, `ThisMonth` and `AllTime`, then it returns one `Money` per currency, micros summed as `Long`, over records that are Granted, Consumed or Reused with `priceSource.isAmountPaid`, sorted by currency code, and `[]` when nothing was paid. EUR 2.99 + USD 1.00 + EUR 5.99 gives `[EUR 8.98, USD 1.00]`. Stranded and estimated records are never included. A purchase at 23:59 on the month's last day counts in that month, and one at 00:00 on Monday counts in the new week.
- Given a history, when `dayOutcome`, `monthSummary` and `sessionsOn` run, then they follow the matrix: worst counted outcome first, Skipped wins over Test, `None` for an empty day, one entry per day of the month, and sessions ordered by `scheduledAt` with paid and stranded records apart.
- Given the repositories and a `DayTicker`, when `ObserveProgress()` is collected, then it emits a `ProgressSnapshot` with every field above, and emits again after a history upsert, after a record put, when the local date changes, when the zone changes, and on `refresh` after a date change. A minute tick on the same day adds nothing.
- Given 3 years of daily rows, when `progressSnapshot` runs on the JVM, then the fastest of 10 warm runs is under 50 ms.
- Given `./gradlew :core:jvmTest koverVerify`, when run, then all tests pass and `core.stats` (6.1's `koverVerifyStats` variant) is at least 90% line coverage.
- Given the scan tests (`SessionLockGuardScanTest`, `PurchaseStateReaderScanTest`, `PurchaseLedgerWriterScanTest`, `SessionHistoryWriterScanTest`) and detekt `NoDirectTimeAccess`, when run, then they pass with no change to their allow-lists or expected writer sets.

## Design Notes

**Why `ObserveProgress` reads purchase records directly is allowed:** `PurchaseStateReaderScanTest` bans only the *Play* state names (`purchaseState`, `PurchaseState`, `PlayPurchaseState`) outside `PurchaseReconciler` and the Play mapping. Purchase *records* are read through the `PurchaseRecordRepository.observeAll()` port, as `PurchaseHistoryViewModel` (4.16) and `ProblemWithChargeViewModel` (4.17) already do. `PurchaseLedgerWriterScanTest` restricts only writes (`putRecord`, `deleteNotOwed`) and `consume`. So the port is the existing `PurchaseRecordRepository`, read only, with `RecordStatus` as the status.

**Why `DayTicker` uses `TimeChangeSignal`:** the AC asks for a ticker "driven from Clock and TimeZoneProvider". `TimeChangeSignal` already gives a minute tick, a clock set and a zone change while collected, and `HomeViewModel` uses it for the countdown. `DayTicker` maps every signal to `StatsDay(clock.now().toLocalDateTime(zone).date, zone.id)` and drops repeats, so the snapshot is recomputed at most once per local-date or zone change (within a minute of midnight). Nothing ticks while no screen collects, and when a screen resubscribes, `onStart` emits the current day. `refresh` lets a screen force a check on resume (6.3 passes its `Resumed` signal). The zone is compared by id, because `TimeZone` equality is not guaranteed across instances.

**Computing once:** `progressSnapshot` may call the 6.1 functions as they are. 1,100 rows times about 10 passes of `toLocalDateTime` is a few milliseconds. If the performance test fails, precompute `sessionDay` once into a `List<Pair<LocalDate, SessionHistoryRow>>` and pass it to private overloads. Do not change 6.1's public signatures.

**Insight arithmetic:** compare in milliseconds: `gap = |avgWeekday − avgWeekend|`, shown when `gap >= 2.minutes` and both counts are at least 3. With equal averages, nothing shows.

## Risks

- **6.1 is not merged yet** (in progress in worktree `agent-ad37c9666cd6d48e1`). 6.2 must branch from main after it lands. The names used here (`sessionDay`, `isCounted`, `currentStreak`, `bestStreak`, `onTimeRate`, `averageTimeToUp`, `WakeStatsRule`, `aSession`, `StatsZone`, private `window`) are from its spec and its work-in-progress code. Re-check them after the merge.
- **Performance test flakiness** on this shared PC and under Kover instrumentation. Mitigated by the warm-up and fastest-of-10. Do not loosen the 50 ms bound; if it flakes, precompute days (see Design Notes).
- **One unreadable history row hides all stats** (`observeAll` throws). This is the same contract as the other history reads, and newer restored databases are already skipped (Story 2.12). 6.3 hides the hero and retries.
- **Adding an abstract method to `SessionHistoryRepository`** breaks every implementer at compile time. The four known ones are listed above. A missed one is caught by `compileTestKotlin` in its module.
- **Delete all data:** records still owed a consume survive (Story 5.9), so money can show right after a delete. Accepted (epic-6-context.md); the note goes in 6.3.
- **Undercount versus Play receipts:** a payment whose intent was lost (estimate price) is charged but not counted. This is a deliberate default, consistent with Purchase history, which shows such a row without an amount.

## Verification

**Commands (JDK 17, `--no-daemon --max-workers=2`, targeted only; never `qualityGate` locally):**
- `:core:jvmTest --tests "*stats*" --tests "*history*" --tests "*session*"`: pass.
- `:data:testAndroidHostTest --tests "*SessionHistory*" --tests "*SessionLockGuardScanTest*" --tests "*PurchaseStateReaderScanTest*" --tests "*PurchaseLedgerWriterScanTest*"`: pass.
- `:androidApp:testDebugUnitTest --tests "*WakeActivityTest*"`: compiles and passes (the double gains `observeAll`).
- `:composeApp:testAndroidHostTest --tests "*UiModuleTest*" --tests "*PurchaseHistory*"`: pass (the `:testing` fake changed).
- `koverVerify` (includes `:core:koverVerifyStats`), `spotlessCheck detekt`: pass.
- No Roborazzi baseline changes (no UI in this story).

## Implementation Notes

- Branched from Story 6.1's branch (88194bb7), then rebased onto main once 6.1 merged as 0f97bb06 (6.1's files are identical in both). Baseline is 0f97bb06.
- No coordinator note was present at the end of the copied spec; the run followed the task brief and `epic-6-context.md`.
- 6.1's real names: the thresholds object is `WakeStats` (not `WakeStatsRule`), with `RATE_DAYS_SHORT`, `RATE_DAYS_LONG` and `AVERAGE_DAYS`. `window` was `private` and is now `internal`; nothing else in `WakeStats.kt` changed.
- Files: `core/.../stats/ProgressStats.kt` (rules, enums, snoozes, Insight, day outcomes, `sessionsOn`), `core/.../stats/MoneyStats.kt` (`isPaidAmount`, `isoWeekOf`, `moneyPaid` and two internal helpers), `core/.../stats/ObserveProgress.kt` (`StatsDay`, `DayTicker`, `ProgressSnapshot`, `progressSnapshot`, `ObserveProgress`); `SessionHistoryRepository.observeAll()` in core, the Room DAO and repository, the `:testing` fake and the test doubles (`InMemoryHistory`, the `MissedNotesTest` object, and `WakeActivityTest`'s `CountingHistory`, which delegates and counts like its other reads); Koin factories in `YawnAndPawnApp.kt`, resolved in `SessionWiringTest`.
- Tests: `ProgressStatsTest` (32), `ObserveProgressTest` (12), `ProgressPerformanceTest` (1), `ProgressTestDoubles`; new defaulted `snoozes` and `fallbackUsed` on `aSession`, plus `aRecord`, `eur` and `usd` in `StatsBuilders.kt`; 2 new tests in `RoomSessionHistoryRepositoryTest`.
- `kotlinx.datetime.YearMonth` resolved (kotlinx-datetime 0.8.0), so `monthSummary` takes a `YearMonth` as specified.
- `computeContext` is shown honoured by recording the dispatcher (the `ContinuationInterceptor` key) that the history flow is collected in; `CoroutineDispatcher.Key` needs an opt-in, so it is not used.
- Verified: `:core:jvmTest koverVerify` (all core suites pass; `koverVerifyStats` and the other package rules pass); `:data:testAndroidHostTest` for `SessionHistory*`, `SessionLockGuardScanTest`, `PurchaseStateReaderScanTest` and `PurchaseLedgerWriterScanTest` (30 tests, 0 failures); `:testing:compileKotlinJvm`; `spotlessCheck detekt`; `:androidApp:testDebugUnitTest` (`WakeActivityTest`, `SessionWiringTest`) and `:composeApp:testAndroidHostTest` (`UiModuleTest`, `PurchaseHistory*`). The full `qualityGate` was not run locally.

## Spec Change Log

- `StatsDay.zone` is a property computed once from `zoneId` in the class body (not a getter). It is still not part of equality, and one `StatsDay` gives one `TimeZone` instance, so a re-emission of either table does not produce a snapshot that differs only by a new `TimeZone` object.
- The money functions (`isPaidAmount`, `isoWeekOf`, `moneyPaid`) live in `MoneyStats.kt`, not `ProgressStats.kt`: one file with all 13 functions fails detekt `TooManyFunctions` (11). `MoneyPeriod` stays in `ProgressStats.kt`, because detekt `MatchingDeclarationName` fails a file whose single class does not match its name. Same package and signatures.
- The 6.1 thresholds object is `WakeStats` (the real name), not `WakeStatsRule`.

## Review Triage Log

Pass 1 (one combined reviewer: blind hunter, edge-case hunter, verification gap; fast mode). No HIGH findings.

| # | Severity | Finding | Verdict / route |
|---|----------|---------|-----------------|
| 1 | MEDIUM | 8 lines over 140 characters (a KDoc in `ProgressStats.kt`, and tests in `ObserveProgressTest`, `ProgressStatsTest` and `RoomSessionHistoryRepositoryTest`): ktlint and detekt `MaxLineLength` would fail | Patched: wrapped. `spotlessCheck detekt` pass. |
| 2 | MEDIUM | `ProgressStats.kt` has 13 top-level functions: detekt `TooManyFunctions` (11) would fail | Patched: the money functions moved to `MoneyStats.kt` (see Spec Change Log). |
| 3 | LOW | The year check of `ThisMonth` is not tested | Patched: the 3-year test asserts that only March 2027 counts for today 2027-03-31 (March 2025 and 2026 hold records too). |
| 4 | LOW | The 30-day edge of `insight` is not tested directly | Patched: a new test shows day −29 counts and day −30 does not. |
| 5 | LOW | The first ObserveProgress snapshot is compared only with `progressSnapshot` itself, so a swapped field would pass | Patched: a fixture with streak 1, best 2, 7-day rate 100, 30-day rate 75, average 3.5 min, and money this month but not this week; each field is asserted. |
| 6 | LOW | "stranded then reused counts once" feeds one record, so it proves no deduplication | Patched: renamed to what it shows. Identity by token hash is the repository's (the Room key). |
| 7 | LOW | The `sessionId` tie-break in `sessionsOn` is untested | Rejected: it only makes the order stable for equal scheduled times; no rule depends on it. |
| 8 | LOW | No Koin resolution test for `DayTicker` and `ObserveProgress` | Patched: `SessionWiringTest` resolves both. |
