package com.yawnandpawn.app.ui.checks

import com.yawnandpawn.app.core.checks.Puzzle
import com.yawnandpawn.app.core.checks.word.WordBank
import com.yawnandpawn.app.core.checks.word.WordList
import com.yawnandpawn.app.ui.wake.CheckContent
import com.yawnandpawn.app.ui.wake.WakeIntent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import com.yawnandpawn.app.core.checks.CheckType as CoreCheckType

/** Story 3.6: every check the pickers offer has a "Try it", and the Math trial answers through the core plugin. */
class CheckRegistryTest {
    private val seed = 42L

    /** The word list is process-wide: no test leaves one behind for the next (review fix, order independence). */
    @AfterTest
    fun uninstallWords() = WordBank.install(WordList(emptyList()))

    private fun problem(difficulty: Difficulty) =
        (CoreCheckType.Math.generate(seed, difficulty.toCore(), count = 1) as Puzzle.Math).problems.single()

    private fun CheckTrial.math(): CheckContent.Math = state.content as CheckContent.Math

    /** Types [answer]'s digits on the pad. */
    private fun CheckTrial.typed(answer: Int): CheckTrial =
        answer.toString().fold(this) { trial, c -> trial.onIntent(WakeIntent.DigitTapped(c.digitToInt())) }

    @Test
    fun `every check the pickers offer has a registered Try it, and the pickers offer only those`() {
        // Word Unscramble needs the app's word list; here a small one.
        WordBank.install(WordList(listOf("apple", "stone", "garden", "listen", "airplane", "notebook")))
        assertTrue(PickableCheckTypes.isNotEmpty())
        PickableCheckTypes.forEach { type -> assertNotNull(CheckRegistry.startTrial(type, Difficulty.Medium, seed), "$type") }
        assertTrue(CheckRegistry.types.containsAll(PickableCheckTypes))
        assertEquals(listOf(CheckType.Math, CheckType.WordUnscramble, CheckType.MemorySequence), PickableCheckTypes)
    }

    @Test
    fun `a check without a registered Try it starts none`() {
        assertNull(CheckRegistry.startTrial(CheckType.HouseHunt, Difficulty.Medium, seed))
    }

    @Test
    fun `Math shows one problem from the core generator at the difficulty set, count 1`() {
        Difficulty.entries.forEach { difficulty ->
            val content = CheckRegistry.startTrial(CheckType.Math, difficulty, seed)!!.math()
            val expected = problem(difficulty)

            assertEquals(1 to 1, content.problemNumber to content.problemCount, "$difficulty")
            assertEquals(expected.operands, content.operands, "$difficulty")
            assertEquals(expected.operators.map { it.name }, content.operators.map { it.name }, "$difficulty")
            assertEquals("", content.answer)
            assertFalse(content.wrong)
        }
    }

    @Test
    fun `a wrong answer clears the field and shows the wrong state until the next digit`() {
        val answer = problem(Difficulty.Hard).answer
        val trial = MathTrial.start(Difficulty.Hard, seed).typed(answer + 1).onIntent(WakeIntent.SubmitAnswer)

        assertEquals("", trial.math().answer)
        assertTrue(trial.math().wrong)
        assertFalse(trial.state.done)
        assertFalse(trial.onIntent(WakeIntent.DigitTapped(1)).math().wrong, "typing clears it")
    }

    @Test
    fun `the right answer, leading zeros allowed, solves it, and taps after that change nothing`() {
        val answer = problem(Difficulty.Easy).answer
        val solved =
            MathTrial
                .start(
                    Difficulty.Easy,
                    seed,
                ).onIntent(WakeIntent.DigitTapped(0))
                .typed(answer)
                .onIntent(WakeIntent.SubmitAnswer)

        assertTrue(solved.state.done)
        assertSame(solved, solved.onIntent(WakeIntent.DigitTapped(3)))
    }

    @Test
    fun `Check with no digits does nothing, the field keeps 5 digits and delete removes the last`() {
        val trial = MathTrial.start(Difficulty.Medium, seed)

        assertSame(trial, trial.onIntent(WakeIntent.SubmitAnswer))
        val six = (1..6).fold<Int, CheckTrial>(trial) { t, d -> t.onIntent(WakeIntent.DigitTapped(d)) }
        assertEquals("12345", six.math().answer)
        assertEquals("1234", six.onIntent(WakeIntent.DeleteDigit).math().answer)
        assertSame(trial, trial.onIntent(WakeIntent.SnoozeClicked), "taps Math does not use")
    }
}
