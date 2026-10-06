package com.yawnandpawn.app.ui.checks

import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.CheckMode
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.checks.Puzzle
import com.yawnandpawn.app.core.checks.word.WordBank
import com.yawnandpawn.app.core.checks.word.WordList
import com.yawnandpawn.app.core.session.CheckRun
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.StepPointer
import com.yawnandpawn.app.testing.aSession
import com.yawnandpawn.app.ui.wake.CheckContent
import com.yawnandpawn.app.ui.wake.CheckPosition
import com.yawnandpawn.app.ui.wake.WakeIntent
import com.yawnandpawn.app.ui.wake.WordAnswer
import com.yawnandpawn.app.ui.wake.WordInput
import com.yawnandpawn.app.ui.wake.WordRound
import com.yawnandpawn.app.ui.wake.wordRound
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import com.yawnandpawn.app.core.checks.CheckType as CoreCheckType
import com.yawnandpawn.app.core.checks.Difficulty as CoreDifficulty

/** Story 3.7: the Word Unscramble tiles, the "Try it" trial and the wake mapping. */
class WordTrialTest {
    private val seed = 3L

    @BeforeTest
    fun install() = WordBank.install(WordList(LIST))

    @AfterTest
    fun uninstall() = WordBank.install(WordList(emptyList()))

    private fun target(difficulty: Difficulty): String =
        (CoreCheckType.WordUnscramble.generate(seed, difficulty.toCore(), 1) as Puzzle.Word).words.single()

    private fun CheckTrial.word(): CheckContent.WordUnscramble = state.content as CheckContent.WordUnscramble

    /** Taps the pool letters that spell [text], in order. */
    private fun CheckTrial.spelled(text: String): CheckTrial =
        text.fold(this) { trial, letter ->
            val place = trial.word().pool.indexOfFirst { it == letter.uppercaseChar() }
            trial.onIntent(WakeIntent.LetterTapped(place))
        }

    @Test
    fun `a letter moves to the next empty slot, a slot sends it back, Clear empties them and Shuffle keeps them`() {
        val input = WordInput("tsinel")

        val two = input.tappedLetter(0).tappedLetter(3)
        assertEquals(listOf('t', 'n', null, null, null, null), two.answerLetters)
        assertEquals(listOf(null, 's', 'i', null, 'e', 'l'), two.pool)
        assertSame(two, two.tappedLetter(0), "a moved letter is gone from the pool")
        assertEquals(listOf(null, 'n', null, null, null, null), two.tappedSlot(0).answerLetters)
        assertSame(two, two.tappedSlot(4), "an empty slot does nothing")
        assertEquals(List(6) { null }, two.cleared().answerLetters)
        val shuffled = two.shuffled()
        assertEquals(two.answerLetters, shuffled.answerLetters, "Shuffle changes only the pool's order")
        assertEquals(input.letters.toList().sorted(), shuffled.order.map { input.letters[it] }.sorted())
        assertEquals(shuffled.order, two.shuffled().order, "deterministic")
        assertNull(two.answer)
        assertEquals("tsinel", (0..5).fold(input) { acc, place -> acc.tappedLetter(place) }.answer)
    }

    @Test
    fun `the trial shows one word of the difficulty set, scrambled, in uppercase tiles`() {
        val trial = CheckRegistry.startTrial(CheckType.WordUnscramble, Difficulty.Hard, seed)!!

        assertEquals(1 to 1, trial.word().wordNumber to trial.word().wordCount)
        assertEquals(
            target(Difficulty.Hard).uppercase().toList().sorted(),
            trial
                .word()
                .pool
                .filterNotNull()
                .sorted(),
        )
        assertTrue(trial.word().slots.all { it == null })
    }

    @Test
    fun `filling every slot with the word solves it, and taps after that change nothing`() {
        val trial = WordTrial.start(Difficulty.Medium, seed)!!

        val solved = trial.spelled(target(Difficulty.Medium))

        assertTrue(solved.state.done)
        assertSame(solved, solved.onIntent(WakeIntent.ClearLetters))
    }

    @Test
    fun `a listed anagram is right too`() {
        // Review fix: the only Medium words are listen and silent, so the target always has its anagram listed.
        WordBank.install(WordList(LIST.filter { it.length !in 6..7 } + "listen" + "silent"))
        val trial = WordTrial.start(Difficulty.Medium, seed)!!
        val word = target(Difficulty.Medium)
        val other = if (word == "listen") "silent" else "listen"

        assertTrue(word in setOf("listen", "silent"), word)
        assertTrue(trial.spelled(other).state.done, "$other for $word")
    }

