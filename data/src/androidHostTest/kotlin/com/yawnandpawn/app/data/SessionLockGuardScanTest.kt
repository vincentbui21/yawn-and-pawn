package com.yawnandpawn.app.data

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Story 2.6 (FR-SES-3): every user-facing write runs inside `SessionLockGuard.whenIdle`, so nothing the user owns
 * changes during a session. A text scan of `:core`'s main sources, comments and string contents blanked out:
 * - In [FULL_PACKAGES] (`core.alarm`, `core.history`) every class or object is checked; elsewhere in `:core` only use
 *   cases (a class or object with `operator fun invoke`), so a settings, base-fee or "Delete all data" use case added
 *   in any package later is held to the rule.
 * - Each write ([WRITES]: repository, store, DAO, DataStore and dismissal writes, request-code allocation, scheduler
 *   and `AlarmScheduling` calls) must sit inside a `whenIdle { }` block. A private function that writes is checked at
 *   its call sites instead.
 * - [ALLOWED] lists the writers that keep working during a session, each with its reason.
 */
class SessionLockGuardScanTest {
    // Host tests run in the :data project directory.
    private val repoRoot = File("..").canonicalFile

    private val sources: List<Source> by lazy {
        val core = File(repoRoot, CORE)
        val missing = FULL_PACKAGES.filterNot { File(core, it).isDirectory }
        check(core.isDirectory && missing.isEmpty()) { "scanned core packages missing: $missing (moved? update the scan)" }
        core
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { Source(it.relativeTo(repoRoot).invariantSeparatorsPath, it.readText()) }
            .toList()
    }

    @Test
    fun `every user-facing write in core runs inside the session lock guard`() {
        assertEquals(emptyList(), sources.flatMap { offenders(it) })
    }

    @Test
    fun `the scan finds the guarded alarm use cases and every allowed writer, so it cannot pass by finding nothing`() {
        val writers = sources.flatMap { writers(it) }.toSet()
        assertEquals(setOf("SaveAlarm", "SetAlarmEnabled", "DeleteAlarm", "DuplicateAlarm"), writers - ALLOWED.keys)
        assertEquals(ALLOWED.keys, writers intersect ALLOWED.keys, "an allowed writer no longer writes: drop it from ALLOWED")
    }

    @Test
    fun `a write outside whenIdle is reported by path and class, whatever the declaration shape`() {
        assertEquals(Rogue.ROGUE_OFFENDERS, Rogue.ROGUE.flatMap { offenders(it) })
    }

    @Test
    fun `comments and strings are blanked out with their offsets kept`() {
        val code = "val a = \"x // y { \" // repository.upsert(a)\n/* /* nested */ whenIdle { */ val b = '{'"
        val blanked = blank(code)
        assertEquals(code.length, blanked.length)
        assertTrue("upsert" !in blanked && "whenIdle" !in blanked && "{" !in blanked, blanked)
        assertTrue(blanked.startsWith("val a = ") && "val b = " in blanked, blanked)
    }

    private data class Source(
        val path: String,
        val text: String,
    )

