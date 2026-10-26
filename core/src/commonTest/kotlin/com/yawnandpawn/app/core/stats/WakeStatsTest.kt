package com.yawnandpawn.app.core.stats

import com.yawnandpawn.app.core.history.SessionOutcome.Missed
import com.yawnandpawn.app.core.history.SessionOutcome.OnTime
import com.yawnandpawn.app.core.history.SessionOutcome.Skipped
import com.yawnandpawn.app.core.history.SessionOutcome.Snoozed
import com.yawnandpawn.app.core.history.SessionOutcome.Test
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Story 6.1: the day model, streaks, on-time rate and average time to get up, with the Epic 6 stats rules. */
class WakeStatsTest {
    /** A Monday. */
    private val monday = LocalDate(2027, 3, 1)
    private val zone = StatsZone

    // --- Day model ---

    @Test
    fun `the day of a session is the local date of scheduled_at, even when it ends after midnight`() {
        val lateAlarm = aSession(monday, at = LocalTime(23, 50), timeToUp = 20.minutes)

        assertEquals(monday, sessionDay(lateAlarm, zone))
        assertEquals(LocalTime(0, 10), lateAlarm.endedAt?.toLocalDateTime(zone)?.time)
    }

    @Test
    fun `a 23_50 alarm finished at 00_10 counts for the earlier day`() {
        val history = listOf(aSession(monday, at = LocalTime(23, 50), timeToUp = 20.minutes))

        assertEquals(1, currentStreak(history, today = monday.day(1), zone))
        assertEquals(100, onTimeRate(history, today = monday, zone, days = 1))
        assertNull(onTimeRate(history, today = monday.day(1), zone, days = 1))
    }

    @Test
    fun `only OnTime, Snoozed and Missed are counted`() {
        val expected = mapOf(OnTime to true, Snoozed to true, Missed to true, Skipped to false, Test to false, null to false)

        expected.forEach { (outcome, counted) ->
            assertEquals(counted, isCounted(aSession(monday, outcome)), "outcome $outcome")
        }
    }

    // --- Streaks ---

    @Test
    fun `empty history has no streak`() {
        assertEquals(0, currentStreak(emptyList(), monday, zone))
        assertEquals(0, bestStreak(emptyList(), zone))
    }

    @Test
    fun `five on-time weekdays with no weekend alarms make a streak of 5`() {
        val history = (0..4).map { aSession(monday.day(it)) }

        assertEquals(5, currentStreak(history, today = monday.day(6), zone))
        assertEquals(5, bestStreak(history, zone))
    }

    @Test
    fun `weeks without a counted session keep the streak, skipped or not`() {
        val history =
            listOf(aSession(monday), aSession(monday.day(1))) +
                (2..20).map { aSession(monday.day(it), Skipped) } +
                aSession(monday.day(40))

        assertEquals(2, currentStreak(history, today = monday.day(39), zone))
        assertEquals(3, currentStreak(history, today = monday.day(60), zone))
        assertEquals(3, bestStreak(history, zone))
    }

    @Test
    fun `a Missed day ends the run`() {
        val history = listOf(aSession(monday), aSession(monday.day(1), Missed), aSession(monday.day(2)))

        assertEquals(1, currentStreak(history, today = monday.day(2), zone))
        assertEquals(1, bestStreak(history, zone))
    }

    @Test
    fun `one Snoozed session breaks a day that also has an on-time session`() {
        val history =
            listOf(
                aSession(monday),
                aSession(monday.day(1), OnTime, at = LocalTime(6, 0)),
                aSession(monday.day(1), Snoozed, at = LocalTime(7, 0)),
                aSession(monday.day(2)),
            )

        assertEquals(1, currentStreak(history, today = monday.day(2), zone))
        assertEquals(1, bestStreak(history, zone))
    }

    @Test
    fun `a day with only Test or only Skipped sessions neither extends nor breaks the streak`() {
        listOf(Test, Skipped).forEach { neutral ->
            val history = listOf(aSession(monday), aSession(monday.day(1), neutral), aSession(monday.day(2)))

            assertEquals(2, currentStreak(history, today = monday.day(2), zone), "$neutral day")
            assertEquals(2, bestStreak(history, zone), "$neutral day")
        }
    }

    @Test
    fun `snoozing today after a 9-day run resets the current streak but keeps the best`() {
        val history = (0..8).map { aSession(monday.day(it)) } + aSession(monday.day(9), Snoozed)

        assertEquals(0, currentStreak(history, today = monday.day(9), zone))
        assertEquals(9, bestStreak(history, zone))
    }

