package com.yawnandpawn.app.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yawnandpawn.app.ui.format.is24HourClock
import org.koin.compose.viewmodel.koinViewModel

/**
 * The Alarms tab route: [HomeViewModel] (scoped to the nav entry) feeding [HomeScreen]. [onOpenEditor] opens the editor
 * (`null` for a new alarm). [openFailed] is the editor's result when its alarm could not be read: Home shows "Couldn't
 * open this alarm." and calls [onOpenFailedShown]. The countdown is recomputed whenever Home resumes, and the
 * reliability settings are checked again whenever it starts or resumes (Story 1.19).
 */
@Composable
fun HomeRoute(
    onOpenEditor: (String?) -> Unit,
    openFailed: Boolean,
    onOpenFailedShown: () -> Unit,
    viewModel: HomeViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is HomeEffect.OpenEditor -> onOpenEditor(effect.alarmId)
            }
        }
    }
    LaunchedEffect(openFailed) {
        if (openFailed) {
            viewModel.onIntent(HomeIntent.EditorOpenFailed)
            onOpenFailedShown()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onIntent(HomeIntent.Started) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onIntent(HomeIntent.Resumed) }
    HomeScreen(state = state, is24Hour = is24HourClock(), onIntent = viewModel::onIntent)
}
