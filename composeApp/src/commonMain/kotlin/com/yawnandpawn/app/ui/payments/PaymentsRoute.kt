package com.yawnandpawn.app.ui.payments

import androidx.compose.runtime.Composable
import com.yawnandpawn.app.core.billing.FeeRules
import com.yawnandpawn.app.core.billing.Money

/**
 * The cap "How payments & refunds work" states (Story 5.8): no single snooze costs more than $50
 * ([FeeRules.PRICE_CAP_TIER]). The cap is defined in USD tiers, so it shows as USD in the phone's locale ("$50.00");
 * the fee paragraph above it says Google Play shows local prices.
 */
val PRICE_CAP: Money = Money(FeeRules.PRICE_CAP_TIER * Money.MICROS_PER_UNIT, "USD")

/**
 * "How payments & refunds work" (Story 5.8, FR-SET-3), pushed from the You tab: the approved [PaymentsScreen] with
 * [PRICE_CAP]. The back arrow calls [onBack]; "Problem with a charge?" and "Purchase history" call
 * [onProblemWithCharge] and [onPurchaseHistory], which push those screens over it.
 */
@Composable
fun PaymentsRoute(
    onBack: () -> Unit,
    onProblemWithCharge: () -> Unit,
    onPurchaseHistory: () -> Unit,
) {
    PaymentsScreen(
        priceCap = PRICE_CAP,
        onIntent = { intent ->
            when (intent) {
                PaymentsIntent.Back -> onBack()
                PaymentsIntent.ProblemWithChargeClicked -> onProblemWithCharge()
                PaymentsIntent.PurchaseHistoryClicked -> onPurchaseHistory()
                else -> Unit
            }
        },
    )
}
