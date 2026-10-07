package com.yawnandpawn.app.android.qr

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.android.qr.QrWake.Companion.START_MILLIS
import com.yawnandpawn.app.android.qr.QrWake.Companion.UNAVAILABLE
import com.yawnandpawn.app.android.qr.QrWake.Companion.VIEWFINDER
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.testing.wrongAnswer
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

/**
 * Story 3.11: the wake QR camera follows the screen. Paused (screen off, Home, the shade) the camera is released and the
 * torch goes off; resumed it binds again with the 5 s watchdog started over and the torch as the switch shows it. The
 * grace countdown's redraws and Loud never rebind it, and a session restored after a kill binds it once on the same step.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class QrLifecycleTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val torch = hasContentDescription("Torch")

    private fun torchIs(state: ToggleableState) = SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, state)

    @Test
    fun `paused the camera and torch are released, resumed they come back and the watchdog starts over`() {
        val wake = QrWake(composeRule)
        wake.ring()
        wake.launch().use { scenario ->
            wake.imUp()
            assertEquals(1, wake.scanner.starts)
            composeRule.onNode(torch).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            composeRule.onNode(torch).assert(torchIs(ToggleableState.Off))
            composeRule.onNode(torch).performClick()
            composeRule.onNode(torch).assert(torchIs(ToggleableState.On))
            assertTrue(wake.scanner.torchOn)
            wake.at(START_MILLIS, 4_000)

            scenario.moveToState(Lifecycle.State.STARTED) // The screen goes off.
            composeRule.waitForIdle()
            assertEquals(1, wake.scanner.stops, "released while paused")
            assertFalse(wake.scanner.running)
            assertFalse(wake.scanner.torchOn, "the torch goes off with the camera")

            wake.at(START_MILLIS, 30_000) // Paused long past the watchdog: nothing counts while paused.
            scenario.moveToState(Lifecycle.State.RESUMED)
            composeRule.waitForIdle()
            assertEquals(2, wake.scanner.starts, "bound again on resume")
            assertTrue(wake.scanner.torchOn, "lit again as the switch shows")
            composeRule.onNode(torch).assert(torchIs(ToggleableState.On))

            wake.at(START_MILLIS + 30_000, 4_900)
            composeRule.onNode(hasContentDescription(VIEWFINDER)).assertExists()
            composeRule.onNodeWithText(UNAVAILABLE).assertDoesNotExist()
            wake.at(START_MILLIS + 30_000, 5_000)
            composeRule.onNodeWithText(UNAVAILABLE).assertExists()
        }
    }

    @Test
    fun `the grace countdown's redraws and grace running out to Loud never bind the camera again`() {
        val wake = QrWake(composeRule)
        wake.ring()
        wake.launch().use {
            wake.imUp()
            (1..8).forEach { second ->
                wake.heartbeatAt(START_MILLIS, second * 1_000L)
                composeRule.mainClock.advanceTimeBy(1_000)
                composeRule.waitForIdle()
            }
            wake.heartbeatAt(START_MILLIS, GRACE_OVER)
            wake.app.dispatch(SessionEvent.GraceElapsed)
            wake.app.awaitUntil("Loud") {
                composeRule.waitForIdle()
                wake.app.engine.state.value is SessionState.Loud
            }
            composeRule.onNode(hasContentDescription(VIEWFINDER)).assertExists()
            assertEquals(1, wake.scanner.starts)
            assertEquals(0, wake.scanner.stops)
        }
    }

    @Test
    fun `a session restored after a kill shows the same step with its failed attempts and binds the camera once`() {
        val first = QrWake(composeRule)
        first.ring()
        first.app.dispatch(SessionEvent.ImUpTapped)
        first.monotonic.set(START_MILLIS + GRACE_OVER)
        first.app.dispatch(SessionEvent.GraceElapsed)
        repeat(2) { first.app.dispatch(SessionEvent.CheckAnswerSubmitted(assertNotNull(wrongAnswer(first.run())))) }
        assertIs<SessionState.Loud>(first.app.engine.state.value)
        assertEquals(2, first.run().failedAttempts)

        // The process dies; a new one opens the wake screen, which restores the stored session.
        val wake = QrWake(composeRule, monotonic = first.monotonic)
        assertEquals(SessionState.Idle, wake.app.engine.state.value)
        wake.launch().use {
            wake.app.awaitUntil("restored on the same step") {
                composeRule.waitForIdle()
                wake.app.engine.state.value is SessionState.Loud
            }
            composeRule.onNode(hasContentDescription(VIEWFINDER)).assertExists()
            assertEquals(2, wake.run().failedAttempts)
            assertEquals(1, wake.scanner.starts)
            wake.scanner.frames(3, wake.toothpaste)
            wake.app.awaitUntil("the restored scan passes the check") {
                composeRule.waitForIdle()
                wake.app.engine.state.value !is SessionState.Ring
            }
        }
    }

    private companion object {
        /** Past the default 20 s grace window. */
        const val GRACE_OVER = 25_000L
    }
}
