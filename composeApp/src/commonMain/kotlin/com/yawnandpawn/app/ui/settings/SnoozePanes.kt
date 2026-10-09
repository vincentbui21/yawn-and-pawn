package com.yawnandpawn.app.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.yawnandpawn.app.ui.components.GroupCard
import com.yawnandpawn.app.ui.components.NoteInline
import com.yawnandpawn.app.ui.components.PpsStepper
import com.yawnandpawn.app.ui.format.formatClockTime
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.editor_fee_ladder
import com.yawnandpawn.app.ui.resources.editor_weakening_under_lock
import com.yawnandpawn.app.ui.resources.settings_base_fee
import com.yawnandpawn.app.ui.resources.settings_base_fee_lock
import com.yawnandpawn.app.ui.resources.settings_fee_ladder_1
import com.yawnandpawn.app.ui.resources.settings_fee_ladder_2
import com.yawnandpawn.app.ui.resources.settings_max_snoozes
import com.yawnandpawn.app.ui.resources.settings_prices_approximate
import com.yawnandpawn.app.ui.resources.settings_weakening_today
import com.yawnandpawn.app.ui.resources.stepper_lower
import com.yawnandpawn.app.ui.resources.stepper_raise
import com.yawnandpawn.app.ui.resources.stepper_value
import com.yawnandpawn.app.ui.theme.PpsTheme
import org.jetbrains.compose.resources.stringResource

/**
 * Base fee: the `stepper` over the price tiers, the fee ladder preview, the commitment-lock note (always), after a
 * weakening change "Saved. Takes effect after ...", and (prices never loaded) the approximate-price note (Story 4.5).
 */
@Composable
internal fun BaseFeePane(
    state: SettingsUiState,
    is24Hour: Boolean,
    onIntent: (SettingsIntent) -> Unit,
) {
    val title = stringResource(Res.string.settings_base_fee)
    val fee = state.baseFee
    val notePadding = Modifier.padding(horizontal = PpsTheme.spacing.cardPadding)
    GroupCard {
        PpsStepper(
            valueText = fee,
            valueDescription = stringResource(Res.string.stepper_value, title, fee),
            decreaseLabel = stringResource(Res.string.stepper_lower, title),
            increaseLabel = stringResource(Res.string.stepper_raise, title),
            onDecrease = { onIntent(SettingsIntent.LowerBaseFee) },
            onIncrease = { onIntent(SettingsIntent.RaiseBaseFee) },
            canDecrease = state.canLowerFee,
            canIncrease = state.canRaiseFee,
        )
    }
    feeLadderText(state.feeLadder)?.let { NoteInline(text = it, modifier = notePadding) }
    NoteInline(text = stringResource(Res.string.settings_base_fee_lock), modifier = notePadding)
    state.baseFeeNote?.let { WeakeningNoteInline(note = it, is24Hour = is24Hour, modifier = notePadding) }
    if (state.pricesApproximate) NoteInline(text = stringResource(Res.string.settings_prices_approximate), modifier = notePadding)
}

/**
 * The fee ladder preview line for [ladder] (1 to 3 prices): "Snooze 1: {price1} · 2: {price2} · 3: {price3}", or its
 * first one or two entries when max snoozes is below 3. Null for an empty ladder.
 */
@Composable
private fun feeLadderText(ladder: List<String>): String? =
    when (ladder.size) {
        0 -> null
        1 -> stringResource(Res.string.settings_fee_ladder_1, ladder[0])
        2 -> stringResource(Res.string.settings_fee_ladder_2, ladder[0], ladder[1])
        else -> stringResource(Res.string.editor_fee_ladder, ladder[0], ladder[1], ladder[2])
    }

/**
 * "Saved. Takes effect after tomorrow's {time} alarm." (or "today's"), {time} per the system 12/24 h setting. A polite
 * live region, so TalkBack also says that the step just made waits for the alarm (review fix 2).
 */
@Composable
private fun WeakeningNoteInline(
    note: WeakeningNote,
    is24Hour: Boolean,
    modifier: Modifier = Modifier,
) {
    NoteInline(
        text =
            stringResource(
                if (note.today) Res.string.settings_weakening_today else Res.string.editor_weakening_under_lock,
                formatClockTime(note.time, is24Hour),
            ),
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/**
 * Max snoozes per session: a `stepper` from 1 to 5 (the default; FR-SET-1), and after raising it under the commitment
 * lock, "Saved. Takes effect after ...".
 */
@Composable
internal fun MaxSnoozesPane(
    state: SettingsUiState,
    is24Hour: Boolean,
    onIntent: (SettingsIntent) -> Unit,
) {
    val title = stringResource(Res.string.settings_max_snoozes)
    val value = state.maxSnoozes.toString()
    GroupCard {
        PpsStepper(
            valueText = value,
            valueDescription = stringResource(Res.string.stepper_value, title, value),
            decreaseLabel = stringResource(Res.string.stepper_lower, title),
            increaseLabel = stringResource(Res.string.stepper_raise, title),
            onDecrease = { onIntent(SettingsIntent.MaxSnoozesChanged(state.maxSnoozes - 1)) },
            onIncrease = { onIntent(SettingsIntent.MaxSnoozesChanged(state.maxSnoozes + 1)) },
            canDecrease = state.maxSnoozes > SettingsUiState.MIN_MAX_SNOOZES,
            canIncrease = state.maxSnoozes < SettingsUiState.DEFAULT_MAX_SNOOZES,
        )
    }
    state.maxSnoozesNote?.let {
        WeakeningNoteInline(note = it, is24Hour = is24Hour, modifier = Modifier.padding(horizontal = PpsTheme.spacing.cardPadding))
    }
}
