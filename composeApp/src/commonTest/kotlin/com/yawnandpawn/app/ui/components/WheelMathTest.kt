package com.yawnandpawn.app.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

class WheelMathTest {
    @Test
    fun `12-hour face values map to and from the 24-hour clock`() {
        assertEquals(12, to12Hour(0))
        assertEquals(12, to12Hour(12))
        assertEquals(6, to12Hour(18))
        assertEquals(0, from12Hour(12, pm = false))
        assertEquals(12, from12Hour(12, pm = true))
        assertEquals(20, from12Hour(8, pm = true))
        assertEquals(8, from12Hour(8, pm = false))
    }

    @Test
    fun `an endless wheel moves the short way round`() {
        assertEquals(2, shortestStep(58, 0, 60))
        assertEquals(-2, shortestStep(0, 58, 60))
        assertEquals(5, shortestStep(10, 15, 60))
        assertEquals(0, shortestStep(7, 7, 24))
    }
}
