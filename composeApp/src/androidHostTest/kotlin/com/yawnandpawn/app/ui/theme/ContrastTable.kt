package com.yawnandpawn.app.ui.theme

import androidx.compose.ui.graphics.Color
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** One row of the DESIGN.md "Verified contrast" table. */
data class ContrastRow(
    val theme: String,
    val foreground: String,
    val background: String,
    val kind: Kind,
    val recorded: Double,
    /** Rows written "**x, fails: ...**" are documented forbidden pairs that must stay below 3.0. */
    val documentedFailure: Boolean,
) {
    enum class Kind { Text, Graphic, Info }

    override fun toString(): String = "$theme | $foreground / $background"
}

/** Parses the table and checks it against the generated tokens. Pure, so fixtures can prove each check. */
object ContrastTable {
    const val TOLERANCE = 0.02
    const val TEXT_MIN = 4.5
    const val GRAPHIC_MIN = 3.0

    private val themes = setOf("Light", "Dark", "Sunrise")
    private val ratio = Regex("""^(\d+(?:\.\d+)?)""")

    /** Rows of the table under the "### Verified contrast" heading. Throws on a row it cannot read. */
    fun parse(markdown: String): List<ContrastRow> {
        val lines = markdown.replace("\r\n", "\n").lines()
        val start = lines.indexOfFirst { it.startsWith("### Verified contrast") }
        require(start >= 0) { "DESIGN.md has no '### Verified contrast' section" }
        return lines
            .drop(start + 1)
            .takeWhile { !it.startsWith("#") }
            .map { it.trim() }
            .filter { it.startsWith("|") }
            .map { row -> row.trim('|').split("|").map { it.trim() } }
            .filterNot { cells ->
                cells.first() == "Theme" || cells.all { cell -> cell.isNotEmpty() && cell.all { it == '-' || it == ':' } }
            }.map { cells ->
                require(cells.first() in themes) { "Contrast row $cells: unknown theme '${cells.first()}' (expected one of $themes)" }
                row(cells)
            }
    }

    private fun row(cells: List<String>): ContrastRow {
        require(cells.size == 4) { "Contrast row $cells does not have 4 columns" }
        val theme = cells[0]
        val pair = cells[1]
        val kind = cells[2]
        val value = cells[3]
        val names = pair.substringBefore("(").split("/").map { it.trim() }
        require(names.size == 2 && names.all { it.isNotEmpty() }) { "Contrast row $cells: pair '$pair' is not 'a / b'" }
        val cleaned = value.replace("*", "").trim()
        val recorded =
            requireNotNull(
                ratio
                    .find(cleaned)
                    ?.groupValues
                    ?.get(1)
                    ?.toDouble(),
            ) { "Contrast row $cells: no ratio in '$value'" }
        val parsedKind =
            requireNotNull(ContrastRow.Kind.entries.firstOrNull { it.name.equals(kind, ignoreCase = true) }) {
                "Contrast row $cells: unknown kind '$kind'"
            }
        return ContrastRow(theme, names[0], names[1], parsedKind, recorded, documentedFailure = cleaned.contains("fails"))
    }

    /** DESIGN.md key of [name] in [theme]: no suffix in Light, `-dark`, `-sunrise` (except `sunrise-*` tokens). */
    fun tokenKey(
        theme: String,
        name: String,
    ): String =
        when (theme) {
            "Dark" -> "$name-dark"
            "Sunrise" -> if (name.startsWith("sunrise-")) name else "$name-sunrise"
            else -> name
        }

    /** WCAG 2.x contrast ratio. */
    fun contrast(
        a: Color,
        b: Color,
    ): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun luminance(color: Color): Double {
        fun channel(value: Float): Double {
            val c = (value * 255).roundToInt() / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    /**
     * The colour a pair side names in [theme]: one token, or a stack written `top+below` (`glass+gradient-top`): each
     * translucent token composited over the next, the last one opaque. Missing keys are added to [missing].
     */
    fun resolve(
        theme: String,
        name: String,
        colors: Map<String, Color>,
        missing: MutableList<String>,
    ): Color? {
        val layers =
            name.split("+").map { part ->
                val key = tokenKey(theme, part.trim())
                colors[key] ?: null.also { missing += key }
            }
        if (layers.any { it == null }) return null
        return layers.filterNotNull().reduceRight { top, below -> composite(top, below) }
    }

    /** [top] (with its alpha) drawn over [below], as an opaque colour. */
    fun composite(
        top: Color,
        below: Color,
    ): Color {
        val a = top.alpha
        return Color(
            red = top.red * a + below.red * (1 - a),
            green = top.green * a + below.green * (1 - a),
            blue = top.blue * a + below.blue * (1 - a),
            alpha = 1f,
        )
    }

    /**
     * The [pairs] (foreground to background, DESIGN.md names) of [theme] that have no passing row in [rows]: missing from
     * the table, or only there as a documented failure (Story 3.12: a screen's pair list is checked against the table).
     */
    fun missingPairs(
        rows: List<ContrastRow>,
        theme: String,
        pairs: List<Pair<String, String>>,
    ): List<Pair<String, String>> =
        pairs.filter { (fg, bg) -> rows.none { it.theme == theme && it.foreground == fg && it.background == bg && !it.documentedFailure } }

    /** The rows of [theme] in [rows] for [pairs]. */
    fun rowsFor(
        rows: List<ContrastRow>,
        theme: String,
        pairs: List<Pair<String, String>>,
    ): List<ContrastRow> = rows.filter { row -> row.theme == theme && (row.foreground to row.background) in pairs }

    /** Every problem with [rows] against [colors] (DESIGN.md key to colour), one message per problem naming the row. */
    fun violations(
        rows: List<ContrastRow>,
        colors: Map<String, Color>,
    ): List<String> =
        rows.flatMap { row ->
            val missing = mutableListOf<String>()
            val fg = resolve(row.theme, row.foreground, colors, missing)
            val bg = resolve(row.theme, row.background, colors, missing)
            if (fg == null || bg == null) {
                return@flatMap listOf("$row: no generated token ${missing.joinToString()}")
            }
            val computed = contrast(fg, bg)
            val shown = String.format(Locale.ROOT, "%.2f", computed)
            buildList {
                if (abs(computed - row.recorded) > TOLERANCE) add("$row: recorded ${row.recorded} but tokens give $shown")
                when {
                    row.kind == ContrastRow.Kind.Info -> {
                        Unit
                    }

                    row.documentedFailure -> {
                        val limit = if (row.kind == ContrastRow.Kind.Text) TEXT_MIN else GRAPHIC_MIN
                        if (computed >= limit) add("$row: documented as failing but is $shown (>= $limit)")
                    }

                    row.kind == ContrastRow.Kind.Text -> {
                        if (computed < TEXT_MIN) add("$row: text pair $shown < 4.5")
                    }

                    row.kind == ContrastRow.Kind.Graphic -> {
                        if (computed < GRAPHIC_MIN) add("$row: graphic pair $shown < 3.0")
                    }
                }
            }
        }
}
