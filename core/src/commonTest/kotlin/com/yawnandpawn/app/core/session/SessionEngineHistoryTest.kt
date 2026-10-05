package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.history.SessionHistoryRow
import com.yawnandpawn.app.core.history.SessionOutcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.time.Deadline
import com.yawnandpawn.app.core.time.TimeSnapshot
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** AD-18 through the engine: it writes history itself, reduces `Recorded` once the row is written, and never stays silent. */
class SessionEngineHistoryTest {
    private val time = EngineTime()
    private val store = InMemorySessionStore()
    private val runner = RecordingRunner()
    private val logger = EngineLogger()
    private val history = InMemoryHistory()
    private val reducer = reducer(availability = SnoozeAvailability.Available(OFFER), check = StepResult.ValidLast)

    private fun engine(
        effects: EffectRunner = runner,
        sessionReducer: SessionReducer = reducer,
    ) = SessionEngine(sessionReducer, store, effects, SessionRecorder(history), time.clock, time.monotonicClock, time.bootCounter, logger)

    private val alarmFired = SessionEvent.AlarmFired(SESSION_ID, testConfig(), SEEDS, beforeFirstUnlock = false)

    private fun Outcome<SessionState, DomainError>.state(): SessionState = assertIs<Outcome.Success<SessionState>>(this).value

    private fun Outcome<SessionState, DomainError>.session(): SessionData = assertIs<SessionState.Active>(state()).session

    /** Dispatches [event] at 06:00:05 for the alarm scheduled at 06:00 and returns the one history row it wrote. */
    private suspend fun startRowOf(event: SessionEvent): SessionHistoryRow {
        val firedAt = SCHEDULED_AT + 5.seconds
        val clock = EngineTime(TimeSnapshot(firedAt.toEpochMilliseconds(), elapsedMillis = 5_000, bootCount = 1))
        val engine =
            SessionEngine(reducer, store, runner, SessionRecorder(history), clock.clock, clock.monotonicClock, clock.bootCounter, logger)
        assertEquals(firedAt, engine.dispatch(event).session().firstRingAt)
        return history.rows.values.single()
    }

    private val expectedStartRow =
        SessionHistoryRow(
            sessionId = SESSION_ID,
            alarmId = "alarm-1",
            scheduledAt = SCHEDULED_AT,
            firstRingAt = SCHEDULED_AT + 5.seconds,
            endedAt = null,
            snoozeCount = 0,
            checkTypes = listOf("Placeholder"),
            timeToCompleteMs = null,
            fallbackUsed = false,
            directBoot = true,
            outcome = null,
        )

    @Test
    fun `the session start row holds the alarm, the scheduled time, the first ring time and the boot state`() =
        runTest {
            assertEquals(expectedStartRow, startRowOf(alarmFired.copy(beforeFirstUnlock = true)))
        }

    @Test
    fun `a test alarm writes the same start row`() =
        runTest {
            assertEquals(
                expectedStartRow,
                startRowOf(SessionEvent.TestAlarmFired(SESSION_ID, testConfig(), SEEDS, beforeFirstUnlock = true)),
            )
        }

    @Test
    fun `the start row is written after the committed step's effects, so the first sound never waits for it`() =
        runTest {
            // Each effect with the number of history rows written when it ran.
            val seen = mutableListOf<Pair<Any, Int>>()
            val observing =
                object : EffectRunner {
                    override suspend fun run(effect: SessionEffect) {
                        seen += effect to history.rows.size
                    }

                    override suspend fun apply(effect: EntryEffect) {
                        seen += effect to history.rows.size
                    }
                }

            engine(effects = observing).dispatch(alarmFired)

            assertTrue(seen.any { it.first is EntryEffect.SoundAt }, "the sound effect ran: $seen")
            assertEquals(listOf(0), seen.map { it.second }.distinct(), "no row was written before any effect ran")
            assertEquals(listOf("Ringing"), store.commits.map { it.kind }, "the state was committed first (AD-2)")
            assertEquals(
                SESSION_ID,
                history.rows.values
                    .single()
                    .sessionId,
                "the start row is written in the same dispatch",
            )
        }

