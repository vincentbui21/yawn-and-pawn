package com.yawnandpawn.app.core.onboarding

import com.yawnandpawn.app.core.alarm.TestClock
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.SessionLockGuard
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.ringSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Story 5.10: the onboarding consent and progress rules (FR-ONB-1, FR-ONB-5). */
class OnboardingTest {
    private val now = Instant.parse("2027-03-08T21:00:00Z")
    private val clock = TestClock(now)
    private val sessionState = MutableStateFlow<SessionState>(SessionState.Idle)
    private val sessionLock = SessionLockGuard(sessionState, MutableStateFlow(true), MutableStateFlow(false))
    private val repository = InMemoryOnboarding(OnboardingProgress())
    private val accept = AcceptDisclosure(repository, clock, sessionLock)
    private val saveStep = SaveOnboardingStep(repository, sessionLock)
    private val complete = CompleteOnboarding(repository, sessionLock)

    @Test
    fun `a fresh user has not completed onboarding, has no step and has not consented`() {
        val fresh = OnboardingProgress()

        assertFalse(fresh.completed)
        assertNull(fresh.step)
        assertFalse(fresh.consented)
        assertTrue(fresh.copy(disclosureAcceptedAt = now).consented)
    }

    @Test
    fun `I understand stores now as the acceptance time and returns it`() =
        runTest {
            assertEquals(Outcome.Success(now), accept())

            assertEquals(now, repository.current.disclosureAcceptedAt)
        }

    @Test
    fun `pressing I understand again keeps the first acceptance time and writes nothing`() =
        runTest {
            accept()
            clock.advanceBy(5.minutes)

            assertEquals(Outcome.Success(now), accept())

            assertEquals(now, repository.current.disclosureAcceptedAt)
            assertEquals(1, repository.writes)
        }

    @Test
    fun `a failed acceptance write or read is returned and nothing is stored`() =
        runTest {
            repository.writeFailure = DomainError.StorageFailure("disk full")
            assertEquals(Outcome.Failure(DomainError.StorageFailure("disk full")), accept())
            assertNull(repository.current.disclosureAcceptedAt)

            repository.writeFailure = null
            repository.readFailure = DomainError.StorageFailure("unreadable")
            assertEquals(Outcome.Failure(DomainError.StorageFailure("unreadable")), accept())
            assertNull(repository.current.disclosureAcceptedAt)
        }

    @Test
    fun `the step is stored on every change`() =
        runTest {
            assertEquals(Outcome.Success(Unit), saveStep("Disclosure"))
            assertEquals("Disclosure", repository.current.step)

            saveStep("Mission")
            assertEquals("Mission", repository.current.step)
        }

    @Test
    fun `onboarding cannot be completed before the disclosure is accepted`() =
        runTest {
            assertEquals(Outcome.Failure(DomainError.DisclosureRequired), complete())
            assertFalse(repository.current.completed)

            accept()

            assertEquals(Outcome.Success(Unit), complete())
            assertTrue(repository.current.completed)
        }

    @Test
    fun `a failed completion write is returned`() =
        runTest {
            accept()
            repository.writeFailure = DomainError.StorageFailure("disk full")

            assertEquals(Outcome.Failure(DomainError.StorageFailure("disk full")), complete())
            assertFalse(repository.current.completed)
        }

    @Test
    fun `during a session every onboarding write returns SessionActive and writes nothing`() =
        runTest {
            sessionState.value = SessionState.Ringing(ringSession())

            assertEquals(Outcome.Failure(DomainError.SessionActive), accept())
            assertEquals(Outcome.Failure(DomainError.SessionActive), saveStep("Disclosure"))
            assertEquals(Outcome.Failure(DomainError.SessionActive), complete())
            assertEquals(OnboardingProgress(), repository.current)
            assertEquals(0, repository.writes)
        }
}
