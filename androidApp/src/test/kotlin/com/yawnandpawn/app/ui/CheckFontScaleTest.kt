package com.yawnandpawn.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
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
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.checks.Puzzle
import com.yawnandpawn.app.screenshotOptions
import com.yawnandpawn.app.ui.TalkBackRules.isInside
import com.yawnandpawn.app.ui.checks.Difficulty
import com.yawnandpawn.app.ui.checks.toCore
import com.yawnandpawn.app.ui.format.Money
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
import com.yawnandpawn.app.ui.wake.WakeMessage
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
 * Story 3.12, the 200% rule: on the smallest supported phone (360 × 640 dp, with a 24 dp status bar and a 48 dp
 * navigation bar) at 200% font, every check (in Grace, the tallest header, and in Loud) keeps its primary input, its
 * own text next to it (the problem's answer, the word's slots, the round and phase, "Not quite. Try again."), the
 * fallback link when shown and the snooze control fully inside the window without scrolling; so do the Fallback check
 * picker and Success. The footer is also tried at its largest: a price, a payment message and the link. Ringing's clock
 * stays capped at 1.3×. Roborazzi records each (`a11y_*_font200`). Controls are found by label and role only.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi", fontScale = 2.0f)
class CheckFontScaleTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val unavailable = SnoozeOffer.Unavailable(SnoozeUnavailableReason.PricesNotLoaded)
    private val price = SnoozeOffer.Available(Money(amountMicros = 12_990_000, currencyCode = "EUR"))
    private val grace = GraceState.Running(secondsLeft = 14, totalSeconds = 20)
    private val link = hasText("Can't do this check?") and hasClickAction()
    private val wrongLine = hasText("Not quite. Try again.")

    /** The one snooze control, whatever it shows (a price, or unavailable with its reason). */
    private val snoozeControl = SemanticsMatcher("the snooze control") { TalkBackRules.label(it).contains("nooze") }

    private fun key(label: String) = hasText(label) and hasClickAction()

    private fun check(
        content: CheckContent,
        grace: GraceState?,
        showLink: Boolean = false,
        snooze: SnoozeOffer = unavailable,
        message: WakeMessage? = null,
    ) = CheckUiState(grace = grace, content = content, snooze = snooze, showFallbackLink = showLink, message = message)

    /**
     * Shows [content] under a 24 dp status bar and over a 48 dp navigation bar (Robolectric gives no system bar insets,
     * so padding stands in for them), records it as `a11y_[name]_font200`, and checks every node of [onScreen] lies fully
     * between the bars, not clipped by a scrolling area.
     */
    private fun fits(
        name: String,
        onScreen: List<SemanticsMatcher>,
        content: @Composable () -> Unit,
    ) = withScreen(PpsThemeMode.Light, content = { Box(Modifier.padding(top = STATUS_BAR, bottom = NAVIGATION_BAR)) { content() } }) {
        composeRule.onRoot().captureRoboImage("src/test/screenshots/a11y_${name}_font200.png", roborazziOptions = screenshotOptions)
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val window =
            with(composeRule.density) { root.copy(top = root.top + STATUS_BAR.toPx(), bottom = root.bottom - NAVIGATION_BAR.toPx()) }
        onScreen.forEach { matcher ->
            val nodes = composeRule.onAllNodes(matcher)
            val count = nodes.fetchSemanticsNodes().size
            assertTrue(count > 0, "$name: no ${matcher.description}")
            (0 until count).forEach { index ->
                // Fully visible: inside the window, and not clipped by a scrolling area (its visible bounds are its size).
                // A line of text may lose at most its leading above the glyphs (a tenth of its box) at the area's edge.
                val node = nodes[index].fetchSemanticsNode()
                val visible = node.boundsInRoot
                val full = nodes[index].getUnclippedBoundsInRoot().toRect()
                val slack = if (node.config.contains(SemanticsProperties.Text)) full.height * TEXT_LEADING else 1f
                assertTrue(
                    visible.isInside(window) && full.height - visible.height < slack && abs(visible.width - full.width) < 1f,
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
    ) = fits("${name}_w360_h640", inputs + snoozeControl + listOfNotNull(link.takeIf { state.showFallbackLink })) {
        CheckScreen(state = state, onIntent = {})
    }

    // Math: the pad and "Check" (Story 3.2's pinned pad), with the answer field and the wrong line.

    private val mathKeys = (0..9).map { key(it.toString()) } + key("Check") + hasContentDescription("Delete digit")
    private val answer = SemanticsMatcher("the answer field") { TalkBackRules.label(it).startsWith("Answer") }

    @Test
    fun `Math Hard in grace`() = wake("math_grace", CheckSamples.hard, mathKeys + answer)

    @Test
    fun `Math in loud with the wrong line`() = wake("math_loud", CheckSamples.wrong, mathKeys + answer + wrongLine)

    // Word Unscramble: the slots, the wrong line, the letters, "Shuffle" and "Clear", on the longest word.

    private val restaurant = WordInput("rtaesutran").content(WordRound(2, 2, "rtaesutran"), wrong = false)
    private val slots = SemanticsMatcher("a slot") { TalkBackRules.label(it).startsWith("Slot ") }
    private val wordInputs = "RTAESUTRAN".toSet().map { hasContentDescription("Letter $it") } + key("Shuffle") + key("Clear") + slots

    @Test
    fun `Word in grace`() = wake("word_grace", check(restaurant, grace), wordInputs)

    @Test
    fun `Word in loud with the wrong line`() =
        wake("word_loud", check(restaurant.copy(wrong = true), GraceState.Expired), wordInputs + wrongLine)

    // Memory Sequence: the grid, the phase line and the wrong line.

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

    private val yourTurn = hasText("Your turn")
    private val watch = hasText("Watch the sequence")

    @Test
    fun `Memory numbered in grace`() = wake("memory_grace", check(memory(Difficulty.Medium, numbered = true), grace), tiles(9) + yourTurn)

    @Test
    fun `Memory numbered in loud with the wrong line`() =
        wake(
            "memory_loud",
            check(memory(Difficulty.Medium, numbered = true, wrong = true), GraceState.Expired),
            tiles(9) + watch + wrongLine,
        )

    @Test
    fun `Memory Hard 4x4 in grace`() =
        wake("memory_hard_grace", check(memory(Difficulty.Hard, numbered = false), grace), tiles(16) + yourTurn)

    @Test
    fun `Memory numbered in loud with a price and a payment message`() =
        wake(
            "memory_loud_price_message",
            check(memory(Difficulty.Medium, numbered = true), GraceState.Expired, snooze = price, message = WakeMessage.PaymentCancelled),
            tiles(9) + yourTurn,
        )

    @Test
    fun `Memory numbered in loud before the first unlock`() =
        wake(
            "memory_loud_locked",
            check(memory(Difficulty.Medium, numbered = true), GraceState.Expired, snooze = SnoozeOffer.LockedBeforeUnlock),
            tiles(9),
        )

    /**
     * A shorter phone (568 dp) with the link and a payment message: the 4×4 grid and the footer do not fit together, so
     * the grid scrolls with the rest at its full size instead of being squashed, and snooze and the link stay on screen.
     */
    @Test
    @Config(qualifiers = "w360dp-h568dp-mdpi")
    fun `Memory Hard in loud with the link and a message on a 568 dp phone scrolls its grid, never squashes it`() {
        val state =
            check(
                memory(Difficulty.Hard, numbered = false),
                GraceState.Expired,
                showLink = true,
                snooze = price,
                message = WakeMessage.PaymentCancelled,
            )
        fits("memory_hard_loud_link_message_w360_h568", listOf(snoozeControl, link)) { CheckScreen(state = state, onIntent = {}) }
        withScreen(
            PpsThemeMode.Light,
            content = { Box(Modifier.padding(top = STATUS_BAR, bottom = NAVIGATION_BAR)) { CheckScreen(state, {}) } },
        ) {
            (1..16).forEach { tile ->
                val node = composeRule.onNode(hasContentDescription("Tile $tile")).performScrollTo().assertIsDisplayed()
                val size = node.getUnclippedBoundsInRoot()
                assertTrue(size.right - size.left >= 64.dp && size.bottom - size.top >= 64.dp, "Tile $tile is $size")
            }
        }
    }

    // QR/Barcode: the viewfinder and the torch; without the camera, the message and the link.

    private val qrInputs = listOf(hasContentDescription("Camera viewfinder. Point at your code."), hasContentDescription("Torch"))

    @Test
    fun `QR in grace`() = wake("qr_grace", check(CheckContent.QrBarcode(), grace), qrInputs)

    /**
     * After 5 different codes the 240 dp viewfinder, the 3-line message, the link and snooze are taller than the window,
     * so the viewfinder scrolls with the rest (recorded for the owner with the v2 compact header): the link and snooze
     * stay on screen, and the viewfinder, its torch and the message scroll into view.
     */
    @Test
    fun `QR in loud after 5 different codes, with the link`() {
        val state = check(CheckContent.QrBarcode(wrongCode = true, wrongAttempts = 5), GraceState.Expired, showLink = true)
        wake("qr_loud", state, emptyList())
        withScreen(
            PpsThemeMode.Light,
            content = { Box(Modifier.padding(top = STATUS_BAR, bottom = NAVIGATION_BAR)) { CheckScreen(state, {}) } },
        ) {
            listOf(
                hasContentDescription("Torch"),
                hasText("That's a different code. Scan your registered one."),
                hasContentDescription("Camera viewfinder. Point at your code."),
            ).forEach { composeRule.onNode(it).performScrollTo().assertIsDisplayed() }
        }
    }

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
        fits("fallback_picker_w360_h640", listOf(hasContentDescription("Back to check"), hasText("Math") and hasClickAction())) {
            FallbackPickerScreen(state = fallbackPickerUiState(), onIntent = {})
        }.also {
            withScreen(PpsThemeMode.Light, content = { FallbackPickerScreen(state = fallbackPickerUiState(), onIntent = {}) }) {
                listOf("Word Unscramble", "Memory Sequence").forEach { name ->
                    composeRule.onNode(hasText(name) and hasClickAction()).performScrollTo().assertIsDisplayed()
                }
            }
        }

    @Test
    fun `Success after a paid snooze, with the pending payment note`() =
        fits(
            "success_w360_h640",
            listOf(key("Done"), hasText("You're up. That's what counts."), hasText("paid this morning", substring = true)),
        ) {
            SuccessScreen(
                state =
                    SuccessUiState(
                        SuccessKind.AfterSnooze(paidThisMorning = Money(amountMicros = 2_000_000, currencyCode = "EUR")),
                        pendingNotUsed = true,
                    ),
                onIntent = {},
                basic = true,
            )
        }

    /** On time with a streak: the production screen (basic until Epic 6) shows "Up on time." and "Done". */
    @Test
    fun `Success on time with a streak`() =
        fits("success_on_time_w360_h640", listOf(key("Done"), hasText("Up on time."))) {
            SuccessScreen(state = SuccessUiState(SuccessKind.OnTime(streakDays = 12)), onIntent = {}, basic = true)
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
        val STATUS_BAR = 24.dp
        val NAVIGATION_BAR = 48.dp

        /** The part of a text line's box that is leading above its glyphs, which a scrolling area may clip. */
        const val TEXT_LEADING = 0.1f
    }
}
