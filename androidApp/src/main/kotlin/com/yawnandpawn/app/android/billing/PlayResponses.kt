package com.yawnandpawn.app.android.billing

import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.yawnandpawn.app.core.billing.LaunchResult
import com.yawnandpawn.app.core.billing.PurchaseSnapshot
import com.yawnandpawn.app.core.billing.PurchaseUpdate
import com.yawnandpawn.app.core.session.ConsumeResult
import com.yawnandpawn.app.core.session.PurchaseFailureKind

/**
 * Play's response codes to domain results, in one table (Story 4.12, unit-tested row by row):
 *
 * | Code | Launch / update | Consume |
 * |---|---|---|
 * | OK | `Launched` / `Purchases` (pending ones included: the reconciler never grants them) | `Consumed` |
 * | USER_CANCELED | `Cancelled` | `Failed` |
 * | ITEM_ALREADY_OWNED | `ItemAlreadyOwned` | `Failed` |
 * | NETWORK_ERROR, SERVICE_UNAVAILABLE, SERVICE_DISCONNECTED | `Failed(Offline)` | `Failed` (retried) |
 * | ITEM_NOT_OWNED | `Failed(Error)` | `NotOwned` (never `Consumed`: the ledger decides by age, Story 4.10) |
 * | BILLING_UNAVAILABLE (a declined card too, S1), ERROR, DEVELOPER_ERROR | `Failed(Error)` | `Failed` |
 * | ITEM_UNAVAILABLE, FEATURE_NOT_SUPPORTED | `Failed(Error)` | `Failed` |
 * | any other code | `Failed(Error)` | `Failed` |
 */
object PlayResponses {
    /** Codes that mean Play could not be reached now: trying again later may help. */
    private val OFFLINE =
        setOf(BillingResponseCode.NETWORK_ERROR, BillingResponseCode.SERVICE_UNAVAILABLE, BillingResponseCode.SERVICE_DISCONNECTED)

    /** The failure kind of a code that is not a result of its own (anything but OK, USER_CANCELED, ITEM_ALREADY_OWNED). */
    fun failureKind(code: Int): PurchaseFailureKind = if (code in OFFLINE) PurchaseFailureKind.Offline else PurchaseFailureKind.Error

    /** Whether a failed query with [code] is worth retrying later (offline, Play busy). */
    fun isTransient(code: Int): Boolean = code in OFFLINE || code == BillingResponseCode.ERROR

    /** What `launchBillingFlow`'s own answer means. */
    fun launchResult(code: Int): LaunchResult =
        when (code) {
            BillingResponseCode.OK -> LaunchResult.Launched
            BillingResponseCode.USER_CANCELED -> LaunchResult.Cancelled
            BillingResponseCode.ITEM_ALREADY_OWNED -> LaunchResult.ItemAlreadyOwned
            else -> LaunchResult.Failed(failureKind(code))
        }

    /** What one `onPurchasesUpdated` means; [purchases] are its mapped snapshots (used only for OK). */
    fun update(
        code: Int,
        purchases: List<PurchaseSnapshot>,
    ): PurchaseUpdate =
        when (code) {
            BillingResponseCode.OK -> PurchaseUpdate.Purchases(purchases)
            BillingResponseCode.USER_CANCELED -> PurchaseUpdate.Cancelled
            BillingResponseCode.ITEM_ALREADY_OWNED -> PurchaseUpdate.ItemAlreadyOwned
            else -> PurchaseUpdate.Failed(failureKind(code))
        }

    /** What `consumeAsync`'s answer means. The cause names the code only, never the token. */
    fun consumeResult(code: Int): ConsumeResult =
        when (code) {
            BillingResponseCode.OK -> ConsumeResult.Consumed
            BillingResponseCode.ITEM_NOT_OWNED -> ConsumeResult.NotOwned
            else -> ConsumeResult.Failed(name(code))
        }

    /** The code's name, for logs. */
    @Suppress("CyclomaticComplexMethod")
    fun name(code: Int): String =
        when (code) {
            BillingResponseCode.OK -> "OK"
            BillingResponseCode.USER_CANCELED -> "USER_CANCELED"
            BillingResponseCode.SERVICE_UNAVAILABLE -> "SERVICE_UNAVAILABLE"
            BillingResponseCode.BILLING_UNAVAILABLE -> "BILLING_UNAVAILABLE"
            BillingResponseCode.ITEM_UNAVAILABLE -> "ITEM_UNAVAILABLE"
            BillingResponseCode.DEVELOPER_ERROR -> "DEVELOPER_ERROR"
            BillingResponseCode.ERROR -> "ERROR"
            BillingResponseCode.ITEM_ALREADY_OWNED -> "ITEM_ALREADY_OWNED"
            BillingResponseCode.ITEM_NOT_OWNED -> "ITEM_NOT_OWNED"
            BillingResponseCode.NETWORK_ERROR -> "NETWORK_ERROR"
            BillingResponseCode.SERVICE_DISCONNECTED -> "SERVICE_DISCONNECTED"
            BillingResponseCode.FEATURE_NOT_SUPPORTED -> "FEATURE_NOT_SUPPORTED"
            else -> "CODE_$code"
        }
}
