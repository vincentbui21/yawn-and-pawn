package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.checks.CheckAnswer
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.reliability.LockedTestRecorder
import com.yawnandpawn.app.core.reliability.LockedTestReporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Story 5.3: a test rung with the phone locked and completed is stored for the checklist's locked-screen test. */
class SessionEngineLockedTestTest {
    private val time = EngineTime()
    private val store = InMemorySessionStore()
    private val runner = RecordingRunner()
    private val logger = EngineLogger()
    private val keyguard = Keyguard()
    private val recorder = Recorder()
    private val userLock = UserLock()
    private val history = InMemoryHistory()

    /** An engine whose locked-test writes are launched on [scope], as the app launches them on ApplicationScope. */
    private fun engine(scope: CoroutineScope) =
        SessionEngine(
            reducer(),
            store,
            runner,
            SessionRecorder(history),
            time.clock,
            time.monotonicClock,
            time.bootCounter,
            logger,
            userLock = userLock,
            unlock = keyguard,
            lockedTest = LockedTestReporter.Launching(recorder, scope, logger),
        )

    private fun TestScope.engine() = engine(backgroundScope)

    private val testFired = SessionEvent.TestAlarmFired(SESSION_ID, testConfig(), beforeFirstUnlock = false)

    private fun Outcome<SessionState, DomainError>.session(): SessionData =
        assertIs<SessionState.Active>(assertIs<Outcome.Success<SessionState>>(this).value).session

    /** Rings [event] and completes it a minute later, then runs the launched write; returns the frozen config. */
    private suspend fun TestScope.ringAndComplete(
        engine: SessionEngine,
        event: SessionEvent = testFired,
    ): SessionConfig {
        val config = engine.dispatch(event).session().config
        complete(engine)
        return config
    }

    /** "I'm up" a minute later and the right answer: the session completes and is recorded; the write runs. */
    private suspend fun TestScope.complete(engine: SessionEngine) {
        time.advanceBy(1.minutes)
        engine.dispatch(SessionEvent.ImUpTapped)
        assertEquals(Outcome.Success(SessionState.Idle), engine.dispatch(SessionEvent.CheckAnswerSubmitted(CheckAnswer.Placeholder)))
        assertIs<SessionState.Completed>(engine.ended.value)
        runCurrent()
    }

    private class Keyguard : UnlockPort {
        var locked = false
        var failure: Exception? = null
        var reads = 0

        override fun isKeyguardLocked(): Boolean {
            reads++
            failure?.let { throw it }
            return locked
        }

        override suspend fun requestUnlock(): UnlockResult = UnlockResult.Succeeded
    }

    private class Recorder : LockedTestRecorder {
        val passes = mutableListOf<Instant>()
        var result: Outcome<Unit, DomainError> = Outcome.Success(Unit)
        var failure: Exception? = null
        var hangs = false

        override suspend fun lockedTestPassed(at: Instant): Outcome<Unit, DomainError> {
            passes += at
            if (hangs) awaitCancellation()
            failure?.let { throw it }
            return result
        }
    }

    private class UserLock : UserLockState {
        val state = MutableStateFlow(true)

        override fun isUserUnlocked(): Boolean = state.value

        override fun observe(): Flow<Boolean> = state
    }

    @Test
    fun `a test fired with the keyguard showing stores the pass once, when it reaches Completed`() =
        runTest {
            keyguard.locked = true
            val engine = engine()

            val config = engine.dispatch(testFired).session().config
            assertTrue(config.testStartedLocked)
            runCurrent()
            assertTrue(recorder.passes.isEmpty(), "nothing before Completed")
            assertEquals(1, keyguard.reads)

            complete(engine)

            assertEquals(listOf(Instant.fromEpochMilliseconds(time.now.wallMillis)), recorder.passes)
            assertEquals(1, keyguard.reads, "only the fire reads the keyguard")
            assertTrue(logger.events.none { it is LogEvent.OperationFailed })
        }

    @Test
    fun `a test fired unlocked stores nothing, and neither does a real alarm rung locked`() =
        runTest {
            assertFalse(ringAndComplete(engine()).testStartedLocked)
            assertTrue(recorder.passes.isEmpty())

            keyguard.locked = true
            val reads = keyguard.reads
            val real = ringAndComplete(engine().also { store.row = null }, SessionEvent.AlarmFired("session-2", testConfig(), false))
            assertFalse(real.testStartedLocked)
            assertEquals(reads, keyguard.reads, "a real alarm never reads the keyguard")
            assertTrue(recorder.passes.isEmpty())
        }

    /**
     * Review item 16 (OPEN QUESTION for the owner, flagged for Story 5.12): a phone with no screen lock never shows the
     * keyguard, so its test can never pass; a test rung with the screen on and unlocked counts as unlocked too.
     */
    @Test
    fun `a phone whose keyguard never shows (screen lock None) can never pass the locked test`() =
        runTest {
            keyguard.locked = false
            repeat(2) { ringAndComplete(engine().also { store.row = null }) }

            assertTrue(recorder.passes.isEmpty())
            assertEquals(2, keyguard.reads)
        }

    @Test
    fun `a test before the first unlock counts as locked, a keyguard that cannot tell counts as unlocked`() =
        runTest {
            keyguard.failure = IllegalStateException("keyguard service gone")
            assertFalse(ringAndComplete(engine()).testStartedLocked)
            assertTrue(recorder.passes.isEmpty())

            userLock.state.value = false
            assertTrue(ringAndComplete(engine().also { store.row = null }).testStartedLocked)
            assertEquals(1, recorder.passes.size)
        }

