package com.yawnandpawn.app.ui.qr

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.yawnandpawn.app.core.checks.qr.RegisteredCode
import com.yawnandpawn.app.ui.components.LocalViewfinderFeed
import org.koin.compose.koinInject

/**
 * QR registration's state (Story 3.10), UI only: scanning until a code is seen in 3 frames in a row, then "Use this code"
 * or "Scan again". Without the camera permission (or with a camera that cannot start) it shows "Camera isn't
 * available." with "Fix". The code chosen goes to the Check setup draft through [onCodeChosen]; nothing is stored here.
 */
class QrRegistrationModel(
    private val permission: CameraPermission,
    private val onCodeChosen: (RegisteredCode) -> Unit,
    private val onBack: () -> Unit,
) {
    var state: QrRegistrationUiState by mutableStateOf(QrRegistrationUiState(cameraUnavailable = !permission.isGranted()))
        private set

    /** The torch toggle (UI only; the scanner lights it while the viewfinder runs). */
    var torchOn: Boolean by mutableStateOf(false)
        private set

    /** The code found, waiting for "Use this code"; null while scanning. */
    var found: RegisteredCode? by mutableStateOf(null)
        private set

    /** The viewfinder runs: the camera can be used. It keeps running while a found code waits, which is ignored. */
    val scanning: Boolean
        get() = !state.cameraUnavailable

    private var asked = false

    /**
     * Opened, or back from the system settings ("Fix"): re-reads the permission. When it is missing on the first show the
     * dialog is asked once (registration is reached only after picking QR/Barcode, so this is the "only when needed"
     * moment); a denial shows "Camera isn't available." and later shows only read the permission again, so closing the
     * dialog (which resumes the screen) never asks twice.
     */
    suspend fun onShown() {
        val granted =
            when {
                permission.isGranted() -> {
                    true
                }

                asked -> {
                    false
                }

                else -> {
                    asked = true
                    permission.request()
                }
            }
        state = state.copy(cameraUnavailable = !granted)
    }

    /** What the scanner reports: a stable code pauses on it, unless one is already waiting; a dead camera is unavailable. */
    fun onScan(event: ScanEvent) {
        when (event) {
            is ScanEvent.Detected -> {
                val code = event.result.code
                if (found == null && code != null && state.step == QrScanStep.Scanning) {
                    found = code
                    state = state.copy(step = QrScanStep.Detected)
                }
            }

            ScanEvent.CameraUnavailable -> {
                state = state.copy(cameraUnavailable = true)
            }
        }
    }

    fun onIntent(intent: QrIntent) {
        when (intent) {
            QrIntent.Back -> {
                onBack()
            }

            QrIntent.TorchToggled -> {
                torchOn = !torchOn
            }

            QrIntent.UseCode -> {
                found?.let(onCodeChosen)
            }

            QrIntent.ScanAgain -> {
                found = null
                state = state.copy(step = QrScanStep.Scanning)
            }

            QrIntent.FixCamera -> {
                permission.openSettings()
            }

            // The printable QR is Epic 7 (FR-PWK-13); its screen exists only in the design preview.
            QrIntent.PrintableClicked, QrIntent.PrintClicked, QrIntent.ReplaceConfirmed, QrIntent.ReplaceCancelled -> {
                Unit
            }
        }
    }
}

/**
 * QR registration as a pushed screen (Story 3.10): [QrRegistrationScreen] with the scanner's live feed in its viewfinder.
 * [resumed] changes each time the screen comes back to the front (for example from the system settings after "Fix"), so
 * the permission is read again.
 *
 * 3.5 hook: Check setup's "Your code" row (`CheckSetupIntent.ScanCodeClicked`) pushes this screen, and [onCodeChosen]
 * writes the code into the QR/Barcode entry of the setup draft (`CheckEntry.code`), saved with the alarm, then pops it.
 */
@Composable
fun QrRegistrationRoute(
    onCodeChosen: (RegisteredCode) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    resumed: Int = 0,
    scanner: CodeScanner = koinInject(),
    permission: CameraPermission = koinInject(),
) {
    val chosen by rememberUpdatedState(onCodeChosen)
    val back by rememberUpdatedState(onBack)
    val model = remember(permission) { QrRegistrationModel(permission, onCodeChosen = { chosen(it) }, onBack = { back() }) }
    LaunchedEffect(model, resumed) { model.onShown() }
    val feed: (@Composable BoxScope.() -> Unit)? =
        if (model.scanning) {
            { scanner.Feed(torchOn = model.torchOn, onEvent = model::onScan) }
        } else {
            null
        }
    CompositionLocalProvider(LocalViewfinderFeed provides feed) {
        QrRegistrationScreen(state = model.state, onIntent = model::onIntent, modifier = modifier, printable = false)
    }
}
