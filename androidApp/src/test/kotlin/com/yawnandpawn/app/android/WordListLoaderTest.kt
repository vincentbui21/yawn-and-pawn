package com.yawnandpawn.app.android

import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.YawnAndPawnApp
import com.yawnandpawn.app.core.checks.Difficulty
import com.yawnandpawn.app.core.checks.word.WordBank
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Story 3.7: the app reads the bundled word list at start and installs it for every Word Unscramble puzzle. */
@RunWith(RobolectricTestRunner::class)
class WordListLoaderTest {
    @get:Rule
    val stopApp = StopAppRule()

    @Test
    fun `the bundled list is installed at app start with at least 300 words in each length group`() {
        val app = ApplicationProvider.getApplicationContext<YawnAndPawnApp>()
        val loaded = WordListLoader.load(app)

        assertEquals(loaded.words, WordBank.current.words, "installed at start")
        Difficulty.entries.forEach { difficulty ->
            assertTrue(loaded.bucket(difficulty).size >= 300, "$difficulty: ${loaded.bucket(difficulty).size}")
        }
        assertTrue(loaded.contains("garden") && loaded.contains("breakfast") && loaded.contains("apple"))
    }

    @Test
    fun `before the first unlock the list is read from the device-protected context too`() {
        val app = ApplicationProvider.getApplicationContext<YawnAndPawnApp>()

        val locked = WordListLoader.load(app.createDeviceProtectedStorageContext())

        assertEquals(WordListLoader.load(app).words, locked.words)
    }
}
