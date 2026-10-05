// SPIKE S1 (branch spike/s1-billing-lockscreen only, never merged to main). Throwaway prototype code.
package com.yawnandpawn.app.android.spike

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AccountIdentifiers
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/**
 * The spike's Play Billing client (Play Billing Library 9.1.0): one `BillingClient` per process with pending purchases
 * enabled for one-time products and automatic service reconnection, the one consumable [PRODUCT_ID], and every
 * callback logged through [SpikeS1Log]. Purchase tokens are logged by their last 6 characters only.
 */
@Suppress("TooManyFunctions") // Spike: one function per button plus its logging helpers.
object SpikeS1Billing {
    const val PRODUCT_ID = "spike_s1_test"
    private const val TOKEN_TAIL = 6

    private var client: BillingClient? = null

    /** Product details of [PRODUCT_ID] once loaded. */
    @Volatile
    var product: ProductDetails? = null
        private set

    @Volatile
    var productStatus: String = "not queried"
        private set

    @Volatile
    var lastResult: String = "none"
        private set

    /** Pay → first onPurchasesUpdated, in ms, one per completed Pay (shown as median / max). */
    val durations: MutableList<Long> = mutableListOf()

    /** The Pay waiting for its first purchase result; null when none is in flight. */
    @Volatile
    private var awaiting: String? = null

    val connection: String
        get() =
            when (client?.connectionState) {
                null -> "no client"
                BillingClient.ConnectionState.DISCONNECTED -> "DISCONNECTED"
                BillingClient.ConnectionState.CONNECTING -> "CONNECTING"
                BillingClient.ConnectionState.CONNECTED -> "CONNECTED"
                BillingClient.ConnectionState.CLOSED -> "CLOSED"
                else -> "state ${client?.connectionState}"
            }

    private val purchasesUpdated =
        PurchasesUpdatedListener { result, purchases ->
            val since = SpikeS1Log.sincePay()
            val pay = awaiting
            SpikeS1Log.log(
                "onPurchasesUpdated ${describe(result)} subResponse=${result.onPurchasesUpdatedSubResponseCode} " +
                    "purchases=${purchases?.size ?: "null"} forPay=${pay ?: "none (a later update, e.g. pending completed)"}",
            )
            purchases?.forEach { logPurchase("  update", it) }
            val states = purchases?.joinToString { stateName(it.purchaseState) } ?: "-"
            lastResult = "${codeName(result.responseCode)} [$states] after ${since ?: "?"} ms"
            if (pay != null && since != null) {
                durations += since
                SpikeS1Log.log("RESULT pay=$pay code=${codeName(result.responseCode)} states=[$states] payToResultMs=$since")
            }
            awaiting = null
            SpikeS1Sound.purchaseInFlight = false
            SpikeS1Sound.kickMonitor()
        }

    /** Creates the client once and connects it; later calls reconnect when it is not connected. */
    fun connect(context: Context) {
        val existing = client
        if (existing != null && existing.isReady) {
            SpikeS1Log.log("billing: already CONNECTED")
            return
        }
        val c =
            existing ?: BillingClient
                .newBuilder(context.applicationContext)
                .setListener(purchasesUpdated)
                .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                .enableAutoServiceReconnection()
                .build()
                .also { client = it }
        SpikeS1Log.log("billing: startConnection (state before: $connection)")
        c.startConnection(
            object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    SpikeS1Log.log("billing: onBillingSetupFinished ${describe(result)}")
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) queryProduct(null)
                }

