package com.yawnandpawn.app.debug.preview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yawnandpawn.app.ui.checks.CheckType
import com.yawnandpawn.app.ui.checksetup.CheckPreviewScreen
import com.yawnandpawn.app.ui.checksetup.CheckSetupIntent
import com.yawnandpawn.app.ui.checksetup.CheckSetupScreen
import com.yawnandpawn.app.ui.checksetup.CheckSetupUiState
import com.yawnandpawn.app.ui.editor.CheckChip
import com.yawnandpawn.app.ui.househunt.HouseHuntError
import com.yawnandpawn.app.ui.househunt.HouseHuntIntent
import com.yawnandpawn.app.ui.househunt.HouseHuntRegistrationScreen
import com.yawnandpawn.app.ui.househunt.HouseHuntRegistrationUiState
import com.yawnandpawn.app.ui.qr.PrintableQrScreen
import com.yawnandpawn.app.ui.qr.PrintableQrUiState
import com.yawnandpawn.app.ui.qr.QrIntent
import com.yawnandpawn.app.ui.qr.QrRegistrationScreen
import com.yawnandpawn.app.ui.qr.QrRegistrationUiState
import com.yawnandpawn.app.ui.qr.QrScanStep
import com.yawnandpawn.app.ui.recordings.RecordingsIntent
import com.yawnandpawn.app.ui.recordings.RecordingsScreen
import com.yawnandpawn.app.ui.wake.CheckContent
import com.yawnandpawn.app.ui.wake.WakeIntent
import kotlinx.coroutines.delay

/** The round 3 screens the tap-through pushes: Check setup and what it opens, and Recordings. */
internal sealed interface SetupPushed : Pushed {
    data class CheckSetup(
        val type: CheckType,
    ) : SetupPushed

    data object TryIt : SetupPushed

    data object QrRegistration : SetupPushed

    data object PrintableQr : SetupPushed

    data object HouseHunt : SetupPushed

    data object Recordings : SetupPushed
}

/**
 * The setup flows of the tap-through (design preview round 3), fake state only: Check setup with "Try it", QR
 * registration and the printable QR, House Hunt registration, and Recordings. [updateChips] changes the selected checks
 * of whoever opened Check setup (the editor or onboarding); [onMessages] hands the saved message numbers back to the
 * editor's Motivation list. What the camera checks have registered ([qrCodeSaved], [houseHuntPhotos]) is shared.
 */
internal class SetupFlowState(
    private val push: (Pushed) -> Unit,
    private val pop: () -> Unit,
    private val updateChips: ((List<CheckChip>) -> List<CheckChip>) -> Unit,
    private val onMessages: (List<Int>) -> Unit,
    registered: Boolean,
) {
    var checkSetup by mutableStateOf(PreviewSetupSamples.setupMath)
    var tryIt by mutableStateOf(PreviewSetupSamples.tryMath)
    var qr by mutableStateOf(PreviewSetupSamples.qrScanning)
    var printable by mutableStateOf(PreviewSetupSamples.printable)
    var houseHunt by mutableStateOf(PreviewSetupSamples.houseHuntEmpty)
    var recordings by mutableStateOf(PreviewSetupSamples.recordingsSaved)
    var qrCodeSaved by mutableStateOf(registered)
    var houseHuntPhotos by mutableIntStateOf(if (registered) 2 else 0)
    private var tryTaps = 0

    fun openCheckSetup(chip: CheckChip) {
        checkSetup =
            CheckSetupUiState(chip.type, chip.difficulty, chip.count, codeSaved = qrCodeSaved, photoCount = houseHuntPhotos)
        push(SetupPushed.CheckSetup(chip.type))
    }

    fun openRecordings() = push(SetupPushed.Recordings)

    fun onCheckSetup(intent: CheckSetupIntent) {
        when (intent) {
            CheckSetupIntent.Back -> {
                pop()
            }

            is CheckSetupIntent.DifficultySelected -> {
                setChip(checkSetup.copy(difficulty = intent.difficulty))
            }

            is CheckSetupIntent.CountChanged -> {
                setChip(checkSetup.copy(count = intent.count))
            }

            CheckSetupIntent.TryItClicked -> {
                tryIt = tryItFor(checkSetup.type)
                tryTaps = 0
                push(SetupPushed.TryIt)
            }

            CheckSetupIntent.ScanCodeClicked -> {
                qr = QrRegistrationUiState()
                push(SetupPushed.QrRegistration)
            }

            CheckSetupIntent.PrintableQrClicked -> {
                printable = PrintableQrUiState(codeSaved = qrCodeSaved)
                push(SetupPushed.PrintableQr)
            }

            CheckSetupIntent.PhotosClicked -> {
                houseHunt = HouseHuntRegistrationUiState(photos = if (checkSetup.photosLost) 0 else houseHuntPhotos)
                push(SetupPushed.HouseHunt)
            }

            CheckSetupIntent.FixCamera -> {
                checkSetup = checkSetup.copy(cameraUnavailable = false)
            }
        }
    }

    private fun setChip(setup: CheckSetupUiState) {
        checkSetup = setup
        updateChips { chips ->
            chips.map {
                if (it.type ==
                    setup.type
                ) {
                    it.copy(difficulty = setup.difficulty, count = setup.count)
                } else {
                    it
                }
            }
        }
    }

    /** "Try it": the check responds like the real one; solved, "Done" and the close return to Check setup. */
    fun onTryIt(intent: WakeIntent) {
        if (intent == WakeIntent.DoneClicked) pop() else tryIt = reduceTryIt(tryIt, intent, ++tryTaps)
    }

    fun onQr(intent: QrIntent) {
        when (intent) {
            QrIntent.Back -> pop()
            QrIntent.UseCode -> registerCode()
            QrIntent.ScanAgain -> qr = qr.copy(step = QrScanStep.Scanning)
            QrIntent.PrintableClicked -> openPrintable()
            QrIntent.PrintClicked -> if (printable.codeSaved) printable = printable.copy(showReplaceDialog = true) else registerCode()
            QrIntent.ReplaceConfirmed -> registerCode()
            QrIntent.ReplaceCancelled -> printable = printable.copy(showReplaceDialog = false)
            QrIntent.FixCamera -> qr = qr.copy(cameraUnavailable = false)
            QrIntent.TorchToggled -> Unit
        }
    }

    private fun openPrintable() {
        printable = PrintableQrUiState(codeSaved = qrCodeSaved)
        push(SetupPushed.PrintableQr)
    }

    /** A scanned code or a printed QR is now the registered code: back to where it came from. */
    private fun registerCode() {
        qrCodeSaved = true
        checkSetup = checkSetup.copy(codeSaved = true)
        printable = printable.copy(showReplaceDialog = false)
        pop()
    }

    fun onHouseHunt(intent: HouseHuntIntent) {
        val state = houseHunt
        when (intent) {
            HouseHuntIntent.SaveClicked -> {
                if (state.photos == 0) {
                    houseHunt = state.copy(error = HouseHuntError.NoPhoto)
                } else {
                    houseHuntPhotos = state.photos
                    checkSetup = checkSetup.copy(photoCount = state.photos, photosLost = false)
                    pop()
                }
            }

            HouseHuntIntent.Cancel -> {
                pop()
            }

            else -> {
                houseHunt = reduceHouseHunt(state, intent)
            }
        }
    }

    fun onRecordings(intent: RecordingsIntent) {
        if (intent == RecordingsIntent.Back) {
            onMessages(recordings.messages.map { it.number })
            pop()
        } else {
            recordings = reduceRecordings(recordings, intent)
        }
    }
}

