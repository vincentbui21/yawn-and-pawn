package com.yawnandpawn.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import com.github.takahirom.roborazzi.captureRoboImage
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.checks.Puzzle
import com.yawnandpawn.app.screenshotOptions
import com.yawnandpawn.app.ui.TalkBackRules.isInside
import com.yawnandpawn.app.ui.checks.Difficulty
import com.yawnandpawn.app.ui.checks.toCore
import com.yawnandpawn.app.ui.format.formatClockTime
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import com.yawnandpawn.app.ui.wake.CheckContent
import com.yawnandpawn.app.ui.wake.CheckScreen
import com.yawnandpawn.app.ui.wake.CheckUiState
import com.yawnandpawn.app.ui.wake.FallbackPickerScreen
import com.yawnandpawn.app.ui.wake.GraceState
import com.yawnandpawn.app.ui.wake.MemoryPlayback
import com.yawnandpawn.app.ui.wake.RingingScreen
import com.yawnandpawn.app.ui.wake.SnoozeOffer
import com.yawnandpawn.app.ui.wake.SnoozeUnavailableReason
import com.yawnandpawn.app.ui.wake.SuccessKind
import com.yawnandpawn.app.ui.wake.SuccessScreen
import com.yawnandpawn.app.ui.wake.SuccessUiState
import com.yawnandpawn.app.ui.wake.WordInput
import com.yawnandpawn.app.ui.wake.WordRound
import com.yawnandpawn.app.ui.wake.fallbackPickerUiState
import com.yawnandpawn.app.ui.wake.memoryCheckContent
import com.yawnandpawn.app.ui.wake.memoryRound
import kotlinx.datetime.LocalTime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.test.assertTrue
import com.yawnandpawn.app.core.checks.CheckType as CoreCheckType

