package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.checks.CheckAnswer
import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.CheckMode
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.checks.CheckResult
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.Difficulty

/** The next snooze on sale: Play product [productId] (`snooze_usd_NN`, AD-7) for snooze [snoozeNumber] of the session. */
data class SnoozeOffer(
    val productId: String,
    val snoozeNumber: Int,
)

/** Why snooze is not offered (AD-7, FR-RNG-7); the wake screen shows the reason on the disabled button. */
enum class UnavailableReason {
    TestMode,
    Offline,
    BeforeFirstUnlock,
    CatalogueNotLoaded,
    MaxSnoozesReached,
    PriceCapReached,
    PaymentPending,
    EarlierPaymentRefunding,

    /**
     * The session's frozen base fee or snooze number is outside the fee rules (`DomainError.InvalidFee`, Story 4.2):
     * a damaged config, never a user choice. Logged; the wake screen shows it like prices not loaded.
     */
    InvalidFee,
}

/** Whether the user can buy a snooze right now (AD-7). */
sealed interface SnoozeAvailability {
    data class Available(
        val offer: SnoozeOffer,
    ) : SnoozeAvailability

    data class Unavailable(
        val reason: UnavailableReason,
    ) : SnoozeAvailability
}

/** The only source of [SnoozeAvailability] (AD-7). Pure. */
fun interface SnoozeAvailabilityPolicy {
    fun availability(session: SessionData): SnoozeAvailability
}

/**
 * The result of checking an answer against the current item of a [CheckRun] (AD-9), in terms of the AD-2 rows: "valid,
 * not last step" ([ValidNextItem], [ValidNext]), "valid, last step" ([ValidLast]) and "invalid" ([Invalid],
 * [InvalidRestart]).
 */
enum class StepResult {
    /** Wrong: one more failed attempt, the same item again. */
    Invalid,

    /** Wrong, and the entry's puzzle starts over with a new seed (`CheckResult.WrongRestart`). */
    InvalidRestart,

    /** Valid, and more items of the current entry follow. */
    ValidNextItem,

    /** Valid, the current entry is done, and more entries follow. */
    ValidNext,

    /** Valid, and it was the last item of the last entry. */
    ValidLast,
}

/** Checks an answer for the current item of [CheckRun] (AD-9). Pure and deterministic per seed. */
fun interface CheckValidator {
    fun validate(
        run: CheckRun,
        answer: CheckAnswer,
    ): StepResult
}

/** Whether the fallback check (FR-PWK-11) may replace the plan. */
sealed interface FallbackDecision {
    data class Allowed(
        val plan: CheckPlan,
    ) : FallbackDecision

    data object NotAllowed : FallbackDecision
}

/** Why the Fallback check picker was offered (FR-PWK-11, Story 3.9). */
enum class FallbackReason {
    /** The camera or its permission cannot be used (missing permission, camera error, no frame within 5 s). */
    CameraUnavailable,

    /** The camera works, but the check failed at least [CameraFallbackPolicy.FAILED_ATTEMPTS] times. */
    FailedAttempts,
}

/**
 * The fallback the user asked for: [type] for [reason]. A class, not two parameters, so a later input (the image
 * matcher's error, Story 7.7) joins it without changing every policy.
 */
data class FallbackRequest(
    val type: CheckType,
    val reason: FallbackReason,
)

/** Decides [FallbackDecision] for the session and [request]. Pure. */
fun interface FallbackPolicy {
    fun fallback(
        session: SessionData,
        request: FallbackRequest,
    ): FallbackDecision
}

/**
 * Whether the fallback is offered now for [reason]: the policy would allow Math, which is always a fallback choice. The
 * wake screen shows "Can't do this check?" exactly then (Story 3.9).
 */
fun FallbackPolicy.offers(
    session: SessionData,
    reason: FallbackReason,
): Boolean = fallback(session, FallbackRequest(CheckType.Math, reason)) is FallbackDecision.Allowed

/**
 * The production [FallbackPolicy] (FR-PWK-11, Story 3.9). The fallback is allowed only when:
 * - the current entry is a camera check ([usesCamera]);
 * - it was not used yet this session;
 * - the chosen type is one of [CheckType.fallbackChoices] by id (never a camera check; either Memory Sequence variant);
 * - the camera is unavailable ([FallbackReason.CameraUnavailable]), or the entry failed at least [FAILED_ATTEMPTS] times.
 *
 * The fallback plan is one entry of the chosen type at Hard with twice its default count (owner-approved default
 * 2026-09-26). [usesCamera] asks whether a type is a camera check; it is the type's own flag in production, and tests
 * pass one that treats a stand-in as a camera check (`FakeCameraCheck`), until Story 3.10 adds the first camera type.
 * The type is kept as asked, so the wake screen's numbered Memory Sequence (TalkBack on) stays numbered.
 */
