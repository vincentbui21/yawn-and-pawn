package com.yawnandpawn.app.android.billing

import android.app.Activity

/**
 * A Play Billing answer (`BillingResult`): Play's response [code] (`BillingClient.BillingResponseCode`) and its debug
 * text, for logs only.
 */
data class PlayResult(
    val code: Int,
    val debugMessage: String = "",
)

/**
 * One Play `Purchase`, as plain values (Story 4.12), so tests and the debug fake never build Play's own objects. Only
 * `PlayPurchaseMapping.kt` turns it into the core `PurchaseSnapshot` and reads [stateCode] (AD-7 scan). [token] is
 * hidden in [toString].
 */
class PlayPurchase(
    val token: String,
    val products: List<String>,
    val stateCode: Int,
    val accountId: String?,
    val profileId: String?,
    val orderId: String?,
    val purchaseTimeMillis: Long,
) {
    override fun toString(): String = "PlayPurchase(token=redacted, products=$products, orderId=$orderId)"
}

/**
 * One product Play returned (`ProductDetails`) with the price of its first one-time purchase offer: [formattedPrice],
 * [priceMicros] and [currency] (null when the product has no one-time offer). [offerToken] selects that offer at
 * launch. [handle] is Play's own `ProductDetails`, needed to launch; only the real facade reads it.
 */
class PlayProduct(
    val productId: String,
    val formattedPrice: String?,
    val priceMicros: Long?,
    val currency: String?,
    val offerToken: String? = null,
    val handle: Any? = null,
) {
    override fun toString(): String = "PlayProduct($productId, $formattedPrice)"
}

/** A `queryProductDetailsAsync` answer: the [products] Play had and the ids it reported as [unfetched]. */
class ProductQuery(
    val result: PlayResult,
    val products: List<PlayProduct> = emptyList(),
    val unfetched: Set<String> = emptySet(),
)

/** A `queryPurchasesAsync` (INAPP) answer. */
class PurchasesQuery(
    val result: PlayResult,
    val purchases: List<PlayPurchase> = emptyList(),
)

/**
 * The thin seam over Play's `BillingClient` (Story 4.12): one call per Play call, callbacks as Play gives them, no
 * logic. `AndroidBilling` owns the connection, the time limits, the thread model and the mapping, and Robolectric tests
 * drive it through a fake facade instead of Play. The real one is [PlayBillingClientFacade].
 *
 * Callbacks may come on any thread (Play uses the main thread) and must be called at most once; a facade may also never
 * call one (Play hung), so every caller bounds its wait.
 */
interface BillingClientFacade {
    /** Whether the client is connected now (`isReady`). */
    val isReady: Boolean

    /** Sets the one `PurchasesUpdatedListener`: every `onPurchasesUpdated`, solicited or not. Set before [startConnection]. */
    fun setPurchasesListener(listener: (PlayResult, List<PlayPurchase>?) -> Unit)

    /**
     * `startConnection`: [onSetup] gets `onBillingSetupFinished`, [onDisconnected] `onBillingServiceDisconnected`. Builds the
     * client on first use (pending purchases for one-time products and automatic service reconnection enabled).
     */
    fun startConnection(
        onSetup: (PlayResult) -> Unit,
        onDisconnected: () -> Unit,
    )

    /** `queryProductDetailsAsync` for the INAPP products [productIds]. */
    fun queryProducts(
        productIds: List<String>,
        onResult: (ProductQuery) -> Unit,
    )

    /** `queryPurchasesAsync` (INAPP): purchased and pending, not consumed. */
    fun queryPurchases(onResult: (PurchasesQuery) -> Unit)

    /** `consumeAsync` of [token]. The name keeps it apart from the core `Billing.consume` (the ledger scan). */
    fun consumeAsync(
        token: String,
        onResult: (PlayResult) -> Unit,
    )

    /**
     * `launchBillingFlow` from [activity] for [product], with `obfuscatedAccountId` = [accountId] and
     * `obfuscatedProfileId` = [profileId]. Main thread only. Returns Play's immediate answer; the purchase itself arrives
     * on the purchases listener.
     */
    fun launchBillingFlow(
        activity: Activity,
        product: PlayProduct,
        accountId: String,
        profileId: String,
    ): PlayResult

    /** `getBillingConfigAsync`: the Play billing country (ISO 3166-1 alpha-2), or null with the failure. */
    fun queryBillingCountry(onResult: (PlayResult, String?) -> Unit)
}
