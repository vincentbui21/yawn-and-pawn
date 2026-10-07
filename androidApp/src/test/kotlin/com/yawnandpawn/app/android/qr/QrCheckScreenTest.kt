package com.yawnandpawn.app.android.qr

import android.content.Intent
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
import com.yawnandpawn.app.core.session.CameraFallbackPolicy
import com.yawnandpawn.app.core.session.CheckRun
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.testing.aSessionConfig
import com.yawnandpawn.app.testing.wrongAnswer
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
            // Story 3.9: the fallback link shows at once when the camera is unavailable.
            composeRule.onNodeWithText("Can't do this check?").assertExists()
            assertFalse(scanner.running)
            assertEquals(0, scanner.starts)
        }
    }

    @Test
    fun `a camera that fails while scanning shows the same message and is released`() {
        val scanner = FakeCodeScanner()
        val app = WakeApp(scanner = scanner)
        ringQr(app, assertNotNull(RegisteredCode.of(toothpaste.format, toothpaste.rawValue)))
        launch(app).use {
            imUp(app)
            scanner.fail()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Camera isn't available. Pick a fallback check.").assertExists()
            // Story 3.9: the fallback link shows at once when the camera is unavailable.
            composeRule.onNodeWithText("Can't do this check?").assertExists()
            assertFalse(scanner.running, "the camera is released")
        }
    }

    @Test
    fun `the camera starts once, through the grace countdown's redraws and into Loud`() {
        val scanner = FakeCodeScanner()
        val app = WakeApp(scanner = scanner)
        ringQr(app, assertNotNull(RegisteredCode.of(toothpaste.format, toothpaste.rawValue)))
        launch(app).use {
            imUp(app)
            repeat(5) {
                ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(1))
                composeRule.mainClock.advanceTimeBy(1_000)
                composeRule.waitForIdle()
            }
            composeRule.onNodeWithText("Scan your code").assertExists()
            assertEquals(1, scanner.starts, "the countdown redraws every second; the camera is not rebound")

            ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(20))
            app.dispatch(SessionEvent.GraceElapsed)
            app.awaitUntil("Loud") {
                composeRule.waitForIdle()
                app.engine.state.value is SessionState.Loud
            }
            composeRule.onNodeWithText("Scan your code").assertExists()
            assertTrue(scanner.running)
            assertEquals(1, scanner.starts, "grace running out to Loud keeps the same camera")
        }
    }

    @Test
    fun `with two codes in view the registered one is submitted, and the other never counts as a failed attempt`() {
        val scanner = FakeCodeScanner()
        val app = WakeApp(scanner = scanner)
        ringQr(app, assertNotNull(RegisteredCode.of(toothpaste.format, toothpaste.rawValue)))
        launch(app).use {
            imUp(app)
            // The cereal is seen first, so it is stable one frame before the toothpaste: it is not submitted.
            scanner.frames(1, cereal)
            scanner.frames(2, cereal, toothpaste)
            composeRule.waitForIdle()
            app.dispatch(SessionEvent.UserInteracted)
            assertEquals(0, run(app).failedAttempts, "another code next to one not yet stable is never submitted")
            scanner.frames(1, cereal, toothpaste)
            composeRule.awaitSuccess(app, "Up on time.")
        }
    }

    @Test
    fun `without the camera the fallback link replaces the QR check with Math, asked for an unavailable camera (Story 3_9)`() {
        val scanner = FakeCodeScanner(permitted = false)
        val app = WakeApp(scanner = scanner)
        ringQr(app, assertNotNull(RegisteredCode.of(toothpaste.format, toothpaste.rawValue)))
        launch(app).use {
            imUp(app)
            composeRule.onNodeWithText("Can't do this check?").performClick()
            composeRule.onNodeWithText("Pick a fallback check").assertExists()
            composeRule.onNode(hasText("Math") and hasClickAction()).performClick()
            app.awaitUntil("the fallback replaced the check") {
                composeRule.waitForIdle()
                run(app).fallbackUsed
            }

            assertEquals(CameraFallbackPolicy.fallbackPlan(CheckType.Math), run(app).plan)
            composeRule.onNodeWithText("Problem 1 of 6").assertExists()
            composeRule.onNodeWithText("Can't do this check?").assertDoesNotExist()
        }
    }

    @Test
    fun `with a working camera the fallback link waits for 5 failed attempts (Story 3_9)`() {
        val scanner = FakeCodeScanner()
        val app = WakeApp(scanner = scanner)
        ringQr(app, assertNotNull(RegisteredCode.of(toothpaste.format, toothpaste.rawValue)))
        launch(app).use {
            imUp(app)
            composeRule.onNodeWithText("Can't do this check?").assertDoesNotExist()
            repeat(
                CameraFallbackPolicy.FAILED_ATTEMPTS,
            ) { app.dispatch(SessionEvent.CheckAnswerSubmitted(assertNotNull(wrongAnswer(run(app))))) }
            app.awaitUntil("the link shows") {
                composeRule.waitForIdle()
                composeRule.onAllNodesWithText("Can't do this check?").fetchSemanticsNodes().isNotEmpty()
            }
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
