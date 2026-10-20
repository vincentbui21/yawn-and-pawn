package com.yawnandpawn.app.ui.payments

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yawnandpawn.app.core.billing.FeeRules
import com.yawnandpawn.app.core.billing.Money
import org.koin.compose.viewmodel.koinViewModel

/**
 * The cap's price tier (Story 5.8): no single snooze is priced above the $50 tier ([FeeRules.PRICE_CAP_TIER]). The
 * tier is defined in USD, so "How payments & refunds work" names it as USD in the phone's locale ("$50.00") only while
 * Google Play's local price of that tier is not known, saying that Play sets the local price (review fix 2).
 */
val PRICE_CAP: Money = Money(FeeRules.PRICE_CAP_TIER * Money.MICROS_PER_UNIT, "USD")

/**
 * "How payments & refunds work" (Story 5.8, FR-SET-3), pushed from the You tab: the approved [PaymentsScreen] with the
 * cap from [PaymentsViewModel] (Play's cached price of the top tier, else the [PRICE_CAP] tier). The back arrow calls
 * [onBack]; "Problem with a charge?" and "Purchase history" call [onProblemWithCharge] and [onPurchaseHistory], which
 * push those screens over it.
 */
@Composable
fun PaymentsRoute(
    onBack: () -> Unit,
    onProblemWithCharge: () -> Unit,
    onPurchaseHistory: () -> Unit,
    viewModel: PaymentsViewModel = koinViewModel(),
) {
    val cap by viewModel.cap.collectAsStateWithLifecycle()
    PaymentsScreen(
        cap = cap,
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
