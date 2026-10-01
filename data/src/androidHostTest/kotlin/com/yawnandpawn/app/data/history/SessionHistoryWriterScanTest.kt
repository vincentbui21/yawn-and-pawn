package com.yawnandpawn.app.data.history

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AD-18: session history has one writer. Scans the shipped sources (main source sets, plus the debug build's) of
 * `:core`, `:data`, `:androidApp` and `:composeApp`: only `RoomSessionHistoryRepository` may use the session history
 * DAO's write methods, and only `SessionRecorder` may call `SessionHistoryRepository.upsert`. Files are exempt by their
 * path from the repository root, never by bare file name. A text scan: an upsert counts when its receiver is a name
 * declared as (or fetched as) a `SessionHistoryRepository` in that file, or it is a `SessionHistoryRepository::upsert`
 * reference.
 */
class SessionHistoryWriterScanTest {
    // Host tests run in the :data project directory.
    private val repoRoot = File("..").canonicalFile

    private val sources: List<Source> =
        MODULES
            .map { File(repoRoot, "$it/src") }
            .flatMap { src -> src.listFiles().orEmpty().filter { it.isDirectory && isShipped(it.name) } }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .map { Source(it.relativeTo(repoRoot).invariantSeparatorsPath, it.readText()) }

    private fun source(path: String): Source = sources.single { it.path == path }

    private val daoWrites: Set<String> = daoWriteMethods(source(DAO_PATH).text)

    @Test
    fun `only the Room repository writes through the DAO and only SessionRecorder calls the repository upsert`() {
        assertEquals(emptyList(), offenders(sources, daoWrites))
    }

    @Test
    fun `the scan sees every module and the real writers, so it cannot pass by finding nothing`() {
        MODULES.forEach { module -> assertTrue(sources.any { it.path.startsWith("$module/src/") }, "no sources scanned in $module") }
        assertEquals(setOf("upsertRow"), daoWrites)
        // Without their exemptions, the two real writers are exactly what the scan reports.
        assertEquals(
            listOf("$RECORDER_PATH calls SessionHistoryRepository.upsert", "$REPOSITORY_PATH calls upsertRow"),
            offenders(listOf(source(RECORDER_PATH), source(REPOSITORY_PATH)), daoWrites, exempt = emptySet()),
        )
    }

    @Test
    fun `a second writer of the DAO or the repository is reported, by path`() {
        val rogue =
            listOf(
                Source("core/a/Stats.kt", "suspend fun f(history: SessionHistoryRepository) = history.upsert(row)"),
                Source("core/b/Ref.kt", "val writer = SessionHistoryRepository::upsert"),
                Source("androidApp/c/Koin.kt", "val repo = get<SessionHistoryRepository>()\nfun g() { repo?.upsert(row) }"),
                Source("composeApp/d/Inline.kt", "fun h() = get<SessionHistoryRepository>().upsert(row)"),
                Source("data/e/Dao.kt", "suspend fun i(dao: SessionHistoryDao) = dao.upsertRow(entity)"),
                Source(
                    "core/f/SessionRecorder.kt",
                    "class SessionRecorder(private val r: SessionHistoryRepository) { fun j() = r.upsert(x) }",
                ),
                // Not a history write: an alarm upsert in a file that also names a history row.
                Source("core/g/Alarms.kt", "suspend fun k(alarms: AlarmRepository, row: SessionHistoryRow) = alarms.upsert(alarm)"),
            )

        assertEquals(
            listOf(
                "core/a/Stats.kt calls SessionHistoryRepository.upsert",
                "core/b/Ref.kt calls SessionHistoryRepository.upsert",
                "androidApp/c/Koin.kt calls SessionHistoryRepository.upsert",
                "composeApp/d/Inline.kt calls SessionHistoryRepository.upsert",
                "data/e/Dao.kt calls upsertRow",
                "core/f/SessionRecorder.kt calls SessionHistoryRepository.upsert",
            ),
            offenders(rogue, setOf("upsertRow")),
        )
    }

    @Test
    fun `DAO write methods are found by their Room annotation`() {
        val dao =
            """
            @Upsert
            abstract suspend fun a(row: E)
            @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun b(row: E)
            @Query("SELECT * FROM t")
            abstract suspend fun c(): E?
            @Query("DELETE FROM t")
            abstract suspend fun d()
            @Update
            abstract suspend fun e(row: E): Int
            @Delete
            abstract suspend fun f(row: E)
            """.trimIndent()

        assertEquals(setOf("a", "b", "d", "e", "f"), daoWriteMethods(dao))
    }

    /** A source file: [path] from the repository root, with `/` separators. */
    private data class Source(
        val path: String,
        val text: String,
    )

    private companion object {
        val MODULES = listOf("core", "data", "androidApp", "composeApp")

        const val DAO_PATH = "data/src/commonMain/kotlin/com/yawnandpawn/app/data/history/SessionHistoryDao.kt"
        const val REPOSITORY_PATH = "data/src/commonMain/kotlin/com/yawnandpawn/app/data/history/RoomSessionHistoryRepository.kt"
        const val RECORDER_PATH = "core/src/commonMain/kotlin/com/yawnandpawn/app/core/session/SessionRecorder.kt"

        /** Who may do what: the DAO and the adapter may use the DAO writes, the recorder may upsert. */
        val EXEMPT = setOf(DAO_PATH, REPOSITORY_PATH, RECORDER_PATH)

        const val REPOSITORY = "SessionHistoryRepository"

        val WRITE_ANNOTATION =
            Regex("""@(Upsert|Insert|Update|Delete)\b|@Query\(\s*"\s*(DELETE|UPDATE|INSERT|REPLACE)\b""", RegexOption.IGNORE_CASE)
        val FUN_NAME = Regex("""\bfun\s+(\w+)\s*\(""")

        /** Names declared with the repository type (`name: SessionHistoryRepository`) or fetched as it from Koin. */
        val TYPED_NAME = Regex("""\b(\w+)\s*:\s*$REPOSITORY\b""")
        val FETCHED_NAME = Regex("""\b(\w+)\s*(?::[^=\n]*)?(?:=|by)\s*(?:\w+\.)?(?:get|inject)<$REPOSITORY>\(\)""")

        /** Upserts that need no declared name: a fetched instance or a function reference. */
        val DIRECT_UPSERT = Regex("""<$REPOSITORY>\(\)\s*\??\.\s*upsert\b|\b$REPOSITORY::upsert\b""")

        /** Main source sets, and the debug build's, ship; test source sets do not. */
        fun isShipped(sourceSet: String): Boolean = sourceSet == "main" || sourceSet == "debug" || sourceSet.endsWith("Main")

        /** The names of the functions in [daoText] that a Room write annotation marks. */
        fun daoWriteMethods(daoText: String): Set<String> {
            val names = mutableSetOf<String>()
            var annotated = false
            daoText.lines().forEach { line ->
                if (WRITE_ANNOTATION.containsMatchIn(line)) annotated = true
                val name = FUN_NAME.find(line)?.groupValues?.get(1)
                if (name != null) {
                    if (annotated) names += name
                    annotated = false
                } else if (line.trim().startsWith("@") && !WRITE_ANNOTATION.containsMatchIn(line)) {
                    annotated = false
                }
            }
            return names
        }

        /** True when [text] calls `upsert` on a `SessionHistoryRepository` it names or fetches, or references it. */
        fun callsRepositoryUpsert(text: String): Boolean {
            if (DIRECT_UPSERT.containsMatchIn(text)) return true
            val names = (TYPED_NAME.findAll(text) + FETCHED_NAME.findAll(text)).map { it.groupValues[1] }.toSet()
            return names.any { name -> Regex("""\b$name\s*\??\.\s*upsert\b|\b$name::upsert\b""").containsMatchIn(text) }
        }

        /** Every use of a DAO write outside the DAO and adapter, and every repository upsert outside the recorder. */
        fun offenders(
            sources: List<Source>,
            daoWrites: Set<String>,
            exempt: Set<String> = EXEMPT,
        ): List<String> =
            sources.flatMap { source ->
                val upserts =
                    if (source.path != RECORDER_PATH || source.path !in exempt) {
                        listOf("${source.path} calls $REPOSITORY.upsert").filter { callsRepositoryUpsert(source.text) }
                    } else {
                        emptyList()
                    }
                val daoUses =
                    if ((source.path == DAO_PATH || source.path == REPOSITORY_PATH) && source.path in exempt) {
                        emptyList()
                    } else {
                        daoWrites.filter { Regex("""(\.|::)$it\b""").containsMatchIn(source.text) }.map { "${source.path} calls $it" }
                    }
                upserts + daoUses
            }
    }
}
