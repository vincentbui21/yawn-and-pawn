package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.onboarding.OnboardingProgress
import com.yawnandpawn.app.core.onboarding.OnboardingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlin.time.Instant

/** When the presets' user accepted the alarm behaviour disclosure. */
val ONBOARDING_ACCEPTED_AT: Instant = Instant.parse("2026-01-01T08:00:00Z")

/** Onboarding presets (Story 5.10). */
object OnboardingPresets {
    /** A first launch (or after "Delete all data"): nothing stored. */
    val Fresh: OnboardingProgress = OnboardingProgress()

    /** "I understand" tapped on step 2, now on the base fee step. */
    val Consented: OnboardingProgress = OnboardingProgress(step = "BaseFee", disclosureAcceptedAt = ONBOARDING_ACCEPTED_AT)

    /** Onboarding done: the app opens on Home and alarms can be saved. */
    val Completed: OnboardingProgress =
        OnboardingProgress(completed = true, step = "BaseFee", disclosureAcceptedAt = ONBOARDING_ACCEPTED_AT)
}

/**
 * In-memory [OnboardingRepository] holding [initial] (by default [OnboardingPresets.Completed], so a test that is not
 * about onboarding can save alarms and opens on Home). Failure switches: [readFailure] fails [get]; [writeFailure] fails
 * every write (nothing is stored); [observeFailure] makes [observe] throw it. [steps] keeps every step written.
 */
class FakeOnboardingRepository(
    initial: OnboardingProgress = OnboardingPresets.Completed,
) : OnboardingRepository {
    private val stored = MutableStateFlow(initial)
    private val written = mutableListOf<String>()

    var readFailure: DomainError? = null
    var writeFailure: DomainError? = null
    var observeFailure: Throwable? = null

    /** The stored progress right now. */
    var current: OnboardingProgress
        get() = stored.value
        set(value) {
            stored.value = value
        }

    /** Every step stored, in order. */
    val steps: List<String>
        get() = written.toList()

    override fun observe(): Flow<OnboardingProgress> {
        val failure = observeFailure ?: return stored
        return flow { throw failure }
    }

    override suspend fun get(): Outcome<OnboardingProgress, DomainError> =
        readFailure?.let { Outcome.Failure(it) } ?: Outcome.Success(stored.value)

    override suspend fun setStep(step: String): Outcome<Unit, DomainError> =
        write {
            written += step
            it.copy(step = step)
        }

    override suspend fun setDisclosureAccepted(at: Instant): Outcome<Unit, DomainError> = write { it.copy(disclosureAcceptedAt = at) }

    override suspend fun setCompleted(): Outcome<Unit, DomainError> = write { it.copy(completed = true) }

    private fun write(change: (OnboardingProgress) -> OnboardingProgress): Outcome<Unit, DomainError> {
        writeFailure?.let { return Outcome.Failure(it) }
        stored.value = change(stored.value)
        return Outcome.Success(Unit)
    }
}
