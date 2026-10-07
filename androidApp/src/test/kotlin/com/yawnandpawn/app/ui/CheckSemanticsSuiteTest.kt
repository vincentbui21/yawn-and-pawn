package com.yawnandpawn.app.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.checks.Puzzle
import com.yawnandpawn.app.core.checks.qr.CodeFormat
import com.yawnandpawn.app.core.checks.qr.RegisteredCode
import com.yawnandpawn.app.ui.TalkBackRules.assertControlsLabelledWithRoles
import com.yawnandpawn.app.ui.TalkBackRules.assertOneHeadingBefore
import com.yawnandpawn.app.ui.TalkBackRules.assertPolite
import com.yawnandpawn.app.ui.TalkBackRules.assertReadsInOrder
import com.yawnandpawn.app.ui.TalkBackRules.headings
import com.yawnandpawn.app.ui.checks.CheckRegistry
import com.yawnandpawn.app.ui.checks.CheckType
import com.yawnandpawn.app.ui.checks.Difficulty
import com.yawnandpawn.app.ui.checks.toCore
import com.yawnandpawn.app.ui.checksetup.CheckPreviewScreen
import com.yawnandpawn.app.ui.checksetup.CheckPreviewUiState
import com.yawnandpawn.app.ui.format.Money
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import com.yawnandpawn.app.ui.wake.CheckContent
import com.yawnandpawn.app.ui.wake.CheckScreen
import com.yawnandpawn.app.ui.wake.CheckUiState
import com.yawnandpawn.app.ui.wake.FallbackPickerScreen
import com.yawnandpawn.app.ui.wake.GraceState
import com.yawnandpawn.app.ui.wake.MemoryPhase
import com.yawnandpawn.app.ui.wake.MemoryPlayback
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import com.yawnandpawn.app.core.checks.CheckType as CoreCheckType

