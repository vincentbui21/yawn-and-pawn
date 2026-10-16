package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.checks.CheckAnswer
import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.CheckMode
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.Difficulty
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.history.SessionOutcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.telemetry.Telemetry
import com.yawnandpawn.app.core.telemetry.TelemetryCheckType
import com.yawnandpawn.app.core.telemetry.TelemetryEvent
import com.yawnandpawn.app.core.telemetry.TelemetryReporter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Story 5.6: the engine reports a session's usage stats once its `Recorded` has committed, and never for a test. The
 * telemetry runs on the test's background scope, as the app launches it on ApplicationScope (review fix 1).
 */
class SessionEngineTelemetryTest {
    private val time = EngineTime()
    private val store = InMemorySessionStore()
    private val runner = RecordingRunner()
    private val logger = EngineLogger()
    private val history = InMemoryHistory()
    private val sent = mutableListOf<TelemetryEvent>()
    private val recording = Telemetry { sent += it }

    /** An engine whose usage stats are launched on the background scope through [TelemetryReporter.Launching]. */
    private fun TestScope.engine(telemetry: Telemetry = recording) =
        engineWith(TelemetryReporter.Launching(telemetry, backgroundScope, logger))

    private fun engineWith(reporter: TelemetryReporter) =
        SessionEngine(
            reducer(),
            store,
            runner,
            SessionRecorder(history),
            time.clock,
            time.monotonicClock,
            time.bootCounter,
            logger,
            telemetry = reporter,
        )

    private fun entry(type: CheckType) = CheckEntry(type, Difficulty.Easy, count = 1)

    /** Two Math entries, Word Unscramble, QR/Barcode and the Placeholder, after 2 paid snoozes. */
    private val plan =
        CheckPlan(
            CheckMode.All,
            listOf(
                entry(CheckType.Math),
                entry(CheckType.WordUnscramble),
                entry(CheckType.Math),
                entry(CheckType.QrBarcode),
                entry(CheckType.Placeholder),
            ),
        )

    private fun ended(
        missed: Boolean = false,
        testMode: Boolean = false,
    ): SessionState {
        val config = testConfig(testMode = testMode, checkPlan = plan)
        val session = ringSession(config).copy(snoozesGranted = 2).noTimers()
        return if (missed) SessionState.Missed(session) else SessionState.Completed(session)
    }

    @Test
    fun `a completed session sends its outcome, its snooze count and each distinct check type once, after Recorded`() =
        runTest {
            store.row = SessionJson.encode(ended())

            assertEquals(Outcome.Success(SessionState.Idle), engine().restore())
            runCurrent()

            assertEquals(
                listOf(
                    TelemetryEvent.SessionOutcome(SessionOutcome.Snoozed),
                    TelemetryEvent.SnoozeCount(2),
                    TelemetryEvent.CheckTypeUsed(TelemetryCheckType.Math),
                    TelemetryEvent.CheckTypeUsed(TelemetryCheckType.WordUnscramble),
                    TelemetryEvent.CheckTypeUsed(TelemetryCheckType.QrBarcode),
                ),
                sent,
            )
            assertNull(store.row, "Recorded committed first")
        }

    @Test
    fun `a missed session sends Missed`() =
        runTest {
            store.row = SessionJson.encode(ended(missed = true))

            engine().restore()
            runCurrent()

            assertEquals(TelemetryEvent.SessionOutcome(SessionOutcome.Missed), sent.first())
            assertEquals(1, sent.count { it is TelemetryEvent.SessionOutcome })
        }

    @Test
    fun `a test session sends nothing`() =
        runTest {
            store.row = SessionJson.encode(ended(testMode = true))

            assertEquals(Outcome.Success(SessionState.Idle), engine().restore())
            runCurrent()

            assertEquals(emptyList(), sent)
        }

    @Test
    fun `a session completed live reports its stats once the dispatch that ends it commits, and nothing while it rings`() =
        runTest {
            val engine = engine()
            engine.dispatch(SessionEvent.AlarmFired(SESSION_ID, testConfig(), beforeFirstUnlock = false))
            engine.dispatch(SessionEvent.ImUpTapped)
            runCurrent()
            assertEquals(emptyList(), sent)

            assertEquals(Outcome.Success(SessionState.Idle), engine.dispatch(SessionEvent.CheckAnswerSubmitted(CheckAnswer.Placeholder)))
            runCurrent()

            // The Placeholder plan names no check type usage stats log.
            assertEquals(listOf(TelemetryEvent.SessionOutcome(SessionOutcome.OnTime), TelemetryEvent.SnoozeCount(0)), sent)
        }

