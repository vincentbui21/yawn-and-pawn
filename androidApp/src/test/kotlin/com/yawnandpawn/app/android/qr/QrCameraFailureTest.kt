package com.yawnandpawn.app.android.qr

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
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
import com.yawnandpawn.app.android.wake.awaitScreen
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.qr.CodeFormat
import com.yawnandpawn.app.core.checks.qr.RegisteredCode
import com.yawnandpawn.app.core.session.CameraFallbackPolicy
import com.yawnandpawn.app.core.session.FallbackReason
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.testing.FakeClock
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/**
 * Story 3.11 with `FakeCodeScanner` and a virtual monotonic clock (the real Koin graph, `WakeApp`): a camera that sends
 * no frame for 5 s, fails mid-scan or is taken by another app brings up "Camera isn't available. Pick a fallback check."
 * and the fallback link at once; frames bring the viewfinder back and the link stays. The camera was bound and opened at
 * [START_MILLIS] (the virtual clock does not move by itself). Review: the latch, the torch and an open picker survive a
 * recreated screen; a slow open, a later wrong code and a second QR entry are handled.
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
        assertLink()
        assertTrue(wake.scanner.running, "the camera stays bound, so it can come back")
    }

    private fun assertLink() {
        composeRule
            .onNodeWithText(LINK)
            .assertExists()
            .assert(hasClickAction())
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.RequestFocus))
    }

    private fun assertViewfinder() {
        composeRule.onNodeWithText(UNAVAILABLE).assertDoesNotExist()
        composeRule.onNode(hasContentDescription(VIEWFINDER)).assertExists()
    }

    /** Heartbeats every 4 s from [fromMillis] to [toMillis] after [START_MILLIS], each followed by a watchdog tick. */
    private fun liveUntil(
        wake: QrWake,
        fromMillis: Long,
        toMillis: Long,
    ) {
        (fromMillis..toMillis step 4_000L).forEach { millis ->
            wake.heartbeatAt(START_MILLIS, millis)
            wake.at(START_MILLIS, millis + 100)
        }
    }

    @Test
    fun `no frame for 4_9 s shows the viewfinder, at 5_0 s the message and the link, and the wall clock plays no part`() {
        val wall = FakeClock()
        val wake = QrWake(composeRule, wall = wall)
        wake.ring()
        wake.launch().use {
            wake.imUp()
            assertEquals(1, wake.scanner.starts)

            wall.advanceBy(1.hours) // A wall-clock change during the ring: the watchdog reads the monotonic clock only.
            wake.at(START_MILLIS, 4_900)
            assertViewfinder()
            composeRule.onNodeWithText(LINK).assertDoesNotExist()

            wall.advanceBy(-2.hours)
            wake.at(START_MILLIS, 5_000)
            assertMessageAndLink(wake)
            assertEquals(1, wake.app.logs().count { it == "OperationFailed operation=camera cause=NoFrames" }, "logged once")

            wake.at(START_MILLIS, 9_000)
            assertEquals(1, wake.app.logs().count { it.startsWith("OperationFailed operation=camera ") }, "once per change")
        }
    }

    @Test
    fun `a slow camera open counts the 5 s from the open, and a camera that never opens is caught at 15 s (review)`() {
        val wake = QrWake(composeRule, scanner = FakeCodeScanner(opensAtOnce = false))
        wake.ring()
        wake.launch().use {
            wake.imUp()
            wake.at(START_MILLIS, 4_000)
            wake.scanner.open() // CameraX took 4 s to open the camera.
            wake.at(START_MILLIS, 8_400)
            assertViewfinder()
            wake.heartbeatAt(START_MILLIS, 8_500) // The first frame, 4.5 s after the open.
            wake.at(START_MILLIS, 9_100)
            assertViewfinder()
            composeRule.onNodeWithText(LINK).assertDoesNotExist()
        }
    }

    @Test
    fun `a camera that never opens shows the message 15 s after the bind`() {
        val wake = QrWake(composeRule, scanner = FakeCodeScanner(opensAtOnce = false))
        wake.ring()
        wake.launch().use {
            wake.imUp()
            wake.at(START_MILLIS, 14_900)
            assertViewfinder()
            wake.at(START_MILLIS, 15_000)
            assertMessageAndLink(wake)
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
    fun `a camera error mid-scan shows the message and a focusable link in the very next frame`() {
        val wake = QrWake(composeRule)
        wake.ring()
        wake.launch().use {
            wake.imUp()
            wake.heartbeatAt(START_MILLIS, 500)
            assertViewfinder()

            composeRule.mainClock.autoAdvance = false
            wake.scanner.fail(CameraProblem.CameraError)
            Snapshot.sendApplyNotifications() // What the main thread does right after the event, before the next frame.
            composeRule.mainClock.advanceTimeByFrame() // One frame, no more.
            assertMessageAndLink(wake)
            composeRule.mainClock.autoAdvance = true
        }
    }

    @Test
    fun `a disconnect shows the message without a picture, then 1 s of frames brings the viewfinder back while the link stays`() {
        val wake = QrWake(composeRule)
        wake.ring()
        wake.launch().use {
            wake.imUp()
            wake.heartbeatAt(START_MILLIS, 500)
            assertEquals(1, wake.scanner.previews, "the viewfinder draws the camera")
            wake.scanner.fail(CameraProblem.Disconnected) // Another app took the camera.
            composeRule.waitForIdle()
            assertMessageAndLink(wake)
            assertEquals(0, wake.scanner.previews, "no picture under the message")

            wake.heartbeatAt(START_MILLIS, 3_000)
            wake.heartbeatAt(START_MILLIS, 3_500)
            composeRule.onNodeWithText(UNAVAILABLE).assertExists() // Half a second of frames: not yet.
            wake.heartbeatAt(START_MILLIS, 4_000)

            assertViewfinder()
            assertEquals(1, wake.scanner.previews, "the picture is back")
            composeRule.onNodeWithText(LINK).assertExists() // Latched for the entry.
            assertEquals(1, wake.scanner.starts, "the same camera came back; nothing was bound again")

            wake.scanner.frames(3, wake.toothpaste)
            composeRule.awaitScreen(wake.app, "the scan resumed and passed the check") {
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
            composeRule.awaitScreen(wake.app, "one failed attempt") {
                wake.run().failedAttempts == 1
            }
            composeRule.onNodeWithText(LINK).assertDoesNotExist()

            wake.scanner.fail(CameraProblem.Disconnected)
            composeRule.waitForIdle()
            composeRule.onNodeWithText(LINK).performClick()
            composeRule.onNode(hasText("Math") and hasClickAction()).performClick()
            composeRule.awaitScreen(wake.app, "the fallback replaced the check") {
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
    fun `the latched link and the torch survive a recreated screen (auto dark mode at sunrise, rotation), and Math is allowed (review)`() {
        val wake = QrWake(composeRule)
        wake.ring()
        wake.launch().use { scenario ->
            wake.imUp()
            composeRule.onNode(hasContentDescription("Torch")).performClick()
            composeRule.waitForIdle()
            wake.scanner.fail(CameraProblem.Disconnected)
            (1..3).forEach { wake.heartbeatAt(START_MILLIS, it * 500L) }
            assertViewfinder()
            assertLink()

            scenario.recreate()
            composeRule.waitForIdle()
            assertLink()
            composeRule
                .onNode(hasContentDescription("Torch"))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
            assertTrue(wake.scanner.torchOn, "the rebound camera is lit as the switch shows")
            assertEquals(0, wake.run().failedAttempts)

            composeRule.onNodeWithText(LINK).performClick()
            composeRule.onNode(hasText("Math") and hasClickAction()).performClick()
            composeRule.awaitScreen(wake.app, "the fallback replaced the check with no failed attempt") {
                wake.run().fallbackUsed
            }
            assertEquals(FallbackReason.CameraUnavailable, wake.fallback.reasons.last())
        }
    }

    @Test
    fun `an open Fallback check picker stays open on a recreated screen (review)`() {
        val wake = QrWake(composeRule)
        wake.ring()
        wake.launch().use { scenario ->
            wake.imUp()
            wake.scanner.fail(CameraProblem.Disconnected)
            composeRule.waitForIdle()
            wake.openPicker()
            scenario.recreate()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Pick a fallback check").assertExists()
        }
    }

    @Test
    fun `the picker releases the camera, and Back to check binds it again with the link kept and the watchdog from 0 (review)`() {
        val wake = QrWake(composeRule)
        wake.ring()
        wake.launch().use {
            wake.imUp()
            wake.scanner.fail(CameraProblem.Disconnected)
            composeRule.waitForIdle()
            wake.openPicker()
            assertEquals(1, wake.scanner.stops, "the picker has no camera")

            wake.monotonic.set(START_MILLIS + 20_000)
            composeRule.onNode(hasContentDescription("Back to check")).performClick()
            composeRule.waitForIdle()
            assertEquals(2, wake.scanner.starts)
            assertLink()
            wake.at(START_MILLIS + 20_000, 4_900)
            assertViewfinder()
        }
    }

    @Test
    fun `a camera that reads no code for 60 s offers the link and keeps the viewfinder (privacy toggle, covered lens)`() {
        val wake = QrWake(composeRule)
        wake.ring()
        wake.launch().use {
            wake.imUp()
            liveUntil(wake, 4_000, 56_000)
            wake.heartbeatAt(START_MILLIS, 59_800)
            wake.at(START_MILLIS, 59_900)
            composeRule.onNodeWithText(LINK).assertDoesNotExist()
            wake.at(START_MILLIS, 60_000)

            assertViewfinder()
            composeRule.onNodeWithText(LINK).assertExists()
            composeRule.onNodeWithText(LINK).performClick()
            composeRule.onNode(hasText("Math") and hasClickAction()).performClick()
            composeRule.awaitScreen(wake.app, "the fallback replaced the check") {
                wake.run().fallbackUsed
            }
            assertIs<SessionState.Ring>(wake.app.engine.state.value, "a check still stands between the user and the end")
        }
    }

    @Test
    fun `a wrong code read once does not disarm the nothing-read link - 60 s after it, the link shows (review)`() {
        val wake = QrWake(composeRule)
        wake.ring()
        wake.launch().use {
            wake.imUp()
            wake.monotonic.set(START_MILLIS + 1_000)
            wake.scanner.frames(3, cereal) // One wrong code at 1 s, then black frames (the privacy toggle from the shade).
            liveUntil(wake, 4_000, 60_000)
            wake.app.dispatch(SessionEvent.UserInteracted)
            composeRule.onNodeWithText(LINK).assertDoesNotExist()
            wake.heartbeatAt(START_MILLIS, 61_000)
            wake.at(START_MILLIS, 61_100)
            assertViewfinder()
            assertLink()
            assertEquals(1, wake.run().failedAttempts)
        }
    }

    @Test
    fun `the latch ends with its entry - the second QR entry has no link and its own watchdog from 0 (review)`() {
        val wake = QrWake(composeRule)
        val second = assertNotNull(RegisteredCode.of(cereal.format, cereal.rawValue))
        wake.ring(codes = listOf(wake.code, second))
        wake.launch().use {
            wake.imUp()
            wake.scanner.fail(CameraProblem.Disconnected)
            (1..3).forEach { wake.heartbeatAt(START_MILLIS, it * 500L) }
            assertLink()

            wake.monotonic.set(START_MILLIS + 10_000)
            wake.scanner.frames(3, wake.toothpaste)
            composeRule.awaitScreen(wake.app, "the first entry passed") {
                wake.run().step.entry == 1
            }
            composeRule.onNodeWithText(LINK).assertDoesNotExist()
            wake.at(START_MILLIS + 10_000, 4_900)
            assertViewfinder()
            wake.at(START_MILLIS + 10_000, 5_000)
            assertMessageAndLink(wake)
        }
    }
}
