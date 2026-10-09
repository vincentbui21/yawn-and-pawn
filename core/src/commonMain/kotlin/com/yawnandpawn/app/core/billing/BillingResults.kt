package com.yawnandpawn.app.core.billing

import com.yawnandpawn.app.core.error.valueOrNull
import com.yawnandpawn.app.core.session.PurchaseFailureKind
import com.yawnandpawn.app.core.session.SessionData

/**
 * What `Billing.launch` answered (Story 4.11). Play's `launchBillingFlow` only opens the sheet: the purchase itself
 * arrives later on `Billing.purchaseUpdates`, so [Launched] is not a payment.
 */
sealed interface LaunchResult {
    /** The purchase sheet is open; its result arrives as a [PurchaseUpdate]. */
    data object Launched : LaunchResult

    /** Play still owns an unconsumed (or pending) purchase of this product (`ITEM_ALREADY_OWNED`). */
    data object ItemAlreadyOwned : LaunchResult

    /** The user closed the sheet (`USER_CANCELED`). */
    data object Cancelled : LaunchResult

    /** The sheet could not open, for [kind]. */
    data class Failed(
        val kind: PurchaseFailureKind,
    ) : LaunchResult
}

/** One `onPurchasesUpdated` call (Story 4.11), solicited by a launch or not. */
sealed interface PurchaseUpdate {
    /**
     * Play reported [purchases] (`OK`), purchased or pending; each one goes through `PurchaseReconciler`. Not a data
     * class: the snapshots hide their tokens, and nothing compares updates.
     */
    class Purchases(
        val purchases: List<PurchaseSnapshot>,
    ) : PurchaseUpdate {
        override fun toString(): String = "Purchases(${purchases.size})"
    }

    /** The open sheet was closed by the user (`USER_CANCELED`). */
    data object Cancelled : PurchaseUpdate

    /** The open sheet answered `ITEM_ALREADY_OWNED`. */
    data object ItemAlreadyOwned : PurchaseUpdate

    /** The open sheet failed for [kind]. */
    data class Failed(
        val kind: PurchaseFailureKind,
    ) : PurchaseUpdate
}

/**
 * The product the next snooze of [session] costs, as `PurchaseReconciler` expects it (`ActiveSessionSummary`): null
 * once max snoozes are granted, over the price cap or for an invalid frozen fee, so no payment can grant then.
 */
fun FeeLadder.expectedNextProduct(session: SessionData): String? =
    if (session.snoozesGranted >= session.config.maxSnoozes) {
        null
    } else {
        (nextStep(session).valueOrNull() as? FeeStep.Product)?.productId
    }
