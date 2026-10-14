package com.yawnandpawn.app.ui.payments

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.yawnandpawn.app.ui.format.is24HourClock
import org.koin.compose.viewmodel.koinViewModel

/**
 * The "Problem with a charge?" route (Story 4.17), pushed from Purchase history: [ProblemWithChargeViewModel] (scoped
 * to the nav entry) feeding [ProblemWithChargeFlow]. Back (the arrow or the system) closes the picker when it is open,
 * otherwise [onBack] leaves the screen.
 */
@Composable
fun ProblemWithChargeRoute(
    onBack: () -> Unit,
    viewModel: ProblemWithChargeViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val writer = supportEmailWriter(state.charges)
    // Only while the picker is open; otherwise system Back pops the route like any pushed screen.
    NavigationBackHandler(state = rememberNavigationEventState(NavigationEventInfo.None), isBackEnabled = state.picking) {
        viewModel.onIntent(PaymentsIntent.Back, writer)
    }
    ProblemWithChargeFlow(
        state = state,
        is24Hour = is24HourClock(),
        onIntent = { intent ->
            if (intent == PaymentsIntent.Back && !state.picking) onBack() else viewModel.onIntent(intent, writer)
        },
    )
}
