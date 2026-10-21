package com.yawnandpawn.app.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.yawnandpawn.app.ui.components.PpsBackground
import org.koin.compose.viewmodel.koinViewModel

/**
 * The onboarding route (Story 5.10): [OnboardingViewModel] (scoped to the nav entry, so a second onboarding after
 * "Delete all data" starts fresh) feeding the approved [OnboardingScreen]. Until the stored step is read it shows the
 * gradient only, so step 1 never flashes before a restored step. System Back from step 2 goes one step back; on step 1
 * it is left to the system, which leaves the app.
 */
@Composable
fun OnboardingRoute(
    is24Hour: Boolean,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val shown = state
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = shown != null && shown.step != OnboardingStep.Mission,
    ) {
        viewModel.onIntent(OnboardingIntent.Back)
    }
    if (shown == null) {
        PpsBackground(modifier = modifier)
    } else {
        OnboardingScreen(state = shown, is24Hour = is24Hour, onIntent = viewModel::onIntent, modifier = modifier)
    }
}
