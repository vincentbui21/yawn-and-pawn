package com.yawnandpawn.app.debug.preview

import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.ui.checkpicker.CheckPickerUiState
import com.yawnandpawn.app.ui.checks.CheckType
import com.yawnandpawn.app.ui.checks.Difficulty
import com.yawnandpawn.app.ui.checksetup.CheckPreviewUiState
import com.yawnandpawn.app.ui.checksetup.CheckSetupUiState
import com.yawnandpawn.app.ui.editor.CheckChip
import com.yawnandpawn.app.ui.editor.CheckMode
import com.yawnandpawn.app.ui.format.Countdown
import com.yawnandpawn.app.ui.format.Weekdays
import com.yawnandpawn.app.ui.format.formatMoney
import com.yawnandpawn.app.ui.home.AlarmCard
import com.yawnandpawn.app.ui.home.HomeUiState
import com.yawnandpawn.app.ui.househunt.HouseHuntError
import com.yawnandpawn.app.ui.househunt.HouseHuntRegistrationUiState
import com.yawnandpawn.app.ui.onboarding.OnboardingStep
import com.yawnandpawn.app.ui.onboarding.OnboardingUiState
import com.yawnandpawn.app.ui.onboarding.TestAlarmStatus
import com.yawnandpawn.app.ui.qr.PrintableQrUiState
import com.yawnandpawn.app.ui.qr.QrRegistrationUiState
import com.yawnandpawn.app.ui.qr.QrScanStep
import com.yawnandpawn.app.ui.recordings.RecordedMessage
import com.yawnandpawn.app.ui.recordings.RecorderState
import com.yawnandpawn.app.ui.recordings.RecordingsUiState
import com.yawnandpawn.app.ui.reliability.ChecklistItem
import com.yawnandpawn.app.ui.reliability.ChecklistRow
import com.yawnandpawn.app.ui.reliability.ItemStatus
import com.yawnandpawn.app.ui.settings.SnoozePrices
import com.yawnandpawn.app.ui.wake.CheckContent
import com.yawnandpawn.app.ui.wake.HouseHuntResult
import com.yawnandpawn.app.ui.wake.MathOperator
import com.yawnandpawn.app.ui.wake.MemoryPhase
import kotlinx.datetime.LocalTime

/**
 * Fake data for design preview round 3, the setup flows, from EXPERIENCE.md F1 (Linh's first run: 7:30 on weekdays,
 * Math and QR/Barcode at random, "Try it" on Math, two checklist fixes) and F10 (her recorded message). Prices go
 * through [PreviewSamples.price], so they are the phone's own currency.
 */
object PreviewSetupSamples {
    // Check picker --------------------------------------------------------------------------------------------------

    /** F1: Math (Medium, 2 problems) and QR/Barcode (a code saved), one of them at random each morning. */
    val picker =
        CheckPickerUiState(
            checks = listOf(CheckChip(CheckType.Math, Difficulty.Medium, count = 2), CheckChip(CheckType.QrBarcode, Difficulty.Medium)),
            mode = CheckMode.Random,
            qrCodeSaved = true,
            houseHuntPhotos = 2,
        )

    /** All mode: three checks in the order they run, with "Move up" / "Move down". */
    val pickerAll =
        picker.copy(
            checks =
                listOf(
                    CheckChip(CheckType.Math, Difficulty.Medium, count = 2),
                    CheckChip(CheckType.MemorySequence, Difficulty.Easy, count = 3),
                    CheckChip(CheckType.HouseHunt, Difficulty.Medium),
                ),
            mode = CheckMode.All,
        )

    /** TalkBack on (Dara, F5): Memory Sequence says "Uses numbered tiles with TalkBack." */
    val pickerTalkBack = picker.copy(checks = listOf(CheckChip(CheckType.MemorySequence, Difficulty.Easy, count = 3)), talkBackOn = true)

    val pickerCameraUnavailable = picker.copy(cameraUnavailable = true)

    // Onboarding ----------------------------------------------------------------------------------------------------