/** The screens of [SetupPushed]. QR registration "finds" a code 1.5 s after it opens (there is no camera). */
@Composable
internal fun SetupScreen(
    top: SetupPushed,
    setup: SetupFlowState,
) {
    when (top) {
        is SetupPushed.CheckSetup -> {
            CheckSetupScreen(state = setup.checkSetup, onIntent = setup::onCheckSetup)
        }

        SetupPushed.TryIt -> {
            val tryIt = setup.tryIt
            CheckPreviewScreen(state = tryIt, onIntent = setup::onTryIt, onClose = { setup.onTryIt(WakeIntent.DoneClicked) })
            // A QR code "scans" on its own in the preview.
            if (tryIt.content is CheckContent.QrBarcode && !tryIt.done) {
                LaunchedEffect(tryIt) {
                    delay(DETECT_MILLIS)
                    setup.tryIt = tryIt.copy(done = true)
                }
            }
        }

        SetupPushed.QrRegistration -> {
            val qr = setup.qr
            QrRegistrationScreen(state = qr, onIntent = setup::onQr)
            if (qr.step == QrScanStep.Scanning && !qr.cameraUnavailable) {
                LaunchedEffect(qr) {
                    delay(DETECT_MILLIS)
                    setup.qr = qr.copy(step = QrScanStep.Detected)
                }
            }
        }

        SetupPushed.PrintableQr -> {
            PrintableQrScreen(state = setup.printable, onIntent = setup::onQr)
        }

        SetupPushed.HouseHunt -> {
            HouseHuntRegistrationScreen(state = setup.houseHunt, onIntent = setup::onHouseHunt)
        }

        SetupPushed.Recordings -> {
            RecordingsScreen(state = setup.recordings, onIntent = setup::onRecordings)
        }
    }
}

/** How long the preview "camera" takes to find a code. */
private const val DETECT_MILLIS = 1_500L

/** System Back on a setup screen: like its own back arrow (Recordings hands its messages back first). */
internal fun SetupFlowState.back(top: SetupPushed) {
    when (top) {
        SetupPushed.Recordings -> onRecordings(RecordingsIntent.Back)
        SetupPushed.QrRegistration, SetupPushed.PrintableQr -> onQr(QrIntent.Back)
        SetupPushed.HouseHunt -> onHouseHunt(HouseHuntIntent.Cancel)
        SetupPushed.TryIt -> onTryIt(WakeIntent.DoneClicked)
        is SetupPushed.CheckSetup -> onCheckSetup(CheckSetupIntent.Back)
    }
}
