package com.yawnandpawn.app.android.qr

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.android.qr.QrWake.Companion.LINK
import com.yawnandpawn.app.android.qr.QrWake.Companion.START_MILLIS
import com.yawnandpawn.app.android.qr.QrWake.Companion.UNAVAILABLE
import com.yawnandpawn.app.android.qr.QrWake.Companion.VIEWFINDER
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.qr.CodeFormat
import com.yawnandpawn.app.core.session.CameraFallbackPolicy
import com.yawnandpawn.app.core.session.FallbackReason
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.ui.qr.CameraProblem
import com.yawnandpawn.app.ui.qr.ScanResult
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Story 3.11 with `FakeCodeScanner` and a virtual monotonic clock (the real Koin graph, `WakeApp`): a camera that sends
 * no frame for 5 s, fails mid-scan or is taken by another app brings up "Camera isn't available. Pick a fallback check."
 * and the fallback link at once; frames bring the viewfinder back and the link stays. The camera was bound at
 * [START_MILLIS] (the virtual clock does not move by itself).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class QrCameraFailureTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val cereal = ScanResult(CodeFormat.Ean13, "5901234123457")

    private fun assertMessageAndLink(wake: QrWake) {
        composeRule
            .onNodeWithText(UNAVAILABLE)
            .assertExists()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        composeRule.onNode(hasContentDescription(VIEWFINDER)).assertDoesNotExist()
        composeRule
            .onNodeWithText(LINK)
            .assertExists()
            .assert(hasClickAction())
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.RequestFocus))
        assertTrue(wake.scanner.running, "the camera stays bound, so it can come back")
    }

    private fun assertViewfinder() {
        composeRule.onNodeWithText(UNAVAILABLE).assertDoesNotExist()
        composeRule.onNode(hasContentDescription(VIEWFINDER)).assertExists()
    }

    @Test
    fun `no frame for 4_9 s shows the viewfinder, at 5_0 s the message and the link (reason CameraUnavailable)`() {
        val wake = QrWake(composeRule)
        wake.ring()
        wake.launch().use {
            wake.imUp()
            assertEquals(1, wake.scanner.starts)

            wake.at(START_MILLIS, 4_900)
            assertViewfinder()
            composeRule.onNodeWithText(LINK).assertDoesNotExist()

            wake.at(START_MILLIS, 5_000)
            assertMessageAndLink(wake)
            assertEquals(1, wake.app.logs().count { it == "OperationFailed operation=camera cause=NoFrames" }, "logged once")

            wake.at(START_MILLIS, 9_000)
            assertEquals(1, wake.app.logs().count { it.startsWith("OperationFailed operation=camera ") }, "once per change")
        }
    }

    @Test
    fun `frames keep the viewfinder past 5 s`() {
        val wake = QrWake(composeRule)
        wake.ring()
        wake.launch().use {
            wake.imUp()
            (1..6).forEach { second ->
                wake.heartbeatAt(START_MILLIS, second * 1_000L)
                wake.at(START_MILLIS, second * 1_000L + 400)
            }
            assertViewfinder()
            composeRule.onNodeWithText(LINK).assertDoesNotExist()
        }
    }

    @Test
    fun `a camera error mid-scan shows the message and the link at once, in the same frame`() {
        val wake = QrWake(composeRule)
        wake.ring()
        wake.launch().use {
            wake.imUp()
            wake.heartbeatAt(START_MILLIS, 500)
            assertViewfinder()

            wake.scanner.fail(CameraProblem.CameraError)
            composeRule.waitForIdle() // No clock moves: the message and a focusable link are there at once.
            assertMessageAndLink(wake)
        }
    }

    @Test
    fun `a disconnect shows the message, then 1 s of frames brings the viewfinder back while the link stays`() {
        val wake = QrWake(composeRule)
        wake.ring()
        wake.launch().use {
            wake.imUp()
            wake.heartbeatAt(START_MILLIS, 500)
            wake.scanner.fail(CameraProblem.Disconnected) // Another app took the camera.
            composeRule.waitForIdle()
            assertMessageAndLink(wake)

            wake.heartbeatAt(START_MILLIS, 3_000)
            wake.heartbeatAt(START_MILLIS, 3_500)
            composeRule.onNodeWithText(UNAVAILABLE).assertExists() // Half a second of frames: not yet.
            wake.heartbeatAt(START_MILLIS, 4_000)

            assertViewfinder()
            composeRule.onNodeWithText(LINK).assertExists() // Latched for the entry.
            assertEquals(1, wake.scanner.starts, "the same camera came back; nothing was bound again")

            wake.scanner.frames(3, wake.toothpaste)
            wake.app.awaitUntil("the scan resumed and passed the check") {
                composeRule.waitForIdle()
                wake.app.engine.state.value !is SessionState.Ring
            }
        }
    }

    @Test
    fun `with the camera down and 1 failed attempt, Math is allowed (reason CameraUnavailable) and runs Hard with 6 problems`() {
        val wake = QrWake(composeRule)
        wake.ring()
        wake.launch().use {
            wake.imUp()
            wake.scanner.frames(3, cereal)
            wake.app.awaitUntil("one failed attempt") {
                composeRule.waitForIdle()
                wake.run().failedAttempts == 1
            }
            composeRule.onNodeWithText(LINK).assertDoesNotExist()

            wake.scanner.fail(CameraProblem.Disconnected)
            composeRule.waitForIdle()
            composeRule.onNodeWithText(LINK).performClick()
            composeRule.onNode(hasText("Math") and hasClickAction()).performClick()
            wake.app.awaitUntil("the fallback replaced the check") {
                composeRule.waitForIdle()
                wake.run().fallbackUsed
            }

            // FailedAttempts with 1 failed attempt would have been denied: the screen asked with CameraUnavailable.
            assertEquals(FallbackReason.CameraUnavailable, wake.fallback.reasons.last())
            assertEquals(1, wake.run().totalFailedAttempts)
            assertEquals(CameraFallbackPolicy.fallbackPlan(CheckType.Math), wake.run().plan)
            composeRule.onNodeWithText("Problem 1 of 6").assertExists()
            composeRule.onNodeWithText(LINK).assertDoesNotExist()
            assertFalse(wake.scanner.running, "no QR screen, no camera")
        }
    }

    @Test
    fun `a camera that reads no code at all for 60 s offers the link and keeps the viewfinder (privacy toggle, covered lens)`() {
        val wake = QrWake(composeRule)
        wake.ring()
        wake.launch().use {
            wake.imUp()
            (4_000L..56_000L step 4_000L).forEach { millis ->
                wake.heartbeatAt(START_MILLIS, millis)
                wake.at(START_MILLIS, millis + 100)
            }
            composeRule.onNodeWithText(LINK).assertDoesNotExist()
            wake.heartbeatAt(START_MILLIS, 59_900)
            wake.at(START_MILLIS, 60_000)

            assertViewfinder()
            composeRule.onNodeWithText(LINK).assertExists()
            composeRule.onNodeWithText(LINK).performClick()
            composeRule.onNode(hasText("Math") and hasClickAction()).performClick()
            wake.app.awaitUntil("the fallback replaced the check") {
                composeRule.waitForIdle()
                wake.run().fallbackUsed
            }
            assertIs<SessionState.Ring>(wake.app.engine.state.value, "a check still stands between the user and the end")
        }
    }

    @Test
    fun `a camera that read a wrong code never gets the nothing-read link`() {
        val wake = QrWake(composeRule)
        wake.ring()
        wake.launch().use {
            wake.imUp()
            wake.scanner.frames(3, cereal)
            (4_000L..64_000L step 4_000L).forEach { millis ->
                wake.heartbeatAt(START_MILLIS, millis)
                wake.at(START_MILLIS, millis + 100)
            }
            wake.app.dispatch(SessionEvent.UserInteracted)
            composeRule.onNodeWithText(LINK).assertDoesNotExist()
            assertViewfinder()
        }
    }
}
