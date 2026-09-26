package com.yawnandpawn.app.core.alarm

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.DayOfWeek.FRIDAY
import kotlinx.datetime.DayOfWeek.MONDAY
import kotlinx.datetime.DayOfWeek.SUNDAY
import kotlinx.datetime.DayOfWeek.THURSDAY
import kotlinx.datetime.DayOfWeek.TUESDAY
import kotlinx.datetime.DayOfWeek.WEDNESDAY
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class AlarmOccurrenceTest {
    private val berlin = TimeZone.of("Europe/Berlin")
    private val newYork = TimeZone.of("America/New_York")
    private val lordHowe = TimeZone.of("Australia/Lord_Howe")
    private val zones = listOf(berlin, newYork, lordHowe)
    private val weekdays = setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY)

    /** A local date-time in [zone] (never inside a gap or overlap when used for "now" or plain expectations). */
    private fun at(
        text: String,
        zone: TimeZone,
    ): Instant = LocalDateTime.parse(text).toInstant(zone)

    /** A local date-time at a fixed offset, to name one side of a DST change exactly. */
    private fun at(
        text: String,
        offset: UtcOffset,
    ): Instant = LocalDateTime.parse(text).toInstant(offset)

    private fun rule(
        time: String,
        days: Set<DayOfWeek> = emptySet(),
    ) = AlarmRule(LocalTime.parse(time), days)

    private data class Case(
        val name: String,
        val rule: AlarmRule,
        val now: Instant,
        val zone: TimeZone,
        val expected: Instant,
    )

    private fun assertAll(cases: List<Case>) {
        cases.forEach { case ->
            assertEquals(
                case.expected,
                nextOccurrence(case.rule, case.now, case.zone),
                "${case.name} in ${case.zone} (expected local ${case.expected.toLocalDateTime(case.zone)})",
            )
        }
    }

    @Test
    fun `plain rules ring at the rule time on the next matching day in every zone`() {
        // 2027-03-03 is a Wednesday, 2027-03-05 a Friday; no DST change near these dates in the three zones.
        val cases =
            zones.flatMap { zone ->
                fun case(
                    name: String,
                    rule: AlarmRule,
                    now: String,
                    expected: String,
                ) = Case(name, rule, at(now, zone), zone, at(expected, zone))
                listOf(
                    case("one-time, later today", rule("07:00"), "2027-03-03T06:00", "2027-03-03T07:00"),
                    case("one-time, passed today", rule("07:00"), "2027-03-03T08:00", "2027-03-04T07:00"),
                    case("one-time, exactly now", rule("07:00"), "2027-03-03T07:00", "2027-03-04T07:00"),
                    case("weekdays, Friday evening", rule("07:00", weekdays), "2027-03-05T20:00", "2027-03-08T07:00"),
                    case("weekdays, Saturday morning", rule("07:00", weekdays), "2027-03-06T06:00", "2027-03-08T07:00"),
                    case("weekdays, Monday just before", rule("07:00", weekdays), "2027-03-08T06:59", "2027-03-08T07:00"),
                    case("Mondays only, exactly now", rule("07:00", setOf(MONDAY)), "2027-03-08T07:00", "2027-03-15T07:00"),
                    case("midnight rule, late evening", rule("00:00"), "2027-03-03T23:59", "2027-03-04T00:00"),
                )
            }
        assertAll(cases)
    }

    @Test
    fun `a rule time inside a DST gap is shifted forward by the gap length`() {
        val cest = UtcOffset(hours = 2)
        val edt = UtcOffset(hours = -4)
        val lordHoweSummer = UtcOffset(hours = 11)
        val cases =
            listOf(
                // Berlin 2027-03-28: 02:00 CET jumps to 03:00 CEST (1 h gap).
                Case("Berlin daily 02:30", rule("02:30"), at("2027-03-27T23:00", berlin), berlin, at("2027-03-28T03:30", cest)),
                Case("Berlin one-time 02:00", rule("02:00"), at("2027-03-28T01:00", berlin), berlin, at("2027-03-28T03:00", cest)),
                // New York 2027-03-14: 02:00 EST jumps to 03:00 EDT (1 h gap).
                Case(
                    "New York Sundays 02:30",
                    rule("02:30", setOf(SUNDAY)),
                    at("2027-03-13T22:00", newYork),
                    newYork,
                    at("2027-03-14T03:30", edt),
                ),
                // Lord Howe 2027-10-03: 02:00 (+10:30) jumps to 02:30 (+11:00), a 30-minute gap.
                Case(
                    "Lord Howe daily 02:15",
                    rule("02:15"),
                    at("2027-10-03T01:00", lordHowe),
                    lordHowe,
                    at("2027-10-03T02:45", lordHoweSummer),
                ),
            )
        assertAll(cases)

        // The local ring time moves by exactly the gap length (1 h in Berlin, 30 min on Lord Howe).
        assertEquals(LocalTime(3, 30), cases[0].expected.toLocalDateTime(berlin).time)
        assertEquals(LocalTime(2, 45), cases[3].expected.toLocalDateTime(lordHowe).time)
        // The shifted instant is where the rule time would be on the pre-change offset: no real time is lost or added.
        assertEquals(at("2027-03-28T02:30", UtcOffset(hours = 1)), cases[0].expected)
        assertEquals(at("2027-10-03T02:15", UtcOffset(hours = 10, minutes = 30)), cases[3].expected)
    }

    private data class Overlap(
        val zone: TimeZone,
        val day: String,
        val nextDay: String,
        val time: String,
        val earlierOffset: UtcOffset,
        val laterOffset: UtcOffset,
    )

    @Test
    fun `a rule time inside a DST overlap rings only at the earlier instance, never the second`() {
        val overlaps =
            listOf(
                // Berlin 2027-10-31: 03:00 CEST falls back to 02:00 CET, so 02:30 happens twice.
                Overlap(berlin, "2027-10-31", "2027-11-01", "02:30", UtcOffset(hours = 2), UtcOffset(hours = 1)),
                // New York 2027-11-07: 02:00 EDT falls back to 01:00 EST.
                Overlap(newYork, "2027-11-07", "2027-11-08", "01:30", UtcOffset(hours = -4), UtcOffset(hours = -5)),
                // Lord Howe 2027-04-04: 02:00 (+11:00) falls back to 01:30 (+10:30), a 30-minute overlap.
                Overlap(lordHowe, "2027-04-04", "2027-04-05", "01:45", UtcOffset(hours = 11), UtcOffset(hours = 10, minutes = 30)),
            )
        overlaps.forEach { o ->
            val daily = rule(o.time)
            val earlier = at("${o.day}T${o.time}", o.earlierOffset)
            val second = at("${o.day}T${o.time}", o.laterOffset)
            val followingDay = at("${o.nextDay}T${o.time}", o.zone)
            // Both instants really show the rule time locally, so this is an overlap.
            assertEquals(earlier.toLocalDateTime(o.zone), second.toLocalDateTime(o.zone), "${o.zone}: overlap")
            assertNotEquals(earlier, second, "${o.zone}: overlap")

            assertEquals(earlier, nextOccurrence(daily, earlier - 3.hours, o.zone), "${o.zone}: before the overlap")
            assertEquals(followingDay, nextOccurrence(daily, earlier, o.zone), "${o.zone}: called at the earlier instance")
            val between = earlier + (second - earlier) / 2
            assertEquals(followingDay, nextOccurrence(daily, between, o.zone), "${o.zone}: called between the instances")
        }
    }

    @Test
    fun `after a zone change the same rule recomputes to the rule time in the new zone`() {
        val alarm = rule("07:00", weekdays)
        // Wednesday 12:00 in Berlin is Wednesday 06:00 in New York and Wednesday 22:00 in Lord Howe.
        val now = at("2027-03-03T12:00", berlin)
        val cases =
            listOf(
                Case("in Berlin", alarm, now, berlin, at("2027-03-04T07:00", berlin)),
                Case("moved to New York", alarm, now, newYork, at("2027-03-03T07:00", newYork)),
                Case("moved to Lord Howe", alarm, now, lordHowe, at("2027-03-04T07:00", lordHowe)),
            )
        assertAll(cases)
        cases.forEach { case ->
            val local = nextOccurrence(case.rule, case.now, case.zone).toLocalDateTime(case.zone)
            assertEquals(LocalTime(7, 0), local.time, case.name)
        }
    }

    @Test
    fun `durationUntil is the time from now to the occurrence and never negative`() {
        val now = at("2027-03-03T00:00", berlin)
        listOf(
            now + 7.hours + 12.minutes to 7.hours + 12.minutes,
            now to Duration.ZERO,
            now - 1.minutes to Duration.ZERO,
        ).forEach { (occurrence, expected) ->
            assertEquals(expected, durationUntil(occurrence, now), "occurrence $occurrence")
        }

        // On the Berlin gap night, 00:00 to 07:00 local is only 6 h of real time.
        val gapNight = at("2027-03-28T00:00", berlin)
        assertEquals(6.hours, durationUntil(nextOccurrence(rule("07:00"), gapNight, berlin), gapNight))
    }

    @Test
    fun `a rule is one-time exactly when it has no repeat days`() {
        assertTrue(rule("07:00").isOneTime)
        assertTrue(rule("07:00").ringsOn(SUNDAY))
        assertFalse(rule("07:00", setOf(MONDAY)).isOneTime)
        assertFalse(rule("07:00", setOf(MONDAY)).ringsOn(SUNDAY))
        assertTrue(rule("07:00", setOf(MONDAY)).ringsOn(MONDAY))
    }
}
