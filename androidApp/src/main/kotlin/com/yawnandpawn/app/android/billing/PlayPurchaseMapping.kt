package com.yawnandpawn.app.android.billing

import com.android.billingclient.api.Purchase
import com.yawnandpawn.app.core.billing.PlayPurchaseState
import com.yawnandpawn.app.core.billing.PurchaseSnapshot
import com.yawnandpawn.app.core.session.PurchaseToken
import kotlin.time.Instant

/*
 * Play's purchases to the core PurchaseSnapshot (Story 4.12, AD-7). Besides PurchaseReconciler, this is the one file
 * allowed to name a purchase's state (PurchaseStateReaderScanTest exempts exactly this path): it only maps the state,
 * the reconciler alone decides what a state means.
 */

/** Play's `Purchase` as plain values (the state as Play's code). */
fun Purchase.toPlayPurchase(): PlayPurchase =
    PlayPurchase(
        token = purchaseToken,
        products = products,
        stateCode = purchaseState,
        accountId = accountIdentifiers?.obfuscatedAccountId,
        profileId = accountIdentifiers?.obfuscatedProfileId,
        orderId = orderId,
        purchaseTimeMillis = purchaseTime,
    )

/**
 * The snapshots of this purchase, one per product (a snooze purchase has one). `obfuscatedAccountId` becomes
 * [PurchaseSnapshot.accountId] (the install id, AD-7) and `obfuscatedProfileId` [PurchaseSnapshot.profileId] (the
 * session id). A purchase in `UNSPECIFIED_STATE` (or a state this library does not know) gives none: it is dropped,
 * never guessed. A blank order id (a pending purchase) is null.
 */
fun PlayPurchase.toSnapshots(): List<PurchaseSnapshot> {
    val state = playStateOf(stateCode) ?: return emptyList()
    return products.map { productId ->
        PurchaseSnapshot(
            token = PurchaseToken(token),
            productId = productId,
            purchaseState = state,
            profileId = profileId?.takeIf { it.isNotBlank() },
            accountId = accountId?.takeIf { it.isNotBlank() },
            orderId = orderId?.takeIf { it.isNotBlank() },
            purchaseTime = Instant.fromEpochMilliseconds(purchaseTimeMillis),
        )
    }
}

/** Every snapshot of [purchases], in order; dropped purchases leave nothing. */
fun snapshotsOf(purchases: List<PlayPurchase>): List<PurchaseSnapshot> = purchases.flatMap { it.toSnapshots() }

/** Play's purchase state code to the core state; null for `UNSPECIFIED_STATE` and any unknown code. */
fun playStateOf(code: Int): PlayPurchaseState? =
    when (code) {
        Purchase.PurchaseState.PURCHASED -> PlayPurchaseState.Purchased
        Purchase.PurchaseState.PENDING -> PlayPurchaseState.Pending
        else -> null
    }

/** Play's state codes, for the debug fake and tests that build a [PlayPurchase] (they never read one). */
object PlayStateCodes {
    const val PURCHASED: Int = Purchase.PurchaseState.PURCHASED
    const val PENDING: Int = Purchase.PurchaseState.PENDING
    const val UNSPECIFIED: Int = Purchase.PurchaseState.UNSPECIFIED_STATE
}