/**
 * Story 3.12, the 200% rule: on the smallest supported phone (360 × 640 dp) at 200% font, with the scroll at 0, every
 * check (in Grace, the tallest header, and in Loud) keeps its primary input, the fallback link when shown and the snooze
 * control fully inside the window; so do the Fallback check picker and Success. Ringing's clock stays capped at 1.3×.
 * Roborazzi records each (`a11y_*_w360_h640_font200`). Controls are found by label and role only.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi", fontScale = 2.0f)
class CheckFontScaleTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val snooze = SnoozeOffer.Unavailable(SnoozeUnavailableReason.PricesNotLoaded)
    private val snoozeControl = hasContentDescription("Snooze unavailable, prices not loaded yet")
    private val grace = GraceState.Running(secondsLeft = 14, totalSeconds = 20)
    private val link = hasText("Can't do this check?") and hasClickAction()

    private fun key(label: String) = hasText(label) and hasClickAction()

    private fun check(
        content: CheckContent,
        grace: GraceState?,
        showLink: Boolean = false,
    ) = CheckUiState(grace = grace, content = content, snooze = snooze, showFallbackLink = showLink)

    /** Shows [content], records it as `a11y_[name]_w360_h640_font200`, and checks [onScreen] lie fully in the window. */
    private fun fits(
        name: String,
        onScreen: List<SemanticsMatcher>,
        content: @Composable () -> Unit,
    ) = withScreen(PpsThemeMode.Light, content = content) {
        composeRule.onRoot().captureRoboImage(
            "src/test/screenshots/a11y_${name}_w360_h640_font200.png",
            roborazziOptions = screenshotOptions,
        )
        val window = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        onScreen.forEach { matcher ->
            val nodes = composeRule.onAllNodes(matcher)
            val count = nodes.fetchSemanticsNodes().size
            assertTrue(count > 0, "$name: no ${matcher.description}")
            (0 until count).forEach { index ->
                // Fully visible: inside the window, and not clipped by a scrolling area (its visible bounds are its size).
                val visible = nodes[index].fetchSemanticsNode().boundsInRoot
                val full = nodes[index].getUnclippedBoundsInRoot().toRect()
                assertTrue(
                    visible.isInside(window) && abs(visible.height - full.height) < 1f && abs(visible.width - full.width) < 1f,
                    "$name: ${matcher.description} shows $visible of $full in $window",
                )
            }
        }
    }

    private fun DpRect.toRect(): Rect = with(composeRule.density) { Rect(left.toPx(), top.toPx(), right.toPx(), bottom.toPx()) }

    private fun wake(
        name: String,
        state: CheckUiState,
        inputs: List<SemanticsMatcher>,
    ) = fits(name, inputs + snoozeControl + listOfNotNull(link.takeIf { state.showFallbackLink })) {
        CheckScreen(state = state, onIntent = {})
    }

    // Math: the pad and "Check" (Story 3.2's pinned pad).

    private val mathKeys = (0..9).map { key(it.toString()) } + key("Check") + hasContentDescription("Delete digit")

    @Test
    fun `Math Hard in grace`() = wake("math_grace", CheckSamples.hard, mathKeys)

    @Test
    fun `Math in loud with the wrong line`() = wake("math_loud", CheckSamples.wrong, mathKeys)

    // Word Unscramble: the pool, "Shuffle" and "Clear" (Story 3.7's pinned actions), on the longest word.

    private val restaurant = WordInput("rtaesutran").content(WordRound(2, 2, "rtaesutran"), wrong = false)
    private val wordInputs =
        "RTAESUTRAN".toSet().map { hasContentDescription("Letter $it") } + key("Shuffle") + key("Clear")

    @Test
    fun `Word in grace`() = wake("word_grace", check(restaurant, grace), wordInputs)

    @Test
    fun `Word in loud with the wrong line`() = wake("word_loud", check(restaurant.copy(wrong = true), GraceState.Expired), wordInputs)

    // Memory Sequence: the grid.

    private fun memory(
        difficulty: Difficulty,
        numbered: Boolean,
        wrong: Boolean = false,
    ): CheckContent.MemorySequence {
        val type = CoreCheckType.MemorySequence(numbered)
        val round = memoryRound(type, type.generate(SEED, difficulty.toCore(), 2) as Puzzle.Memory, 0)!!
        return memoryCheckContent(round, MemoryPlayback(round.sequence, frame = if (wrong) 0 else FRAMES_PLAYED), wrong)
    }

    private fun tiles(count: Int) = (1..count).map { hasContentDescription("Tile $it") }

    @Test
    fun `Memory numbered in grace`() = wake("memory_grace", check(memory(Difficulty.Medium, numbered = true), grace), tiles(9))

    @Test
    fun `Memory numbered in loud with the wrong line`() =
        wake("memory_loud", check(memory(Difficulty.Medium, numbered = true, wrong = true), GraceState.Expired), tiles(9))

    @Test
    fun `Memory Hard 4x4 in grace`() = wake("memory_hard_grace", check(memory(Difficulty.Hard, numbered = false), grace), tiles(16))

    // QR/Barcode: the viewfinder and the torch; without the camera, the message and the link.

    private val qrInputs = listOf(hasContentDescription("Camera viewfinder. Point at your code."), hasContentDescription("Torch"))

    @Test
    fun `QR in grace`() = wake("qr_grace", check(CheckContent.QrBarcode(), grace), qrInputs)

    @Test
    fun `QR in loud after 5 different codes, with the link`() =
        wake("qr_loud", check(CheckContent.QrBarcode(wrongCode = true, wrongAttempts = 5), GraceState.Expired, showLink = true), qrInputs)

    @Test
    fun `QR without the camera in grace, with the link`() =
        wake(
            "qr_unavailable_grace",
            check(CheckContent.QrBarcode(cameraAvailable = false), grace, showLink = true),
            listOf(hasText("Camera isn't available. Pick a fallback check.")),
        )

    // The Fallback check picker and Success.

    /**
     * The picker is a list: its close button and Math (always first, always available) are on screen without scrolling;
     * the other cards scroll into view (TalkBack scrolls the list by itself).
     */
    @Test
    fun `the Fallback check picker`() =
        fits("fallback_picker", listOf(hasContentDescription("Back to check"), hasText("Math") and hasClickAction())) {
            FallbackPickerScreen(state = fallbackPickerUiState(), onIntent = {})
        }.also {
            withScreen(PpsThemeMode.Light, content = { FallbackPickerScreen(state = fallbackPickerUiState(), onIntent = {}) }) {
                listOf("Word Unscramble", "Memory Sequence").forEach { name ->
                    composeRule.onNode(hasText(name) and hasClickAction()).performScrollTo().assertIsDisplayed()
                }
            }
        }

    @Test
    fun `Success`() =
        fits("success", listOf(key("Done"), hasText("You're up. That's what counts."))) {
            SuccessScreen(
                state = SuccessUiState(SuccessKind.AfterSnooze(paidThisMorning = null), pendingNotUsed = true),
                onIntent = {},
                basic = true,
            )
        }

    // Ringing: the clock capped at 1.3x.

    @Test
    fun `the Ringing clock stays within 1_3x of its 100 percent size`() {
        val time = formatClockTime(LocalTime(6, 15), is24Hour = false)
        var atScale = 0f
        var atOne = 0f
        withScreen(PpsThemeMode.Light, content = { RingingScreen(state = RingingSamples.firstRing, is24Hour = false, onIntent = {}) }) {
            atScale = composeRule.onNode(hasContentDescription(time)).getUnclippedBoundsInRoot().let { (it.bottom - it.top).value }
        }
        withScreen(
            PpsThemeMode.Light,
            content = {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1f)) {
                    RingingScreen(state = RingingSamples.firstRing, is24Hour = false, onIntent = {})
                }
            },
        ) {
            atOne = composeRule.onNode(hasContentDescription(time)).getUnclippedBoundsInRoot().let { (it.bottom - it.top).value }
        }
        assertTrue(atOne > 0f && atScale <= atOne * CLOCK_CAP + 1f, "the clock is $atScale dp at 200% and $atOne dp at 100%")
    }

    private companion object {
        const val SEED = 21L
        const val FRAMES_PLAYED = 64
        const val CLOCK_CAP = 1.3f
    }
}
