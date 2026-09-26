package com.yawnandpawn.app.detekt

import dev.detekt.api.Config
import dev.detekt.test.lint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoRawColorTest {
    private val rule = NoRawColor(Config.empty)

    @Test
    fun `a raw hex colour outside ui_theme is reported pointing to PpsTheme`() {
        val findings = rule.lint("package com.yawnandpawn.app.ui.home\nval brand = Color(0xFF112233)\n")

        assertEquals(1, findings.size)
        assertTrue(findings.single().message.contains("PpsTheme"))
    }

    @Test
    fun `a theme colour outside ui_theme is not reported`() {
        assertEquals(0, rule.findingsIn("val accent = Color(PpsTheme.colors.accent.value)"))
    }

    @Test
    fun `integer channel colours are reported and computed ones are not`() {
        assertEquals(1, rule.findingsIn("val red = Color(255, 0, 0)"))
        assertEquals(0, rule.findingsIn("val red = Color(red, green, blue)"))
    }

    @Test
    fun `float channel colours are reported and computed ones are not`() {
        assertEquals(1, rule.findingsIn("val dim = Color(0.2f, 0.1f, 0.1f)"))
        assertEquals(1, rule.findingsIn("val dim = Color(red = 0.2f, green = 0.1f, blue = 0.1f, alpha = 1f)"))
        assertEquals(0, rule.findingsIn("val dim = Color(0.2f, green, 0.1f)"))
    }

    @Test
    fun `named colours are reported but Transparent and Unspecified are not`() {
        assertEquals(1, rule.findingsIn("val a = Color.Red"))
        assertEquals(1, rule.findingsIn("val a = Color.Black"))
        assertEquals(1, rule.findingsIn("val a = androidx.compose.ui.graphics.Color.White"))
        assertEquals(0, rule.findingsIn("val a = Color.Transparent\nval b = Color.Unspecified"))
    }

    @Test
    fun `imports of Color are not reported`() {
        assertEquals(0, rule.findingsIn("import androidx.compose.ui.graphics.Color\nval a = Color.Transparent"))
    }

    @Test
    fun `raw colours inside the theme package and its sub-packages are not reported`() {
        val body = "val bg = Color(0xFFFAF8F5)\nval c = Color.White\nval d = Color(255, 0, 0)"
        assertEquals(0, rule.findingsIn(body, "com.yawnandpawn.app.ui.theme"))
        assertEquals(0, rule.findingsIn(body, "com.yawnandpawn.app.ui.theme.preview"))
    }

    @Test
    fun `a look-alike ui_theme package elsewhere is still reported`() {
        assertEquals(1, rule.findingsIn("val bg = Color(0xFFFAF8F5)", "com.yawnandpawn.app.feature.ui.theme"))
    }
}
