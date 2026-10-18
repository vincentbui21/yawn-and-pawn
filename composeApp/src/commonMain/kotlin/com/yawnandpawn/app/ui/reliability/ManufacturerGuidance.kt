package com.yawnandpawn.app.ui.reliability

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.reliability.ConfirmMakerGuidance
import com.yawnandpawn.app.core.reliability.DeviceInfoSource
import com.yawnandpawn.app.core.reliability.DeviceMaker
import com.yawnandpawn.app.core.reliability.ManufacturerSettings
import com.yawnandpawn.app.ui.components.GroupCard
import com.yawnandpawn.app.ui.components.GroupDivider
import com.yawnandpawn.app.ui.components.PpsFilledButton
import com.yawnandpawn.app.ui.components.PpsOutlinedButton
import com.yawnandpawn.app.ui.components.SubScreen
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.editor_back
import com.yawnandpawn.app.ui.resources.reliability_done_this
import com.yawnandpawn.app.ui.resources.reliability_huawei_step_1
import com.yawnandpawn.app.ui.resources.reliability_huawei_step_2
import com.yawnandpawn.app.ui.resources.reliability_manufacturer
import com.yawnandpawn.app.ui.resources.reliability_oneplus_step_2
import com.yawnandpawn.app.ui.resources.reliability_open_settings
import com.yawnandpawn.app.ui.resources.reliability_oppo_step_2
import com.yawnandpawn.app.ui.resources.reliability_samsung_step_1
import com.yawnandpawn.app.ui.resources.reliability_samsung_step_2
import com.yawnandpawn.app.ui.resources.reliability_step_battery_usage
import com.yawnandpawn.app.ui.resources.reliability_step_recents
import com.yawnandpawn.app.ui.resources.reliability_vivo_step_1
import com.yawnandpawn.app.ui.resources.reliability_vivo_step_2
import com.yawnandpawn.app.ui.resources.reliability_xiaomi_step_1
import com.yawnandpawn.app.ui.resources.reliability_xiaomi_step_2
import com.yawnandpawn.app.ui.theme.PpsTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * The maker's guidance steps, 2 to 4, in order (EXPERIENCE.md long-form copy for Xiaomi; the others are owner drafts,
 * sourced in `docs/oem-guidance.md`); none for [DeviceMaker.Other].
 */
fun DeviceMaker.guidanceSteps(): List<StringResource> =
    when (this) {
        DeviceMaker.Xiaomi -> {
            listOf(Res.string.reliability_xiaomi_step_1, Res.string.reliability_xiaomi_step_2, Res.string.reliability_step_recents)
        }

        DeviceMaker.Samsung -> {
            listOf(Res.string.reliability_samsung_step_1, Res.string.reliability_samsung_step_2, Res.string.reliability_step_recents)
        }

        DeviceMaker.Huawei -> {
            listOf(Res.string.reliability_huawei_step_1, Res.string.reliability_huawei_step_2, Res.string.reliability_step_recents)
        }

        DeviceMaker.OppoRealme -> {
            listOf(Res.string.reliability_step_battery_usage, Res.string.reliability_oppo_step_2, Res.string.reliability_step_recents)
        }

        DeviceMaker.Vivo -> {
            listOf(Res.string.reliability_vivo_step_1, Res.string.reliability_vivo_step_2, Res.string.reliability_step_recents)
        }

        DeviceMaker.OnePlus -> {
            listOf(Res.string.reliability_step_battery_usage, Res.string.reliability_oneplus_step_2, Res.string.reliability_step_recents)
        }

        DeviceMaker.Other -> {
            emptyList()
        }
    }

/**
 * What the maker guidance renders (Story 5.5).
 *
 * @property maker the phone's maker, whose steps show.
 * @property confirming "I've done this" is being stored: further taps wait for it.
 */
data class ManufacturerGuidanceUiState(
    val maker: DeviceMaker = DeviceMaker.Other,
    val confirming: Boolean = false,
)

/** What the user can do on the maker guidance; Back is the route's own. */
sealed interface ManufacturerGuidanceIntent {
    /** "Open settings": the maker's own screen, else the app details. */
    data object OpenSettings : ManufacturerGuidanceIntent

    /** "I've done this": stores the confirmation, then the screen closes. */
    data object Done : ManufacturerGuidanceIntent
}

