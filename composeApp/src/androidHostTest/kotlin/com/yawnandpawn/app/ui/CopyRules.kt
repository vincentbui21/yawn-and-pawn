package com.yawnandpawn.app.ui

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** One UI string: resource key plus text (a plural or array item keeps its parent key). */
data class UiString(
    val key: String,
    val text: String,
    val file: String = "",
)

/**
 * FR-MSG-4 copy rules from Story 1.3, exactly: no em dash, no banned hype words, no hard-coded
 * currency symbols, never "backup" next to "check", no emoji outside `success_zero_snooze`,
 * `_headline` / `_title` at most 8 words, `_body` at most 25 words. Pure over [UiString]s.
 */
object CopyRules {
    const val HEADLINE_MAX_WORDS = 8
    const val BODY_MAX_WORDS = 25
    const val EMOJI_ALLOWED_KEY = "success_zero_snooze"

    private const val EM_DASH = '\u2014'

    /** The banned words and their inflections only ("elevator" is fine). */
    private val bannedWords =
        Regex(
            """\b(elevat(e|es|ed|ing)|seamless(ly)?|unleash(es|ed|ing)?|supercharg(e|es|ed|er|ing))\b""",
            RegexOption.IGNORE_CASE,
        )
    private val currency = Regex("[\$€£¥]")

    /** Positional placeholders (%1$s, %2$d) use a dollar sign that is not a currency symbol. */
    private val placeholder = Regex("""%\d+\$[a-z]""")

    /** "backup", "back-up" or "back up" next to "check(s)", in either order. */
    private val backupCheck = Regex("""\bback[\s-]?up[\s-]+checks?\b|\bchecks?[\s-]+back[\s-]?up\b""", RegexOption.IGNORE_CASE)

    /** Android string escapes that render as whitespace, so they separate words. */
    private val whitespaceEscapes = Regex("""\\[nt]""")
    private val emojiRanges =
        listOf(
            0x1F000..0x1FAFF,
            0x2300..0x23FF,
            0x2600..0x27BF,
            0x2B00..0x2BFF,
            0x203C..0x203C,
            0x2049..0x2049,
            0xFE0F..0xFE0F,
            0x200D..0x200D,
        )

    /** Variation selector and zero-width joiner are part of an emoji, not an emoji of their own. */
    private val emojiJoiners = setOf(0xFE0F, 0x200D)

    fun violations(string: UiString): List<String> {
        val where = "${string.file}${if (string.file.isEmpty()) "" else ": "}'${string.key}'"
        return (wordingViolations(string.text) + emojiViolations(string) + lengthViolations(string)).map { "$where: $it" }
    }

    private fun wordingViolations(text: String): List<String> =
        buildList {
            if (EM_DASH in text) add("em dash (use a period, comma or '·')")
            bannedWords.find(text)?.let { add("banned word '${it.value}'") }
            if (currency.containsMatchIn(text.replace(placeholder, ""))) add("hard-coded currency symbol (use a {price} argument)")
            if (backupCheck.containsMatchIn(text)) add("say 'fallback check', never 'backup check'")
        }

    private fun emojiViolations(string: UiString): List<String> {
        val emojiCodePoints =
            string.text
                .codePoints()
                .toArray()
                .filter { cp -> emojiRanges.any { cp in it } }
        val emojiCount = emojiCodePoints.count { it !in emojiJoiners }
        return when {
            string.key != EMOJI_ALLOWED_KEY && emojiCodePoints.isNotEmpty() -> listOf("emoji (only '$EMOJI_ALLOWED_KEY' may have one)")
            string.key == EMOJI_ALLOWED_KEY && emojiCount > 1 -> listOf("$emojiCount emoji (at most one)")
            else -> emptyList()
        }
    }

    private fun lengthViolations(string: UiString): List<String> {
        val key = string.key
        val words =
            string.text
                .replace(whitespaceEscapes, " ")
                .split(Regex("\\s+"))
                .count { it.isNotBlank() }
        return buildList {
            if ((key.endsWith("_headline") || key.endsWith("_title")) && words > HEADLINE_MAX_WORDS) {
                add("headline has $words words (max $HEADLINE_MAX_WORDS)")
            }
            if (key.endsWith("_body") && words > BODY_MAX_WORDS) add("body has $words words (max $BODY_MAX_WORDS)")
        }
    }

    /** Every `<string>`, `<plurals><item>` and `<string-array><item>` of a Compose resources strings file. */
    fun read(file: File): List<UiString> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val root = document.documentElement
        val children = (0 until root.childNodes.length).map { root.childNodes.item(it) }.filterIsInstance<Element>()
        val label = file.parentFile?.name.orEmpty() + "/" + file.name
        return children.flatMap { element ->
            val key = element.getAttribute("name")
            when (element.tagName) {
                "string" -> {
                    listOf(UiString(key, element.textContent, label))
                }

                "plurals", "string-array" -> {
                    val items = element.getElementsByTagName("item")
                    (0 until items.length).map { UiString(key, items.item(it).textContent, label) }
                }

                else -> {
                    emptyList()
                }
            }
        }
    }

    /**
     * The `values*` XML files under a resources directory: every XML file for Compose resources, or only
     * `strings*.xml` for Android `res` (where values folders also hold themes, colours, ...).
     */
    fun stringFiles(
        resourcesDir: File,
        onlyStringsFiles: Boolean = false,
    ): List<File> =
        resourcesDir
            .listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name.startsWith("values") }
            .flatMap { dir ->
                dir.listFiles().orEmpty().filter { it.extension == "xml" && (!onlyStringsFiles || it.name.startsWith("strings")) }
            }.sortedBy { it.path }
}
