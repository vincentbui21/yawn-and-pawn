package com.yawnandpawn.app.core.config

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmWriteLock
import com.yawnandpawn.app.core.alarm.CheckConfig
import com.yawnandpawn.app.core.alarm.InMemoryAlarms
import com.yawnandpawn.app.core.alarm.InMemoryCheckConfigs
import com.yawnandpawn.app.core.alarm.RecordingLogger
import com.yawnandpawn.app.core.alarm.SequentialIds
import com.yawnandpawn.app.core.alarm.TestClock
import com.yawnandpawn.app.core.alarm.TestZone
import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.CheckMode
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.Difficulty
import com.yawnandpawn.app.core.checks.qr.CodeFormat
import com.yawnandpawn.app.core.checks.qr.RegisteredCode
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.session.GlobalSettings
import com.yawnandpawn.app.core.session.SessionLockGuard
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.ringSession
import com.yawnandpawn.app.core.session.snoozedSession
import com.yawnandpawn.app.core.session.testConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Story 4.4: PromotePendingChanges and RecordCommitmentEvent. */
class PromotePendingChangesTest {
    private val sevenThirty = Instant.parse("2027-03-09T07:30:00Z")
    private val clock = TestClock(sevenThirty + 10.minutes)
    private val alarms = InMemoryAlarms()
    private val checks = InMemoryCheckConfigs(alarms)
    private val settings = InMemoryGlobalSettings(GlobalSettings(baseFeeTier = 3, maxSnoozes = 2))
    private val pending = InMemoryPendingChanges()
    private val logger = RecordingLogger()
    private var active: Occurrence? = null
    private val promote = PromotePendingChanges(pending, settings, alarms, checks, clock, AlarmWriteLock(), logger) { active }

    private val created = Instant.parse("2027-01-01T00:00:00Z")
    private val alarm =
        Alarm(id = "a", time = LocalTime(7, 30), graceSeconds = 15, requestCode = 1_000, createdAt = created, updatedAt = created)
    private val waitsFor = Occurrence("a", sevenThirty)
    private val math = CheckEntry(CheckType.Math, Difficulty.Hard, 5)
    private val easy = CheckEntry(CheckType.Math, Difficulty.Easy, 1)

    private fun change(
        value: SettingValue,
        alarmId: String? = null,
        after: Occurrence = waitsFor,
    ) = PendingChange(alarmId, value, after)

    @Test
    fun `due changes become live and are deleted, global and per alarm`() =
        runTest {
            alarms.alarms.value += "a" to alarm
            checks.rows.value += "a" to listOf(CheckConfig("a:Math", "a", 0, math, created, created))
            pending.changes.value =
                listOf(
                    change(SettingValue.BaseFeeTier(1)),
                    change(SettingValue.MaxSnoozes(5)),
                    change(SettingValue.GraceSeconds(30), "a"),
                    change(SettingValue.Checks(CheckPlan(CheckMode.Random, listOf(easy))), "a"),
                )

            assertEquals(Outcome.Success(4), promote())

            assertEquals(GlobalSettings(baseFeeTier = 1, maxSnoozes = 5), settings.settings.value)
            val stored = alarms.alarms.value.getValue("a")
            assertEquals(30, stored.graceSeconds)
            assertEquals(CheckMode.Random, stored.checkMode)
            assertEquals(created, stored.updatedAt, "a promotion is not a user edit")
            val row =
                checks.rows.value
                    .getValue("a")
                    .single()
            assertEquals(easy, row.entry)
            assertEquals(created, row.createdAt, "the type it already had keeps its creation time")
            assertTrue(pending.changes.value.isEmpty())
        }

    @Test
    fun `a change waits until its occurrence has passed`() =
        runTest {
            clock.now = sevenThirty
            pending.changes.value = listOf(change(SettingValue.BaseFeeTier(1)))

            assertEquals(Outcome.Success(0), promote())
            assertEquals(3, settings.settings.value.baseFeeTier)

            clock.now = sevenThirty + 1.minutes
            assertEquals(Outcome.Success(1), promote())
            assertEquals(1, settings.settings.value.baseFeeTier)
        }

    @Test
    fun `a change waits while the session for its occurrence is in progress, then applies`() =
        runTest {
            pending.changes.value =
                listOf(change(SettingValue.BaseFeeTier(1)), change(SettingValue.MaxSnoozes(5), after = Occurrence("b", sevenThirty)))
            active = waitsFor

            assertEquals(Outcome.Success(1), promote(), "only the other occurrence's change")
            assertEquals(GlobalSettings(baseFeeTier = 3, maxSnoozes = 5), settings.settings.value)

            active = null
            assertEquals(Outcome.Success(1), promote())
            assertEquals(1, settings.settings.value.baseFeeTier)
        }

    @Test
    fun `an alarm turned off still takes the change, a deleted alarm drops it`() =
        runTest {
            alarms.alarms.value += "a" to alarm.copy(enabled = false)
            pending.changes.value = listOf(change(SettingValue.GraceSeconds(30), "a"), change(SettingValue.GraceSeconds(25), "gone"))

            assertEquals(Outcome.Success(2), promote())
            assertEquals(
                30,
                alarms.alarms.value
                    .getValue("a")
                    .graceSeconds,
            )
            assertTrue(pending.changes.value.isEmpty())
        }