    @Test
    fun `a missed locked test stores nothing`() =
        runTest {
            keyguard.locked = true
            val engine = engine()
            engine.dispatch(testFired)

            time.advanceBy(30.minutes)
            engine.dispatch(SessionEvent.SlotFired)
            runCurrent()

            assertIs<SessionState.Missed>(engine.ended.value)
            assertTrue(recorder.passes.isEmpty())
        }

    @Test
    fun `a failing, throwing or cancelled write is logged and the session still ends`() =
        runTest {
            keyguard.locked = true
            recorder.result = Outcome.Failure(DomainError.StorageFailure("disk full"))
            ringAndComplete(engine())
            assertEquals(1, recorder.passes.size)
            assertTrue(LogEvent.OperationFailed("record locked test", "storage failure: disk full") in logger.events)

            recorder.failure = IllegalStateException("boom")
            ringAndComplete(engine().also { store.row = null })
            assertTrue(LogEvent.OperationFailed("record locked test", "IllegalStateException") in logger.events)

            // Review item 17: a CancellationException from the adapter never reaches the engine's dispatch.
            recorder.failure = CancellationException("adapter cancelled")
            ringAndComplete(engine().also { store.row = null })
            assertTrue(LogEvent.OperationFailed("record locked test", "cancelled") in logger.events)
            assertEquals(3, recorder.passes.size)
        }

    @Test
    fun `a write that never returns holds nothing back - the next real alarm still rings (review fix 11)`() =
        runTest {
            keyguard.locked = true
            recorder.hangs = true
            val engine = engine()
            ringAndComplete(engine)
            assertEquals(1, recorder.passes.size, "the write started and hangs")

            val real = engine.dispatch(SessionEvent.AlarmFired("session-2", testConfig(), false))

            assertIs<SessionState.Ringing>(assertIs<Outcome.Success<SessionState>>(real).value)
            assertIs<SessionState.Ringing>(engine.state.value)
        }

    @Test
    fun `a reporter that throws is logged and the session still ends`() =
        runTest {
            keyguard.locked = true
            val engine =
                SessionEngine(
                    reducer(),
                    store,
                    runner,
                    SessionRecorder(history),
                    time.clock,
                    time.monotonicClock,
                    time.bootCounter,
                    logger,
                    unlock = keyguard,
                    lockedTest = { throw IllegalStateException("no scope") },
                )

            assertTrue(ringAndComplete(engine).testStartedLocked)
            assertTrue(LogEvent.OperationFailed("record locked test", "IllegalStateException") in logger.events)
        }

    @Test
    fun `a test the wake service ends for a real alarm is not the user's, so it stores nothing (review fix 10)`() =
        runTest {
            keyguard.locked = true
            val engine = engine()
            engine.dispatch(testFired)

            engine.testPreempted(SESSION_ID)
            complete(engine)

            assertTrue(recorder.passes.isEmpty())

            // Only that session: the next locked test the user completes passes.
            ringAndComplete(engine, SessionEvent.TestAlarmFired("session-2", testConfig(), beforeFirstUnlock = false))
            assertEquals(1, recorder.passes.size)
        }

    @Test
    fun `a stored Completed locked test whose history write fails is never recorded again on restore or tick`() =
        runTest {
            keyguard.locked = true
            val first = engine()
            first.dispatch(testFired)
            history.upsertFailure = DomainError.StorageFailure("disk")
            complete(first, expectIdle = false)
            assertEquals(1, recorder.passes.size, "the completion itself is recorded")
            assertIs<SessionState.Completed>(store.stored)

            val restored = engine()
            restored.restore()
            restored.tick()
            restored.tick()
            runCurrent()

            assertIs<SessionState.Completed>(restored.state.value, "the history row is still not written")
            assertEquals(1, recorder.passes.size, "a restored Completed is not a new completion")
        }

    @Test
    fun `a locked test restored after a process kill passes when completed unlocked, without a new keyguard read`() =
        runTest {
            keyguard.locked = true
            engine().dispatch(testFired)
            keyguard.locked = false
            keyguard.reads = 0

            val restored = engine()
            restored.restore()
            complete(restored)

            assertEquals(1, recorder.passes.size)
            assertEquals(0, keyguard.reads, "the lock was frozen at the fire")
        }

    @Test
    fun `a locked test cannot be snoozed, and completing it after the unlock passes once with one keyguard read`() =
        runTest {
            keyguard.locked = true
            val engine = engine()
            engine.dispatch(testFired)

            // A test never snoozes (it never charges): the tap is ignored and the test rings on.
            assertIs<SessionState.Ringing>(assertIs<Outcome.Success<SessionState>>(engine.dispatch(SessionEvent.SnoozeTapped)).value)
            keyguard.locked = false
            complete(engine)

            assertEquals(1, recorder.passes.size)
            assertEquals(1, keyguard.reads)
        }

    /** [complete], for a session whose history write fails: it stays Completed instead of going Idle. */
    private suspend fun TestScope.complete(
        engine: SessionEngine,
        expectIdle: Boolean,
    ) {
        if (expectIdle) return complete(engine)
        time.advanceBy(1.minutes)
        engine.dispatch(SessionEvent.ImUpTapped)
        engine.dispatch(SessionEvent.CheckAnswerSubmitted(CheckAnswer.Placeholder))
        runCurrent()
    }
}
