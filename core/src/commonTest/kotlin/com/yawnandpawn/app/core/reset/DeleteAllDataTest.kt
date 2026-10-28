package com.yawnandpawn.app.core.reset

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmFired
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.alarm.AlarmScheduler
import com.yawnandpawn.app.core.alarm.AlarmWriteLock
import com.yawnandpawn.app.core.alarm.Motivation
import com.yawnandpawn.app.core.alarm.RequestCodes
import com.yawnandpawn.app.core.billing.LedgerStatus
import com.yawnandpawn.app.core.billing.LedgerWorld
import com.yawnandpawn.app.core.billing.PriceRefreshJobs
import com.yawnandpawn.app.core.billing.PurchaseLedger
import com.yawnandpawn.app.core.billing.PurchaseRecord
import com.yawnandpawn.app.core.billing.RecordStatus
import com.yawnandpawn.app.core.billing.SESSION
import com.yawnandpawn.app.core.billing.TOKEN_1
import com.yawnandpawn.app.core.billing.TOKEN_2
import com.yawnandpawn.app.core.billing.grant
import com.yawnandpawn.app.core.billing.hash
import com.yawnandpawn.app.core.billing.record
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.session.ActiveSessionStore
import com.yawnandpawn.app.core.session.InMemorySessionStore
import com.yawnandpawn.app.core.session.PurchaseToken
import com.yawnandpawn.app.core.session.SessionJson
import com.yawnandpawn.app.core.session.SessionLockGuard
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.ringSession
import com.yawnandpawn.app.core.session.snoozedSession
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.Deadline
import com.yawnandpawn.app.core.work.BackgroundJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalTime
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Story 5.9 (FR-SET-4) and its review: "Delete all data", every row of the I/O matrix, with a fake that fails, then
 * throws, after each step; a half-done delete holding the app; and the resume on start with its limits.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeleteAllDataTest {
    private val world = DeletionWorld()

    @Test
    fun `happy path - everything is deleted, every code cancelled, the install id reset, the marker gone, one event logged`() =
        runTest {
            world.fill()
            val deleteAllData = world.deleteAllData()
            val completed = world.completions(this)

            assertEquals(Outcome.Success(Unit), deleteAllData())

            world.assertEverythingDeleted(installIdReset = true)
            // Steps 1 to 9 in their order: the stored codes and the test alarm inside the guard, the purchase records twice
            // (again after a recovery may have run), then the unused codes up to the mark after the guard.
            assertEquals(
                listOf(Step.WriteMarker, Step.ListAlarms, Step.ReadMark) +
                    List(GUARDED_CANCELS.size) { Step.CancelCode } +
                    listOf(
                        Step.CancelSlot,
                        Step.AlarmData,
                        Step.History,
                        Step.Records,
                        Step.Intents,
                        Step.Session,
                        Step.Settings,
                        Step.InstallId,
                        Step.Records,
                        Step.RemoveMarker,
                    ) +
                    List(UNUSED_CANCELS.size) { Step.CancelCode },
                world.calls,
            )
            assertEquals(GUARDED_CANCELS + UNUSED_CANCELS, world.cancelled)
            assertEquals(listOf<LogEvent>(LogEvent.AllDataDeleted(alarms = 2, keptRecords = 0, at = NOW)), world.logged)
            // After the guarded part: unsent crash reports dropped, usage stats reset, the immediate price refresh enqueued.
            assertEquals(1, world.crashDeletes)
            assertEquals(1, world.analyticsResets)
            assertEquals(listOf(PriceRefreshJobs.NOW), world.ledgerWorld.work.jobs)
            assertFalse(deleteAllData.pending.value)
            assertEquals(1, completed.size, "the app is told to go back to Home")
            assertTrue(world.freshWrites.single(), "the user's delete starts a new start count")
        }

    @Test
    fun `a session in progress, not restored, the emergency ring, or Completed or Missed refuses and changes nothing`() =
        runTest {
            world.fill()
            world.marker = true
            val deleteAllData = world.deleteAllData()
            val refusals =
                listOf<Pair<String, () -> Unit>>(
                    "Ringing" to { world.state.value = SessionState.Ringing(ringSession()) },
                    "Grace" to { world.state.value = SessionState.Grace(ringSession()) },
                    "Loud" to { world.state.value = SessionState.Loud(ringSession()) },
                    "Snoozed" to { world.state.value = SessionState.Snoozed(snoozedSession()) },
                    "Completed waiting for history" to { world.state.value = SessionState.Completed(ringSession()) },
                    "Missed waiting for history" to { world.state.value = SessionState.Missed(ringSession()) },
                    "not restored" to { world.restored.value = false },
                    "emergency ring" to { world.emergency.value = true },
                )

            refusals.forEach { (case, arrange) ->
                arrange()
                assertEquals(Outcome.Failure(DomainError.SessionActive), deleteAllData(), case)
                assertEquals(Outcome.Failure(DomainError.SessionActive), deleteAllData.resume(), case)
                assertEquals(emptyList<Step>(), world.calls, case)
                world.state.value = SessionState.Idle
                world.restored.value = true
                world.emergency.value = false
            }

            assertEquals(2, world.alarms.stored.size)
            assertEquals(4, world.ledgerWorld.recordRows.size)
            assertEquals(emptyList<LogEvent>(), world.logged, "a refusal is not logged as a failure")
            assertEquals(0, world.crashDeletes)
            assertEquals(0, world.analyticsResets)
            assertEquals(emptyList<BackgroundJob>(), world.ledgerWorld.work.jobs)
            assertTrue(deleteAllData.pending.value, "a refusal leaves a pending deletion pending")
        }

    @Test
    fun `a refusal writes no marker`() =
        runTest {
            world.state.value = SessionState.Snoozed(snoozedSession())

            assertEquals(Outcome.Failure(DomainError.SessionActive), world.deleteAllData()())

            assertFalse(world.marker)
            assertFalse(world.pendingDeletion.pending.value)
        }

    @Test
    fun `owed money - the ledger stays whole, owed records stay, the rest go, and the install id is not reset`() =
        runTest {
            world.fill()
            val pendingRow = grant(token = TOKEN_1)
            val settledMarker = grant(token = TOKEN_2, status = LedgerStatus.Consumed).copy(settledAt = NOW)
            world.ledgerWorld.ledgerRows[TOKEN_1] = pendingRow
            world.ledgerWorld.ledgerRows[TOKEN_2] = settledMarker
            val owedWithoutRow = record(RecordStatus.Granted, token = OWED)
            val reusedNotConsumed = record(RecordStatus.Reused, token = REUSED, consumedAt = null)
            world.ledgerWorld.recordRows[owedWithoutRow.tokenHash] = owedWithoutRow
            world.ledgerWorld.recordRows[reusedNotConsumed.tokenHash] = reusedNotConsumed

            assertEquals(Outcome.Success(Unit), world.deleteAllData()())

            assertEquals(mapOf(TOKEN_1 to pendingRow, TOKEN_2 to settledMarker), world.ledgerWorld.ledgerRows.toMap())
            assertEquals(setOf(OWED.hash(), REUSED.hash()), world.ledgerWorld.recordRows.keys)
            world.assertEverythingDeleted(installIdReset = false, keptRecords = 2)
            assertEquals(listOf<LogEvent>(LogEvent.AllDataDeleted(alarms = 2, keptRecords = 2, at = NOW)), world.logged)
        }

    @Test
    fun `a pending ledger row keeps its record whatever its status, and its session's intents (review item 4)`() =
        runTest {
            world.fill()
            world.ledgerWorld.ledgerRows[TOKEN_1] = grant(token = TOKEN_1)
            // A stranded record whose reuse the pending row still settles: stranded records go, this one stays.
            val strandedReuse = record(RecordStatus.Stranded, token = TOKEN_1, sessionId = SESSION)
            world.ledgerWorld.recordRows[strandedReuse.tokenHash] = strandedReuse

            assertEquals(Outcome.Success(Unit), world.deleteAllData()())

            assertEquals(setOf(TOKEN_1.hash()), world.ledgerWorld.recordRows.keys, "the other settled records go")
            assertEquals(setOf(SESSION), world.keptIntentSessions, "the pending row's session keeps its intents")
            world.assertEverythingDeleted(installIdReset = false, keptRecords = 1)
        }

    @Test
    fun `a stranded record a recovery writes during the deletion is deleted too (review item 5)`() =
        runTest {
            world.fill()
            // A purchase recovery records a stranded payment while the intents are deleted (after step 4 ran once).
            world.onIntents = {
                val late = record(RecordStatus.Stranded, token = PurchaseToken("late-recovery"))
                world.ledgerWorld.recordRows[late.tokenHash] = late
            }

            assertEquals(Outcome.Success(Unit), world.deleteAllData()())

            assertEquals(emptyMap<String, PurchaseRecord>(), world.ledgerWorld.recordRows.toMap())
            world.assertEverythingDeleted(installIdReset = true)
        }

    @Test
    fun `a pending ledger row alone, or an owed record alone, keeps the install id`() =
        runTest {
            world.ledgerWorld.ledgerRows[TOKEN_1] = grant(token = TOKEN_1)
            assertEquals(Outcome.Success(Unit), world.deleteAllData()())
            assertEquals(0, world.installIdResets)

            world.ledgerWorld.ledgerRows.clear()
            val owed = record(RecordStatus.Granted, token = OWED)
            world.ledgerWorld.recordRows[owed.tokenHash] = owed
            assertEquals(Outcome.Success(Unit), world.deleteAllData()())
            assertEquals(0, world.installIdResets)

            // Once consumed it is an ordinary record: the next delete removes it, and the install id is reset.
            world.ledgerWorld.recordRows[owed.tokenHash] = owed.copy(status = RecordStatus.Consumed, consumedAt = NOW)
            assertEquals(Outcome.Success(Unit), world.deleteAllData()())
            assertEquals(emptyMap<String, PurchaseRecord>(), world.ledgerWorld.recordRows.toMap())
            assertEquals(1, world.installIdResets)
        }

    @Test
    fun `a step that fails leaves the marker and holds the app, and the next run completes every step`() =
        runTest {
            Step.entries.forEach { step -> crashAndResume(step, throwing = false) }
        }

    @Test
    fun `a step that throws leaves the marker and holds the app, and the next run completes every step`() =
        runTest {
            Step.entries.forEach { step -> crashAndResume(step, throwing = true) }
        }

    private suspend fun TestScope.crashAndResume(
        step: Step,
        throwing: Boolean,
    ) {
        val world = DeletionWorld()
        world.fill()
        world.failAt = step.takeUnless { throwing }
        world.throwAt = step.takeIf { throwing }
        val deleteAllData = world.deleteAllData()
        val completed = world.completions(this)

        val failed = assertIs<Outcome.Failure<DomainError>>(deleteAllData(), "$step")

        val expected = if (throwing) DomainError.StorageFailure("IllegalStateException") else DomainError.StorageFailure("$step")
        assertEquals(expected, failed.error, "$step")
        val markerWritten = step != Step.WriteMarker
        assertEquals(markerWritten, world.marker, "$step: the marker stays once written")
        assertEquals(markerWritten, deleteAllData.pending.value, "$step: a half-done delete holds the app (review item 1)")
        assertEquals(
            markerWritten,
            world.guard.whenIdle { Outcome.Success(Unit) } == Outcome.Failure(DomainError.SessionActive),
            "$step: no other guarded write (a SaveAlarm) runs on top of it",
        )
        // The ledger logs its own failed delete too; a throw passes through it.
        val ledgerLog =
            listOf<LogEvent>(LogEvent.OperationFailed.of(PurchaseLedger.DELETE_RECORDS, expected)).filter {
                step == Step.Records &&
                    !throwing
            }
        assertEquals(ledgerLog + LogEvent.OperationFailed.of(DeleteAllData.OPERATION, expected), world.logged, "$step")
        assertEquals(0, world.crashDeletes, "$step")
        assertEquals(0, world.analyticsResets, "$step: nothing is reset before the deletion completes")
        assertEquals(emptyList<BackgroundJob>(), world.ledgerWorld.work.jobs, "$step")
        assertEquals(0, completed.size, "$step")

        world.failAt = null
        world.throwAt = null
        world.logged.clear()
        assertEquals(Outcome.Success(Unit), deleteAllData.resume(), "$step")
        world.assertEverythingDeleted(installIdReset = true)
        assertEquals(1, world.logged.count { it is LogEvent.AllDataDeleted }, "$step")
        assertEquals(1, world.crashDeletes, "$step")
        assertEquals(1, world.analyticsResets, "$step")
        assertFalse(deleteAllData.pending.value, "$step")
        assertEquals(Outcome.Success(Unit), world.guard.whenIdle { Outcome.Success(Unit) }, "$step: writes again")
        assertEquals(1, completed.size, "$step")
    }

    @Test
    fun `the purchase records step fails when the grant ledger cannot be read, before anything is deleted there`() =
        runTest {
            world.fill()
            world.ledgerWorld.ledgerFailure = DomainError.StorageFailure("ledger closed")

            assertEquals(Outcome.Failure(DomainError.StorageFailure("ledger closed")), world.deleteAllData()())
            assertEquals(
                listOf<LogEvent>(
                    LogEvent.OperationFailed.of(PurchaseLedger.LOAD_LEDGER, DomainError.StorageFailure("ledger closed")),
                    LogEvent.OperationFailed.of(DeleteAllData.OPERATION, DomainError.StorageFailure("ledger closed")),
                ),
                world.logged,
            )
            assertEquals(4, world.ledgerWorld.recordRows.size)
            assertTrue(world.marker)
        }

    @Test
    fun `a price refresh that cannot be enqueued, or unused codes that cannot be cancelled, are logged and it still succeeded`() =
        runTest {
            world.fill()
            world.ledgerWorld.work.failure = DomainError.BackgroundWorkFailure("user locked")
            world.failCancelOf = UNUSED_CANCELS.first()

            assertEquals(Outcome.Success(Unit), world.deleteAllData()())

            assertFalse(world.marker)
            assertEquals(
                listOf(
                    LogEvent.OperationFailed(DeleteAllData.CANCEL_UNUSED_CODES, "1 of ${UNUSED_CANCELS.size} codes"),
                    LogEvent.OperationFailed.of(DeleteAllData.ENQUEUE_PRICE_REFRESH, DomainError.BackgroundWorkFailure("user locked")),
                ),
                world.logged.takeLast(2),
            )
        }

    @Test
    fun `the guarded part runs to its end when the caller is cancelled meanwhile`() =
        runTest(UnconfinedTestDispatcher()) {
            world.fill()
            val hold = CompletableDeferred<Unit>()
            world.markerHold = hold
            val deleteAllData = world.deleteAllData()
            val caller = launch { deleteAllData() }

            caller.cancel()
            hold.complete(Unit)
            advanceUntilIdle()

            world.assertEverythingDeleted(installIdReset = true)
        }

    @Test
    fun `resume on start - a marker found at start counts the start, holds the app and runs once the guard unlocks`() =
        runTest {
            world.fill()
            world.marker = true
            world.restored.value = false
            val deleteAllData = world.deleteAllData()
            val resume = world.resume(deleteAllData, backgroundScope)
            assertTrue(deleteAllData.pending.value)

            assertNotNull(resume.onAppStart())
            runCurrent()
            assertEquals(1, world.starts)
            assertEquals(1, world.restores, "the session is restored when nothing is stored")
            assertEquals(emptyList<Step>(), world.calls, "nothing runs before the stored session is restored")
            assertNull(resume.ensureRunning(), "one resume at a time")

            world.restored.value = true
            runCurrent()

            world.assertEverythingDeleted(installIdReset = true)
            assertFalse(deleteAllData.pending.value)
            assertFalse(world.freshWrites.single(), "a resume keeps the start count")
            assertNull(resume.ensureRunning(), "nothing pending any more")
        }

    @Test
    fun `resume waits for a ring, and for a session still waiting for its history row, and never runs during it`() =
        runTest {
            world.fill()
            world.marker = true
            world.state.value = SessionState.Ringing(ringSession())
            val deleteAllData = world.deleteAllData()

            world.resume(deleteAllData, backgroundScope).onAppStart()
            // A ring has no time limit: the resume waits as long as it lasts.
            advanceTimeBy(ResumePendingDeletion.IDLE_WAIT * 5)
            world.state.value = SessionState.Completed(ringSession())
            advanceTimeBy(ResumePendingDeletion.IDLE_WAIT / 2)
            assertEquals(emptyList<Step>(), world.calls)
            assertTrue(deleteAllData.pending.value)

            world.state.value = SessionState.Idle
            runCurrent()

            world.assertEverythingDeleted(installIdReset = true)
        }

    @Test
    fun `a session left unfinished longer than the idle wait makes the resume give up to Home, the marker kept (review item 3)`() =
        runTest {
            world.fill()
            world.marker = true
            world.state.value = SessionState.Missed(ringSession())
            val deleteAllData = world.deleteAllData()

            world.resume(deleteAllData, backgroundScope).onAppStart()
            advanceTimeBy(ResumePendingDeletion.IDLE_WAIT + 1.seconds)

            assertFalse(deleteAllData.pending.value, "the app goes on to Home")
            assertTrue(world.marker)
            assertEquals(emptyList<Step>(), world.calls)
            assertEquals(
                LogEvent.OperationFailed(
                    ResumePendingDeletion.OPERATION,
                    "gave up: the session stayed unfinished for ${ResumePendingDeletion.IDLE_WAIT}",
                ),
                world.logged.single(),
            )
        }

    @Test
    fun `resume waits again when a session started just before the deletion took the lock`() =
        runTest {
            world.fill()
            world.marker = true
            val deleteAllData = world.deleteAllData()
            val resume = world.resume(deleteAllData, backgroundScope)
            // The ring starts while the deletion waits for the alarm write lock.
            val lock = world.lock
            val holder = CompletableDeferred<Unit>()
            backgroundScope.launch { lock.withLock { holder.await() } }
            runCurrent()

            resume.onAppStart()
            runCurrent()
            world.state.value = SessionState.Ringing(ringSession())
            holder.complete(Unit)
            runCurrent()
            assertEquals(emptyList<Step>(), world.calls)
            assertTrue(deleteAllData.pending.value)

            world.state.value = SessionState.Idle
            runCurrent()

            world.assertEverythingDeleted(installIdReset = true)
            assertFalse(deleteAllData.pending.value)
        }

    @Test
    fun `a resume that keeps failing tries three times, then gives up to Home with the marker kept for the next start`() =
        runTest {
            world.fill()
            world.marker = true
            world.failAt = Step.Settings
            val deleteAllData = world.deleteAllData()

            world.resume(deleteAllData, backgroundScope).onAppStart()
            // Background work is not run by advanceUntilIdle: move the clock past the retries instead.
            advanceTimeBy(ResumePendingDeletion.RETRY_DELAY * ResumePendingDeletion.MAX_TRIES)
            runCurrent()

            assertEquals(ResumePendingDeletion.MAX_TRIES, world.calls.count { it == Step.Settings })
            assertFalse(deleteAllData.pending.value, "the app goes on to Home")
            assertTrue(world.marker)
            assertEquals(
                LogEvent.OperationFailed(ResumePendingDeletion.OPERATION, "gave up: ${ResumePendingDeletion.MAX_TRIES} runs failed"),
                world.logged.last(),
            )
            // The next start finds the marker and runs again.
            world.failAt = null
            val nextStart = world.deleteAllData()
            assertTrue(nextStart.pending.value)
            world.resume(nextStart, backgroundScope).onAppStart()
            runCurrent()
            world.assertEverythingDeleted(installIdReset = true)
            assertEquals(2, world.starts)
        }

    @Test
    fun `after three starts that found the marker the fourth gives up at once and the app runs as usual (review item 3)`() =
        runTest {
            world.fill()
            world.marker = true
            world.starts = ResumePendingDeletion.MAX_STARTS
            val deleteAllData = world.deleteAllData()

            assertNull(world.resume(deleteAllData, backgroundScope).onAppStart())
            runCurrent()

            assertFalse(deleteAllData.pending.value)
            assertEquals(Outcome.Success(Unit), world.guard.whenIdle { Outcome.Success(Unit) }, "writes again")
            assertEquals(emptyList<Step>(), world.calls)
            assertTrue(world.marker, "kept: the user's next Delete starts again")
            assertEquals(
                LogEvent.OperationFailed(ResumePendingDeletion.OPERATION, "gave up: 4 starts found the marker"),
                world.logged.single(),
            )
        }

    @Test
    fun `a failed delete from the You tab is resumed by the restoring screen, and the app is then told it completed`() =
        runTest {
            world.fill()
            world.failAt = Step.AlarmData
            val deleteAllData = world.deleteAllData()
            val completed = world.completions(this)
            val resume = world.resume(deleteAllData, backgroundScope)

            assertIs<Outcome.Failure<DomainError>>(deleteAllData())
            assertTrue(deleteAllData.pending.value)
            world.failAt = null

            assertNotNull(resume.ensureRunning())
            runCurrent()

            world.assertEverythingDeleted(installIdReset = true)
            assertFalse(deleteAllData.pending.value)
            assertEquals(1, completed.size)
        }

    @Test
    fun `a marker gone meanwhile ends the resume and releases the app`() =
        runTest {
            world.marker = true
            val deleteAllData = world.deleteAllData()
            val resume = world.resume(deleteAllData, backgroundScope)
            world.restored.value = false
            resume.onAppStart()
            runCurrent()

            world.marker = false
            world.restored.value = true
            runCurrent()

            // The resume checks the marker before each run: none left, so nothing runs.
            assertFalse(deleteAllData.pending.value)
            assertEquals(emptyList<Step>(), world.calls)
        }

    @Test
    fun `nothing pending - the app start resumes nothing and releases the app`() =
        runTest {
            val deleteAllData = world.deleteAllData()

            assertFalse(deleteAllData.pending.value)
            assertNull(world.resume(deleteAllData, backgroundScope).onAppStart())
            assertEquals(0, world.starts)
            assertEquals(0, world.restores)
        }

    private companion object {
        val NOW: Instant = Instant.parse("2027-03-10T07:00:00Z")
        val OWED = PurchaseToken("owed-token")
        val REUSED = PurchaseToken("reused-token")

        /** Inside the guard: the stored codes (1000, 1003), then the test alarm. */
        val GUARDED_CANCELS = listOf(1000, 1003, RequestCodes.TEST_ALARM)

        /** After the guard: the codes up to the mark 1004 no stored alarm used. */
        val UNUSED_CANCELS = listOf(1001, 1002, 1004)
    }

    /** Every port call that can fail, in the order the steps make them. */
    internal enum class Step {
        WriteMarker,
        ListAlarms,
        ReadMark,
        CancelCode,
        CancelSlot,
        AlarmData,
        History,
        Records,
        Intents,
        Session,
        Settings,
        InstallId,
        RemoveMarker,
    }

    /** What "Delete all data" works on, in memory: each port records its step and fails or throws there when told. */
    private class DeletionWorld {
        val state = MutableStateFlow<SessionState>(SessionState.Idle)
        val restored = MutableStateFlow(true)
        val emergency = MutableStateFlow(false)
        val lock = AlarmWriteLock()
        val ledgerWorld = LedgerWorld().apply { now = NOW }
        val sessionStore = InMemorySessionStore()

        var failAt: Step? = null
        var throwAt: Step? = null
        var failCancelOf: Int? = null
        val calls = mutableListOf<Step>()
        val cancelled = mutableListOf<Int>()
        val logged: MutableList<LogEvent> get() = ledgerWorld.logged

        var marker = false
        var starts = 0
        val freshWrites = mutableListOf<Boolean>()
        var markerHold: CompletableDeferred<Unit>? = null
        val alarms = Alarms()
        var mark = RequestCodes.INITIAL_HIGH_WATER_MARK
        val armed = mutableSetOf<Int>()
        var historyRows = 0
        var intents = 0
        var keptIntentSessions: Set<String>? = null
        var onIntents: () -> Unit = {}
        var settings = 0
        var installIdResets = 0
        var crashDeletes = 0
        var analyticsResets = 0
        var restores = 0

        /** The process's pending deletion and the guard that follows it; each [deleteAllData] is a new process start. */
        var pendingDeletion = PendingDeletion(initiallyPending = false)
            private set
        var guard = SessionLockGuard(state, restored, emergency, deletionPending = pendingDeletion.pending)
            private set

        /** Two alarms (1000, 1003, mark 1004) armed, the test alarm and the slot armed, history, records, intents, settings. */
        fun fill() {
            alarms.stored = listOf(alarm("a1", 1000), alarm("a2", 1003))
            mark = 1004
            armed += listOf(1000, 1001, 1003, RequestCodes.TEST_ALARM, RequestCodes.SESSION_SLOT)
            historyRows = 3
            intents = 2
            settings = 5
            listOf(
                record(RecordStatus.Consumed, token = PurchaseToken("consumed")),
                record(RecordStatus.Stranded, token = PurchaseToken("stranded")),
                record(RecordStatus.Reused, token = PurchaseToken("reused-consumed"), consumedAt = NOW),
                record(RecordStatus.Consumed, token = PurchaseToken("consumed-2")),
            ).forEach { ledgerWorld.recordRows[it.tokenHash] = it }
            sessionStore.row = SessionJson.encode(SessionState.Idle)
        }

        fun assertEverythingDeleted(
            installIdReset: Boolean,
            keptRecords: Int = 0,
        ) {
            assertFalse(marker, "the marker is removed")
            assertEquals(emptyList<Alarm>(), alarms.stored)
            assertEquals(emptySet<Int>(), armed, "no alarm left armed")
            assertEquals(0, historyRows)
            assertEquals(keptRecords, ledgerWorld.recordRows.size)
            assertEquals(0, intents)
            assertNull(sessionStore.row)
            assertEquals(0, settings)
            assertEquals(installIdReset, installIdResets > 0)
            assertEquals(1004, mark, "the high-water mark is never reset")
        }

        /** Every completed deletion the app is told about. */
        fun completions(scope: TestScope): List<Unit> {
            val completed = mutableListOf<Unit>()
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
                pendingDeletion.completed.collect { completed += it }
            }
            return completed
        }

        private fun hit(step: Step): Outcome<Unit, DomainError> {
            calls += step
            if (throwAt == step) throw IllegalStateException("$step")
            return if (failAt == step) Outcome.Failure(DomainError.StorageFailure("$step")) else Outcome.Success(Unit)
        }

        private fun Outcome<Unit, DomainError>.then(action: () -> Unit): Outcome<Unit, DomainError> =
            also { if (it is Outcome.Success) action() }

        val deletionMarker =
            object : DeletionMarker {
                override fun exists(): Boolean = marker

                override suspend fun write(resetAttempts: Boolean): Outcome<Unit, DomainError> {
                    markerHold?.await()
                    return hit(Step.WriteMarker).then {
                        freshWrites += resetAttempts
                        if (resetAttempts || !marker) starts = 0
                        marker = true
                    }
                }

                override suspend fun remove(): Outcome<Unit, DomainError> = hit(Step.RemoveMarker).then { marker = false }

                override fun countStart(): Int = if (marker) ++starts else 0
            }

        private val scheduler =
            object : AlarmScheduler {
                override fun schedule(
                    alarmId: String,
                    requestCode: Int,
                    triggerAtWallMillis: Long,
                ): Outcome<Unit, DomainError> = error("never arms")

                override fun cancel(requestCode: Int): Outcome<Unit, DomainError> {
                    if (failCancelOf == requestCode) return Outcome.Failure(DomainError.SchedulerFailure("cancel"))
                    return hit(Step.CancelCode).then {
                        cancelled += requestCode
                        armed -= requestCode
                    }
                }

                override fun armSessionSlot(
                    deadline: Deadline,
                    alarm: AlarmFired?,
                    retrySince: Instant?,
                ): Outcome<Unit, DomainError> = error("never arms")

                override fun cancelSessionSlot(): Outcome<Unit, DomainError> =
                    hit(Step.CancelSlot).then {
                        armed -=
                            RequestCodes.SESSION_SLOT
                    }

                override fun scheduleTest(triggerAtWallMillis: Long): Outcome<Unit, DomainError> = error("never arms")
            }

        private val eraser =
            object : LocalDataEraser {
                override suspend fun deleteAlarmData(): Outcome<Unit, DomainError> =
                    hit(Step.AlarmData).then { alarms.stored = emptyList() }

                override suspend fun deletePurchaseIntents(keepSessions: Set<String>): Outcome<Unit, DomainError> =
                    hit(Step.Intents).then {
                        intents = 0
                        keptIntentSessions = keepSessions
                        onIntents()
                    }

                override suspend fun clearSettings(): Outcome<Unit, DomainError> = hit(Step.Settings).then { settings = 0 }
            }

        inner class Alarms : AlarmRepository {
            var stored: List<Alarm> = emptyList()

            override fun observeAll(): Flow<List<Alarm>> = emptyFlow()

            override suspend fun listAll(): Outcome<List<Alarm>, DomainError> =
                when (val hit = hit(Step.ListAlarms)) {
                    is Outcome.Failure -> hit
                    is Outcome.Success -> Outcome.Success(stored)
                }

            override suspend fun get(id: String): Outcome<Alarm, DomainError> = error("not used")

            override suspend fun upsert(alarm: Alarm): Outcome<Unit, DomainError> = error("not used")

            override suspend fun delete(id: String): Outcome<Unit, DomainError> = error("not used")

            override suspend fun clearMotivation(motivation: Motivation): Outcome<Unit, DomainError> = error("not used")
        }

        /** The session store, whose clear is the Session step. */
        private val activeSession =
            object : ActiveSessionStore by sessionStore {
                override suspend fun clear(): Outcome<Unit, DomainError> =
                    when (val hit = hit(Step.Session)) {
                        is Outcome.Failure -> hit
                        is Outcome.Success -> sessionStore.clear()
                    }
            }

        init {
            ledgerWorld.beforeRecordDelete = { hit(Step.Records).let { (it as? Outcome.Failure)?.error } }
        }

        /** A deletion as a new process start makes it: its pending state read from the marker, the guard following it. */
        fun deleteAllData(): DeleteAllData {
            pendingDeletion = PendingDeletion(marker)
            guard = SessionLockGuard(state, restored, emergency, deletionPending = pendingDeletion.pending)
            return DeleteAllData(
                lock = lock,
                sessionLock = guard,
                marker = deletionMarker,
                alarms = alarms,
                highWaterMark = {
                    when (val hit = hit(Step.ReadMark)) {
                        is Outcome.Failure -> hit
                        is Outcome.Success -> Outcome.Success(mark)
                    }
                },
                scheduler = scheduler,
                eraser = eraser,
                history = { hit(Step.History).then { historyRows = 0 } },
                ledger = ledgerWorld.ledger(),
                activeSession = activeSession,
                installId = { hit(Step.InstallId).then { installIdResets++ } },
                crashReports = { crashDeletes++ },
                analytics = { analyticsResets++ },
                work = ledgerWorld.work,
                clock =
                    object : Clock {
                        override fun now(): Instant = NOW
                    },
                logger = ledgerWorld.logger,
                pendingDeletion = pendingDeletion,
                context = EmptyCoroutineContext,
            )
        }

        fun resume(
            deleteAllData: DeleteAllData,
            scope: CoroutineScope,
        ): ResumePendingDeletion =
            ResumePendingDeletion(
                deleteAllData = deleteAllData,
                pendingDeletion = pendingDeletion,
                marker = deletionMarker,
                sessionLock = guard,
                scope = scope,
                logger = ledgerWorld.logger,
                restoreSession = { restores++ },
            )

        private fun alarm(
            id: String,
            requestCode: Int,
        ): Alarm =
            Alarm(
                id = id,
                time = LocalTime(7, 0),
                requestCode = requestCode,
                createdAt = NOW,
                updatedAt = NOW,
            )
    }
}
