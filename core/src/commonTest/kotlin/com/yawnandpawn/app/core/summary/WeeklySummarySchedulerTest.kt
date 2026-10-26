package com.yawnandpawn.app.core.summary

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.stats.IsoWeek
import com.yawnandpawn.app.core.summary.WorkCall.Cancel
import com.yawnandpawn.app.core.summary.WorkCall.Schedule
import com.yawnandpawn.app.core.work.SchedulePolicy.AfterRun
import com.yawnandpawn.app.core.work.SchedulePolicy.KeepPending
import com.yawnandpawn.app.core.work.SchedulePolicy.Replace
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/** Story 6.8: the scheduler keeps the one `weekly-summary` work at the next Sunday 19:00 and never cancels a due run. */
class WeeklySummarySchedulerTest {
    private val work = WeeklyWork()
    private val store = MemoryWeeklyStore()

    // Saturday 2027-03-06 19:00 in Berlin: the next target is 24 h away.
    private val clock = StoppedClock(berlin("2027-03-06T19:00"))
    private val zones = SetZone()
    private val lock = Lock()
    private val logs = Logs()
    private val scheduler = WeeklySummaryScheduler(work, store, clock, zones, lock, logs)

    private val failed = DomainError.BackgroundWorkFailure("user locked: weekly-summary")

    @Test
    fun `on and not due, a sync replaces the work at the next Sunday 19 00`() =
        runTest {
            assertEquals(Outcome.Success(Unit), scheduler.sync())

            assertEquals(listOf<WorkCall>(Schedule(24.hours, Replace)), work.calls)
        }

    @Test
    fun `off, a sync cancels the work`() =
        runTest {
            store.state.value = WeeklySummaryPrefs(enabled = false)

            scheduler.sync()

            assertEquals(listOf<WorkCall>(Cancel), work.calls)
        }

    @Test
    fun `while a run is due and unhandled, the pending run is kept and one is enqueued at once if none`() =
        runTest {
            clock.now = berlin("2027-03-07T19:30")

            scheduler.sync()

            assertEquals(listOf<WorkCall>(Schedule(Duration.ZERO, KeepPending)), work.calls)
        }

    @Test
    fun `once the due week is handled, a sync replaces the work at next week's target`() =
        runTest {
            clock.now = berlin("2027-03-07T19:30")
            store.state.value = WeeklySummaryPrefs(lastHandledWeek = IsoWeek(2027, 9))

            scheduler.sync()

            assertEquals(listOf<WorkCall>(Schedule(berlin("2027-03-14T19:00") - clock.now, Replace)), work.calls)
        }

    @Test
    fun `the zone of now decides the target`() =
        runTest {
            zones.zone = TimeZone.of("Asia/Ho_Chi_Minh")

            scheduler.sync()

            // Saturday 18:00Z is Sunday 01:00 in Ho Chi Minh City: its Sunday 19:00 is 18 h away.
            assertEquals(listOf<WorkCall>(Schedule(18.hours, Replace)), work.calls)
        }

    @Test
    fun `locked, nothing reaches the work and nothing is logged`() =
        runTest {
            lock.unlocked = false

            assertEquals(Outcome.Success(Unit), scheduler.sync())
            assertEquals(Outcome.Success(Unit), scheduler.start())

            assertEquals(emptyList(), work.calls)
            assertEquals(emptyList(), logs.events)
        }

    @Test
    fun `a store that cannot be read counts as on, and is logged`() =
        runTest {
            store.state.value = WeeklySummaryPrefs(enabled = false)
            store.failRead = DomainError.StorageFailure("disk")

            scheduler.sync()

            assertEquals(listOf<WorkCall>(Schedule(24.hours, Replace)), work.calls)
            assertEquals(
                listOf<LogEvent>(LogEvent.OperationFailed.of("schedule weekly summary", DomainError.StorageFailure("disk"))),
                logs.events,
            )
        }

    @Test
    fun `a work failure is logged and returned`() =
        runTest {
            work.failure = failed

            assertEquals(Outcome.Failure(failed), scheduler.sync())

            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed.of("schedule weekly summary", failed)), logs.events)
        }

    @Test
    fun `start keeps a pending work, once per process, and tries again after a failure or a locked start`() =
        runTest {
            lock.unlocked = false
            scheduler.start()
            lock.unlocked = true
            work.failure = failed
            assertIs<Outcome.Failure<*>>(scheduler.start())
            work.failure = null

            scheduler.start()
            scheduler.start()

            assertEquals(listOf<WorkCall>(Schedule(24.hours, KeepPending)), work.calls)
        }

    @Test
    fun `start while a run is due keeps it, and start while off cancels`() =
        runTest {
            clock.now = berlin("2027-03-07T19:30")
            scheduler.start()
            val off = WeeklySummaryScheduler(work, MemoryWeeklyStore(WeeklySummaryPrefs(enabled = false)), clock, zones, lock, logs)
            off.start()

            assertEquals(listOf(Schedule(Duration.ZERO, KeepPending), Cancel), work.calls)
        }

    @Test
    fun `after a run, next week's work is chained after it, and nothing when off`() =
        runTest {
            clock.now = berlin("2027-03-07T19:00:05")

            scheduler.afterRun()
            store.state.value = WeeklySummaryPrefs(enabled = false)
            scheduler.afterRun()

            assertEquals(listOf<WorkCall>(Schedule(berlin("2027-03-14T19:00") - clock.now, AfterRun)), work.calls)
        }

    @Test
    fun `an after-run that cannot hand over is logged`() =
        runTest {
            work.failure = failed

            assertEquals(Outcome.Failure(failed), scheduler.afterRun())

            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed.of("schedule weekly summary", failed)), logs.events)
        }

    @Test
    fun `concurrent syncs are serialized`() =
        runTest {
            var inside = false
            var overlapped = false
            val release = CompletableDeferred<Unit>()
            val slowStore =
                object : WeeklySummaryStore by store {
                    override suspend fun read(): Outcome<WeeklySummaryPrefs, DomainError> {
                        if (inside) overlapped = true
                        inside = true
                        release.await()
                        yield()
                        inside = false
                        return store.read()
                    }
                }
            val serialized = WeeklySummaryScheduler(work, slowStore, clock, zones, lock, logs)

            val first = async { serialized.sync() }
            val second = async { serialized.sync() }
            yield()
            release.complete(Unit)
            first.await()
            second.await()

            assertFalse(overlapped, "two syncs ran at once")
            assertEquals(2, work.calls.size)
        }

    @Test
    fun `a cancelled run chains nothing`() =
        runTest {
            // A sync replaced the running work (and so enqueued its successor) just before its last step.
            val run =
                launch {
                    currentCoroutineContext().job.cancel()
                    scheduler.afterRun()
                }

            run.join()

            assertTrue(run.isCancelled)
            assertEquals(emptyList(), work.calls)
        }
}
