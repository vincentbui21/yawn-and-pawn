package com.yawnandpawn.app.ui.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class CountdownTest {
    @Test
    fun `under an hour counts minutes`() {
        assertEquals(Countdown.Minutes(45), countdownOf(45.minutes))
    }

    @Test
    fun `one to 24 hours counts hours and minutes`() {
        assertEquals(Countdown.HoursMinutes(7, 12), countdownOf(7.hours + 12.minutes))
        assertEquals(Countdown.HoursMinutes(1, 0), countdownOf(60.minutes))
    }

    @Test
    fun `24 hours or more counts days and hours`() {
        assertEquals(Countdown.DaysHours(2, 3), countdownOf(2.days + 3.hours + 20.minutes))
        assertEquals(Countdown.DaysHours(1, 0), countdownOf(24.hours))
    }

    @Test
    fun `partial minutes round up and nothing shows under 1 min`() {
        assertEquals(Countdown.Minutes(45), countdownOf(44.minutes + 1.seconds))
        assertEquals(Countdown.Minutes(1), countdownOf(30.seconds))
        assertEquals(Countdown.Minutes(1), countdownOf(0.seconds))
        assertEquals(Countdown.HoursMinutes(1, 0), countdownOf(59.minutes + 30.seconds))
    }

    @Test
    fun `money multiplies and adds in one currency`() {
        assertEquals(Money.of(3, "EUR"), Money.of(1, "EUR") * 3)
        assertEquals(Money.of(3, "USD"), Money.of(1, "USD") + Money.of(2, "USD"))
    }
}
