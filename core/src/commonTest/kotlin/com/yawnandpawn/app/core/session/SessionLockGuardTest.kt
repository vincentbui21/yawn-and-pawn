package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Story 2.6: the guard runs a write only while no session is active, and reads the engine state at call time. */
class SessionLockGuardTest {
    private val state = MutableStateFlow<SessionState>(SessionState.Idle)
    private val guard = SessionLockGuard(state)

    @Test
    fun `when Idle the block runs and its outcome is returned`() =
        runTest {
            assertFalse(guard.isLocked)
            assertEquals(Outcome.Success(42), guard.whenIdle { Outcome.Success(42) })
        }

    @Test
    fun `in every other state the block never runs and SessionActive is returned`() =
        runTest {
            val session = ringSession()
            val active =
                listOf(
                    SessionState.Ringing(session),
                    SessionState.Grace(session),
                    SessionState.Loud(session),
                    SessionState.Snoozed(snoozedSession()),
                    SessionState.Completed(session),
                    SessionState.Missed(session),
                )

            active.forEach { locked ->
                state.value = locked
                var ran = false

                val outcome = guard.whenIdle<Unit> { Outcome.Success(Unit).also { ran = true } }

                assertTrue(guard.isLocked, "$locked")
                assertEquals(Outcome.Failure(DomainError.SessionActive), outcome, "$locked")
                assertFalse(ran, "nothing runs in $locked")
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
}