    @Test
    fun `nothing is sent while the history write fails, and the stats go once when Recorded finally commits`() =
        runTest {
            store.row = SessionJson.encode(ended())
            history.upsertFailure = DomainError.StorageFailure("disk full")
            val engine = engine()

            assertIs<SessionState.Completed>(assertIs<Outcome.Success<SessionState>>(engine.restore()).value)
            runCurrent()
            assertEquals(emptyList(), sent)

            history.upsertFailure = null
            assertEquals(Outcome.Success(SessionState.Idle), engine.tick())
            engine.tick()
            runCurrent()

            assertEquals(1, sent.count { it is TelemetryEvent.SessionOutcome })
        }

    @Test
    fun `a Recorded whose commit fails sends nothing, and the retry that commits it sends the stats once`() =
        runTest {
            store.row = SessionJson.encode(ended())
            // The restored Completed state commits nothing new; the commit of Recorded (Idle) is the one that fails.
            store.commitFailure = DomainError.StorageFailure("disk full")
            val engine = engine()

            assertIs<SessionState.Completed>(assertIs<Outcome.Success<SessionState>>(engine.restore()).value)
            runCurrent()
            assertEquals(emptyList(), sent)
            assertEquals(1, history.upserts.size, "the history row is written")

            store.commitFailure = null
            assertEquals(Outcome.Success(SessionState.Idle), engine.tick())
            runCurrent()

            assertEquals(1, sent.count { it is TelemetryEvent.SessionOutcome })
        }

    @Test
    fun `a telemetry that throws is logged by type, and the session still goes Idle`() =
        runTest {
            store.row = SessionJson.encode(ended())

            assertEquals(Outcome.Success(SessionState.Idle), engine(Telemetry { error("analytics broke") }).restore())
            runCurrent()

            assertTrue(LogEvent.OperationFailed("log usage stats", "IllegalStateException") in logger.events, "${logger.events}")
            assertNull(store.row)
        }

    @Test
    fun `a reporter that throws is logged by type, and the session still goes Idle`() =
        runTest {
            store.row = SessionJson.encode(ended())

            val engine = engineWith(TelemetryReporter { throw IllegalStateException("no scope") })

            assertEquals(Outcome.Success(SessionState.Idle), engine.restore())

            assertTrue(LogEvent.OperationFailed("log usage stats", "IllegalStateException") in logger.events, "${logger.events}")
            assertNull(store.row)
        }

    /**
     * Review fix 1: the consent read and the Analytics calls never run inside the engine's lock. A telemetry that is
     * still waiting holds nothing back: a new alarm dispatched meanwhile rings, with its sound, before it completes.
     */
    @Test
    fun `a telemetry still waiting holds nothing back - a concurrent alarm rings with its sound first`() =
        runTest {
            val waiting = CompletableDeferred<Unit>()
            store.row = SessionJson.encode(ended())
            val engine =
                engine(
                    Telemetry {
                        waiting.await()
                        sent += it
                    },
                )
            val restored = launch { engine.restore() }
            runCurrent()
            assertTrue(restored.isCompleted, "the restore handed over the stats and returned")

            val fired = async { engine.dispatch(SessionEvent.AlarmFired("session-2", testConfig(), beforeFirstUnlock = false)) }
            runCurrent()

            assertTrue(fired.isCompleted, "the alarm's dispatch never waited for the telemetry")
            assertIs<SessionState.Ringing>(assertIs<Outcome.Success<SessionState>>(fired.await()).value)
            assertTrue(runner.entry.any { it is EntryEffect.SoundAt }, "the ring's sound ran: ${runner.entry}")
            assertFalse(waiting.isCompleted)
            assertEquals(emptyList(), sent)

            waiting.complete(Unit)
            runCurrent()

            assertEquals(1, sent.count { it is TelemetryEvent.SessionOutcome }, "the stats still go once it completes")
        }

    @Test
    fun `a telemetry cancelled with its scope is logged as cancelled, never thrown`() =
        runTest {
            store.row = SessionJson.encode(ended())
            val job = Job(backgroundScope.coroutineContext[Job])
            val scope = CoroutineScope(backgroundScope.coroutineContext + job)

            val engine = engineWith(TelemetryReporter.Launching(Telemetry { awaitCancellation() }, scope, logger))

            assertEquals(Outcome.Success(SessionState.Idle), engine.restore())
            runCurrent()
            job.cancel()
            runCurrent()

            assertTrue(LogEvent.OperationFailed("log usage stats", "cancelled") in logger.events, "${logger.events}")
        }
}
