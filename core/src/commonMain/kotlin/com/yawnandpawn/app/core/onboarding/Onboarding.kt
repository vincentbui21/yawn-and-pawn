package com.yawnandpawn.app.core.onboarding

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.error.flatMap
import com.yawnandpawn.app.core.error.map
import com.yawnandpawn.app.core.reliability.ReliabilityRecords
import com.yawnandpawn.app.core.session.ScheduleTestAlarm
import com.yawnandpawn.app.core.session.SessionLockGuard
import com.yawnandpawn.app.core.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * How far the user got through onboarding (FR-ONB-1, FR-ONB-5, Story 5.10): whether it is [completed], the [step] the
 * user is on (its UI name, stored on every change so it survives process death; null before the first change), and
 * when the user tapped "I understand" under the alarm behaviour disclosure ([disclosureAcceptedAt], null until then).
 * No alarm can be saved before that consent ([consented]). [alarmId] is the first alarm onboarding saved (Story 5.11):
 * going back to that step edits it instead of adding a second one, and the test alarm (Story 5.12) rings it.
 * [testRingsAt] is when step 8's last test rings (Story 5.12), so its result survives a cleared screen or process death;
 * [testNoteDue] is set when step 8 was skipped: Home shows "Ring a test alarm with your phone locked to check it works."
 * until a locked test passes or the note is dismissed ([OnboardingTestNote]).
 */
data class OnboardingProgress(
    val completed: Boolean = false,
    val step: String? = null,
    val disclosureAcceptedAt: Instant? = null,
    val alarmId: String? = null,
    val testRingsAt: Instant? = null,
    val testNoteDue: Boolean = false,
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

    /** Stores [at] as when step 8's last test rings (Story 5.12); null forgets it. */
    suspend fun setTestRingsAt(at: Instant?): Outcome<Unit, DomainError>

    /** Stores whether Home shows the skipped-test note (Story 5.12). */
    suspend fun setTestNoteDue(due: Boolean): Outcome<Unit, DomainError>
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
 * disclosure is not accepted, so no path skips the consent screen. With [testSkipped] ("Skip for now" on step 8, Story
 * 5.12) Home's test note is stored first, so a completed onboarding never misses it; a failed note write completes
 * nothing. Inside [SessionLockGuard.whenIdle].
 */
class CompleteOnboarding(
    private val repository: OnboardingRepository,
    private val sessionLock: SessionLockGuard,
) {
    suspend operator fun invoke(testSkipped: Boolean = false): Outcome<Unit, DomainError> =
        sessionLock.whenIdle {
            repository.get().flatMap { progress ->
                when {
                    !progress.consented -> Outcome.Failure(DomainError.DisclosureRequired)
                    testSkipped -> repository.setTestNoteDue(true).flatMap { repository.setCompleted() }
                    else -> repository.setCompleted()
                }
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

/**
 * Remembers when step 8's test rings (Story 5.12), so the step reads its result again after a cleared screen or process
 * death. Inside [SessionLockGuard.whenIdle] like every write the user drives.
 */
class RememberOnboardingTest(
    private val repository: OnboardingRepository,
    private val sessionLock: SessionLockGuard,
) {
    suspend operator fun invoke(ringsAt: Instant?): Outcome<Unit, DomainError> = sessionLock.whenIdle { repository.setTestRingsAt(ringsAt) }
}

/** What step 8's test alarm came to (Story 5.12). */
enum class OnboardingTestResult {
    /** No test was rung from step 8. */
    NotRung,

    /** Rung; its ring (and a locked pass) may still come. */
    Waiting,

    /** A test rung with the phone locked was completed after the ring was asked for. */
    Passed,

    /** Over without a locked pass: the phone was not locked (or the ring never came, was missed or was preempted). */
    NotLocked,
}

/** Step 8's rule (Story 5.12). Pure. */
object OnboardingTestAlarm {
    /** How long after its ring time a test without a locked pass still counts as waiting. */
    val RESULT_SLACK: Duration = 10.seconds

    /**
     * The result of the test that rings at [ringsAt] (null: none rung), with the last locked pass at [passedAt] (5.3's
     * `lockedTestPassedAt`), at [now]: [OnboardingTestResult.Passed] when the pass came after the ring was asked for
     * ([ringsAt] − [ScheduleTestAlarm.TEST_DELAY]); else [OnboardingTestResult.Waiting] before [ringsAt] +
     * [RESULT_SLACK]; else [OnboardingTestResult.NotLocked].
     */
    fun result(
        ringsAt: Instant?,
        passedAt: Instant?,
        now: Instant,
    ): OnboardingTestResult =
        when {
            ringsAt == null -> OnboardingTestResult.NotRung
            passedAt != null && passedAt >= ringsAt - ScheduleTestAlarm.TEST_DELAY -> OnboardingTestResult.Passed
            now < ringsAt + RESULT_SLACK -> OnboardingTestResult.Waiting
            else -> OnboardingTestResult.NotLocked
        }
}

/**
 * Home's note after "Skip for now" on step 8 (Story 5.12): "Ring a test alarm with your phone locked to check it works."
 * shows while the note is due and no locked test has passed ([shown]); "Dismiss" ends it ([dismiss]). A failing read
 * fails [shown] (the caller logs it and shows no note).
 */
class OnboardingTestNote(
    private val repository: OnboardingRepository,
    private val records: ReliabilityRecords,
    private val sessionLock: SessionLockGuard,
) {
    fun shown(): Flow<Boolean> =
        combine(
            repository.observe().map { it.testNoteDue },
            records.observe().map { it.lockedTestPassedAt != null },
        ) { due, passed -> due && !passed }
            .distinctUntilChanged()

    /** "Dismiss": the note is no longer due. Inside [SessionLockGuard.whenIdle]. */
    suspend fun dismiss(): Outcome<Unit, DomainError> = sessionLock.whenIdle { repository.setTestNoteDue(false) }
}
