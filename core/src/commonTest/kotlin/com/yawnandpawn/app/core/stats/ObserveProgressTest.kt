package com.yawnandpawn.app.core.stats

import com.yawnandpawn.app.core.billing.PurchaseRecordsRead
import com.yawnandpawn.app.core.history.SessionOutcome.OnTime
import com.yawnandpawn.app.core.history.SessionOutcome.Snoozed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** Story 6.2: ObserveProgress recomputes on either table and on a new local date or zone, and only then. */
class ObserveProgressTest {
    private val zone = StatsZone
    private val today = LocalDate(2027, 3, 31)
    private val history = MutableHistory()
    private val records = MutableRecords()
    private val clock = TestClock(LocalDateTime(today, LocalTime(7, 0)).toInstant(zone))
    private val zones = TestZones(zone)
    private val signal = TestTimeSignal()
    private val ticker = DayTicker(clock, zones, signal)

    /** Collects ObserveProgress into a list in the background, up to its first emission. */
    private fun TestScope.collect(refresh: Flow<Unit> = emptyFlow()): List<ProgressSnapshot> {
        val seen = mutableListOf<ProgressSnapshot>()
        backgroundScope.launch { ObserveProgress(history, records, ticker, EmptyCoroutineContext)(refresh).toList(seen) }
        runCurrent()
        return seen
    }

    @Test
    fun `the first snapshot is the pure snapshot of both tables for today`() =
        runTest {
            // Two good days, a snoozed day (last week), then a good today: streak 1, best 2, 7-day rate 100, 30-day 75.
            val snoozed = aSession(today.day(-8), Snoozed, snoozes = 2, timeToUp = 7.minutes)
            val rows = listOf(aSession(today.day(-10)), aSession(today.day(-9)), snoozed, aSession(today, timeToUp = 1.minutes))
            rows.forEach { history.upsert(it) }
            val record = aRecord(snoozed.sessionId, eur(2_990_000), LocalDateTime(today.day(-8), LocalTime(6, 40)))
            records.putRecord(record)
            records.unreadable = 1

            val seen = collect()

            val expected = progressSnapshot(rows, PurchaseRecordsRead(listOf(record), 1), today, zone)
            assertEquals(listOf(expected), seen)
            with(seen.single()) {
                assertEquals(today, this.today)
                assertEquals("Europe/Berlin", zone.id)
                assertEquals(1, currentStreak)
                assertEquals(2, bestStreak)
                assertEquals(100, onTimeRate7Days)
                assertEquals(75, onTimeRate30Days)
                assertEquals(3.5.minutes, averageTimeToUp)
                assertEquals(2, snoozesLast30Days)
                assertNull(insight)
                assertEquals(emptyList(), paidThisWeek)
                assertEquals(listOf(eur(2_990_000)), paidThisMonth)
                assertEquals(listOf(eur(2_990_000)), paidAllTime)
                assertTrue(hasHistory)
                assertEquals(1, unreadablePurchases)
                assertEquals(rows, history)
                assertEquals(listOf(record), records)
            }
        }

    @Test
    fun `an empty history has no history and no money`() =
        runTest {
            with(collect().single()) {
                assertFalse(hasHistory)
                assertEquals(emptyList(), paidThisWeek)
                assertNull(insight)
                assertEquals(today, this.today)
            }
        }

    @Test
    fun `a running session alone is history already`() =
        runTest {
            history.upsert(aSession(today, outcome = null))

            assertTrue(collect().single().hasHistory)
        }

    @Test
    fun `a history upsert and a record put each give a new snapshot`() =
        runTest {
            val seen = collect()

            val row = aSession(today, OnTime)
            history.upsert(row)
            runCurrent()
            assertEquals(2, seen.size)
            assertEquals(1, seen.last().currentStreak)

            records.putRecord(aRecord(row.sessionId, usd(1_000_000), LocalDateTime(today, LocalTime(6, 31))))
            runCurrent()
            assertEquals(3, seen.size)
            assertEquals(listOf(usd(1_000_000)), seen.last().paidThisWeek)
        }

