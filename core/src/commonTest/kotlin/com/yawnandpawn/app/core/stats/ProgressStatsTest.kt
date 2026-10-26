package com.yawnandpawn.app.core.stats

import com.yawnandpawn.app.core.billing.PriceSource
import com.yawnandpawn.app.core.billing.PurchaseRecordsRead
import com.yawnandpawn.app.core.billing.RecordStatus
import com.yawnandpawn.app.core.history.SessionOutcome.Missed
import com.yawnandpawn.app.core.history.SessionOutcome.OnTime
import com.yawnandpawn.app.core.history.SessionOutcome.Skipped
import com.yawnandpawn.app.core.history.SessionOutcome.Snoozed
import com.yawnandpawn.app.core.history.SessionOutcome.Test
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Story 6.2: snoozes over 30 days, money per currency, day outcomes, Insight and the ISO week, with the Epic 6 rules. */
class ProgressStatsTest {
    private val zone = StatsZone

    /** A Wednesday; the last 30 days are 2027-03-02 to 2027-03-31 and cross the DST change of 2027-03-28. */
    private val today = LocalDate(2027, 3, 31)

    // --- Snoozes over 30 days ---

    @Test
    fun `the 30th day back counts and the 31st does not`() {
        val history =
            listOf(
                aSession(today.day(-29), Snoozed, snoozes = 2),
                aSession(today.day(-30), Snoozed, snoozes = 5),
            )

        assertEquals(2, snoozesLast30Days(history, today, zone))
    }

    @Test
    fun `snoozes count over OnTime, Snoozed and Missed only`() {
        val history =
            listOf(
                aSession(today, Snoozed, snoozes = 2),
                aSession(today, Missed, snoozes = 3),
                aSession(today, Test, snoozes = 1),
                aSession(today, Skipped, snoozes = 0),
                aSession(today, outcome = null, snoozes = 1),
            )

        assertEquals(5, snoozesLast30Days(history, today, zone))
    }

    @Test
    fun `no counted session means no snoozes`() {
        assertEquals(0, snoozesLast30Days(emptyList(), today, zone))
        assertEquals(0, snoozesLast30Days(listOf(aSession(today, Test, snoozes = 2), aSession(today, Skipped)), today, zone))
    }

    @Test
    fun `a session after today is not in the window`() {
        assertEquals(0, snoozesLast30Days(listOf(aSession(today.day(1), Snoozed, snoozes = 4)), today, zone))
    }

    // --- Money ---

    @Test
    fun `money is one total per currency, in code order, for every period`() {
        val records =
            listOf(
                aRecord("s1", usd(1_000_000), at(today, 7, 0)),
                aRecord("s1", eur(2_990_000), at(today, 7, 1)),
                aRecord("s2", eur(5_990_000), at(today, 7, 2)),
            )

        MoneyPeriod.entries.forEach { period ->
            assertEquals(listOf(eur(8_980_000), usd(1_000_000)), moneyPaid(records, period, today, zone), "$period")
        }
    }

    @Test
    fun `granted, consumed and reused records count and stranded ones do not`() {
        val records =
            listOf(
                aRecord("s", eur(1), at(today, 7, 0), status = RecordStatus.Granted),
                aRecord("s", eur(2), at(today, 7, 0), status = RecordStatus.Consumed),
                aRecord("s", eur(4), at(today, 7, 0), status = RecordStatus.Reused),
                aRecord("s", eur(8), at(today, 7, 0), status = RecordStatus.Stranded),
            )

        assertEquals(listOf(eur(7)), moneyPaid(records, MoneyPeriod.AllTime, today, zone))
    }

    @Test
    fun `a reused payment, once stranded, counts as its one reused record`() {
        val reused = aRecord("s", eur(2_990_000), at(today, 7, 0), status = RecordStatus.Reused, tokenHash = "same")

        assertEquals(listOf(eur(2_990_000)), moneyPaid(listOf(reused), MoneyPeriod.ThisWeek, today, zone))
    }

    @Test
    fun `an estimated price is never counted as paid`() {
        listOf(PriceSource.Snapshot, PriceSource.Tier, PriceSource.Unknown).forEach { source ->
            val estimate = aRecord("s", eur(2_990_000), at(today, 7, 0), source = source)
            assertFalse(isPaidAmount(estimate), "$source")
            assertEquals(emptyList(), moneyPaid(listOf(estimate), MoneyPeriod.AllTime, today, zone), "$source")
        }
    }

