package com.yawnandpawn.app.android.wake

import android.content.Intent
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
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
import com.yawnandpawn.app.launchActivity
import com.yawnandpawn.app.testing.FakeAccessibilityState
import com.yawnandpawn.app.testing.aSessionConfig
import com.yawnandpawn.app.testing.rightAnswer
import com.yawnandpawn.app.ui.TalkBackRules
import com.yawnandpawn.app.ui.TalkBackRules.spokenOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Story 3.12 review, end to end on the real wake screen (`WakeActivity`, the engine's failed attempts): after each wrong
 * Word Unscramble word focus goes to "Not quite. Try again." (TalkBack reads it whole, and the next swipe is the first
 * letter); after a wrong Memory Sequence tap in the numbered variant focus never moves, so the replayed sequence is
 * announced in full. Math is covered by `TalkBackF5FlowTest`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class WrongAnswerFocusTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val wrongLine = hasText("Not quite. Try again.")
    private val focused = SemanticsMatcher.expectValue(SemanticsProperties.Focused, true)

    private fun run(app: WakeApp): CheckRun = assertIs<SessionState.Active>(app.engine.state.value).session.checkRun

    private fun waitFor(
        app: WakeApp,
        what: String,
        condition: () -> Boolean,
    ) = composeRule.awaitScreen(app, what, condition)

    private fun ring(
        app: WakeApp,
        entry: CheckEntry,
    ) {
        app.dispatch(
            SessionEvent.AlarmFired("session-1", aSessionConfig().copy(checkPlan = CheckPlan(CheckMode.All, listOf(entry))), false),
        )
        assertIs<SessionState.Ringing>(app.engine.state.value)
    }

    private fun imUp(app: WakeApp) {
        composeRule.onNode(hasText("I'm up") and hasClickAction()).performClick()
        waitFor(app, "Grace") { app.engine.state.value is SessionState.Grace }
    }

    @Test
    fun `after each wrong word focus goes to Not quite, read whole, and the next swipe is the first letter`() {
        val word = CheckEntry(CheckType.WordUnscramble, Difficulty.Easy, 2)
        val app = WakeApp()
        ring(app, word)
        launchActivity<WakeActivity>(Intent(app.app, WakeActivity::class.java)).use {
            imUp(app)
            repeat(2) { attempt ->
                val scramble = (word.type.generate(run(app).seeds.single(), word.difficulty, word.count) as Puzzle.Word).scrambles[0]
                // The scramble itself is never the word nor a listed word.
                scramble.uppercase().forEach { letter ->
                    composeRule.onAllNodes(hasContentDescription("Letter $letter"))[0].performClick()
                }
                waitFor(app, "wrong word ${attempt + 1} counted") { run(app).failedAttempts == attempt + 1 }

                composeRule.onNode(wrongLine).assertIsFocused()
                assertPolite()
                val spoken = composeRule.spokenOrder()
                val next = spoken[spoken.indexOf("Not quite. Try again.") + 1]
                assertTrue(next.startsWith("Letter "), "the next swipe is the first letter: $spoken")
            }
        }
    }

    @Test
    fun `after a wrong numbered Memory tap focus never moves, so the replayed sequence is announced in full`() {
        val memory = CheckEntry(CheckType.MemorySequence(numbered = true), Difficulty.Easy, 2)
        val app = WakeApp(accessibility = FakeAccessibilityState(screenReaderOn = true))
        ring(app, memory)
        launchActivity<WakeActivity>(Intent(app.app, WakeActivity::class.java)).use {
            imUp(app)
            awaitYourTurn(app)
            val wrong = (1..GRID_TILES).first { it != assertIs<CheckAnswer.Tile>(assertNotNull(rightAnswer(run(app)))).number }
            composeRule.onNode(hasContentDescription("Tile $wrong")).performClick()
            waitFor(app, "the wrong tap counted") { run(app).failedAttempts == 1 }

            // The replay: "Not quite.", the phase and the sequence are polite announcements, and nothing takes focus.
            assertPolite()
            val sequence = TalkBackRules.label(composeRule.onNode(SEQUENCE_NODE).fetchSemanticsNode())
            assertTrue(SEQUENCE.matches(sequence), sequence)
            assertNoFocus()
            awaitYourTurn(app)
            assertNoFocus()
        }
    }

    private fun assertPolite() =
        assertEquals(
            LiveRegionMode.Polite,
            composeRule
                .onNode(wrongLine)
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.LiveRegion),
        )

    private fun assertNoFocus() = assertTrue(composeRule.onAllNodes(focused).fetchSemanticsNodes().isEmpty(), "no node has focus")

    /** Lets the sequence play (350 ms lit, 150 ms gaps) until it is the user's turn. */
    private fun awaitYourTurn(app: WakeApp) =
        waitFor(app, "Your turn") {
            composeRule.mainClock.advanceTimeBy(PLAYBACK_STEP_MILLIS)
            composeRule.onAllNodes(hasText("Your turn")).fetchSemanticsNodes().isNotEmpty()
        }

    private companion object {
        const val PLAYBACK_STEP_MILLIS = 350L
        const val GRID_TILES = 9

        /** "3, 7, 1, 9": the round's tiles as numbers. */
        val SEQUENCE = Regex("""^\d+(, \d+)+$""")
        val SEQUENCE_NODE = SemanticsMatcher("the announced sequence") { SEQUENCE.matches(TalkBackRules.label(it)) }
    }
}
