package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.checks.CheckAnswer
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
    fun `the config takes the alarm's own settings and the global fee settings`() {
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
                vibrateInGrace = true,
                volumePercent = 65,
                gradualVolume = false,
                rampStartPercent = 20,
                soundRef = "builtin:birds",
                vibration = false,
                checkPlan = CheckPlan(CheckMode.All, listOf(CheckEntry(CheckType.Placeholder, Difficulty.Medium, count = 1))),
            ),
            ConfigResolver.resolve(alarm, settings, testMode = false, scheduledAt = SCHEDULED_AT),
        )
    }

    @Test
    fun `the ramp start is always the fixed 20 percent, also for an alarm saved with the old clamped value`() {
        listOf(0, 10, 20, 100).forEach { stored ->
            val stale = alarm.copy(rampStartPercent = stored)
            val resolved = ConfigResolver.resolve(stale, GlobalSettings(), testMode = false, scheduledAt = SCHEDULED_AT)
            assertEquals(Alarm.DEFAULT_RAMP_START_PERCENT, resolved.rampStartPercent, "stored $stored")
        }
    }

    @Test
    fun `test mode is carried into the config`() {
        val config = ConfigResolver.resolve(alarm, GlobalSettings(), testMode = true, scheduledAt = SCHEDULED_AT)
        assertEquals(true, config.testMode)
        assertEquals(1, config.baseFeeTier)
        assertEquals(5, config.maxSnoozes)
        assertEquals(false, config.vibrateInGrace)
    }
}
