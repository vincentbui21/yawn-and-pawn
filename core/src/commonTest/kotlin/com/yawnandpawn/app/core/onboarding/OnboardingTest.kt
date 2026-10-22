package com.yawnandpawn.app.core.onboarding

import com.yawnandpawn.app.core.alarm.TestClock
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.SessionLockGuard
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.ringSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Stories 5.10 to 5.12: the onboarding consent and progress rules (FR-ONB-1, FR-ONB-5), the first alarm's id, and the
 * test alarm's ring time, result, skip and Home note (FR-ONB-4, FR-ALM-8).
 */
class OnboardingTest {
    private val now = Instant.parse("2027-03-08T21:00:00Z")
    private val clock = TestClock(now)
    private val sessionState = MutableStateFlow<SessionState>(SessionState.Idle)
    private val sessionLock = SessionLockGuard(sessionState, MutableStateFlow(true), MutableStateFlow(false))
    private val repository = InMemoryOnboarding(OnboardingProgress())
    private val accept = AcceptDisclosure(repository, clock, sessionLock)
    private val saveStep = SaveOnboardingStep(repository, sessionLock)
    private val complete = CompleteOnboarding(repository, sessionLock)
    private val rememberAlarm = RememberOnboardingAlarm(repository, sessionLock)
    private val rememberTest = RememberOnboardingTest(repository, sessionLock)
    private val testNote = OnboardingTestNote(repository, InMemoryRecords(), sessionLock)

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
    fun `the first alarm's id is remembered, and null forgets it`() =
        runTest {
            assertNull(OnboardingProgress().alarmId, "no first alarm before onboarding saves one")

            assertEquals(Outcome.Success(Unit), rememberAlarm("alarm-1"))
            assertEquals("alarm-1", repository.current.alarmId)

            assertEquals(Outcome.Success(Unit), rememberAlarm(null))
            assertNull(repository.current.alarmId)
        }

    @Test
    fun `a failed first alarm write is returned`() =
        runTest {
            repository.writeFailure = DomainError.StorageFailure("disk full")

            assertEquals(Outcome.Failure(DomainError.StorageFailure("disk full")), rememberAlarm("alarm-1"))
            assertNull(repository.current.alarmId)
        }

    @Test
    fun `during a session every onboarding write returns SessionActive and writes nothing`() =
        runTest {
            sessionState.value = SessionState.Ringing(ringSession())

            assertEquals(Outcome.Failure(DomainError.SessionActive), accept())
            assertEquals(Outcome.Failure(DomainError.SessionActive), saveStep("Disclosure"))
            assertEquals(Outcome.Failure(DomainError.SessionActive), complete())
            assertEquals(Outcome.Failure(DomainError.SessionActive), complete(testSkipped = true))
            assertEquals(Outcome.Failure(DomainError.SessionActive), rememberAlarm("alarm-1"))
            assertEquals(Outcome.Failure(DomainError.SessionActive), rememberTest(now))
            assertEquals(Outcome.Failure(DomainError.SessionActive), testNote.dismiss())
            assertEquals(OnboardingProgress(), repository.current)
            assertEquals(0, repository.writes)
        }

    // Story 5.12: the test alarm on a locked screen.

    @Test
    fun `when step 8's test rings is remembered, and null forgets it`() =
        runTest {
            assertNull(OnboardingProgress().testRingsAt)
            assertFalse(OnboardingProgress().testNoteDue)

            assertEquals(Outcome.Success(Unit), rememberTest(now))
            assertEquals(now, repository.current.testRingsAt)

            assertEquals(Outcome.Success(Unit), rememberTest(null))
            assertNull(repository.current.testRingsAt)
        }

    @Test
    fun `Skip for now completes with Home's note, a passed test completes without it`() =
        runTest {
            accept()

            assertEquals(Outcome.Success(Unit), complete(testSkipped = true))
            assertTrue(repository.current.completed)
            assertTrue(repository.current.testNoteDue)

            val passed = InMemoryOnboarding(OnboardingProgress(disclosureAcceptedAt = now))
            assertEquals(Outcome.Success(Unit), CompleteOnboarding(passed, sessionLock)())
            assertTrue(passed.current.completed)
            assertFalse(passed.current.testNoteDue)
        }

    @Test
    fun `a skip whose note cannot be stored completes nothing, and a skip without consent is refused`() =
        runTest {
            assertEquals(Outcome.Failure(DomainError.DisclosureRequired), complete(testSkipped = true))
            assertFalse(repository.current.testNoteDue)

            accept()
            repository.writeFailure = DomainError.StorageFailure("disk full")
            assertEquals(Outcome.Failure(DomainError.StorageFailure("disk full")), complete(testSkipped = true))
            assertFalse(repository.current.completed)
        }

    @Test
    fun `the test result is not rung, waiting, passed or not locked`() {
        val ringsAt = now + 10.seconds
        val result = OnboardingTestAlarm::result

        assertEquals(OnboardingTestResult.NotRung, result(null, null, now))
        assertEquals(OnboardingTestResult.NotRung, result(null, now, now), "a pass without a ring from step 8")
        assertEquals(OnboardingTestResult.Waiting, result(ringsAt, null, now))
        assertEquals(OnboardingTestResult.Waiting, result(ringsAt, null, ringsAt + 9.seconds))
        assertEquals(OnboardingTestResult.NotLocked, result(ringsAt, null, ringsAt + OnboardingTestAlarm.RESULT_SLACK))
        assertEquals(OnboardingTestResult.Passed, result(ringsAt, ringsAt + 30.seconds, ringsAt + 40.seconds))
        assertEquals(OnboardingTestResult.Passed, result(ringsAt, now, now), "a pass from the moment the ring was asked for")
        assertEquals(
            OnboardingTestResult.Passed,
            result(ringsAt, now - 2.minutes, ringsAt + 1.minutes),
            "review fix: a pass after the clock was set back 2 min still counts",
        )
        assertEquals(
            OnboardingTestResult.NotLocked,
            result(ringsAt, now - OnboardingTestAlarm.CLOCK_SKEW - 1.seconds, ringsAt + 1.minutes),
            "a pass from well before the ring was asked for does not count",
        )
        assertEquals(OnboardingTestResult.Waiting, result(ringsAt, now - 1.hours, now))
    }

    @Test
    fun `Home's test note shows while due and no locked test has passed, and Dismiss ends it`() =
        runTest {
            val records = InMemoryRecords()
            val note = OnboardingTestNote(repository, records, sessionLock)
            assertFalse(note.shown().first(), "not due")

            accept()
            complete(testSkipped = true)
            assertTrue(note.shown().first())

            records.passedAt = now + 1.minutes
            assertFalse(note.shown().first(), "a locked test passed")

            records.passedAt = null
            assertEquals(Outcome.Success(Unit), note.dismiss())
            assertFalse(note.shown().first(), "dismissed")
            assertFalse(repository.current.testNoteDue)
        }

    @Test
    fun `a failed note dismissal is returned and the note stays`() =
        runTest {
            accept()
            complete(testSkipped = true)
            repository.writeFailure = DomainError.StorageFailure("disk full")

            assertEquals(Outcome.Failure(DomainError.StorageFailure("disk full")), testNote.dismiss())
            assertTrue(testNote.shown().first())
        }
}
