package com.yawnandpawn.app.tokens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesignTokenParserTest {
    private val fixture = checkNotNull(javaClass.getResource("/fixture-design.md")).readText()

    private fun withColor(value: String) = fixture.replace("accent: '#d96f14'", "accent: $value")

    private fun failureMessage(markdown: String): String =
        assertFailsWith<TokenParseException> { DesignTokenParser.parse(markdown) }.message.orEmpty()

    @Test
    fun `colours are parsed in file order and normalised to upper case`() {
        val tokens = DesignTokenParser.parse(fixture)

        assertEquals(
            listOf(
                ColorToken("bg", "#FAF8F5"),
                ColorToken("accent", "#D96F14"),
                ColorToken("bg-dark", "#111214"),
                ColorToken("accent-dark", "#F5A04E"),
                ColorToken("bg-sunrise", "#FFF6EA"),
                ColorToken("sunrise-gradient-top", "#FFE3C2"),
            ),
            tokens.colors,
        )
    }

    @Test
    fun `typography keeps size, line height and weight`() {
        val tokens = DesignTokenParser.parse(fixture)

        assertEquals(
            listOf(TypeToken("clock-xl", 88, 92, 300), TypeToken("body", 16, 24, 400)),
            tokens.typography,
        )
    }

    @Test
    fun `rounded tokens are dp values or fully rounded`() {
        val tokens = DesignTokenParser.parse(fixture)

        assertEquals(listOf(RoundedToken("sm", Radius.Dp(8)), RoundedToken("full", Radius.Full)), tokens.rounded)
    }

    @Test
    fun `spacing keeps dp values and skips prose values`() {
        val tokens = DesignTokenParser.parse(fixture)

        assertEquals(listOf(SpacingToken("1", 4), SpacingToken("screen-margin", 20)), tokens.spacing)
    }

    @Test
    fun `a three digit colour is rejected naming the key and value`() {
        val message = failureMessage(withColor("'#FFF'"))

        assertTrue(message.contains("'colors.accent' has value '#FFF'"), message)
    }

    @Test
    fun `a named colour is rejected naming the key and value`() {
        val message = failureMessage(withColor("orange"))

        assertTrue(message.contains("'colors.accent' has value 'orange'"), message)
    }

    @Test
    fun `an eight digit colour is read as RRGGBBAA, a seven digit one is rejected`() {
        val tokens = DesignTokenParser.parse(withColor("'#ffffffb8'"))

        assertEquals(ColorToken("accent", "#FFFFFFB8"), tokens.colors[1])
        failureMessage(withColor("'#FFD96F1'"))
    }

    @Test
    fun `a typography size without sp is rejected`() {
        val message = failureMessage(fixture.replace("fontSize: 16sp", "fontSize: 16px"))

        assertTrue(message.contains("typography.body.fontSize"), message)
    }

    @Test
    fun `a radius in px other than the pill value is rejected`() {
        val message = failureMessage(fixture.replace("sm: 8dp", "sm: 8px"))

        assertTrue(message.contains("rounded.sm"), message)
    }

    @Test
    fun `a file without frontmatter is rejected`() {
        failureMessage("# DESIGN.md\n")
    }

    @Test
    fun `a duplicate key is rejected instead of keeping the last value`() {
        val message = failureMessage(fixture.replace("  bg: '#FAF8F5'\n", "  bg: '#FAF8F5'\n  bg: '#FFFFFF'\n"))

        assertTrue(message.contains("duplicate key bg"), message)
    }

    @Test
    fun `a UTF-8 byte order mark is ignored`() {
        assertEquals(DesignTokenParser.parse(fixture), DesignTokenParser.parse(Char(0xFEFF) + fixture))
    }

    @Test
    fun `a spacing value in px or without a unit is rejected, prose is skipped`() {
        assertTrue(failureMessage(fixture.replace("screen-margin: 20dp", "screen-margin: 20px")).contains("spacing.screen-margin"))
        assertTrue(failureMessage(fixture.replace("screen-margin: 20dp", "screen-margin: 20")).contains("spacing.screen-margin"))
        assertTrue(DesignTokenParser.parse(fixture).spacing.none { it.name == "thumb-zone" })
    }

    @Test
    fun `CRLF line endings parse the same`() {
        assertEquals(DesignTokenParser.parse(fixture), DesignTokenParser.parse(fixture.replace("\n", "\r\n")))
    }
}
