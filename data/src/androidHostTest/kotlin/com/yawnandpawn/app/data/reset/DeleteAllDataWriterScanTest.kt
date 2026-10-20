package com.yawnandpawn.app.data.reset

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Story 5.9 review (item 8): "Delete all data" has one caller for each of its writes. Scans the shipped sources (main
 * source sets, plus the debug build's) of `:core`, `:data`, `:androidApp` and `:composeApp`, with comments blanked out.
 * Each write has a name of its own, so a call is found whatever its receiver:
 * - the deletion ports (`deleteAlarmData`, `deleteAllHistory`, `deleteSettledRecords`, `deletePurchaseIntents`,
 *   `clearSettings`, `resetInstallId`) are called only by `DeleteAllData`, inside its guard (`SessionLockGuardScanTest`);
 * - the DAO deletes are called only by their adapter: `AlarmDataDao.deleteAllAlarmData` and
 *   `PurchaseIntentDao.deleteAllExcept` by `AndroidLocalDataEraser`, `SessionHistoryDao.deleteRowsAndMerges` by
 *   `RoomSessionHistoryRepository`.
 */
class DeleteAllDataWriterScanTest {
    private val repoRoot = File("..").canonicalFile

    private val sources: List<Source> =
        MODULES
            .map { File(repoRoot, "$it/src") }
            .flatMap { src -> src.listFiles().orEmpty().filter { it.isDirectory && isShipped(it.name) } }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .map { Source(it.relativeTo(repoRoot).invariantSeparatorsPath, blankComments(it.readText())) }

    @Test
    fun `only DeleteAllData calls the deletion ports, and only their adapters call the DAO deletes`() {
        assertEquals(emptyList(), offenders(sources))
    }

    @Test
    fun `the scan sees the real callers, so it cannot pass by finding nothing`() {
        MODULES.forEach { module -> assertTrue(sources.any { it.path.startsWith("$module/src/") }, "no sources scanned in $module") }
        val real = sources.filter { it.path in EXEMPT.values }
        assertEquals(EXEMPT.values.toSet(), real.map { it.path }.toSet(), "every exempt file exists")
        assertEquals(EXEMPT.map { (rule, path) -> "$path calls $rule" }.toSet(), offenders(real, exempt = emptyMap()).toSet())
    }

    @Test
    fun `a second caller is reported, by path, whatever the receiver`() {
        val rogue =
            listOf(
                Source("composeApp/a/Reset.kt", "suspend fun f(e: LocalDataEraser) { e.deleteAlarmData(); e.clearSettings() }"),
                Source("androidApp/b/Koin.kt", "suspend fun g() = get<SessionHistoryEraser>().deleteAllHistory()"),
                Source("core/c/Billing.kt", "suspend fun h(l: PurchaseLedger) = l.deleteSettledRecords()"),
                Source("data/d/Ids.kt", "suspend fun i(p: DataStoreInstallIdProvider) = p.resetInstallId()"),
                Source("data/e/Chained.kt", "suspend fun j(db: AppDatabase) = db.alarmDataDao().deleteAllAlarmData()"),
                Source("data/f/Intents.kt", "suspend fun k(dao: PurchaseIntentDao) = dao.deleteAllExcept(emptyList())"),
                Source("data/g/History.kt", "suspend fun l(dao: SessionHistoryDao) = dao.deleteRowsAndMerges()"),
                Source("composeApp/h/Ui.kt", "val m = LocalDataEraser::deletePurchaseIntents"),
                // Not a deletion write: the session store's own clear and an unrelated delete.
                Source("data/i/Other.kt", "suspend fun n(s: ActiveSessionStore, a: AlarmRepository) { s.clear(); a.delete(id) }"),
            )

        assertEquals(
            listOf(
                "composeApp/a/Reset.kt calls deletion ports",
                "androidApp/b/Koin.kt calls deletion ports",
                "core/c/Billing.kt calls deletion ports",
                "data/d/Ids.kt calls deletion ports",
                "data/e/Chained.kt calls alarm data DAO delete",
                "data/f/Intents.kt calls intent DAO delete",
                "data/g/History.kt calls history DAO delete",
                "composeApp/h/Ui.kt calls deletion ports",
            ),
            offenders(rogue),
        )
    }

    /** A source file: [path] from the repository root, with `/` separators. */
    private data class Source(
        val path: String,
        val text: String,
    )

    private companion object {
        val MODULES = listOf("core", "data", "androidApp", "composeApp")

        const val CORE = "core/src/commonMain/kotlin/com/yawnandpawn/app/core"
        const val DATA = "data/src/commonMain/kotlin/com/yawnandpawn/app/data"
        const val DATA_ANDROID = "data/src/androidMain/kotlin/com/yawnandpawn/app/data"

        /** Each rule and the one file allowed to break it. */
        val RULES: Map<String, Regex> =
            linkedMapOf(
                "deletion ports" to
                    Regex(
                        """(?:\.|::)(?:deleteAlarmData|deleteAllHistory|deleteSettledRecords|deletePurchaseIntents""" +
                            """|clearSettings|resetInstallId)\b""",
                    ),
                "alarm data DAO delete" to Regex("""(?:\.|::)deleteAllAlarmData\b"""),
                "intent DAO delete" to Regex("""(?:\.|::)deleteAllExcept\b"""),
                "history DAO delete" to Regex("""(?:\.|::)deleteRowsAndMerges\b"""),
            )

        val EXEMPT: Map<String, String> =
            mapOf(
                "deletion ports" to "$CORE/reset/DeleteAllData.kt",
                "alarm data DAO delete" to "$DATA_ANDROID/reset/AndroidLocalDataEraser.kt",
                "intent DAO delete" to "$DATA_ANDROID/reset/AndroidLocalDataEraser.kt",
                "history DAO delete" to "$DATA/history/RoomSessionHistoryRepository.kt",
            )

        /** Main source sets, and the debug build's, ship; test source sets do not. */
        fun isShipped(sourceSet: String): Boolean = sourceSet == "main" || sourceSet == "debug" || sourceSet.endsWith("Main")

        /** [text] with `//` and block comments replaced by spaces, so KDoc that names a call is not a call. */
        fun blankComments(text: String): String = Regex("""/\*[\s\S]*?\*/|//[^\n]*""").replace(text) { " ".repeat(it.value.length) }

        /** Every rule a source breaks outside its exempt file, in source order, then rule order. */
        fun offenders(
            sources: List<Source>,
            exempt: Map<String, String> = EXEMPT,
        ): List<String> =
            sources.flatMap { source ->
                RULES
                    .filter { (name, regex) -> exempt[name] != source.path && regex.containsMatchIn(source.text) }
                    .map { (name, _) -> "${source.path} calls $name" }
            }
    }
}
