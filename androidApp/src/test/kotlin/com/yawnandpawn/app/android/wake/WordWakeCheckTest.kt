package com.yawnandpawn.app.android.wake

import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
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
import com.yawnandpawn.app.core.checks.Puzzle
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
import kotlin.test.assertNotNull

/**
 * Story 3.7 on the real wake screen (review fix: Word wired into `WakeCheck`), over the app's own word list: the letters
 * are placed on screen only, a full word is sent as `CheckAnswerSubmitted(Word)` and the engine decides.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class WordWakeCheckTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val wordEntry = CheckEntry(CheckType.WordUnscramble, Difficulty.Easy, 2)
    private val wordConfig = aSessionConfig().copy(checkPlan = CheckPlan(CheckMode.All, listOf(wordEntry)))

    private fun ringWord(app: WakeApp) {
        app.dispatch(SessionEvent.AlarmFired("session-1", wordConfig, beforeFirstUnlock = false))
        assertIs<SessionState.Ringing>(app.engine.state.value)
    }

    private fun launch(app: WakeApp) = ActivityScenario.launch<WakeActivity>(Intent(app.app, WakeActivity::class.java))

    private fun run(app: WakeApp): CheckRun = assertIs<SessionState.Active>(app.engine.state.value).session.checkRun

    private fun waitFor(
        app: WakeApp,
        what: String,
        condition: () -> Boolean,
    ) = app.awaitUntil(what) {
        composeRule.waitForIdle()
        condition()
    }

    /** Taps the pool letters that spell [word], as a user does. */
    private fun spell(word: String) =
        word.uppercase().forEach { letter ->
            composeRule.onAllNodesWithContentDescription("Letter $letter")[0].performClick()
        }

    private fun scramble(app: WakeApp): String {
        val run = run(app)
        val puzzle = wordEntry.type.generate(run.seeds.single(), wordEntry.difficulty, wordEntry.count) as Puzzle.Word
        return puzzle.scrambles[run.step.item]
    }

    @Test
    fun `I'm up shows word 1 of 2, a full right word moves on, and both words end the session with Success`() {
        val app = WakeApp()
        ringWord(app)
        launch(app).use {
            composeRule.onNodeWithText("I'm up").performClick()
            waitFor(app, "Grace") { app.engine.state.value is SessionState.Grace }

            repeat(2) { item ->
                composeRule.onNodeWithText("Word ${item + 1} of 2").assertExists()
                spell(assertIs<CheckAnswer.Word>(assertNotNull(rightAnswer(run(app)))).text)
                waitFor(app, "word ${item + 1} answered") {
                    app.engine.state.value !is SessionState.Ring || run(app).step != StepPointer(0, item)
                }
            }

            composeRule.awaitSuccess(app, "Up on time.")
        }
    }

    @Test
    fun `a wrong word clears the slots and says Not quite, and the same word can then be solved`() {
        val app = WakeApp()
        ringWord(app)
        launch(app).use {
            composeRule.onNodeWithText("I'm up").performClick()
            waitFor(app, "Grace") { app.engine.state.value is SessionState.Grace }

            // The scramble itself is never the word nor a listed word.
            spell(scramble(app))
            waitFor(app, "the wrong word counted") { run(app).failedAttempts == 1 }

            composeRule.onNodeWithText("Not quite. Try again.").assertExists()
            assertEquals(StepPointer(0, 0), run(app).step, "the same word")
            composeRule.onNodeWithContentDescription("Slot 1, empty").assertExists()
            spell(assertIs<CheckAnswer.Word>(assertNotNull(rightAnswer(run(app)))).text)
            waitFor(app, "word 1 answered") { run(app).step == StepPointer(0, 1) }
            composeRule.onNodeWithText("Word 2 of 2").assertExists()
            composeRule.onNodeWithText("Not quite. Try again.").assertDoesNotExist()
        }
    }
}