                override fun onBillingServiceDisconnected() {
                    SpikeS1Log.log("billing: onBillingServiceDisconnected")
                }
            },
        )
    }

    /** Queries [PRODUCT_ID]; [then] runs with the loaded details (or null on failure). */
    fun queryProduct(then: ((ProductDetails?) -> Unit)?) {
        val c = client ?: return SpikeS1Log.log("billing: queryProduct with no client").also { then?.invoke(null) }
        val params =
            QueryProductDetailsParams
                .newBuilder()
                .setProductList(
                    listOf(
                        QueryProductDetailsParams.Product
                            .newBuilder()
                            .setProductId(PRODUCT_ID)
                            .setProductType(BillingClient.ProductType.INAPP)
                            .build(),
                    ),
                ).build()
        SpikeS1Log.log("billing: queryProductDetailsAsync $PRODUCT_ID")
        c.queryProductDetailsAsync(params) { result, details ->
            val found = details.productDetailsList.firstOrNull { it.productId == PRODUCT_ID }
            val unfetched = details.unfetchedProductList.joinToString { "${it.productId}(status ${it.statusCode})" }
            SpikeS1Log.log(
                "billing: onProductDetailsResponse ${describe(result)} loaded=${details.productDetailsList.size} " +
                    "unfetched=[$unfetched]",
            )
            if (found != null) {
                product = found
                val offers = found.oneTimePurchaseOfferDetailsList.orEmpty()
                val price = offers.firstOrNull()?.formattedPrice ?: found.oneTimePurchaseOfferDetails?.formattedPrice
                productStatus = "loaded: ${found.title} $price (${offers.size} offer(s))"
                offers.forEach {
                    SpikeS1Log.log(
                        "  offer id=${it.offerId} option=${it.purchaseOptionId} ${it.formattedPrice} " +
                            "${it.priceAmountMicros} ${it.priceCurrencyCode} token…${it.offerToken?.takeLast(TOKEN_TAIL)}",
                    )
                }
            } else {
                productStatus = "NOT loaded: ${codeName(result.responseCode)} unfetched=[$unfetched]"
            }
            then?.invoke(found)
        }
    }

    /**
     * Launches the Play purchase sheet for [PRODUCT_ID] with [profileId] as `obfuscatedProfileId` (a fresh UUID per Pay)
     * and [accountId] as `obfuscatedAccountId` (one random id per install, as AD-7's installId). Loads the product
     * first when it is not loaded yet (offline, it fails here and the failure is the result of this Pay).
     */
    fun launch(
        activity: Activity,
        pay: String,
        profileId: String,
        accountId: String,
    ) {
        awaiting = pay
        SpikeS1Sound.purchaseInFlight = true
        SpikeS1Sound.kickMonitor()
        val details = product
        if (details == null) {
            SpikeS1Log.log("billing: product not loaded (connection=$connection), querying before launch")
            queryProduct { loaded ->
                if (loaded == null) {
                    finishWithoutSheet(pay, "no product details")
                } else {
                    activity.runOnUiThread { launchLoaded(activity, loaded, pay, profileId, accountId) }
                }
            }
            return
        }
        launchLoaded(activity, details, pay, profileId, accountId)
    }

    private fun launchLoaded(
        activity: Activity,
        details: ProductDetails,
        pay: String,
        profileId: String,
        accountId: String,
    ) {
        val c = client ?: return finishWithoutSheet(pay, "no client")
        val productParams =
            BillingFlowParams.ProductDetailsParams
                .newBuilder()
                .setProductDetails(details)
                .apply {
                    details.oneTimePurchaseOfferDetailsList
                        ?.firstOrNull()
                        ?.offerToken
                        ?.let { setOfferToken(it) }
                }.build()
        val params =
            BillingFlowParams
                .newBuilder()
                .setProductDetailsParamsList(listOf(productParams))
                .setObfuscatedProfileId(profileId)
                .setObfuscatedAccountId(accountId)
                .build()
        SpikeS1Log.log("billing: launchBillingFlow pay=$pay obfuscatedProfileId=$profileId obfuscatedAccountId=$accountId")
        val result = c.launchBillingFlow(activity, params)
        SpikeS1Log.log("billing: launchBillingFlow returned ${describe(result)}")
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            finishWithoutSheet(pay, "launchBillingFlow ${codeName(result.responseCode)}")
        }
    }

    private fun finishWithoutSheet(
        pay: String,
        why: String,
    ) {
        val since = SpikeS1Log.sincePay()
        lastResult = "NO SHEET: $why after ${since ?: "?"} ms"
        SpikeS1Log.log("RESULT pay=$pay NO SHEET ($why) payToResultMs=$since")
        awaiting = null
        SpikeS1Sound.purchaseInFlight = false
        SpikeS1Sound.kickMonitor()
    }

    /** queryPurchasesAsync(INAPP): logs every owned purchase; [then] gets them (empty on failure). */
    fun queryPurchases(then: ((List<Purchase>) -> Unit)? = null) {
        val c = client ?: return SpikeS1Log.log("billing: queryPurchases with no client")
        SpikeS1Log.log("billing: queryPurchasesAsync INAPP")
        c.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()) {
            result,
            purchases,
            ->
            SpikeS1Log.log("billing: onQueryPurchasesResponse ${describe(result)} count=${purchases.size}")
            purchases.forEach { logPurchase("  owned", it) }
            then?.invoke(purchases)
        }
    }

    /** Consumes every owned PURCHASED item (a PENDING one cannot be consumed yet and is only logged). */
    fun consumeAll() {
        queryPurchases { purchases ->
            val c = client ?: return@queryPurchases
            if (purchases.isEmpty()) SpikeS1Log.log("billing: nothing to consume")
            purchases.forEach { p ->
                val tail = p.purchaseToken.takeLast(TOKEN_TAIL)
                if (p.purchaseState != Purchase.PurchaseState.PURCHASED) {
                    SpikeS1Log.log("billing: skip consume token…$tail state=${stateName(p.purchaseState)}")
                    return@forEach
                }
                SpikeS1Log.log("billing: consumeAsync token…$tail")
                c.consumeAsync(ConsumeParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) { result, token ->
                    SpikeS1Log.log("billing: onConsumeResponse ${describe(result)} token…${token.takeLast(TOKEN_TAIL)}")
                }
            }
        }
    }

    private fun logPurchase(
        prefix: String,
        p: Purchase,
    ) {
        val ids: AccountIdentifiers? = p.accountIdentifiers
        SpikeS1Log.log(
            "$prefix state=${stateName(p.purchaseState)} products=${p.products} orderId=${p.orderId} " +
                "obfuscatedProfileId=${ids?.obfuscatedProfileId ?: "MISSING"} " +
                "obfuscatedAccountId=${ids?.obfuscatedAccountId ?: "MISSING"} acknowledged=${p.isAcknowledged} " +
                "quantity=${p.quantity} purchaseTime=${p.purchaseTime} token…${p.purchaseToken.takeLast(TOKEN_TAIL)}",
        )
    }

    private fun describe(result: BillingResult): String =
        "code=${codeName(result.responseCode)}(${result.responseCode}) debug='${result.debugMessage}'"

    fun codeName(code: Int): String =
        when (code) {
            BillingClient.BillingResponseCode.OK -> "OK"
            BillingClient.BillingResponseCode.USER_CANCELED -> "USER_CANCELED"
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE -> "SERVICE_UNAVAILABLE"
            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> "BILLING_UNAVAILABLE"
            BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> "ITEM_UNAVAILABLE"
            BillingClient.BillingResponseCode.DEVELOPER_ERROR -> "DEVELOPER_ERROR"
            BillingClient.BillingResponseCode.ERROR -> "ERROR"
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> "ITEM_ALREADY_OWNED"
            BillingClient.BillingResponseCode.ITEM_NOT_OWNED -> "ITEM_NOT_OWNED"
            BillingClient.BillingResponseCode.NETWORK_ERROR -> "NETWORK_ERROR"
            BillingClient.BillingResponseCode.SERVICE_DISCONNECTED -> "SERVICE_DISCONNECTED"
            BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED -> "FEATURE_NOT_SUPPORTED"
            else -> "CODE_$code"
        }

    private fun stateName(state: Int): String =
        when (state) {
            Purchase.PurchaseState.PURCHASED -> "PURCHASED"
            Purchase.PurchaseState.PENDING -> "PENDING"
            Purchase.PurchaseState.UNSPECIFIED_STATE -> "UNSPECIFIED"
            else -> "STATE_$state"
        }
}
