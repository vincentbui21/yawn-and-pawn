package com.yawnandpawn.app.debug.preview

import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.ui.progress.ProgressIntent
import com.yawnandpawn.app.ui.progress.ProgressUiState
import com.yawnandpawn.app.ui.reliability.ChecklistItem
import com.yawnandpawn.app.ui.reliability.ItemStatus
import com.yawnandpawn.app.ui.reliability.ReliabilityUiState
import com.yawnandpawn.app.ui.settings.SettingsIntent
import com.yawnandpawn.app.ui.settings.SettingsPane
import com.yawnandpawn.app.ui.settings.SettingsUiState
import com.yawnandpawn.app.ui.you.YouIntent
import com.yawnandpawn.app.ui.you.YouUiState

/** Progress in the tap-through: days select (a day shows its chip), the months switch between September and August. */
internal fun reduceProgress(
    state: ProgressUiState,
    intent: ProgressIntent,
): ProgressUiState =
    when (intent) {
        is ProgressIntent.DaySelected -> state.copy(selection = intent.selection)
        ProgressIntent.PreviousMonth -> state.copy(calendar = PreviewProgressSamples.august, selection = null)
        ProgressIntent.NextMonth -> state.copy(calendar = PreviewProgressSamples.september, selection = null)
        else -> state
    }

/**
 * Settings in the tap-through: every control responds, nothing is stored. Lowering the base fee is a weakening change
 * under the commitment lock, so it shows "Saved. Takes effect after tomorrow's 7:30 alarm." (F6).
 */
internal fun reduceSettings(
    state: SettingsUiState,
    intent: SettingsIntent,
): SettingsUiState =
    when (intent) {
        is SettingsIntent.OpenPane -> {
            state.copy(pane = intent.pane)
        }

        SettingsIntent.Back -> {
            state.copy(pane = SettingsPane.Main)
        }

        SettingsIntent.LowerBaseFee -> {
            state.withFee(state.lowerFee).copy(weakening = WEAKENING)
        }

        SettingsIntent.RaiseBaseFee -> {
            state.withFee(state.higherFee)
        }

        else -> {
            reduceSettingValues(state, intent)
        }
    }

/** The values the sub-screens and switches set. */
private fun reduceSettingValues(
    state: SettingsUiState,
    intent: SettingsIntent,
): SettingsUiState =
    when (intent) {
        is SettingsIntent.MaxSnoozesChanged -> state.copy(maxSnoozes = intent.value)
        is SettingsIntent.SnoozeLengthSelected -> state.copy(defaultSnoozeMinutes = intent.minutes)
        is SettingsIntent.QuietTimeChanged -> state.copy(defaultQuietSeconds = intent.seconds)
        is SettingsIntent.VibrateDuringQuietTimeToggled -> state.copy(vibrateDuringQuietTime = intent.on)
        is SettingsIntent.BrightWakeScreenToggled -> state.copy(brightWakeScreen = intent.on)
        is SettingsIntent.ThemeSelected -> state.copy(theme = intent.mode)
        is SettingsIntent.WeeklySummaryToggled -> state.copy(weeklySummary = intent.on)
        is SettingsIntent.UsageStatsToggled -> state.copy(usageStats = intent.on)
        else -> state
    }

private val WEAKENING = PreviewProgressSamples.settingsBaseFeeWeakening.weakening

/** One price tier down or up: the fee steps in whole multiples of the preview base fee. */
private fun SettingsUiState.withFee(fee: Money?): SettingsUiState {
    if (fee == null) return this
    val unit = PreviewSamples.price(1)
    val multiple = (fee.micros / unit.micros).toInt()
    return copy(
        baseFee = fee,
        lowerFee = if (multiple > 1) PreviewSamples.price(multiple - 1) else null,
        higherFee = if (multiple < MAX_FEE_MULTIPLE) PreviewSamples.price(multiple + 1) else null,
    )
}

/** "Fix" returns from the system setting with the item OK; the manufacturer steps open first. */
internal fun ReliabilityUiState.fixed(item: ChecklistItem): ReliabilityUiState =
    if (item == ChecklistItem.Manufacturer && !showManufacturerSteps) {
        copy(showManufacturerSteps = true)
    } else {
        copy(rows = rows.map { if (it.item == item) it.copy(status = ItemStatus.Ok) else it }, showManufacturerSteps = false)
    }

/** The preview's base fee goes up to 5 tiers. */
private const val MAX_FEE_MULTIPLE = 5

/** The You tab in the tap-through: links show what a phone without a browser sees, "Delete all data" asks first. */
internal fun reduceYou(
    state: YouUiState,
    intent: YouIntent,
): YouUiState =
    when (intent) {
        YouIntent.PrivacyClicked, YouIntent.TermsClicked, YouIntent.SupportClicked, YouIntent.AboutClicked -> {
            state.copy(
                noBrowser = !state.noBrowser,
            )
        }

        YouIntent.DeleteAllClicked -> {
            state.copy(showDeleteDialog = true)
        }

        YouIntent.DeleteConfirmed, YouIntent.DeleteCancelled -> {
            state.copy(showDeleteDialog = false)
        }

        else -> {
            state
        }
    }
