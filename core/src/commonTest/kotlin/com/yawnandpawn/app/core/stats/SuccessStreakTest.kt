package com.yawnandpawn.app.core.stats

import com.yawnandpawn.app.core.history.SessionHistoryRow
import com.yawnandpawn.app.core.history.SessionOutcome.Missed
import com.yawnandpawn.app.core.history.SessionOutcome.OnTime
import com.yawnandpawn.app.core.history.SessionOutcome.Skipped
import com.yawnandpawn.app.core.history.SessionOutcome.Snoozed
import com.yawnandpawn.app.core.history.SessionOutcome.Test
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Story 6.4: the streak before and after a completed zero-snooze session, with the Story 6.1 rules. */
class SuccessStreakTest {
    private val today = LocalDate(2027, 3, 12)
    private val zone = StatsZone

    private fun scheduled(
        day: LocalDate = today,
        at: LocalTime = LocalTime(6, 30),
        zone: TimeZone = this.zone,
    ): Instant = LocalDateTime(day, at).toInstant(zone)

    private fun streak(
        snapshot: List<SessionHistoryRow>,
        scheduledAt: Instant = scheduled(),
        today: LocalDate = this.today,
        zone: TimeZone = this.zone,
    ): SuccessStreak.Known = successStreak(snapshot, THIS, "alarm-1", scheduledAt, today, zone)

    /** [days] good days ending yesterday. */
    private fun goodDays(days: Int): List<SessionHistoryRow> = (1..days).map { aSession(today.day(-it)) }

    @Test
    fun `eleven good days ending yesterday grow to twelve`() {
        val result = streak(goodDays(11))

        assertEquals(SuccessStreak.Known(before = 11, after = 12), result)
        assertTrue(result.grew)
    }

    @Test
    fun `the first ever on-time morning grows from 0 to 1`() {
        val result = streak(emptyList())

        assertEquals(SuccessStreak.Known(before = 0, after = 1), result)
        assertTrue(result.grew)
    }

    @Test
    fun `a second on-time session on a day that is already good does not grow`() {
        val result = streak(goodDays(4) + aSession(today, at = LocalTime(5, 0)))

        assertEquals(SuccessStreak.Known(before = 5, after = 5), result)
        assertFalse(result.grew)
    }

    @Test
    fun `an earlier Snoozed or Missed session today leaves no streak`() {
        listOf(Snoozed, Missed).forEach { outcome ->
            val result = streak(goodDays(6) + aSession(today, outcome, at = LocalTime(5, 0)))

            assertEquals(0, result.after, "after an earlier $outcome session")
            assertFalse(result.grew, "after an earlier $outcome session")
        }
    }

    @Test
    fun `this session's own start or end row in the snapshot changes nothing`() {
        val history = goodDays(3)
        val start = aSession(today, outcome = null).copy(sessionId = THIS)
        val end = aSession(today).copy(sessionId = THIS)
        val endSnoozed = aSession(today, Snoozed).copy(sessionId = THIS)

        val expected = streak(history)
        assertEquals(SuccessStreak.Known(before = 3, after = 4), expected)
        assertEquals(expected, streak(history + start), "with its start row")
        assertEquals(expected, streak(history + end), "with its end row")
        assertEquals(expected, streak(history + endSnoozed), "any row of this session is dropped")
    }

    @Test
    fun `Test and Skipped rows change nothing`() {
        val history = goodDays(2)
        val noise = listOf(aSession(today, Test, at = LocalTime(5, 0)), aSession(today.day(-1), Skipped), aSession(today.day(-3), Test))

        assertEquals(streak(history), streak(history + noise))
    }

    @Test
    fun `a 23_50 alarm done at 00_10 belongs to the earlier day`() {
        val yesterday = today.day(-1)
        // Good days up to the day before yesterday; this session was scheduled yesterday at 23:50 and Success shows today.
        val history = (2..4).map { aSession(today.day(-it)) }

        val result = streak(history, scheduledAt = scheduled(yesterday, LocalTime(23, 50)))

        assertEquals(SuccessStreak.Known(before = 3, after = 4), result)
        // A Snoozed session yesterday morning shares its day: counted for today, it would have made a streak of 1.
        val snoozedYesterday = aSession(yesterday, Snoozed, at = LocalTime(7, 0))
        assertEquals(0, streak(history + snoozedYesterday, scheduledAt = scheduled(yesterday, LocalTime(23, 50))).after)
    }

    @Test
    fun `the spring DST day in Berlin is one day`() {
        val dstDay = LocalDate(2027, 3, 28)
        val history = listOf(aSession(dstDay.day(-1)), aSession(dstDay, at = LocalTime(0, 30)))

        val result = streak(history, scheduledAt = scheduled(dstDay, LocalTime(2, 30)), today = dstDay)

        assertEquals(SuccessStreak.Known(before = 2, after = 2), result)
    }

    @Test
    fun `a zone change from Ho Chi Minh to London moves a 06_30 session to the previous date`() {
        val hoChiMinh = TimeZone.of("Asia/Ho_Chi_Minh")
        val london = TimeZone.of("Europe/London")
        val day = LocalDate(2027, 1, 15)
        // A good day before, in London dates; the session at 06:30 Ho Chi Minh is 23:30 the day before in London.
        val history = listOf(aSession(day.day(-2), zone = london))
        val at = scheduled(day, LocalTime(6, 30), hoChiMinh)

        assertEquals(SuccessStreak.Known(before = 1, after = 2), streak(history, at, today = day, zone = london))
        // The same session in Ho Chi Minh dates is on `day`; the day between has nothing counted, so the run goes on.
        assertEquals(SuccessStreak.Known(before = 1, after = 2), streak(history, at, today = day, zone = hoChiMinh))
        // A Missed session on the previous date shares the session's London day (no streak), but not its Ho Chi Minh day.
        val missed = aSession(day.day(-1), Missed, at = LocalTime(7, 0), zone = london)
        assertEquals(0, streak(history + missed, at, today = day, zone = london).after)
        assertEquals(1, streak(history + missed, at, today = day, zone = hoChiMinh).after)
    }

    @Test
    fun `NONE is a known zero that did not grow`() {
        assertEquals(SuccessStreak.Known(before = 0, after = 0), SuccessStreak.NONE)
        assertFalse(SuccessStreak.NONE.grew)
    }

    private companion object {
        const val THIS = "this-session"
    }
}
