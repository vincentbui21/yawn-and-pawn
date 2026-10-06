package com.yawnandpawn.app.ui

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.screenshotOptions
import com.yawnandpawn.app.ui.qr.QrRegistrationScreen
import com.yawnandpawn.app.ui.qr.QrRegistrationUiState
import com.yawnandpawn.app.ui.qr.QrScanStep
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import com.yawnandpawn.app.ui.wake.CheckContent
import com.yawnandpawn.app.ui.wake.CheckScreen
import com.yawnandpawn.app.ui.wake.CheckUiState
import com.yawnandpawn.app.ui.wake.GraceState
import com.yawnandpawn.app.ui.wake.SnoozeOffer
import com.yawnandpawn.app.ui.wake.SnoozeUnavailableReason
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Story 3.10 screenshots, with the camera preview replaced by the placeholder surface (no feed is provided): the wake QR
 * check (scanning, a different code, camera unavailable) in Sunrise, and QR registration (scanning, a code found, camera
 * unavailable) in Light and Dark, at 100% and 200% font scale.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-mdpi")
class QrScreenshotTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val snooze = SnoozeOffer.Unavailable(SnoozeUnavailableReason.PricesNotLoaded)
    private val scanning = CheckUiState(grace = GraceState.Running(14, 20), content = CheckContent.QrBarcode(), snooze = snooze)
    private val wrong = scanning.copy(content = CheckContent.QrBarcode(wrongCode = true, wrongAttempts = 1))
    private val unavailable = scanning.copy(grace = GraceState.Expired, content = CheckContent.QrBarcode(cameraAvailable = false))

    private fun wake(
        name: String,
        state: CheckUiState,
        assertions: () -> Unit = {},
    ) = withScreen(PpsThemeMode.Light, content = { CheckScreen(state = state, onIntent = {}) }) {
        composeRule.onNodeWithText("Scan your code").assertExists()
        composeRule.onNode(hasContentDescription("Snooze unavailable, prices not loaded yet")).assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = screenshotOptions)
        assertions()
    }

    private fun registration(
        name: String,
        mode: PpsThemeMode,
        state: QrRegistrationUiState,
        assertions: () -> Unit = {},
    ) = withScreen(mode, content = { QrRegistrationScreen(state = state, onIntent = {}, printable = false) }) {
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = screenshotOptions)
        assertions()
    }

    private fun torch() = composeRule.onNode(hasContentDescription("Torch")).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)

    @Test
    fun `wake QR check scanning`() =
        wake("wake_check_qr_sunrise", scanning) {
            composeRule.onNode(hasContentDescription("Camera viewfinder. Point at your code.")).assertExists()
            torch()
        }

    @Test
    @Config(fontScale = 2.0f)
    fun `wake QR check scanning at 200 percent`() = wake("wake_check_qr_sunrise_font200", scanning)

    @Test
    fun `wake QR check with a different code`() =
        wake("wake_check_qr_wrong_sunrise", wrong) {
            composeRule.onNodeWithText("That's a different code. Scan your registered one.").assertExists()
        }

    @Test
    @Config(fontScale = 2.0f)
    fun `wake QR check with a different code at 200 percent`() = wake("wake_check_qr_wrong_sunrise_font200", wrong)

    @Test
    fun `wake QR check without the camera`() =
        wake("wake_check_qr_camera_unavailable_sunrise", unavailable) {
            composeRule.onNodeWithText("Camera isn't available. Pick a fallback check.").assertExists()
            composeRule.onNode(hasContentDescription("Camera viewfinder. Point at your code.")).assertDoesNotExist()
        }

    @Test
    @Config(fontScale = 2.0f)
    fun `wake QR check without the camera at 200 percent`() = wake("wake_check_qr_camera_unavailable_sunrise_font200", unavailable)

    @Test
    fun `registration scanning, light and dark`() {
        registration("qr_registration_scanning_light", PpsThemeMode.Light, QrRegistrationUiState()) {
            composeRule.onNodeWithText("Scan a code to use this check.").assertExists()
            torch()
        }
        registration("qr_registration_scanning_dark", PpsThemeMode.Dark, QrRegistrationUiState())
    }

    @Test
    @Config(fontScale = 2.0f)
    fun `registration scanning at 200 percent`() {
        registration("qr_registration_scanning_light_font200", PpsThemeMode.Light, QrRegistrationUiState())
        registration("qr_registration_scanning_dark_font200", PpsThemeMode.Dark, QrRegistrationUiState())
    }

    @Test
    fun `registration with a code found, light and dark`() {
        val found = QrRegistrationUiState(step = QrScanStep.Detected)
        registration("qr_registration_detected_light", PpsThemeMode.Light, found) {
            composeRule.onNodeWithText("Use this code").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            composeRule.onNodeWithText("Scan again").assertIsDisplayed()
        }
        registration("qr_registration_detected_dark", PpsThemeMode.Dark, found)
    }

    @Test
    @Config(fontScale = 2.0f)
    fun `registration with a code found at 200 percent`() {
        val found = QrRegistrationUiState(step = QrScanStep.Detected)
        registration("qr_registration_detected_light_font200", PpsThemeMode.Light, found)
        registration("qr_registration_detected_dark_font200", PpsThemeMode.Dark, found)
    }

    @Test
    fun `registration without the camera, light and dark`() {
        val none = QrRegistrationUiState(cameraUnavailable = true)
        registration("qr_registration_camera_unavailable_light", PpsThemeMode.Light, none) {
            composeRule.onNodeWithText("Camera isn't available.").assertExists()
            composeRule.onNodeWithText("Fix").assertExists()
        }
        registration("qr_registration_camera_unavailable_dark", PpsThemeMode.Dark, none)
    }

    @Test
    @Config(fontScale = 2.0f)
    fun `registration without the camera at 200 percent`() {
        val none = QrRegistrationUiState(cameraUnavailable = true)
        registration("qr_registration_camera_unavailable_light_font200", PpsThemeMode.Light, none)
        registration("qr_registration_camera_unavailable_dark_font200", PpsThemeMode.Dark, none)
    }
}
