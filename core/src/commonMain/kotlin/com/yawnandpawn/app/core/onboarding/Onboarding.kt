package com.yawnandpawn.app.core.onboarding

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.error.flatMap
import com.yawnandpawn.app.core.error.map
import com.yawnandpawn.app.core.session.SessionLockGuard
import com.yawnandpawn.app.core.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/**
 * How far the user got through onboarding (FR-ONB-1, FR-ONB-5, Story 5.10): whether it is [completed], the [step] the
 * user is on (its UI name, stored on every change so it survives process death; null before the first change), and
 * when the user tapped "I understand" under the alarm behaviour disclosure ([disclosureAcceptedAt], null until then).
 * No alarm can be saved before that consent ([consented]). [alarmId] is the first alarm onboarding saved (Story 5.11):
 * going back to that step edits it instead of adding a second one, and the test alarm (Story 5.12) rings it.
 */
data class OnboardingProgress(
    val completed: Boolean = false,
    val step: String? = null,
    val disclosureAcceptedAt: Instant? = null,
    val alarmId: String? = null,
) {
    /** The user accepted the alarm behaviour disclosure. */
    val consented: Boolean
        get() = disclosureAcceptedAt != null
}

/**
 * Port for the onboarding progress, kept by `:data` in the settings DataStore (device-protected, backed up, cleared by
 * "Delete all data"), `FakeOnboardingRepository` in tests. A store that cannot be read emits a fresh [OnboardingProgress]
 * from [observe] (logged), so the user is sent through onboarding rather than past it; [get] returns that read failure
 * as a `StorageFailure`. Writes return a `StorageFailure` and are never thrown.
 */
interface OnboardingRepository {
    /** The stored progress; emits again after every change. */
    fun observe(): Flow<OnboardingProgress>

    suspend fun get(): Outcome<OnboardingProgress, DomainError>

    /** Stores [step] as the step the user is on. */
    suspend fun setStep(step: String): Outcome<Unit, DomainError>

    /** Stores [at] as the time the user accepted the disclosure ([AcceptDisclosure] keeps the first one). */
    suspend fun setDisclosureAccepted(at: Instant): Outcome<Unit, DomainError>

    /** Marks onboarding as completed: the app opens on Home from now on. */
    suspend fun setCompleted(): Outcome<Unit, DomainError>

    /** Stores [id] as the first alarm onboarding saved (Story 5.11); null forgets it. */
    suspend fun setAlarmId(id: String?): Outcome<Unit, DomainError>
}

/**
 * "I understand" under the alarm behaviour disclosure (FR-ONB-5): stores now as the acceptance time and returns it. An
 * earlier acceptance is kept, not moved (DEFAULT TAKEN, owner can change): the first consent is the one that counts.
 * Like every user-facing write it runs inside [SessionLockGuard.whenIdle] (onboarding is behind the session lock then
 * anyway).
 */
class AcceptDisclosure(
    private val repository: OnboardingRepository,
    private val clock: Clock,
    private val sessionLock: SessionLockGuard,
) {
    suspend operator fun invoke(): Outcome<Instant, DomainError> =
        sessionLock.whenIdle {
            repository.get().flatMap { progress ->
                val first = progress.disclosureAcceptedAt
                if (first != null) {
                    Outcome.Success(first)
                } else {
                    val now = clock.now()
                    repository.setDisclosureAccepted(now).map { now }
                }
            }
        }
}

/**
 * Stores the onboarding step the user is on (by its UI name), on every change, so the step survives process death.
 * Inside [SessionLockGuard.whenIdle] like every write the user drives.
 */
class SaveOnboardingStep(
    private val repository: OnboardingRepository,
    private val sessionLock: SessionLockGuard,
) {
    suspend operator fun invoke(step: String): Outcome<Unit, DomainError> = sessionLock.whenIdle { repository.setStep(step) }
}

/**
 * Finishes onboarding: the app opens on Home from now on. Refused with [DomainError.DisclosureRequired] while the
 * disclosure is not accepted, so no path skips the consent screen. Inside [SessionLockGuard.whenIdle].
 */
class CompleteOnboarding(
    private val repository: OnboardingRepository,
    private val sessionLock: SessionLockGuard,
) {
    suspend operator fun invoke(): Outcome<Unit, DomainError> =
        sessionLock.whenIdle {
            repository.get().flatMap { progress ->
                if (progress.consented) repository.setCompleted() else Outcome.Failure(DomainError.DisclosureRequired)
            }
        }
}

/**
 * Remembers the first alarm onboarding saved (Story 5.11), so the step survives process death without a second alarm:
 * going back to the first alarm or its checks edits that alarm. Null forgets it (the stored alarm could not be read).
 * Inside [SessionLockGuard.whenIdle] like every write the user drives.
 */
class RememberOnboardingAlarm(
    private val repository: OnboardingRepository,
    private val sessionLock: SessionLockGuard,
) {
    suspend operator fun invoke(id: String?): Outcome<Unit, DomainError> = sessionLock.whenIdle { repository.setAlarmId(id) }
}
