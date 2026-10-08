package com.yawnandpawn.app.core.alarm

import com.yawnandpawn.app.core.config.InMemoryPendingChanges
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.FireKind
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.session.SessionLockGuard
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.ringSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Story 1.10 I/O matrix: the alarm use cases, `rescheduleAll()` and the Epic 1 fire handler against the scheduler port. */
class AlarmSchedulingTest {
    private val berlin = TimeZone.of("Europe/Berlin")
    private val newYork = TimeZone.of("America/New_York")
    private val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

    private fun berlin(text: String): Instant = LocalDateTime.parse(text).toInstant(berlin)

    // Wednesday 2027-03-03, 06:00 in Berlin.
    private val clock = TestClock(berlin("2027-03-03T06:00"))
    private val zone = TestZone(berlin)
    private val ids = SequentialIds()
    private val repository = InMemoryAlarms()
    private val lock = AlarmWriteLock()
    private val sequence = InMemorySequence()
    private val scheduler = RecordingScheduler()
    private val logger = RecordingLogger()
    private val scheduling = AlarmScheduling(repository, scheduler, clock, zone, lock, logger)
    private val sessionState = MutableStateFlow<SessionState>(SessionState.Idle)
    private val sessionLock = SessionLockGuard(sessionState, restored = MutableStateFlow(true), emergency = MutableStateFlow(false))
    private val checkConfigs = InMemoryCheckConfigs(repository)
    private val save =
        SaveAlarm(repository, ids, clock, lock, sequence, scheduling, sessionLock, checkConfigs, InMemoryPendingChanges(), zone)
    private val setEnabled = SetAlarmEnabled(repository, clock, lock, scheduling, sessionLock)
    private val delete = DeleteAlarm(repository, lock, scheduling, sessionLock, checkConfigs)
    private val duplicate = DuplicateAlarm(repository, ids, clock, lock, sequence, scheduling, sessionLock, checkConfigs)
    private val onFire = RearmOnFire(repository, scheduling, clock, lock, logger)

    private val sevenAm = AlarmDraft(time = LocalTime(7, 0))

    private suspend fun saved(draft: AlarmDraft = sevenAm): Alarm = assertIs<Outcome.Success<Alarm>>(save(draft)).value

    /** Puts [alarm] straight into the repository, as an earlier app run would have left it. */
    private fun stored(alarm: Alarm): Alarm = alarm.also { repository.alarms.value += it.id to it }

    private fun alarm(
        id: String,
        code: Int,
        time: LocalTime = LocalTime(7, 0),
        repeatDays: Set<DayOfWeek> = emptySet(),
        enabled: Boolean = true,
    ) = Alarm(
        id = id,
        time = time,
        repeatDays = repeatDays,
        enabled = enabled,
        requestCode = code,
        createdAt = clock.now,
        updatedAt = clock.now,
    )

    private fun schedule(
        alarm: Alarm,
        at: Instant,
    ) = Call.Schedule(alarm.id, alarm.requestCode, at)

    @Test
    fun `saving an enabled alarm arms it at its next occurrence`() =
        runTest {
            val alarm = saved()

            assertEquals(listOf<Call>(schedule(alarm, berlin("2027-03-03T07:00"))), scheduler.calls)
        }

    @Test
    fun `saving a disabled alarm cancels its code`() =
        runTest {
            val alarm = saved(sevenAm.copy(enabled = false))

            assertEquals(listOf<Call>(Call.Cancel(alarm.requestCode)), scheduler.calls)
        }

    @Test
    fun `editing an alarm re-arms it under the same code at the new time`() =
        runTest {
            val alarm = saved()
            scheduler.calls.clear()

            save(sevenAm.copy(id = alarm.id, time = LocalTime(6, 30)))

            assertEquals(listOf<Call>(schedule(alarm, berlin("2027-03-03T06:30"))), scheduler.calls)
        }

    @Test
    fun `disabling cancels the code and enabling arms it again`() =
        runTest {
            val alarm = saved()
            scheduler.calls.clear()

            setEnabled(alarm.id, enabled = false)
            setEnabled(alarm.id, enabled = true)

            assertEquals(listOf(Call.Cancel(alarm.requestCode), schedule(alarm, berlin("2027-03-03T07:00"))), scheduler.calls)
        }

