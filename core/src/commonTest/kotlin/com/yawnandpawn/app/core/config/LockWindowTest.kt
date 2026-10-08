package com.yawnandpawn.app.core.config

import com.yawnandpawn.app.core.alarm.Alarm
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class LockWindowTest {
    private val occurrence = Instant.parse("2027-03-09T07:30:00Z")

    private fun alarm(
        id: String = "a",
        time: LocalTime = LocalTime(7, 30),
        enabled: Boolean = true,
    ) = Alarm(
        id = id,
        time = time,
        enabled = enabled,
        requestCode = 1_000,
        createdAt = Instant.parse("2027-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2027-01-01T00:00:00Z"),
    )

    @Test
    fun `the window is 0 exclusive to 8 h inclusive before the occurrence, on instants`() {
        val cases: List<Pair<Duration, Boolean>> =
            listOf(
                (7.hours + 59.minutes + 59.seconds) to true,
                8.hours to true,
                (8.hours + 1.seconds) to false,
                // Review 7: one millisecond past 8 h is outside.
                (8.hours + 1.milliseconds) to false,
                1.seconds to true,
                Duration.ZERO to false,
                (-1).seconds to false,
            )
        cases.forEach { (until, inside) ->
            assertEquals(inside, LockWindow.contains(occurrence, occurrence - until), "$until before the occurrence")
        }
    }

    @Test
    fun `an enabled alarm is in its window with its next occurrence, outside it with none`() {
        val now = occurrence - (7.hours + 59.minutes + 59.seconds)
        assertEquals(Occurrence("a", occurrence), LockWindow.of(alarm(), now, TimeZone.UTC))
        assertNull(LockWindow.of(alarm(), occurrence - (8.hours + 1.seconds), TimeZone.UTC))
    }

    @Test
    fun `a disabled alarm never locks`() {
        assertNull(LockWindow.of(alarm(enabled = false), occurrence - 1.hours, TimeZone.UTC))
        assertNull(LockWindow.latest(listOf(alarm(enabled = false)), occurrence - 1.hours, TimeZone.UTC))
    }

    @Test
    fun `on a DST-gap night the instant difference wins over the local one`() {
        // Helsinki springs forward on 2027-03-28 at 03:00 (to 04:00). From 22:00 on the 27th to 06:30 on the 28th is
        // 8 h 30 min on the wall clock but only 7 h 30 min of real time: inside the window.
        val helsinki = TimeZone.of("Europe/Helsinki")
        val now = Instant.parse("2027-03-27T20:00:00Z") // 22:00 local, UTC+2
        val ringsAt = Instant.parse("2027-03-28T03:30:00Z") // 06:30 local, UTC+3

        val locked = LockWindow.of(alarm(time = LocalTime(6, 30)), now, helsinki)

        assertEquals(Occurrence("a", ringsAt), locked)
        assertEquals(7.hours + 30.minutes, ringsAt - now)
    }

    @Test
    fun `a global setting waits for the latest occurrence inside the window`() {
        val now = Instant.parse("2027-03-08T23:40:00Z")
        val alarms =
            listOf(
                alarm(id = "early", time = LocalTime(1, 40)),
                alarm(id = "late", time = LocalTime(7, 30)),
                alarm(id = "outside", time = LocalTime(9, 0)),
                alarm(id = "off", time = LocalTime(7, 35), enabled = false),
            )

        assertEquals(Occurrence("late", Instant.parse("2027-03-09T07:30:00Z")), LockWindow.latest(alarms, now, TimeZone.UTC))
        assertNull(LockWindow.latest(listOf(alarm(id = "outside", time = LocalTime(9, 0))), now, TimeZone.UTC))
    }

    @Test
    fun `ties go to the greater alarm id, whatever the order`() {
        val first = Occurrence("a", occurrence)
        val second = Occurrence("b", occurrence)

        assertEquals(second, LockWindow.latestOf(listOf(first, second)))
        assertEquals(second, LockWindow.latestOf(listOf(second, first)))
        assertNull(LockWindow.latestOf(emptyList()))
    }

    @Test
    fun `the window is 8 hours`() {
        assertEquals(8.hours, LockWindow.LENGTH)
        assertTrue(LockWindow.contains(occurrence, occurrence - LockWindow.LENGTH))
        assertFalse(LockWindow.contains(occurrence, occurrence - LockWindow.LENGTH - 1.seconds))
    }
}
