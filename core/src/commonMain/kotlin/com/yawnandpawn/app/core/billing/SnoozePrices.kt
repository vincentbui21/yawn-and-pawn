package com.yawnandpawn.app.core.billing

import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.SessionData
import com.yawnandpawn.app.core.session.SnoozeOffer

/**
 * Play's LIVE price of a snooze product, read from its `ProductDetails` (Story 4.13; 4.3 review handoff). The confirm
 * sheet asks when it opens and again at the "Pay" tap, and `PayConfirmed` carries only this price. Null when Play
 * cannot give one now (offline, the product not loaded): billing cannot launch then, so nothing is sent. Story 4.12
 * binds the Play adapter; until then [None] knows no price.
 */
fun interface LivePriceSource {
    suspend fun livePrice(productId: String): LivePrice?

    companion object {
        /** No billing yet: no live price, so the sheet never sends a Pay. */
        val None: LivePriceSource = LivePriceSource { null }
    }
}

/**
 * The cached display price of a snooze product (Story 4.3's price cache), for the snooze button and the sheet before
 * the live price arrives. Display only: it can be stale, so it never reaches a `PurchaseIntent`. Story 4.3/4.7 bind the
 * price cache; until then [None] knows no price ("Prices not loaded yet").
 */
fun interface DisplayPrices {
    fun priceOf(productId: String): Money?

    companion object {
        val None: DisplayPrices = DisplayPrices { null }
    }
}

/**
 * The product of the snooze after [offer] in [session], for the confirm sheet's "The next one costs {nextPrice}."
 * (Story 4.13): snooze `offer.snoozeNumber + 1` on the frozen base fee. Null when there is no next snooze to price: it
 * would pass the frozen max snoozes or the $50 cap, or the fee is invalid. The sheet then says only "This one costs
 * {price}." Pure.
 */
fun FeeLadder.followingProduct(
    session: SessionData,
    offer: SnoozeOffer,
): String? {
    val next = offer.snoozeNumber + 1
    if (next > session.config.maxSnoozes) return null
    val step = (productFor(session.config.baseFeeTier, next) as? Outcome.Success)?.value
    return (step as? FeeStep.Product)?.productId
}

/**
 * The tax note on the confirm sheet (owner-approved default 2026-09-26): "Google Play shows the final total, including
 * any tax." where Play prices exclude tax, decided by the Play billing country.
 */
object TaxNote {
    /**
     * Countries (ISO 3166-1 alpha-2) whose Play prices exclude tax; the same list as `config/tax-exclusive-countries.txt`
     * (a test keeps them equal).
     */
    val TAX_EXCLUSIVE_COUNTRIES: Set<String> = setOf("US", "CA")

    /** Whether the sheet shows the note for the Play billing [countryCode] (null while unknown: no note). */
    fun shows(countryCode: String?): Boolean = countryCode?.trim()?.uppercase() in TAX_EXCLUSIVE_COUNTRIES
}

/**
 * The Play billing country (`BillingClient.getBillingConfigAsync`, cached by the Play adapter in Story 4.12), for
 * [TaxNote]. Null while unknown. Until 4.12, [None] knows none, so no note shows.
 */
fun interface BillingCountry {
    fun countryCode(): String?

    companion object {
        val None: BillingCountry = BillingCountry { null }
    }
}
