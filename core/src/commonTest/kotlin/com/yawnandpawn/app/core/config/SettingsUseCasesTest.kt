package com.yawnandpawn.app.core.config

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmWriteLock
import com.yawnandpawn.app.core.alarm.InMemoryAlarms
import com.yawnandpawn.app.core.alarm.SequentialIds
import com.yawnandpawn.app.core.alarm.TestClock
import com.yawnandpawn.app.core.alarm.TestZone
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.GlobalSettings
import com.yawnandpawn.app.core.session.SessionLockGuard
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.snoozedSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** Story 4.4: SetBaseFee and SetMaxSnoozes through the commitment lock. Tests use 23:40 and a 07:30 alarm (7 h 50 min). */
class SettingsUseCasesTest {
    private val now = Instant.parse("2027-03-08T23:40:00Z")
    private val clock = TestClock(now)
    private val alarms = InMemoryAlarms()
    private val settings = InMemoryGlobalSettings(GlobalSettings(baseFeeTier = 3, maxSnoozes = 2))
    private val pending = InMemoryPendingChanges()
    private val sessionState = MutableStateFlow<SessionState>(SessionState.Idle)
    private val sessionLock = SessionLockGuard(sessionState, MutableStateFlow(true), MutableStateFlow(false))
    private val save = SaveGlobalSetting(settings, pending, alarms, clock, TestZone(TimeZone.UTC), AlarmWriteLock(), sessionLock)
    private val setBaseFee = SetBaseFee(save)
    private val setMaxSnoozes = SetMaxSnoozes(save)

    private val sevenThirty = Occurrence("a", Instant.parse("2027-03-09T07:30:00Z"))

    private fun alarmAt(
        time: LocalTime,
        id: String = "a",
        enabled: Boolean = true,
    ) {
        val created = Instant.parse("2027-01-01T00:00:00Z")
        alarms.alarms.value +=
            id to Alarm(id = id, time = time, enabled = enabled, requestCode = 1_000 + id.length, createdAt = created, updatedAt = created)
    }

    @Test
    fun `lowering the fee with an alarm inside 8 h waits for it, raising applies at once`() =
        runTest {
            alarmAt(LocalTime(7, 30))

            assertEquals(Outcome.Success(Saved(sevenThirty)), setBaseFee(1))
            assertEquals(3, settings.settings.value.baseFeeTier, "still the live fee")
            assertEquals(listOf(PendingChange(null, SettingValue.BaseFeeTier(1), sevenThirty)), pending.changes.value)

            assertEquals(Outcome.Success(Saved(null)), setBaseFee(5))
            assertEquals(5, settings.settings.value.baseFeeTier)
            assertTrue(pending.changes.value.isEmpty(), "raising clears the pending change")
        }

    @Test
    fun `effective 3 with 1 pending - 2 still waits and replaces it, 5 applies and clears it`() =
        runTest {
            alarmAt(LocalTime(7, 30))
            setBaseFee(1)

            assertEquals(Outcome.Success(Saved(sevenThirty)), setBaseFee(2))
            assertEquals(listOf(PendingChange(null, SettingValue.BaseFeeTier(2), sevenThirty)), pending.changes.value)
            assertEquals(3, settings.settings.value.baseFeeTier)

            assertEquals(Outcome.Success(Saved(null)), setBaseFee(5))
            assertEquals(5, settings.settings.value.baseFeeTier)
            assertTrue(pending.changes.value.isEmpty())
        }

    @Test
    fun `with no alarm inside 8 h every change applies at once`() =
        runTest {
            alarmAt(LocalTime(9, 0))
            alarmAt(LocalTime(7, 30), id = "off", enabled = false)

            assertEquals(Outcome.Success(Saved(null)), setBaseFee(1))
            assertEquals(Outcome.Success(Saved(null)), setMaxSnoozes(5))
            assertEquals(GlobalSettings(baseFeeTier = 1, maxSnoozes = 5), settings.settings.value)
            assertTrue(pending.changes.value.isEmpty())
        }

    @Test
    fun `more snoozes inside the window wait for the latest alarm in it, fewer apply at once`() =
        runTest {
            alarmAt(LocalTime(1, 0), id = "b")
            alarmAt(LocalTime(7, 30))

            assertEquals(Outcome.Success(Saved(sevenThirty)), setMaxSnoozes(4))
            assertEquals(2, settings.settings.value.maxSnoozes)

            assertEquals(Outcome.Success(Saved(null)), setMaxSnoozes(1))
            assertEquals(1, settings.settings.value.maxSnoozes)
            assertTrue(pending.changes.value.isEmpty())
        }

    @Test
    fun `a pending change past its occurrence is promoted on the way`() =
        runTest {
            alarmAt(LocalTime(7, 30))
            pending.changes.value = listOf(PendingChange(null, SettingValue.BaseFeeTier(2), Occurrence("x", now - 1.hours)))

            assertEquals(Outcome.Success(Saved(sevenThirty)), setBaseFee(1))
            assertEquals(2, settings.settings.value.baseFeeTier, "the due value is live now")
            assertEquals(listOf(PendingChange(null, SettingValue.BaseFeeTier(1), sevenThirty)), pending.changes.value)
        }

    @Test
    fun `fee tiers 1 to 10 and max snoozes 1 to 5, anything else is InvalidSetting with nothing written`() =
        runTest {
            listOf(0, 11).forEach { assertEquals(Outcome.Failure(DomainError.InvalidSetting(SettingField.BaseFee)), setBaseFee(it)) }
            listOf(0, 6).forEach { assertEquals(Outcome.Failure(DomainError.InvalidSetting(SettingField.MaxSnoozes)), setMaxSnoozes(it)) }
            assertEquals(0, settings.writes)

            assertEquals(Outcome.Success(Saved(null)), setBaseFee(10))
            assertEquals(Outcome.Success(Saved(null)), setMaxSnoozes(5))
            assertEquals(Outcome.Success(Saved(null)), setBaseFee(1))
            assertEquals(Outcome.Success(Saved(null)), setMaxSnoozes(1))
        }

    @Test
    fun `nothing changes during a session`() =
        runTest {
            sessionState.value = SessionState.Snoozed(snoozedSession())

            assertEquals(Outcome.Failure(DomainError.SessionActive), setBaseFee(5))
            assertEquals(0, settings.writes)
        }

    @Test
    fun `read and write failures are returned`() =
        runTest {
            alarmAt(LocalTime(7, 30))
            val failure = DomainError.StorageFailure("disk")

            alarms.listFailure = failure
            assertEquals(Outcome.Failure(failure), setBaseFee(1))
            alarms.listFailure = null

            pending.writeFailure = failure
            assertEquals(Outcome.Failure(failure), setBaseFee(1))
            assertEquals(3, settings.settings.value.baseFeeTier, "the live value is kept: the stronger one")

            pending.failure = failure
            assertEquals(Outcome.Failure(failure), setBaseFee(5))
            pending.failure = null
            pending.writeFailure = null

            settings.failure = failure
            assertEquals(Outcome.Failure(failure), setBaseFee(5))
            assertNull(pending.changes.value.firstOrNull())
        }
}
