package com.yawnandpawn.app.core.billing

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.error.valueOrNull
import com.yawnandpawn.app.core.session.SessionData
import com.yawnandpawn.app.core.session.SnoozeOffer

/** The limits of the snooze fee (PRD §6.2, Q1–Q3). */
object FeeRules {
    /** The base fee B in whole USD tiers: $1 to $10 in $1 steps. */
    val BASE_FEE_TIERS: IntRange = 1..10

    /** Max snoozes per session: the user may lower the default 5 down to 1. */
    val MAX_SNOOZES: IntRange = 1..5

    /** The per-snooze cap: no snooze costs more than $50, the top of the catalogue. */
    const val PRICE_CAP_TIER: Int = 50
}

/** The 50 consumable snooze products on Play (AD-7, PRD §6.3): `snooze_usd_01` … `snooze_usd_50`, product NN at NN.00 USD. */
object SnoozeProducts {
    /** Every product id, cheapest first; the same list as `config/snooze-products.txt` (read by `tools/play-catalog`). */
    val all: List<String> = (1..FeeRules.PRICE_CAP_TIER).map(::idOf)

    /** The id of USD tier [tier] (1..50), zero-padded to 2 digits. */
    fun idOf(tier: Int): String = PREFIX + tier.toString().padStart(2, '0')

    private const val PREFIX = "snooze_usd_"
}

/** What the fee ladder says about one snooze. */
sealed interface FeeStep {
    /** Snooze on sale as Play product [productId], priced at [usdTier] whole USD. */
    data class Product(
        val productId: String,
        val usdTier: Int,
    ) : FeeStep

    /** The snooze would cost more than $50 ([FeeRules.PRICE_CAP_TIER]), so it is not offered; only the check remains. */
    data object PriceCapReached : FeeStep
}

/**
 * Maps the base fee tier B and the snooze number N to the Play product of that snooze (AD-7). Pure; returns
 * [DomainError.InvalidFee] instead of throwing (AD-12). The price of the next snooze of a session is always [nextStep].
 */
fun interface FeeLadder {
    fun productFor(
        baseFeeTier: Int,
        snoozeNumber: Int,
    ): Outcome<FeeStep, DomainError.InvalidFee>
}

/**
 * The production [FeeLadder] (PRD §6.2): the Nth snooze costs B × N, so it is product `snooze_usd_NN` with NN = B × N
 * when NN ≤ 50, and [FeeStep.PriceCapReached] above. B must be in [FeeRules.BASE_FEE_TIERS] and N at least 1.
 */
object UsdFeeLadder : FeeLadder {
    override fun productFor(
        baseFeeTier: Int,
        snoozeNumber: Int,
    ): Outcome<FeeStep, DomainError.InvalidFee> {
        if (baseFeeTier !in FeeRules.BASE_FEE_TIERS || snoozeNumber < 1) {
            return Outcome.Failure(DomainError.InvalidFee(baseFeeTier, snoozeNumber))
        }
        // Long, so a huge snooze number reaches the cap instead of overflowing.
        val tier = baseFeeTier.toLong() * snoozeNumber
        return Outcome.Success(stepOf(tier))
    }

    private fun stepOf(tier: Long): FeeStep =
        if (tier > FeeRules.PRICE_CAP_TIER) {
            FeeStep.PriceCapReached
        } else {
            FeeStep.Product(SnoozeProducts.idOf(tier.toInt()), tier.toInt())
        }
}

/** The ladder's answer for the session's next snooze: `productFor(config.baseFeeTier, snoozesGranted + 1)` (AD-2). */
fun FeeLadder.nextStep(session: SessionData): Outcome<FeeStep, DomainError.InvalidFee> =
    productFor(session.config.baseFeeTier, session.snoozesGranted + 1)

/** The offer for the session's next snooze, or null when the cap is reached or the frozen fee is invalid. */
fun FeeLadder.nextOffer(session: SessionData): SnoozeOffer? =
    (nextStep(session).valueOrNull() as? FeeStep.Product)?.let { SnoozeOffer(it.productId, session.snoozesGranted + 1) }