/**
 * Story 3.12: one semantics suite over every check screen (Math Easy and Hard, Word Unscramble, numbered Memory Sequence
 * while it plays and on the user's turn, QR/Barcode scanning, with a different code and without the camera), the
 * Fallback check picker, "Try it" for each check and every Success variant. Nodes are found by label, role, heading and
 * reading order only: every control has a label and a role, the problem or instruction is the one heading before the
 * first input, feedback and the countdown are polite live regions, and focus goes back to the first input after a wrong
 * answer.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-mdpi")
class CheckSemanticsSuiteTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val snooze = SnoozeOffer.Unavailable(SnoozeUnavailableReason.PricesNotLoaded)
    private val snoozeSpoken = "Snooze unavailable, prices not loaded yet"

    private fun check(
        content: CheckContent,
        grace: GraceState? = GraceState.Expired,
        link: Boolean = false,
    ) = CheckUiState(grace = grace, content = content, snooze = snooze, showFallbackLink = link)

    private fun wake(
        state: CheckUiState,
        block: () -> Unit,
    ) = withScreen(PpsThemeMode.Light, content = { CheckScreen(state = state, onIntent = {}) }, block = block)

    private val key = { label: String -> hasText(label) and hasClickAction() }

    private fun memory(
        frame: Int,
        wrong: Boolean = false,
        attempts: Int = 0,
    ): CheckContent.MemorySequence {
        val type = CoreCheckType.MemorySequence(numbered = true)
        val round = memoryRound(type, type.generate(SEED, Difficulty.Medium.toCore(), 2) as Puzzle.Memory, 0)!!
        return memoryCheckContent(round, MemoryPlayback(round.sequence, frame = frame), wrong, attempts)
    }

    private val word = WordInput("tnseo").content(WordRound(1, 2, "tnseo"), wrong = false)

    // Math

    @Test
    fun `Math Easy and Hard read the problem as a heading in words, the keys by label and the answer politely`() {
        listOf(CheckSamples.easy, CheckSamples.hard).forEach { state ->
            val math = state.content as CheckContent.Math
            wake(state) {
                composeRule.assertControlsLabelledWithRoles()
                val heading = composeRule.headings().single()
                assertTrue(SPOKEN_PROBLEM.matches(heading), "the problem in words: $heading")
                composeRule.assertOneHeadingBefore(heading, key("1"))
                composeRule.assertPolite("Answer ${math.answer}".trim())
                composeRule.assertReadsInOrder(
                    "Problem ${math.problemNumber} of ${math.problemCount}",
                    heading,
                    "Answer",
                    "1",
                    "2",
                    "3",
                    "4",
                    "5",
                    "6",
                    "7",
                    "8",
                    "9",
                    "Delete digit",
                    "0",
                    "Check",
                    snoozeSpoken,
                )
            }
        }
    }

    @Test
    fun `the grace header reads before the check, the expired line and the countdown politely`() {
        wake(CheckSamples.grace5) {
            composeRule.assertReadsInOrder("5 seconds left", "Quiet for 5s", "Problem 1 of 3")
            // The announcement node, polite, with text at an announced second only.
            val announced =
                composeRule
                    .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion) and hasContentDescription("5 seconds left"))
                    .fetchSemanticsNodes()
            assertEquals(1, announced.size)
        }
        wake(CheckSamples.loud) {
            composeRule.assertPolite("Time's up. Alarm's back on until you finish.")
            composeRule.assertReadsInOrder("Time's up", "Problem 1 of 3")
        }
    }

    @Test
    fun `after each wrong Math answer focus goes back to the 1 key, and the wrong line is polite`() {
        val start = CheckSamples.loud
        val math = start.content as CheckContent.Math
        var shown by mutableStateOf(start)
        val elsewhere = FocusRequester()
        withScreen(
            PpsThemeMode.Light,
            content = {
                Box {
                    CheckScreen(state = shown, onIntent = {})
                    Box(Modifier.size(1.dp).focusRequester(elsewhere).focusable())
                }
            },
        ) {
            composeRule.onNode(key("1")).assertIsNotFocused()

            composeRule.runOnUiThread { shown = start.copy(content = math.copy(wrong = true, wrongAttempts = 1)) }
            composeRule.onNode(key("1")).assertIsFocused()
            composeRule.assertPolite("Not quite. Try again.")

            // Typed again (the wrong line goes), focus moved elsewhere, then a second wrong answer: back on "1".
            composeRule.runOnUiThread {
                shown = start.copy(content = math.copy(answer = "4", wrongAttempts = 1))
                elsewhere.requestFocus()
            }
            composeRule.onNode(key("1")).assertIsNotFocused()
            composeRule.runOnUiThread { shown = start.copy(content = math.copy(wrong = true, wrongAttempts = 2)) }
            composeRule.onNode(key("1")).assertIsFocused()
        }
    }

    // Word Unscramble

    @Test
    fun `Word reads Word n of count as its heading, then slots, letters, Shuffle and Clear`() {
        wake(check(word)) {
            composeRule.assertControlsLabelledWithRoles()
            composeRule.assertOneHeadingBefore("Word 1 of 2", hasContentDescription("Slot 1, empty"))
            composeRule.assertReadsInOrder(
                "Word 1 of 2",
                "Slot 1, empty",
                "Slot 5, empty",
                "Letter T",
                "Letter N",
                "Letter S",
                "Letter E",
                "Letter O",
                "Shuffle",
                "Clear",
                snoozeSpoken,
            )
        }
        val placed = WordInput("tnseo").tappedLetter(3).content(WordRound(1, 2, "tnseo"), wrong = false)
        wake(check(placed)) { composeRule.assertPolite("E") }
        // Letter tiles are 48 dp.
        wake(check(word)) {
            composeRule.onNode(hasContentDescription("Letter T")).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        }
    }

    @Test
    fun `after each wrong word focus goes back to the first letter, and the wrong line is polite`() {
        val wrong = WordInput("tnseo").content(WordRound(1, 2, "tnseo"), wrong = true).copy(wrongAttempts = 1)
        var shown by mutableStateOf(check(word))
        val elsewhere = FocusRequester()
        withScreen(
            PpsThemeMode.Light,
            content = {
                Box {
                    CheckScreen(state = shown, onIntent = {})
                    Box(Modifier.size(1.dp).focusRequester(elsewhere).focusable())
                }
            },
        ) {
            composeRule.onNode(hasContentDescription("Letter T")).assertIsNotFocused()
            composeRule.runOnUiThread { shown = check(wrong) }
            composeRule.onNode(hasContentDescription("Letter T")).assertIsFocused()
            composeRule.assertPolite("Not quite. Try again.")

            composeRule.runOnUiThread {
                shown = check(word.copy(wrongAttempts = 1))
                elsewhere.requestFocus()
            }
            composeRule.runOnUiThread { shown = check(wrong.copy(wrongAttempts = 2)) }
            composeRule.onNode(hasContentDescription("Letter T")).assertIsFocused()
        }
    }

    // Memory Sequence (numbered, as with TalkBack and as the fallback)

    @Test
    fun `numbered Memory reads the phase as a polite heading, the sequence politely, and its tiles disabled while it plays`() {
        val watching = memory(frame = 0)
        wake(check(watching)) {
            composeRule.assertControlsLabelledWithRoles()
            assertEquals(listOf("Watch the sequence"), composeRule.headings())
            composeRule.assertPolite("Watch the sequence")
            composeRule.assertPolite(watching.announced!!.joinToString(", "))
            composeRule.assertReadsInOrder("Round 1 of 2", "Watch the sequence", "Tile 1", "Tile 5", "Tile 9", snoozeSpoken)
            (1..9).forEach { tile -> composeRule.onNode(hasContentDescription("Tile $tile")).assertIsNotEnabled() }
            // Numbers on every tile: the label is the number, never a colour.
            composeRule.onNode(hasContentDescription("Tile 1")).assertHeightIsAtLeast(84.dp)
        }
        val turn = memory(frame = FRAMES_PLAYED)
        assertEquals(MemoryPhase.YourTurn, turn.phase)
        wake(check(turn)) {
            composeRule.assertControlsLabelledWithRoles()
            composeRule.assertOneHeadingBefore("Your turn", hasContentDescription("Tile 1"))
            composeRule.assertPolite("Your turn")
            (1..9).forEach { tile ->
                val node = composeRule.onNode(hasContentDescription("Tile $tile")).fetchSemanticsNode()
                assertTrue(SemanticsProperties.Disabled !in node.config, "Tile $tile is enabled on the user's turn")
            }
        }
    }

    @Test
    fun `after a wrong tap focus goes to tile 1 once Your turn starts again, not while the new sequence plays`() {
        var shown by mutableStateOf(check(memory(frame = FRAMES_PLAYED)))
        withScreen(PpsThemeMode.Light, content = { CheckScreen(state = shown, onIntent = {}) }) {
            composeRule.runOnUiThread { shown = check(memory(frame = 0, wrong = true, attempts = 1)) }
            composeRule.assertPolite("Not quite. Try again.")
            // Disabled while it plays: not focusable, so it has no focused state at all.
            composeRule
                .onNode(hasContentDescription("Tile 1"))
                .assert(SemanticsMatcher("not focused") { it.config.getOrNull(SemanticsProperties.Focused) != true })

            composeRule.runOnUiThread { shown = check(memory(frame = FRAMES_PLAYED, attempts = 1)) }
            composeRule.onNode(hasContentDescription("Tile 1")).assertIsFocused()
        }
    }

    // QR/Barcode

    @Test
    fun `QR reads its heading, the viewfinder, the torch as a switch with its state, and the different-code line politely`() {
        wake(check(CheckContent.QrBarcode(torchOn = true))) {
            composeRule.assertControlsLabelledWithRoles()
            composeRule.assertOneHeadingBefore("Scan your code", hasContentDescription("Torch"))
            composeRule.assertReadsInOrder("Scan your code", "Camera viewfinder. Point at your code.", "Torch", snoozeSpoken)
            val torch = composeRule.onNode(hasContentDescription("Torch")).assertHeightIsAtLeast(48.dp).fetchSemanticsNode()
            assertEquals(Role.Switch, torch.config.getOrNull(SemanticsProperties.Role))
            assertEquals(ToggleableState.On, torch.config.getOrNull(SemanticsProperties.ToggleableState))
        }
        wake(check(CheckContent.QrBarcode(wrongCode = true, wrongAttempts = 5), link = true)) {
            composeRule.assertPolite("That's a different code. Scan your registered one.")
            composeRule.assertReadsInOrder("Scan your code", "Torch", "That's a different code", "Can't do this check?", snoozeSpoken)
        }
    }

    @Test
    fun `without the camera QR reads the message politely, then the fallback link, a 48 dp button`() {
        wake(check(CheckContent.QrBarcode(cameraAvailable = false), link = true)) {
            composeRule.assertControlsLabelledWithRoles()
            composeRule.assertOneHeadingBefore("Scan your code", key("Can't do this check?"))
            composeRule.assertPolite("Camera isn't available. Pick a fallback check.")
            composeRule.assertReadsInOrder("Scan your code", "Camera isn't available", "Can't do this check?", snoozeSpoken)
            val link = composeRule.onNode(key("Can't do this check?")).assertHeightIsAtLeast(48.dp).fetchSemanticsNode()
            assertEquals(Role.Button, link.config.getOrNull(SemanticsProperties.Role))
            composeRule.onNode(hasContentDescription(snoozeSpoken)).assertHeightIsAtLeast(64.dp)
        }
    }

    // The Fallback check picker

    @Test
    fun `the picker reads its title, Back to check, then each card once as name and description, Math first`() {
        withScreen(PpsThemeMode.Light, content = { FallbackPickerScreen(state = fallbackPickerUiState(), onIntent = {}) }) {
            composeRule.assertControlsLabelledWithRoles()
            composeRule.assertOneHeadingBefore("Pick a fallback check", hasContentDescription("Back to check"))
            composeRule.assertReadsInOrder(
                "Pick a fallback check",
                "Back to check",
                "Math Solve a few quick sums.",
                "Word Unscramble Unscramble a few words.",
                "Memory Sequence Repeat a pattern of tiles.",
            )
            composeRule.onNode(hasContentDescription("Back to check")).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            listOf("Math", "Word Unscramble", "Memory Sequence").forEach { name ->
                val card = composeRule.onAllNodes(hasClickAction() and hasText(name)).fetchSemanticsNodes().single()
                assertEquals(Role.Button, card.config.getOrNull(SemanticsProperties.Role), name)
                assertTrue(card.boundsInRoot.height >= with(composeRule.density) { 64.dp.toPx() }, "$name card is at least 64 dp")
                // Read once: name and description are one node.
                assertEquals(2, card.config[SemanticsProperties.Text].size, name)
            }
        }
    }

    // "Try it"

    private val code: RegisteredCode = assertNotNull(RegisteredCode.of(CodeFormat.Ean13, "4006381333931"))

    @Test
    fun `Try it reads its heading and Back first, then the same check semantics, for every check`() {
        val trials =
            mapOf(
                CheckType.Math to ("Problem 1 of 1" to key("1")),
                CheckType.WordUnscramble to ("Word 1 of 1" to SemanticsMatcher("a slot") { TalkBackRules.label(it).startsWith("Slot 1") }),
                CheckType.MemorySequence to ("Watch the sequence" to hasContentDescription("Tile 1")),
                CheckType.QrBarcode to ("Scan your code" to hasContentDescription("Torch")),
            )
        trials.forEach { (type, expected) ->
            val trial = assertNotNull(CheckRegistry.startTrial(type, Difficulty.Medium, SEED, accessible = true, code = code), "$type")
            withScreen(PpsThemeMode.Light, content = { CheckPreviewScreen(state = trial.state, onIntent = {}, onClose = {}) }) {
                composeRule.assertControlsLabelledWithRoles()
                val headings = composeRule.headings()
                assertEquals("Try it", headings.first(), "$type")
                composeRule.assertReadsInOrder("Try it", "Back", expected.first)
                assertTrue(composeRule.onAllNodes(expected.second).fetchSemanticsNodes().isNotEmpty(), "$type input")
            }
        }
    }

    @Test
    fun `Try it done reads the polite done card, then Done`() {
        val done = CheckPreviewUiState(content = CheckSamples.loud.content, done = true)
        withScreen(PpsThemeMode.Light, content = { CheckPreviewScreen(state = done, onIntent = {}, onClose = {}) }) {
            composeRule.assertControlsLabelledWithRoles()
            composeRule.assertPolite("Nice. That's how it works.")
            composeRule.assertReadsInOrder("Try it", "Back", "Nice. That's how it works.", "Done")
        }
    }

    // Success

    @Test
    fun `every Success reads its headline as a heading first, then Done, a 72 dp button`() {
        val variants =
            listOf(
                SuccessUiState(SuccessKind.OnTime(streakDays = 0)) to "Up on time.",
                SuccessUiState(SuccessKind.OnTime(streakDays = 4)) to "Up on time.",
                SuccessUiState(SuccessKind.AfterSnooze(paidThisMorning = null)) to "You're up. That's what counts.",
                SuccessUiState(SuccessKind.AfterSnooze(paidThisMorning = Money(amountMicros = 2_000_000, currencyCode = "EUR"))) to
                    "You're up. That's what counts.",
                SuccessUiState(SuccessKind.Test, pendingNotUsed = true) to "Test finished. Your alarm works.",
            )
        variants.forEach { (state, headline) ->
            withScreen(PpsThemeMode.Light, content = { SuccessScreen(state = state, onIntent = {}, basic = true) }) {
                composeRule.assertControlsLabelledWithRoles()
                assertEquals(listOf(headline), composeRule.headings(), "$state")
                composeRule.assertOneHeadingBefore(headline, key("Done"))
                composeRule.assertReadsInOrder(headline, "Done")
                composeRule.onNode(key("Done")).assertHeightIsAtLeast(72.dp)
            }
        }
    }

    private companion object {
        const val SEED = 21L

        /** Every frame of a 2-round Medium round has played by then: the user's turn. */
        const val FRAMES_PLAYED = 64

        /** "23 times 4 plus 17": numbers joined by operator words, never signs. */
        val SPOKEN_PROBLEM = Regex("""^\d+ (plus|minus|times) \d+( (plus|minus|times) \d+)*$""")
    }
}
