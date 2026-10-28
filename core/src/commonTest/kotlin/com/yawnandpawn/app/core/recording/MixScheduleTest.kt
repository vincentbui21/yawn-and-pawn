package com.yawnandpawn.app.core.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Story 7.5: what a "Mix into alarm" ring plays, from its mix anchor. */
class MixScheduleTest {
    private val message = 8.seconds

    private fun at(elapsed: Duration) = mixSchedule(elapsed, recordingDuration = message)

    @Test
    fun `the first 30 s are the alarm sound, so the ramp completes`() {
        assertEquals(MixSource.Alarm, at(Duration.ZERO))
        assertEquals(MixSource.Alarm, at(29.seconds))
        assertEquals(MixSource.Alarm, at(30.seconds - 1.milliseconds))
    }

    @Test
    fun `the message plays once from 30 s, then the alarm sound again`() {
        assertEquals(MixSource.Recording, at(30.seconds))
        assertEquals(MixSource.Recording, at(30.seconds + message - 1.milliseconds))
        assertEquals(MixSource.Alarm, at(30.seconds + message))
        assertEquals(MixSource.Alarm, at(67.seconds))
    }

    @Test
    fun `the second cycle repeats the first`() {
        val cycle = 30.seconds + message

        assertEquals(MixSource.Alarm, at(cycle))
        assertEquals(MixSource.Alarm, at(cycle + 29.seconds))
        assertEquals(MixSource.Recording, at(cycle + 30.seconds))
        assertEquals(MixSource.Recording, at(cycle + 37.seconds))
        assertEquals(MixSource.Alarm, at(cycle * 2))
    }

    @Test
    fun `a recording without a length never plays`() {
        listOf(Duration.ZERO, (-1).seconds).forEach { duration ->
            listOf(Duration.ZERO, 30.seconds, 45.seconds, 864_000.seconds).forEach {
                assertEquals(MixSource.Alarm, mixSchedule(it, recordingDuration = duration))
            }
        }
    }

    @Test
    fun `counted from a new anchor, the alarm sound comes first again whatever the time before`() {
        // The player restarts the anchor (after grace, a call or the message); from 0 the schedule is a fresh cycle.
        assertEquals(MixSource.Recording, at(34.seconds), "34 s from the old anchor: the message")
        assertEquals(MixSource.Alarm, at(Duration.ZERO))
        assertEquals(MixSource.Alarm, at(29.seconds))
        assertEquals(MixSource.Recording, at(30.seconds))
    }

    @Test
    fun `a custom sound block is honoured`() {
        assertEquals(MixSource.Alarm, mixSchedule(9.seconds, soundBlock = 10.seconds, recordingDuration = message))
        assertEquals(MixSource.Recording, mixSchedule(10.seconds, soundBlock = 10.seconds, recordingDuration = message))
        assertEquals(MixSource.Alarm, mixSchedule(18.seconds, soundBlock = 10.seconds, recordingDuration = message))
    }
}