    @Test
    fun `a completed session is recorded on time and goes Idle in the same dispatch, clearing the active session`() =
        runTest {
            val firedAt = SCHEDULED_AT + 5.seconds
            val clock = EngineTime(TimeSnapshot(firedAt.toEpochMilliseconds(), elapsedMillis = 5_000, bootCount = 1))
            val engine =
                SessionEngine(
                    reducer,
                    store,
                    runner,
                    SessionRecorder(history),
                    clock.clock,
                    clock.monotonicClock,
                    clock.bootCounter,
                    logger,
                )
            engine.dispatch(alarmFired)
            engine.dispatch(SessionEvent.ImUpTapped)
            // 06:03: 2 min 55 s after the first ring.
            clock.advanceBy(2.minutes + 55.seconds)

            assertEquals(Outcome.Success(SessionState.Idle), engine.dispatch(SessionEvent.CheckAnswerSubmitted(CheckAnswer.Placeholder)))

            val row = history.rows.getValue(SESSION_ID)
            assertEquals(SessionOutcome.OnTime, row.outcome)
            assertEquals(SCHEDULED_AT + 3.minutes, row.endedAt)
            assertEquals(175_000L, row.timeToCompleteMs)
            assertEquals(listOf("Ringing", "Grace", "Completed", "Idle"), store.commits.map { it.kind })
            assertNull(store.row, "the active session is cleared")
            assertEquals(SessionState.Idle, engine.state.value)
            assertEquals(SessionEffect.ClearRuntimeSession(SESSION_ID), runner.ran.last())
            assertTrue(runner.ran.none { it is EntryEffect.HistoryWriteRequested || it is SessionEffect.RecordSessionStart })
        }

    @Test
    fun `a test session is recorded as Test`() =
        runTest {
            val engine = engine()
            engine.dispatch(SessionEvent.TestAlarmFired(SESSION_ID, testConfig(), SEEDS, beforeFirstUnlock = false))
            engine.dispatch(SessionEvent.ImUpTapped)

            assertEquals(Outcome.Success(SessionState.Idle), engine.dispatch(SessionEvent.CheckAnswerSubmitted(CheckAnswer.Placeholder)))

            assertEquals(SessionOutcome.Test, history.rows.getValue(SESSION_ID).outcome)
            assertNull(store.row)
        }

    @Test
    fun `a failed history write is logged, the session stays Completed, and the next tick writes it and goes Idle`() =
        runTest {
            val engine = engine()
            engine.dispatch(alarmFired)
            engine.dispatch(SessionEvent.ImUpTapped)
            history.upsertFailure = DomainError.StorageFailure("disk full")

            val completed =
                assertIs<SessionState.Completed>(engine.dispatch(SessionEvent.CheckAnswerSubmitted(CheckAnswer.Placeholder)).state())

            assertEquals(completed, store.stored, "the row stays until the history is written")
            assertEquals(LogEvent.OperationFailed("record session end", "storage failure: disk full"), logger.events.last())
            assertEquals(Outcome.Success<SessionState>(completed), engine.tick(), "still failing")

            history.upsertFailure = null
            assertEquals(Outcome.Success(SessionState.Idle), engine.tick())
            assertEquals(SessionOutcome.OnTime, history.rows.getValue(SESSION_ID).outcome)
            assertNull(store.row)
        }

    @Test
    fun `any event dispatched while Completed retries a failed history write`() =
        runTest {
            val engine = engine()
            engine.dispatch(alarmFired)
            engine.dispatch(SessionEvent.ImUpTapped)
            history.findFailure = DomainError.StorageFailure("locked")
            assertIs<SessionState.Completed>(engine.dispatch(SessionEvent.CheckAnswerSubmitted(CheckAnswer.Placeholder)).state())
            assertTrue(history.upserts.none { it.outcome != null }, "a failed read writes nothing")

            history.findFailure = null
            assertEquals(Outcome.Success(SessionState.Idle), engine.dispatch(SessionEvent.SlotFired))
            assertEquals(1, history.upserts.count { it.outcome != null })
        }

