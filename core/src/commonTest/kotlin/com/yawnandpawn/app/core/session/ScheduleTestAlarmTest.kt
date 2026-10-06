package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmDraft
import com.yawnandpawn.app.core.alarm.Call
import com.yawnandpawn.app.core.alarm.RecordingLogger
import com.yawnandpawn.app.core.alarm.RecordingScheduler
import com.yawnandpawn.app.core.alarm.TestClock
import com.yawnandpawn.app.core.checks.CheckAnswer
import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.CheckMode
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.Difficulty
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Story 1.18: "Test alarm" rings the editor's values as a test 10 s later, through the test request code. */
class ScheduleTestAlarmTest {
    private val now = Instant.parse("2027-03-03T06:00:00Z")
    private val clock = TestClock(now)
    private val scheduler = RecordingScheduler()
    private val store = InMemoryTestAlarmStore()
    private val logger = RecordingLogger()
    private val schedule = ScheduleTestAlarm(scheduler, store, clock, logger)

    private val draft =
        AlarmDraft(
            id = null,
            time = LocalTime(7, 30),
            repeatDays = setOf(DayOfWeek.MONDAY),
            label = "  Gym  ",
            soundRef = "builtin:rain",
            volumePercent = 55,
            gradualVolume = false,
            vibration = false,
            snoozeLengthMinutes = 5,
            graceSeconds = 25,
        )

    @Test
    fun `it stores the unsaved values as a test config and arms the test alarm 10 s ahead`() =
        runTest {
            assertEquals(Outcome.Success(now + 10.seconds), schedule(draft))

            assertEquals(listOf<Call>(Call.Test(now + 10.seconds)), scheduler.calls)
            val config = store.pending!!
            assertTrue(config.testMode)
            assertEquals(ConfigResolver.TEST_ALARM_ID, config.alarmId)
            assertEquals("Gym", config.label)
            assertEquals(now + 10.seconds, config.scheduledAt)
            assertEquals("builtin:rain", config.soundRef)
            assertEquals(55, config.volumePercent)
            assertEquals(false, config.gradualVolume)
            assertEquals(Alarm.DEFAULT_RAMP_START_PERCENT, config.rampStartPercent)
            assertEquals(false, config.vibration)
            assertEquals(5, config.snoozeLengthMinutes)
            assertEquals(25, config.graceSeconds)
            assertEquals(CheckPlan(CheckMode.Random, listOf(CheckEntry(CheckType.Math, Difficulty.Medium, count = 3))), config.checkPlan)
        }

    @Test
    fun `a stored alarm keeps its id and a blank label means none`() =
        runTest {
            schedule(draft.copy(id = "alarm-7", label = "   "))

            assertEquals("alarm-7", store.pending?.alarmId)
            assertNull(store.pending?.label)
        }

    @Test
    fun `when the test alarm cannot be armed the pending config is taken back and the failure returned`() =
        runTest {
            scheduler.failure = DomainError.ExactAlarmNotPermitted

            assertEquals(Outcome.Failure(DomainError.ExactAlarmNotPermitted), schedule(draft))
            assertNull(store.pending)
        }

    @Test
    fun `a pending config that cannot be taken back after a failed arm is logged`() =
        runTest {
            scheduler.failure = DomainError.ExactAlarmNotPermitted
            store.takeFailure = DomainError.StorageFailure("disk full")

            assertEquals(Outcome.Failure(DomainError.ExactAlarmNotPermitted), schedule(draft))

            assertEquals(
                listOf<LogEvent>(LogEvent.OperationFailed.of("take back pending test alarm", DomainError.StorageFailure("disk full"))),
                logger.events,
            )
        }

    @Test
    fun `when the config cannot be stored nothing is armed`() =
        runTest {
            store.putFailure = DomainError.StorageFailure("disk full")

            assertEquals(Outcome.Failure(DomainError.StorageFailure("disk full")), schedule(draft))
            assertEquals(emptyList(), scheduler.calls)
        }

    @Test
    fun `a pending config round trips through the pinned JSON, and garbage reads as none`() {
        val config = ConfigResolver.resolveTest(draft, GlobalSettings(), now)

        assertEquals(config, SessionJson.decodeConfig(SessionJson.encodeConfig(config)))
        assertNull(SessionJson.decodeConfig("{not json"))
    }
}

/** An in-memory [TestAlarmStore]; [putFailure] fails [TestAlarmStore.put]. */
internal class InMemoryTestAlarmStore : TestAlarmStore {
    var pending: SessionConfig? = null
    var putFailure: DomainError? = null
    var takeFailure: DomainError? = null

    override suspend fun put(config: SessionConfig): Outcome<Unit, DomainError> {
        putFailure?.let { return Outcome.Failure(it) }
        pending = config
        return Outcome.Success(Unit)
    }

    override suspend fun take(): Outcome<SessionConfig?, DomainError> {
        takeFailure?.let { return Outcome.Failure(it) }
        return Outcome.Success(pending).also { pending = null }
    }
}