    @Test
    fun `paid amount needs a counted status and the intent price`() {
        val expected =
            mapOf(
                RecordStatus.Granted to true,
                RecordStatus.Consumed to true,
                RecordStatus.Reused to true,
                RecordStatus.Stranded to false,
            )

        expected.forEach { (status, paid) ->
            assertEquals(paid, isPaidAmount(aRecord("s", eur(1), at(today, 7, 0), status = status)), "$status")
        }
    }

    @Test
    fun `nothing paid is an empty list, never a zero amount`() {
        val stranded = aRecord("s", eur(1), at(today, 7, 0), status = RecordStatus.Stranded)
        val estimate = aRecord("s", eur(1), at(today, 7, 0), source = PriceSource.Tier)

        listOf(emptyList(), listOf(stranded), listOf(stranded, estimate)).forEach { records ->
            MoneyPeriod.entries.forEach { period -> assertEquals(emptyList(), moneyPaid(records, period, today, zone)) }
        }
    }

    @Test
    fun `a purchase at 23_59 on the last day of a 25-hour month end counts in that month only`() {
        // 2027-10-31 is the Sunday the clocks go back in Berlin.
        val lastDay = LocalDate(2027, 10, 31)
        val october = aRecord("s", eur(1), at(lastDay, 23, 59))
        val november = aRecord("s", eur(2), at(LocalDate(2027, 11, 1), 0, 0))

        assertEquals(listOf(eur(1)), moneyPaid(listOf(october, november), MoneyPeriod.ThisMonth, lastDay, zone))
        assertEquals(listOf(eur(2)), moneyPaid(listOf(october, november), MoneyPeriod.ThisMonth, lastDay.day(1), zone))
    }

    @Test
    fun `a purchase at 00_00 on Monday is in the new week and 23_59 on Sunday in the old one`() {
        val monday = LocalDate(2027, 11, 1)
        val newWeek = aRecord("s", eur(1), at(monday, 0, 0))
        val oldWeek = aRecord("s", eur(2), at(monday.day(-1), 23, 59))

        assertEquals(listOf(eur(1)), moneyPaid(listOf(newWeek, oldWeek), MoneyPeriod.ThisWeek, monday, zone))
        assertEquals(listOf(eur(2)), moneyPaid(listOf(newWeek, oldWeek), MoneyPeriod.ThisWeek, monday.day(-1), zone))
    }

    @Test
    fun `all time sums years of records per currency`() {
        val records =
            (0 until 36).flatMap { month ->
                val day = LocalDate(2025, 1, 15).day(month * 30)
                listOf(aRecord("s$month", eur(1_000_000), at(day, 7, 0)), aRecord("s$month", usd(500_000), at(day, 7, 5)))
            }

        assertEquals(listOf(eur(36_000_000), usd(18_000_000)), moneyPaid(records, MoneyPeriod.AllTime, today, zone))
        // March of 2025 and 2026 hold records too: only March 2027 is this month.
        assertEquals(listOf(eur(1_000_000), usd(500_000)), moneyPaid(records, MoneyPeriod.ThisMonth, today, zone))
        assertEquals(emptyList(), moneyPaid(records, MoneyPeriod.ThisWeek, LocalDate(2030, 1, 1), zone))
    }

    @Test
    fun `a purchase made in another zone falls on its local date in the current zone`() {
        val saigon = TimeZone.of("Asia/Ho_Chi_Minh")
        val london = TimeZone.of("Europe/London")
        val purchase = aRecord("s", eur(1), at(LocalDate(2027, 1, 15), 6, 30), zone = saigon)

        assertEquals(LocalDate(2027, 1, 14), purchase.purchasedAt.toLocalDateTime(london).date)
        assertEquals(listOf(eur(1)), moneyPaid(listOf(purchase), MoneyPeriod.ThisWeek, LocalDate(2027, 1, 15), london))

        // On a month boundary the local date decides: 06:30 on 1 February in Saigon is 31 January in London.
        val february = aRecord("s", eur(2), at(LocalDate(2027, 2, 1), 6, 30), zone = saigon)
        assertEquals(emptyList(), moneyPaid(listOf(february), MoneyPeriod.ThisMonth, LocalDate(2027, 2, 1), london))
        assertEquals(listOf(eur(2)), moneyPaid(listOf(february), MoneyPeriod.ThisMonth, LocalDate(2027, 1, 31), london))
        assertEquals(listOf(eur(2)), moneyPaid(listOf(february), MoneyPeriod.ThisMonth, LocalDate(2027, 2, 1), saigon))
    }

