package com.yawnandpawn.app.data.billing

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Story 4.10, AD-7: purchases have one writer and one consumer. Scans the shipped sources (main source sets, plus the
 * debug build's) of `:core`, `:data`, `:androidApp` and `:composeApp`, with comments blanked out. Every write has a
 * name of its own, so a call is found whatever its receiver (a parameter, a chained DAO getter, an adapter built by its
 * concrete type, a Koin lookup or a function reference):
 * - only `PurchaseLedger` writes records (`putRecord`), changes the grant ledger (`markConsumed`, `markSettled`,
 *   `purgeSettledBefore`) and calls any `consume`: consuming a token that granted nothing keeps money for nothing;
 * - only the Room adapters use the DAO writes (`upsertRecord`; `setConsumed`, `setSettled`, `deleteSettledBefore`);
 * - grant rows are inserted only through `RoomActiveSessionStore`'s commit (`insertGrant`, `commit(..., grants)`), and
 *   only `SessionEngine` makes a `RuntimeWrite.PutGrant`, in the transaction of the paid snooze.
 */
class PurchaseLedgerWriterScanTest {
    private val repoRoot = File("..").canonicalFile

    private val sources: List<Source> =
        MODULES
            .map { File(repoRoot, "$it/src") }
            .flatMap { src -> src.listFiles().orEmpty().filter { it.isDirectory && isShipped(it.name) } }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .map { Source(it.relativeTo(repoRoot).invariantSeparatorsPath, blankComments(it.readText())) }

    @Test
    fun `only the ledger writes records and consumes, and only the Room adapters use the DAO writes`() {
        assertEquals(emptyList(), offenders(sources))
    }

    @Test
    fun `the scan sees every module and the real writers, so it cannot pass by finding nothing`() {
        MODULES.forEach { module -> assertTrue(sources.any { it.path.startsWith("$module/src/") }, "no sources scanned in $module") }
        val real = sources.filter { it.path in EXEMPT.values }
        assertEquals(EXEMPT.values.toSet(), real.map { it.path }.toSet(), "every exempt file exists")
        // Without their exemptions, the real writers are exactly what the scan reports.
        assertEquals(EXEMPT.map { (rule, path) -> "$path calls $rule" }.toSet(), offenders(real, exempt = emptyMap()).toSet())
    }

    @Test
    fun `a second writer or consumer is reported, by path, whatever the receiver`() {
        val rogue =
            listOf(
                Source("core/a/History.kt", "suspend fun f(records: PurchaseRecordRepository) = records.putRecord(record)"),
                Source("androidApp/b/Koin.kt", "fun g() = get<PurchaseRecordRepository>().putRecord(record)"),
                Source("data/c/Concrete.kt", "suspend fun h(dao: PurchaseRecordDao) = RoomPurchaseRecordRepository(dao).putRecord(r)"),
                Source("data/d/Chained.kt", "suspend fun i(db: AppDatabase) = db.purchaseRecordDao().upsertRecord(entity)"),
                Source("core/e/Settle.kt", "suspend fun j(l: GrantLedgerStore) { l.markSettled(token, now) }"),
                Source("data/f/Concrete.kt", "suspend fun k(dao: GrantLedgerDao) = RoomGrantLedgerStore(dao).markConsumed(t)"),
                Source("data/g/Chained.kt", "suspend fun l(db: RuntimeDatabase) = db.grantLedgerDao().setSettled(t, 1)"),
                Source("data/h/Purge.kt", "suspend fun m(dao: GrantLedgerDao) = dao.deleteSettledBefore(0)"),
                Source("data/i/Insert.kt", "suspend fun n(dao: ActiveSessionDao) = dao.insertGrant(row)"),
                Source("data/j/Commit.kt", "suspend fun o(dao: ActiveSessionDao) = dao.commit(null, grants = listOf(row))"),
                Source("core/k/Engine.kt", "suspend fun p() = store.commit(state, listOf(RuntimeWrite.PutGrant(grant)))"),
                Source("androidApp/l/Coordinator.kt", "suspend fun q(billing: Billing) = billing.consume(token)"),
                Source("composeApp/m/Ui.kt", "val c = Billing::consume"),
                // Not a write: reads of the records, an unrelated delete and a map put.
                Source(
                    "composeApp/n/History.kt",
                    "suspend fun r(x: PurchaseRecordRepository, a: AlarmRepository) { x.all(); a.delete(id); m.put(k, v) }",
                ),
            )

        assertEquals(
            listOf(
                "core/a/History.kt calls putRecord",
                "androidApp/b/Koin.kt calls putRecord",
                "data/c/Concrete.kt calls putRecord",
                "data/d/Chained.kt calls upsertRecord",
                "core/e/Settle.kt calls ledger writes",
                "data/f/Concrete.kt calls ledger writes",
                "data/g/Chained.kt calls ledger DAO writes",
                "data/h/Purge.kt calls ledger DAO writes",
                "data/i/Insert.kt calls grant inserts",
                "data/j/Commit.kt calls grant inserts",
                "core/k/Engine.kt calls PutGrant",
                "androidApp/l/Coordinator.kt calls consume",
                "composeApp/m/Ui.kt calls consume",
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
        const val LEDGER_PATH = "$CORE/billing/PurchaseLedger.kt"
        const val ENGINE_PATH = "$CORE/session/SessionEngine.kt"
        const val RECORD_REPOSITORY_PATH = "$DATA/billing/RoomPurchaseRecordRepository.kt"
        const val LEDGER_STORE_PATH = "$DATA/session/RoomGrantLedgerStore.kt"
        const val SESSION_STORE_PATH = "$DATA/session/RoomActiveSessionStore.kt"

        /** Each rule and the one file allowed to break it. */
        val RULES: Map<String, Regex> =
            linkedMapOf(
                "putRecord" to Regex("""(?:\.|::)putRecord\b"""),
                "upsertRecord" to Regex("""(?:\.|::)upsertRecord\b"""),
                "ledger writes" to Regex("""(?:\.|::)(?:markConsumed|markSettled|purgeSettledBefore)\b"""),
                "ledger DAO writes" to Regex("""(?:\.|::)(?:setConsumed|setSettled|deleteSettledBefore)\b"""),
                "grant inserts" to Regex("""(?:\.|::)insertGrant\b|\.commit\([^)]*\bgrants\b"""),
                "PutGrant" to Regex("""(?<!class )\bPutGrant\("""),
                "consume" to Regex("""(?:\.|::)consume\b"""),
            )

        val EXEMPT: Map<String, String> =
            mapOf(
                "putRecord" to LEDGER_PATH,
                "upsertRecord" to RECORD_REPOSITORY_PATH,
                "ledger writes" to LEDGER_PATH,
                "ledger DAO writes" to LEDGER_STORE_PATH,
                "grant inserts" to SESSION_STORE_PATH,
                "PutGrant" to ENGINE_PATH,
                "consume" to LEDGER_PATH,
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
