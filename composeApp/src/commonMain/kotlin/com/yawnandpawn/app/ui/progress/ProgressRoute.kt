package com.yawnandpawn.app.ui.progress

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.datetime.LocalDate
import org.koin.compose.viewmodel.koinViewModel

/**
 * The Progress tab (Story 6.5): [ProgressScreen] over [ProgressViewModel]. Every intent goes to the ViewModel; the chip or
 * a second tap on a day also calls [onOpenDay] (Day detail, Story 6.7), and the "Purchase history" link
 * [onOpenPurchaseHistory].
 */
@Composable
fun ProgressRoute(
    onOpenDay: (LocalDate) -> Unit,
    onOpenPurchaseHistory: () -> Unit,
    viewModel: ProgressViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ProgressScreen(
        state = state,
        onIntent = { intent ->
            viewModel.onIntent(intent)
            when (intent) {
                is ProgressIntent.DayTapped -> onOpenDay(intent.date)
                ProgressIntent.PurchaseHistoryClicked -> onOpenPurchaseHistory()
                else -> Unit
            }
        },
    )
}
