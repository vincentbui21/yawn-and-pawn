package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Story 2.6: the guard runs a write only while the app is not session-locked (restored, and no ring, snooze or emergency
 * ring in progress), reads the state at call time, and never lets a session start in the middle of a guarded write.
 */
class SessionLockGuardTest {
    private val state = MutableStateFlow<SessionState>(SessionState.Idle)
    private val restored = MutableStateFlow(true)
    private val emergency = MutableStateFlow(false)
    private val guard = SessionLockGuard(state, restored, emergency)

    @Test
    fun `when Idle the block runs and its outcome is returned`() =
        runTest {
            assertFalse(guard.isLocked)
            assertEquals(Outcome.Success(42), guard.whenIdle { Outcome.Success(42) })
        }

    @Test
    fun `during a ring or a snooze the block never runs and SessionActive is returned`() =
        runTest {
            val session = ringSession()
            val inProgress =
                listOf(
                    SessionState.Ringing(session),
                    SessionState.Grace(session),
                    SessionState.Loud(session),
                    SessionState.Snoozed(snoozedSession()),
                )

            inProgress.forEach { locked ->
                state.value = locked
                assertRefused("$locked")
            }
        }

    @Test
    fun `before the stored session is restored (or while its load fails) the guard refuses, though the state is Idle`() =
        runTest {
            restored.value = false

            assertRefused("not restored")

            restored.value = true
            assertEquals(Outcome.Success(1), guard.whenIdle { Outcome.Success(1) })
        }

    @Test
    fun `while the emergency ring plays the guard refuses`() =
        runTest {
            emergency.value = true

            assertRefused("emergency ring")
        }

    @Test
    fun `Completed and Missed only wait for their history row, so they do not lock`() =
        runTest {
            listOf(SessionState.Completed(ringSession()), SessionState.Missed(ringSession())).forEach { ended ->
                state.value = ended
                assertFalse(guard.isLocked, "$ended")
                assertEquals(Outcome.Success(1), guard.whenIdle { Outcome.Success(1) }, "$ended")
            }
        }

    @Test
    fun `the lock lifts as soon as the session is Idle again`() =
        runTest {
            state.value = SessionState.Ringing(ringSession())
            assertEquals(Outcome.Failure(DomainError.SessionActive), guard.whenIdle { Outcome.Success(1) })

            state.value = SessionState.Idle

            assertEquals(Outcome.Success(1), guard.whenIdle { Outcome.Success(1) })
        }

    @Test
    fun `a session start waits for a guarded write in flight, and the next write is refused`() =
        runTest {
            val writing = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()
            val write =
                async {
                    guard.whenIdle {
                        events += "write started"
                        writing.await()
                        events += "write done"
                        Outcome.Success(Unit)
                    }
                }
            runCurrent()
            launch {
                guard.startingSession {
                    events += "session started"
                    state.value = SessionState.Ringing(ringSession())
                }
            }
            runCurrent()
            assertEquals(listOf("write started"), events, "the start waits")

            writing.complete(Unit)

            assertEquals(Outcome.Success(Unit), write.await())
            runCurrent()
            assertEquals(listOf("write started", "write done", "session started"), events)
            assertEquals(Outcome.Failure(DomainError.SessionActive), guard.whenIdle { Outcome.Success(Unit) })
        }

    @Test
    fun `locked emits the current lock, then each change once`() =
        runTest {
            restored.value = false
            val seen = async { guard.locked.take(4).toList() }
            runCurrent()

            restored.value = true
            runCurrent()
            state.value = SessionState.Ringing(ringSession())
            runCurrent()
            state.value = SessionState.Loud(ringSession())
            runCurrent()
            state.value = SessionState.Completed(ringSession())
            runCurrent()
            emergency.value = true
            runCurrent()

            assertEquals(listOf(true, false, true, false), seen.await())
            assertTrue(guard.locked.first())
        }

    private suspend fun assertRefused(case: String) {
        var ran = false

        val outcome = guard.whenIdle<Unit> { Outcome.Success(Unit).also { ran = true } }

        assertTrue(guard.isLocked, case)
        assertEquals(Outcome.Failure(DomainError.SessionActive), outcome, case)
        assertFalse(ran, "nothing runs: $case")
    }
}
