package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmDraft
import com.yawnandpawn.app.core.alarm.RequestCodes
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.time.Deadline
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SchedulerFakesTest {
    @Test
    fun `the fake scheduler records every call in order and tracks what is armed`() {
        val scheduler = FakeAlarmScheduler()
        val slot = Deadline(wallMillis = 5_000, elapsedMillis = 50, bootCount = 1)

        scheduler.schedule("a", 1000, 1_000)
        scheduler.schedule("b", 1001, 2_000)
        scheduler.schedule("a", 1000, 3_000)
        scheduler.cancel(1001)
        scheduler.armSessionSlot(slot)
        scheduler.scheduleTest(4_000)
        scheduler.cancelSessionSlot()

        assertEquals(
            listOf(
                SchedulerCall.Schedule("a", 1000, 1_000),
                SchedulerCall.Schedule("b", 1001, 2_000),
                SchedulerCall.Schedule("a", 1000, 3_000),
                SchedulerCall.Cancel(1001),
                SchedulerCall.ArmSessionSlot(slot),
                SchedulerCall.ScheduleTest(4_000),
                SchedulerCall.CancelSessionSlot,
            ),
            scheduler.calls,
        )
        assertEquals(mapOf(1000 to 3_000L, RequestCodes.TEST_ALARM to 4_000L), scheduler.armed)

        scheduler.clearCalls()
        assertEquals(emptyList(), scheduler.calls)
        assertEquals(2, scheduler.armed.size)
    }

    @Test
    fun `a set failure fails every arming call, records it and arms nothing, while cancelling still succeeds`() {
        val scheduler = FakeAlarmScheduler()
        scheduler.schedule("a", 1000, 1_000)
        scheduler.failure = DomainError.ExactAlarmNotPermitted

        val arming =
            listOf(
                scheduler.schedule("b", 1001, 1_000),
                scheduler.armSessionSlot(Deadline(1, 1, 1)),
                scheduler.scheduleTest(1),
            )

        arming.forEach { assertEquals(Outcome.Failure(DomainError.ExactAlarmNotPermitted), it) }
        assertEquals(mapOf(1000 to 1_000L), scheduler.armed)
        assertEquals(Outcome.Success(Unit), scheduler.cancel(1000))
        assertEquals(Outcome.Success(Unit), scheduler.cancelSessionSlot())
        assertEquals(6, scheduler.calls.size)
        assertTrue(scheduler.armed.isEmpty())
    }

    @Test
    fun `the fake sequence hands out the mark plus one, starting at the first alarm code`() =
        runTest {
            val sequence = FakeRequestCodeSequence()

            assertEquals(Outcome.Success(1000), sequence.next())
            assertEquals(Outcome.Success(1001), sequence.next())
            assertEquals(1001, sequence.lastUsed)
            assertEquals(Outcome.Success(1006), FakeRequestCodeSequence(lastUsed = 1005).next())
        }

    @Test
    fun `a set failure fails the sequence and hands out nothing`() =
        runTest {
            val sequence = FakeRequestCodeSequence()
            val failure = DomainError.StorageFailure("disk full")
            sequence.failure = failure

            assertEquals(Outcome.Failure(failure), sequence.next())
            assertEquals(RequestCodes.INITIAL_HIGH_WATER_MARK, sequence.lastUsed)
        }

    @Test
    fun `the use case fixture wires save, delete and the scheduler together`() =
        runTest {
            val scheduler = FakeAlarmScheduler()
            val fixture = AlarmUseCasesFixture(scheduler = scheduler)

            val first = assertIs<Outcome.Success<Alarm>>(fixture.save(AlarmDraft(time = LocalTime(7, 0)))).value
            val second = assertIs<Outcome.Success<Alarm>>(fixture.save(AlarmDraft(time = LocalTime(8, 0)))).value
            fixture.delete(second.id)
            val third = assertIs<Outcome.Success<Alarm>>(fixture.save(AlarmDraft(time = LocalTime(9, 0)))).value

            assertEquals(listOf(1000, 1001, 1002), listOf(first.requestCode, second.requestCode, third.requestCode))
            assertEquals(setOf(1000, 1002), scheduler.armed.keys)
            // FakeClock starts at 06:00 UTC; the fixture's zone is UTC, so 07:00 is one hour later.
            assertEquals(DEFAULT_FAKE_INSTANT.toEpochMilliseconds() + 3_600_000, scheduler.armed[1000])
        }
}