class CameraFallbackPolicy(
    private val usesCamera: (CheckType) -> Boolean = CheckType::usesCamera,
) : FallbackPolicy {
    override fun fallback(
        session: SessionData,
        request: FallbackRequest,
    ): FallbackDecision {
        val run = session.checkRun
        val current = run.currentEntry?.type
        val allowed =
            current != null &&
                usesCamera(current) &&
                !run.fallbackUsed &&
                CheckType.fallbackChoices.any { it.id == request.type.id } &&
                !usesCamera(request.type) &&
                (request.reason == FallbackReason.CameraUnavailable || run.failedAttempts >= FAILED_ATTEMPTS)
        return if (allowed) FallbackDecision.Allowed(fallbackPlan(request.type)) else FallbackDecision.NotAllowed
    }

    companion object {
        /** Failed attempts on a working camera after which the fallback is offered. */
        const val FAILED_ATTEMPTS = 5

        /** One entry of [type] at Hard with twice its default count (Math 6), within the type's range. */
        fun fallbackPlan(type: CheckType): CheckPlan =
            CheckPlan(CheckMode.All, listOf(CheckEntry(type, Difficulty.Hard, (2 * type.defaultCount).coerceIn(type.countRange))))
    }
}

/**
 * The Epic 1 production [SnoozeAvailabilityPolicy]: there is no billing yet, so snooze is never offered. The reason, in
 * this order of precedence (Story 2.3; Epic 4's real policy keeps it): a test session says "Test · no charge"
 * ([UnavailableReason.TestMode]); while the user has not unlocked since boot ([userLock], read live) "Unlock your phone
 * to snooze" ([UnavailableReason.BeforeFirstUnlock]); otherwise it waits for the catalogue.
 */
class NoBillingSnoozeAvailability(
    private val userLock: UserLockState = UserLockState.Unlocked,
) : SnoozeAvailabilityPolicy {
    override fun availability(session: SessionData): SnoozeAvailability =
        SnoozeAvailability.Unavailable(
            when {
                session.config.testMode -> UnavailableReason.TestMode
                !userLock.isUserUnlocked() -> UnavailableReason.BeforeFirstUnlock
                else -> UnavailableReason.CatalogueNotLoaded
            },
        )
}

/**
 * The production [CheckValidator] (AD-9, wired since Story 3.2): the current entry's [CheckType] generates the puzzle
 * from the entry's seed and validates
 * the answer, and its [CheckResult] maps onto the AD-2 rows:
 * - [CheckResult.ItemCorrect] → [StepResult.ValidNextItem]; [CheckResult.Correct] → [StepResult.ValidNext], or
 *   [StepResult.ValidLast] on the last entry;
 * - [CheckResult.Wrong] → [StepResult.Invalid]; [CheckResult.WrongRestart] → [StepResult.InvalidRestart].
 *
 * A run with no current entry or no seed for it is [StepResult.Invalid]; it never throws. The reducer derives the seeds
 * a damaged row is missing from the session coordinates before it validates, so only a passed check gets there. An item
 * outside the entry's puzzle is checked on the nearest item ([CheckRun.withItemInPuzzle]), so the last one passes it.
 * A [CheckType.Placeholder] entry of a session stored by Epics 1–2 passes with the placeholder answer.
 */
object PluginCheckValidator : CheckValidator {
    override fun validate(
        run: CheckRun,
        answer: CheckAnswer,
    ): StepResult {
        val entry = run.currentEntry
        val seed = run.seeds.getOrNull(run.step.entry)
        return if (entry == null || seed == null) {
            StepResult.Invalid
        } else {
            // A damaged row's item past the end is checked on the last item, as the wake screen shows it (Story 3.2).
            val item = run.withItemInPuzzle().step.item
            val result = entry.type.validate(entry.puzzle(seed), item, answer)
            stepResultOf(result, lastEntry = run.step.entry >= run.plan.entries.lastIndex)
        }
    }

    /** The AD-2 row of a plugin's [result] on an entry that is the plan's last ([lastEntry]) or not. */
    internal fun stepResultOf(
        result: CheckResult,
        lastEntry: Boolean,
    ): StepResult =
        when (result) {
            CheckResult.ItemCorrect -> StepResult.ValidNextItem
            CheckResult.Correct -> if (lastEntry) StepResult.ValidLast else StepResult.ValidNext
            CheckResult.Wrong -> StepResult.Invalid
            CheckResult.WrongRestart -> StepResult.InvalidRestart
        }
}
