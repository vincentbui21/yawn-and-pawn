package com.yawnandpawn.app.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AD-8, Story 4.2: the UI turns money into text only through the `MoneyFormatter` (`formatMoney`). Outside
 * `AndroidMoneyFormatter.kt`, no `:composeApp` source or `:androidApp` main source (comments aside) builds a currency
 * format, looks up a currency, or writes a currency symbol into a string. The debug design-preview samples pick the
 * preview's currency with `java.util.Currency` and are not scanned.
 */
class MoneyFormattingScanTest {
    private val composeSources =
        File(checkNotNull(System.getProperty("yawnandpawn.composeResources")) { "yawnandpawn.composeResources not set" })
            .resolve("../..")
            .canonicalFile

    private val appSources =
        File(checkNotNull(System.getProperty("yawnandpawn.androidRes")) { "yawnandpawn.androidRes not set" })
            .resolve("..")
            .canonicalFile

    private fun mainSources(): List<File> =
        (
            listOf("commonMain", "androidMain").map { composeSources.resolve(it) } +
                listOf(appSources.resolve("kotlin"))
        ).flatMap { root -> root.walk().filter { it.isFile && it.extension == "kt" }.toList() }

    @Test
    fun `money is formatted only by AndroidMoneyFormatter`() {
        val files = mainSources()
        assertTrue(files.size > 100, "scanned only ${files.size} files")
        assertTrue(files.any { it.name == FORMATTER }, "$FORMATTER not found")

        val violations =
            files
                .filter { it.name != FORMATTER }
                .flatMap { file -> violations(file.readText()).map { "${file.relativeTo(composeSources.parentFile.parentFile)}: $it" } }
        assertTrue(violations.isEmpty(), violations.joinToString("\n"))
    }

    @Test
    fun `the scan catches every banned pattern outside comments`() {
        assertEquals(
            listOf(
                "getCurrencyInstance",
                "Currency.getInstance",
                "DecimalFormat",
                "escaped dollar",
                "escaped dollar",
                "symbol €",
                "dollar digit",
            ),
            violations(sample).map { it.substringBefore(" at ") },
        )
    }

    /** One line per banned pattern, plus comments that mention them (not reported). [D] spells a dollar sign. */
    private val sample: String =
        listOf(
            "// NumberFormat.getCurrencyInstance() in a comment is fine, so is \"${D}1.00\" and \"€\".",
            "/* \"${D}1.00\" */",
            "val a = NumberFormat.getCurrencyInstance(locale)",
            "val b = java.util.Currency.getInstance(\"USD\")",
            "val c = DecimalFormat(\"0.00\")",
            "val d = \"\\${D}\" + price",
            "val e = \"$D{'$D'}\" + price",
            "val f = \"1 €\"",
            "val g = \"Pay ${D}3 now\"",
        ).joinToString("\n")

    private fun violations(source: String): List<String> {
        val code = source.replace(blockComment, " ").lines().joinToString("\n") { it.replace(lineComment, "") }
        return banned.flatMap { (name, pattern) -> pattern.findAll(code).map { "$name at ${it.value.trim()}" } }
    }

    private companion object {
        const val FORMATTER = "AndroidMoneyFormatter.kt"
        const val D = '$'
        val blockComment = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        val lineComment = Regex("""(^|\s)//.*$""")
        val banned: List<Pair<String, Regex>> =
            listOf(
                "getCurrencyInstance" to Regex("""getCurrencyInstance"""),
                "Currency.getInstance" to Regex("""Currency\.getInstance"""),
                "DecimalFormat" to Regex("""\bDecimalFormat\b"""),
                "escaped dollar" to Regex("""\\\$|\$\{'\$'\}"""),
                "symbol €" to Regex("""€"""),
                "symbol £" to Regex("""£"""),
                "symbol ¥" to Regex("""[¥￥]"""),
                "symbol ₫" to Regex("""₫"""),
                "dollar digit" to Regex("""\$\d"""),
            )
    }
}