    @Test
    fun `a session still in progress today is ignored`() {
        val run = (0..2).map { aSession(monday.day(it)) }
        val history = run + aSession(monday.day(3), outcome = null)

        assertEquals(3, currentStreak(run, today = monday.day(3), zone))
        assertEquals(3, currentStreak(history, today = monday.day(3), zone))
        assertEquals(3, bestStreak(history, zone))
    }

    @Test
    fun `days after today are ignored by the current streak`() {
        val history = (0..2).map { aSession(monday.day(it)) } + aSession(monday.day(4), Missed)

        assertEquals(3, currentStreak(history, today = monday.day(3), zone))
        assertEquals(0, currentStreak(history, today = monday.day(4), zone))
        assertEquals(3, bestStreak(history, zone))
    }

    @Test
    fun `the best streak can be an older run`() {
        val history =
            (0..3).map { aSession(monday.day(it)) } +
                aSession(monday.day(4), Missed) +
                (5..6).map { aSession(monday.day(it)) }

        assertEquals(2, currentStreak(history, today = monday.day(6), zone))
        assertEquals(4, bestStreak(history, zone))
    }

    // --- On-time rate ---

    @Test
    fun `on-time rate is OnTime over counted, rounded half up to a whole percent`() {
        val cases =
            listOf(
                listOf(OnTime, OnTime, OnTime, OnTime, OnTime, OnTime, Snoozed) to 86,
                listOf(OnTime, Snoozed, Missed) to 33,
                listOf(OnTime, OnTime, Missed) to 67,
                listOf(Missed, Missed) to 0,
                listOf(OnTime, Snoozed, Snoozed, Snoozed, Snoozed, Snoozed, Snoozed, Missed) to 13,
                listOf(OnTime, OnTime, OnTime, OnTime, OnTime, OnTime, OnTime, Missed) to 88,
                listOf(OnTime, Test, Skipped, null) to 100,
            )

        cases.forEach { (outcomes, rate) ->
            val history = outcomes.mapIndexed { i, outcome -> aSession(monday.day(i % 7), outcome) }
            assertEquals(rate, onTimeRate(history, today = monday.day(6), zone, days = 7), "$outcomes")
        }
    }

    @Test
    fun `on-time rate is null, not 0, without a counted session in the window`() {
        val history = listOf(aSession(monday, Test), aSession(monday.day(1), Skipped), aSession(monday.day(2), null))

        assertNull(onTimeRate(history, today = monday.day(2), zone, days = 7))
        assertNull(onTimeRate(emptyList(), today = monday, zone, days = 30))
    }

    @Test
    fun `the window runs from today minus days - 1 through today`() {
        val today = monday.day(10)
        val firstDay = aSession(today.day(-6))
        val dayBefore = aSession(today.day(-7), Missed)
        val tomorrow = aSession(today.day(1), Missed)

        assertEquals(100, onTimeRate(listOf(firstDay, dayBefore, tomorrow), today, zone, days = 7))
        assertEquals(50, onTimeRate(listOf(firstDay, dayBefore), today, zone, days = 8))
        assertEquals(100, onTimeRate(listOf(firstDay, dayBefore), today, zone, days = WakeStats.RATE_DAYS_SHORT))
        assertEquals(50, onTimeRate(listOf(firstDay, dayBefore), today, zone, days = WakeStats.RATE_DAYS_LONG))
    }

    @Test
    fun `a window under one day is a programming error`() {
        assertFailsWith<IllegalArgumentException> { onTimeRate(emptyList(), monday, zone, days = 0) }
    }

    // --- Average time to get up ---

    @Test
    fun `average time to up is the mean over OnTime and Snoozed sessions`() {
        val history =
            listOf(
                aSession(monday, OnTime, timeToUp = 2.minutes),
                aSession(monday.day(1), Snoozed, timeToUp = 10.minutes),
                aSession(monday.day(2), Missed, timeToUp = 30.minutes),
                aSession(monday.day(3), Test, timeToUp = 1.minutes),
                aSession(monday.day(4), Skipped, timeToUp = 1.minutes),
                aSession(monday.day(5), OnTime, timeToUp = null),
                aSession(monday.day(6), outcome = null),
            )

        assertEquals(6.minutes, averageTimeToUp(history, today = monday.day(6), zone))
    }

    @Test
    fun `average time to up keeps the millisecond mean`() {
        val history = listOf(aSession(monday, timeToUp = 1.milliseconds), aSession(monday.day(1), timeToUp = 2.milliseconds))

        assertEquals(1_500.microseconds, averageTimeToUp(history, today = monday.day(1), zone))
    }