    // --- ISO week ---

    @Test
    fun `the ISO week runs Monday through Sunday, across a year end too`() {
        val monday = LocalDate(2027, 11, 1)
        assertEquals(monday..LocalDate(2027, 11, 7), isoWeekOf(monday))
        assertEquals(monday..LocalDate(2027, 11, 7), isoWeekOf(LocalDate(2027, 11, 7)))
        assertEquals(monday..LocalDate(2027, 11, 7), isoWeekOf(LocalDate(2027, 11, 4)))
        // 2027-01-01 is a Friday.
        assertEquals(LocalDate(2026, 12, 28)..LocalDate(2027, 1, 3), isoWeekOf(LocalDate(2027, 1, 1)))
    }

    // --- Day outcomes ---

    @Test
    fun `a day shows its worst counted outcome`() {
        val cases =
            mapOf(
                listOf(OnTime, Snoozed, Missed) to DayOutcome.Missed,
                listOf(Missed, OnTime) to DayOutcome.Missed,
                listOf(OnTime, Snoozed) to DayOutcome.Snoozed,
                listOf(OnTime) to DayOutcome.OnTime,
            )

        cases.forEach { (outcomes, expected) ->
            val history = outcomes.mapIndexed { i, outcome -> aSession(today, outcome, at = LocalTime(6, i)) }
            assertEquals(DaySummary(today, expected, outcomes.size, fallbackUsed = false), dayOutcome(history, today, zone))
        }
    }

    @Test
    fun `a day with only Test or Skipped is neutral, Skipped first`() {
        val cases =
            mapOf(
                listOf(Skipped, Test) to DayOutcome.Skipped,
                listOf(Test) to DayOutcome.Test,
                listOf(Skipped) to DayOutcome.Skipped,
            )

        cases.forEach { (outcomes, expected) ->
            val history = outcomes.map { aSession(today, it) }
            assertEquals(expected, dayOutcome(history, today, zone).outcome, "$outcomes")
        }
    }

    @Test
    fun `a day with no finished session is None`() {
        assertEquals(DaySummary(today, DayOutcome.None, 0, false), dayOutcome(emptyList(), today, zone))
        assertEquals(DaySummary(today, DayOutcome.None, 0, false), dayOutcome(listOf(aSession(today, outcome = null)), today, zone))
    }

    @Test
    fun `a counted session wins over Test and every finished session is counted in the day`() {
        val history = listOf(aSession(today, OnTime), aSession(today, Test), aSession(today, outcome = null))

        assertEquals(DaySummary(today, DayOutcome.OnTime, 2, false), dayOutcome(history, today, zone))
    }

    @Test
    fun `any finished session with the fallback marks the day`() {
        val history = listOf(aSession(today, OnTime), aSession(today, Test, fallbackUsed = true))

        assertTrue(dayOutcome(history, today, zone).fallbackUsed)
        // A running session's fallback does not count yet.
        assertFalse(dayOutcome(listOf(aSession(today, outcome = null, fallbackUsed = true)), today, zone).fallbackUsed)
    }

    @Test
    fun `a 23_50 alarm finished at 00_10 belongs to the earlier day`() {
        val history = listOf(aSession(today, Snoozed, at = LocalTime(23, 50), timeToUp = 20.minutes))

        assertEquals(DayOutcome.Snoozed, dayOutcome(history, today, zone).outcome)
        assertEquals(DayOutcome.None, dayOutcome(history, today.day(1), zone).outcome)
    }

    @Test
    fun `a month has one summary per date, leap February and a DST March included`() {
        val february = monthSummary(emptyList(), YearMonth(2028, Month.FEBRUARY), zone)
        assertEquals(29, february.size)
        assertEquals(LocalDate(2028, 2, 1), february.first().date)
        assertEquals(LocalDate(2028, 2, 29), february.last().date)

        val history =
            listOf(
                aSession(LocalDate(2027, 3, 28), Missed, at = LocalTime(3, 30)),
                aSession(LocalDate(2027, 3, 31), OnTime, at = LocalTime(23, 59)),
                aSession(LocalDate(2027, 4, 1), Snoozed, at = LocalTime(0, 0)),
                aSession(LocalDate(2027, 2, 28), Snoozed, at = LocalTime(23, 59)),
            )
        val march = monthSummary(history, YearMonth(2027, Month.MARCH), zone)
        assertEquals(31, march.size)
        assertEquals((1..31).map { LocalDate(2027, 3, it) }, march.map { it.date })
        assertEquals(
            mapOf(LocalDate(2027, 3, 28) to DayOutcome.Missed, LocalDate(2027, 3, 31) to DayOutcome.OnTime),
            march.filter { it.outcome != DayOutcome.None }.associate { it.date to it.outcome },
        )
    }

