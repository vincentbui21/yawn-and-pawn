package com.yawnandpawn.app.debug.preview

import androidx.compose.runtime.Composable
import com.yawnandpawn.app.ui.checkpicker.CheckPickerScreen
import com.yawnandpawn.app.ui.checkpicker.CheckPickerUiState
import com.yawnandpawn.app.ui.checksetup.CheckPreviewScreen
import com.yawnandpawn.app.ui.checksetup.CheckPreviewUiState
import com.yawnandpawn.app.ui.checksetup.CheckSetupScreen
import com.yawnandpawn.app.ui.checksetup.CheckSetupUiState
import com.yawnandpawn.app.ui.home.HomeScreen
import com.yawnandpawn.app.ui.househunt.HouseHuntRegistrationScreen
import com.yawnandpawn.app.ui.househunt.HouseHuntRegistrationUiState
import com.yawnandpawn.app.ui.onboarding.OnboardingScreen
import com.yawnandpawn.app.ui.onboarding.OnboardingStep
import com.yawnandpawn.app.ui.onboarding.OnboardingUiState
import com.yawnandpawn.app.ui.qr.PrintableQrScreen
import com.yawnandpawn.app.ui.qr.PrintableQrUiState
import com.yawnandpawn.app.ui.qr.QrRegistrationScreen
import com.yawnandpawn.app.ui.qr.QrRegistrationUiState
import com.yawnandpawn.app.ui.recordings.RecordingsScreen
import com.yawnandpawn.app.ui.recordings.RecordingsUiState
import com.yawnandpawn.app.ui.shell.AppShell
import com.yawnandpawn.app.ui.shell.AppTab

/**
 * Design preview round 3, the setup flows: onboarding (8 steps, the approximate base fee, no check, the phone not
 * locked, Home after skipping the test), the Check picker, Check setup and "Try it", QR registration and the printable
 * QR, House Hunt registration, and Recordings (empty, recording, a take, saved messages, microphone off, too short,
 * delete). App screens get the tall screenshot window like round 2; "Try it" is Sunrise.
 */
object PreviewRound3 {
    private val s = PreviewSetupSamples

    private fun item(
        id: String,
        group: String,
        title: String,
        primary: Boolean = false,
        hasDialog: Boolean = false,
        wake: Boolean = false,
        tall: Boolean = !wake,
        render: @Composable (is24Hour: Boolean) -> Unit,
    ) = PreviewItem(id, SETUP_ROUND, group, title, wake = wake, primary = primary, hasDialog = hasDialog, tall = tall, render = render)

    // A phone-sized window: the step's actions sit at the bottom of the screen; the long steps (checks, checklist)
    // get the tall one so every card is in the picture.
    private fun onboarding(
        id: String,
        title: String,
        state: OnboardingUiState,
        primary: Boolean = false,
    ) = item(
        id,
        "Onboarding",
        title,
        primary = primary,
        tall = state.step == OnboardingStep.Checks || state.step == OnboardingStep.Reliability,
    ) { is24 -> OnboardingScreen(state = state, is24Hour = is24, onIntent = {}) }

    private fun picker(
        id: String,
        title: String,
        state: CheckPickerUiState,
        primary: Boolean = false,
    ) = item(id, "Check picker", title, primary = primary) { CheckPickerScreen(state = state, onIntent = {}, onBack = {}) }

    private fun setup(
        id: String,
        title: String,
        state: CheckSetupUiState,
        primary: Boolean = false,
    ) = item(id, "Check setup", title, primary = primary) { CheckSetupScreen(state = state, onIntent = {}) }

    private fun tryIt(
        id: String,
        title: String,
        state: CheckPreviewUiState,
        primary: Boolean = false,
    ) = item(id, "Check setup: Try it", title, primary = primary, wake = true) {
        CheckPreviewScreen(state = state, onIntent = {}, onClose = {})
    }

    private fun qr(
        id: String,
        title: String,
        state: QrRegistrationUiState,
        primary: Boolean = false,
    ) = item(id, "QR registration", title, primary = primary) { QrRegistrationScreen(state = state, onIntent = {}) }

    private fun printable(
        id: String,
        title: String,
        state: PrintableQrUiState,
        primary: Boolean = false,
        hasDialog: Boolean = false,
    ) = item(id, "QR registration", title, primary = primary, hasDialog = hasDialog) { PrintableQrScreen(state = state, onIntent = {}) }

    private fun houseHunt(
        id: String,
        title: String,
        state: HouseHuntRegistrationUiState,
        primary: Boolean = false,
    ) = item(id, "House Hunt registration", title, primary = primary) { HouseHuntRegistrationScreen(state = state, onIntent = {}) }

