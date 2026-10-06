package com.yawnandpawn.app.ui.wake

import kotlin.test.Test
import kotlin.test.assertEquals

/** Story 3.4: the countdown's haptic ticks and TalkBack announcements (UX-DR65). */
class GraceRhythmTest {
    @Test
    fun `a short tick every 5 s while the window runs, not at its start or at 0`() {
        assertEquals(listOf(15, 10, 5), (20 downTo 0).filter { isTickSecond(it, 20) })
        assertEquals(listOf(25, 20, 15, 10, 5), (30 downTo 0).filter { isTickSecond(it, 30) })
        assertEquals(listOf(15, 10, 5), (17 downTo 0).filter { isTickSecond(it, 17) })
    }

    @Test
    fun `TalkBack hears the seconds left every 10 s and at 5 s, not every second`() {
        assertEquals(listOf(10, 5), (20 downTo 0).mapNotNull { announcedSecond(it, 20) })
        assertEquals(listOf(20, 10, 5), (30 downTo 0).mapNotNull { announcedSecond(it, 30) })
        assertEquals(listOf(20, 10, 5), (25 downTo 0).mapNotNull { announcedSecond(it, 25) })
        assertEquals(listOf(10, 5), (15 downTo 0).mapNotNull { announcedSecond(it, 15) })
    }
}
