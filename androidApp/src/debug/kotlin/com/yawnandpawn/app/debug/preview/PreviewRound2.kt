package com.yawnandpawn.app.debug.preview

import androidx.compose.runtime.Composable
import com.yawnandpawn.app.ui.daydetail.DayDetailScreen
import com.yawnandpawn.app.ui.daydetail.DayDetailUiState
import com.yawnandpawn.app.ui.payments.PaymentsScreen
import com.yawnandpawn.app.ui.payments.ProblemWithChargeScreen
import com.yawnandpawn.app.ui.progress.ProgressScreen
import com.yawnandpawn.app.ui.progress.ProgressUiState
import com.yawnandpawn.app.ui.purchases.PurchaseHistoryScreen
import com.yawnandpawn.app.ui.purchases.PurchaseHistoryUiState
import com.yawnandpawn.app.ui.reliability.ReliabilityScreen
import com.yawnandpawn.app.ui.reliability.ReliabilityUiState
import com.yawnandpawn.app.ui.settings.SettingsPane
import com.yawnandpawn.app.ui.settings.SettingsScreen
import com.yawnandpawn.app.ui.settings.SettingsUiState
import com.yawnandpawn.app.ui.shell.AppShell
import com.yawnandpawn.app.ui.shell.AppTab
import com.yawnandpawn.app.ui.you.YouScreen
import com.yawnandpawn.app.ui.you.YouUiState

/**
 * Design preview round 2, progress and settings: Progress (full, a tapped week, empty), Day detail (snoozed, fallback,
 * two sessions, missed, test, skipped), Purchase history (and empty), Settings with its sub-screens and states, the
 * Reliability checklist and Payments & refunds. Tall scrolling screens get a tall screenshot window.
 */
object PreviewRound2 {
    private val s = PreviewProgressSamples

    private fun item(
        id: String,
        group: String,
        title: String,
        primary: Boolean = false,
        tall: Boolean = true,
        hasDialog: Boolean = false,
        render: @Composable (is24Hour: Boolean) -> Unit,
    ) = PreviewItem(id, 2, group, title, primary = primary, hasDialog = hasDialog, tall = tall, render = render)

    private fun progress(
        id: String,
        title: String,
        state: ProgressUiState,
        primary: Boolean = false,
    ) = item(id, "Progress", title, primary = primary) {
        AppShell(selected = AppTab.Progress, onSelect = {}) { ProgressScreen(state = state, onIntent = {}) }
    }

    private fun day(
        id: String,
        title: String,
        state: DayDetailUiState,
        primary: Boolean = false,
    ) = item(id, "Day detail", title, primary = primary) { is24 -> DayDetailScreen(state = state, is24Hour = is24, onBack = {}) }

    private fun purchases(
        id: String,
        title: String,
        state: PurchaseHistoryUiState,
        primary: Boolean = false,
    ) = item(id, "Purchase history", title, primary = primary) { is24 ->
        PurchaseHistoryScreen(state = state, is24Hour = is24, onBack = {}, onProblemWithCharge = {})
    }

    /**
     * A Settings item. Its [sample] is read when the item is shown, not when the catalogue is built: the samples format
     * their prices with the phone's `MoneyFormatter`, which needs the running app (and its current locale).
     */
    private fun settings(
        id: String,
        title: String,
        sample: () -> SettingsUiState,
        group: String = "Settings",
        primary: Boolean = false,
        hasDialog: Boolean = false,
    ) = item(id, group, title, primary = primary, hasDialog = hasDialog) { is24 ->
        val state = sample()
        if (state.pane == SettingsPane.Main) {
            AppShell(selected = AppTab.Settings, onSelect = {}, showNavBar = !state.sessionInProgress) {
                SettingsScreen(state = state, is24Hour = is24, onIntent = {})
            }
        } else {
            SettingsScreen(state = state, is24Hour = is24, onIntent = {})
        }
    }

