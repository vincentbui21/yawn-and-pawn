package com.yawnandpawn.app.data

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The AD-2 table in `docs/architecture.md` and the reducer's row list (`AD2_ROWS` in `:core`'s
 * `SessionTransitionTable.kt`, one test per row) describe the same rows (Story 4.8 review): the same count, and every
 * documented (From, Event) pair, the "(unlocking)" and "(paused)" sub-states and the keyguard guards included, matches
 * exactly one row id. It lives in `:data`'s host tests with the other source scans, as `:core` has no JVM-only tests.
 */
class Ad2TableDocTest {
    private val repoRoot = File("..").canonicalFile

    /** One documented row: From, Event and Guard as written. */
    private data class DocRow(
        val from: String,
        val event: String,
        val guard: String,
    )

    private fun docRows(): List<DocRow> {
        val lines = File(repoRoot, "docs/architecture.md").readLines()
        val start = lines.indexOfFirst { it.startsWith("### AD-2") }
        val header = (start until lines.size).first { lines[it].startsWith("| From | Event | Guard |") }
        return lines
            .drop(header + 2)
            .takeWhile { it.startsWith("|") }
            .map { line ->
                val cells = line.trim('|').split(" | ").map { it.trim() }
                DocRow(from = cells[0], event = cells[1], guard = cells[2])
            }
    }

    private fun rowIds(): List<String> {
        val source = File(repoRoot, "core/src/commonTest/kotlin/com/yawnandpawn/app/core/session/SessionTransitionTable.kt").readText()
        val list = source.substringAfter("internal val AD2_ROWS: List<String> =").substringBefore("\n    )")
        return Regex("\"(R\\d\\d [^\"]+)\"").findAll(list).map { it.groupValues[1] }.toList()
    }

    /** "Ringing, Grace, Loud (unlocking)" as the row ids spell it: "Ringing|Grace|Loud (unlocking)". */
    private fun DocRow.idFrom(): String = from.replace(", ", "|")

    /** "SlotFired / ProcessRestored" as "SlotFired|ProcessRestored". */
    private fun DocRow.idEvent(): String = event.replace(" / ", "|")

    private fun DocRow.matches(id: String): Boolean {
        val (from, rest) = id.substringAfter(' ').split('+', limit = 2).let { it[0] to it[1] }
        val eventMatches = rest == idEvent() || rest.startsWith(idEvent() + " ")
        val keyguard =
            when {
                rest.endsWith("keyguard not locked") -> "keyguard not locked" in guard
                rest.endsWith("keyguard locked") -> "keyguard locked" in guard
                else -> true
            }
        return from == idFrom() && eventMatches && keyguard
    }

    @Test
    fun `the documented AD-2 table has exactly the reducer's rows`() {
        val docs = docRows()
        val ids = rowIds()
        assertEquals(34, ids.size, "AD2_ROWS as parsed: $ids")
        assertEquals(ids.size, docs.size, "documented rows: ${docs.map { "${it.from} + ${it.event}" }}")

        val unused = ids.toMutableList()
        docs.forEach { row ->
            val id = unused.firstOrNull { row.matches(it) }
            assertTrue(id != null, "no row id for the documented ${row.from} + ${row.event} (${row.guard}); left: $unused")
            unused.remove(id)
        }
        assertEquals(emptyList(), unused, "row ids with no documented row")
    }

    @Test
    fun `the unlock rows are documented with their sub-state and keyguard guards`() {
        val docs = docRows()
        val pay = docs.filter { it.event == "PayConfirmed" }
        assertEquals(2, pay.size)
        assertTrue(pay.any { "keyguard not locked" in it.guard } && pay.any { "keyguard locked (" in it.guard })
        listOf("UnlockSucceeded", "UnlockFailed").forEach { event ->
            assertEquals("Ringing, Grace, Loud (unlocking)", docs.single { it.event == event }.from, event)
        }
    }
}
