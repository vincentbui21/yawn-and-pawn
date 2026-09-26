package com.yawnandpawn.app.ui.theme

import androidx.compose.ui.graphics.Color
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The DESIGN.md "Verified contrast" table is the gate (UX-DR8): every row is recomputed from PpsTokens. */
class ContrastTest {
    private val designMd = File(checkNotNull(System.getProperty("yawnandpawn.designMd")) { "yawnandpawn.designMd not set" })
    private val markdown = designMd.readText()

    @Test
    fun `every verified contrast row matches the generated tokens`() {
        val rows = ContrastTable.parse(markdown)
        val section = markdown.substringAfter("### Verified contrast").substringBefore(NEXT_HEADING)
        val tableLines = section.lines().count { line -> line.trim().startsWith("|") } - 2 // minus header and separator

        assertEquals(tableLines, rows.size, "every table row is parsed")
        listOf("Light", "Dark", "Sunrise").forEach { theme -> assertTrue(rows.any { it.theme == theme }, "no $theme rows") }
        val violations = ContrastTable.violations(rows, PpsTokens.colorsByName)
        assertTrue(violations.isEmpty(), violations.joinToString("\n"))
    }

    @Test
    fun `no background token is pure black or pure white`() {
        listOf("bg", "bg-dark", "bg-sunrise").forEach { key ->
            val color = checkNotNull(PpsTokens.colorsByName[key]) { "missing $key" }
            assertTrue(color != Color.Black && color != Color.White, "$key is $color")
        }
    }

    @Test
    fun `Sunrise fallbacks to Light tokens pass on every Sunrise background they can appear on`() {
        // DESIGN.md has no snoozed/missed/inverse-accent -sunrise tokens; SunrisePpsColors reuses Light ones.
        val sunrise = SunrisePpsColors
        listOf("snoozed" to sunrise.snoozed, "missed" to sunrise.missed).forEach { (name, color) ->
            listOf("bg-sunrise" to sunrise.bg, "surface-sunrise" to sunrise.surface).forEach { (bgName, bg) ->
                val ratio = ContrastTable.contrast(color, bg)
                assertTrue(ratio >= ContrastTable.TEXT_MIN, "$name / $bgName is $ratio, below 4.5 (text) / 3.0 (graphic)")
            }
        }
        // inverse-accent is only a snackbar action colour, on inverse-surface.
        val action = ContrastTable.contrast(sunrise.inverseAccent, sunrise.inverseSurface)
        assertTrue(action >= ContrastTable.TEXT_MIN, "inverse-accent / inverse-surface-sunrise is $action")
    }

    // Fixtures: each check fails on a bad row or token.

    private val fixture =
        """
        ### Verified contrast (WCAG 2.x)

        | Theme | Pair | Kind | Ratio |
        |---|---|---|---|
        | Light | text / bg | text | 16.84 |
        | Sunrise | accent / surface (countdown ring) | graphic | 3.37 |
        | Sunrise | accent / sunrise-gradient-top | graphic | **2.73, fails: no accent on the gradient** |
        | Light | snoozed / missed | info | 1.03 (told apart by glyph) |

        ## Typography
        """.trimIndent()

    @Test
    fun `the fixture table parses pair names, kinds, ratios and documented failures`() {
        val rows = ContrastTable.parse(fixture)

        assertEquals(4, rows.size)
        assertEquals(ContrastRow("Sunrise", "accent", "surface", ContrastRow.Kind.Graphic, 3.37, false), rows[1])
        assertEquals(ContrastRow("Sunrise", "accent", "sunrise-gradient-top", ContrastRow.Kind.Graphic, 2.73, true), rows[2])
        assertEquals(1.03, rows[3].recorded)
        assertTrue(ContrastTable.violations(rows, PpsTokens.colorsByName).isEmpty())
    }

    @Test
    fun `a token drift of more than 0_02 fails naming the row`() {
        val drifted = PpsTokens.colorsByName + ("bg" to Color(0xFFE0DED8))

        val violations = ContrastTable.violations(ContrastTable.parse(fixture), drifted)

        assertTrue(violations.any { it.startsWith("Light | text / bg: recorded 16.84") }, violations.toString())
    }

    @Test
    fun `a text pair below 4_5 fails`() {
        val row = ContrastRow("Light", "missed", "bg", ContrastRow.Kind.Text, 5.26, false)
        val weak = PpsTokens.colorsByName + ("missed" to PpsTokens.Light.outline)

        val violations = ContrastTable.violations(listOf(row), weak)

        assertTrue(violations.any { it.contains("text pair") && it.contains("< 4.5") }, violations.toString())
    }

    @Test
    fun `a graphic pair below 3_0 fails`() {
        val row = ContrastRow("Sunrise", "accent", "surface-variant", ContrastRow.Kind.Graphic, 2.89, false)

        val violations = ContrastTable.violations(listOf(row), PpsTokens.colorsByName)

        assertTrue(violations.any { it.contains("graphic pair 2.89 < 3.0") }, violations.toString())
    }

    @Test
    fun `a documented failing pair that reaches 3_0 fails`() {
        val row = ContrastRow("Sunrise", "text", "bg", ContrastRow.Kind.Graphic, 16.68, true)

        val violations = ContrastTable.violations(listOf(row), PpsTokens.colorsByName)

        assertTrue(violations.any { it.contains("documented as failing") }, violations.toString())
    }

    @Test
    fun `a documented failing text pair is held to the text limit`() {
        // 3.18 is below 4.5, so a text row documented as failing is correct even though it passes 3.0.
        val row = ContrastRow("Light", "accent", "bg", ContrastRow.Kind.Text, 3.18, true)

        assertTrue(ContrastTable.violations(listOf(row), PpsTokens.colorsByName).isEmpty())
    }

    @Test
    fun `a row with an unknown theme fails parsing`() {
        val table = fixture.replace("| Light | snoozed / missed", "| Night | snoozed / missed")

        val error = runCatching { ContrastTable.parse(table) }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("unknown theme 'Night'"), error.toString())
    }

    @Test
    fun `info rows are only ratio-checked`() {
        val row = ContrastRow("Light", "snoozed", "missed", ContrastRow.Kind.Info, 1.03, false)

        assertTrue(ContrastTable.violations(listOf(row), PpsTokens.colorsByName).isEmpty())
    }

    @Test
    fun `a row naming an unknown token fails`() {
        val row = ContrastRow("Sunrise", "snoozed", "bg", ContrastRow.Kind.Text, 5.0, false)

        val violations = ContrastTable.violations(listOf(row), PpsTokens.colorsByName)

        assertEquals(listOf("Sunrise | snoozed / bg: no generated token snoozed-sunrise"), violations)
    }

    private companion object {
        /** A markdown heading at the start of a line ends the section. */
        const val NEXT_HEADING = "\n#"
    }
}