    @Test
    fun `deleting cancels the code after the row is gone`() =
        runTest {
            val alarm = saved()
            scheduler.calls.clear()
            var storedAtCancel: Boolean? = null
            scheduler.onCall = { storedAtCancel = alarm.id in repository.alarms.value }

            assertEquals(Outcome.Success(Unit), delete(alarm.id))

            assertEquals(listOf<Call>(Call.Cancel(alarm.requestCode)), scheduler.calls)
            assertEquals(false, storedAtCancel)
        }

    @Test
    fun `a failed delete makes no scheduler call`() =
        runTest {
            val alarm = saved()
            scheduler.calls.clear()
            val failure = DomainError.StorageFailure("disk full")
            repository.deleteFailure = failure

            assertEquals(Outcome.Failure(failure), delete(alarm.id))
            assertEquals(Outcome.Failure(DomainError.NotFound("missing")), delete("missing"))
            assertEquals(emptyList(), scheduler.calls)
        }

    @Test
    fun `duplicating an enabled alarm arms the copy under a new, higher code`() =
        runTest {
            val original = saved()
            scheduler.calls.clear()

            val copy = assertIs<Outcome.Success<Alarm>>(duplicate(original.id)).value

            assertTrue(copy.requestCode > original.requestCode)
            assertEquals(listOf<Call>(schedule(copy, berlin("2027-03-03T07:00"))), scheduler.calls)
        }

    @Test
    fun `duplicating a disabled alarm cancels the copy's code`() =
        runTest {
            val original = saved(sevenAm.copy(enabled = false))
            scheduler.calls.clear()

            val copy = assertIs<Outcome.Success<Alarm>>(duplicate(original.id)).value

            assertEquals(listOf<Call>(Call.Cancel(copy.requestCode)), scheduler.calls)
        }

    @Test
    fun `a failed repository write makes no scheduler call and returns the failure`() =
        runTest {
            val alarm = saved()
            scheduler.calls.clear()
            val failure = DomainError.StorageFailure("disk full")
            repository.failure = failure

            assertEquals(Outcome.Failure(failure), save(sevenAm))
            assertEquals(Outcome.Failure(failure), save(sevenAm.copy(id = alarm.id)))
            assertEquals(Outcome.Failure(failure), setEnabled(alarm.id, enabled = false))
            assertEquals(Outcome.Failure(failure), duplicate(alarm.id))
            assertEquals(Outcome.Failure(DomainError.InvalidAlarm(AlarmField.GraceSeconds)), save(sevenAm.copy(graceSeconds = 99)))
            assertEquals(emptyList(), scheduler.calls)
        }

    @Test
    fun `a denied scheduler is logged and the use case still succeeds with the alarm stored`() =
        runTest {
            scheduler.failure = DomainError.ExactAlarmNotPermitted

            val alarm = saved()
            val disabled = setEnabled(alarm.id, enabled = false)

            assertIs<Outcome.Success<Alarm>>(disabled)
            assertEquals(
                listOf(alarm.copy(enabled = false)),
                repository.alarms.value.values
                    .toList(),
            )
            assertEquals(Outcome.Success(Unit), delete(alarm.id))
            assertEquals(
                listOf<LogEvent>(
                    // Only arming needs the permission; the cancels on disable and delete go through.
                    LogEvent.OperationFailed("schedule alarm", "exact alarms not permitted"),
                ),
                logger.events,
            )
        }

