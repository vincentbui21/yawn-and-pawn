package com.yawnandpawn.app.android.qr

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tripwire (Story 3.10 review): Story 3.10 was built before Stories 3.5 and 3.9 and left thin hooks for them, marked
 * `3.5 hook` and `3.9 hook` in the sources (the picker's `PICKABLE_TYPES` and camera gate, `SaveAlarm` rejecting a QR
 * entry without a code, the registry's QR composable, the registration route, the fallback link). A hook left behind
 * after the rebase is silent: QR could never be picked or saved, or would ring as Math. Green on this base (the 3.5 and
 * 3.9 classes do not exist yet); once a story's class exists, every marker of that story must be gone.
 */
class QrRebaseTripwireTest {
    /** Host tests run in the :androidApp project directory; the repository is its parent. */
    private val sources: List<File> =
        listOf("androidApp", "composeApp", "core", "data")
            .map { File("../$it/src") }
            .onEach { assertTrue(it.isDirectory, "sources at ${it.absolutePath}") }
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != THIS_FILE }.toList() }

    private fun declares(declaration: Regex): List<String> = sources.filter { declaration.containsMatchIn(it.readText()) }.map { it.name }

    private fun markers(story: String): List<String> =
        sources.flatMap { file ->
            file
                .readLines()
                .withIndex()
                .filter { "$story hook" in it.value }
                .map { "${file.name}:${it.index + 1}" }
        }

    @Test
    fun `no 3_5 hook is left once Story 3_5's check registry exists`() {
        assertTrue(sources.size > 100, "the sources were found")
        if (declares(Regex("""\bobject CheckRegistry\b""")).isNotEmpty()) {
            assertEquals(emptyList(), markers("3.5"), "Story 3.5 is in: wire each 3.5 hook (spec 3.10, Rebase notes), then drop its marker")
        } else {
            assertTrue(markers("3.5").isNotEmpty(), "on this base the hooks are still marked")
        }
    }

    @Test
    fun `no 3_9 hook is left once Story 3_9's camera fallback policy exists`() {
        if (declares(Regex("""\bclass CameraFallbackPolicy\b""")).isNotEmpty()) {
            assertEquals(emptyList(), markers("3.9"), "Story 3.9 is in: wire each 3.9 hook (spec 3.10, Rebase notes), then drop its marker")
        } else {
            assertTrue(markers("3.9").isNotEmpty(), "on this base the hooks are still marked")
        }
    }

    private companion object {
        const val THIS_FILE = "QrRebaseTripwireTest.kt"
    }
}
