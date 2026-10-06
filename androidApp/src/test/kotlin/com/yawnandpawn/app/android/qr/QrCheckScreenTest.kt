package com.yawnandpawn.app.android.qr

import android.content.Intent
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.android.wake.WakeActivity
import com.yawnandpawn.app.android.wake.WakeApp
import com.yawnandpawn.app.android.wake.awaitSuccess
import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.CheckMode
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.Difficulty
import com.yawnandpawn.app.core.checks.qr.CodeFormat
import com.yawnandpawn.app.core.checks.qr.RegisteredCode
import com.yawnandpawn.app.core.session.CheckRun
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.testing.aSessionConfig
import com.yawnandpawn.app.ui.qr.QrRegistrationRoute
import com.yawnandpawn.app.ui.qr.ScanResult
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import com.yawnandpawn.app.ui.withScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Story 3.10 with `FakeCodeScanner` (no camera): register a code, then scan it on the wake screen to stop the alarm; a
 * different code is a failed attempt, counted once per 2 s; without the permission the camera is unavailable at once.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class QrCheckScreenTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val toothpaste = ScanResult(CodeFormat.Ean13, "4006381333931")
    private val cereal = ScanResult(CodeFormat.Ean13, "5901234123457")

    private fun qrPlan(code: RegisteredCode) =
        CheckPlan(CheckMode.All, listOf(CheckEntry(CheckType.QrBarcode, Difficulty.Medium, 1, code = code)))

    private fun ringQr(
        app: WakeApp,
        code: RegisteredCode,
    ) {
        app.dispatch(SessionEvent.AlarmFired("session-qr", aSessionConfig().copy(checkPlan = qrPlan(code)), beforeFirstUnlock = false))
        assertIs<SessionState.Ringing>(app.engine.state.value)
    }

    private fun launch(app: WakeApp) = ActivityScenario.launch<WakeActivity>(Intent(app.app, WakeActivity::class.java))

    private fun run(app: WakeApp): CheckRun = assertIs<SessionState.Active>(app.engine.state.value).session.checkRun

    private fun imUp(app: WakeApp) {
        composeRule.onNodeWithText("I'm up").performClick()
        app.awaitUntil("Grace") {
            composeRule.waitForIdle()
            app.engine.state.value is SessionState.Grace
        }
    }

    /** Registers [result] on the registration screen, as a user does: hold the code up, then "Use this code". */
    private fun register(
        scanner: FakeCodeScanner,
        result: ScanResult,
    ): RegisteredCode {
        val chosen = mutableListOf<RegisteredCode>()
        val permission = FakeCameraPermission(granted = true)
        withScreen(
            PpsThemeMode.Light,
            content = { QrRegistrationRoute(onCodeChosen = { chosen += it }, onBack = {}, scanner = scanner, permission = permission) },
        ) {
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Scan a code to use this check.").assertExists()
            composeRule.onNodeWithText("Make a printable QR").assertDoesNotExist()
            assertTrue(scanner.running, "the viewfinder starts at once")
            scanner.frames(2, result)
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Use this code").assertDoesNotExist()
            scanner.frames(1, result)
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Scan again").assertExists()
            composeRule.onNodeWithText("Use this code").performClick()
            composeRule.waitForIdle()
        }
        assertEquals(0, permission.requests, "granted already: nothing asked")
        return chosen.single()
    }

    @Test
    fun `a registered code scanned on the wake screen stops the alarm`() {
        val scanner = FakeCodeScanner()
        val app = WakeApp(scanner = scanner)
        val code = register(scanner, toothpaste)
        assertEquals(RegisteredCode.of(CodeFormat.Ean13, "4006381333931"), code)

        ringQr(app, code)
        launch(app).use {
            assertFalse(scanner.running, "no camera on the Ringing screen")
            imUp(app)
            composeRule.onNodeWithText("Scan your code").assertExists()
            composeRule.onNode(hasContentDescription("Camera viewfinder. Point at your code.")).assertExists()
            assertTrue(scanner.running, "the viewfinder starts on screen open")

            scanner.frames(3, toothpaste)
            composeRule.awaitSuccess(app, "Up on time.")
        }
    }

    @Test
    fun `a different code is a failed attempt with the wrong-code line, counted once per 2 s`() {
        val scanner = FakeCodeScanner()
        val app = WakeApp(scanner = scanner)
        ringQr(app, assertNotNull(RegisteredCode.of(toothpaste.format, toothpaste.rawValue)))
        launch(app).use {
            imUp(app)
            scanner.frames(3, cereal)
            app.awaitUntil("the wrong code counted") {
                composeRule.waitForIdle()
                run(app).failedAttempts == 1
            }
            composeRule.onNodeWithText("That's a different code. Scan your registered one.").assertExists()

            scanner.frames(6, cereal)
            composeRule.waitForIdle()
            // A later event through the same scope: anything the screen sent before it has reached the engine.
            app.dispatch(SessionEvent.UserInteracted)
            assertEquals(1, run(app).failedAttempts, "the same wrong code within 2 s is not submitted again")

            ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(2_100))
            scanner.frames(3, cereal)
            app.awaitUntil("the wrong code counted again after 2 s") {
                composeRule.waitForIdle()
                run(app).failedAttempts == 2
            }
            app.dispatch(SessionEvent.UserInteracted)
            assertEquals(2, run(app).failedAttempts, "one more, not one per frame")
            assertTrue(app.engine.state.value is SessionState.Ring, "still ringing")

            composeRule.onNode(hasContentDescription("Torch")).performClick()
            composeRule.waitForIdle()
            assertTrue(scanner.torchOn, "the torch toggle reaches the camera")
        }
    }

    @Test
    fun `without the camera permission the check says the camera is not available at once and starts no camera`() {
        val scanner = FakeCodeScanner(permitted = false)
        val app = WakeApp(scanner = scanner)
        ringQr(app, assertNotNull(RegisteredCode.of(toothpaste.format, toothpaste.rawValue)))
        launch(app).use {
            imUp(app)
            composeRule.onNodeWithText("Camera isn't available. Pick a fallback check.").assertExists()
            composeRule.onNode(hasContentDescription("Camera viewfinder. Point at your code.")).assertDoesNotExist()
            assertFalse(scanner.running)
        }
    }

    @Test
    fun `a camera that cannot start shows the same message`() {
        val scanner = FakeCodeScanner()
        val app = WakeApp(scanner = scanner)
        ringQr(app, assertNotNull(RegisteredCode.of(toothpaste.format, toothpaste.rawValue)))
        launch(app).use {
            imUp(app)
            scanner.fail()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Camera isn't available. Pick a fallback check.").assertExists()
            assertFalse(scanner.running, "the camera is released")
        }
    }

    @Test
    fun `registration without the permission asks once and, denied, shows camera unavailable with Fix`() {
        val scanner = FakeCodeScanner(permitted = false)
        val permission = FakeCameraPermission(granted = false, answer = false)
        WakeApp(scanner = scanner)
        withScreen(
            PpsThemeMode.Light,
            content = { QrRegistrationRoute(onCodeChosen = {}, onBack = {}, scanner = scanner, permission = permission) },
        ) {
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Camera isn't available.").assertExists()
            composeRule.onNodeWithText("Fix").performClick()
            composeRule.waitForIdle()
        }
        assertEquals(1, permission.requests)
        assertEquals(1, permission.settingsOpened)
        assertFalse(scanner.running)
    }
}