    @Test
    fun `a promoted plan keeps the code registered since, and a plan that can never ring is dropped`() =
        runTest {
            val old = RegisteredCode.of(CodeFormat.QrCode, "old")!!
            val new = RegisteredCode.of(CodeFormat.QrCode, "new")!!
            alarms.alarms.value += "a" to alarm
            checks.rows.value +=
                "a" to
                listOf(
                    CheckConfig(
                        "a:QrBarcode",
                        "a",
                        0,
                        CheckEntry(CheckType.QrBarcode, Difficulty.Medium, 1, new),
                        created,
                        created,
                        created,
                    ),
                )
            val plan = CheckPlan(CheckMode.Random, listOf(CheckEntry(CheckType.QrBarcode, Difficulty.Medium, 1, old), easy))
            pending.changes.value = listOf(change(SettingValue.Checks(plan), "a"))

            assertEquals(Outcome.Success(1), promote())
            val rows = checks.rows.value.getValue("a")
            assertEquals(new, rows.first().entry.code)
            assertEquals(created, rows.first().codeRegisteredAt, "the same code keeps its registration time")

            pending.changes.value = listOf(change(SettingValue.Checks(CheckPlan(CheckMode.All, emptyList())), "a"))
            assertEquals(Outcome.Success(1), promote())
            assertEquals(
                2,
                checks.rows.value
                    .getValue("a")
                    .size,
                "the invalid plan was not stored",
            )
            assertTrue(pending.changes.value.isEmpty())
            assertTrue(logger.events.any { it is LogEvent.OperationFailed })
        }

    @Test
    fun `failures are logged and the change is kept for the next run`() =
        runTest {
            val failure = DomainError.StorageFailure("disk")
            pending.changes.value = listOf(change(SettingValue.BaseFeeTier(1)))

            settings.failure = failure
            assertEquals(Outcome.Success(0), promote())
            assertEquals(1, pending.changes.value.size)

            settings.failure = null
            pending.failure = failure
            assertEquals(Outcome.Failure(failure), promote())

            pending.failure = null
            alarms.alarms.value += "a" to alarm
            checks.failure = failure
            pending.changes.value = listOf(change(SettingValue.Checks(CheckPlan(CheckMode.All, listOf(math))), "a"))
            assertEquals(Outcome.Success(0), promote())
            assertEquals(3, logger.events.count { it is LogEvent.OperationFailed })
            assertEquals(1, pending.changes.value.size)

            checks.failure = null
            pending.changes.value = listOf(change(SettingValue.GraceSeconds(30), "a"))
            alarms.failure = failure
            assertEquals(Outcome.Success(0), promote())
            assertEquals(
                15,
                alarms.alarms.value
                    .getValue("a")
                    .graceSeconds,
            )

            alarms.failure = null
            pending.writeFailure = failure
            assertEquals(Outcome.Success(0), promote(), "live, but the pending change could not be deleted: it runs again")
            assertEquals(
                30,
                alarms.alarms.value
                    .getValue("a")
                    .graceSeconds,
            )
            assertEquals(1, pending.changes.value.size)
        }

    @Test
    fun `the occurrence of a ring or a snooze in progress, none otherwise`() {
        val config = testConfig()
        val expected = Occurrence(config.alarmId, config.scheduledAt)

        assertEquals(expected, PromotePendingChanges.occurrenceOf(SessionState.Ringing(ringSession())))
        assertEquals(expected, PromotePendingChanges.occurrenceOf(SessionState.Snoozed(snoozedSession())))
        assertNull(PromotePendingChanges.occurrenceOf(SessionState.Completed(ringSession())))
        assertNull(PromotePendingChanges.occurrenceOf(SessionState.Idle))
    }

    @Test
    fun `as the scheduling hook it promotes`() =
        runTest {
            pending.changes.value = listOf(change(SettingValue.BaseFeeTier(1)))
            val hook: PendingChangePromotion = promote
            hook.promote()
            assertEquals(1, settings.settings.value.baseFeeTier)
        }

    private val events = InMemoryCommitmentEvents()
    private val sessionState = MutableStateFlow<SessionState>(SessionState.Idle)
    private val record =
        RecordCommitmentEvent(
            events,
            SequentialIds(),
            clock,
            TestZone(TimeZone.UTC),
            SessionLockGuard(sessionState, MutableStateFlow(true), MutableStateFlow(false)),
        )

    @Test
    fun `a commitment event is written only inside the alarm's window`() =
        runTest {
            clock.now = sevenThirty - 7.hours
            val event = assertIs<Outcome.Success<CommitmentEvent?>>(record(alarm, CommitmentAction.Disabled)).value

            assertEquals(CommitmentEvent("id-1", "a", sevenThirty, CommitmentAction.Disabled, clock.now), event)
            assertEquals(listOf(event), events.events)

            clock.now = sevenThirty - 9.hours
            assertEquals(Outcome.Success(null), record(alarm, CommitmentAction.Deleted))
            assertEquals(Outcome.Success(null), record(alarm.copy(enabled = false), CommitmentAction.Deleted))
            assertEquals(1, events.events.size)
        }

    @Test
    fun `a commitment event is refused during a session and a write failure is returned`() =
        runTest {
            clock.now = sevenThirty - 1.hours
            sessionState.value = SessionState.Ringing(ringSession())
            assertEquals(Outcome.Failure(DomainError.SessionActive), record(alarm, CommitmentAction.Deleted))

            sessionState.value = SessionState.Idle
            events.failure = DomainError.StorageFailure("disk")
            assertEquals(Outcome.Failure(DomainError.StorageFailure("disk")), record(alarm, CommitmentAction.Deleted))
            assertTrue(events.events.isEmpty())
        }
}
