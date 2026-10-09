package com.yawnandpawn.app.android.billing

import android.app.Activity
import com.android.billingclient.api.BillingClient.BillingResponseCode

/**
 * A programmable [BillingClientFacade] (Story 4.12): Robolectric tests drive `AndroidBilling` through it and never reach
 * Play. Every callback answers at once on the caller's thread, unless its `hold…` flag is set (Play never answers).
 *
 * The default answers like a phone without the Play Store (`BILLING_UNAVAILABLE` on setup); [available] answers OK.
 */
class FakeBillingClientFacade(
    available: Boolean = false,
) : BillingClientFacade {
    /** The setup answer; null never answers (a hung connection). */
    var setupCode: Int? = if (available) BillingResponseCode.OK else BillingResponseCode.BILLING_UNAVAILABLE

    var startConnections = 0
        private set

    /** Setup answers used before [setupCode], first to last (for example a `DEVELOPER_ERROR` while Play reconnects). */
    val nextSetupCodes = ArrayDeque<Int>()

    /** Play drops the service right after each OK setup (a flapping Play Store). */
    var dropAfterConnect = false

    /** Called at each `startConnection` (tests record the time). */
    var onStartConnection: () -> Unit = {}

    var endConnections = 0
        private set

    private var connected = false
    private var onDisconnectedCallback: () -> Unit = {}
    private var listener: (PlayResult, List<PlayPurchase>?) -> Unit = { _, _ -> }

    /** The products Play knows, by id. */
    val products = mutableMapOf<String, PlayProduct>()
    var productCode: Int = BillingResponseCode.OK
    var reportedUnfetched: Set<String> = emptySet()
    var holdProducts = false
    val productQueries = mutableListOf<List<String>>()

    val owned = mutableListOf<PlayPurchase>()
    var purchasesCode: Int = BillingResponseCode.OK
    var holdPurchases = false
    var purchaseQueries = 0
        private set

    var consumeCode: Int = BillingResponseCode.OK
    var holdConsume = false
    val consumed = mutableListOf<String>()

    /** `launchBillingFlow`'s answer; a non-OK answer is also sent to the listener, as Play does (the echo). */
    var launchCode: Int = BillingResponseCode.OK
    var echoLaunchErrors = true
    var throwOnLaunch: Throwable? = null
    val launches = mutableListOf<Launch>()

    var countryCode: String? = "US"

    /** One `launchBillingFlow` call, with the thread it ran on. */
    data class Launch(
        val activity: Activity,
        val productId: String,
        val accountId: String,
        val profileId: String,
        val thread: String,
    )

    override val isReady: Boolean
        get() = connected

    override fun setPurchasesListener(listener: (PlayResult, List<PlayPurchase>?) -> Unit) {
        this.listener = listener
    }

    override fun startConnection(
        onSetup: (PlayResult) -> Unit,
        onDisconnected: () -> Unit,
    ) {
        startConnections++
        onStartConnection()
        onDisconnectedCallback = onDisconnected
        val code = nextSetupCodes.removeFirstOrNull() ?: setupCode
        if (code == null) {
            heldSetup = onSetup
            return
        }
        connected = code == BillingResponseCode.OK
        onSetup(PlayResult(code))
        if (connected && dropAfterConnect) disconnect()
    }

    override fun endConnection() {
        endConnections++
        heldSetup = null
        connected = false
    }

    private var heldSetup: ((PlayResult) -> Unit)? = null

    /** Play finally answers a setup it held ([setupCode] was null), with [code]. */
    fun answerSetup(code: Int) {
        val setup = heldSetup ?: return
        heldSetup = null
        connected = code == BillingResponseCode.OK
        setup(PlayResult(code))
    }

    /** Play drops the service. */
    fun disconnect() {
        connected = false
        onDisconnectedCallback()
    }

    /** Play calls `onPurchasesUpdated`. */
    fun deliver(
        code: Int,
        purchases: List<PlayPurchase>? = null,
    ) = listener(PlayResult(code), purchases)

    override fun queryProducts(
        productIds: List<String>,
        onResult: (ProductQuery) -> Unit,
    ) {
        productQueries += productIds
        if (holdProducts) return
        val found = productIds.mapNotNull { products[it] }
        onResult(ProductQuery(PlayResult(productCode), found, reportedUnfetched.intersect(productIds.toSet())))
    }

    override fun queryPurchases(onResult: (PurchasesQuery) -> Unit) {
        purchaseQueries++
        if (holdPurchases) return
        onResult(PurchasesQuery(PlayResult(purchasesCode), owned.toList()))
    }

    override fun consumeAsync(
        token: String,
        onResult: (PlayResult) -> Unit,
    ) {
        consumed += token
        if (holdConsume) return
        onResult(PlayResult(consumeCode))
    }

    override fun launchBillingFlow(
        activity: Activity,
        product: PlayProduct,
        accountId: String,
        profileId: String,
    ): PlayResult {
        launches += Launch(activity, product.productId, accountId, profileId, Thread.currentThread().name)
        throwOnLaunch?.let { throw it }
        val result = PlayResult(launchCode)
        if (launchCode != BillingResponseCode.OK && echoLaunchErrors) listener(result, null)
        return result
    }

    override fun queryBillingCountry(onResult: (PlayResult, String?) -> Unit) {
        onResult(PlayResult(BillingResponseCode.OK), countryCode)
    }

    companion object {
        /** A product priced [micros] in [currency], shown as [formatted]. */
        fun product(
            productId: String,
            formatted: String = "$1.00",
            micros: Long = 1_000_000,
            currency: String = "USD",
        ): PlayProduct = PlayProduct(productId, formatted, micros, currency, offerToken = "offer-$productId")

        /** A purchase of [productId] by install [accountId] for session [profileId]. */
        fun purchase(
            productId: String,
            token: String = "token-$productId",
            stateCode: Int = PlayStateCodes.PURCHASED,
            accountId: String? = "install-1",
            profileId: String? = "session-1",
            orderId: String? = "GPA.1",
            timeMillis: Long = 1_800_000_000_000,
        ): PlayPurchase = PlayPurchase(token, listOf(productId), stateCode, accountId, profileId, orderId, timeMillis)
    }
}
