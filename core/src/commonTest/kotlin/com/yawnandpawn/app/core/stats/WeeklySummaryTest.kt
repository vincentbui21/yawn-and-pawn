package com.yawnandpawn.app.core.stats

import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.billing.PriceSource
import com.yawnandpawn.app.core.billing.PurchaseRecord
import com.yawnandpawn.app.core.billing.RecordStatus
import com.yawnandpawn.app.core.history.SessionHistoryRow
import com.yawnandpawn.app.core.history.SessionOutcome.Missed
import com.yawnandpawn.app.core.history.SessionOutcome.OnTime
import com.yawnandpawn.app.core.history.SessionOutcome.Skipped
import com.yawnandpawn.app.core.history.SessionOutcome.Snoozed
import com.yawnandpawn.app.core.history.SessionOutcome.Test
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** Story 6.8: what one ISO week says in the weekly summary: on-time mornings (good days) and the money paid. */
class WeeklySummaryTest {
    /** Monday 2027-03-01 to Sunday 2027-03-07 (W09), in Berlin. */
    private val monday = LocalDate(2027, 3, 1)
    private val sunday = LocalDate(2027, 3, 7)
    private val week = IsoWeek(2027, 9)

    private fun summary(
        vararg sessions: SessionHistoryRow,
        records: List<PurchaseRecord> = emptyList(),
        weekOf: LocalDate = sunday,
        zone: TimeZone = StatsZone,
    ) = weeklySummary(sessions.toList(), records, weekOf, zone)

    @Test
    fun `a week with no counted session has nothing to send`() {
        assertEquals(WeeklySummary(week, onTime = 0, paid = emptyList(), shouldSend = false), summary())
        assertFalse(summary(aSession(monday, Test), aSession(sunday, Test)).shouldSend)
        assertFalse(summary(aSession(monday, Skipped)).shouldSend)
        assertFalse(summary(aSession(monday, outcome = null)).shouldSend, "a running session is not counted")
    }

    @Test
    fun `a missed week is sent with no on-time morning`() {
        val missed = summary(aSession(monday, Missed), aSession(sunday, Missed))

        assertEquals(WeeklySummary(week, onTime = 0, paid = emptyList(), shouldSend = true), missed)
        assertEquals(WeeklySummaryText.NoOnTime, missed.text())
    }

    @Test
    fun `on-time mornings are good days, so two on-time alarms on one morning count once`() {
        assertEquals(1, summary(aSession(monday), aSession(monday, at = LocalTime(7, 0))).onTime)
        assertEquals(0, summary(aSession(monday), aSession(monday, Snoozed, at = LocalTime(7, 0))).onTime)
        assertEquals(2, summary(aSession(monday), aSession(monday.day(2)), aSession(monday.day(3), Missed), aSession(sunday, Test)).onTime)
    }

    @Test
    fun `the week runs from Monday 00 00 to Sunday 24 00 local, by the day the alarm was scheduled`() {
        assertEquals(1, summary(aSession(sunday, at = LocalTime(23, 59))).onTime)
        assertEquals(0, summary(aSession(sunday.day(1), at = LocalTime(0, 0))).onTime)
        assertEquals(0, summary(aSession(monday.day(-1), at = LocalTime(23, 59))).onTime)
        // A 23:50 Sunday alarm finished at 00:10 on Monday belongs to Sunday's week.
        assertEquals(1, summary(aSession(sunday, at = LocalTime(23, 50), timeToUp = 20.minutes)).onTime)
        // Any date of the week gives the same summary.
        assertEquals(summary(aSession(monday)), summary(aSession(monday), weekOf = monday.day(3)))
    }

    @Test
    fun `the Berlin DST weeks still have seven mornings`() {
        listOf(LocalDate(2027, 3, 22), LocalDate(2027, 10, 25)).forEach { start ->
            val sessions = (0 until 7).map { aSession(start.plus(it, DateTimeUnit.DAY)) }.toTypedArray()

            assertEquals(7, summary(*sessions, weekOf = start).onTime, "week of $start")
        }
    }

    @Test
    fun `a zone change moves a Monday 06 30 session into the previous week`() {
        // Monday 2027-03-08 06:30 in Berlin is Sunday 21:30 in Los Angeles.
        val session = aSession(sunday.day(1))

        assertEquals(0, summary(session).onTime)
        assertEquals(1, summary(session, zone = TimeZone.of("America/Los_Angeles")).onTime)
    }

    @Test
    fun `money is paid per currency, sorted by code, never across currencies (money)`() {
        val records =
            listOf(
                aRecord("s1", eur(2_990_000), LocalDateTime(monday, LocalTime(6, 35))),
                aRecord("s2", usd(1_000_000), LocalDateTime(monday.day(2), LocalTime(6, 35))),
                aRecord("s3", eur(5_990_000), LocalDateTime(sunday, LocalTime(7, 5))),
            )

        val paid = summary(aSession(monday, Snoozed), records = records)

        assertEquals(listOf(eur(8_980_000), usd(1_000_000)), paid.paid)
        assertEquals(WeeklySummaryText.Paid(0, paid.paid), paid.text())
    }

    @Test
    fun `stranded payments and estimated prices never count (money)`() {
        val records =
            listOf(
                aRecord("s1", eur(2_990_000), LocalDateTime(monday, LocalTime(6, 35)), status = RecordStatus.Stranded),
                aRecord("s2", eur(1_990_000), LocalDateTime(monday, LocalTime(6, 40)), source = PriceSource.Tier),
                aRecord("s3", eur(990_000), LocalDateTime(monday, LocalTime(6, 45)), status = RecordStatus.Granted),
            )

        assertEquals(listOf(eur(990_000)), summary(aSession(monday, Snoozed), records = records).paid)
    }

    @Test
    fun `a payment at Monday 00 00 counts in the new week (money)`() {
        val records = listOf(aRecord("s1", usd(1_000_000), LocalDateTime(sunday.day(1), LocalTime(0, 0))))

        assertEquals(emptyList(), summary(aSession(sunday), records = records).paid)
        assertEquals(listOf(usd(1_000_000)), summary(aSession(sunday.day(1)), records = records, weekOf = sunday.day(1)).paid)
    }

    @Test
    fun `the text follows the on-time count and what was paid`() {
        fun text(
            onTime: Int,
            paid: List<Money> = emptyList(),
        ) = WeeklySummary(week, onTime, paid, shouldSend = true).text()

        assertEquals(WeeklySummaryText.NothingPaid(1), text(1))
        assertEquals(WeeklySummaryText.NothingPaid(5), text(5))
        assertEquals(WeeklySummaryText.NoOnTime, text(0))
        // (money)
        assertEquals(WeeklySummaryText.Paid(0, listOf(usd(1_000_000))), text(0, listOf(usd(1_000_000))))
        assertEquals(WeeklySummaryText.Paid(1, listOf(usd(1_000_000))), text(1, listOf(usd(1_000_000))))
        assertEquals(
            WeeklySummaryText.Paid(3, listOf(eur(8_980_000), usd(1_000_000))),
            text(3, listOf(eur(8_980_000), usd(1_000_000))),
        )
        assertTrue(text(7).let { it is WeeklySummaryText.NothingPaid && it.onTime == 7 })
    }
}
