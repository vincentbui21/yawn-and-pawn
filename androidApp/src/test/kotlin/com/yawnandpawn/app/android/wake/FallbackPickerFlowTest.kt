package com.yawnandpawn.app.android.wake

import android.content.Intent
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.session.CameraFallbackPolicy
import com.yawnandpawn.app.core.session.FallbackDecision
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.testing.FakeFallbackPolicy
import com.yawnandpawn.app.testing.aSessionConfig
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
 * Story 3.9: "Can't do this check?" opens the Fallback check picker; "Back to check" returns without using it; Math
 * replaces the check, once. No camera check screen exists before Story 3.10, so a fake policy offers the fallback on the
 * Math screen, standing in for a camera check whose camera is unavailable.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class FallbackPickerFlowTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val mathHard6 = CameraFallbackPolicy.fallbackPlan(CheckType.Math)

    private fun session(app: WakeApp) = assertIs<SessionState.Active>(app.engine.state.value).session

    private fun waitFor(
        app: WakeApp,
        what: String,
        condition: () -> Boolean,
    ) = app.awaitUntil(what) {
        composeRule.waitForIdle()
        condition()
    }

    private fun link() = composeRule.onNodeWithText("Can't do this check?")

    @Test
    fun `the link opens the picker, Back to check returns, and Math replaces the check once with the grace window running on`() {
        val policy = FakeFallbackPolicy(FallbackDecision.Allowed(mathHard6))
        val app = WakeApp(fallback = policy)
        app.dispatch(
            SessionEvent.AlarmFired("session-1", aSessionConfig().copy(checkPlan = CheckPlan.default()), beforeFirstUnlock = false),
        )
        ActivityScenario.launch<WakeActivity>(Intent(app.app, WakeActivity::class.java)).use { scenario ->
            composeRule.onNodeWithText("I'm up").performClick()
            waitFor(app, "Grace") { app.engine.state.value is SessionState.Grace }
            val graceEnd = session(app).graceEnd

            link().performClick()
            composeRule.onNodeWithText("Pick a fallback check").assertExists()
            // Back does nothing on a wake screen: the picker stays.
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            composeRule.onNodeWithText("Pick a fallback check").assertExists()
            assertIs<SessionState.Grace>(app.engine.state.value, "the alarm stays muted while the countdown runs")

            composeRule.onNode(hasContentDescription("Back to check")).performClick()
            composeRule.onNodeWithText("Problem 1 of 3").assertExists()
            assertFalse(session(app).checkRun.fallbackUsed, "closing uses no fallback")

            link().performClick()
            composeRule.onNode(hasText("Math") and hasClickAction()).performClick()
            waitFor(app, "the fallback replaced the check") { session(app).checkRun.fallbackUsed }

            assertEquals(mathHard6, session(app).checkRun.plan)
            assertEquals(graceEnd, session(app).graceEnd, "no new grace window")
            assertIs<SessionState.Grace>(app.engine.state.value)
            composeRule.onNodeWithText("Problem 1 of 6").assertExists()
            link().assertDoesNotExist()
            composeRule.onNodeWithText("Pick a fallback check").assertDoesNotExist()
        }
    }

    @Test
    fun `without an offered fallback there is no link`() {
        val policy = FakeFallbackPolicy(FallbackDecision.NotAllowed)
        val app = WakeApp(fallback = policy)
        app.dispatch(
            SessionEvent.AlarmFired("session-1", aSessionConfig().copy(checkPlan = CheckPlan.default()), beforeFirstUnlock = false),
        )
        ActivityScenario.launch<WakeActivity>(Intent(app.app, WakeActivity::class.java)).use {
            composeRule.onNodeWithText("I'm up").performClick()
            waitFor(app, "Grace") { app.engine.state.value is SessionState.Grace }

            composeRule.onNodeWithText("Problem 1 of 3").assertExists()
            link().assertDoesNotExist()
            assertTrue(policy.calls > 0, "the screen asked the policy")
        }
    }
}
