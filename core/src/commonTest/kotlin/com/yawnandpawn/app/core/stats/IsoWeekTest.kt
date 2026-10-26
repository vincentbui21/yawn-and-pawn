package com.yawnandpawn.app.core.stats

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 6.8: the ISO-8601 week the weekly summary stores as handled ("2027-W01"). */
class IsoWeekTest {
    @Test
    fun `the week is the one holding the Thursday, across year ends`() {
        mapOf(
            LocalDate(2026, 12, 28) to IsoWeek(2026, 53),
            LocalDate(2027, 1, 3) to IsoWeek(2026, 53),
            LocalDate(2027, 1, 4) to IsoWeek(2027, 1),
            LocalDate(2020, 12, 31) to IsoWeek(2020, 53),
            LocalDate(2021, 1, 3) to IsoWeek(2020, 53),
            LocalDate(2024, 12, 30) to IsoWeek(2025, 1),
            LocalDate(2027, 3, 7) to IsoWeek(2027, 9),
        ).forEach { (date, week) -> assertEquals(week, IsoWeek.of(date), "$date") }
    }

    @Test
    fun `the text is zero-padded and parses back, and anything else parses to null`() {
        assertEquals("2027-W01", IsoWeek(2027, 1).toString())
        assertEquals("2026-W53", IsoWeek(2026, 53).toString())
        assertEquals(IsoWeek(2027, 1), IsoWeek.parse("2027-W01"))
        assertEquals(IsoWeek(2026, 53), IsoWeek.parse(IsoWeek(2026, 53).toString()))
        listOf("", "garbage", "2027-W1", "2027-W00", "2027-W54", "2027-W53", "27-W01", "2027W01", " 2027-W01").forEach { text ->
            assertNull(IsoWeek.parse(text), "'$text'")
        }
    }

    @Test
    fun `a week number outside 1 to 53 is refused`() {
        assertFailsWith<IllegalArgumentException> { IsoWeek(2027, 0) }
        assertFailsWith<IllegalArgumentException> { IsoWeek(2027, 54) }
    }

    @Test
    fun `monday starts the week, and weeks order by year then number`() {
        assertEquals(LocalDate(2027, 1, 4), IsoWeek(2027, 1).monday())
        assertEquals(LocalDate(2026, 12, 28), IsoWeek(2026, 53).monday())
        assertEquals(LocalDate(2025, 12, 29), IsoWeek(2026, 1).monday())
        assertTrue(IsoWeek(2026, 53) < IsoWeek(2027, 1))
        assertTrue(IsoWeek(2027, 2) > IsoWeek(2027, 1))
    }

    @Test
    fun `every date lies in the seven days from its week's Monday (seeded)`() {
        val random = Random(SEED)
        repeat(1_000) {
            val date = LocalDate(1990, 1, 1).plus(random.nextInt(DAYS_TO_2060), DateTimeUnit.DAY)
            val week = IsoWeek.of(date)
            val monday = week.monday()

            assertTrue(monday <= date && date < monday.plus(7, DateTimeUnit.DAY), "$date in $week from $monday")
            assertEquals(week, IsoWeek.of(monday), "$date")
            assertEquals(week, IsoWeek.parse(week.toString()), "$date")
        }
    }

    private companion object {
        const val SEED = 68
        const val DAYS_TO_2060 = 25_567
    }
}
