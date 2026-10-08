package com.yawnandpawn.app.data

import com.yawnandpawn.app.core.billing.PurchaseSnapshot
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AD-7 (Story 4.9): the purchase rules live only in `PurchaseReconciler`. Scans the shipped sources (main source sets,
 * plus the debug build's) of `:core`, `:data`, `:androidApp` and `:composeApp`, with comments blanked out: nothing may
 * name a purchase's state, neither Play's (`purchaseState`, `getPurchaseState`, `PurchaseState`) nor the core
 * snapshot's (`purchaseState`, `PlayPurchaseState`), except the reconciler and the Play Billing adapter's mapping
 * (Story 4.12), which must use [ADAPTER_MAPPING_PATH]. Files are exempt by their path from the repository root, never
 * by bare file name. `PurchaseSnapshot` is not a data class, so its state cannot be destructured out without the name.
 */
class PurchaseStateReaderScanTest {
    // Host tests run in the :data project directory.
    private val repoRoot = File("..").canonicalFile

    private val sources: List<Source> by lazy {
        MODULES
            .map { File(repoRoot, "$it/src") }
            .flatMap { src -> src.listFiles().orEmpty().filter { it.isDirectory && isShipped(it.name) } }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .map { Source(it.relativeTo(repoRoot).invariantSeparatorsPath, it.readText()) }
    }

    @Test
    fun `only the reconciler and the adapter mapping read a purchase's state`() {
        assertEquals(emptyList(), offenders(sources))
    }

    @Test
    fun `the scan sees every module and the reconciler, so it cannot pass by finding nothing`() {
        MODULES.forEach { module -> assertTrue(sources.any { it.path.startsWith("$module/src/") }, "no sources scanned in $module") }
        val reconciler = sources.single { it.path == RECONCILER_PATH }
        assertEquals(listOf(RECONCILER_PATH), offenders(listOf(reconciler), exempt = emptySet()))
    }

    @Test
    fun `a reader of Play's or the snapshot's purchase state is reported by path, comments are not`() {
        val rogue =
            listOf(
                Source("androidApp/a/Billing.kt", "if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) grant()"),
                Source("androidApp/b/Java.kt", "val s = purchase.getPurchaseState()"),
                Source("androidApp/c/Const.kt", "val pending = PurchaseState.PENDING"),
                Source("core/d/Coordinator.kt", "if (snapshot.purchaseState == PlayPurchaseState.Pending) dispatch(PurchasePending)"),
                Source("core/e/Enum.kt", "val purchased = PlayPurchaseState.Purchased"),
                Source("composeApp/f/Safe.kt", "val s = snapshot?.purchaseState"),
                // Reads with no dot before the name: a scope function, and wildcard imports of either enum.
                Source("core/h/With.kt", "fun f(s: PurchaseSnapshot) = with(s) { purchaseState == Pending }"),
                Source("core/i/Wildcard.kt", "import com.yawnandpawn.app.core.billing.PlayPurchaseState.*\nval p = Pending"),
                Source("androidApp/j/PlayWildcard.kt", "import com.android.billingclient.api.Purchase.PurchaseState.*\nval p = PENDING"),
                Source("androidApp/k/Reference.kt", "val read = PurchaseSnapshot::purchaseState"),
                // Not readers: comments, and names that only contain the word.
                Source("core/g/Doc.kt", "// purchase.purchaseState\n/* PlayPurchaseState.Pending */ val myPurchaseStateLabel = 1"),
                Source("core/l/Names.kt", "val purchaseStateLabel = 1\nclass MyPurchaseStateView"),
                Source(ADAPTER_MAPPING_PATH, "Purchased.takeIf { purchase.purchaseState == Purchase.PurchaseState.PURCHASED }"),
            )

        assertEquals(
            listOf(
                "androidApp/a/Billing.kt",
                "androidApp/b/Java.kt",
                "androidApp/c/Const.kt",
                "core/d/Coordinator.kt",
                "core/e/Enum.kt",
                "composeApp/f/Safe.kt",
                "core/h/With.kt",
                "core/i/Wildcard.kt",
                "androidApp/j/PlayWildcard.kt",
                "androidApp/k/Reference.kt",
            ),
            offenders(rogue),
        )
    }

    @Test
    fun `a snapshot cannot be destructured, so its state is never read without the name the scan looks for`() {
        // `val (_, _, state) = snapshot` or `for ((t, p, s) in purchases)` need componentN, which only a data class has.
        val components =
            PurchaseSnapshot::class.java.methods
                .map { it.name }
                .filter { it.matches(Regex("""component\d+""")) }
        assertEquals(emptyList(), components)
    }

    /** A source file: [path] from the repository root, with `/` separators. */
    private data class Source(
        val path: String,
        val text: String,
    )

    private companion object {
        val MODULES = listOf("core", "data", "androidApp", "composeApp")

        const val RECONCILER_PATH = "core/src/commonMain/kotlin/com/yawnandpawn/app/core/billing/PurchaseReconciler.kt"

        /** Where Story 4.12 maps Play's `Purchase` to `PurchaseSnapshot`; the only other file allowed to read the state. */
        const val ADAPTER_MAPPING_PATH = "androidApp/src/main/kotlin/com/yawnandpawn/app/android/billing/PlayPurchaseMapping.kt"

        val EXEMPT = setOf(RECONCILER_PATH, ADAPTER_MAPPING_PATH)

        /**
         * Any use of the names at all, not only `x.name`: a scope function (`with(snapshot) { purchaseState }`), a
         * property reference, a wildcard import of either enum or a named argument all name them. Only the reconciler
         * and the adapter mapping need them.
         */
        val READS =
            listOf(
                Regex("""\bpurchaseState\b"""),
                Regex("""\bgetPurchaseState\b"""),
                Regex("""\bPurchaseState\b"""),
                Regex("""\bPlayPurchaseState\b"""),
            )

        val COMMENTS = Regex("""//[^\n]*|/\*[\s\S]*?\*/""")

        /** Main source sets, and the debug build's, ship; test source sets do not. */
        fun isShipped(sourceSet: String): Boolean = sourceSet == "main" || sourceSet == "debug" || sourceSet.endsWith("Main")

        /** The paths of the [sources] outside [exempt] that read a purchase's state. */
        fun offenders(
            sources: List<Source>,
            exempt: Set<String> = EXEMPT,
        ): List<String> =
            sources
                .filter { it.path !in exempt }
                .filter { source -> COMMENTS.replace(source.text, " ").let { code -> READS.any { it.containsMatchIn(code) } } }
                .map { it.path }
    }
}