    /** "Make sure it rings": battery and full-screen alarm still to fix (F1 step 6); the camera for the QR check. */
    val onboardingChecklist =
        listOf(
            ChecklistRow(ChecklistItem.Notifications, ItemStatus.Ok),
            ChecklistRow(ChecklistItem.FullScreen, ItemStatus.Missing),
            ChecklistRow(ChecklistItem.ExactAlarms, ItemStatus.Ok),
            ChecklistRow(ChecklistItem.DoNotDisturb, ItemStatus.Ok),
            ChecklistRow(ChecklistItem.Battery, ItemStatus.Missing),
            ChecklistRow(ChecklistItem.Manufacturer, ItemStatus.Missing),
            ChecklistRow(ChecklistItem.Camera, ItemStatus.Ok),
        )

    /**
     * Step 1 at the lowest tier. The onboarding samples are getters, and the catalogue takes them as lambdas, because they
     * format prices with the phone's `MoneyFormatter` (ICU), which is not available when the parameterized tests build
     * their case list (like the Settings samples, Story 4.5).
     */
    val onboarding: OnboardingUiState get() =
        OnboardingUiState(
            checks = picker,
            reliability = onboardingChecklist,
        ).withPreviewFee(tier = 1)

    val onboardingDisclosure: OnboardingUiState get() = onboarding.copy(step = OnboardingStep.Disclosure)

    val onboardingBaseFee: OnboardingUiState get() = onboarding.copy(step = OnboardingStep.BaseFee)

    /** Offline on first launch (F1 failure): USD tiers with the approximate-price note. */
    val onboardingBaseFeeApproximate: OnboardingUiState get() =
        onboardingBaseFee.copy(
            baseFee = formatMoney(Money.of(1, USD)),
            feeLadder = (1..SnoozePrices.LADDER_ENTRIES).map { formatMoney(Money.of(it, USD)) },
            pricesApproximate = true,
        )

    val onboardingFirstAlarm: OnboardingUiState get() = onboarding.copy(step = OnboardingStep.FirstAlarm)

    val onboardingChecks: OnboardingUiState get() = onboarding.copy(step = OnboardingStep.Checks)

    val onboardingChecksNone: OnboardingUiState get() =
        onboardingChecks.copy(checks = CheckPickerUiState(checks = emptyList(), noCheckError = true))

    val onboardingReliability: OnboardingUiState get() = onboarding.copy(step = OnboardingStep.Reliability)

    val onboardingAnalytics: OnboardingUiState get() = onboarding.copy(step = OnboardingStep.Analytics)

    val onboardingTest: OnboardingUiState get() = onboarding.copy(step = OnboardingStep.TestAlarm)

    val onboardingTestNotLocked: OnboardingUiState get() = onboardingTest.copy(testStatus = TestAlarmStatus.NotLocked)

    /** Home after onboarding with the test skipped: the new 7:30 alarm and the note recommending the test (FR-ONB-4). */
    val homeTestSkipped =
        HomeUiState(
            nextAlarm = Countdown.HoursMinutes(7, 50),
            alarms = listOf(AlarmCard("new", LocalTime(7, 30), Weekdays, null, listOf(CheckType.Math, CheckType.QrBarcode), true)),
            testSkipped = true,
        )

    // Check setup ---------------------------------------------------------------------------------------------------

    val setupMath = CheckSetupUiState(CheckType.Math, Difficulty.Medium, count = 2)

    val setupMemoryTalkBack = CheckSetupUiState(CheckType.MemorySequence, Difficulty.Easy, count = 3, talkBackOn = true)

    val setupQr = CheckSetupUiState(CheckType.QrBarcode, codeSaved = true)

    val setupQrNone = CheckSetupUiState(CheckType.QrBarcode)

    val setupQrCameraUnavailable = setupQr.copy(cameraUnavailable = true)

    val setupHouseHunt = CheckSetupUiState(CheckType.HouseHunt, photoCount = 2)

    val setupHouseHuntLost = CheckSetupUiState(CheckType.HouseHunt, photosLost = true)

    // "Try it" ------------------------------------------------------------------------------------------------------

    val tryMath =
        CheckPreviewUiState(
            CheckContent.Math(problemNumber = 1, problemCount = 2, operands = listOf(47, 38), operators = listOf(MathOperator.Plus)),
        )

