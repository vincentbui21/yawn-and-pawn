package com.yawnandpawn.app.core.checks.word

import com.yawnandpawn.app.core.checks.CheckAnswer
import com.yawnandpawn.app.core.checks.CheckResult
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.Difficulty
import com.yawnandpawn.app.core.checks.Puzzle
import com.yawnandpawn.app.core.checks.SeedDeriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Story 3.7: the Word Unscramble plugin (FR-PWK-8) over a fixture list. */
class WordCheckTest {
    private val list = WordList(FIXTURE)
    private val word = CheckType.WordUnscramble

    @BeforeTest
    fun install() = WordBank.install(list)

    @AfterTest
    fun uninstall() = WordBank.install(WordList(emptyList()))

    private fun puzzle(
        seed: Long = 5L,
        difficulty: Difficulty = Difficulty.Medium,
        count: Int = 2,
    ): Puzzle.Word = word.generate(seed, difficulty, count) as Puzzle.Word

    @Test
    fun `the list keeps lowercase a-z words once, sorted, by length group, with an anagram index`() {
        val raw = WordList(listOf("Stone", " notes ", "onset", "stone", "two words", "café", "", "abc"))

        assertEquals(listOf("abc", "notes", "onset", "stone"), raw.words)
        assertEquals(listOf("notes", "onset", "stone"), raw.bucket(Difficulty.Easy))
        assertEquals(setOf("notes", "onset", "stone"), raw.anagramsOf("TONES"))
        assertTrue(raw.contains("STONE"))
        assertFalse(raw.contains("tones"))
        assertEquals(4..5, WordList.lengths(Difficulty.Easy))
        assertEquals(6..7, WordList.lengths(Difficulty.Medium))
        assertEquals(8..10, WordList.lengths(Difficulty.Hard))
    }

    @Test
    fun `over 10,000 seeds each difficulty picks distinct words of its lengths, scrambled into no listed word`() {
        Difficulty.entries.forEach { difficulty ->
            repeat(10_000) { n ->
                val puzzle = puzzle(seed = SeedDeriver.seed("s-$n", 1, 0, 0), difficulty = difficulty, count = 3)
                assertEquals(3, puzzle.words.toSet().size, "$difficulty distinct")
                puzzle.words.zip(puzzle.scrambles).forEach { (target, scramble) ->
                    assertTrue(target.length in WordList.lengths(difficulty), "$difficulty $target")
                    assertEquals(target.toList().sorted(), scramble.toList().sorted(), "a permutation of $target")
                    assertNotEquals(target, scramble)
                    assertFalse(list.contains(scramble), "$scramble is a listed word")
                }
            }
        }
    }

    @Test
    fun `a seed always gives the same words and scrambles (pinned), and the count is brought into 1 to 5`() {
        // Pinned: stored sessions depend on it, so a change to the generator must be a deliberate test change.
        assertEquals(PINNED, puzzle(seed = 42L, difficulty = Difficulty.Easy, count = 2).let { it.words.zip(it.scrambles) })
        assertEquals(puzzle(seed = 9L), puzzle(seed = 9L))
        assertEquals(5, puzzle(count = 9).size)
        assertEquals(1, puzzle(count = 0).size)
    }

    @Test
    fun `the target or any listed word with its letters is right, in any case, and the last word is Correct`() {
        val puzzle = Puzzle.Word(listOf("listen", "garden"), listOf("tsinel", "nadreg"))

        assertSame(CheckResult.ItemCorrect, word.validate(puzzle, 0, CheckAnswer.Word("listen")))
        assertSame(CheckResult.ItemCorrect, word.validate(puzzle, 0, CheckAnswer.Word("SILENT")), "a listed anagram")
        assertSame(CheckResult.ItemCorrect, word.validate(puzzle, 0, CheckAnswer.Word("Tinsel")))
        assertSame(CheckResult.Wrong, word.validate(puzzle, 0, CheckAnswer.Word("inlets")), "same letters, not listed")
        assertSame(CheckResult.Wrong, word.validate(puzzle, 0, CheckAnswer.Word("garden")), "another item's word")
        assertSame(CheckResult.Correct, word.validate(puzzle, 1, CheckAnswer.Word("GARDEN")))
        assertSame(CheckResult.Correct, word.validate(puzzle, 1, CheckAnswer.Word("danger")))
    }

    @Test
    fun `anything that is not a word for an item of the puzzle is Wrong`() {
        val puzzle = Puzzle.Word(listOf("listen"), listOf("tsinel"))

        assertSame(CheckResult.Wrong, word.validate(puzzle, 0, CheckAnswer.Number("1")))
        assertSame(CheckResult.Wrong, word.validate(puzzle, 1, CheckAnswer.Word("listen")))
        assertSame(CheckResult.Wrong, word.validate(Puzzle.Placeholder, 0, CheckAnswer.Word("listen")))
        assertEquals(0, word.restartFrom(0, Difficulty.Easy))
    }

    @Test
    fun `with no list installed no puzzle can be made`() {
        WordBank.install(WordList(emptyList()))

        assertEquals(Puzzle.Word(emptyList(), emptyList()), puzzle())
    }

    @Test
    fun `a word with no scramble that is not a listed word is skipped for the next pick`() {
        // Every arrangement of "aaaa" is "aaaa" itself: no scramble exists, so only "stop" can be picked.
        WordBank.install(WordList(listOf("aaaa", "stop")))

        val picked = puzzle(difficulty = Difficulty.Easy)
        assertEquals(listOf("stop"), picked.words)
        assertNotEquals("stop", picked.scrambles.single())
    }

    private companion object {
        val FIXTURE =
            listOf(
                // 4-5
                "apple",
                "bread",
                "chair",
                "dance",
                "eagle",
                "field",
                "grape",
                "house",
                "juice",
                "lemon",
                "maple",
                "night",
                "ocean",
                "piano",
                "quilt",
                "river",
                "stone",
                "notes",
                "onset",
                "table",
                "tiger",
                "under",
                "water",
                "zebra",
                "bike",
                "lamp",
                "milk",
                "rain",
                "snow",
                "wind",
                // 6-7
                "listen",
                "silent",
                "tinsel",
                "enlist",
                "garden",
                "danger",
                "ranged",
                "basket",
                "bottle",
                "candle",
                "dinner",
                "forest",
                "guitar",
                "island",
                "jacket",
                "kitten",
                "ladder",
                "mirror",
                "pencil",
                "rabbit",
                "summer",
                "window",
                "blanket",
                "chicken",
                "diamond",
                "kitchen",
                "morning",
                "rainbow",
                "teacher",
                "village",
                // 8-10
                "airplane",
                "birthday",
                "calendar",
                "dinosaur",
                "elephant",
                "flamingo",
                "football",
                "hospital",
                "keyboard",
                "language",
                "mountain",
                "notebook",
                "painting",
                "question",
                "sandwich",
                "squirrel",
                "treasure",
                "umbrella",
                "vacation",
                "weekend",
                "breakfast",
                "butterfly",
                "chocolate",
                "furniture",
                "pineapple",
                "sunflower",
                "waterfall",
                "watermelon",
                "lighthouse",
                "restaurant",
            )

        val PINNED = listOf("field" to "eldif", "stone" to "ntseo")
    }
}
