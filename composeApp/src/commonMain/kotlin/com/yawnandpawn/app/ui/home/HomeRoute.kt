package com.yawnandpawn.app.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yawnandpawn.app.ui.format.is24HourClock
import com.yawnandpawn.app.ui.settings.WeakeningNote
import org.koin.compose.viewmodel.koinViewModel

/**
 * The Alarms tab route: [HomeViewModel] (scoped to the nav entry) feeding [HomeScreen]. [onOpenEditor] opens the editor
 * (`null` for a new alarm); [onOpenDuplicate] opens it on a new, unsaved alarm prefilled from the alarm it gets.
 * [openFailed] is the editor's result when its alarm could not be read: Home shows "Couldn't open this alarm." and
 * calls [onOpenFailedShown]. [savedUnderLock] is its result after a save that left a weakening change waiting under the
 * commitment lock: Home shows "Saved. Takes effect after ..." and calls [onSavedUnderLockShown] (Story 4.6).
 * [onOpenQrRegistration] opens QR registration of an alarm's code ("Re-register", Story 3.10;
 * the editor by default). [onOpenReliability] opens the reliability checklist from the banner's "Fix" (Story 5.4). The
 * countdown is recomputed and the reliability checklist evaluated again (once) whenever Home resumes, which also ends
 * every start, so the banner clears itself once the blocking rows are fixed.
 */
@Composable
fun HomeRoute(
    onOpenEditor: (String?) -> Unit,
    onOpenDuplicate: (String) -> Unit,
    openFailed: Boolean,
    onOpenFailedShown: () -> Unit,
    onOpenQrRegistration: (String) -> Unit = onOpenEditor,
    savedUnderLock: WeakeningNote? = null,
    onSavedUnderLockShown: () -> Unit = {},
    onOpenReliability: () -> Unit = {},
    viewModel: HomeViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is HomeEffect.OpenEditor -> onOpenEditor(effect.alarmId)
                is HomeEffect.OpenDuplicate -> onOpenDuplicate(effect.sourceId)
                is HomeEffect.OpenQrRegistration -> onOpenQrRegistration(effect.alarmId)
                HomeEffect.OpenReliability -> onOpenReliability()
            }
        }
    }
    LaunchedEffect(openFailed) {
        if (openFailed) {
            viewModel.onIntent(HomeIntent.EditorOpenFailed)
            onOpenFailedShown()
        }
    }
    LaunchedEffect(savedUnderLock) {
        savedUnderLock?.let {
            viewModel.onIntent(HomeIntent.SavedUnderLock(it))
            onSavedUnderLockShown()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onIntent(HomeIntent.Resumed) }
    HomeScreen(state = state, is24Hour = is24HourClock(), onIntent = viewModel::onIntent)
}
