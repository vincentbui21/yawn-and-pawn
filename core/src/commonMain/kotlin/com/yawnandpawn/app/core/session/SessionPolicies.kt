package com.yawnandpawn.app.core.session

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
 * Maps `(baseFeeTier, snoozeNumber)` to the Play product of that snooze (AD-7). The real ladder is Epic 4; the price of
 * the next snooze is always [nextOffer].
 */
fun interface FeeLadder {
    fun offer(
        baseFeeTier: Int,
        snoozeNumber: Int,
    ): SnoozeOffer
}

/** The offer for the session's next snooze: `FeeLadder(config.baseFeeTier, snoozesGranted + 1)` (AD-2). */
fun FeeLadder.nextOffer(session: SessionData): SnoozeOffer = offer(session.config.baseFeeTier, session.snoozesGranted + 1)

/** The result of checking an answer against the current step (AD-9). */
enum class StepResult {
    Invalid,

    /** Valid, and more steps follow. */
    ValidNext,

    /** Valid, and it was the last step. */
    ValidLast,
}

/** Checks an answer for the current step of [CheckRun] (AD-9). Pure and deterministic per seed. */
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

/** Decides [FallbackDecision] for the session (for example after 5 failed matches or a matcher error). Pure. */
fun interface FallbackPolicy {
    fun fallback(session: SessionData): FallbackDecision
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

/** The Epic 1 production [CheckValidator]: the answer to a [CheckStep.Placeholder] step is valid; anything else is not. */
object PlaceholderCheckValidator : CheckValidator {
    override fun validate(
        run: CheckRun,
        answer: CheckAnswer,
    ): StepResult =
        when {
            run.currentStep != CheckStep.Placeholder || answer != CheckAnswer.Placeholder -> StepResult.Invalid
            run.step >= run.plan.steps.lastIndex -> StepResult.ValidLast
            else -> StepResult.ValidNext
        }
}

/** The Epic 1 production [FallbackPolicy]: no fallback check exists yet. */
object NoFallbackPolicy : FallbackPolicy {
    override fun fallback(session: SessionData): FallbackDecision = FallbackDecision.NotAllowed
}

/**
 * The Epic 1 [FeeLadder]: a simple tier mapping until the real ladder (Epic 4). Snooze n at base tier B is product
 * `snooze_usd_NN` with NN = B + n - 1, capped at the top of the catalogue.
 */
object TierFeeLadder : FeeLadder {
    const val TOP_TIER = 50

    override fun offer(
        baseFeeTier: Int,
        snoozeNumber: Int,
    ): SnoozeOffer {
        require(baseFeeTier >= 1 && snoozeNumber >= 1) { "tier and snooze number start at 1, were $baseFeeTier and $snoozeNumber" }
        val tier = (baseFeeTier + snoozeNumber - 1).coerceAtMost(TOP_TIER)
        return SnoozeOffer(productId = snoozeProductId(tier), snoozeNumber = snoozeNumber)
    }
}

/** The Play product id of price tier [tier] (1..50): `snooze_usd_01` … `snooze_usd_50` (AD-7). */
fun snoozeProductId(tier: Int): String = "snooze_usd_" + tier.toString().padStart(2, '0')
