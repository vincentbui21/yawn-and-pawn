package com.yawnandpawn.app.core.summary

import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.history.SessionOutcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.stats.IsoWeek
import com.yawnandpawn.app.core.stats.WeeklySummary
import com.yawnandpawn.app.core.summary.WorkCall.Schedule
import com.yawnandpawn.app.core.work.SchedulePolicy.AfterRun
import com.yawnandpawn.app.core.work.TaskResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Story 6.8: one weekly summary run posts a due week once, stores it as handled, and chains next week last. */
class WeeklySummaryTaskTest {
    private val week = IsoWeek(2027, 9)
    private val history =
        History(
            listOf(
                session("2027-03-01T06:30"),
                session("2027-03-02T06:30"),
                session("2027-03-03T06:30", SessionOutcome.Snoozed),
                session("2027-03-04T06:30", SessionOutcome.Missed),
                session("2027-03-07T08:00"),
                // The week after: not in this summary.
                session("2027-03-08T06:30"),
            ),
        )
    private val records = Records(listOf(payment("2027-03-03T06:35", Money(990_000, "EUR"))))
    private val store = MemoryWeeklyStore()
    private val notifier = MemoryNotifier()
    private val work = WeeklyWork()
    private val clock = StoppedClock(berlin("2027-03-07T19:00"))
    private val zones = SetZone()
    private val logs = Logs()
    private val scheduler = WeeklySummaryScheduler(work, store, clock, zones, Lock(), logs)
    private val task = WeeklySummaryTask(history, records, store, notifier, scheduler, clock, zones, logs)

    /** The chained run a week after [now]. */
    private fun chained(now: Instant = clock.now): WorkCall = Schedule(berlin("2027-03-14T19:00") - now, AfterRun)

    private val failure = DomainError.StorageFailure("disk")

    @Test
    fun `a run at Sunday 19 00 posts the week once, stores it, then chains next week`() =
        runTest {
            // The week is stored before next week is chained.
            work.during = { assertEquals(week, store.state.value.lastHandledWeek, "stored before the chain") }

            assertEquals(TaskResult.Done, task.run())

            assertEquals(listOf(WeeklySummary(week, onTime = 3, paid = listOf(Money(990_000, "EUR")), shouldSend = true)), notifier.posted)
            assertEquals(listOf("week=2027-W09"), store.writes)
            assertEquals(listOf(chained()), work.calls)
        }

    @Test
    fun `a second run in the same week posts nothing more`() =
        runTest {
            task.run()
            clock.now = berlin("2027-03-07T21:00")

            assertEquals(TaskResult.Done, task.run())

            assertEquals(1, notifier.posted.size)
            assertEquals(listOf("week=2027-W09"), store.writes)
            assertEquals(listOf(chained(berlin("2027-03-07T19:00")), chained()), work.calls)
        }

    @Test
    fun `a late run posts nothing, stores the week, and chains next week`() =
        runTest {
            clock.now = berlin("2027-03-09T08:00")

            assertEquals(TaskResult.Done, task.run())

            assertEquals(emptyList(), notifier.posted)
            assertEquals(week, store.state.value.lastHandledWeek)
            assertEquals(listOf(chained()), work.calls)
        }

    @Test
    fun `a week with only test sessions posts nothing, and the week is stored`() =
        runTest {
            history.rows = listOf(session("2027-03-02T06:30", SessionOutcome.Test))

            task.run()

            assertEquals(emptyList(), notifier.posted)
            assertEquals(week, store.state.value.lastHandledWeek)
        }

    @Test
    fun `without the notification permission nothing is posted, and the week is stored`() =
        runTest {
            notifier.allowed = false

            task.run()

            assertEquals(emptyList(), notifier.posted)
            assertEquals(week, store.state.value.lastHandledWeek)
            assertEquals(listOf(chained()), work.calls)
        }

