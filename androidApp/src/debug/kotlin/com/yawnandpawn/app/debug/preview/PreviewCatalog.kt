package com.yawnandpawn.app.debug.preview

import androidx.compose.runtime.Composable
import com.yawnandpawn.app.ui.editor.AlarmEditorScreen
import com.yawnandpawn.app.ui.editor.EditorUiState
import com.yawnandpawn.app.ui.home.HomeScreen
import com.yawnandpawn.app.ui.home.HomeUiState
import com.yawnandpawn.app.ui.shell.AppShell
import com.yawnandpawn.app.ui.shell.AppTab
import com.yawnandpawn.app.ui.shell.TabPlaceholder
import com.yawnandpawn.app.ui.sound.SoundPickerScreen
import com.yawnandpawn.app.ui.sound.SoundPickerUiState
import com.yawnandpawn.app.ui.wake.CheckScreen
import com.yawnandpawn.app.ui.wake.CheckUiState
import com.yawnandpawn.app.ui.wake.FallbackPickerScreen
import com.yawnandpawn.app.ui.wake.RingingScreen
import com.yawnandpawn.app.ui.wake.RingingUiState
import com.yawnandpawn.app.ui.wake.SnoozedScreen
import com.yawnandpawn.app.ui.wake.SuccessScreen
import com.yawnandpawn.app.ui.wake.SuccessUiState
import com.yawnandpawn.app.ui.wake.WakeMessage

/**
 * One state of one screen in the design preview menu. [id] names its screenshot baseline; [wake] screens are always
 * Sunrise (the Light/Dark toggle does not apply); [primary] states also get a 200% font-scale screenshot. [render]
 * draws the state with no-op intents (the tap-through entries are interactive).
 */
class PreviewItem(
    val id: String,
    val round: Int,
    val group: String,
    val title: String,
    val wake: Boolean = false,
    val primary: Boolean = false,
    /** A `dialog-confirm` is its own window, so its screenshot captures the whole screen. */
    val hasDialog: Boolean = false,
    /** Tall scrolling screens (the full editor) get a taller screenshot so nothing is cut off. */
    val tall: Boolean = false,
    val render: @Composable (is24Hour: Boolean) -> Unit,
)

/** Every state the design preview shows, grouped by round and screen (Round 1: the daily loop). */
object PreviewCatalog {
    private fun home(
        id: String,
        title: String,
        state: HomeUiState,
        primary: Boolean = false,
        hasDialog: Boolean = false,
    ) = PreviewItem(id, 1, "Alarms (Home)", title, primary = primary, hasDialog = hasDialog) { is24 ->
        AppShell(selected = AppTab.Alarms, onSelect = {}, showNavBar = !state.sessionInProgress) {
            HomeScreen(state = state, is24Hour = is24, onIntent = {})
        }
    }

    private fun editor(
        id: String,
        title: String,
        state: EditorUiState,
        primary: Boolean = false,
    ) = PreviewItem(id, 1, "Alarm editor", title, primary = primary, tall = true) { is24 ->
        AlarmEditorScreen(state = state, is24Hour = is24, onIntent = {})
    }

    private fun sound(
        id: String,
        title: String,
        state: SoundPickerUiState,
    ) = PreviewItem(id, 1, "Sound picker", title) { SoundPickerScreen(state = state, onIntent = {}) }

    private fun ringing(
        id: String,
        title: String,
        state: RingingUiState,
        group: String = "Ringing",
        primary: Boolean = false,
    ) = PreviewItem(
        id,
        1,
        group,
        title,
        wake = true,
        primary = primary,
    ) { is24 ->
        RingingScreen(state = state, is24Hour = is24, onIntent = {
        })
    }

    private fun check(
        id: String,
        title: String,
        state: CheckUiState,
        primary: Boolean = false,
    ) = PreviewItem(id, 1, "Check", title, wake = true, primary = primary) { CheckScreen(state = state, onIntent = {}) }

    private fun success(
        id: String,
        title: String,
        state: SuccessUiState,
        primary: Boolean = false,
    ) = PreviewItem(id, 1, "Success and Snoozed", title, wake = true, primary = primary) { SuccessScreen(state = state, onIntent = {}) }

    private val s = PreviewSamples

