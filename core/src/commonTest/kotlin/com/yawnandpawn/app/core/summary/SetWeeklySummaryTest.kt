package com.yawnandpawn.app.core.summary

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.SessionLockGuard
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.summary.WorkCall.Cancel
import com.yawnandpawn.app.core.summary.WorkCall.Schedule
import com.yawnandpawn.app.core.work.SchedulePolicy.Replace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours

/** Story 6.8: the Settings switch stores the choice and aligns the work at once, never during a session. */
class SetWeeklySummaryTest {
    private val work = WeeklyWork()
    private val store = MemoryWeeklyStore()
    private val restored = MutableStateFlow(true)
    private val sessionLock = SessionLockGuard(MutableStateFlow<SessionState>(SessionState.Idle), restored, MutableStateFlow(false))
    private val scheduler = WeeklySummaryScheduler(work, store, StoppedClock(berlin("2027-03-06T19:00")), SetZone(), Lock(), Logs())
    private val setWeeklySummary = SetWeeklySummary(store, scheduler, sessionLock)

    @Test
    fun `off stores the choice and cancels the work, on stores it and enqueues the next run`() =
        runTest {
            assertEquals(Outcome.Success(Unit), setWeeklySummary(false))
            assertEquals(false, store.state.value.enabled)

            assertEquals(Outcome.Success(Unit), setWeeklySummary(true))
            assertEquals(true, store.state.value.enabled)

            assertEquals(listOf(Cancel, Schedule(24.hours, Replace)), work.calls)
        }

    @Test
    fun `during a session nothing is written and the work is left alone`() =
        runTest {
            // Not restored yet counts as locked (Story 2.6).
            restored.value = false

            assertEquals(Outcome.Failure(DomainError.SessionActive), setWeeklySummary(false))

            assertEquals(emptyList(), store.writes)
            assertEquals(emptyList(), work.calls)
        }

    @Test
    fun `a store write failure touches no work`() =
        runTest {
            val failure = DomainError.StorageFailure("disk")
            store.failWrite = failure

            assertEquals(Outcome.Failure(failure), setWeeklySummary(false))

            assertEquals(emptyList(), work.calls)
        }

    @Test
    fun `a work failure after the write is returned, with the choice stored`() =
        runTest {
            val failure = DomainError.BackgroundWorkFailure("weekly-summary: IllegalStateException")
            work.failure = failure

            assertEquals(Outcome.Failure(failure), setWeeklySummary(false))

            assertEquals(false, store.state.value.enabled)
        }
}
