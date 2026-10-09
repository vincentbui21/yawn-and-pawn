package com.yawnandpawn.app.debug.preview

import com.yawnandpawn.app.ui.progress.ProgressIntent
import com.yawnandpawn.app.ui.progress.ProgressUiState
import com.yawnandpawn.app.ui.reliability.ChecklistItem
import com.yawnandpawn.app.ui.reliability.ItemStatus
import com.yawnandpawn.app.ui.reliability.ReliabilityUiState
import com.yawnandpawn.app.ui.settings.SettingsIntent
import com.yawnandpawn.app.ui.settings.SettingsPane
import com.yawnandpawn.app.ui.settings.SettingsUiState
import com.yawnandpawn.app.ui.settings.WeakeningNote
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
            if (state.canLowerFee) state.withPreviewFee(state.baseFeeTier - 1).copy(baseFeeNote = WEAKENING) else state
        }

        // Raising is never held by the lock: it applies at once and drops a waiting lower fee.
        SettingsIntent.RaiseBaseFee -> {
            if (state.canRaiseFee) state.withPreviewFee(state.baseFeeTier + 1).copy(baseFeeNote = null) else state
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
        is SettingsIntent.MaxSnoozesChanged -> state.copy(maxSnoozes = intent.value).withPreviewFee(state.baseFeeTier)
        is SettingsIntent.SnoozeLengthSelected -> state.copy(defaultSnoozeMinutes = intent.minutes)
        is SettingsIntent.QuietTimeChanged -> state.copy(defaultQuietSeconds = intent.seconds)
        is SettingsIntent.VibrateDuringQuietTimeToggled -> state.copy(vibrateDuringQuietTime = intent.on)
        is SettingsIntent.BrightWakeScreenToggled -> state.copy(brightWakeScreen = intent.on)
        is SettingsIntent.ThemeSelected -> state.copy(theme = intent.mode)
        is SettingsIntent.WeeklySummaryToggled -> state.copy(weeklySummary = intent.on)
        is SettingsIntent.UsageStatsToggled -> state.copy(usageStats = intent.on)
        else -> state
    }

private val WEAKENING: WeakeningNote? get() = PreviewProgressSamples.settingsBaseFeeWeakening.baseFeeNote

/** "Fix" returns from the system setting with the item OK; the manufacturer steps open first. */
internal fun ReliabilityUiState.fixed(item: ChecklistItem): ReliabilityUiState =
    if (item == ChecklistItem.Manufacturer && !showManufacturerSteps) {
        copy(showManufacturerSteps = true)
    } else {
        copy(rows = rows.map { if (it.item == item) it.copy(status = ItemStatus.Ok) else it }, showManufacturerSteps = false)
    }

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
