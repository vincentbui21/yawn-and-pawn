package com.yawnandpawn.app.data

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals

/**
 * Story 2.6 (FR-SES-3): every user-facing mutating use case runs its writes inside `SessionLockGuard.whenIdle`, so
 * nothing the user owns changes during a session. A text scan of `:core`'s `core.alarm` and `core.config` main
 * sources: a class with an `operator fun invoke` whose body writes a repository (or allocates a request code) must call
 * `whenIdle`. Classes without `invoke` (the alarm-fire path, `AlarmScheduling`) are not user actions and are not
 * checked. A later story that adds a settings, base-fee or "Delete all data" use case is held to the same rule.
 */
class SessionLockGuardScanTest {
    // Host tests run in the :data project directory.
    private val repoRoot = File("..").canonicalFile

    private val sources: List<Source> =
        PACKAGES
            .map { File(repoRoot, "core/src/commonMain/kotlin/com/yawnandpawn/app/core/$it") }
            .filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .map { Source(it.relativeTo(repoRoot).invariantSeparatorsPath, it.readText()) }

    @Test
    fun `every alarm and config use case that writes calls the session lock guard`() {
        assertEquals(emptyList(), offenders(sources))
    }

    @Test
    fun `the scan finds the four guarded alarm use cases, so it cannot pass by finding nothing`() {
        assertEquals(
            setOf("SaveAlarm", "SetAlarmEnabled", "DeleteAlarm", "DuplicateAlarm"),
            sources.flatMap { writingUseCases(it.text) }.map { it.name }.toSet(),
        )
    }

    @Test
    fun `a use case that writes without the guard is reported, by path and class`() {
        val rogue =
            listOf(
                Source(
                    "core/alarm/Rogue.kt",
                    """
                    class SnoozeAll(private val repository: AlarmRepository, private val lock: AlarmWriteLock) {
                        suspend operator fun invoke() = lock.withLock { repository.upsert(alarm) }
                    }

                    class Guarded(private val repository: AlarmRepository, private val sessionLock: SessionLockGuard) {
                        suspend operator fun invoke() = sessionLock.whenIdle { repository.delete(id) }
                    }

                    class FireOnly(private val repository: AlarmRepository) {
                        suspend fun onFire() = repository.upsert(alarm)
                    }
                    """.trimIndent(),
                ),
                Source(
                    "core/config/Settings.kt",
                    "class SetBaseFee(private val settingsRepository: SettingsRepository) {\n" +
                        "    suspend operator fun invoke(fee: Money) = settingsRepository.put(fee)\n}",
                ),
                Source(
                    "core/alarm/Codes.kt",
                    "class TakeCode(private val requestCodes: RequestCodeSequence) {\n" +
                        "    suspend operator fun invoke() = requestCodes.next()\n}",
                ),
            )

        assertEquals(
            listOf(
                "core/alarm/Rogue.kt: SnoozeAll writes without SessionLockGuard.whenIdle",
                "core/config/Settings.kt: SetBaseFee writes without SessionLockGuard.whenIdle",
                "core/alarm/Codes.kt: TakeCode writes without SessionLockGuard.whenIdle",
            ),
            offenders(rogue),
        )
    }

    private data class Source(
        val path: String,
        val text: String,
    )

    private data class UseCase(
        val name: String,
        val body: String,
    )

    private fun offenders(sources: List<Source>): List<String> =
        sources.flatMap { source ->
            writingUseCases(source.text)
                .filterNot { GUARD.containsMatchIn(it.body) }
                .map { "${source.path}: ${it.name} writes without SessionLockGuard.whenIdle" }
        }

    /** The classes of [text] that have an `operator fun invoke` and write somewhere in their body. */
    private fun writingUseCases(text: String): List<UseCase> {
        val starts = CLASS.findAll(text).toList()
        return starts
            .mapIndexed { i, match ->
                val end = starts.getOrNull(i + 1)?.range?.first ?: text.length
                UseCase(match.groupValues[1], text.substring(match.range.first, end))
            }.filter { INVOKE.containsMatchIn(it.body) && WRITE.containsMatchIn(it.body) }
    }

    private companion object {
        val PACKAGES = listOf("alarm", "config")
        val CLASS = Regex("""(?m)^(?:(?:public|internal|private|open|abstract|data|sealed)\s+)*class\s+(\w+)""")
        val INVOKE = Regex("""operator\s+fun\s+invoke""")
        val WRITE =
            Regex(
                """\b\w*(?:[Rr]epository|[Ss]tore)\s*\??\.\s*(?:upsert|delete|insert|update|save|put|clear|remove)\w*\s*\(""" +
                    """|\bvalidateAndStore\s*\(|\brequestCodes\s*\??\.\s*next\s*\(""",
            )
        val GUARD = Regex("""\.whenIdle\b""")
    }
}