    @Test
    fun `a Recorded from outside never ends a session whose row is not written, it only retries the write`() =
        runTest {
            val engine = engine()
            engine.dispatch(alarmFired)
            engine.dispatch(SessionEvent.ImUpTapped)
            history.upsertFailure = DomainError.StorageFailure("disk full")
            val completed =
                assertIs<SessionState.Completed>(engine.dispatch(SessionEvent.CheckAnswerSubmitted(CheckAnswer.Placeholder)).state())

            assertEquals(Outcome.Success<SessionState>(completed), engine.dispatch(SessionEvent.Recorded(SESSION_ID)))
            assertEquals(completed, store.stored)

            history.upsertFailure = null
            assertEquals(Outcome.Success(SessionState.Idle), engine.dispatch(SessionEvent.Recorded(SESSION_ID)))
            assertEquals(SessionOutcome.OnTime, history.rows.getValue(SESSION_ID).outcome)
            assertEquals(listOf("Ringing", "Grace", "Completed", "Idle"), store.commits.map { it.kind })
        }

    @Test
    fun `when going Idle fails after the write, the row is not written again and the next tick goes Idle`() =
        runTest {
            val engine = engine()
            engine.dispatch(alarmFired)
            engine.dispatch(SessionEvent.ImUpTapped)
            store.onCommit = { if (it is SessionState.Completed) store.commitFailure = DomainError.StorageFailure("disk full") }

            assertIs<SessionState.Completed>(engine.dispatch(SessionEvent.CheckAnswerSubmitted(CheckAnswer.Placeholder)).state())
            assertIs<SessionState.Completed>(engine.tick().state())

            store.commitFailure = null
            assertEquals(Outcome.Success(SessionState.Idle), engine.tick())
            assertEquals(1, history.upserts.count { it.outcome != null }, "written once")
            assertNull(store.row)
        }

    @Test
    fun `a write that succeeded before a crash is replayed on restore as the same one row, and the session ends Idle`() =
        runTest {
            val first = engine()
            first.dispatch(alarmFired)
            first.dispatch(SessionEvent.ImUpTapped)
            // The process dies right after the history write: Recorded is never committed.
            store.onCommit = { if (it is SessionState.Completed) store.commitFailure = DomainError.StorageFailure("killed") }
            first.dispatch(SessionEvent.CheckAnswerSubmitted(CheckAnswer.Placeholder))
            val written = history.rows.getValue(SESSION_ID)
            assertIs<SessionState.Completed>(store.stored)

            store.onCommit = {}
            store.commitFailure = null
            time.advanceBy(10.minutes)
            val second = engine(effects = RecordingRunner())

            assertEquals(Outcome.Success(SessionState.Idle), second.restore())

            assertEquals(mapOf(SESSION_ID to written), history.rows.toMap(), "exactly one row with identical values")
            assertEquals(written, history.upserts.last())
            assertNull(store.row)
        }

    @Test
    fun `restoring a persisted Completed or Missed session writes its row once and goes Idle`() =
        runTest {
            listOf(SessionState.Completed(ringSession().noTimers()), SessionState.Missed(ringSession().noTimers())).forEach { ended ->
                history.rows.clear()
                history.upserts.clear()
                store.row = SessionJson.encode(ended)
                val engine = engine()

                assertEquals(Outcome.Success(SessionState.Idle), engine.restore(), ended.kind)

                val row = history.upserts.single()
                assertEquals(if (ended is SessionState.Missed) SessionOutcome.Missed else SessionOutcome.OnTime, row.outcome)
                assertEquals(SCHEDULED_AT, row.firstRingAt, "a session stored before Story 1.13 has no first ring time")
                assertNull(store.row)
            }
        }

    @Test
    fun `a failed session start write is logged and the session still rings`() =
        runTest {
            history.upsertFailure = DomainError.StorageFailure("disk full")

            assertIs<SessionState.Ringing>(engine().dispatch(alarmFired).state())

            assertEquals(LogEvent.OperationFailed("record session start", "storage failure: disk full"), logger.events.single())
        }

