package com.yawnandpawn.app.ui.settings

import com.yawnandpawn.app.core.billing.FeeRules
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import kotlinx.datetime.LocalTime

/** The Settings main screen or one of its sub-screens (progressive disclosure, owner decision 2026-09-27). */
enum class SettingsPane { Main, BaseFee, MaxSnoozes, SnoozeLength, QuietTime }

/**
 * The rows of the Settings main screen. A screen shows the rows it is given; production leaves out the rows whose
 * stories are not built yet, so no row leads nowhere (previews show them all).
 */
enum class SettingsRow {
    BaseFee,
    MaxSnoozes,
    DefaultSnoozeLength,
    DefaultQuietTime,
    VibrateDuringQuietTime,
    BrightWakeScreen,
    Appearance,
    WeeklySummary,
    UsageStats,
    Reliability,
}

/** A commitment-lock note after a weakening change: it takes effect after the next alarm at [time]. */
data class WeakeningNote(
    val time: LocalTime,
    /** The next alarm rings today ("after today's 7:30 alarm") rather than tomorrow. */
    val today: Boolean = false,
)

/** What Settings renders. */
data class SettingsUiState(
    /** The price of [baseFeeTier] as shown: Play's own `formattedPrice`, or a USD approximation ([pricesApproximate]). */
    val baseFee: String,
    /**
     * The base fee tier B the user chose (1 to 10; the pending value once a lower fee waits under the lock): snooze N
     * costs B x N (FR-SET-1).
     */
    val baseFeeTier: Int = FeeRules.BASE_FEE_TIERS.first,
    /** The fee ladder preview: the prices of snoozes 1 to min(3, [maxSnoozes]) as shown. */
    val feeLadder: List<String> = emptyList(),
    /** Prices never loaded: USD tiers with "Approximate. Your local price shows when you're online.". */
    val pricesApproximate: Boolean = false,
    val maxSnoozes: Int = DEFAULT_MAX_SNOOZES,
    val defaultSnoozeMinutes: Int = DEFAULT_SNOOZE_MINUTES,
    val defaultQuietSeconds: Int = DEFAULT_QUIET_SECONDS,
    val vibrateDuringQuietTime: Boolean = false,
    val brightWakeScreen: Boolean = true,
    val theme: PpsThemeMode = PpsThemeMode.System,
    val weeklySummary: Boolean = true,
    val usageStats: Boolean = false,
    /** A reliability checklist item fails: the non-dismissible `banner-warning`. */
    val reliabilityProblem: Boolean = false,
    /** A session is active: Settings is not reachable, only `panel-session-in-progress` shows (session lock). */
    val sessionInProgress: Boolean = false,
    val pane: SettingsPane = SettingsPane.Main,
    /** A lower base fee was saved under the commitment lock and waits for an alarm. */
    val baseFeeNote: WeakeningNote? = null,
    /** More snoozes per session were saved under the commitment lock and wait for an alarm. */
    val maxSnoozesNote: WeakeningNote? = null,
) {
    /** − is enabled above tier 1. */
    val canLowerFee: Boolean get() = baseFeeTier > FeeRules.BASE_FEE_TIERS.first

    /** + is enabled below tier 10. */
    val canRaiseFee: Boolean get() = baseFeeTier < FeeRules.BASE_FEE_TIERS.last

    companion object {
        /** 5, the default and the most (FR-SET-1). */
        val DEFAULT_MAX_SNOOZES: Int = FeeRules.MAX_SNOOZES.last
        val MIN_MAX_SNOOZES: Int = FeeRules.MAX_SNOOZES.first
        const val DEFAULT_SNOOZE_MINUTES = 9
        const val DEFAULT_QUIET_SECONDS = 20
    }
}

/** Everything the user can do in Settings. */
sealed interface SettingsIntent {
    data class OpenPane(
        val pane: SettingsPane,
    ) : SettingsIntent

    /** Back: a sub-screen returns to the main screen (the change is kept). */
    data object Back : SettingsIntent

    data object LowerBaseFee : SettingsIntent

    data object RaiseBaseFee : SettingsIntent

    data class MaxSnoozesChanged(
        val value: Int,
    ) : SettingsIntent

    data class SnoozeLengthSelected(
        val minutes: Int,
    ) : SettingsIntent

    data class QuietTimeChanged(
        val seconds: Int,
    ) : SettingsIntent

    data class VibrateDuringQuietTimeToggled(
        val on: Boolean,
    ) : SettingsIntent

    data class BrightWakeScreenToggled(
        val on: Boolean,
    ) : SettingsIntent

    data class ThemeSelected(
        val mode: PpsThemeMode,
    ) : SettingsIntent

    data class WeeklySummaryToggled(
        val on: Boolean,
    ) : SettingsIntent

    data class UsageStatsToggled(
        val on: Boolean,
    ) : SettingsIntent

    data object FixSettings : SettingsIntent

    data object ReliabilityClicked : SettingsIntent

    data object BackToAlarm : SettingsIntent
}
