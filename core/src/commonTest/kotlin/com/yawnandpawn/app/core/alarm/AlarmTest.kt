package com.yawnandpawn.app.core.alarm

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class AlarmTest {
    private val created = Instant.parse("2027-03-03T06:00:00Z")

    private fun alarm(
        time: LocalTime = LocalTime(7, 0),
        id: String = "a",
        createdAt: Instant = created,
    ) = Alarm(id = id, time = time, requestCode = RequestCodes.FIRST_ALARM, createdAt = createdAt, updatedAt = createdAt)

    @Test
    fun `a new alarm has the story defaults`() {
        val alarm = alarm()

        assertEquals(emptySet(), alarm.repeatDays)
        assertNull(alarm.label)
        assertEquals(true, alarm.enabled)
        assertEquals(Alarm.DEFAULT_SOUND_REF, alarm.soundRef)
        assertEquals(80, alarm.volumePercent)
        assertEquals(true, alarm.gradualVolume)
        assertEquals(20, alarm.rampStartPercent)
        assertEquals(true, alarm.vibration)
        assertEquals(9, alarm.snoozeLengthMinutes)
        assertEquals(20, alarm.graceSeconds)
    }

    @Test
    fun `the rule carries time and repeat days`() {
        val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)
        val alarm = alarm(time = LocalTime(6, 30)).copy(repeatDays = weekdays)

        assertEquals(AlarmRule(LocalTime(6, 30), weekdays), alarm.toRule())
    }

    @Test
    fun `the list order is time of day, then creation time, then id`() {
        val later = Instant.parse("2027-03-04T06:00:00Z")
        val alarms =
            listOf(
                alarm(LocalTime(22, 0), "late"),
                alarm(LocalTime(7, 15), "b", later),
                alarm(LocalTime(6, 30), "early"),
                alarm(LocalTime(7, 15), "c"),
                alarm(LocalTime(7, 15), "a"),
            )

        assertEquals(listOf("early", "a", "c", "b", "late"), alarms.sortedWith(AlarmListOrder).map { it.id })
    }

    private data class ValidationCase(
        val name: String,
        val alarm: Alarm,
        val invalid: AlarmField?,
    )

    private val valid = alarm()
    private val emoji = "😀"

    private val validationCases =
        listOf(
            ValidationCase("defaults", valid, null),
            ValidationCase("empty label", valid.copy(label = ""), null),
            ValidationCase("40-character label", valid.copy(label = "x".repeat(40)), null),
            ValidationCase("41-character label", valid.copy(label = "x".repeat(41)), AlarmField.Label),
            ValidationCase("40 emoji", valid.copy(label = emoji.repeat(40)), null),
            ValidationCase("41 emoji", valid.copy(label = emoji.repeat(41)), AlarmField.Label),
            ValidationCase("lone high surrogates count once each", valid.copy(label = "\uD83D".repeat(41)), AlarmField.Label),
            ValidationCase("lone high surrogate at the end", valid.copy(label = "x".repeat(39) + "\uD83D"), null),
            ValidationCase("volume 0 with a level ramp", valid.copy(volumePercent = 0, rampStartPercent = 0), null),
            ValidationCase("volume 100", valid.copy(volumePercent = 100), null),
            ValidationCase("volume -1", valid.copy(volumePercent = -1), AlarmField.VolumePercent),
            ValidationCase("volume 101", valid.copy(volumePercent = 101), AlarmField.VolumePercent),
            ValidationCase("ramp start 0", valid.copy(rampStartPercent = 0), null),
            ValidationCase("ramp start 100 at volume 100", valid.copy(volumePercent = 100, rampStartPercent = 100), null),
            ValidationCase("ramp start equal to volume", valid.copy(volumePercent = 50, rampStartPercent = 50), null),
            // The ramp start is a percentage of the set volume (Story 1.14), so it may exceed the volume figure.
            ValidationCase("ramp start above volume", valid.copy(volumePercent = 50, rampStartPercent = 51), null),
            ValidationCase("default ramp start at volume 10", valid.copy(volumePercent = 10, rampStartPercent = 20), null),
            ValidationCase(
                "no ramp, start above volume",
                valid.copy(volumePercent = 50, rampStartPercent = 90, gradualVolume = false),
                null,
            ),
            ValidationCase("blank sound", valid.copy(soundRef = " "), AlarmField.SoundRef),
            ValidationCase("empty sound", valid.copy(soundRef = ""), AlarmField.SoundRef),
            ValidationCase("ramp start -1", valid.copy(rampStartPercent = -1), AlarmField.RampStartPercent),
            ValidationCase("ramp start 101", valid.copy(rampStartPercent = 101), AlarmField.RampStartPercent),
            ValidationCase("snooze 5", valid.copy(snoozeLengthMinutes = 5), null),
            ValidationCase("snooze 10", valid.copy(snoozeLengthMinutes = 10), null),
            ValidationCase("snooze 15", valid.copy(snoozeLengthMinutes = 15), null),
            ValidationCase("snooze 7", valid.copy(snoozeLengthMinutes = 7), AlarmField.SnoozeLengthMinutes),
            ValidationCase("snooze 0", valid.copy(snoozeLengthMinutes = 0), AlarmField.SnoozeLengthMinutes),
            ValidationCase("grace 15", valid.copy(graceSeconds = 15), null),
            ValidationCase("grace 30", valid.copy(graceSeconds = 30), null),
            ValidationCase("grace 14", valid.copy(graceSeconds = 14), AlarmField.GraceSeconds),
            ValidationCase("grace 31", valid.copy(graceSeconds = 31), AlarmField.GraceSeconds),
            ValidationCase("first invalid field wins", valid.copy(label = "x".repeat(41), graceSeconds = 31), AlarmField.Label),
        )

    @Test
    fun `validation accepts every allowed value and names the first field out of range`() {
        validationCases.forEach { case ->
            assertEquals(case.invalid, validate(case.alarm), case.name)
        }
    }
}