    private fun recordings(
        id: String,
        title: String,
        state: RecordingsUiState,
        primary: Boolean = false,
        hasDialog: Boolean = false,
    ) = item(id, "Recordings", title, primary = primary, hasDialog = hasDialog) { RecordingsScreen(state = state, onIntent = {}) }

    val items: List<PreviewItem> =
        listOf(
            onboarding("onboarding_mission", "1 Mission", s.onboarding, primary = true),
            onboarding("onboarding_disclosure", "2 Alarm behaviour disclosure", s.onboardingDisclosure, primary = true),
            onboarding("onboarding_base_fee", "3 Base fee", s.onboardingBaseFee),
            onboarding("onboarding_base_fee_approximate", "3 Base fee, prices never loaded", s.onboardingBaseFeeApproximate),
            onboarding("onboarding_first_alarm", "4 First alarm", s.onboardingFirstAlarm, primary = true),
            onboarding("onboarding_checks", "5 Checks", s.onboardingChecks),
            onboarding("onboarding_checks_none", "5 Checks, none selected", s.onboardingChecksNone),
            onboarding("onboarding_reliability", "6 Reliability checklist", s.onboardingReliability),
            onboarding("onboarding_analytics", "7 Analytics choice", s.onboardingAnalytics),
            onboarding("onboarding_test", "8 Test alarm", s.onboardingTest, primary = true),
            onboarding("onboarding_test_not_locked", "8 Test alarm, phone not locked", s.onboardingTestNotLocked),
            item("home_test_skipped", "Onboarding", "Home after skipping the test alarm") { is24 ->
                AppShell(selected = AppTab.Alarms, onSelect = {}) { HomeScreen(state = s.homeTestSkipped, is24Hour = is24, onIntent = {}) }
            },
            picker("check_picker", "Math and QR/Barcode, Random", s.picker, primary = true),
            picker("check_picker_all", "All mode: the order", s.pickerAll),
            picker("check_picker_talkback", "Memory Sequence with TalkBack on", s.pickerTalkBack),
            picker("check_picker_camera_unavailable", "Camera unavailable", s.pickerCameraUnavailable),
            setup("check_setup_math", "Math: difficulty, problems, Try it", s.setupMath, primary = true),
            setup("check_setup_memory_talkback", "Memory Sequence with TalkBack on", s.setupMemoryTalkBack),
            setup("check_setup_qr", "QR/Barcode, code saved", s.setupQr),
            setup("check_setup_qr_none", "QR/Barcode, no code yet", s.setupQrNone),
            setup("check_setup_qr_camera_unavailable", "QR/Barcode, camera unavailable", s.setupQrCameraUnavailable),
            setup("check_setup_house_hunt", "House Hunt, two photos", s.setupHouseHunt),
            setup("check_setup_house_hunt_lost", "House Hunt, photos not restored", s.setupHouseHuntLost),
            tryIt("try_it_math", "Math", s.tryMath, primary = true),
            tryIt("try_it_word", "Word Unscramble", s.tryWord),
            tryIt("try_it_memory", "Memory Sequence", s.tryMemory),
            tryIt("try_it_done", "Solved: Nice. That's how it works.", s.tryDone),
            qr("qr_scanning", "Scanning", s.qrScanning, primary = true),
            qr("qr_detected", "Code found", s.qrDetected),
            qr("qr_camera_unavailable", "Camera unavailable", s.qrCameraUnavailable),
            printable("qr_printable", "Printable QR", s.printable, primary = true),
            printable("qr_replace_dialog", "Printable QR, replace the old code", s.printableReplace, hasDialog = true),
            houseHunt("house_hunt_empty", "No photos yet", s.houseHuntEmpty, primary = true),
            houseHunt("house_hunt_two_photos", "Two photos", s.houseHuntTwo),
            houseHunt("house_hunt_matched", "Three photos, test match matched", s.houseHuntMatched),
            houseHunt("house_hunt_no_match", "Test match, doesn't match yet", s.houseHuntNoMatch),
            houseHunt("house_hunt_photo_failed", "Couldn't use that photo", s.houseHuntPhotoFailed),
            houseHunt("house_hunt_need_photo", "Save without a photo", s.houseHuntNeedPhoto),
            houseHunt("house_hunt_camera_unavailable", "Camera unavailable", s.houseHuntCameraUnavailable),
            recordings("recordings_empty", "Empty", s.recordingsEmpty, primary = true),
            recordings("recordings_recording", "Recording", s.recordingsRecording),
            recordings("recordings_take", "A take: play, re-record, save, delete", s.recordingsTake),
            recordings("recordings_list", "Saved messages, one playing", s.recordingsList, primary = true),
            recordings("recordings_mic_denied", "Microphone off", s.recordingsMicDenied),
            recordings("recordings_too_short", "Too short", s.recordingsTooShort),
            recordings("recordings_delete_dialog", "Delete a message", s.recordingsDeleteDialog, hasDialog = true),
        )
}