    @Test
    fun `average time to up covers the last 30 days only`() {
        val today = monday.day(40)
        val history =
            listOf(
                aSession(today.day(-29), timeToUp = 4.minutes),
                aSession(today.day(-30), timeToUp = 40.minutes),
                aSession(today.day(1), timeToUp = 40.minutes),
            )

        assertEquals(4.minutes, averageTimeToUp(history, today, zone))
    }

    @Test
    fun `average time to up is null without an on-time or snoozed session`() {
        assertNull(averageTimeToUp(emptyList(), monday, zone))
        assertNull(averageTimeToUp(listOf(aSession(monday, Missed), aSession(monday, Test)), monday, zone))
        assertNull(averageTimeToUp(listOf(aSession(monday, OnTime, timeToUp = null)), monday, zone))
    }

    @Test
    fun `whole minutes round half up and 0 means under a minute`() {
        val cases =
            listOf(
                0.seconds to 0,
                29.seconds to 0,
                29_999.milliseconds to 0,
                30.seconds to 1,
                2.minutes + 29.seconds to 2,
                2.minutes + 30.seconds to 3,
                (-5).minutes to 0,
                Duration.INFINITE to Int.MAX_VALUE,
            )

        cases.forEach { (duration, minutes) -> assertEquals(minutes, wholeMinutes(duration), "$duration") }
    }

    // --- DST and zone changes ---

    @Test
    fun `the spring DST day in Berlin is still one day`() {
        val dstDay = LocalDate(2027, 3, 28)
        val history =
            listOf(
                aSession(dstDay.day(-1)),
                aSession(dstDay, at = LocalTime(0, 30)),
                aSession(dstDay, at = LocalTime(2, 30)),
                aSession(dstDay, at = LocalTime(23, 30)),
                aSession(dstDay.day(1)),
            )

        history.subList(1, 4).forEach { assertEquals(dstDay, sessionDay(it, zone)) }
        assertEquals(3, currentStreak(history, today = dstDay.day(1), zone))
        assertEquals(3, bestStreak(history, zone))
        assertEquals(100, onTimeRate(history.subList(1, 4), today = dstDay, zone, days = 1))
    }

    @Test
    fun `windows across a DST change still hold whole local days`() {
        val today = LocalDate(2027, 3, 31)
        val history = listOf(aSession(today.day(-6), at = LocalTime(0, 5)), aSession(today.day(-7), Missed, at = LocalTime(23, 55)))

        assertEquals(100, onTimeRate(history, today, zone, days = 7))
        val autumn = LocalDate(2027, 10, 31)
        val autumnHistory = listOf(aSession(autumn, at = LocalTime(2, 30)), aSession(autumn, Snoozed, at = LocalTime(23, 30)))
        autumnHistory.forEach { assertEquals(autumn, sessionDay(it, zone)) }
        assertEquals(50, onTimeRate(autumnHistory, autumn, zone, days = 1))
        assertEquals(0, currentStreak(autumnHistory, autumn, zone))
    }

    @Test
    fun `a zone change from Ho Chi Minh to London moves a 06_30 session to the previous local date`() {
        val hoChiMinh = TimeZone.of("Asia/Ho_Chi_Minh")
        val london = TimeZone.of("Europe/London")
        val day = LocalDate(2027, 1, 15)
        val history = listOf(aSession(day, at = LocalTime(6, 30), zone = hoChiMinh))

        assertEquals(day, sessionDay(history.single(), hoChiMinh))
        assertEquals(day.day(-1), sessionDay(history.single(), london))
        assertEquals(1, currentStreak(history, today = day.day(-1), london))
        assertEquals(0, currentStreak(history, today = day.day(-1), hoChiMinh))
        assertEquals(100, onTimeRate(history, today = day.day(-1), london, days = 1))
        assertNull(onTimeRate(history, today = day.day(-1), hoChiMinh, days = 1))
        assertEquals(3.minutes, averageTimeToUp(history, today = day.day(-1), london))
        assertNull(averageTimeToUp(history, today = day.day(-1), hoChiMinh))
    }

    @Test
    fun `the streak follows the zone of the computation`() {
        val hoChiMinh = TimeZone.of("Asia/Ho_Chi_Minh")
        val london = TimeZone.of("Europe/London")
        // 06:30 and 23:00 Ho Chi Minh time on the same day: two different London days.
        val day = LocalDate(2027, 1, 15)
        val history =
            listOf(aSession(day, Snoozed, at = LocalTime(6, 30), zone = hoChiMinh), aSession(day, at = LocalTime(23, 0), zone = hoChiMinh))

        assertEquals(0, currentStreak(history, today = day, hoChiMinh))
        assertEquals(0, bestStreak(history, hoChiMinh))
        assertEquals(1, currentStreak(history, today = day, london))
        assertEquals(1, bestStreak(history, london))
    }
}
