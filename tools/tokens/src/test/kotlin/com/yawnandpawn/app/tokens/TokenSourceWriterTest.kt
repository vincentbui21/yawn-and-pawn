package com.yawnandpawn.app.tokens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TokenSourceWriterTest {
    private val fixture = checkNotNull(javaClass.getResource("/fixture-design.md")).readText()
    private val source = TokenSourceWriter.render(DesignTokenParser.parse(fixture), "com.example.theme", "DESIGN.md")

    @Test
    fun `colours are grouped into Light, Dark and Sunrise with the suffix dropped`() {
        assertTrue(source.contains("    object Light {\n        val bg = Color(0xFFFAF8F5)\n        val accent = Color(0xFFD96F14)\n    }"))
        assertTrue(source.contains("    object Dark {\n        val bg = Color(0xFF111214)\n        val accent = Color(0xFFF5A04E)\n    }"))
        assertTrue(
            source.contains(
                "    object Sunrise {\n        val bg = Color(0xFFFFF6EA)\n        val sunriseGradientTop = Color(0xFFFFE3C2)\n    }",
            ),
        )
    }

    @Test
    fun `every colour is reachable by its DESIGN_md key`() {
        assertTrue(source.contains("\"accent-dark\" to Dark.accent,"))
        assertTrue(source.contains("\"sunrise-gradient-top\" to Sunrise.sunriseGradientTop,"))
    }

    @Test
    fun `type, rounded and spacing tokens are rendered`() {
        assertTrue(source.contains("        val clockXl =\n            PpsTypeToken(\n                fontSize = 88.sp,"))
        assertTrue(source.contains("lineHeight = 92.sp,"))
        assertTrue(source.contains("fontWeight = FontWeight(300),"))
        assertTrue(source.contains("val sm = CornerSize(8.dp)"))
        assertTrue(source.contains("val full = CornerSize(percent = 50)"))
        assertTrue(source.contains("val space1: Dp = 4.dp"))
        assertTrue(source.contains("val screenMargin: Dp = 20.dp"))
        assertFalse(source.contains("thumbZone"))
    }

    @Test
    fun `output is deterministic and uses the requested package`() {
        assertEquals(source, TokenSourceWriter.render(DesignTokenParser.parse(fixture), "com.example.theme", "DESIGN.md"))
        assertTrue(source.contains("\npackage com.example.theme\n"))
        assertFalse(source.contains("\r"))
    }

    @Test
    fun `two keys that map to the same Kotlin name are rejected`() {
        val tokens = DesignTokenParser.parse(fixture)
        val clash = tokens.copy(spacing = tokens.spacing + SpacingToken("screen--margin", 4))

        assertFailsWith<TokenParseException> { TokenSourceWriter.render(clash, "p", "DESIGN.md") }
    }

    @Test
    fun `drift reports the first differing line and ignores CRLF`() {
        assertNull(TokenDrift.firstDifference("a\nb\n", "a\r\nb\r\n"))
        assertEquals("first difference at line 2: expected 'b', found 'c'", TokenDrift.firstDifference("a\nb\n", "a\nc\n"))
        assertEquals("the file does not exist", TokenDrift.firstDifference("a\n", null))
    }
}
