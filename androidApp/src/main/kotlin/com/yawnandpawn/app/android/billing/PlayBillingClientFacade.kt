package com.yawnandpawn.app.android.billing

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.GetBillingConfigParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/**
 * The real [BillingClientFacade] over Play Billing Library 9.1 (Story 4.12). One `BillingClient` per process, built on
 * the first [startConnection] (never at app start, so nothing touches Play before the first unlock, AD-15) with:
 * - the purchases listener;
 * - pending purchases for one-time products (`enablePendingPurchases(...enableOneTimeProducts())`, PBL 8+);
 * - automatic service reconnection.
 *
 * A phone without the Play Store (an emulator image without Play, a de-Googled phone) never builds a client: every call
 * answers `BILLING_UNAVAILABLE` at once, so nothing waits for a service that can never bind.
 */
class PlayBillingClientFacade(
    context: Context,
) : BillingClientFacade {
    private val appContext = context.applicationContext

    @Volatile
    private var client: BillingClient? = null

    @Volatile
    private var listener: (PlayResult, List<PlayPurchase>?) -> Unit = { _, _ -> }

    override val isReady: Boolean
        get() = client?.isReady == true

    override fun setPurchasesListener(listener: (PlayResult, List<PlayPurchase>?) -> Unit) {
        this.listener = listener
    }

    @Synchronized
    private fun clientOrNull(): BillingClient? =
        client?.takeIf { it.connectionState != BillingClient.ConnectionState.CLOSED }
            ?: if (playStoreInstalled()) newClient().also { client = it } else null

    private fun newClient(): BillingClient =
        BillingClient
            .newBuilder(appContext)
            .setListener { result, purchases -> listener(result.toPlay(), purchases?.map { it.toPlayPurchase() }) }
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build()

    override fun startConnection(
        onSetup: (PlayResult) -> Unit,
        onDisconnected: () -> Unit,
    ) {
        val c = clientOrNull() ?: return onSetup(NO_PLAY_STORE)
        c.startConnection(
            object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) = onSetup(result.toPlay())

                override fun onBillingServiceDisconnected() = onDisconnected()
            },
        )
    }

    override fun queryProducts(
        productIds: List<String>,
        onResult: (ProductQuery) -> Unit,
    ) {
        val c = clientOrNull() ?: return onResult(ProductQuery(NO_PLAY_STORE))
        val params =
            QueryProductDetailsParams
                .newBuilder()
                .setProductList(
                    productIds.map {
                        QueryProductDetailsParams.Product
                            .newBuilder()
                            .setProductId(it)
                            .setProductType(BillingClient.ProductType.INAPP)
                            .build()
                    },
                ).build()
        c.queryProductDetailsAsync(params) { result, details ->
            onResult(
                ProductQuery(
                    result.toPlay(),
                    details.productDetailsList.map { it.toPlayProduct() },
                    details.unfetchedProductList.map { it.productId }.toSet(),
                ),
            )
        }
    }

    override fun queryPurchases(onResult: (PurchasesQuery) -> Unit) {
        val c = clientOrNull() ?: return onResult(PurchasesQuery(NO_PLAY_STORE))
        c.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()) {
            result,
            purchases,
            ->
            onResult(PurchasesQuery(result.toPlay(), purchases.map { it.toPlayPurchase() }))
        }
    }

    override fun consumeAsync(
        token: String,
        onResult: (PlayResult) -> Unit,
    ) {
        val c = clientOrNull() ?: return onResult(NO_PLAY_STORE)
        c.consumeAsync(ConsumeParams.newBuilder().setPurchaseToken(token).build()) { result, _ -> onResult(result.toPlay()) }
    }

    override fun launchBillingFlow(
        activity: Activity,
        product: PlayProduct,
        accountId: String,
        profileId: String,
    ): PlayResult {
        val c = clientOrNull()
        val details = product.handle as? ProductDetails
        return when {
            c == null -> NO_PLAY_STORE
            details == null -> PlayResult(BillingClient.BillingResponseCode.DEVELOPER_ERROR, "no details")
            else -> c.launchBillingFlow(activity, flowParams(details, product, accountId, profileId)).toPlay()
        }
    }

    private fun flowParams(
        details: ProductDetails,
        product: PlayProduct,
        accountId: String,
        profileId: String,
    ): BillingFlowParams {
        val productParams =
            BillingFlowParams.ProductDetailsParams
                .newBuilder()
                .setProductDetails(details)
                .apply { product.offerToken?.let { setOfferToken(it) } }
                .build()
        return BillingFlowParams
            .newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .setObfuscatedAccountId(accountId)
            .setObfuscatedProfileId(profileId)
            .build()
    }

    override fun queryBillingCountry(onResult: (PlayResult, String?) -> Unit) {
        val c = clientOrNull() ?: return onResult(NO_PLAY_STORE, null)
        c.getBillingConfigAsync(GetBillingConfigParams.newBuilder().build()) { result, config ->
            onResult(result.toPlay(), config?.countryCode)
        }
    }

    /** Play's billing service is there to bind (visible through the library's own `<queries>` entry). */
    private fun playStoreInstalled(): Boolean =
        appContext.packageManager
            .queryIntentServices(Intent(BILLING_SERVICE_ACTION).setPackage(PLAY_STORE_PACKAGE), 0)
            .isNotEmpty()

    private companion object {
        const val PLAY_STORE_PACKAGE = "com.android.vending"
        const val BILLING_SERVICE_ACTION = "com.android.vending.billing.InAppBillingService.BIND"
        val NO_PLAY_STORE = PlayResult(BillingClient.BillingResponseCode.BILLING_UNAVAILABLE, "no Play Store")

        fun BillingResult.toPlay(): PlayResult = PlayResult(responseCode, debugMessage)

        /** The first one-time purchase offer: the snooze products have exactly one ("buy", Story 4.1). */
        fun ProductDetails.toPlayProduct(): PlayProduct {
            val offer = oneTimePurchaseOfferDetailsList?.firstOrNull() ?: oneTimePurchaseOfferDetails
            return PlayProduct(
                productId = productId,
                formattedPrice = offer?.formattedPrice,
                priceMicros = offer?.priceAmountMicros,
                currency = offer?.priceCurrencyCode,
                offerToken = offer?.offerToken,
                handle = this,
            )
        }
    }
}
