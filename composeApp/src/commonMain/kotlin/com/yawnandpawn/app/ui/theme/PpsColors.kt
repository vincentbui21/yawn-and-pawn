package com.yawnandpawn.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Every colour role of DESIGN.md for one token set (Light, Dark or Sunrise). Screens read it
 * through `PpsTheme.colors` for roles Material 3 has no slot for (`accentText`, `snoozed`, ...).
 */
@Immutable
data class PpsColors(
    val bg: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val outline: Color,
    val outlineSubtle: Color,
    val text: Color,
    val textSecondary: Color,
    val accent: Color,
    val onAccent: Color,
    val accentText: Color,
    val success: Color,
    val snoozed: Color,
    val missed: Color,
    val error: Color,
    val disabledContainer: Color,
    val disabledContent: Color,
    val inverseSurface: Color,
    val inverseText: Color,
    val inverseAccent: Color,
    /**
     * Top of the screen background gradient (fades into [bg]): `gradient-top` in Light and Dark, `sunrise-gradient-top` on
     * wake screens. Accent never sits directly on it (DESIGN.md contrast table).
     */
    val gradientTop: Color,
    /** Translucent glass fill of cards over the background (`glass`). */
    val glass: Color,
    /** Denser glass for surfaces over moving content: the bottom pill, the nav bar and sheets (`glass-strong`). */
    val glassStrong: Color,
    /** The faint hairline edge of every glass surface (`glass-edge`, decorative). */
    val glassEdge: Color,
    /** Accent tint drawn over [glass] for the one highlighted card (the Progress streak card, `glass-accent`). */
    val glassAccent: Color,
    val isDark: Boolean,
)

val LightPpsColors: PpsColors =
    with(PpsTokens.Light) {
        PpsColors(
            bg = bg,
            surface = surface,
            surfaceVariant = surfaceVariant,
            outline = outline,
            outlineSubtle = outlineSubtle,
            text = text,
            textSecondary = textSecondary,
            accent = accent,
            onAccent = onAccent,
            accentText = accentText,
            success = success,
            snoozed = snoozed,
            missed = missed,
            error = error,
            disabledContainer = disabledContainer,
            disabledContent = disabledContent,
            inverseSurface = inverseSurface,
            inverseText = inverseText,
            inverseAccent = inverseAccent,
            gradientTop = gradientTop,
            glass = glass,
            glassStrong = glassStrong,
            glassEdge = glassEdge,
            glassAccent = glassAccent,
            isDark = false,
        )
    }

val DarkPpsColors: PpsColors =
    with(PpsTokens.Dark) {
        PpsColors(
            bg = bg,
            surface = surface,
            surfaceVariant = surfaceVariant,
            outline = outline,
            outlineSubtle = outlineSubtle,
            text = text,
            textSecondary = textSecondary,
            accent = accent,
            onAccent = onAccent,
            accentText = accentText,
            success = success,
            snoozed = snoozed,
            missed = missed,
            error = error,
            disabledContainer = disabledContainer,
            disabledContent = disabledContent,
            inverseSurface = inverseSurface,
            inverseText = inverseText,
            inverseAccent = inverseAccent,
            gradientTop = gradientTop,
            glass = glass,
            glassStrong = glassStrong,
            glassEdge = glassEdge,
            glassAccent = glassAccent,
            isDark = true,
        )
    }

/**
 * Sunrise (wake screens). DESIGN.md defines no `snoozed-sunrise`, `missed-sunrise` or
 * `inverse-accent-sunrise`: wake screens show no outcome history and snackbars there have no action.
 * Sunrise is a light set with the same text colours as Light, so those three roles use the Light tokens.
 */
val SunrisePpsColors: PpsColors =
    with(PpsTokens.Sunrise) {
        PpsColors(
            bg = bg,
            surface = surface,
            surfaceVariant = surfaceVariant,
            outline = outline,
            outlineSubtle = outlineSubtle,
            text = text,
            textSecondary = textSecondary,
            accent = accent,
            onAccent = onAccent,
            accentText = accentText,
            success = success,
            snoozed = PpsTokens.Light.snoozed,
            missed = PpsTokens.Light.missed,
            error = error,
            disabledContainer = disabledContainer,
            disabledContent = disabledContent,
            inverseSurface = inverseSurface,
            inverseText = inverseText,
            inverseAccent = PpsTokens.Light.inverseAccent,
            gradientTop = sunriseGradientTop,
            glass = glass,
            glassStrong = glassStrong,
            glassEdge = glassEdge,
            glassAccent = glassAccent,
            isDark = false,
        )
    }

/**
 * Maps the token set onto every Material 3 role, so no Material default (baseline purple) can leak
 * into a component. Roles DESIGN.md names map directly; the rest reuse a token whose pairing is
 * already in the DESIGN.md contrast table:
 * - primary / primaryContainer / primaryFixed* = accent with on-accent (one accent, three locks)
 * - secondary / tertiary = accent-text on surface; their containers and fixed roles = surface-variant with text
 * - surface containers: lowest and dim = bg, low / container / bright = surface, high / highest = surface-variant
 * - surfaceTint = surface, so tonal elevation adds no colour cast (surfaces separate by tone tokens)
 * - onError = surface, errorContainer = surface with error content
 * - scrim = text in light sets, bg in Dark (a dark veil in every set)
 */
fun PpsColors.toColorScheme(): ColorScheme =
    ColorScheme(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accent,
        onPrimaryContainer = onAccent,
        inversePrimary = inverseAccent,
        secondary = accentText,
        onSecondary = surface,
        secondaryContainer = surfaceVariant,
        onSecondaryContainer = text,
        tertiary = accentText,
        onTertiary = surface,
        tertiaryContainer = surfaceVariant,
        onTertiaryContainer = text,
        background = bg,
        onBackground = text,
        surface = surface,
        onSurface = text,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = textSecondary,
        surfaceTint = surface,
        inverseSurface = inverseSurface,
        inverseOnSurface = inverseText,
        error = error,
        onError = surface,
        errorContainer = surface,
        onErrorContainer = error,
        outline = outline,
        outlineVariant = outlineSubtle,
        scrim = if (isDark) bg else text,
        surfaceBright = surface,
        surfaceDim = bg,
        surfaceContainer = surface,
        surfaceContainerHigh = surfaceVariant,
        surfaceContainerHighest = surfaceVariant,
        surfaceContainerLow = surface,
        surfaceContainerLowest = bg,
        primaryFixed = accent,
        primaryFixedDim = accent,
        onPrimaryFixed = onAccent,
        onPrimaryFixedVariant = onAccent,
        secondaryFixed = surfaceVariant,
        secondaryFixedDim = surfaceVariant,
        onSecondaryFixed = text,
        onSecondaryFixedVariant = textSecondary,
        tertiaryFixed = surfaceVariant,
        tertiaryFixedDim = surfaceVariant,
        onTertiaryFixed = text,
        onTertiaryFixedVariant = textSecondary,
    )
