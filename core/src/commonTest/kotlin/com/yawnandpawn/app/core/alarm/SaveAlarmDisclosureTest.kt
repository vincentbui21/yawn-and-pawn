package com.yawnandpawn.app.core.alarm

import com.yawnandpawn.app.core.config.InMemoryPendingChanges
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.onboarding.AcceptDisclosure
import com.yawnandpawn.app.core.onboarding.InMemoryOnboarding
import com.yawnandpawn.app.core.onboarding.OnboardingProgress
import com.yawnandpawn.app.core.session.SessionLockGuard
import com.yawnandpawn.app.core.session.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

/**
 * Story 5.10 (FR-ONB-5): no alarm is saved before "I understand" is stored. A new alarm and an edit alike return
 * `DisclosureRequired` with nothing stored or armed, an unreadable onboarding progress refuses the save (fail closed),
 * and once the disclosure is accepted the same save goes through.
 */
class SaveAlarmDisclosureTest {
    private val clock = TestClock(Instant.parse("2027-03-08T12:00:00Z"))
    private val repository = InMemoryAlarms()
    private val lock = AlarmWriteLock()
    private val scheduler = RecordingScheduler()
    private val scheduling = AlarmScheduling(repository, scheduler, clock, TestZone(TimeZone.UTC), lock, RecordingLogger())
    private val sessionLock =
        SessionLockGuard(MutableStateFlow<SessionState>(SessionState.Idle), MutableStateFlow(true), MutableStateFlow(false))
    private val checkConfigs = InMemoryCheckConfigs(repository)
    private val sequence = InMemorySequence()
    private val onboarding = InMemoryOnboarding(OnboardingProgress())
    private val save =
        SaveAlarm(
            repository,
            SequentialIds(),
            clock,
            lock,
            sequence,
            scheduling,
            sessionLock,
            checkConfigs,
            InMemoryPendingChanges(),
            TestZone(TimeZone.UTC),
            onboarding,
        )
    private val draft = AlarmDraft(time = LocalTime(7, 30))

    @Test
    fun `a new alarm before consent is refused, and nothing is stored, allocated or armed`() =
        runTest {
            val codeBefore = sequence.lastUsed

            assertEquals(Outcome.Failure(DomainError.DisclosureRequired), save(draft))

            assertEquals(emptyMap(), repository.alarms.value)
            assertEquals(emptyMap(), checkConfigs.rows.value)
            assertEquals(codeBefore, sequence.lastUsed)
            assertEquals(emptyList(), scheduler.calls)
        }

    @Test
    fun `an edit of an alarm stored before this build is refused too, and the stored alarm is untouched`() =
        runTest {
            val old = alarm(id = "old", requestCode = RequestCodes.FIRST_ALARM)
            repository.upsert(old)

            assertEquals(Outcome.Failure(DomainError.DisclosureRequired), save(draft.copy(id = "old", time = LocalTime(9, 0))))

            assertEquals(mapOf("old" to old), repository.alarms.value)
            assertEquals(emptyList(), scheduler.calls)
        }

    @Test
    fun `an unreadable onboarding progress refuses the save with its storage failure`() =
        runTest {
            onboarding.readFailure = DomainError.StorageFailure("unreadable")

            assertEquals(Outcome.Failure(DomainError.StorageFailure("unreadable")), save(draft))
            assertEquals(emptyMap(), repository.alarms.value)
        }

    @Test
    fun `once I understand is stored the same save goes through`() =
        runTest {
            assertEquals(Outcome.Success(clock.now), AcceptDisclosure(onboarding, clock, sessionLock)())

            val saved = assertIs<Outcome.Success<Alarm>>(save(draft)).value

            assertEquals(saved, repository.alarms.value[saved.id])
        }

    private fun alarm(
        id: String,
        requestCode: Int,
    ): Alarm =
        Alarm(
            id = id,
            time = LocalTime(7, 0),
            repeatDays = emptySet(),
            label = null,
            enabled = true,
            requestCode = requestCode,
            createdAt = clock.now,
            updatedAt = clock.now,
        )
}
