package com.yawnandpawn.app.ui

import android.app.AlarmManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.android.wake.WakeApp
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.testing.FakeActiveSessionStore
import com.yawnandpawn.app.testing.FakeSessionHistoryRepository
import com.yawnandpawn.app.ui.editor.AlarmEditorArgs
import com.yawnandpawn.app.ui.editor.AlarmEditorRoute
import com.yawnandpawn.app.ui.editor.AlarmEditorViewModel
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.parameter.parametersOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Story 3.6: a "Try it" run on the real app graph (Koin, Room, the wake engine and runtime) never rings, touches a
 * session, writes history or arms anything: it is a practice run of the check screen only.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-mdpi")
class TryItNoStakesTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Test
    fun `a full Try it, a wrong answer, the right one and Done, leaves no trace and returns to Check setup`() {
        val store = FakeActiveSessionStore()
        val history = FakeSessionHistoryRepository()
        val app = WakeApp(store = store, history = history)
        val viewModel = app.koin.get<AlarmEditorViewModel> { parametersOf(AlarmEditorArgs(alarmId = null)) }

        withScreen(PpsThemeMode.Light, content = {
            AlarmEditorRoute(alarmId = null, onClose = {}, onOpenFailed = {}, onOpenCopy = {}, viewModel = viewModel)
        }) {
            composeRule.onNodeWithText("Wake-up check").performClick()
            composeRule.onNodeWithText("Easy · 3 problems").performClick()
            composeRule.onNodeWithText("Try it").performClick()
            composeRule.waitForIdle()

            // The problem as TalkBack reads it ("23 times 4 plus 17"), worked out here: the screen never says the answer.
            val spoken =
                composeRule
                    .onNode(isHeading() and SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.ContentDescription]
                    .single()
            val answer = evaluate(spoken)
            tap(answer + 1)
            composeRule.onNodeWithText("Check").performClick()
            composeRule.onNodeWithText("Not quite. Try again.").assertExists()
            tap(answer)
            composeRule.onNodeWithText("Check").performClick()
            composeRule.onNodeWithText("Nice. That's how it works.").assertExists()
            composeRule.onNodeWithText("Done").performClick()
            composeRule.waitForIdle()

            composeRule.onNodeWithText("Problems").assertExists()
        }

        assertEquals(SessionState.Idle, app.engine.state.value, "no session")
        assertEquals(emptyList(), store.commits, "nothing written to runtime.db")
        assertTrue(history.rows.isEmpty(), "no history row")
        assertTrue(shadowOf(app.app.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty(), "nothing armed")
        assertTrue(app.mediaPlayers.isEmpty(), "no sound")
        assertEquals(null, app.runtime.emergency.value)
    }

    @Test
    fun `Back in Try it returns to Check setup and drops the practice answer (Story 3-6 review fix)`() {
        val app = WakeApp()
        val viewModel = app.koin.get<AlarmEditorViewModel> { parametersOf(AlarmEditorArgs(alarmId = null)) }

        withScreen(PpsThemeMode.Light, content = {
            AlarmEditorRoute(alarmId = null, onClose = {}, onOpenFailed = {}, onOpenCopy = {}, viewModel = viewModel)
        }) {
            composeRule.onNodeWithText("Wake-up check").performClick()
            composeRule.onNodeWithText("Easy · 3 problems").performClick()
            composeRule.onNodeWithText("Try it").performClick()
            composeRule.waitForIdle()
            tap(7)
            composeRule.onNodeWithContentDescription("Answer 7").assertExists()

            composeRule.onNodeWithContentDescription("Back").performClick()
            composeRule.waitForIdle()

            composeRule.onNodeWithText("Problems").assertExists()
            composeRule.onNodeWithText("Check").assertDoesNotExist()
            composeRule.onNodeWithContentDescription("Answer 7").assertDoesNotExist()
        }
        assertEquals(SessionState.Idle, app.engine.state.value, "no session")
    }

    @Test
    fun `a digit typed during the wrong-answer shake leaves the answer field at rest (Story 3-6 review fix)`() {
        val app = WakeApp()
        val viewModel = app.koin.get<AlarmEditorViewModel> { parametersOf(AlarmEditorArgs(alarmId = null)) }

        withScreen(PpsThemeMode.Light, content = {
            AlarmEditorRoute(alarmId = null, onClose = {}, onOpenFailed = {}, onOpenCopy = {}, viewModel = viewModel)
        }) {
            composeRule.onNodeWithText("Wake-up check").performClick()
            composeRule.onNodeWithText("Easy · 3 problems").performClick()
            composeRule.onNodeWithText("Try it").performClick()
            composeRule.waitForIdle()
            // A digit that is surely wrong: an Easy problem (the default since 2026-10-08) can be "37 minus 37".
            val spoken =
                composeRule
                    .onNode(isHeading() and SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.ContentDescription]
                    .single()
            val wrong = if (evaluate(spoken) == 0) 9 else 0
            tap(wrong)
            val rest =
                composeRule
                    .onNodeWithContentDescription("Answer $wrong")
                    .fetchSemanticsNode()
                    .boundsInRoot.left

            composeRule.mainClock.autoAdvance = false
            composeRule.onNodeWithText("Check").performClick()
            composeRule.mainClock.advanceTimeBy(MID_SHAKE_MILLIS)
            composeRule.onNodeWithText("1").performClick()
            composeRule.mainClock.advanceTimeBy(FRAMES_MILLIS)

            val left =
                composeRule
                    .onNodeWithContentDescription("Answer 1")
                    .fetchSemanticsNode()
                    .boundsInRoot.left
            assertEquals(rest, left, "the field is back at rest, not left mid-shake")
            composeRule.mainClock.autoAdvance = true
        }
    }

    private fun tap(value: Int) = value.toString().forEach { digit -> composeRule.onNodeWithText(digit.toString()).performClick() }

    /** "23 times 4 plus 17" worked out, times before plus and minus. */
    private fun evaluate(spoken: String): Int {
        val tokens = spoken.split(' ')
        var sum = 0
        var sign = 1
        var term = tokens[0].toInt()
        var i = 1
        while (i < tokens.size) {
            val next = tokens[i + 1].toInt()
            when (tokens[i]) {
                "times" -> {
                    term *= next
                }

                else -> {
                    sum += sign * term
                    sign = if (tokens[i] == "plus") 1 else -1
                    term = next
                }
            }
            i += 2
        }
        return sum + sign * term
    }

    private companion object {
        /** Inside the 200 ms shake. */
        const val MID_SHAKE_MILLIS = 60L

        /** A few frames, far less than the rest of the shake. */
        const val FRAMES_MILLIS = 50L
    }
}
