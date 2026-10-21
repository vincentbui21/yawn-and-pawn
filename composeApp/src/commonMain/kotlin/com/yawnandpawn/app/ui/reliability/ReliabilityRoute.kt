package com.yawnandpawn.app.ui.reliability

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel

/**
 * The reliability checklist route (Story 5.4), pushed from the Settings row or the Home and Settings banner:
 * [ReliabilityViewModel] (scoped to the nav entry) feeding [ReliabilityScreen]. Every `ON_START` and `ON_RESUME` re-reads
 * the rows, so a setting fixed in the system settings (or a permission dialog answered over the screen) shows "OK" on
 * return. The back arrow calls [onBack]; "Fix" on "Manufacturer settings" calls [onOpenManufacturerGuidance].
 */
@Composable
fun ReliabilityRoute(
    onBack: () -> Unit,
    onOpenManufacturerGuidance: () -> Unit,
    viewModel: ReliabilityViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                ReliabilityEffect.OpenManufacturerGuidance -> onOpenManufacturerGuidance()
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.refresh() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    ReliabilityScreen(
        state = state,
        onIntent = { intent -> if (intent == ReliabilityIntent.Back) onBack() else viewModel.onIntent(intent) },
    )
}