    val items: List<PreviewItem> =
        listOf(
            PreviewItem("shell_progress_placeholder", 1, "App shell", "Progress tab (placeholder until round 2)") {
                AppShell(selected = AppTab.Progress, onSelect = {}) { TabPlaceholder(AppTab.Progress) }
            },
            PreviewItem("shell_settings_placeholder", 1, "App shell", "Settings tab (placeholder until round 2)") {
                AppShell(selected = AppTab.Settings, onSelect = {}) { TabPlaceholder(AppTab.Settings) }
            },
            home("home_list", "Streak, next alarm, alarm cards", s.homeList, primary = true),
            home("home_paid", "Paid this week, rings in 45 min", s.homePaid),
            home("home_days_away", "Next alarm in 2 d 3 h", s.homeDaysAway),
            home("home_empty", "Empty", s.homeEmpty, primary = true),
            home("home_missed", "Missed note and re-register banner", s.homeMissed),
            home("home_reliability", "Permission missing banner", s.homeReliability),
            home("home_session", "Session in progress", s.homeSession, primary = true),
            home("home_disable_dialog", "Turn off under the commitment lock", s.homeDisableDialog, hasDialog = true),
            editor("editor_full_new", "New alarm (full editor)", s.editorNew, primary = true),
            editor("editor_full_edit", "Edit alarm with two checks and a message", s.editorEdit),
            editor("editor_no_check", "No check selected", s.editorNoCheck),
            editor("editor_weakening", "Weakening under lock", s.editorWeakening),
            editor("editor_sound_missing", "Custom sound missing", s.editorSoundMissing),
            editor("editor_rings_tomorrow", "One-time alarm rings tomorrow", s.editorTomorrow),
            sound("sound_picker", "Sounds", s.soundPicker),
            sound("sound_previewing", "Preview playing", s.soundPreviewing),
            ringing("ringing_first", "First ring", s.ringingFirst, primary = true),
            ringing("ringing_after_snooze", "After a snooze", s.ringingAfterSnooze),
            ringing("ringing_test", "Test alarm", s.ringingTest),
            ringing("ringing_locked", "Before first unlock (phone restarted)", s.ringingLocked, primary = true),
            ringing("ringing_offline", "Snooze unavailable: offline", s.ringingOffline),
            ringing("ringing_max_snoozes", "Snooze unavailable: max snoozes", s.ringingMaxSnoozes),
            ringing("ringing_stranded", "Earlier payment being refunded", s.ringingStranded),
            ringing("ringing_phone_call", "Paused for a call", s.ringingPhoneCall),
            ringing("sheet_confirm", "Confirm", s.sheetConfirm, group = "Snooze confirm sheet", primary = true),
            ringing("sheet_last_snooze", "Last snooze, tax note", s.sheetLastSnooze, group = "Snooze confirm sheet"),
            ringing("sheet_unlocking", "Unlock step", s.sheetUnlocking, group = "Snooze confirm sheet"),
            ringing("sheet_already_paid", "Already paid", s.sheetAlreadyPaid, group = "Snooze confirm sheet"),
            ringing(
                "payment_unlock_failed",
                "Unlock cancelled",
                s.ringingWithMessage(WakeMessage.UnlockFailed),
                group = "Payment outcomes",
            ),
            ringing(
                "payment_cancelled",
                "Payment cancelled",
                s.ringingWithMessage(WakeMessage.PaymentCancelled),
                group = "Payment outcomes",
            ),
            ringing("payment_error", "Billing error", s.ringingWithMessage(WakeMessage.PaymentError), group = "Payment outcomes"),
            ringing("payment_offline", "No connection", s.ringingWithMessage(WakeMessage.PaymentOffline), group = "Payment outcomes"),
            ringing("payment_pending", "Pending", s.ringingWithMessage(WakeMessage.PaymentPending), group = "Payment outcomes"),
            check("check_math", "Math, grace running", s.checkMath, primary = true),
            check("check_math_wrong", "Math, wrong answer, grace expired", s.checkMathWrong),
            check("check_word", "Word Unscramble", s.checkWord),
            check("check_memory_watch", "Memory Sequence, watch", s.checkMemoryWatch),
            check("check_memory_turn", "Memory Sequence, your turn (numbered)", s.checkMemoryTurn),
            check("check_qr", "QR/Barcode", s.checkQr),
            check("check_qr_wrong", "QR/Barcode, wrong code", s.checkQrWrong),
            check("check_qr_camera_unavailable", "Camera unavailable", s.checkQrCameraUnavailable),
            check("check_house_hunt", "House Hunt", s.checkHouseHunt),
            check("check_house_hunt_no_match", "House Hunt, no match yet", s.checkHouseHuntNoMatch),
            check("check_test", "Test alarm check", s.checkTest),
            check("check_sheet", "Snooze tapped during grace", s.checkSheet),
            PreviewItem("fallback_picker", 1, "Fallback check picker", "Pick a fallback check", wake = true) {
                FallbackPickerScreen(state = s.fallbackPicker, onIntent = {})
            },
            success("success_on_time", "Zero snooze, 12-day streak", s.successOnTime, primary = true),
            success("success_first", "Zero snooze, before streaks", s.successFirst),
            success("success_after_snooze", "After a snooze", s.successAfterSnooze),
            success("success_pending", "Pending payment not used", s.successPending),
            success("success_test", "Test finished", s.successTest),
            PreviewItem("snoozed", 1, "Success and Snoozed", "Snoozed", wake = true) { is24 ->
                SnoozedScreen(state = s.snoozed, is24Hour = is24)
            },
        )
}