    @Test
    fun `switched off, a run does nothing at all`() =
        runTest {
            store.state.value = WeeklySummaryPrefs(enabled = false)

            assertEquals(TaskResult.Done, task.run())

            assertEquals(emptyList(), notifier.posted)
            assertEquals(emptyList(), store.writes)
            assertEquals(emptyList(), work.calls)
        }

    @Test
    fun `a history read failure is logged, posts nothing, leaves the week open and still chains next week`() =
        runTest {
            history.failure = failure

            assertEquals(TaskResult.Done, task.run())

            assertEquals(emptyList(), notifier.posted)
            assertEquals(null, store.state.value.lastHandledWeek)
            assertEquals(listOf(chained()), work.calls)
            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed.of("run weekly summary", failure)), logs.events)
        }

    @Test
    fun `a purchase records read failure is handled the same way`() =
        runTest {
            records.failure = failure

            assertEquals(TaskResult.Done, task.run())

            assertEquals(emptyList(), notifier.posted)
            assertEquals(null, store.state.value.lastHandledWeek)
            assertEquals(listOf(chained()), work.calls)
        }

    @Test
    fun `a store that cannot be read posts nothing and only chains next week`() =
        runTest {
            store.failRead = failure

            assertEquals(TaskResult.Done, task.run())

            assertEquals(emptyList(), notifier.posted)
            assertEquals(emptyList(), store.writes)
            assertEquals(listOf(chained()), work.calls)
        }

    @Test
    fun `a post failure is logged, and the week stays stored`() =
        runTest {
            val refused = DomainError.BackgroundWorkFailure("post weekly summary: SecurityException")
            notifier.failure = refused

            assertEquals(TaskResult.Done, task.run())

            assertEquals(week, store.state.value.lastHandledWeek)
            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed.of("run weekly summary", refused)), logs.events)
        }

    @Test
    fun `a week store write failure is logged and the summary still posts`() =
        runTest {
            store.failWrite = failure

            task.run()

            assertEquals(1, notifier.posted.size)
            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed.of("run weekly summary", failure)), logs.events)
        }

    @Test
    fun `an already handled week only chains next week`() =
        runTest {
            store.state.value = WeeklySummaryPrefs(lastHandledWeek = week)

            task.run()

            assertEquals(emptyList(), notifier.posted)
            assertEquals(emptyList(), store.writes)
            assertEquals(listOf(chained()), work.calls)
        }

    @Test
    fun `a switch turned off while the week is summarised posts nothing`() =
        runTest {
            val turnedOff =
                object : WeeklySummaryStore by store {
                    override suspend fun setLastHandledWeek(week: IsoWeek): Outcome<Unit, DomainError> =
                        store.setLastHandledWeek(week).also { store.state.value = store.state.value.copy(enabled = false) }
                }
            val run = WeeklySummaryTask(history, records, turnedOff, notifier, scheduler, clock, zones, logs)

            assertEquals(TaskResult.Done, run.run())

            assertEquals(emptyList(), notifier.posted)
            assertEquals(week, store.state.value.lastHandledWeek)
            assertEquals(emptyList(), work.calls, "off: nothing chained")
        }

    @Test
    fun `a run cancelled while the week is stored still posts it and stores it, and chains nothing`() =
        runTest {
            lateinit var job: Job
            val cancelledThen =
                object : WeeklySummaryStore by store {
                    override suspend fun setLastHandledWeek(week: IsoWeek): Outcome<Unit, DomainError> {
                        // A sync replaces the running work at this moment.
                        job.cancel()
                        yield()
                        return store.setLastHandledWeek(week)
                    }
                }
            val run = WeeklySummaryTask(history, records, cancelledThen, notifier, scheduler, clock, zones, logs)

            job = launch { run.run() }
            job.join()

            assertTrue(job.isCancelled)
            assertEquals(1, notifier.posted.size)
            assertEquals(week, store.state.value.lastHandledWeek)
            assertEquals(emptyList(), work.calls, "the run that replaced it chains next week")
        }
}
