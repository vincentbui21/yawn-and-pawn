package com.yawnandpawn.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PpsThemeTest {
    @Test
    fun `System mode follows the system dark setting`() {
        assertEquals(PpsColorSet.Light, resolveColorSet(PpsThemeMode.System, wake = false, systemInDarkTheme = false))
        assertEquals(PpsColorSet.Dark, resolveColorSet(PpsThemeMode.System, wake = false, systemInDarkTheme = true))
    }

    @Test
    fun `explicit Light and Dark ignore the system setting`() {
        assertEquals(PpsColorSet.Light, resolveColorSet(PpsThemeMode.Light, wake = false, systemInDarkTheme = true))
        assertEquals(PpsColorSet.Dark, resolveColorSet(PpsThemeMode.Dark, wake = false, systemInDarkTheme = false))
    }

    @Test
    fun `wake always uses Sunrise`() {
        PpsThemeMode.entries.forEach { mode ->
            listOf(false, true).forEach { dark ->
                assertEquals(PpsColorSet.Sunrise, resolveColorSet(mode, wake = true, systemInDarkTheme = dark))
            }
        }
    }

    @Test
    fun `each set maps to its generated tokens`() {
        assertEquals(PpsTokens.Light.bg, PpsColorSet.Light.colors().bg)
        assertEquals(PpsTokens.Dark.accentText, PpsColorSet.Dark.colors().accentText)
        assertEquals(PpsTokens.Sunrise.accent, PpsColorSet.Sunrise.colors().accent)
        assertEquals(PpsTokens.Sunrise.sunriseGradientTop, SunrisePpsColors.sunriseGradientTop)
        assertNull(LightPpsColors.sunriseGradientTop)
        assertTrue(DarkPpsColors.isDark)
        assertFalse(SunrisePpsColors.isDark)
    }

    @Test
    fun `the Material colour scheme uses the named DESIGN_md roles`() {
        PpsColorSet.entries.map { it.colors() }.forEach { colors ->
            val scheme = colors.toColorScheme()
            assertEquals(colors.bg, scheme.background)
            assertEquals(colors.surface, scheme.surface)
            assertEquals(colors.surfaceVariant, scheme.surfaceVariant)
            assertEquals(colors.text, scheme.onBackground)
            assertEquals(colors.text, scheme.onSurface)
            assertEquals(colors.textSecondary, scheme.onSurfaceVariant)
            assertEquals(colors.accent, scheme.primary)
            assertEquals(colors.onAccent, scheme.onPrimary)
            assertEquals(colors.outline, scheme.outline)
            assertEquals(colors.outlineSubtle, scheme.outlineVariant)
            assertEquals(colors.error, scheme.error)
            assertEquals(colors.inverseSurface, scheme.inverseSurface)
            assertEquals(colors.inverseText, scheme.inverseOnSurface)
            assertEquals(colors.inverseAccent, scheme.inversePrimary)
        }
    }

    /** DESIGN.md keys of each set; Sunrise also gets its documented Light fallbacks (see SunrisePpsColors). */
    private fun tokensOf(set: PpsColorSet): Set<Color> {
        val keys = PpsTokens.colorsByName.keys
        val setKeys =
            when (set) {
                PpsColorSet.Dark -> keys.filter { it.endsWith("-dark") }
                PpsColorSet.Sunrise -> keys.filter { it.endsWith("-sunrise") || it.startsWith("sunrise-") } + SUNRISE_LIGHT_FALLBACKS
                PpsColorSet.Light -> keys.filter { !it.endsWith("-dark") && !it.endsWith("-sunrise") && !it.startsWith("sunrise-") }
            }
        return setKeys.map { PpsTokens.colorsByName.getValue(it) }.toSet()
    }

    @Test
    fun `every Material colour role comes from a token of its own set`() {
        PpsColorSet.entries.forEach { set ->
            val tokenColors = tokensOf(set)
            val scheme = set.colors().toColorScheme()
            val roles =
                listOf(
                    scheme.primary,
                    scheme.onPrimary,
                    scheme.primaryContainer,
                    scheme.onPrimaryContainer,
                    scheme.inversePrimary,
                    scheme.secondary,
                    scheme.onSecondary,
                    scheme.secondaryContainer,
                    scheme.onSecondaryContainer,
                    scheme.tertiary,
                    scheme.onTertiary,
                    scheme.tertiaryContainer,
                    scheme.onTertiaryContainer,
                    scheme.background,
                    scheme.onBackground,
                    scheme.surface,
                    scheme.onSurface,
                    scheme.surfaceVariant,
                    scheme.onSurfaceVariant,
                    scheme.surfaceTint,
                    scheme.inverseSurface,
                    scheme.inverseOnSurface,
                    scheme.error,
                    scheme.onError,
                    scheme.errorContainer,
                    scheme.onErrorContainer,
                    scheme.outline,
                    scheme.outlineVariant,
                    scheme.scrim,
                    scheme.surfaceBright,
                    scheme.surfaceDim,
                    scheme.surfaceContainer,
                    scheme.surfaceContainerHigh,
                    scheme.surfaceContainerHighest,
                    scheme.surfaceContainerLow,
                    scheme.surfaceContainerLowest,
                    scheme.primaryFixed,
                    scheme.primaryFixedDim,
                    scheme.onPrimaryFixed,
                    scheme.onPrimaryFixedVariant,
                    scheme.secondaryFixed,
                    scheme.secondaryFixedDim,
                    scheme.onSecondaryFixed,
                    scheme.onSecondaryFixedVariant,
                    scheme.tertiaryFixed,
                    scheme.tertiaryFixedDim,
                    scheme.onTertiaryFixed,
                    scheme.onTertiaryFixedVariant,
                )
            roles.forEachIndexed { index, color -> assertTrue(color in tokenColors, "$set role #$index is $color, not a token") }
        }
    }

    @Test
    fun `the type ramp has the DESIGN_md sizes and weights`() {
        val type = ppsTypography()
        val expected =
            listOf(
                Triple(type.clockXl, 88 to 92, 300),
                Triple(type.display, 48 to 52, 500),
                Triple(type.headline, 28 to 34, 600),
                Triple(type.title, 20 to 26, 600),
                Triple(type.buttonWake, 20 to 24, 500),
                Triple(type.body, 16 to 24, 400),
                Triple(type.label, 14 to 20, 500),
                Triple(type.caption, 12 to 16, 400),
            )
        expected.forEach { (style, sizes, weight) ->
            assertEquals(sizes.first.sp, style.fontSize)
            assertEquals(sizes.second.sp, style.lineHeight)
            assertEquals(FontWeight(weight), style.fontWeight)
        }
    }

    @Test
    fun `tabular figures are on for clock-xl, display, title and button-wake only`() {
        val type = ppsTypography()
        listOf(type.clockXl, type.display, type.title, type.buttonWake).forEach { assertEquals("tnum", it.fontFeatureSettings) }
        listOf(type.headline, type.body, type.label, type.caption).forEach { assertNull(it.fontFeatureSettings) }
    }

    /** Linear font scaling (Android 13 and older), where the cap is needed. */
    private class LinearDensity(
        override val fontScale: Float,
    ) : Density {
        override val density = 1f

        override fun TextUnit.toDp(): Dp = (value * fontScale).dp

        override fun Dp.toSp(): TextUnit = (value / fontScale).sp
    }

    @Test
    fun `clock-xl is capped at 1_3x font scale`() {
        val max = CLOCK_XL_MAX_FONT_SCALE
        assertEquals(88.sp, cappedFontSize(88.sp, LinearDensity(fontScale = 1f), max))
        assertEquals(88.sp, cappedFontSize(88.sp, LinearDensity(fontScale = 1.2f), max))
        val double = LinearDensity(fontScale = 2f)
        val atDouble = cappedFontSize(88.sp, double, max)
        assertEquals(114.4f, with(double) { atDouble.toDp().value }, 0.1f)

        val capped = ppsTypography().withClockCap(double)
        assertEquals(atDouble, capped.clockXl.fontSize)
        assertEquals(16.sp, capped.body.fontSize)
    }

    @Test
    fun `the cap never renders clock-xl above 1_3x nor above its uncapped size`() {
        // Holds for whatever sp-to-dp conversion the density uses (linear, or Android 14+ non-linear).
        // The composed PpsTheme cap on a linear-scaling device is covered by ClockCapTest (androidApp, SDK 33).
        listOf(1f, 1.5f, 2f).forEach { scale ->
            val density = Density(1f, fontScale = scale)
            val capped = cappedFontSize(88.sp, density, CLOCK_XL_MAX_FONT_SCALE)
            with(density) { assertTrue(capped.toDp().value <= 88f * CLOCK_XL_MAX_FONT_SCALE + 0.1f, "at $scale") }
            assertTrue(capped.value <= 88f)
        }
    }

    private companion object {
        val SUNRISE_LIGHT_FALLBACKS = listOf("snoozed", "missed", "inverse-accent")
    }

    @Test
    fun `shapes use the locked radius tokens`() {
        val shapes = PpsShapes()
        val material = shapes.toMaterialShapes()
        assertEquals(shapes.sm, material.small)
        assertEquals(shapes.md, material.medium)
        assertEquals(shapes.lg, material.large)
        assertEquals(PpsTokens.Rounded.full, shapes.full.topStart)
    }

    @Test
    fun `spacing exposes the target sizes`() {
        val spacing = PpsSpacing()
        assertEquals(PpsTokens.Spacing.targetMin, spacing.targetMin)
        assertEquals(PpsTokens.Spacing.targetWake, spacing.targetWake)
        assertEquals(PpsTokens.Spacing.targetWakeHero, spacing.targetWakeHero)
    }
}