    @Test
    fun `a wrong word clears the slots and shows the wrong state until the next tap`() {
        val trial = WordTrial.start(Difficulty.Easy, seed)!!
        val word = target(Difficulty.Easy)
        val wrongOrder = word.reversed().takeIf { it != word && !WordBank.current.contains(it) } ?: word.drop(1) + word.first()

        val wrong = trial.spelled(wrongOrder)

        assertTrue(wrong.word().wrong)
        assertFalse(wrong.state.done)
        assertTrue(wrong.word().slots.all { it == null })
        assertFalse(wrong.onIntent(WakeIntent.LetterTapped(0)).word().wrong)
        assertSame(wrong, wrong.onIntent(WakeIntent.SubmitAnswer), "taps the tiles do not use")
    }

    @Test
    fun `without a word list there is no Word trial`() {
        WordBank.install(WordList(emptyList()))

        assertNull(WordTrial.start(Difficulty.Easy, seed))
    }

    @Test
    fun `a session in Grace or Loud on a Word entry gives its current item, any other state none`() {
        val entry = CheckEntry(CoreCheckType.WordUnscramble, CoreDifficulty.Medium, 3)
        val session = aSession().copy(checkRun = CheckRun(CheckPlan(CheckMode.All, listOf(entry)), listOf(seed), step = StepPointer(0, 2)))
        val puzzle = CoreCheckType.WordUnscramble.generate(seed, CoreDifficulty.Medium, 3) as Puzzle.Word

        assertEquals(WordRound(3, 3, puzzle.scrambles[2]), wordRound(SessionState.Loud(session)))
        assertEquals(wordRound(SessionState.Loud(session)), wordRound(SessionState.Grace(session)))
        assertNull(wordRound(SessionState.Ringing(session)))
        assertNull(wordRound(SessionState.Loud(aSession())), "a Math entry")
        assertNull(wordRound(puzzle, 3), "past the end")
        assertNull(wordRound(Puzzle.Placeholder, 0))
        assertNotEquals(puzzle.words[2], puzzle.scrambles[2])
    }

    private fun position(
        item: Int,
        failedAttempts: Int = 0,
    ) = CheckPosition("s", ringIndex = 0, entry = 0, item = item, seed = seed, failedAttempts = failedAttempts)

    @Test
    fun `the wake screen's Word answer keeps its letters on the same item and starts each new item empty (review fix)`() {
        val round = WordRound(1, 2, "tnseo")
        val start = WordAnswer().following(position(0), round)
        assertEquals(WordInput("tnseo"), start.input)
        assertNull(start.edited(WakeIntent.DigitTapped(1)), "not a Word tap")

        val placed = assertNotNull(start.edited(WakeIntent.LetterTapped(0)))
        assertEquals(placed, placed.following(position(0), round), "the same item keeps the letters")
        assertEquals(WordInput("ilfed"), placed.following(position(1), WordRound(2, 2, "ilfed")).input, "a new item starts empty")

        val full = "tnseo".indices.fold(start) { answer, place -> assertNotNull(answer.edited(WakeIntent.LetterTapped(place))) }
        assertEquals("tnseo", full.answer, "every slot filled: the word to send")
    }

    @Test
    fun `a wrong word clears the slots and shows Not quite until the next tap (review fix)`() {
        val round = WordRound(1, 2, "tnseo")
        val full =
            "tnseo".indices.fold(WordAnswer().following(position(0), round)) { answer, place ->
                assertNotNull(answer.edited(WakeIntent.LetterTapped(place)))
            }

        val wrong = full.following(position(0, failedAttempts = 1), round)

        assertTrue(wrong.wrong)
        assertEquals(WordInput("tnseo"), wrong.input, "the slots are cleared")
        assertTrue(assertNotNull(wrong.content()).wrong)
        assertFalse(assertNotNull(wrong.edited(WakeIntent.LetterTapped(2))).wrong, "the next tap clears it")
        assertNull(WordAnswer().following(null, null).content(), "no Word entry")
    }

    private companion object {
        val LIST =
            listOf(
                "apple",
                "bread",
                "chair",
                "dance",
                "field",
                "grape",
                "house",
                "lemon",
                "stone",
                "water",
                "listen",
                "garden",
                "basket",
                "candle",
                "forest",
                "island",
                "kitten",
                "pencil",
                "summer",
                "window",
                "airplane",
                "birthday",
                "calendar",
                "elephant",
                "mountain",
                "notebook",
                "sandwich",
                "treasure",
                "umbrella",
                "vacation",
            )
    }
}
