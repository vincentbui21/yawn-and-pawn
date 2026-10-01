package com.yawnandpawn.app.tokens

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.YAMLException

/**
 * A colour token, e.g. `accent-dark` = `#F5A04E`: always upper-case `#RRGGBB`, or `#RRGGBBAA` (CSS order, alpha last)
 * for a translucent token such as the glass fill.
 */
data class ColorToken(
    val name: String,
    val hex: String,
)

/** A typography token; sizes in sp, weight 100..900. */
data class TypeToken(
    val name: String,
    val fontSizeSp: Int,
    val lineHeightSp: Int,
    val fontWeight: Int,
)

/** A corner radius: a dp value, or fully rounded (`9999px` in DESIGN.md). */
sealed interface Radius {
    data class Dp(
        val value: Int,
    ) : Radius

    data object Full : Radius
}

data class RoundedToken(
    val name: String,
    val radius: Radius,
)

data class SpacingToken(
    val name: String,
    val dp: Int,
)

/** The token groups generated into `PpsTokens.kt`. `components` is out of scope (Story 1.3). */
data class DesignTokens(
    val colors: List<ColorToken>,
    val typography: List<TypeToken>,
    val rounded: List<RoundedToken>,
    val spacing: List<SpacingToken>,
)

class TokenParseException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

/** Parses the YAML frontmatter of DESIGN.md. Pure; throws [TokenParseException] naming the bad key. */
object DesignTokenParser {
    private val hexColor = Regex("^#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?$")
    private val dpValue = Regex("^(\\d+)dp$")
    private val spValue = Regex("^(\\d+)sp$")

    /** A number with or without a unit: a spacing value like that which is not dp is an error, not prose. */
    private val numeric = Regex("^-?\\d+(\\.\\d+)?[a-z]*$")

    /** An editor may save DESIGN.md with a UTF-8 byte order mark. */
    private const val BOM = '\uFEFF'

    /** DESIGN.md writes the pill radius as `9999px`. */
    private const val FULL_RADIUS = "9999px"
    private val fontWeights = 100..900

    fun parse(markdown: String): DesignTokens {
        val root = loadFrontmatter(markdown)
        return DesignTokens(
            colors = group(root, "colors").map { (key, value) -> color(key, value) },
            typography = group(root, "typography").map { (key, value) -> type(key, value) },
            rounded = group(root, "rounded").map { (key, value) -> rounded(key, value) },
            spacing = group(root, "spacing").mapNotNull { (key, value) -> spacing(key, value) },
        )
    }

    /** The text between the opening `---` line and the next `---` line. */
    fun frontmatter(markdown: String): String {
        val lines = markdown.removePrefix(BOM.toString()).replace("\r\n", "\n").lines()
        if (lines.firstOrNull()?.trim() != "---") throw TokenParseException("DESIGN.md does not start with a '---' frontmatter block")
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (end < 0) throw TokenParseException("DESIGN.md frontmatter has no closing '---'")
        return lines.subList(1, end + 1).joinToString("\n")
    }

    private fun loadFrontmatter(markdown: String): Map<*, *> {
        val loaded =
            try {
                Yaml(SafeConstructor(LoaderOptions().apply { isAllowDuplicateKeys = false })).load<Any?>(frontmatter(markdown))
            } catch (e: YAMLException) {
                throw TokenParseException("DESIGN.md frontmatter is not valid YAML: ${e.message}", e)
            }
        return loaded as? Map<*, *> ?: throw TokenParseException("DESIGN.md frontmatter is not a YAML mapping")
    }

    private fun group(
        root: Map<*, *>,
        name: String,
    ): List<Pair<String, Any?>> {
        val map = root[name] as? Map<*, *> ?: throw TokenParseException("DESIGN.md frontmatter has no '$name' mapping")
        return map.entries.map { (key, value) -> key.toString() to value }
    }

    private fun color(
        key: String,
        value: Any?,
    ): ColorToken {
        val text = value?.toString().orEmpty()
        if (!hexColor.matches(text)) {
            throw TokenParseException("Colour token 'colors.$key' has value '$value'; expected #RRGGBB or #RRGGBBAA")
        }
        return ColorToken(key, text.uppercase())
    }

    private fun type(
        key: String,
        value: Any?,
    ): TypeToken {
        val style = value as? Map<*, *> ?: throw TokenParseException("Typography token 'typography.$key' is not a mapping")

        fun sp(field: String): Int {
            val raw = style[field]?.toString().orEmpty()
            return spValue
                .matchEntire(raw)
                ?.groupValues
                ?.get(1)
                ?.toInt()
                ?: throw TokenParseException("Typography token 'typography.$key.$field' has value '$raw'; expected <int>sp")
        }
        val weight = style["fontWeight"]?.toString()?.toIntOrNull()
        if (weight == null || weight !in fontWeights) {
            throw TokenParseException(
                "Typography token 'typography.$key.fontWeight' has value '${style["fontWeight"]}'; expected 100..900",
            )
        }
        return TypeToken(key, fontSizeSp = sp("fontSize"), lineHeightSp = sp("lineHeight"), fontWeight = weight)
    }

    /** dp values become tokens; prose (thumb-zone: 'bottom 40% of screen height') is skipped; px, sp or bare numbers fail. */
    private fun spacing(
        key: String,
        value: Any?,
    ): SpacingToken? {
        val text = value?.toString().orEmpty().trim()
        val dp = dpValue.matchEntire(text)
        return when {
            dp != null -> {
                SpacingToken(key, dp.groupValues[1].toInt())
            }

            value is Number || numeric.matches(text) -> {
                throw TokenParseException("Spacing token 'spacing.$key' has value '$text'; expected <int>dp")
            }

            else -> {
                null
            }
        }
    }

    private fun rounded(
        key: String,
        value: Any?,
    ): RoundedToken {
        val text = value?.toString().orEmpty()
        val dp = dpValue.matchEntire(text)
        val radius =
            when {
                text == FULL_RADIUS -> Radius.Full
                dp != null -> Radius.Dp(dp.groupValues[1].toInt())
                else -> throw TokenParseException("Rounded token 'rounded.$key' has value '$text'; expected <int>dp or $FULL_RADIUS")
            }
        return RoundedToken(key, radius)
    }
}