    @Test
    fun `day summaries cover a range in order, and an empty range gives none`() {
        val history = listOf(aSession(today.day(-29), Missed), aSession(today, OnTime))
        val ring = daySummaries(history, today.day(-29)..today, zone)

        assertEquals(30, ring.size)
        assertEquals(DayOutcome.Missed, ring.first().outcome)
        assertEquals(DayOutcome.OnTime, ring.last().outcome)
        assertEquals(28, ring.count { it.outcome == DayOutcome.None })
        assertEquals(emptyList(), daySummaries(history, today..today.day(-1), zone))
    }

    @Test
    fun `the snapshot's last mornings are the 30 days ending today, oldest first`() {
        val history = listOf(aSession(today.day(-30), Missed), aSession(today.day(-29), Snoozed), aSession(today, OnTime))
        val ring = progressSnapshot(history, PurchaseRecordsRead(emptyList()), today, zone).lastMornings

        assertEquals(ProgressRule.RING_DAYS, ring.size)
        assertEquals(today.day(-29), ring.first().date)
        assertEquals(today, ring.last().date)
        assertEquals(DayOutcome.Snoozed, ring.first().outcome)
        assertEquals(DayOutcome.OnTime, ring.last().outcome)
        assertEquals(28, ring.count { it.outcome == DayOutcome.None })
    }

    // --- Sessions on a day ---

    @Test
    fun `sessions on a day are in scheduled order with their paid and stranded records apart`() {
        val late = aSession(today, OnTime, at = LocalTime(7, 0))
        val early = aSession(today, Snoozed, at = LocalTime(6, 0))
        val running = aSession(today, outcome = null, at = LocalTime(8, 0))
        val otherDay = aSession(today.day(-1), Snoozed)
        val consumed = aRecord(early.sessionId, eur(1_000_000), at(today, 6, 5))
        val reused = aRecord(early.sessionId, eur(2_000_000), at(today, 6, 15), status = RecordStatus.Reused)
        val stranded = aRecord(early.sessionId, eur(4_000_000), at(today, 6, 10), status = RecordStatus.Stranded)
        val estimate = aRecord(early.sessionId, usd(3_000_000), at(today, 6, 1), source = PriceSource.Snapshot)
        val lateBuy = aRecord(late.sessionId, usd(1_000_000), at(today, 7, 1))
        val records =
            listOf(
                reused,
                stranded,
                consumed,
                estimate,
                lateBuy,
                aRecord(otherDay.sessionId, eur(9_000_000), at(today.day(-1), 6, 5)),
                aRecord(null, eur(9_000_000), at(today, 6, 5), status = RecordStatus.Stranded),
            )

        val sessions = sessionsOn(listOf(late, running, otherDay, early), records, today, zone)

        assertEquals(
            listOf(
                SessionOnDay(
                    early,
                    purchases = listOf(estimate, consumed, reused),
                    paid = listOf(eur(3_000_000)),
                    stranded = listOf(stranded),
                ),
                SessionOnDay(late, purchases = listOf(lateBuy), paid = listOf(usd(1_000_000)), stranded = emptyList()),
            ),
            sessions,
        )
    }

    @Test
    fun `a day without finished sessions lists none`() {
        assertEquals(emptyList(), sessionsOn(listOf(aSession(today, outcome = null)), emptyList(), today, zone))
        assertEquals(emptyList(), sessionsOn(emptyList(), emptyList(), today, zone))
    }

    // --- Insight ---

    @Test
    fun `faster weekday mornings give the weekday line`() {
        val history = weekdays(4, 3.minutes.inWholeSeconds) + weekends(3, 6.minutes.inWholeSeconds)

        assertEquals(InsightLine.FastestOnWeekdays, insight(history, today, zone))
    }

    @Test
    fun `faster weekend mornings give the weekend line`() {
        val history = weekdays(3, 8.minutes.inWholeSeconds) + weekends(3, 5.minutes.inWholeSeconds)

        assertEquals(InsightLine.FastestOnWeekends, insight(history, today, zone))
    }

