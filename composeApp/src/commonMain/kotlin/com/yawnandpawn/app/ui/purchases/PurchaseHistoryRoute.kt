package com.yawnandpawn.app.ui.purchases

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yawnandpawn.app.ui.format.is24HourClock
import org.koin.compose.viewmodel.koinViewModel

/**
 * The Purchase history route (Story 4.16): [PurchaseHistoryViewModel] (scoped to the nav entry) feeding
 * [PurchaseHistoryScreen]. [onBack] leaves it; its "Problem with a charge?" row calls [onProblemWithCharge] (Story 4.17).
 */
@Composable
fun PurchaseHistoryRoute(
    onBack: () -> Unit,
    onProblemWithCharge: () -> Unit,
    viewModel: PurchaseHistoryViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PurchaseHistoryScreen(
        state = state,
        is24Hour = is24HourClock(),
        onBack = onBack,
        onProblemWithCharge = onProblemWithCharge,
        onIntent = viewModel::onIntent,
        showProblemWithCharge = true,
    )
}