    private object Rogue {
        /** Rogue samples for the self-test: one shape per class. */
        val ROGUE =
            listOf(
                Source(
                    "core/alarm/Rogue.kt",
                    """
                    class SnoozeAll(private val repository: AlarmRepository, private val lock: AlarmWriteLock) {
                        suspend operator fun invoke() = lock.withLock { repository.upsert(alarm) }
                    }

                    class Guarded(private val repository: AlarmRepository, private val sessionLock: SessionLockGuard) {
                        suspend operator fun invoke() = sessionLock.whenIdle { store(repository.get(id)) }

                        private suspend fun store(alarm: Alarm) = repository.delete(alarm.id)
                    }

                    class HalfGuarded(private val repository: AlarmRepository, private val sessionLock: SessionLockGuard) {
                        // sessionLock.whenIdle { } in a comment guards nothing
                        suspend operator fun invoke() {
                            sessionLock.whenIdle { repository.get(id) }
                            repository.upsert(alarm)
                        }
                    }

                    object ClearAll {
                        suspend fun clear(alarmDao: AlarmDao) = alarmDao.deleteAll()
                    }

                    @JvmInline
                    value class Rearm(val scheduling: AlarmScheduling) {
                        fun now(alarm: Alarm) = scheduling.sync(alarm)
                    }

                    internal final class Arm(private val scheduler: AlarmScheduler) {
                        fun all(alarms: List<Alarm>) = alarms.forEach(scheduler::cancel)

                        inner class Nested(private val settings: DataStore<Preferences>) {
                            suspend fun reset() = settingsDataStore.edit { it.clear() }
                        }
                    }

                    class Helper(private val repository: AlarmRepository) {
                        suspend fun run() = writeIt()

                        private suspend fun writeIt() = repository.upsert(alarm)
                    }
                    """.trimIndent(),
                ),
                Source(
                    "core/history/Notes.kt",
                    "class Notes(private val dismissals: MissedNoteDismissals) {\n" +
                        "    suspend fun dismissAll(ids: List<String>) = ids.forEach { dismissals.dismiss(it) }\n}",
                ),
                Source(
                    "core/settings/Settings.kt",
                    """
                    class SetBaseFee(private val settingsRepository: SettingsRepository) {
                        suspend operator fun invoke(fee: Money) = settingsRepository.put(fee)
                    }

                    class TakeCode(private val requestCodes: RequestCodeSequence) {
                        suspend operator fun invoke() = requestCodes.next()
                    }

                    class Engine(private val store: ActiveSessionStore) {
                        suspend fun step(state: SessionState) = store.commit(state)
                    }
                    """.trimIndent(),
                ),
            )

        /** What the scan reports for [ROGUE], in order. */
        val ROGUE_OFFENDERS =
            listOf(
                "core/alarm/Rogue.kt: SnoozeAll writes outside SessionLockGuard.whenIdle",
                "core/alarm/Rogue.kt: HalfGuarded writes outside SessionLockGuard.whenIdle",
                "core/alarm/Rogue.kt: ClearAll writes outside SessionLockGuard.whenIdle",
                "core/alarm/Rogue.kt: Rearm writes outside SessionLockGuard.whenIdle",
                "core/alarm/Rogue.kt: Arm writes outside SessionLockGuard.whenIdle",
                "core/alarm/Rogue.kt: Nested writes outside SessionLockGuard.whenIdle",
                "core/alarm/Rogue.kt: Helper writes outside SessionLockGuard.whenIdle",
                "core/history/Notes.kt: Notes writes outside SessionLockGuard.whenIdle",
                // Engine is not a use case outside the fully scanned packages, so it is not checked.
                "core/settings/Settings.kt: SetBaseFee writes outside SessionLockGuard.whenIdle",
                "core/settings/Settings.kt: TakeCode writes outside SessionLockGuard.whenIdle",
            )
    }

    /** A class or object declaration, from its header to the next declaration (nested ones split it). */
    private data class Declaration(
        val name: String,
        val range: IntRange,
        val isUseCase: Boolean,
    )

    private fun offenders(source: Source): List<String> =
        unguardedWriters(source)
            .filterNot { it in ALLOWED }
            .map { "${source.path}: $it writes outside SessionLockGuard.whenIdle" }

    /** The checked declarations of [source] with a write outside `whenIdle`, in order. */
    private fun unguardedWriters(source: Source): List<String> {
        val text = blank(source.text)
        val guarded = GUARD.findAll(text).map { it.range.last..closingBrace(text, it.range.last) }.toList()
        return writesIn(text)
            .filter { pos -> guarded.none { pos in it } }
            .mapNotNull { pos -> checkedAt(source, text, pos) }
            .distinct()
    }

    /** Every checked declaration of [source] that writes at all (inside `whenIdle` or not). */
    private fun writers(source: Source): List<String> {
        val text = blank(source.text)
        return writesIn(text).mapNotNull { pos -> checkedAt(source, text, pos) }.distinct()
    }

    /** The checked declaration holding [pos], or null when [source]'s scope does not check it. */
    private fun checkedAt(
        source: Source,
        text: String,
        pos: Int,
    ): String? {
        val declaration = declarations(text).lastOrNull { pos in it.range } ?: return null
        val full = FULL_PACKAGES.any { "/core/$it/" in "/${source.path}" }
        return declaration.name.takeIf { full || declaration.isUseCase }
    }

