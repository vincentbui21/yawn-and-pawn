package com.yawnandpawn.app.detekt

import dev.detekt.api.Config
import dev.detekt.test.lint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoRawSpTest {
    private val rule = NoRawSp(Config.empty)

    @Test
    fun `a raw sp size outside ui_theme is reported pointing to PpsTheme`() {
        val findings = rule.lint("package com.yawnandpawn.app.ui.home\nval size = 14.sp\n")

        assertEquals(1, findings.size)
        assertTrue(findings.single().message.contains("PpsTheme.typography"))
    }

    @Test
    fun `float and negative sp literals are reported`() {
        assertEquals(2, rule.findingsIn("val half = 13.5f.sp\nval neg = (-2).sp"))
    }

    @Test
    fun `a typography token outside ui_theme is not reported`() {
        assertEquals(0, rule.findingsIn("val size = PpsTheme.typography.body.fontSize\nval dp = 14.dp"))
    }

    @Test
    fun `em literals are reported and computed em values are not`() {
        assertEquals(1, rule.findingsIn("val tracking = 14.em"))
        assertEquals(0, rule.findingsIn("val tracking = spacing.em"))
    }

    @Test
    fun `TextUnit literals are reported and token-based ones are not`() {
        assertEquals(1, rule.findingsIn("val size = TextUnit(14f, TextUnitType.Sp)"))
        assertEquals(0, rule.findingsIn("val size = TextUnit(body.fontSize.value, TextUnitType.Sp)"))
    }

    @Test
    fun `raw sizes inside the theme package are not reported`() {
        assertEquals(0, rule.findingsIn("val body = 16.sp\nval e = 1.em", "com.yawnandpawn.app.ui.theme"))
    }

    @Test
    fun `a look-alike ui_theme package elsewhere is still reported`() {
        assertEquals(1, rule.findingsIn("val body = 16.sp", "com.yawnandpawn.app.feature.ui.theme"))
    }
}