    private fun you(
        id: String,
        title: String,
        state: YouUiState,
        primary: Boolean = false,
        hasDialog: Boolean = false,
    ) = item(id, "You", title, primary = primary, hasDialog = hasDialog) {
        AppShell(selected = AppTab.You, onSelect = {}) { YouScreen(state = state, onIntent = {}) }
    }

    private fun reliability(
        id: String,
        title: String,
        state: ReliabilityUiState,
        primary: Boolean = false,
    ) = item(id, "Reliability checklist", title, primary = primary) { ReliabilityScreen(state = state, onIntent = {}) }

    val items: List<PreviewItem> =
        listOf(
            progress(
                "progress_full",
                "Ring of 30 mornings, tiles, week chart, streak, calendar, money, insight",
                s.progress,
                primary = true,
            ),
            progress("progress_dot_chip", "A ring dot tapped: its label chip", s.progressDotChip, primary = true),
            progress("progress_calendar_chip", "A calendar day tapped: its label chip", s.progressCalendarChip),
            progress("progress_empty", "Empty ring", s.progressEmpty, primary = true),
            day("day_snoozed", "Snoozed twice, paid: the morning's timeline", s.daySnoozed, primary = true),
            day("day_fallback", "Fallback check, merged alarm, alarm turned off", s.dayFallback),
            day("day_before_unlock", "Before first unlock, check switched, quiet time ran out", s.dayBeforeUnlock),
            day("day_two_sessions", "Two sessions", s.dayTwoSessions),
            day("day_missed", "Missed, alarm deleted", s.dayMissed),
            day("day_test", "Test day", s.dayTest),
            day("day_skipped", "Skipped day", s.daySkipped),
            purchases("purchases_list", "Charges by month, one refunded", s.purchases, primary = true),
            purchases("purchases_empty", "Empty", s.purchasesEmpty),
            settings("settings_main", "All sections", { s.settings }, primary = true),
            settings("settings_reliability_banner", "Permission missing banner", { s.settingsReliability }),
            you("you_main", "Money, privacy and your data, help", s.you, primary = true),
            you("you_delete_dialog", "Delete all data", s.youDeleteDialog, hasDialog = true),
            you("you_no_browser", "Link without a browser", s.youNoBrowser),
            settings("settings_session", "During a session (session lock)", { s.settingsSession }),
            settings("settings_base_fee", "Base fee", { s.settingsBaseFee }, group = "Settings sub-screens", primary = true),
            settings(
                "settings_base_fee_weakening",
                "Base fee lowered under lock",
                { s.settingsBaseFeeWeakening },
                group = "Settings sub-screens",
            ),
            settings(
                "settings_base_fee_approximate",
                "Base fee, prices never loaded",
                { s.settingsBaseFeeApproximate },
                group = "Settings sub-screens",
            ),
            settings("settings_max_snoozes", "Max snoozes per session", { s.settingsMaxSnoozes }, group = "Settings sub-screens"),
            settings("settings_snooze_length", "Default snooze length", { s.settingsSnoozeLength }, group = "Settings sub-screens"),
            settings("settings_quiet_time", "Default quiet time", { s.settingsQuietTime }, group = "Settings sub-screens"),
            reliability("reliability_missing", "Items missing and revoked", s.reliabilityMissing, primary = true),
            reliability("reliability_all_ok", "All OK", s.reliabilityAllOk),
            reliability("reliability_manufacturer", "Manufacturer steps (Xiaomi)", s.reliabilityManufacturer),
            item("payments", "Payments & refunds", "How payments & refunds work", primary = true) {
                PaymentsScreen(priceCap = s.priceCap, onIntent = {})
            },
            item("payments_problem", "Payments & refunds", "Problem with a charge?") { ProblemWithChargeScreen(onIntent = {}) },
            item("payments_problem_no_browser", "Payments & refunds", "Problem with a charge?, no browser") {
                ProblemWithChargeScreen(onIntent = {}, noBrowser = true)
            },
        )
}
