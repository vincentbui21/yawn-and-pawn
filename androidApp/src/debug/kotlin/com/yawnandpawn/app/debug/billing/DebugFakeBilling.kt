package com.yawnandpawn.app.debug.billing

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.yawnandpawn.app.android.billing.BillingClientFacade
import com.yawnandpawn.app.android.billing.PlayProduct
import com.yawnandpawn.app.android.billing.PlayPurchase
import com.yawnandpawn.app.android.billing.PlayResult
import com.yawnandpawn.app.android.billing.PlayStateCodes
import com.yawnandpawn.app.android.billing.ProductQuery
import com.yawnandpawn.app.android.billing.PurchasesQuery
import java.io.File
import java.util.UUID

/**
 * The hidden developer toggle (Story 4.12, debug builds only): binds [DebugFakeBillingFacade] instead of Play for
 * emulator tests without a Play account. Off by default. Turned on or off with
 * `adb shell am broadcast -a com.yawnandpawn.app.debug.FAKE_BILLING --ez on true|false` (registered by
 * `DebugHooksProvider`), and read when the app process starts, so restart the app after the broadcast. Kept as a flag
 * file in device-protected storage, so it reads before the first unlock too.
 */
object DebugFakeBillingToggle {
    const val ACTION = "com.yawnandpawn.app.debug.FAKE_BILLING"
    const val EXTRA_ON = "on"
    private const val FLAG_FILE = "debug_fake_billing"

    private fun flag(context: Context): File = File(context.createDeviceProtectedStorageContext().filesDir, FLAG_FILE)

    fun isOn(context: Context): Boolean = runCatching { flag(context).exists() }.getOrDefault(false)

    fun set(
        context: Context,
        on: Boolean,
    ) {
        runCatching {
            val file = flag(context)
            if (on) file.writeText("on") else file.delete()
        }
    }
}

/** Receives the toggle broadcast (registered at runtime by `DebugHooksProvider`). */
class DebugFakeBillingReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action == DebugFakeBillingToggle.ACTION) {
            DebugFakeBillingToggle.set(context, intent.getBooleanExtra(DebugFakeBillingToggle.EXTRA_ON, false))
        }
    }
}

/**
 * A pretend Play for emulator tests (debug builds only): always connected; every snooze product `snooze_usd_NN` costs
 * NN.00 USD; the billing country is US; a launch "opens the sheet" and [PURCHASE_DELAY_MS] later reports the purchase,
 * PURCHASED, with the launch's account and profile ids; an owned product answers `ITEM_ALREADY_OWNED` until consumed.
 * Nothing is charged and nothing leaves the phone. Callbacks run on the main thread, as Play's do. [nowMillis] is the
 * wall clock (given by the adapter package, the only one that reads the system clocks).
 */
class DebugFakeBillingFacade(
    private val nowMillis: () -> Long,
) : BillingClientFacade {
    private val main = Handler(Looper.getMainLooper())
    private val owned = mutableListOf<PlayPurchase>()
    private var listener: (PlayResult, List<PlayPurchase>?) -> Unit = { _, _ -> }

    @Volatile
    private var connected = false

    override val isReady: Boolean
        get() = connected

    override fun setPurchasesListener(listener: (PlayResult, List<PlayPurchase>?) -> Unit) {
        this.listener = listener
    }

    override fun startConnection(
        onSetup: (PlayResult) -> Unit,
        onDisconnected: () -> Unit,
    ) {
        connected = true
        main.post { onSetup(OK) }
    }

    override fun queryProducts(
        productIds: List<String>,
        onResult: (ProductQuery) -> Unit,
    ) {
        val known = productIds.mapNotNull { id -> tierOf(id)?.let { productOf(id, it) } }
        val unfetched = productIds.filter { id -> known.none { it.productId == id } }.toSet()
        main.post { onResult(ProductQuery(OK, known, unfetched)) }
    }

    override fun queryPurchases(onResult: (PurchasesQuery) -> Unit) {
        val list = synchronized(owned) { owned.toList() }
        main.post { onResult(PurchasesQuery(OK, list)) }
    }

    override fun consumeAsync(
        token: String,
        onResult: (PlayResult) -> Unit,
    ) {
        val removed = synchronized(owned) { owned.removeAll { it.token == token } }
        main.post { onResult(if (removed) OK else PlayResult(BillingResponseCode.ITEM_NOT_OWNED)) }
    }

    override fun launchBillingFlow(
        activity: Activity,
        product: PlayProduct,
        accountId: String,
        profileId: String,
    ): PlayResult {
        if (synchronized(owned) { owned.any { product.productId in it.products } }) {
            val result = PlayResult(BillingResponseCode.ITEM_ALREADY_OWNED)
            main.post { listener(result, null) }
            return result
        }
        val purchase =
            PlayPurchase(
                token = "debug-" + UUID.randomUUID(),
                products = listOf(product.productId),
                stateCode = PlayStateCodes.PURCHASED,
                accountId = accountId,
                profileId = profileId,
                orderId = "GPA.DEBUG-" + UUID.randomUUID().toString().take(ORDER_ID_LENGTH),
                purchaseTimeMillis = nowMillis(),
            )
        main.postDelayed({
            synchronized(owned) { owned += purchase }
            listener(OK, listOf(purchase))
        }, PURCHASE_DELAY_MS)
        return OK
    }

    override fun queryBillingCountry(onResult: (PlayResult, String?) -> Unit) {
        main.post { onResult(OK, "US") }
    }

    private companion object {
        val OK = PlayResult(BillingResponseCode.OK)
        const val PURCHASE_DELAY_MS = 1_500L
        const val ORDER_ID_LENGTH = 8
        const val PREFIX = "snooze_usd_"
        const val MICROS = 1_000_000L

        fun tierOf(productId: String): Int? = productId.removePrefix(PREFIX).takeIf { productId.startsWith(PREFIX) }?.toIntOrNull()

        fun productOf(
            productId: String,
            tier: Int,
        ): PlayProduct = PlayProduct(productId, "$$tier.00", tier * MICROS, "USD")
    }
}
