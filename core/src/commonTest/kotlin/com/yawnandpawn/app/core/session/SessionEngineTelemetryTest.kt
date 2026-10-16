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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 5.6: the engine sends a session's usage stats once its `Recorded` has committed, and never for a test. */
class SessionEngineTelemetryTest {
    private val time = EngineTime()
    private val store = InMemorySessionStore()
    private val runner = RecordingRunner()
    private val logger = EngineLogger()
    private val history = InMemoryHistory()
    private val sent = mutableListOf<TelemetryEvent>()
    private val recording = Telemetry { sent += it }

    private fun engine(telemetry: Telemetry = recording) =
        SessionEngine(
            reducer(),
            store,
            runner,
            SessionRecorder(history),
            time.clock,
            time.monotonicClock,
            time.bootCounter,
            logger,
            telemetry = telemetry,
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

            assertEquals(TelemetryEvent.SessionOutcome(SessionOutcome.Missed), sent.first())
            assertEquals(1, sent.count { it is TelemetryEvent.SessionOutcome })
        }

    @Test
    fun `a test session sends nothing`() =
        runTest {
            store.row = SessionJson.encode(ended(testMode = true))

            assertEquals(Outcome.Success(SessionState.Idle), engine().restore())

            assertEquals(emptyList(), sent)
        }

    @Test
    fun `a session completed live sends its stats in the dispatch that ends it, and nothing while it rings`() =
        runTest {
            val engine = engine()
            engine.dispatch(SessionEvent.AlarmFired(SESSION_ID, testConfig(), beforeFirstUnlock = false))
            engine.dispatch(SessionEvent.ImUpTapped)
            assertEquals(emptyList(), sent)

            assertEquals(Outcome.Success(SessionState.Idle), engine.dispatch(SessionEvent.CheckAnswerSubmitted(CheckAnswer.Placeholder)))

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
            assertEquals(emptyList(), sent)

            history.upsertFailure = null
            assertEquals(Outcome.Success(SessionState.Idle), engine.tick())
            engine.tick()

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
            assertEquals(emptyList(), sent)
            assertEquals(1, history.upserts.size, "the history row is written")

            store.commitFailure = null
            assertEquals(Outcome.Success(SessionState.Idle), engine.tick())

            assertEquals(1, sent.count { it is TelemetryEvent.SessionOutcome })
        }

    @Test
    fun `a telemetry that throws is logged by type, and the session still goes Idle`() =
        runTest {
            store.row = SessionJson.encode(ended())

            assertEquals(Outcome.Success(SessionState.Idle), engine(Telemetry { error("analytics broke") }).restore())

            assertTrue(LogEvent.OperationFailed("log usage stats", "IllegalStateException") in logger.events, "${logger.events}")
            assertNull(store.row)
        }

    @Test
    fun `a telemetry that hangs is given up after its timeout, and the session goes Idle`() =
        runTest {
            store.row = SessionJson.encode(ended())

            assertEquals(Outcome.Success(SessionState.Idle), engine(Telemetry { awaitCancellation() }).restore())

            assertTrue(LogEvent.OperationFailed("log usage stats", "timed out") in logger.events, "${logger.events}")
        }
}
