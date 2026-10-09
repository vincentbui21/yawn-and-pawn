package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.billing.BillingCountry
import com.yawnandpawn.app.core.billing.DisplayPrices
import com.yawnandpawn.app.core.billing.LivePrice
import com.yawnandpawn.app.core.billing.LivePriceSource
import com.yawnandpawn.app.core.billing.Money
import kotlinx.coroutines.CompletableDeferred

/** The USD price of snooze product `snooze_usd_NN`: NN.00 USD. */
fun usdPriceOf(productId: String): Money = Money.of(productId.takeLast(2).toInt(), "USD")

/**
 * Play's live prices (Story 4.13): each product at [usdPriceOf] unless [prices] says otherwise (a null entry: Play
 * knows no price). [asked] lists every product asked, in order. With [hold] set, each call waits for [release].
 */
class FakeLivePriceSource(
    val prices: MutableMap<String, LivePrice?> = mutableMapOf(),
) : LivePriceSource {
    val asked: MutableList<String> = mutableListOf()
    var hold: Boolean = false
    private var gate = CompletableDeferred<Unit>()

    override suspend fun livePrice(productId: String): LivePrice? {
        asked += productId
        if (hold) gate.await()
        return if (productId in prices) prices[productId] else usdLivePrice(productId)
    }

    /** Lets every held call go on. */
    fun release() {
        gate.complete(Unit)
        gate = CompletableDeferred()
    }
}

/** Play's live USD price of [productId] at [usdPriceOf] ("$NN.00"). */
fun usdLivePrice(productId: String): LivePrice {
    val price = usdPriceOf(productId)
    return LivePrice(productId, price, "$" + price.micros / Money.MICROS_PER_UNIT + ".00")
}

/** Cached display prices: [prices], or [usdPriceOf] for every product when [all] is set. */
class FakeDisplayPrices(
    val prices: MutableMap<String, Money> = mutableMapOf(),
    var all: Boolean = true,
) : DisplayPrices {
    override fun priceOf(productId: String): Money? = prices[productId] ?: if (all) usdPriceOf(productId) else null
}

/** The Play billing country (Story 4.13's tax note). */
class FakeBillingCountry(
    var country: String? = null,
) : BillingCountry {
    override fun countryCode(): String? = country
}
