package com.yawnandpawn.app.core.config

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmWriteLock
import com.yawnandpawn.app.core.alarm.InMemoryAlarms
import com.yawnandpawn.app.core.alarm.InMemoryCheckConfigs
import com.yawnandpawn.app.core.alarm.RecordingLogger
import com.yawnandpawn.app.core.alarm.TestClock
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.session.ConfigResolver
import com.yawnandpawn.app.core.session.GlobalSettings
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Story 4.4 review fixes 9 and 11: the fire's settings read, and promotion never weakening the ring a change waited for. */
class ReadFireSettingsTest {
    private val sevenThirty = Instant.parse("2027-03-09T07:30:00Z")
    private val waitsFor = Occurrence("a", sevenThirty)
    private val pending = InMemoryPendingChanges()
    private val settings = InMemoryGlobalSettings(GlobalSettings(baseFeeTier = 7, maxSnoozes = 2), pending)
    private val logger = RecordingLogger()
    private val reader = ReadFireSettings(settings, pending, logger)

    private val globalFee = PendingChange(null, SettingValue.BaseFeeTier(1), waitsFor)
    private val ownGrace = PendingChange("a", SettingValue.GraceSeconds(30), waitsFor)
    private val otherGrace = PendingChange("b", SettingValue.GraceSeconds(25), Occurrence("b", sevenThirty))

    private fun failures(operation: String) = logger.events.filter { it is LogEvent.OperationFailed && it.operation == operation }

    @Test
    fun `one snapshot of the settings with the global changes, and the alarm's own changes beside it`() =
        runTest {
            pending.changes.value = listOf(globalFee, ownGrace, otherGrace)

            val read = reader.read("a")

            assertEquals(FireSettings(GlobalSettings(baseFeeTier = 7, maxSnoozes = 2), listOf(globalFee, ownGrace)), read)
            assertEquals(SettingsSnapshot(GlobalSettings(baseFeeTier = 7, maxSnoozes = 2), listOf(globalFee)), settings.lastKnown())
            assertTrue(logger.events.isEmpty())
        }

    @Test
    fun `a stalled store holds the fire for at most the budget, both reads at once, and the last-known settings ring`() =
        runTest {
            settings.lastKnownSnapshot = SettingsSnapshot(GlobalSettings(baseFeeTier = 7), listOf(globalFee))
            settings.snapshotDelay = 900.milliseconds
            pending.readDelay = 900.milliseconds
            pending.changes.value = listOf(ownGrace)

            val read = reader.read("a")

            assertTrue(currentTime <= ReadFireSettings.BUDGET.inWholeMilliseconds, "rang after $currentTime ms")
            assertEquals(500, ReadFireSettings.BUDGET.inWholeMilliseconds)
            assertEquals(FireSettings(GlobalSettings(baseFeeTier = 7), listOf(globalFee)), read, "no own changes read: the live alarm")
            assertEquals(
                LogEvent.OperationFailed(ReadFireSettings.OPERATION_SETTINGS, "timed out"),
                failures(ReadFireSettings.OPERATION_SETTINGS).single(),
            )
            assertEquals(1, failures(ReadFireSettings.OPERATION_PENDING).size)
        }

    @Test
    fun `a failing store rings the last-known settings, and the defaults only when there never were any`() =
        runTest {
            settings.failure = DomainError.StorageFailure("disk")
            settings.lastKnownSnapshot = SettingsSnapshot(GlobalSettings(baseFeeTier = 7), emptyList())

            assertEquals(GlobalSettings(baseFeeTier = 7), reader.read("a").settings)

            settings.lastKnownSnapshot = null
            assertEquals(FireSettings(GlobalSettings(), emptyList()), reader.read("a"))
            assertEquals(2, failures(ReadFireSettings.OPERATION_SETTINGS).size)
        }

    @Test
    fun `a store that throws is a failure too, never a failed fire`() =
        runTest {
            settings.snapshotThrows = IllegalStateException("corrupt")
            pending.failure = DomainError.StorageFailure("disk")

            assertEquals(FireSettings(GlobalSettings(), emptyList()), reader.read("a"))
            assertEquals(
                LogEvent.OperationFailed(ReadFireSettings.OPERATION_SETTINGS, "IllegalStateException"),
                failures(ReadFireSettings.OPERATION_SETTINGS).single(),
            )
            assertEquals(1, failures(ReadFireSettings.OPERATION_PENDING).size)
        }

    @Test
    fun `promotion right after the occurrence never weakens the ring the change waited for (review 9)`() =
        runTest {
            // A cold start at the 07:30 fire: rescheduleAll promotes while the engine is still Idle, before AlarmFired.
            val clock = TestClock(sevenThirty + 1.seconds)
            val alarms = InMemoryAlarms()
            val created = Instant.parse("2027-01-01T00:00:00Z")
            val alarm =
                Alarm(id = "a", time = LocalTime(7, 30), graceSeconds = 20, requestCode = 1_000, createdAt = created, updatedAt = created)
            alarms.alarms.value += "a" to alarm
            pending.changes.value = listOf(globalFee, ownGrace)
            val promote =
                PromotePendingChanges(pending, settings, alarms, InMemoryCheckConfigs(alarms), clock, AlarmWriteLock(), logger) { null }

            assertEquals(Outcome.Success(0), promote(), "nothing is due a second after the occurrence")
            val fire = reader.read("a")
            val config =
                ConfigResolver.resolve(
                    alarms.alarms.value.getValue("a"),
                    emptyList(),
                    fire.settings,
                    testMode = false,
                    scheduledAt = sevenThirty,
                    wordsAvailable = true,
                    pendingChanges = fire.pendingChanges,
                )

            assertEquals(7, config.baseFeeTier, "the strong fee")
            assertEquals(20, config.graceSeconds, "the strong grace window")

            // Tomorrow's ring takes the changes.
            val tomorrow =
                ConfigResolver.resolve(
                    alarm,
                    emptyList(),
                    fire.settings,
                    false,
                    sevenThirty + 1.days,
                    pendingChanges = fire.pendingChanges,
                )
            assertEquals(1, tomorrow.baseFeeTier)
            assertEquals(30, tomorrow.graceSeconds)
        }
}
