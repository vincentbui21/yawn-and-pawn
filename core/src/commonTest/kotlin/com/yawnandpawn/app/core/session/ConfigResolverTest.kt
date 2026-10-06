package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmDraft
import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.CheckMode
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.Difficulty
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class ConfigResolverTest {
    private val alarm =
        Alarm(
            id = "alarm-7",
            time = LocalTime(6, 30),
            label = "Gym",
            soundRef = "builtin:birds",
            volumePercent = 65,
            gradualVolume = false,
            vibration = false,
            snoozeLengthMinutes = 15,
            graceSeconds = 30,
            vibrateInGrace = false,
            requestCode = 1_000,
            createdAt = Instant.parse("2027-01-01T00:00:00Z"),
            updatedAt = Instant.parse("2027-01-01T00:00:00Z"),
        )

    @Test
    fun `global settings default to base fee tier 1, 5 snoozes, 20 s grace, 9 min snooze and no vibration in grace`() {
        assertEquals(
            GlobalSettings(baseFeeTier = 1, maxSnoozes = 5, graceSeconds = 20, snoozeLengthMinutes = 9, vibrateInGrace = false),
            GlobalSettings(),
        )
    }

    @Test
    fun `the config takes the alarm's own settings, its quiet-time vibration included, and the global fee settings`() {
        val settings = GlobalSettings(baseFeeTier = 3, maxSnoozes = 2, vibrateInGrace = true)
        assertEquals(
            SessionConfig(
                alarmId = "alarm-7",
                label = "Gym",
                scheduledAt = SCHEDULED_AT,
                testMode = false,
                baseFeeTier = 3,
                maxSnoozes = 2,
                snoozeLengthMinutes = 15,
                graceSeconds = 30,
                vibrateInGrace = false,
                volumePercent = 65,
                gradualVolume = false,
                rampStartPercent = 20,
                soundRef = "builtin:birds",
                vibration = false,
                checkPlan = CheckPlan(CheckMode.Random, listOf(CheckEntry(CheckType.Math, Difficulty.Medium, count = 3))),
            ),
            ConfigResolver.resolve(alarm, emptyList(), settings, testMode = false, scheduledAt = SCHEDULED_AT),
        )
    }

    @Test
    fun `the ramp start is always the fixed 20 percent, also for an alarm saved with the old clamped value`() {
        listOf(0, 10, 20, 100).forEach { stored ->
            val stale = alarm.copy(rampStartPercent = stored)
            val resolved = ConfigResolver.resolve(stale, emptyList(), GlobalSettings(), testMode = false, scheduledAt = SCHEDULED_AT)
            assertEquals(Alarm.DEFAULT_RAMP_START_PERCENT, resolved.rampStartPercent, "stored $stored")
        }
    }

    @Test
    fun `the plan is the alarm's checks in its mode, in the given order (Story 3-5)`() {
        val hard = CheckEntry(CheckType.Math, Difficulty.Hard, count = 5)
        val memory = CheckEntry(CheckType.MemorySequence(), Difficulty.Easy, count = 1)
        listOf(listOf(hard, memory), listOf(memory, hard)).forEach { checks ->
            listOf(CheckMode.Random, CheckMode.All).forEach { mode ->
                val config =
                    ConfigResolver.resolve(
                        alarm.copy(checkMode = mode),
                        checks,
                        GlobalSettings(),
                        testMode = false,
                        scheduledAt = SCHEDULED_AT,
                    )

                assertEquals(CheckPlan(mode, checks), config.checkPlan, "mode $mode, order $checks")
            }
        }
    }

    @Test
    fun `an alarm without checks rings the default plan`() {
        val config = ConfigResolver.resolve(alarm.copy(checkMode = CheckMode.All), emptyList(), GlobalSettings(), false, SCHEDULED_AT)

        assertEquals(ConfigResolver.defaultPlan(), config.checkPlan)
    }

    @Test
    fun `with a screen reader on at the fire, Memory Sequence is frozen as its numbered variant (Story 3-8)`() {
        val memory = CheckEntry(CheckType.MemorySequence(), Difficulty.Hard, count = 2)

        val off = ConfigResolver.resolve(alarm, listOf(memory), GlobalSettings(), false, SCHEDULED_AT)
        val on = ConfigResolver.resolve(alarm, listOf(memory), GlobalSettings(), false, SCHEDULED_AT, accessible = true)

        assertEquals(listOf(memory), off.checkPlan.entries)
        assertEquals(listOf(memory.copy(type = CheckType.MemorySequence(numbered = true))), on.checkPlan.entries)
    }

    @Test
    fun `test mode is carried into the config`() {
        val config = ConfigResolver.resolve(alarm, emptyList(), GlobalSettings(), testMode = true, scheduledAt = SCHEDULED_AT)
        assertEquals(true, config.testMode)
        assertEquals(1, config.baseFeeTier)
        assertEquals(5, config.maxSnoozes)
        assertEquals(false, config.vibrateInGrace)
    }

    @Test
    fun `quiet-time vibration is the alarm's own setting, on by default, whatever the global setting says (Story 3_4)`() {
        val off = GlobalSettings(vibrateInGrace = false)
        val on = GlobalSettings(vibrateInGrace = true)

        assertEquals(
            true,
            Alarm(id = "a", time = LocalTime(7, 0), requestCode = 1, createdAt = SCHEDULED_AT, updatedAt = SCHEDULED_AT).vibrateInGrace,
        )
        assertEquals(
            true,
            ConfigResolver
                .resolve(alarm.copy(vibration = true, vibrateInGrace = true), NO_CHECKS, off, testMode = false, scheduledAt = SCHEDULED_AT)
                .vibrateInGrace,
        )
        assertEquals(
            false,
            ConfigResolver.resolve(alarm.copy(vibration = true), NO_CHECKS, on, false, SCHEDULED_AT).vibrateInGrace,
        )
        val draft = AlarmDraft(time = LocalTime(7, 0))
        assertEquals(true, draft.vibrateInGrace)
        assertEquals(true, ConfigResolver.resolveTest(draft, off, SCHEDULED_AT).vibrateInGrace)
        assertEquals(false, ConfigResolver.resolveTest(draft.copy(vibrateInGrace = false), on, SCHEDULED_AT).vibrateInGrace)
    }

    @Test
    fun `an alarm with vibration off never vibrates during quiet time, whatever its quiet-time switch says (review fix)`() {
        val quiet = alarm.copy(vibration = false, vibrateInGrace = true)

        val config = ConfigResolver.resolve(quiet, NO_CHECKS, GlobalSettings(), testMode = false, scheduledAt = SCHEDULED_AT)
        assertEquals(false, config.vibrateInGrace)
        val draft = AlarmDraft(time = LocalTime(7, 0), vibration = false, vibrateInGrace = true)
        assertEquals(false, ConfigResolver.resolveTest(draft, GlobalSettings(), SCHEDULED_AT).vibrateInGrace)
        assertEquals(true, ConfigResolver.resolveTest(draft.copy(vibration = true), GlobalSettings(), SCHEDULED_AT).vibrateInGrace)
    }
}

/** An alarm without check rows (the resolver then uses the default plan). */
private val NO_CHECKS: List<CheckEntry> = emptyList()