    @Test
    fun `thin data or a small gap gives no line`() {
        assertNull(insight(weekdays(4, 180) + weekends(2, 600), today, zone), "two weekend sessions")
        assertNull(insight(weekdays(2, 180) + weekends(3, 600), today, zone), "two weekday sessions")
        assertNull(insight(weekdays(3, 180) + weekends(3, 180 + 119), today, zone), "a gap of 1 min 59 s")
        assertNull(insight(weekdays(3, 180) + weekends(3, 180), today, zone), "equal averages")
        assertNull(insight(emptyList(), today, zone), "no data")
    }

    @Test
    fun `a gap of exactly 2 minutes shows the line`() {
        assertEquals(InsightLine.FastestOnWeekdays, insight(weekdays(3, 180) + weekends(3, 300), today, zone))
        assertEquals(InsightLine.FastestOnWeekends, insight(weekdays(3, 300) + weekends(3, 180), today, zone))
    }

    @Test
    fun `insight leaves out Missed, Test, Skipped, untimed and old sessions`() {
        val base = weekdays(3, 180) + weekends(2, 600)
        // 2027-03-27 is a Saturday in the window, 2027-02-28 a Sunday 31 days back and 2027-04-03 a Saturday after today.
        val saturday = LocalDate(2027, 3, 27)
        val ignored =
            listOf(
                aSession(saturday, Missed, timeToUp = 10.minutes),
                aSession(saturday, Test, timeToUp = 10.minutes),
                aSession(saturday, Skipped, timeToUp = 10.minutes),
                aSession(saturday, Snoozed, timeToUp = null),
                aSession(saturday, outcome = null),
                aSession(LocalDate(2027, 2, 28), OnTime, timeToUp = 10.minutes),
                aSession(LocalDate(2027, 4, 3), OnTime, timeToUp = 10.minutes),
            )

        assertNull(insight(base + ignored, today, zone))
        // One more timed weekend session is enough: the ignored rows did not count.
        val oneMore = aSession(saturday, Snoozed, timeToUp = 10.minutes)
        assertEquals(InsightLine.FastestOnWeekdays, insight(base + ignored + oneMore, today, zone))
    }

    @Test
    fun `the 30th day back counts for insight and the 31st does not`() {
        val thin = weekdays(2, 180) + weekends(3, 600)

        // Tuesday 2027-03-02 is day -29, Monday 2027-03-01 day -30.
        assertEquals(InsightLine.FastestOnWeekdays, insight(thin + aSession(today.day(-29), OnTime, timeToUp = 3.minutes), today, zone))
        assertNull(insight(thin + aSession(today.day(-30), OnTime, timeToUp = 3.minutes), today, zone))
    }

    @Test
    fun `insight days follow the given zone`() {
        // Saturday 00:30 in Berlin is still Friday 23:30 in London: a weekday there.
        val earlySaturday = (1..3).map { aSession(LocalDate(2027, 3, 27), OnTime, at = LocalTime(0, 30), timeToUp = 10.minutes) }
        val berlin = weekdays(3, 180) + earlySaturday

        assertEquals(InsightLine.FastestOnWeekdays, insight(berlin, today, zone))
        assertNull(insight(berlin, today, TimeZone.of("Europe/London")))
    }

    /** [count] timed OnTime sessions on weekdays of the window (Mon 03-29, Tue 03-30, Wed 03-31, Fri 03-26), [seconds] each. */
    private fun weekdays(
        count: Int,
        seconds: Long,
    ) = List(count) { i -> aSession(weekdayDates[i % weekdayDates.size], OnTime, at = LocalTime(6, i), timeToUp = seconds.seconds) }

    /** [count] timed Snoozed sessions on weekend days of the window (Sat 03-27, Sun 03-28, Sun 03-21), [seconds] each. */
    private fun weekends(
        count: Int,
        seconds: Long,
    ) = List(count) { i -> aSession(weekendDates[i % weekendDates.size], Snoozed, at = LocalTime(9, i), timeToUp = seconds.seconds) }

    private val weekdayDates = listOf(LocalDate(2027, 3, 29), LocalDate(2027, 3, 30), LocalDate(2027, 3, 31), LocalDate(2027, 3, 26))
    private val weekendDates = listOf(LocalDate(2027, 3, 27), LocalDate(2027, 3, 28), LocalDate(2027, 3, 21))

    private fun at(
        date: LocalDate,
        hour: Int,
        minute: Int,
    ) = LocalDateTime(date, LocalTime(hour, minute))
}
