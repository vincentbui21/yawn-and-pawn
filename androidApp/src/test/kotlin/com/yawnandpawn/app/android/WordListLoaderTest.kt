package com.yawnandpawn.app.android

import android.content.ContextWrapper
import android.content.res.AssetManager
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.YawnAndPawnApp
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.Difficulty
import com.yawnandpawn.app.core.checks.Puzzle
import com.yawnandpawn.app.core.checks.SeedDeriver
import com.yawnandpawn.app.core.checks.word.WordBank
import com.yawnandpawn.app.core.checks.word.WordList
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.testing.FakeLogger
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Story 3.7: the app reads the bundled word list at start and installs it for every Word Unscramble puzzle. */
@RunWith(RobolectricTestRunner::class)
class WordListLoaderTest {
    @get:Rule
    val stopApp = StopAppRule()

    private val app = ApplicationProvider.getApplicationContext<YawnAndPawnApp>()
    private val logger = FakeLogger()

    @Test
    fun `the bundled list is installed at app start with at least 300 words in each length group`() {
        val loaded = WordListLoader.load(app, logger)

        assertEquals(loaded.words, WordBank.current.words, "installed at start")
        Difficulty.entries.forEach { difficulty ->
            assertTrue(loaded.bucket(difficulty).size >= 300, "$difficulty: ${loaded.bucket(difficulty).size}")
        }
        assertTrue(loaded.contains("garden") && loaded.contains("breakfast") && loaded.contains("apple"))
        assertEquals(emptyList(), logger.events)
    }

    // Robolectric shares one AssetManager between the contexts, so this checks the call path a Direct Boot start takes,
    // not the storage: the real check is the reboot row of the Epic 3 device checklist (ring before the first unlock).
    @Test
    fun `the device-protected context reads the same list (the path a start before the first unlock takes)`() {
        val locked = WordListLoader.load(app.createDeviceProtectedStorageContext(), logger)

        assertEquals(WordListLoader.load(app, logger).words, locked.words)
    }

    @Test
    fun `an unreadable asset never crashes the start, the list is empty and the failure is logged (review fix)`() {
        val broken =
            object : ContextWrapper(app) {
                override fun getAssets(): AssetManager = error("asset store unavailable")
            }

        val loaded = WordListLoader.load(broken, logger)

        assertEquals(emptyList(), loaded.words)
        assertEquals(listOf<LogEvent>(LogEvent.OperationFailed("load word list", "asset store unavailable")), logger.events)
    }

    @Test
    fun `over 2,000 seeds per difficulty the real list gives 5 distinct words with real scrambles (review fix)`() {
        val list = WordListLoader.load(app, logger)
        WordBank.install(list)
        Difficulty.entries.forEach { difficulty ->
            repeat(SEEDS) { n ->
                val puzzle = CheckType.WordUnscramble.generate(SeedDeriver.seed("s-$n", 0, 0, 0), difficulty, 5) as Puzzle.Word
                assertEquals(5, puzzle.words.toSet().size, "$difficulty seed $n: 5 distinct words")
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
    fun `no listed word has the letters of a blocklisted word, so no scramble can spell one (review fix)`() {
        val list = WordListLoader.load(app, logger)
        // The androidApp tests run in its project directory.
        val blocked =
            File("../config/word-blocklist.txt")
                .readLines()
                .map { it.substringBefore('#').trim().lowercase() }
                .filter { it.isNotEmpty() }

        assertTrue(blocked.size > 50, "the blocklist was read")
        assertEquals(emptyMap(), blocked.associateWith { list.anagramsOf(it) }.filterValues { it.isNotEmpty() })
    }

    private companion object {
        const val SEEDS = 2_000
    }
}