    /** A session completed by [engine] whose end write failed with [failure]; the failure is then still set. */
    private suspend fun completedWithFailingWrite(
        engine: SessionEngine,
        failure: DomainError = DomainError.StorageFailure("disk full"),
    ): SessionState.Completed {
        engine.dispatch(alarmFired)
        engine.dispatch(SessionEvent.ImUpTapped)
        time.advanceBy(2.minutes)
        history.upsertFailure = failure
        return assertIs<SessionState.Completed>(engine.dispatch(SessionEvent.CheckAnswerSubmitted(CheckAnswer.Placeholder)).state())
    }

    private val nextAlarm = SessionEvent.AlarmFired("session-2", testConfig(), SEEDS, beforeFirstUnlock = false)

    @Test
    fun `a new alarm behind an unwritten ended session retries the write, records it and rings`() =
        runTest {
            val engine = engine()
            completedWithFailingWrite(engine)
            history.upsertFailure = null

            val ringing = assertIs<SessionState.Ringing>(engine.dispatch(nextAlarm).state())

            assertEquals("session-2", ringing.session.sessionId)
            assertEquals(SessionOutcome.OnTime, history.rows.getValue(SESSION_ID).outcome)
            assertEquals(listOf("Ringing", "Grace", "Completed", "Idle", "Ringing"), store.commits.map { it.kind })
            assertEquals(ringing, store.stored)
        }

    @Test
    fun `a new alarm behind an end write that still fails abandons it, logs it and rings`() =
        runTest {
            val engine = engine()
            completedWithFailingWrite(engine)

            val ringing = assertIs<SessionState.Ringing>(engine.dispatch(nextAlarm).state())

            assertEquals("session-2", ringing.session.sessionId)
            assertTrue(LogEvent.OperationFailed("record session end", "abandoned for a new alarm") in logger.events)
            assertEquals(listOf("Ringing", "Grace", "Completed", "Idle", "Ringing"), store.commits.map { it.kind })
            assertEquals(ringing, store.stored)
            assertTrue(SessionEffect.ClearRuntimeSession(SESSION_ID) in runner.ran)
        }

    @Test
    fun `a write that succeeds hours after the end keeps the real end time`() =
        runTest {
            val engine = engine()
            completedWithFailingWrite(engine)
            val endedAt = Instant.fromEpochMilliseconds(time.now.wallMillis)
            time.advanceBy(3.hours)
            history.upsertFailure = null

            assertEquals(Outcome.Success(SessionState.Idle), engine.tick())

            assertEquals(endedAt, history.rows.getValue(SESSION_ID).endedAt)
            assertEquals(2.minutes.inWholeMilliseconds, history.rows.getValue(SESSION_ID).timeToCompleteMs)
        }

    @Test
    fun `a session started before the first unlock stays direct boot in history after the unlock, even when the start write failed`() =
        runTest {
            val engine = engine()
            history.upsertFailure = DomainError.StorageFailure("disk full")
            engine.dispatch(alarmFired.copy(beforeFirstUnlock = true))
            history.upsertFailure = null
            engine.dispatch(SessionEvent.UserUnlocked)
            engine.dispatch(SessionEvent.ImUpTapped)

            assertEquals(Outcome.Success(SessionState.Idle), engine.dispatch(SessionEvent.CheckAnswerSubmitted(CheckAnswer.Placeholder)))

            assertEquals(true, history.rows.getValue(SESSION_ID).directBoot)
        }

    @Test
    fun `a start effect for a session the state does not hold is dropped and logged`() =
        runTest {
            val engineHistory = EngineHistory(SessionRecorder(history), logger)

            engineHistory.recordStart(SessionEffect.RecordSessionStart("other", testConfig()), SessionState.Ringing(ringSession()))
            engineHistory.recordStart(SessionEffect.RecordSessionStart(SESSION_ID, testConfig()), SessionState.Idle)

            assertTrue(history.upserts.isEmpty())
            assertEquals(
                listOf("record session start", "record session start"),
                logger.events.map { assertIs<LogEvent.OperationFailed>(it).operation },
            )
        }
}