/** One-off results of the maker guidance. */
sealed interface ManufacturerGuidanceEffect {
    /** The confirmation is stored: the screen closes. */
    data object Close : ManufacturerGuidanceEffect
}

/**
 * The maker guidance (Story 5.5), stateless: `SubScreen` "Manufacturer settings", [maker]'s numbered steps in one
 * `GroupCard` (each step one TalkBack item with its number; no card for a maker without steps), then `button-filled`
 * "Open settings" and `button-outlined` "I've done this".
 */
@Composable
fun ManufacturerGuidanceScreen(
    maker: DeviceMaker,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SubScreen(
        title = stringResource(Res.string.reliability_manufacturer),
        backContentDescription = stringResource(Res.string.editor_back),
        onBack = onBack,
        modifier = modifier,
    ) {
        val steps = maker.guidanceSteps()
        if (steps.isNotEmpty()) {
            GroupCard {
                steps.forEachIndexed { index, step ->
                    if (index > 0) GroupDivider()
                    GuidanceStep(number = index + 1, text = stringResource(step))
                }
            }
        }
        PpsFilledButton(
            text = stringResource(Res.string.reliability_open_settings),
            onClick = onOpenSettings,
            modifier = Modifier.fillMaxWidth(),
        )
        PpsOutlinedButton(
            text = stringResource(Res.string.reliability_done_this),
            onClick = onDone,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** One numbered step: the number (`title`, `text-secondary`) and the step (`body`), read together by TalkBack. */
@Composable
private fun GuidanceStep(
    number: Int,
    text: String,
) {
    val colors = PpsTheme.colors
    val spacing = PpsTheme.spacing
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = spacing.targetMin)
                .semantics(mergeDescendants = true) { }
                .padding(horizontal = spacing.cardPadding, vertical = spacing.space3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.space3),
    ) {
        Text(
            text = number.toString(),
            modifier = Modifier.widthIn(min = spacing.space5),
            style = PpsTheme.typography.title,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        Text(text = text, style = PpsTheme.typography.body, color = colors.text)
    }
}

/**
 * The maker guidance (Story 5.5, FR-ONB-3): the phone's maker from [device]; "Open settings" asks [settings] for the
 * maker's screen; "I've done this" runs [confirm], then closes. A failed confirmation (already logged by [confirm])
 * leaves the screen open, so the user can tap again; taps while one is being stored are ignored.
 */
class ManufacturerGuidanceViewModel(
    device: DeviceInfoSource,
    private val settings: ManufacturerSettings,
    private val confirm: ConfirmMakerGuidance,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ManufacturerGuidanceUiState(maker = device.current().deviceMaker))
    val state: StateFlow<ManufacturerGuidanceUiState> = mutableState.asStateFlow()

    private val mutableEffects = Channel<ManufacturerGuidanceEffect>(Channel.BUFFERED)
    val effects: Flow<ManufacturerGuidanceEffect> = mutableEffects.receiveAsFlow()

    fun onIntent(intent: ManufacturerGuidanceIntent) {
        when (intent) {
            ManufacturerGuidanceIntent.OpenSettings -> {
                settings.open(state.value.maker)
            }

            ManufacturerGuidanceIntent.Done -> {
                if (state.value.confirming) return
                mutableState.update { it.copy(confirming = true) }
                viewModelScope.launch {
                    val confirmed = confirm()
                    mutableState.update { it.copy(confirming = false) }
                    if (confirmed is Outcome.Success) mutableEffects.send(ManufacturerGuidanceEffect.Close)
                }
            }
        }
    }
}

/**
 * The maker guidance route (Story 5.5): [ManufacturerGuidanceViewModel] (scoped to the nav entry) feeding
 * [ManufacturerGuidanceScreen]. The back arrow, and a stored "I've done this", call [onClose].
 */
@Composable
fun ManufacturerGuidanceRoute(
    onClose: () -> Unit,
    viewModel: ManufacturerGuidanceViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                ManufacturerGuidanceEffect.Close -> onClose()
            }
        }
    }
    ManufacturerGuidanceScreen(
        maker = state.maker,
        onBack = onClose,
        onOpenSettings = { viewModel.onIntent(ManufacturerGuidanceIntent.OpenSettings) },
        onDone = { viewModel.onIntent(ManufacturerGuidanceIntent.Done) },
    )
}