    @Test
    fun `rescheduleAll twice makes the same calls, arming every enabled alarm and cancelling every disabled code`() =
        runTest {
            val early = stored(alarm("early", 1000, LocalTime(6, 30)))
            val late = stored(alarm("late", 1003, LocalTime(22, 0), repeatDays = weekdays))
            val off = stored(alarm("off", 1001, LocalTime(9, 0), enabled = false))

            assertEquals(Outcome.Success(Unit), scheduling.rescheduleAll())
            val first = scheduler.calls.toList()
            scheduler.calls.clear()
            assertEquals(Outcome.Success(Unit), scheduling.rescheduleAll())

            assertEquals(
                listOf(
                    schedule(early, berlin("2027-03-03T06:30")),
                    Call.Cancel(off.requestCode),
                    schedule(late, berlin("2027-03-03T22:00")),
                ),
                first,
            )
            assertEquals(first, scheduler.calls)
            assertEquals(LogEvent.AlarmsRescheduled(scheduled = 2, disabled = 1, failed = 0), logger.events.last())
        }

    @Test
    fun `rescheduleAll on a DST spring-forward day arms a 02 30 alarm at 03 30 local`() =
        runTest {
            // Berlin skips 02:00 to 03:00 on Sunday 2027-03-28; the boot broadcast arrives just after midnight.
            clock.now = berlin("2027-03-28T00:05")
            val night = stored(alarm("night", 1000, LocalTime(2, 30)))

            scheduling.rescheduleAll()

            assertEquals(listOf<Call>(schedule(night, Instant.parse("2027-03-28T01:30:00Z"))), scheduler.calls)
            assertEquals(LocalDateTime.parse("2027-03-28T03:30"), Instant.parse("2027-03-28T01:30:00Z").toLocalDateTime(berlin))
        }

    @Test
    fun `after a zone change from Berlin to New York rescheduleAll re-arms at 07 00 New York time`() =
        runTest {
            val alarm = saved()
            assertEquals(listOf<Call>(schedule(alarm, berlin("2027-03-03T07:00"))), scheduler.calls)
            scheduler.calls.clear()

            zone.zone = newYork
            scheduling.rescheduleAll()

            // 06:00 Berlin is 00:00 in New York, so 07:00 New York is later the same day.
            assertEquals(listOf<Call>(schedule(alarm, LocalDateTime.parse("2027-03-03T07:00").toInstant(newYork))), scheduler.calls)
        }

