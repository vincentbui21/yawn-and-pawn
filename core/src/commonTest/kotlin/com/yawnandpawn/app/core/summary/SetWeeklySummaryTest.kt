package com.yawnandpawn.app.core.summary

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.SessionLockGuard
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.summary.WorkCall.Cancel
import com.yawnandpawn.app.core.summary.WorkCall.Schedule
import com.yawnandpawn.app.core.work.SchedulePolicy.Replace
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

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

    @Test
    fun `a ring can start while the work is still being aligned, as the guard is already released`() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val slowStore =
                object : WeeklySummaryStore by store {
                    // The alignment's read of the switch waits; the guarded write before it does not.
                    override suspend fun read(): Outcome<WeeklySummaryPrefs, DomainError> {
                        release.await()
                        return store.read()
                    }
                }
            val clock = StoppedClock(berlin("2027-03-06T19:00"))
            val slowScheduler = WeeklySummaryScheduler(work, slowStore, clock, SetZone(), Lock(), Logs())
            val toggle = async { SetWeeklySummary(slowStore, slowScheduler, sessionLock)(false) }
            yield()
            assertEquals(false, store.state.value.enabled, "stored under the guard")

            val started = withTimeoutOrNull(1.seconds) { sessionLock.startingSession { "ringing" } }

            assertEquals("ringing", started)
            release.complete(Unit)
            assertEquals(Outcome.Success(Unit), toggle.await())
            assertEquals(listOf<WorkCall>(Cancel), work.calls)
        }
}
