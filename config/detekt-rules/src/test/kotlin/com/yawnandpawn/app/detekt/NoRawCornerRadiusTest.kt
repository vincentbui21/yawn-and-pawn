package com.yawnandpawn.app.detekt

import dev.detekt.api.Config
import dev.detekt.test.lint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoRawCornerRadiusTest {
    private val rule = NoRawCornerRadius(Config.empty)

    @Test
    fun `a raw dp radius outside ui_theme is reported pointing to PpsTheme`() {
        val findings = rule.lint("package com.yawnandpawn.app.ui.home\nval card = RoundedCornerShape(12.dp)\n")

        assertEquals(1, findings.size)
        assertTrue(findings.single().message.contains("PpsTheme.shapes"))
    }

    @Test
    fun `named dp corners are reported`() {
        assertEquals(1, rule.findingsIn("val top = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)"))
    }

    @Test
    fun `a theme shape token outside ui_theme is not reported`() {
        assertEquals(0, rule.findingsIn("val card = RoundedCornerShape(PpsTokens.Rounded.md)"))
    }

    @Test
    fun `percent and pixel radii are reported and token-based ones are not`() {
        assertEquals(1, rule.findingsIn("val a = RoundedCornerShape(12)"))
        assertEquals(1, rule.findingsIn("val a = RoundedCornerShape(12f)"))
        assertEquals(1, rule.findingsIn("val a = RoundedCornerShape(percent = 25)"))
        assertEquals(0, rule.findingsIn("val a = RoundedCornerShape(percent = fullPercent)"))
    }

    @Test
    fun `a raw CornerSize is reported and a token CornerSize is not`() {
        assertEquals(1, rule.findingsIn("val a = CornerSize(12.dp)"))
        assertEquals(0, rule.findingsIn("val a = CornerSize(PpsTheme.spacing.space2)"))
    }

    @Test
    fun `a raw CutCornerShape is reported and a token one is not`() {
        assertEquals(1, rule.findingsIn("val a = CutCornerShape(4.dp)"))
        assertEquals(0, rule.findingsIn("val a = CutCornerShape(PpsTokens.Rounded.sm)"))
    }

    @Test
    fun `raw radii inside the theme package are not reported`() {
        assertEquals(
            0,
            rule.findingsIn("val md = RoundedCornerShape(16.dp)\nval f = CornerSize(percent = 50)", "com.yawnandpawn.app.ui.theme"),
        )
    }

    @Test
    fun `a look-alike ui_theme package elsewhere is still reported`() {
        assertEquals(1, rule.findingsIn("val md = RoundedCornerShape(16.dp)", "com.yawnandpawn.app.feature.ui.theme"))
    }
}