    @Test
    fun `a tick after local midnight gives the new day, and a tick on the same day gives nothing`() =
        runTest {
            val seen = collect()

            clock.now += 1.minutes
            signal.tick()
            runCurrent()
            assertEquals(1, seen.size)

            clock.now = LocalDateTime(today.day(1), LocalTime(0, 0, 30)).toInstant(zone)
            signal.tick()
            runCurrent()
            assertEquals(listOf(today, today.day(1)), seen.map { it.today })
        }

    @Test
    fun `a zone change on the same date gives a snapshot in the new zone`() =
        runTest {
            val seen = collect()
            // 07:00 in Berlin is 12:00 the same day in Saigon.
            zones.zone = TimeZone.of("Asia/Ho_Chi_Minh")
            signal.tick()
            runCurrent()

            assertEquals(listOf("Europe/Berlin", "Asia/Ho_Chi_Minh"), seen.map { it.zone.id })
            assertEquals(listOf(today, today), seen.map { it.today })
        }

    @Test
    fun `refresh after a date change gives the new day, and on the same day gives nothing`() =
        runTest {
            val refresh = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
            val seen = collect(refresh)

            refresh.tryEmit(Unit)
            runCurrent()
            assertEquals(1, seen.size)

            clock.now = LocalDateTime(today.day(1), LocalTime(6, 0)).toInstant(zone)
            refresh.tryEmit(Unit)
            runCurrent()
            assertEquals(listOf(today, today.day(1)), seen.map { it.today })
        }

    @Test
    fun `a storage failure in either table reaches the collector`() =
        runTest {
            val progress = ObserveProgress(history, records, ticker, EmptyCoroutineContext)
            history.observeFailure = IllegalStateException("history")
            val historyFailure = assertFailsWith<IllegalStateException> { progress().first() }
            assertEquals("history", historyFailure.message)

            history.observeFailure = null
            records.observeFailure = IllegalStateException("records")
            val recordsFailure = assertFailsWith<IllegalStateException> { progress().first() }
            assertEquals("records", recordsFailure.message)
        }

    @Test
    fun `the computation runs in the compute context`() =
        runTest {
            val compute = StandardTestDispatcher(testScheduler, name = "compute")

            val snapshot = ObserveProgress(history, records, ticker, compute)().first()

            assertEquals(today, snapshot.today)
            assertSame<Any?>(compute, history.collectedIn?.get(ContinuationInterceptor))
        }

    // --- DayTicker ---

    @Test
    fun `the ticker reads today in the current zone`() {
        assertEquals(StatsDay(today, "Europe/Berlin"), ticker.current())

        // 23:30 in Berlin is already the next day in Saigon.
        clock.now = LocalDateTime(today, LocalTime(23, 30)).toInstant(zone)
        zones.zone = TimeZone.of("Asia/Ho_Chi_Minh")
        assertEquals(StatsDay(today.day(1), "Asia/Ho_Chi_Minh"), ticker.current())
    }

    @Test
    fun `stats days are equal by date and zone id`() {
        val day = StatsDay(today, "Europe/Berlin")

        assertEquals(day, StatsDay(today, TimeZone.of("Europe/Berlin").id))
        assertEquals("Europe/Berlin", day.zone.id)
        assertFalse(day == StatsDay(today, "Europe/London"))
        assertFalse(day == StatsDay(today.day(1), "Europe/Berlin"))
    }

    @Test
    fun `the ticker emits once at start and again only for a new date or zone`() =
        runTest {
            val days = mutableListOf<StatsDay>()
            backgroundScope.launch { ticker.days().toList(days) }
            runCurrent()

            // Another TimeZone instance with the same id is the same day.
            zones.zone = TimeZone.of("Europe/Berlin")
            signal.tick()
            runCurrent()
            zones.zone = TimeZone.of("Europe/London")
            signal.tick()
            runCurrent()

            assertEquals(listOf(StatsDay(today, "Europe/Berlin"), StatsDay(today, "Europe/London")), days)
        }
}
