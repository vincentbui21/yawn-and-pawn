package com.yawnandpawn.app.core.stats

import com.yawnandpawn.app.core.stats.WeeklySummaryDecision.AlreadyHandled
import com.yawnandpawn.app.core.stats.WeeklySummaryDecision.SkipLate
import com.yawnandpawn.app.core.stats.WeeklySummaryDecision.Summarise
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * Story 6.8: the WorkManager late-run skip rule. A run summarises the week of the latest Sunday 19:00 only within 24 h
 * of it and only once; later it skips the week, and a clock set back never summarises a week again.
 */
class WeeklySummaryDecisionTest {
    private val berlin = TimeZone.of("Europe/Berlin")
    private val sunday = LocalDate(2027, 3, 7)
    private val week = IsoWeek(2027, 9)

    private fun berlin(text: String): Instant = LocalDateTime.parse(text).toInstant(berlin)

    /** The decision at [now], checked against [weeklySummaryDue] on the way. */
    private fun decide(
        now: Instant,
        lastHandled: IsoWeek? = null,
        zone: TimeZone = berlin,
    ): WeeklySummaryDecision {
        val decision = weeklySummaryDecision(now, zone, lastHandled)
        assertEquals(decision is Summarise, weeklySummaryDue(now, zone, lastHandled), "due agrees with $decision")
        return decision
    }

    @Test
    fun `a run from Sunday 19 00 up to 24 h later summarises that week`() {
        listOf("2027-03-07T19:00", "2027-03-07T23:59", "2027-03-08T18:59", "2027-03-08T19:00").forEach { at ->
            assertEquals(Summarise(week, sunday), decide(berlin(at)), at)
        }
    }

    @Test
    fun `a run later than 24 h skips the week, a phone locked from boot until Tuesday too`() {
        assertEquals(SkipLate(week), decide(berlin("2027-03-08T19:01")))
        assertEquals(SkipLate(week), decide(berlin("2027-03-09T08:00")))
        assertEquals(SkipLate(week), decide(berlin("2027-03-14T18:59")))
    }

    @Test
    fun `a handled week is not summarised again`() {
        assertEquals(AlreadyHandled, decide(berlin("2027-03-07T19:30"), lastHandled = week))
        assertEquals(AlreadyHandled, decide(berlin("2027-03-09T08:00"), lastHandled = week))
        assertEquals(Summarise(IsoWeek(2027, 10), LocalDate(2027, 3, 14)), decide(berlin("2027-03-14T19:00"), lastHandled = week))
    }

    @Test
    fun `a clock set back before Sunday 19 00 never summarises this week, nor an older one again`() {
        val justBefore = berlin("2027-03-07T18:59")

        assertEquals(SkipLate(IsoWeek(2027, 8)), decide(justBefore))
        assertEquals(AlreadyHandled, decide(justBefore, lastHandled = IsoWeek(2027, 8)))
        // Set back after this week's run: the week before is not posted twice.
        assertEquals(AlreadyHandled, decide(justBefore, lastHandled = week))
        assertEquals(AlreadyHandled, decide(berlin("2027-03-01T10:00"), lastHandled = week))
    }

    @Test
    fun `a handled week far ahead is left from a clock set forward, and is ignored`() {
        assertEquals(Summarise(week, sunday), decide(berlin("2027-03-07T19:00"), lastHandled = IsoWeek(2030, 10)))
        assertEquals(Summarise(week, sunday), decide(berlin("2027-03-07T19:00"), lastHandled = IsoWeek(2027, 11)))
    }

    @Test
    fun `the run decides in the zone of now, not the one it was scheduled in`() {
        val now = Instant.parse("2027-03-07T17:30:00Z")

        // 18:30 in Berlin, before its Sunday 19:00; already Monday 00:30 in Ho Chi Minh City.
        assertEquals(SkipLate(IsoWeek(2027, 8)), decide(now, zone = berlin))
        assertEquals(Summarise(week, sunday), decide(now, zone = TimeZone.of("Asia/Ho_Chi_Minh")))
    }

    @Test
    fun `on the Berlin DST Sundays 24 h is elapsed time`() {
        // Spring forward 2027-03-28 (19:00 CEST = 17:00Z), fall back 2027-10-31 (19:00 CET = 18:00Z).
        assertEquals(Summarise(IsoWeek(2027, 12), LocalDate(2027, 3, 28)), decide(Instant.parse("2027-03-29T17:00:00Z")))
        assertEquals(SkipLate(IsoWeek(2027, 12)), decide(Instant.parse("2027-03-29T17:01:00Z")))
        assertEquals(Summarise(IsoWeek(2027, 43), LocalDate(2027, 10, 31)), decide(Instant.parse("2027-10-31T18:00:00Z")))
        assertEquals(Summarise(IsoWeek(2027, 43), LocalDate(2027, 10, 31)), decide(Instant.parse("2027-11-01T18:00:00Z")))
        assertEquals(SkipLate(IsoWeek(2027, 43)), decide(Instant.parse("2027-11-01T18:01:00Z")))
    }
}
