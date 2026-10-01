package com.yawnandpawn.app.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.ui.components.rememberReducedMotion
import com.yawnandpawn.app.ui.format.formatClockTime
import com.yawnandpawn.app.ui.theme.PpsTheme
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import com.yawnandpawn.app.ui.theme.PpsTokens
import com.yawnandpawn.app.ui.wake.RingingScreen
import com.yawnandpawn.app.ui.wake.RingingUiState
import kotlinx.datetime.LocalTime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Story 1.15 layout and accessibility rules of the Ringing screen, on a phone-sized window: the sizes and places of the
 * two wake actions at 100% and 200% font scale, TalkBack order and labels, the disabled-snooze tokens, and no motion
 * with the animator duration scale at 0.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-mdpi")
class RingingSemanticsTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val pricesNotLoaded = "Snooze unavailable, prices not loaded yet"

    private fun ringing(
        state: RingingUiState = RingingSamples.firstRing,
        block: () -> Unit,
    ) = withScreen(PpsThemeMode.Light, content = { RingingScreen(state = state, is24Hour = false, onIntent = {}) }, block = block)

    private fun imUp() = composeRule.onNodeWithText("I'm up")

    private fun snooze() = composeRule.onNodeWithContentDescription(pricesNotLoaded)

    private val DpRect.heightDp: Dp get() = bottom - top

    private fun SemanticsNodeInteraction.traversalIndex(): Float =
        fetchSemanticsNode().config.getOrNull(SemanticsProperties.TraversalIndex) ?: 0f

    /**
     * Both wake actions are on screen, "I'm up" (72 dp) in the bottom 40%, snooze (64 dp) 16 dp below it. The snooze
     * label "Snooze unavailable: prices not loaded yet" wraps to two lines, so its height grows with the font.
     */
    private fun assertThumbZone(snooze: SemanticsNodeInteraction = snooze()) {
        val root = composeRule.onRoot().getBoundsInRoot()
        val up = imUp().getBoundsInRoot()
        val snooze = snooze.getBoundsInRoot()

        assertTrue(up.heightDp >= 72.dp, "I'm up is ${up.heightDp}")
        assertTrue(snooze.heightDp >= 64.dp, "snooze is ${snooze.heightDp}")
        assertTrue(up.top >= root.top + (root.bottom - root.top) * 0.6f, "I'm up (top ${up.top}) is in the bottom 40% of ${root.bottom}")
        assertTrue(abs((snooze.top - up.bottom).value - 16f) <= 1f, "snooze sits 16 dp below I'm up, was ${snooze.top - up.bottom}")
        assertTrue(snooze.bottom <= root.bottom, "snooze is on screen without scrolling")
    }

    @Test
    fun `I'm up and snooze sit in the thumb zone at their sizes`() = ringing { assertThumbZone() }

    @Test
    @Config(fontScale = 2.0f)
    fun `at 200 percent both actions stay on screen in the thumb zone and nothing clips`() =
        ringing {
            assertThumbZone()
            composeRule.onNodeWithText("Work").assertExists()
        }

    @Test
    fun `with a one-line snooze label I'm up is the tallest action on screen`() =
        ringing(RingingSamples.testAlarm) {
            val test = composeRule.onNodeWithContentDescription("Snooze unavailable, Test · no charge")
            assertThumbZone(test)
            val up = imUp().getBoundsInRoot()
            assertTrue(up.heightDp > test.getBoundsInRoot().heightDp, "I'm up is taller than snooze")
            composeRule.onAllNodes(hasClickAction()).fetchSemanticsNodes().forEach { node ->
                assertTrue(node.boundsInRoot.height <= up.heightDp.value * PIXELS_PER_DP, "no action is taller than I'm up")
            }
        }

    @Test
    fun `TalkBack reads the clock as the full time first, then I'm up, and the snooze with its reason`() =
        ringing {
            val clock = composeRule.onNodeWithContentDescription(formatClockTime(LocalTime(6, 15), is24Hour = false))
            assertTrue(
                clock
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.ContentDescription]
                    .single()
                    .endsWith("AM"),
            )

            val order = listOf(clock.traversalIndex(), imUp().traversalIndex(), composeRule.onNodeWithText("Work").traversalIndex())
            assertEquals(order.sorted(), order, "clock, then I'm up, then the rest")
            assertTrue(order[0] < order[1] && order[1] < order[2], "strict order $order")
            snooze().assertExists()
        }

    @Test
    fun `a test alarm's snooze is read as Snooze unavailable, Test no charge`() =
        ringing(RingingSamples.testAlarm) {
            composeRule.onNodeWithContentDescription("Snooze unavailable, Test · no charge").assertExists()
        }

    @Test
    fun `the disabled snooze is filled with the disabled-container-sunrise token, not an alpha`() {
        var content = Color.Unspecified
        withScreen(
            PpsThemeMode.Light,
            content = {
                RingingScreen(state = RingingSamples.firstRing, is24Hour = false, onIntent = {})
                PpsTheme(wake = true) { content = PpsTheme.colors.disabledContent }
            },
        ) {
            val pixels = snooze().captureToImage().toPixelMap()

            // The vertical padding above the label, mid-width: pure container colour.
            assertEquals(PpsTokens.Sunrise.disabledContainer, pixels[pixels.width / 2, pixels.height / 8])
            assertEquals(PpsTokens.Sunrise.disabledContent, content)
        }
    }

    /**
     * The compose test clock never runs infinite animations, so the pulse itself can't be seen moving here: the test
     * checks that the screen's reduced-motion switch (which turns the pulse off) is on, and that no other transition
     * changes a frame. The pulse on a real phone is checked in Story 1.21.
     */
    @Test
    fun `with the animator duration scale at 0 the pulse is off and the screen does not move`() {
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        var reduced = false
        withScreen(
            PpsThemeMode.Light,
            content = {
                reduced = rememberReducedMotion()
                RingingScreen(state = RingingSamples.firstRing, is24Hour = false, onIntent = {})
            },
        ) {
            composeRule.waitForIdle()
            assertTrue(reduced, "reduced motion: the I'm up pulse is off")
            composeRule.mainClock.autoAdvance = false
            composeRule.mainClock.advanceTimeBy(SETTLE_MILLIS)
            val first = composeRule.onRoot().captureToImage().toPixelMap()
            composeRule.mainClock.advanceTimeBy(PULSE_HALF_MILLIS)
            val later = composeRule.onRoot().captureToImage().toPixelMap()

            assertEquals(first.buffer.toList(), later.buffer.toList(), "no pulse, no transition")
        }
    }

    private companion object {
        /** mdpi: one pixel per dp. */
        const val PIXELS_PER_DP = 1f

        /** Half the "I'm up" pulse (1.2 s), where it would be at its largest. */
        const val PULSE_HALF_MILLIS = 600L

        /** Lets the first frames (fonts, insets) settle before the first picture. */
        const val SETTLE_MILLIS = 100L
    }
}
