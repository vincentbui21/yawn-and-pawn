package com.yawnandpawn.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.testing.aSession
import com.yawnandpawn.app.testing.aSessionConfig
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import com.yawnandpawn.app.ui.wake.SuccessScreen
import com.yawnandpawn.app.ui.wake.SuccessUiState
import com.yawnandpawn.app.ui.wake.WakeIntent
import com.yawnandpawn.app.ui.wake.successUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The basic Success states, built the way `WakeActivity` builds them (Story 3.3). */
object SuccessSamples {
    val onTime: SuccessUiState = successUiState(aSession())
    val afterSnooze: SuccessUiState = successUiState(aSession().copy(snoozesGranted = 2))
    val test: SuccessUiState = successUiState(aSession(config = aSessionConfig(testMode = true)))

    /** Each sample with its headline. */
    val all: List<Pair<SuccessUiState, String>> =
        listOf(onTime to "Up on time.", afterSnooze to "You're up. That's what counts.", test to "Test finished. Your alarm works.")
}

/**
 * Story 3.3 layout, accessibility and behaviour of the basic Success screen on a phone-sized window: the 72 dp "Done"
 * in the thumb zone at 100% and 200%, TalkBack order, no paid line, no motion, and one success haptic.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class SuccessScreenTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val haptics = RecordingHaptics()
    private val intents = mutableListOf<WakeIntent>()

    private fun success(
        state: SuccessUiState,
        block: () -> Unit,
    ) = withScreen(
        PpsThemeMode.Light,
        content = {
            CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                SuccessScreen(state = state, onIntent = { intents += it }, basic = true)
            }
        },
        block = block,
    )

    private fun done() = composeRule.onNodeWithText("Done")

    private fun assertDoneInThumbZone() {
        val root = composeRule.onRoot().getBoundsInRoot()
        val done = done().getBoundsInRoot()
        assertTrue(done.bottom - done.top >= 72.dp, "Done is ${done.bottom - done.top}")
        assertTrue(done.right - done.left >= root.right - root.left - 24.dp * 2, "Done is full width: $done in $root")
        assertTrue(done.top >= root.top + (root.bottom - root.top) * 0.6f, "Done (top ${done.top}) is in the bottom 40% of ${root.bottom}")
        assertTrue(done.bottom <= root.bottom, "Done is on screen without scrolling")
    }

    @Test
    fun `each variant shows its headline and a 72 dp Done in the thumb zone`() =
        SuccessSamples.all.forEach { (state, headline) ->
            success(state) {
                composeRule.onNodeWithText(headline).assertExists()
                assertDoneInThumbZone()
            }
        }

    @Test
    @Config(fontScale = 2.0f)
    fun `at 200 percent on a 360 by 640 dp phone Done stays on screen in the thumb zone`() =
        SuccessSamples.all.forEach { (state, headline) ->
            success(state) {
                composeRule.onNodeWithText(headline).assertExists()
                assertDoneInThumbZone()
            }
        }

    @Test
    fun `TalkBack reads the headline first, as a heading, then Done`() =
        SuccessSamples.all.forEach { (state, headline) ->
            success(state) {
                val readable =
                    SemanticsMatcher("read by TalkBack") {
                        SemanticsProperties.Text in it.config || SemanticsProperties.ContentDescription in it.config
                    }
                val order =
                    composeRule
                        .onAllNodes(readable)
                        .fetchSemanticsNodes()
                        .map { it.config.getOrNull(SemanticsProperties.Text)?.joinToString() }
                assertEquals(listOf(headline, "Done"), order, "reading order")
                composeRule.onNodeWithText(headline).assert(isHeading())
            }
        }

    @Test
    fun `after a snooze there is no paid line before Epic 4`() =
        success(SuccessSamples.afterSnooze) {
            composeRule.onNodeWithText("paid this morning", substring = true).assertDoesNotExist()
        }

    @Test
    fun `nothing moves, the screen at 32 ms and at 1 s is the same picture`() =
        success(SuccessSamples.onTime) {
            composeRule.mainClock.autoAdvance = false
            composeRule.mainClock.advanceTimeBy(FRAME)
            val start = composeRule.onRoot().captureToImage().toPixelMap()
            composeRule.mainClock.advanceTimeBy(ONE_SECOND)
            val later = composeRule.onRoot().captureToImage().toPixelMap()

            assertEquals(start.width * start.height, later.width * later.height)
            val changed = (0 until start.height).sumOf { y -> (0 until start.width).count { x -> start[x, y] != later[x, y] } }
            assertEquals(0, changed, "pixels changed between the two moments")
        }

    @Test
    fun `the success haptic plays once for each variant`() =
        SuccessSamples.all.forEach { (state, _) ->
            haptics.played.clear()
            success(state) {
                composeRule.waitForIdle()
                assertEquals(listOf(HapticFeedbackType.Confirm), haptics.played, "for $state")
            }
        }

    @Test
    fun `Done sends DoneClicked`() =
        success(SuccessSamples.onTime) {
            done().performClick()
            assertEquals(listOf<WakeIntent>(WakeIntent.DoneClicked), intents)
        }

    private companion object {
        const val FRAME = 32L
        const val ONE_SECOND = 1_000L
    }
}

/** A [HapticFeedback] that records what was played. */
private class RecordingHaptics : HapticFeedback {
    val played = mutableListOf<HapticFeedbackType>()

    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        played += hapticFeedbackType
    }
}
