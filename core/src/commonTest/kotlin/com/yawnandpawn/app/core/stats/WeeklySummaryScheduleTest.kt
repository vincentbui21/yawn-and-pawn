package com.yawnandpawn.app.core.stats

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** Story 6.8: the weekly summary target, Sunday 19:00 local, in every zone and across DST. */
class WeeklySummaryScheduleTest {
    private val berlin = TimeZone.of("Europe/Berlin")

    private fun berlin(text: String): Instant = LocalDateTime.parse(text).toInstant(berlin)

    @Test
    fun `the next target is the coming Sunday 19 00, or a week later from 19 00 on`() {
        // Saturday 2027-03-06 23:00, Sunday 18:59, Sunday 19:00 exactly.
        assertEquals(berlin("2027-03-07T19:00"), nextWeeklySummaryAt(berlin("2027-03-06T23:00"), berlin))
        assertEquals(berlin("2027-03-07T19:00"), nextWeeklySummaryAt(berlin("2027-03-07T18:59"), berlin))
        assertEquals(berlin("2027-03-14T19:00"), nextWeeklySummaryAt(berlin("2027-03-07T19:00"), berlin))
        assertEquals(berlin("2027-03-14T19:00"), nextWeeklySummaryAt(berlin("2027-03-08T06:30"), berlin))
    }

    @Test
    fun `across the Berlin DST changes the target stays 19 00 local`() {
        val spring = Instant.parse("2027-03-27T11:00:00Z")
        assertEquals(Instant.parse("2027-03-28T17:00:00Z"), nextWeeklySummaryAt(spring, berlin))
        assertEquals(30.hours, nextWeeklySummaryAt(spring, berlin) - spring)

        val autumn = Instant.parse("2027-10-30T10:00:00Z")
        assertEquals(Instant.parse("2027-10-31T18:00:00Z"), nextWeeklySummaryAt(autumn, berlin))
        assertEquals(32.hours, nextWeeklySummaryAt(autumn, berlin) - autumn)
    }

    @Test
    fun `the zone decides which Sunday evening comes next`() {
        val now = Instant.parse("2027-03-07T10:00:00Z")

        assertEquals(Instant.parse("2027-03-07T12:00:00Z"), nextWeeklySummaryAt(now, TimeZone.of("Asia/Ho_Chi_Minh")))
        assertEquals(Instant.parse("2027-03-08T00:00:00Z"), nextWeeklySummaryAt(now, TimeZone.of("America/New_York")))
    }

    @Test
    fun `the last target mirrors the next one, a Sunday 19 00 exactly being its own`() {
        assertEquals(berlin("2027-03-07T19:00"), lastWeeklySummaryAt(berlin("2027-03-07T19:00"), berlin))
        assertEquals(berlin("2027-02-28T19:00"), lastWeeklySummaryAt(berlin("2027-03-07T18:59"), berlin))
        assertEquals(berlin("2027-02-28T19:00"), lastWeeklySummaryAt(berlin("2027-03-06T23:00"), berlin))
        assertEquals(berlin("2027-03-07T19:00"), lastWeeklySummaryAt(berlin("2027-03-08T19:01"), berlin))
        assertEquals(Instant.parse("2027-03-28T17:00:00Z"), lastWeeklySummaryAt(Instant.parse("2027-03-29T08:00:00Z"), berlin))
    }

    @Test
    fun `in any zone the targets are Sunday 19 00 local, around now, a week apart (seeded)`() {
        val zones = listOf("UTC", "Europe/Berlin", "America/New_York", "Asia/Ho_Chi_Minh").map { TimeZone.of(it) }
        val random = Random(SEED)
        val from = Instant.parse("2020-01-01T00:00:00Z").epochSeconds
        val until = Instant.parse("2035-01-01T00:00:00Z").epochSeconds
        repeat(1_000) {
            val now = Instant.fromEpochSeconds(random.nextLong(from, until))
            zones.forEach { zone ->
                val next = nextWeeklySummaryAt(now, zone)
                val last = lastWeeklySummaryAt(now, zone)
                listOf(next, last).forEach { target ->
                    val local = target.toLocalDateTime(zone)
                    assertEquals(DayOfWeek.SUNDAY, local.dayOfWeek, "$now in $zone: $local")
                    assertEquals(LocalTime(19, 0), local.time, "$now in $zone: $local")
                }
                assertTrue(next > now && next - now < 8.days, "$now in $zone: next $next")
                assertTrue(last <= now && now - last < 8.days, "$now in $zone: last $last")
                assertEquals(next, nextWeeklySummaryAt(last, zone), "$now in $zone")
            }
        }
    }

    private companion object {
        const val SEED = 19
    }
}