    @Test
    fun `rescheduleAll returns and logs a failed read and makes no scheduler call`() =
        runTest {
            stored(alarm("a", 1000))
            val failure = DomainError.StorageFailure("corrupt")
            repository.listFailure = failure

            assertEquals(Outcome.Failure(failure), scheduling.rescheduleAll())
            assertEquals(emptyList(), scheduler.calls)
            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed("reschedule alarms", "storage failure: corrupt")), logger.events)
        }

    @Test
    fun `rescheduleAll counts denied arming calls as failed and still cancels disabled codes`() =
        runTest {
            stored(alarm("a", 1000))
            stored(alarm("b", 1001, enabled = false))
            scheduler.failure = DomainError.ExactAlarmNotPermitted

            assertEquals(Outcome.Success(Unit), scheduling.rescheduleAll())

            assertEquals(2, scheduler.calls.size)
            assertEquals(LogEvent.AlarmsRescheduled(scheduled = 0, disabled = 1, failed = 1), logger.events.last())
        }

    @Test
    fun `a one-time alarm whose time passed while the phone was off is switched off and cancelled, not moved to tomorrow`() =
        runTest {
            // Saved at 06:00 for 07:00; the phone was off from 06:30 until 09:00.
            val once = stored(alarm("once", 1000))
            clock.now = berlin("2027-03-03T09:00")

            assertEquals(Outcome.Success(Unit), scheduling.rescheduleAll())

            assertEquals(listOf<Call>(Call.Cancel(once.requestCode)), scheduler.calls)
            assertEquals(once.copy(enabled = false, updatedAt = berlin("2027-03-03T09:00")), repository.alarms.value.getValue(once.id))
            assertEquals(
                listOf<LogEvent>(
                    LogEvent.OneTimeAlarmPassed(once.id, missedAt = berlin("2027-03-03T07:00")),
                    LogEvent.AlarmsRescheduled(scheduled = 0, disabled = 1, failed = 0),
                ),
                logger.events,
            )

            // A second run sees a disabled alarm and makes the same call.
            scheduler.calls.clear()
            scheduling.rescheduleAll()
            assertEquals(listOf<Call>(Call.Cancel(once.requestCode)), scheduler.calls)
        }

    @Test
    fun `a one-time alarm skipped by a clock jump is switched off too`() =
        runTest {
            val once = stored(alarm("once", 1000))
            // The clock is set forward past 07:00.
            clock.now = berlin("2027-03-03T07:00") + 1.minutes

            scheduling.rescheduleAll()

            assertEquals(listOf<Call>(Call.Cancel(once.requestCode)), scheduler.calls)
            assertEquals(
                false,
                repository.alarms.value
                    .getValue(once.id)
                    .enabled,
            )
        }

    @Test
    fun `a one-time alarm whose time is still ahead is armed as before`() =
        runTest {
            val once = stored(alarm("once", 1000))
            clock.now = berlin("2027-03-03T06:59")

            scheduling.rescheduleAll()

            assertEquals(listOf<Call>(schedule(once, berlin("2027-03-03T07:00"))), scheduler.calls)
            assertEquals(
                true,
                repository.alarms.value
                    .getValue(once.id)
                    .enabled,
            )
        }

    @Test
    fun `a repeating alarm whose last occurrence passed is armed for its next one`() =
        runTest {
            val weekday = stored(alarm("weekday", 1000, repeatDays = weekdays))
            clock.now = berlin("2027-03-03T09:00")

            scheduling.rescheduleAll()

            assertEquals(listOf<Call>(schedule(weekday, berlin("2027-03-04T07:00"))), scheduler.calls)
            assertEquals(
                true,
                repository.alarms.value
                    .getValue(weekday.id)
                    .enabled,
            )
        }

    @Test
    fun `a passed one-time alarm that cannot be stored disabled is logged, cancelled and not armed`() =
        runTest {
            val once = stored(alarm("once", 1000))
            clock.now = berlin("2027-03-03T09:00")
            repository.failure = DomainError.StorageFailure("disk full")

            scheduling.rescheduleAll()

            assertEquals(listOf<Call>(Call.Cancel(once.requestCode)), scheduler.calls)
            assertEquals(LogEvent.OperationFailed("disable passed one-time alarm", "storage failure: disk full"), logger.events.first())
        }

    @Test
    fun `a repeating alarm that fired on Monday at 07 00 is armed for Tuesday at 07 00`() =
        runTest {
            val monday = berlin("2027-03-08T07:00")
            clock.now = monday + 40.milliseconds
            val weekday = stored(alarm("weekday", 1000, repeatDays = weekdays))

            onFire.onAlarmFired(AlarmFired(weekday.id, scheduledAt = monday))

            assertEquals(listOf<Call>(schedule(weekday, berlin("2027-03-09T07:00"))), scheduler.calls)
            assertEquals(
                true,
                repository.alarms.value
                    .getValue(weekday.id)
                    .enabled,
            )
        }

    @Test
    fun `a repeating alarm that fired a little early is not armed for the same occurrence again`() =
        runTest {
            val monday = berlin("2027-03-08T07:00")
            clock.now = monday - 2.seconds
            val weekday = stored(alarm("weekday", 1000, repeatDays = weekdays))

            onFire.onAlarmFired(AlarmFired(weekday.id, scheduledAt = monday))

            assertEquals(listOf<Call>(schedule(weekday, berlin("2027-03-09T07:00"))), scheduler.calls)
        }

    @Test
    fun `a repeating alarm that fired late is armed for its next occurrence after now`() =
        runTest {
            val monday = berlin("2027-03-08T07:00")
            // The phone was off from Monday until Wednesday 08:00.
            clock.now = berlin("2027-03-10T08:00")
            val weekday = stored(alarm("weekday", 1000, repeatDays = weekdays))

            onFire.onAlarmFired(AlarmFired(weekday.id, scheduledAt = monday))

            assertEquals(listOf<Call>(schedule(weekday, berlin("2027-03-11T07:00"))), scheduler.calls)
        }

    @Test
    fun `a one-time alarm that fired is switched off, its code cancelled, and that is logged`() =
        runTest {
            val once = saved()
            scheduler.calls.clear()
            val logged = logger.events.size
            clock.now = berlin("2027-03-03T07:00") + 10.milliseconds

            onFire.onAlarmFired(AlarmFired(once.id, scheduledAt = berlin("2027-03-03T07:00")))

            assertEquals(
                false,
                repository.alarms.value
                    .getValue(once.id)
                    .enabled,
            )
            assertEquals(listOf<Call>(Call.Cancel(once.requestCode)), scheduler.calls)
            assertEquals(listOf<LogEvent>(LogEvent.OneTimeAlarmDisabled(once.id, berlin("2027-03-03T07:00"))), logger.events.drop(logged))
        }

    @Test
    fun `during a session a fire still switches a one-time alarm off and re-arms a repeating one (Story 2-6)`() =
        runTest {
            val once = saved()
            val weekday = stored(alarm("weekday", 1001, repeatDays = weekdays))
            scheduler.calls.clear()
            // The fire started the session before it re-arms: the user's use cases are locked now.
            sessionState.value = SessionState.Ringing(ringSession())
            clock.now = berlin("2027-03-03T07:00") + 10.milliseconds

            onFire.onAlarmFired(AlarmFired(once.id, scheduledAt = berlin("2027-03-03T07:00")))
            onFire.onAlarmFired(AlarmFired(weekday.id, scheduledAt = berlin("2027-03-03T07:00")))

            assertEquals(
                false,
                repository.alarms.value
                    .getValue(once.id)
                    .enabled,
            )
            assertEquals(
                listOf<Call>(Call.Cancel(once.requestCode), schedule(weekday, berlin("2027-03-04T07:00"))),
                scheduler.calls,
            )
            assertEquals(Outcome.Failure(DomainError.SessionActive), setEnabled(weekday.id, enabled = false), "the user still cannot")
        }

    @Test
    fun `a fire for a deleted or disabled alarm arms nothing and is logged`() =
        runTest {
            val off = stored(alarm("off", 1001, enabled = false))

            onFire.onAlarmFired(AlarmFired("gone", scheduledAt = clock.now))
            onFire.onAlarmFired(AlarmFired(off.id, scheduledAt = clock.now))

            assertEquals(emptyList(), scheduler.calls)
            assertEquals(
                listOf<LogEvent>(
                    LogEvent.FireIgnored(FireKind.Alarm, "gone", "not found: gone"),
                    LogEvent.FireIgnored(FireKind.Alarm, "off", "alarm disabled"),
                ),
                logger.events,
            )
            assertEquals(
                false,
                repository.alarms.value
                    .getValue("off")
                    .enabled,
            )
        }

    @Test
    fun `a one-time alarm that cannot be switched off after its fire is logged`() =
        runTest {
            val once = stored(alarm("once", 1000))
            repository.failure = DomainError.StorageFailure("disk full")

            onFire.onAlarmFired(AlarmFired(once.id, scheduledAt = clock.now))

            assertEquals(emptyList(), scheduler.calls)
            assertEquals(
                listOf<LogEvent>(LogEvent.OperationFailed("disable fired one-time alarm", "storage failure: disk full")),
                logger.events,
            )
        }

    @Test
    fun `session-slot and test fires are logged and ignored until their stories bind them`() =
        runTest {
            onFire.onSessionSlotFired(null)
            onFire.onTestAlarmFired()

            assertEquals(emptyList(), scheduler.calls)
            assertEquals(
                listOf(FireKind.SessionSlot, FireKind.TestAlarm),
                logger.events.map { assertIs<LogEvent.FireIgnored>(it).kind },
            )
        }

    @Test
    fun `the Home countdown and the scheduler agree on the next occurrence`() =
        runTest {
            clock.now = berlin("2027-03-03T06:59") + 30.seconds
            val alarm = saved()

            val armed = assertIs<Call.Schedule>(scheduler.calls.single()).at
            assertEquals(nextOccurrence(alarm.toRule(), clock.now, berlin), armed)
            assertEquals(30.seconds, durationUntil(armed, clock.now))
            assertTrue(durationUntil(armed, clock.now) < 1.minutes)
        }
}
