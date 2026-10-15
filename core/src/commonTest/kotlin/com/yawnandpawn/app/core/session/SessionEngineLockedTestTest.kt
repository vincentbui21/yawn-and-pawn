package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.checks.CheckAnswer
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.reliability.LockedTestRecorder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
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

    private fun engine() =
        SessionEngine(
            reducer(),
            store,
            runner,
            SessionRecorder(InMemoryHistory()),
            time.clock,
            time.monotonicClock,
            time.bootCounter,
            logger,
            userLock = userLock,
            unlock = keyguard,
            lockedTest = recorder,
        )

    private val testFired = SessionEvent.TestAlarmFired(SESSION_ID, testConfig(), beforeFirstUnlock = false)

    private fun Outcome<SessionState, DomainError>.session(): SessionData =
        assertIs<SessionState.Active>(assertIs<Outcome.Success<SessionState>>(this).value).session

    /** Rings [event] and completes it a minute later; returns the frozen config. */
    private suspend fun ringAndComplete(
        engine: SessionEngine,
        event: SessionEvent = testFired,
    ): SessionConfig {
        val config = engine.dispatch(event).session().config
        time.advanceBy(1.minutes)
        engine.dispatch(SessionEvent.ImUpTapped)
        assertEquals(Outcome.Success(SessionState.Idle), engine.dispatch(SessionEvent.CheckAnswerSubmitted(CheckAnswer.Placeholder)))
        assertIs<SessionState.Completed>(engine.ended.value)
        return config
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

        override suspend fun lockedTestPassed(at: Instant): Outcome<Unit, DomainError> {
            failure?.let { throw it }
            passes += at
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
            assertTrue(recorder.passes.isEmpty(), "nothing before Completed")
            assertEquals(1, keyguard.reads)

            time.advanceBy(1.minutes)
            engine.dispatch(SessionEvent.ImUpTapped)
            engine.dispatch(SessionEvent.CheckAnswerSubmitted(CheckAnswer.Placeholder))

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

            assertIs<SessionState.Missed>(engine.ended.value)
            assertTrue(recorder.passes.isEmpty())
        }

    @Test
    fun `a failing or throwing write is logged and the session still ends`() =
        runTest {
            keyguard.locked = true
            recorder.result = Outcome.Failure(DomainError.StorageFailure("disk full"))
            ringAndComplete(engine())
            assertEquals(1, recorder.passes.size)
            assertTrue(LogEvent.OperationFailed("record locked test", "storage failure: disk full") in logger.events)

            recorder.failure = IllegalStateException("boom")
            ringAndComplete(engine().also { store.row = null })
            assertTrue(LogEvent.OperationFailed("record locked test", "IllegalStateException") in logger.events)
        }

    @Test
    fun `the engine records nothing without a recorder`() =
        runTest {
            keyguard.locked = true
            val engine =
                SessionEngine(
                    reducer(),
                    store,
                    runner,
                    SessionRecorder(InMemoryHistory()),
                    time.clock,
                    time.monotonicClock,
                    time.bootCounter,
                    logger,
                    unlock = keyguard,
                )
            assertTrue(ringAndComplete(engine).testStartedLocked)
            assertTrue(logger.events.none { it is LogEvent.OperationFailed })
        }
}
