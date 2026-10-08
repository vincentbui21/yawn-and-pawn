package com.yawnandpawn.app.data.billing

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Story 4.10, AD-7: purchases have one writer and one consumer. Scans the shipped sources (main source sets, plus the
 * debug build's) of `:core`, `:data`, `:androidApp` and `:composeApp`, with comments blanked out:
 * - only `RoomPurchaseRecordRepository` uses the `purchase_record` DAO's writes, and only `PurchaseLedger` calls
 *   `PurchaseRecordRepository.put`;
 * - only `RoomGrantLedgerStore` uses the `grant_ledger` DAO's writes (rows are inserted only by `ActiveSessionDao.commit`),
 *   and only `PurchaseLedger` calls `GrantLedgerStore.markConsumed` or `delete`;
 * - only `PurchaseLedger` calls `consume` on anything: consuming a token that granted nothing keeps money for nothing.
 * Files are exempt by their path from the repository root. A call counts when its receiver is a name declared as (or
 * fetched as) the port in that file, a fetched instance, or a function reference.
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
        val real = sources.filter { it.path in EXEMPT.keys }
        assertEquals(EXEMPT.keys, real.map { it.path }.toSet(), "every exempt file exists")
        // Without their exemptions, the real writers are exactly what the scan reports.
        assertEquals(
            setOf(
                "$LEDGER_PATH calls PurchaseRecordRepository.put",
                "$LEDGER_PATH calls GrantLedgerStore.markConsumed|delete",
                "$LEDGER_PATH calls consume",
                "$RECORD_REPOSITORY_PATH calls upsertRecord",
                "$LEDGER_STORE_PATH calls setStatus|delete",
            ),
            offenders(real, exempt = emptyMap()).toSet(),
        )
    }

    @Test
    fun `a second writer or consumer is reported, by path`() {
        val rogue =
            listOf(
                Source("core/a/History.kt", "suspend fun f(records: PurchaseRecordRepository) = records.put(record)"),
                Source("androidApp/b/Koin.kt", "fun g() = get<PurchaseRecordRepository>().put(record)"),
                Source("core/c/Ref.kt", "val w = PurchaseRecordRepository::put"),
                Source("data/d/Dao.kt", "suspend fun h(dao: PurchaseRecordDao) = dao.upsertRecord(entity)"),
                Source("core/e/Settle.kt", "suspend fun i(l: GrantLedgerStore) { l.delete(token) }"),
                Source("data/f/Ledger.kt", "suspend fun j(dao: GrantLedgerDao) = dao.setStatus(t, \"consumed\")"),
                Source("androidApp/g/Coordinator.kt", "suspend fun k(billing: Billing) = billing.consume(token)"),
                Source("composeApp/h/Ui.kt", "val c = Billing::consume"),
                // Not a write: a read of the records, and an unrelated delete.
                Source(
                    "composeApp/i/History.kt",
                    "suspend fun l(r: PurchaseRecordRepository, alarms: AlarmRepository) { r.all(); alarms.delete(id) }",
                ),
            )

        assertEquals(
            listOf(
                "core/a/History.kt calls PurchaseRecordRepository.put",
                "androidApp/b/Koin.kt calls PurchaseRecordRepository.put",
                "core/c/Ref.kt calls PurchaseRecordRepository.put",
                "data/d/Dao.kt calls upsertRecord",
                "core/e/Settle.kt calls GrantLedgerStore.markConsumed|delete",
                "data/f/Ledger.kt calls setStatus|delete",
                "androidApp/g/Coordinator.kt calls consume",
                "composeApp/h/Ui.kt calls consume",
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

        const val LEDGER_PATH = "core/src/commonMain/kotlin/com/yawnandpawn/app/core/billing/PurchaseLedger.kt"
        const val RECORD_DAO_PATH = "data/src/commonMain/kotlin/com/yawnandpawn/app/data/billing/PurchaseRecordDao.kt"
        const val RECORD_REPOSITORY_PATH = "data/src/commonMain/kotlin/com/yawnandpawn/app/data/billing/RoomPurchaseRecordRepository.kt"
        const val LEDGER_DAO_PATH = "data/src/commonMain/kotlin/com/yawnandpawn/app/data/session/GrantLedgerDao.kt"
        const val LEDGER_STORE_PATH = "data/src/commonMain/kotlin/com/yawnandpawn/app/data/session/RoomGrantLedgerStore.kt"

        /** The rules; [EXEMPT] names the one file allowed to break each. */
        val RULES =
            listOf(
                Rule("PurchaseRecordRepository.put") { callsPort(it, "PurchaseRecordRepository", "put") },
                Rule("GrantLedgerStore.markConsumed|delete") { callsPort(it, "GrantLedgerStore", "(?:markConsumed|delete)") },
                Rule("consume") { Regex("""(?:\.|::)consume\b""").containsMatchIn(it) },
                Rule("upsertRecord") { Regex("""(?:\.|::)upsertRecord\b""").containsMatchIn(it) },
                Rule("setStatus|delete") { callsPort(it, "GrantLedgerDao", "(?:setStatus|delete)") },
            )

        /** Which file may do what: the ledger and the two Room adapters, plus the DAOs that declare the writes. */
        val EXEMPT: Map<String, Set<String>> =
            mapOf(
                LEDGER_PATH to setOf("PurchaseRecordRepository.put", "GrantLedgerStore.markConsumed|delete", "consume"),
                RECORD_REPOSITORY_PATH to setOf("upsertRecord"),
                LEDGER_STORE_PATH to setOf("setStatus|delete"),
                RECORD_DAO_PATH to emptySet(),
                LEDGER_DAO_PATH to emptySet(),
            )

        class Rule(
            val name: String,
            val matches: (String) -> Boolean,
        )

        /** Main source sets, and the debug build's, ship; test source sets do not. */
        fun isShipped(sourceSet: String): Boolean = sourceSet == "main" || sourceSet == "debug" || sourceSet.endsWith("Main")

        /** [text] with `//` and block comments replaced by spaces, so KDoc that names a call is not a call. */
        fun blankComments(text: String): String = Regex("""/\*[\s\S]*?\*/|//[^\n]*""").replace(text) { " ".repeat(it.value.length) }

        /**
         * True when [text] calls [method] on a [port] it names (`name: Port`, `get<Port>()`), on a fetched instance, or
         * through a `Port::method` reference.
         */
        fun callsPort(
            text: String,
            port: String,
            method: String,
        ): Boolean {
            if (Regex("""<$port>\(\)\s*\??\.\s*$method\b|\b$port::$method\b""").containsMatchIn(text)) return true
            val typed = Regex("""\b(\w+)\s*:\s*$port\b""").findAll(text)
            val fetched = Regex("""\b(\w+)\s*(?::[^=\n]*)?(?:=|by)\s*(?:\w+\.)?(?:get|inject)<$port>\(\)""").findAll(text)
            val names = (typed + fetched).map { it.groupValues[1] }.toSet()
            return names.any { name -> Regex("""\b$name\s*\??\.\s*$method\s*\(""").containsMatchIn(text) }
        }

        fun offenders(
            sources: List<Source>,
            exempt: Map<String, Set<String>> = EXEMPT,
        ): List<String> =
            RULES
                .flatMap { rule ->
                    sources
                        .filter { rule.name !in exempt[it.path].orEmpty() }
                        .filter { rule.matches(it.text) }
                        .map { "${it.path} calls ${rule.name}" }
                }.sortedBy { line -> sources.indexOfFirst { line.startsWith(it.path + " ") } }
    }
}
