package com.yawnandpawn.app.ui.daydetail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yawnandpawn.app.ui.format.is24HourClock
import kotlinx.datetime.LocalDate
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * The Day detail route (Story 6.7): [DayDetailViewModel] for [date] (scoped to the nav entry, keyed by the date so two
 * dates never share one) feeding [DayDetailScreen]. [onBack] leaves it; a date with nothing logged (its data was deleted
 * while the route was saved) leaves it too, once.
 */
@Composable
fun DayDetailRoute(
    date: LocalDate,
    onBack: () -> Unit,
    viewModel: DayDetailViewModel = koinViewModel(key = date.toString()) { parametersOf(date) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val leave by rememberUpdatedState(onBack)
    LaunchedEffect(state.nothingLogged) { if (state.nothingLogged) leave() }
    DayDetailScreen(
        state = state,
        is24Hour = is24HourClock(),
        onBack = onBack,
        onIntent = viewModel::onIntent,
    )
}