    val tryMemory = CheckPreviewUiState(CheckContent.MemorySequence(round = 1, roundCount = 3, phase = MemoryPhase.Watch, litTile = 5))

    val tryWord =
        CheckPreviewUiState(
            CheckContent.WordUnscramble(
                wordNumber = 1,
                wordCount = 2,
                pool = listOf('R', null, 'I', 'S', 'N', 'U'),
                slots = listOf('S', 'U', null, null, null, null),
            ),
        )

    val tryDone = tryMath.copy(done = true)

    // QR/Barcode ----------------------------------------------------------------------------------------------------

    val qrScanning = QrRegistrationUiState()

    val qrDetected = QrRegistrationUiState(step = QrScanStep.Detected)

    val qrCameraUnavailable = QrRegistrationUiState(cameraUnavailable = true)

    val printable = PrintableQrUiState()

    val printableReplace = PrintableQrUiState(codeSaved = true, showReplaceDialog = true)

    // House Hunt ----------------------------------------------------------------------------------------------------

    val houseHuntEmpty = HouseHuntRegistrationUiState()

    val houseHuntTwo = HouseHuntRegistrationUiState(photos = 2)

    val houseHuntMatched = HouseHuntRegistrationUiState(photos = 3, testResult = HouseHuntResult.Matched)

    val houseHuntNoMatch = HouseHuntRegistrationUiState(photos = 2, testResult = HouseHuntResult.NoMatch)

    val houseHuntPhotoFailed = HouseHuntRegistrationUiState(photos = 1, error = HouseHuntError.PhotoFailed)

    val houseHuntNeedPhoto = HouseHuntRegistrationUiState(error = HouseHuntError.NoPhoto)

    val houseHuntCameraUnavailable = HouseHuntRegistrationUiState(cameraUnavailable = true)

    // Recordings (F10) ----------------------------------------------------------------------------------------------

    /** A voice's level meter mid-sentence (fixed values; nothing records). */
    private val levels = listOf(0.2f, 0.5f, 0.8f, 0.6f, 0.3f, 0.7f, 1f, 0.8f, 0.4f, 0.2f, 0.5f, 0.9f, 0.7f, 0.3f, 0.6f, 0.8f, 0.5f, 0.2f)

    val recordingsEmpty = RecordingsUiState()

    /** Linh at 0:09 of "Stand-up is at nine. You like being early." */
    val recordingsRecording = RecordingsUiState(recorder = RecorderState.Recording(seconds = 9, levels = levels))

    val recordingsTake = RecordingsUiState(recorder = RecorderState.Recorded(seconds = 9))

    val recordingsList =
        RecordingsUiState(
            messages =
                listOf(
                    RecordedMessage(number = 1, seconds = 9, playing = true, playedSeconds = 4),
                    RecordedMessage(number = 2, seconds = 14),
                ),
        )

    val recordingsMicDenied = RecordingsUiState(micDenied = true)

    val recordingsTooShort = RecordingsUiState(tooShort = true)

    val recordingsDeleteDialog =
        RecordingsUiState(
            messages = listOf(RecordedMessage(number = 1, seconds = 9), RecordedMessage(number = 2, seconds = 14)),
            deleteDialogFor = 2,
        )

    /** The saved messages of [recordingsList], not playing: what Recordings opens with in the tap-through. */
    val recordingsSaved = recordingsDeleteDialog.copy(deleteDialogFor = null)
}

/** Prices never loaded: onboarding shows US dollar tiers. */
private const val USD = "USD"

/**
 * Onboarding at the preview's base fee [tier] (Story 5.10): snooze N costs N x [tier] x the preview base fee, in the
 * phone's currency ([PreviewSamples.price]), with the 3-entry ladder (max snoozes 5), as the real step shows Play's
 * prices.
 */
internal fun OnboardingUiState.withPreviewFee(tier: Int): OnboardingUiState =
    copy(
        baseFeeTier = tier,
        baseFee = formatMoney(PreviewSamples.price(tier)),
        feeLadder = (1..SnoozePrices.LADDER_ENTRIES).map { formatMoney(PreviewSamples.price(tier * it)) },
    )
