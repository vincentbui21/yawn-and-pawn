package com.yawnandpawn.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.screenshotOptions
import com.yawnandpawn.app.ui.checks.Difficulty
import com.yawnandpawn.app.ui.checks.WordTrial
import com.yawnandpawn.app.ui.checksetup.CheckPreviewScreen
import com.yawnandpawn.app.ui.theme.PpsThemeMode
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

/**
 * Story 3.7 screenshots of the Word Unscramble check as the wake screen and "Try it" show it: Easy, Hard (the tiles wrap)
 * and a wrong word, in Sunrise at 100% and 200% font scale on a 360 × 640 dp phone, with "Shuffle", "Clear" and the
 * snooze control on screen without scrolling. The words are fixed here; the app's list is in `words_en.txt`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class WordCheckScreenshotTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    /** "stone" scrambled, two letters placed. */
    private val easy = WordInput("tnseo").tappedLetter(3).tappedLetter(4).content(WordRound(1, 2, "tnseo"), wrong = false)

    /** "restaurant" scrambled: 10 tiles wrap onto a second row. */
    private val hard = WordInput("rtaesutran").tappedLetter(0).content(WordRound(2, 2, "rtaesutran"), wrong = false)

    /** A wrong word: the slots cleared, "Not quite. Try again.". */
    private val wrong = WordInput("dgenar").content(WordRound(1, 3, "dgenar"), wrong = true)

    private fun check(content: CheckContent) =
        CheckUiState(
            grace = GraceState.Expired,
            content = content,
            snooze = SnoozeOffer.Unavailable(SnoozeUnavailableReason.PricesNotLoaded),
        )

    private fun wake(
        name: String,
        content: CheckContent,
    ) = withScreen(PpsThemeMode.Light, content = { CheckScreen(state = check(content), onIntent = {}) }) {
        // On screen without scrolling: Shuffle, Clear and the snooze control.
        composeRule.onNodeWithText("Shuffle").assertIsDisplayed()
        composeRule.onNodeWithText("Clear").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Snooze unavailable, prices not loaded yet").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = screenshotOptions)
    }

    @Test
    fun `Easy`() = wake("wake_check_word_easy_sunrise", easy)

    @Test
    @Config(fontScale = 2.0f)
    fun `Easy at 200 percent`() = wake("wake_check_word_easy_sunrise_font200", easy)

    @Test
    fun `Hard, wrapped`() = wake("wake_check_word_hard_sunrise", hard)

    @Test
    @Config(fontScale = 2.0f)
    fun `Hard, wrapped, at 200 percent`() = wake("wake_check_word_hard_sunrise_font200", hard)

    @Test
    fun `a wrong word`() = wake("wake_check_word_wrong_sunrise", wrong)

    @Test
    @Config(fontScale = 2.0f)
    fun `a wrong word at 200 percent`() = wake("wake_check_word_wrong_sunrise_font200", wrong)

    @Test
    fun `TalkBack reads each letter and slot, and the answer so far`() {
        withScreen(PpsThemeMode.Light, content = { CheckScreen(state = check(easy), onIntent = {}) }) {
            composeRule.onNodeWithContentDescription("Letter T").assertExists()
            composeRule.onNodeWithContentDescription("Slot 1, E").assertExists()
            composeRule.onNodeWithContentDescription("Slot 3, empty").assertExists()
            composeRule.onNodeWithContentDescription("E O").assertExists()
        }
    }

    private fun tryIt(name: String) =
        withScreen(
            PpsThemeMode.Light,
            // The app's own list (installed at start from words_en.txt) and a fixed seed.
            content = { CheckPreviewScreen(state = WordTrial.start(Difficulty.Medium, seed = 4L)!!.state, onIntent = {}, onClose = {}) },
        ) {
            composeRule.onNodeWithText("Word 1 of 1").assertExists()
            composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = screenshotOptions)
        }

    @Test
    fun `Try it`() = tryIt("try_it_word_sunrise")

    @Test
    @Config(fontScale = 2.0f)
    fun `Try it at 200 percent`() = tryIt("try_it_word_sunrise_font200")
}
