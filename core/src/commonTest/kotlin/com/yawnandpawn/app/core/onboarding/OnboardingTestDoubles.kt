package com.yawnandpawn.app.core.onboarding

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Instant

// Core cannot depend on :testing (AD-1): the core tests' own onboarding double; :testing has FakeOnboardingRepository.

/** When the core tests' user accepted the disclosure (well before every test's clock). */
internal val ACCEPTED_AT: Instant = Instant.parse("2027-01-01T08:00:00Z")

/**
 * In-memory [OnboardingRepository] holding [initial] (by default a user who accepted the disclosure, so the alarm tests
 * can save). [readFailure] fails [get]; [writeFailure] fails every write (nothing is stored). [writes] counts the writes
 * that went through.
 */
internal class InMemoryOnboarding(
    initial: OnboardingProgress = OnboardingProgress(completed = true, disclosureAcceptedAt = ACCEPTED_AT),
) : OnboardingRepository {
    private val stored = MutableStateFlow(initial)

    var readFailure: DomainError? = null
    var writeFailure: DomainError? = null
    var writes = 0
        private set

    val current: OnboardingProgress
        get() = stored.value

    override fun observe(): Flow<OnboardingProgress> = stored

    override suspend fun get(): Outcome<OnboardingProgress, DomainError> =
        readFailure?.let { Outcome.Failure(it) } ?: Outcome.Success(stored.value)

    override suspend fun setStep(step: String): Outcome<Unit, DomainError> = write { it.copy(step = step) }

    override suspend fun setDisclosureAccepted(at: Instant): Outcome<Unit, DomainError> = write { it.copy(disclosureAcceptedAt = at) }

    override suspend fun setCompleted(): Outcome<Unit, DomainError> = write { it.copy(completed = true) }

    override suspend fun setAlarmId(id: String?): Outcome<Unit, DomainError> = write { it.copy(alarmId = id) }

    private fun write(change: (OnboardingProgress) -> OnboardingProgress): Outcome<Unit, DomainError> {
        writeFailure?.let { return Outcome.Failure(it) }
        stored.value = change(stored.value)
        writes++
        return Outcome.Success(Unit)
    }
}
