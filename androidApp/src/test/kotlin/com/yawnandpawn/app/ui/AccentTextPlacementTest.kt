package com.yawnandpawn.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.ui.checks.Difficulty
import com.yawnandpawn.app.ui.checks.WordTrial
import com.yawnandpawn.app.ui.checksetup.CheckPreviewScreen
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import com.yawnandpawn.app.ui.theme.PpsTokens
import com.yawnandpawn.app.ui.wake.CheckContent
import com.yawnandpawn.app.ui.wake.CheckScreen
import com.yawnandpawn.app.ui.wake.CheckUiState
import com.yawnandpawn.app.ui.wake.GraceState
import com.yawnandpawn.app.ui.wake.SnoozeOffer
import com.yawnandpawn.app.ui.wake.SnoozeUnavailableReason
import com.yawnandpawn.app.ui.wake.WordInput
import com.yawnandpawn.app.ui.wake.WordRound
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.test.assertTrue

/**
 * Story 3.12, the fallback link contrast question: `accent-text` passes on `bg-sunrise` (5.06) but fails on
 * `sunrise-gradient-top` (4.39), and the wake background fades from one to the other over the top 40% of the screen. So
 * every `accent-text` label on a check screen ("Can't do this check?", "Shuffle", "Clear", also in "Try it") is checked
 * where it actually sits: the rendered background around it must give at least 4.5:1 (Story 3.12 review: read from the
 * captured screen, not from a copy of the gradient's formula).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccentTextPlacementTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val snooze = SnoozeOffer.Unavailable(SnoozeUnavailableReason.PricesNotLoaded)
    private val link = hasText("Can't do this check?") and hasClickAction()
    private val actions = listOf(hasText("Shuffle") and hasClickAction(), hasText("Clear") and hasClickAction())

    private val qrWithLink =
        CheckUiState(
            grace = GraceState.Running(14, 20),
            content = CheckContent.QrBarcode(cameraAvailable = false),
            snooze = snooze,
            showFallbackLink = true,
        )
    private val word =
        CheckUiState(
            grace = GraceState.Expired,
            content = WordInput("rtaesutran").content(WordRound(1, 2, "rtaesutran"), wrong = false),
            snooze = snooze,
            showFallbackLink = true,
        )

    /**
     * The rendered background behind each label: the screen is captured, and the pixels just inside the four corners of
     * the label's touch target (the text button has no fill, and its text sits in the middle) are read. The worst of
     * them against `accent-text` must give at least 4.5:1.
     */
    private fun assertPasses(
        what: String,
        matchers: List<SemanticsMatcher>,
    ) {
        matchers.forEach { matcher ->
            // A label in a scrolling area is read where it sits once scrolled into view (a pinned one has no scroll).
            runCatching { composeRule.onNode(matcher).performScrollTo() }
            val screen = composeRule.onRoot().captureToImage().toPixelMap()
            val bounds = composeRule.onNode(matcher).fetchSemanticsNode().boundsInRoot
            val corners =
                listOf(bounds.left + INSET to bounds.top + INSET, bounds.right - INSET to bounds.top + INSET) +
                    listOf(bounds.left + INSET to bounds.bottom - INSET, bounds.right - INSET to bounds.bottom - INSET)
            val worst = corners.minOf { (x, y) -> contrast(PpsTokens.Sunrise.accentText, screen[x.toInt(), y.toInt()]) }
            assertTrue(worst >= TEXT_MIN, "$what: ${matcher.description} at $bounds is $worst:1 on the rendered background")
        }
    }

    private fun wake(
        state: CheckUiState,
        matchers: List<SemanticsMatcher>,
    ) = withScreen(PpsThemeMode.Light, content = { CheckScreen(state = state, onIntent = {}) }) { assertPasses("wake", matchers) }

    private fun tryItWord() =
        withScreen(
            PpsThemeMode.Light,
            content = { CheckPreviewScreen(state = WordTrial.start(Difficulty.Hard, seed = 4L)!!.state, onIntent = {}, onClose = {}) },
        ) { assertPasses("Try it", actions) }

    @Test
    @Config(qualifiers = "w411dp-h891dp-mdpi")
    fun `on a typical phone`() {
        wake(qrWithLink, listOf(link))
        wake(word, actions + link)
        tryItWord()
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-mdpi", fontScale = 2.0f)
    fun `on a 360 x 640 phone at 200 percent`() {
        wake(qrWithLink, listOf(link))
        wake(word, actions + link)
        tryItWord()
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-mdpi")
    fun `in landscape`() {
        wake(qrWithLink, listOf(link))
        wake(word, actions + link)
        tryItWord()
    }

    /** WCAG 2.x contrast, as the Story 1.3 contrast table computes it. */
    private fun contrast(
        a: Color,
        b: Color,
    ): Double {
        fun channel(value: Float): Double {
            val c = (value * MAX_CHANNEL).roundToInt() / MAX_CHANNEL.toDouble()
            return if (c <= LINEAR_LIMIT) c / LINEAR_DIVISOR else ((c + OFFSET) / (1 + OFFSET)).pow(GAMMA)
        }

        fun luminance(color: Color) = RED * channel(color.red) + GREEN * channel(color.green) + BLUE * channel(color.blue)
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + FLARE) / (min(la, lb) + FLARE)
    }

    private companion object {
        /** How far inside the touch target the background is read, in px. */
        const val INSET = 3f
        const val TEXT_MIN = 4.5
        const val MAX_CHANNEL = 255f
        const val LINEAR_LIMIT = 0.03928
        const val LINEAR_DIVISOR = 12.92
        const val OFFSET = 0.055
        const val GAMMA = 2.4
        const val RED = 0.2126
        const val GREEN = 0.7152
        const val BLUE = 0.0722
        const val FLARE = 0.05
    }
}
