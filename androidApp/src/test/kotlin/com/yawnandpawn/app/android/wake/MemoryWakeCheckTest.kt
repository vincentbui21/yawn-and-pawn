package com.yawnandpawn.app.android.wake

import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.checks.CheckAnswer
import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.CheckMode
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.Difficulty
import com.yawnandpawn.app.core.session.CheckRun
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.StepPointer
import com.yawnandpawn.app.testing.aSessionConfig
import com.yawnandpawn.app.testing.rightAnswer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/**
 * Story 3.8 on the real wake screen (review fix: Memory wired into `WakeCheck`): after "I'm up" the round plays, taps
 * are sent as `CheckAnswerSubmitted(Tile)` and the engine decides; a wrong tap restarts the round with a new sequence.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class MemoryWakeCheckTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val memoryConfig =
        aSessionConfig().copy(checkPlan = CheckPlan(CheckMode.All, listOf(CheckEntry(CheckType.MemorySequence(), Difficulty.Easy, 2))))

    private fun ringMemory(app: WakeApp) {
        app.dispatch(SessionEvent.AlarmFired("session-1", memoryConfig, beforeFirstUnlock = false))
        assertIs<SessionState.Ringing>(app.engine.state.value)
    }

    private fun launch(app: WakeApp) = ActivityScenario.launch<WakeActivity>(Intent(app.app, WakeActivity::class.java))

    private fun run(app: WakeApp): CheckRun = assertIs<SessionState.Active>(app.engine.state.value).session.checkRun

    private fun waitFor(
        app: WakeApp,
        what: String,
        condition: () -> Boolean,
    ) = composeRule.awaitScreen(app, what, condition)

    /** Lets the sequence play (350 ms lit, 150 ms gaps) until it is the user's turn. */
    private fun awaitYourTurn(app: WakeApp) =
        waitFor(app, "Your turn") {
            composeRule.mainClock.advanceTimeBy(PLAYBACK_STEP_MILLIS)
            composeRule.onAllNodesWithText("Your turn").fetchSemanticsNodes().isNotEmpty()
        }

    private fun tap(tile: Int) = composeRule.onNodeWithContentDescription("Tile $tile").performClick()

    /** Taps the round's tiles as the engine expects them, waiting for each to be counted. */
    private fun solveRound(app: WakeApp) {
        awaitYourTurn(app)
        val start = run(app).step
        repeat(ROUND_LENGTH) { taps ->
            val tile = assertIs<CheckAnswer.Tile>(assertNotNull(rightAnswer(run(app)))).number
            tap(tile)
            waitFor(app, "tap ${taps + 1} counted") {
                app.engine.state.value !is SessionState.Ring || run(app).step != StepPointer(start.entry, start.item + taps)
            }
        }
    }

    @Test
    fun `I'm up plays the round, the taps solve both rounds and the session ends with Success`() {
        val app = WakeApp()
        ringMemory(app)
        launch(app).use {
            composeRule.onNodeWithText("I'm up").performClick()
            waitFor(app, "Grace") { app.engine.state.value is SessionState.Grace }
            composeRule.onNodeWithText("Round 1 of 2").assertExists()
            composeRule.onNodeWithText("Watch the sequence").assertExists()

            solveRound(app)
            composeRule.onNodeWithText("Round 2 of 2").assertExists()
            solveRound(app)

            composeRule.awaitSuccess(app, "Up on time.")
        }
    }

    @Test
    fun `a wrong tap restarts the round with a new sequence, shows Not quite while it plays, then it can be solved`() {
        val app = WakeApp()
        ringMemory(app)
        launch(app).use {
            composeRule.onNodeWithText("I'm up").performClick()
            waitFor(app, "Grace") { app.engine.state.value is SessionState.Grace }
            awaitYourTurn(app)
            val right = assertIs<CheckAnswer.Tile>(assertNotNull(rightAnswer(run(app)))).number
            tap(right)
            waitFor(app, "the first tap counted") { run(app).step == StepPointer(0, 1) }
            val seed = run(app).seeds.single()
            val wrong = (1..GRID_TILES).first { it != assertIs<CheckAnswer.Tile>(rightAnswer(run(app))).number }

            tap(wrong)
            waitFor(app, "the wrong tap counted") { run(app).failedAttempts == 1 }

            assertEquals(StepPointer(0, 0), run(app).step, "the round starts again")
            assertNotEquals(seed, run(app).seeds.single(), "with a new sequence")
            composeRule.onNodeWithText("Not quite. Try again.").assertExists()
            composeRule.onNodeWithText("Watch the sequence").assertExists()
            solveRound(app)
            composeRule.onNodeWithText("Not quite. Try again.").assertDoesNotExist()
            composeRule.onNodeWithText("Round 2 of 2").assertExists()
        }
    }

    private companion object {
        /** One playback step at most (a lit tile is 350 ms). */
        const val PLAYBACK_STEP_MILLIS = 350L

        /** Easy: 4 tiles a round. */
        const val ROUND_LENGTH = 4

        /** Easy: a 3x3 grid. */
        const val GRID_TILES = 9
    }
}
