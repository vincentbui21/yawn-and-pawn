package com.yawnandpawn.app.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.yawnandpawn.app.ui.checks.CheckType
import com.yawnandpawn.app.ui.checks.displayName
import com.yawnandpawn.app.ui.components.PpsBackground
import com.yawnandpawn.app.ui.components.subScreenTransition
import com.yawnandpawn.app.ui.editor.AlarmEditorScreen
import com.yawnandpawn.app.ui.editor.EditorIntent
import com.yawnandpawn.app.ui.editor.EditorPane
import com.yawnandpawn.app.ui.editor.EditorUiState
import com.yawnandpawn.app.ui.editor.rememberLastNonNull
import com.yawnandpawn.app.ui.reliability.ManufacturerGuidanceRoute
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.check_try_it
import com.yawnandpawn.app.ui.resources.reliability_manufacturer
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * The onboarding route (Stories 5.10 and 5.11): [OnboardingViewModel] (scoped to the nav entry, so a second onboarding
 * after "Delete all data" starts fresh) feeding the approved [OnboardingScreen]. Until the stored step is read it shows
 * the gradient only, so step 1 never flashes before a restored step.
 *
 * Over step 5 the editor's Check setup, "Try it" or QR registration slides in ([OnboardingUiState.checkSetup]), over
 * step 6 the maker guidance; they are screens of this route, not nav routes, and the last one shown stays while it
 * slides out. System Back closes them through the editor (one level at a time) or closes the guidance; otherwise it goes
 * one step back, and on step 1 it is left to the system, which leaves the app. Every resume re-checks the checklist and
 * lets QR registration read the camera permission again.
 */
@Composable
fun OnboardingRoute(
    is24Hour: Boolean,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        resumed++
        viewModel.onIntent(OnboardingIntent.Resumed)
    }
    val shown = state
    val overlay = shown?.overlay() ?: Overlay.None
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = shown != null && (overlay != Overlay.None || shown.step != OnboardingStep.Mission),
    ) {
        viewModel.onIntent(
            when (overlay) {
                Overlay.CheckSetup -> OnboardingIntent.CheckSetup(EditorIntent.BackRequested)
                Overlay.Guidance -> OnboardingIntent.GuidanceClosed
                Overlay.None -> OnboardingIntent.Back
            },
        )
    }
    Box(modifier = modifier.fillMaxSize()) {
        if (shown == null) {
            PpsBackground()
        } else {
            OnboardingContent(state = shown, overlay = overlay, is24Hour = is24Hour, resumed = resumed, onIntent = viewModel::onIntent)
        }
    }
}

/**
 * The step, or what covers it, sliding in and out; the last Check setup shown stays while it slides out after Back.
 * Taps count only once the slide is over (review fix, as on the steps), and each overlay names itself to TalkBack as a
 * pane by its title.
 */
@Composable
private fun OnboardingContent(
    state: OnboardingUiState,
    overlay: Overlay,
    is24Hour: Boolean,
    resumed: Int,
    onIntent: (OnboardingIntent) -> Unit,
) {
    val setup = rememberLastNonNull(state.checkSetup)
    // The step keeps its scroll position while an overlay covers it (back from Check setup on a long, 200 % picker).
    val steps = rememberSaveableStateHolder()
    AnimatedContent(
        targetState = overlay,
        transitionSpec = { subScreenTransition(forward = targetState != Overlay.None) },
        label = "onboarding overlay",
    ) { showing ->
        val settled = transition.currentState == EnterExitState.Visible && transition.targetState == EnterExitState.Visible
        val live by rememberUpdatedState(settled)
        val guarded: (OnboardingIntent) -> Unit = { if (live) onIntent(it) }
        when (showing) {
            Overlay.None -> {
                steps.SaveableStateProvider(STEPS_KEY) { OnboardingScreen(state = state, is24Hour = is24Hour, onIntent = guarded) }
            }

            Overlay.CheckSetup -> {
                setup?.let {
                    Box(modifier = Modifier.fillMaxSize().paneTitled(it.overlayTitle())) {
                        AlarmEditorScreen(
                            state = it,
                            is24Hour = is24Hour,
                            onIntent = { intent -> guarded(OnboardingIntent.CheckSetup(intent)) },
                            resumed = resumed,
                        )
                    }
                }
            }

            Overlay.Guidance -> {
                Box(modifier = Modifier.fillMaxSize().paneTitled(stringResource(Res.string.reliability_manufacturer))) {
                    GuidanceOpening { ManufacturerGuidanceRoute(onClose = { guarded(OnboardingIntent.GuidanceClosed) }) }
                }
            }
        }
    }
}

/**
 * Gives [content] (the maker guidance) a ViewModel store of its own for this opening, cleared when it leaves (review fix):
 * a "Close" left over from an earlier opening never closes the next one.
 */
@Composable
private fun GuidanceOpening(content: @Composable () -> Unit) {
    val store = remember { ViewModelStore() }
    DisposableEffect(store) { onDispose { store.clear() } }
    val owner =
        remember(store) {
            object : ViewModelStoreOwner {
                override val viewModelStore: ViewModelStore = store
            }
        }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { content() }
}

/** The overlay's title, as its own screen shows it: the check's name, "Try it", or QR/Barcode for its registration. */
@Composable
private fun EditorUiState.overlayTitle(): String =
    when (pane) {
        EditorPane.TryIt -> stringResource(Res.string.check_try_it)
        EditorPane.ScanCode -> CheckType.QrBarcode.displayName()
        else -> setupType?.displayName().orEmpty()
    }

/** TalkBack announces the overlay by [title] when it opens (like a step's headline, Story 5.10). */
private fun Modifier.paneTitled(title: String): Modifier = semantics { paneTitle = title }

/** What covers the step: nothing, the editor's Check setup (step 5), or the maker guidance (step 6). */
private enum class Overlay { None, CheckSetup, Guidance }

private fun OnboardingUiState.overlay(): Overlay =
    when {
        checkSetup != null -> Overlay.CheckSetup
        manufacturerGuidance -> Overlay.Guidance
        else -> Overlay.None
    }

/** The saved state (scroll positions) of the steps under an overlay. */
private const val STEPS_KEY = "onboarding steps"