    /**
     * The offsets of the writes in [text]: each [WRITES] match and each call of a private function that writes. Writes
     * inside such a function are left to its call sites.
     */
    private fun writesIn(text: String): List<Int> {
        val helpers = writingHelpers(text)
        val direct = WRITES.findAll(text).map { it.range.first }
        val calls =
            helpers.flatMap { helper ->
                Regex("""(?<![\w.:])${helper.name}\s*\(""").findAll(text).map { it.range.first }.filterNot { it in helper.declaration }
            }
        return (direct + calls).filterNot { pos -> helpers.any { pos in it.body } }.sorted().toList()
    }

    private data class Helper(
        val name: String,
        val declaration: IntRange,
        val body: IntRange,
    )

    /** The private functions of [text] that write, directly or through another one (to a fixed point). */
    private fun writingHelpers(text: String): List<Helper> {
        val all =
            PRIVATE_FUN
                .findAll(text)
                .map { match ->
                    Helper(match.groupValues[1], match.range, match.range.first..bodyEnd(text, match.range.last))
                }.toList()
        val writing = all.filter { helper -> WRITES.findAll(text).any { it.range.first in helper.body } }.toMutableSet()
        do {
            val more =
                all.filter { helper ->
                    helper !in writing &&
                        writing.any { w -> Regex("""(?<![\w.:])${w.name}\s*\(""").findAll(text).any { it.range.first in helper.body } }
                }
            writing += more
        } while (more.isNotEmpty())
        return writing.toList()
    }

