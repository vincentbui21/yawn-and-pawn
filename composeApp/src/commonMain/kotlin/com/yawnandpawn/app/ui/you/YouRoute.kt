package com.yawnandpawn.app.ui.you

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel

/**
 * The You rows whose screens are built (Story 5.8): Money (Purchase history, How payments & refunds work), Privacy and
 * your data (Privacy policy) and Help (Support, Email support, Terms). "Delete all data" (Story 5.9) and "About" (no
 * story) stay hidden, so no row leads nowhere.
 */
val BuiltYouRows: Set<YouRow> =
    setOf(YouRow.PurchaseHistory, YouRow.Payments, YouRow.Privacy, YouRow.Support, YouRow.EmailSupport, YouRow.Terms)

/**
 * The You tab route (Story 5.8): [YouViewModel] (scoped to the nav entry) feeding [YouScreen] with [BuiltYouRows].
 * "Purchase history" calls [onOpenPurchaseHistory] and "How payments & refunds work" [onOpenPayments]; the links and
 * the support email are the ViewModel's.
 */
@Composable
fun YouRoute(
    onOpenPurchaseHistory: () -> Unit,
    onOpenPayments: () -> Unit,
    viewModel: YouViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    YouScreen(
        state = state,
        onIntent = { intent ->
            viewModel.onIntent(intent)
            when (intent) {
                YouIntent.PurchaseHistoryClicked -> onOpenPurchaseHistory()
                YouIntent.PaymentsClicked -> onOpenPayments()
                else -> Unit
            }
        },
        rows = BuiltYouRows,
    )
}
