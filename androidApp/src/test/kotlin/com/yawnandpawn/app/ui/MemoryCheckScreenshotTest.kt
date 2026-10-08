package com.yawnandpawn.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.checks.Puzzle
import com.yawnandpawn.app.screenshotOptions
import com.yawnandpawn.app.ui.checks.Difficulty
import com.yawnandpawn.app.ui.checks.MemoryTrial
import com.yawnandpawn.app.ui.checks.toCore
import com.yawnandpawn.app.ui.checksetup.CheckPreviewScreen
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import com.yawnandpawn.app.ui.wake.CheckContent
import com.yawnandpawn.app.ui.wake.CheckScreen
import com.yawnandpawn.app.ui.wake.CheckUiState
import com.yawnandpawn.app.ui.wake.GraceState
import com.yawnandpawn.app.ui.wake.MemoryPlayback
import com.yawnandpawn.app.ui.wake.SnoozeOffer
import com.yawnandpawn.app.ui.wake.SnoozeUnavailableReason
import com.yawnandpawn.app.ui.wake.memoryCheckContent
import com.yawnandpawn.app.ui.wake.memoryRound
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.yawnandpawn.app.core.checks.CheckType as CoreCheckType

/**
 * Story 3.8 screenshots of the Memory Sequence check as the wake screen and "Try it" show it: 3x3 while it plays, 4x4
 * (Hard) on the user's turn, the numbered TalkBack variant, a lit tile and a wrong tap, in Sunrise at 100% and 200% font
 * scale on a 360 dp phone; and the tiles' size.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h780dp-mdpi")
class MemoryCheckScreenshotTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val seed = 21L

    private fun content(
        difficulty: Difficulty,
        frame: Int = 0,
        numbered: Boolean = false,
        wrong: Boolean = false,
    ): CheckContent.MemorySequence {
        val type = CoreCheckType.MemorySequence(numbered)
        val round = memoryRound(type, type.generate(seed, difficulty.toCore(), 2) as Puzzle.Memory, 0)!!
        return memoryCheckContent(round, MemoryPlayback(round.sequence, frame = frame), wrong)
    }

    private fun check(content: CheckContent) =
        CheckUiState(
            grace = GraceState.Running(secondsLeft = 14, totalSeconds = 20),
            content = content,
            snooze = SnoozeOffer.Unavailable(SnoozeUnavailableReason.PricesNotLoaded),
        )

    private fun wake(
        name: String,
        content: CheckContent,
        expected: String,
    ) = withScreen(PpsThemeMode.Light, content = { CheckScreen(state = check(content), onIntent = {}) }) {
        composeRule.onNodeWithText(expected).assertExists()
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = screenshotOptions)
    }

    private val playing get() = content(Difficulty.Medium)
    private val hardTurn get() = content(Difficulty.Hard, frame = 16)
    private val numbered get() = content(Difficulty.Medium, frame = 12, numbered = true)
    private val litGap get() = content(Difficulty.Easy, frame = 2)
    private val wrong get() = content(Difficulty.Medium, frame = 0, wrong = true)

    @Test
    fun `3x3 while the sequence plays`() = wake("wake_check_memory_watch_sunrise", playing, "Watch the sequence")

    @Test
    @Config(fontScale = 2.0f)
    fun `3x3 while the sequence plays at 200 percent`() = wake("wake_check_memory_watch_sunrise_font200", playing, "Watch the sequence")

    @Test
    fun `4x4 Hard on the user's turn`() = wake("wake_check_memory_hard_sunrise", hardTurn, "Your turn")

    @Test
    @Config(fontScale = 2.0f)
    fun `4x4 Hard on the user's turn at 200 percent`() = wake("wake_check_memory_hard_sunrise_font200", hardTurn, "Your turn")

    @Test
    fun `numbered TalkBack variant`() = wake("wake_check_memory_numbered_sunrise", numbered, "Your turn")

    @Test
    @Config(fontScale = 2.0f)
    fun `numbered TalkBack variant at 200 percent`() = wake("wake_check_memory_numbered_sunrise_font200", numbered, "Your turn")

    @Test
    fun `playback, the second tile lit`() = wake("wake_check_memory_playback_sunrise", litGap, "Watch the sequence")

    @Test
    @Config(fontScale = 2.0f)
    fun `playback, the second tile lit at 200 percent`() = wake("wake_check_memory_playback_sunrise_font200", litGap, "Watch the sequence")

    @Test
    fun `a wrong tap`() = wake("wake_check_memory_wrong_sunrise", wrong, "Not quite. Try again.")

    @Test
    @Config(fontScale = 2.0f)
    fun `a wrong tap at 200 percent`() = wake("wake_check_memory_wrong_sunrise_font200", wrong, "Not quite. Try again.")

    private fun tryIt(name: String) =
        withScreen(
            PpsThemeMode.Light,
            content = { CheckPreviewScreen(state = MemoryTrial.start(Difficulty.Medium, seed).state, onIntent = {}, onClose = {}) },
        ) {
            composeRule.onNodeWithText("Watch the sequence").assertExists()
            composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = screenshotOptions)
        }

    @Test
    fun `Try it`() = tryIt("try_it_memory_sunrise")

    @Test
    @Config(fontScale = 2.0f)
    fun `Try it at 200 percent`() = tryIt("try_it_memory_sunrise_font200")

    @Test
    @Config(fontScale = 2.0f)
    fun `every 4x4 tile is at least 64 dp and on screen, with the snooze control, at 200 percent on a 360 dp phone`() {
        withScreen(PpsThemeMode.Light, content = { CheckScreen(state = check(hardTurn), onIntent = {}) }) {
            val root = composeRule.onRoot().getBoundsInRoot()
            (1..16).forEach { tile ->
                val bounds = composeRule.onNodeWithContentDescription("Tile $tile").getBoundsInRoot()
                assertTrue(bounds.right - bounds.left >= 64.dp && bounds.bottom - bounds.top >= 64.dp, "Tile $tile $bounds")
                assertTrue(bounds.right <= root.right, "Tile $tile fits the width")
            }
            composeRule.onNodeWithContentDescription("Snooze unavailable, prices not loaded yet").assertExists()
        }
    }

    @Test
    fun `the numbered variant announces its round as numbers while it plays`() {
        val watching = content(Difficulty.Easy, numbered = true)
        withScreen(PpsThemeMode.Light, content = { CheckScreen(state = check(watching), onIntent = {}) }) {
            val spoken = watching.announced!!.joinToString(", ")
            val node = composeRule.onAllNodesWithContentDescription(spoken).fetchSemanticsNodes().single()
            // Review fix: a polite live region with a real size (a 0x0 node never reaches TalkBack).
            assertEquals(LiveRegionMode.Polite, node.config[SemanticsProperties.LiveRegion])
            assertTrue(node.boundsInRoot.width > 0f && node.boundsInRoot.height > 0f, "${node.boundsInRoot}")
        }
    }

    @Test
    fun `the grid stays in place when Not quite appears and goes (Epic 3 device check)`() {
        var shown by mutableStateOf(content(Difficulty.Medium, frame = 0))
        withScreen(PpsThemeMode.Light, content = { CheckScreen(state = check(shown), onIntent = {}) }) {
            composeRule.onNodeWithText("Not quite. Try again.").assertDoesNotExist()
            val before = composeRule.onNodeWithContentDescription("Tile 1").getBoundsInRoot().top

            composeRule.runOnUiThread { shown = content(Difficulty.Medium, frame = 0, wrong = true) }
            composeRule.onNodeWithText("Not quite. Try again.").assertExists()
            assertEquals(before, composeRule.onNodeWithContentDescription("Tile 1").getBoundsInRoot().top, "no jump down")

            composeRule.runOnUiThread { shown = content(Difficulty.Medium, frame = 0) }
            composeRule.onNodeWithText("Not quite. Try again.").assertDoesNotExist()
            assertEquals(before, composeRule.onNodeWithContentDescription("Tile 1").getBoundsInRoot().top, "no jump up")
        }
    }

    @Test
    @Config(fontScale = 2.0f)
    fun `at 200 percent the pinned grid moves at most a few dp when Not quite appears, never a whole line (Epic 3 device check)`() {
        var shown by mutableStateOf(content(Difficulty.Medium, frame = 0))
        withScreen(PpsThemeMode.Light, content = { CheckScreen(state = check(shown), onIntent = {}) }) {
            val before = composeRule.onNodeWithContentDescription("Tile 1").getBoundsInRoot().top

            composeRule.runOnUiThread { shown = content(Difficulty.Medium, frame = 0, wrong = true) }
            composeRule.onNodeWithText("Not quite. Try again.").assertExists()

            // The area above the pinned grid scrolls here, so the line's room is not kept (it would push "Your turn" out
            // of view); the grid only takes up what little slack the area had left.
            val moved = composeRule.onNodeWithContentDescription("Tile 1").getBoundsInRoot().top - before
            assertTrue(moved <= 8.dp, "moved $moved")
        }
    }

    @Test
    fun `the grid stays in place when the numbered sequence starts and ends (review fix)`() {
        var shown by mutableStateOf(content(Difficulty.Easy, numbered = true))
        withScreen(PpsThemeMode.Light, content = { CheckScreen(state = check(shown), onIntent = {}) }) {
            val watchingTop = composeRule.onNodeWithContentDescription("Tile 1").getBoundsInRoot().top

            composeRule.runOnUiThread { shown = content(Difficulty.Easy, frame = 8, numbered = true) }
            composeRule.onNodeWithText("Your turn").assertExists()

            assertEquals(watchingTop, composeRule.onNodeWithContentDescription("Tile 1").getBoundsInRoot().top)
        }
    }
}