    /** Where the function whose parameter list opens at [openParen] ends: its block, or else the next declaration. */
    private fun bodyEnd(
        text: String,
        openParen: Int,
    ): Int {
        var depth = 0
        var i = openParen
        while (i < text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) break
            }
            i++
        }
        val next = NEXT_DECLARATION.find(text, i)?.range?.first ?: text.length
        val brace = text.indexOf('{', i).takeIf { it in 0 until next }
        val equals = text.indexOf('=', i).takeIf { it in 0 until next }
        return if (brace != null && (equals == null || brace < equals)) closingBrace(text, brace) else next - 1
    }

    private fun declarations(text: String): List<Declaration> {
        val starts = DECLARATION.findAll(text).toList()
        return starts.mapIndexed { i, match ->
            val end = (starts.getOrNull(i + 1)?.range?.first ?: text.length) - 1
            val range = match.range.first..end
            Declaration(match.groupValues[1].ifEmpty { "Companion" }, range, INVOKE.containsMatchIn(text.substring(range)))
        }
    }

    private fun closingBrace(
        text: String,
        open: Int,
    ): Int {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return i
            }
        }
        return text.length - 1
    }

    /**
     * [code] with every comment (nested block comments too) and the contents of every string and char literal replaced
     * by spaces, newlines and offsets kept, so a word in a comment or string is neither a write nor a guard.
     */
    private fun blank(code: String): String {
        val out = StringBuilder(code)
        var i = 0
        while (i < code.length) {
            val span = commentAt(code, i) ?: literalAt(code, i)
            if (span == null) {
                i++
            } else {
                for (j in span.from until span.to) if (out[j] != '\n') out[j] = ' '
                i = span.next
            }
        }
        return out.toString()
    }

    /** Blank [from] until [to], then go on at [next]. */
    private class Span(
        val from: Int,
        val to: Int,
        val next: Int,
    )

    private fun commentAt(
        code: String,
        i: Int,
    ): Span? =
        when {
            code.startsWith("//", i) -> code.indexOf('\n', i).let { if (it < 0) code.length else it }.let { Span(i, it, it) }
            code.startsWith("/*", i) -> blockCommentEnd(code, i).let { Span(i, it, it) }
            else -> null
        }

    /** Where the block comment at [start] ends, nested comments included. */
    private fun blockCommentEnd(
        code: String,
        start: Int,
    ): Int {
        var depth = 0
        var j = start
        while (j < code.length) {
            when {
                code.startsWith("/*", j) -> {
                    depth++
                }

                code.startsWith("*/", j) -> {
                    depth--
                }

                else -> {
                    j++
                    continue
                }
            }
            j += 2
            if (depth == 0) return j
        }
        return code.length
    }

    /** The contents of the raw string, string or char literal at [i] (the quotes stay). */
    private fun literalAt(
        code: String,
        i: Int,
    ): Span? =
        when {
            code.startsWith("\"\"\"", i) -> {
                code.indexOf("\"\"\"", i + 3).let { if (it < 0) code.length else it }.let { Span(i + 3, it, minOf(it + 3, code.length)) }
            }

            code[i] == '"' || code[i] == '\'' -> {
                quotedEnd(code, i).let { Span(i + 1, it, minOf(it + 1, code.length)) }
            }

            else -> {
                null
            }
        }

    private fun quotedEnd(
        code: String,
        start: Int,
    ): Int {
        var j = start + 1
        while (j < code.length && code[j] != code[start] && code[j] != '\n') j += if (code[j] == '\\') 2 else 1
        return minOf(j, code.length)
    }

    private companion object {
        const val CORE = "core/src/commonMain/kotlin/com/yawnandpawn/app/core"

        /**
         * Every class and object here is checked, not only use cases. There is no `core.config` yet: settings, base-fee
         * and "Delete all data" use cases (Epics 4 and 5) are checked as use cases wherever they live in `:core`; a
         * package that also holds other writers the user drives (a repository facade, a dismissal) is added here.
         */
        val FULL_PACKAGES = listOf("alarm", "history", "stats")

        /** The writers allowed to write during a session, and why. */
        val ALLOWED =
            mapOf(
                // The alarm-fire path: re-arms a repeating alarm and switches a fired one-time alarm off (Story 2.6).
                "RearmOnFire" to "fire path",
                // The system-alarm sync: called by the guarded use cases inside whenIdle, and by system events (app
                // start, boot, time and zone changes) that must keep the schedule right during a session.
                "AlarmScheduling" to "system events",
                // Dismissing Home's missed note changes no alarm, fee or history row, so it may run during a session
                // (Home is behind the lock then anyway).
                "MissedNotes" to "display state only",
                // Dismissing Home's re-register banner (Story 3.13), likewise: a display state, in the settings DataStore.
                "ReRegisterSuggestions" to "display state only",
                // "Test alarm" (Story 1.18): stores the pending test config and arms the test code. The editor that
                // offers it is closed by the lock, and a test that fires during a session is ignored by the engine
                // (TestAlarmFired while active, AD-2), so it changes nothing the user owns.
                "ScheduleTestAlarm" to "test fires are ignored during a session",
            )

        val DECLARATION =
            Regex(
                """(?m)^[ \t]*(?:@\w+(?:\([^)]*\))?\s+)*""" +
                    """(?:(?:public|internal|private|protected|open|abstract|final|data|sealed|value|inner|enum|annotation""" +
                    """|companion|fun)\s+)*""" +
                    """(?:class|object|interface)\b[ \t]*(\w*)""",
            )

        val NEXT_DECLARATION =
            Regex(
                """(?m)^[ \t]*(?:(?:public|internal|private|protected|override|open|abstract|final|suspend|inline|operator|data|""" +
                    """sealed|value|inner|enum|companion)\s+)*(?:fun|class|object|interface)\b""",
            )

        val PRIVATE_FUN =
            Regex(
                """(?m)^[ \t]*private\s+(?:(?:suspend|inline|tailrec|operator|infix)\s+)*fun\s+""" +
                    """(?:<[^>]*>\s*)?(?:[\w.<>?, ]+\.)?(\w+)\s*\(""",
            )

        val INVOKE = Regex("""operator\s+fun\s+invoke""")

        private const val VERBS = "upsert|delete|insert|update|save|put|clear|remove|dismiss|record|commit|take|set|write|replace|edit"

        val WRITES =
            Regex(
                """\b\w*(?:[Rr]epository|[Ss]tore|[Dd]ao|[Dd]ismissals|[Rr]ecorder)\s*\??\.\s*(?:$VERBS)\w*\s*[({]""" +
                    """|\b\w*[Dd]ataStore\s*\??\.\s*(?:edit|updateData)\b""" +
                    """|\b(?:requestCodes|\w*[Ss]equence)\s*\??\.\s*next\s*\(""" +
                    """|\b\w*[Ss]chedul(?:ing|er)\s*(?:\??\.|::)\s*(?:sync|cancel|schedule|reschedule)\w*""",
            )

        val GUARD = Regex("""\.whenIdle\s*(?:<[^>]*>)?\s*(?:\(\s*\))?\s*\{""")
    }
}
