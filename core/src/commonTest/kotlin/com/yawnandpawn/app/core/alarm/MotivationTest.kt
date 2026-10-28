package com.yawnandpawn.app.core.alarm

import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Instant

/** Story 7.2: the stored text of an alarm's motivation message. */
class MotivationTest {
    @Test
    fun `every motivation has a stable text that reads back as itself`() {
        assertEquals("none", Motivation.None.encode())
        assertEquals("random", Motivation.Random.encode())
        assertEquals("recording:abc-1", Motivation.Recording("abc-1").encode())
        listOf(Motivation.None, Motivation.Random, Motivation.Recording("abc-1")).forEach {
            assertEquals(it, Motivation.parse(it.encode()))
        }
    }

    @Test
    fun `unknown text, no text and a recording without an id read as no message`() {
        listOf(null, "", "Random", "mix", "recording:", "file:x").forEach {
            assertEquals(Motivation.None, Motivation.parse(it), "$it")
        }
    }

    @Test
    fun `a different message makes a different alarm, and a new alarm plays none`() {
        val at = Instant.parse("2027-01-01T00:00:00Z")
        val alarm = Alarm("a", LocalTime(7, 0), requestCode = 1000, createdAt = at, updatedAt = at)

        assertEquals(Motivation.None, alarm.motivation)
        assertEquals(Motivation.None, AlarmDraft(time = LocalTime(7, 0)).motivation)
        assertFalse(alarm.hasSameSettingsAs(alarm.copy(motivation = Motivation.Random)))
    }

    @Test
    fun `when it plays keeps a stable text, unknown text reads as after the check, and it makes a different alarm (Story 7-5)`() {
        MotivationMode.entries.forEach { assertEquals(it, MotivationMode.parse(it.text)) }
        assertEquals("after_up", MotivationMode.AfterImUp.text)
        assertEquals("mix", MotivationMode.MixIntoAlarm.text)
        listOf(null, "", "Mix", "later").forEach { assertEquals(MotivationMode.AfterImUp, MotivationMode.parse(it), "$it") }
        val at = Instant.parse("2027-01-01T00:00:00Z")
        val alarm = Alarm("a", LocalTime(7, 0), requestCode = 1000, createdAt = at, updatedAt = at)

        assertEquals(MotivationMode.AfterImUp, alarm.motivationMode)
        assertEquals(MotivationMode.AfterImUp, AlarmDraft(time = LocalTime(7, 0)).motivationMode)
        assertFalse(alarm.hasSameSettingsAs(alarm.copy(motivationMode = MotivationMode.MixIntoAlarm)))
    }
}
